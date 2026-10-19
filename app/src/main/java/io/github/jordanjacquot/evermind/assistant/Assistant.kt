package io.github.jordanjacquot.evermind.assistant

import io.github.jordanjacquot.evermind.comprendre.Manque
import io.github.jordanjacquot.evermind.comprendre.contient
import io.github.jordanjacquot.evermind.comprendre.lirePhrase
import io.github.jordanjacquot.evermind.donnees.RendezVous
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.time.Duration
import java.time.LocalDateTime

/** Un message de la conversation avec l'IA. */
data class MessageIA(val role: String, val contenu: String)

/** Ce qui fait tourner l'IA : llama.cpp sur le téléphone, ou (pour les tests sur PC) le llama-server d'Evermind PC. */
fun interface MoteurIA {
    /** La réponse JSON (selon le schéma), ou null si l'IA n'a pas pu répondre. */
    suspend fun repondre(messages: List<MessageIA>, schema: String): String?
}

fun messagesEnJson(messages: List<MessageIA>): String =
    JsonArray(messages.map { buildJsonObject { put("role", JsonPrimitive(it.role)); put("content", JsonPrimitive(it.contenu)) } }).toString()

/**
 * L'assistant d'Evermind : ses règles (Ordres) comprennent tout de suite les demandes courantes ; pour le reste, l'IA
 * (comme sur PC, cerveau.ps1) propose ce qu'Evermind doit faire. Evermind vérifie tout avant d'agir et garde de quoi
 * revenir en arrière. Sans IA (petits téléphones), les règles suffisent.
 */
class Assistant(
    private val moteur: MoteurIA?,
    private val rappelsParDefaut: (RendezVous) -> List<String>,
    private val horaires: () -> Map<String, String> = { HorairesDefaut },
    /** Les règles d'Evermind d'abord (false : l'IA seule, pour la comparer aux règles). */
    private val regles: Boolean = true,
    /**
     * Quand la réponse des règles paraît douteuse, demander aussi à l'IA et garder la meilleure. Seulement avec une IA
     * assez solide (le plus petit modèle se trompe plus souvent que les règles qu'il contrôlerait).
     */
    private val controle: () -> Boolean = { true },
    /** Les consignes données à l'IA : les longues (modèles généraux) ou les courtes (le modèle entraîné pour Evermind). */
    private val consignes: () -> (AgendaIA) -> Contexte = { Consignes::contexte },
    /** Change les heures d'un poste (« mon poste du matin, c'est de 7 h à 15 h ») : le poste, puis « 7 h à 15 h ». */
    private val changerHoraire: ((String, String) -> Unit)? = null,
) {
    // Mémoire de la conversation : les 3 derniers échanges, s'ils datent de moins de 3 minutes
    private val conversation = mutableListOf<MessageIA>()
    private var conversationQuand: LocalDateTime? = null
    private val annulations = mutableListOf<List<RendezVous>>()
    private val memoire = MemoireOrdres()

    private fun conversationRecente(maintenant: LocalDateTime) =
        conversation.isNotEmpty() && conversationQuand?.let { Duration.between(it, maintenant).toMinutes() < 3 } == true

    fun oublier() { conversation.clear(); conversationQuand = null; memoire.oublier() }

    /** L'agenda d'avant la dernière action de l'assistant (« Annuler »), ou null s'il n'y a rien à annuler. */
    fun annulerDerniere(): List<RendezVous>? = annulations.removeLastOrNull()

    /**
     * Une demande (« décale le dentiste à jeudi ») sur cet agenda. Rend ce qu'Evermind dit et le nouvel agenda ;
     * null si ni les règles ni l'IA n'ont compris.
     */
    suspend fun demander(texte: String, rendezVous: List<RendezVous>, maintenant: LocalDateTime = LocalDateTime.now()): Pair<Resultat, List<RendezVous>>? {
        // « Mon poste du matin, c'est de 7 h à 15 h » : un réglage, pas un rendez-vous
        val reglage = if (changerHoraire != null) reglageHoraire(texte) else null
        if (reglage != null) {
            changerHoraire!!(reglage.first, reglage.second)
            val d = "C'est noté : le poste ${PostesNoms[reglage.first]}, c'est maintenant de ${reglage.second}."
            return Resultat(d, d, null, false) to rendezVous
        }
        // Une phrase longue et parlée (« euh du coup je voudrais qu'on me rappelle le jour avant la soirée chez max... ») :
        // le modèle entraîné la comprend mieux que les règles, il répond d'abord ; sa réponse passe les mêmes contrôles, et
        // s'il échoue, les règles reprennent la main. Les ordres courts, et les phrases construites que les règles
        // maîtrisent (la veille de, deux actions, une liste...), restent aux règles (docs/MESURES.md, étape 2 bis).
        if (regles && controle() && moteur != null && pourLeModele(texte)) {
            val avantM = memoire.copie()
            val avantA = annulations.toList()
            val parIA = runCatching { demanderIA(texte, rendezVous, maintenant) }.getOrNull()
            if (parIA != null && doute(texte, null, parIA.first, rendezVous, parIA.second) == null && douteIA(texte, rendezVous, parIA.second, false) == null) return parIA
            if (System.getenv("EVERMIND_TRACE") != null) println("LONGUE « $texte » : les règles reprennent")
            memoire.reprendre(avantM)
            annulations.clear(); annulations += avantA
        }
        val agenda = AgendaIA(rendezVous, maintenant)
        // D'abord les règles d'Evermind : immédiat, et toujours la même chose pour la même phrase
        // (une erreur dans les règles ne doit jamais faire planter l'app : la phrase est alors « pas comprise »)
        val avantMemoire = memoire.copie()
        val avantAnnulations = annulations.toList()
        val compris = if (!regles) null else runCatching { Ordres(agenda, memoire, horaires()).comprendre(texte) }.getOrNull()
        if (compris != null) {
            val res = Actions(agenda, rappelsParDefaut, annulations).appliquer(compris, emptyMap(), texte)
            retenir(rendezVous, agenda, compris, maintenant)
            // « Crée un rendez-vous chez le garagiste » sans dire quand : la phrase attend son jour (« lundi à 8 h »)
            memoire.incomplet = res.aCorriger
            val parRegles = res to agenda.liste.toList()
            // Une réponse douteuse : l'IA essaie aussi ; la sienne est gardée si elle n'a pas l'air douteuse
            val raison = doute(texte, compris, res, rendezVous, agenda.liste)
            if (System.getenv("EVERMIND_TRACE") != null && raison != null) println("DOUTE « $texte » : $raison")
            if (!controle() || moteur == null || raison == null) return parRegles
            val apresRegles = memoire.copie()
            val annulationsRegles = annulations.toList()
            memoire.reprendre(avantMemoire)
            annulations.clear(); annulations += avantAnnulations
            val parIA = runCatching { demanderIA(texte, rendezVous, maintenant) }.getOrNull()
            // (l'IA n'est gardée que si sa réponse tient debout : ses titres viennent de la phrase, elle ne supprime pas
            // sans qu'on le demande, et elle fait quelque chose là où les règles agissaient)
            val changeRegles = agendaDiffere(rendezVous, parRegles.second)
            if (parIA != null && doute(texte, null, parIA.first, rendezVous, parIA.second) == null && douteIA(texte, rendezVous, parIA.second, changeRegles) == null) return parIA
            memoire.reprendre(apresRegles)
            annulations.clear(); annulations += annulationsRegles
            return parRegles
        }
        // Les règles n'ont rien compris : la réponse de l'IA est vérifiée de la même façon (sinon rien n'est touché)
        val parIA = demanderIA(texte, rendezVous, maintenant) ?: return null
        if (doute(texte, null, parIA.first, rendezVous, parIA.second) != null && agendaDiffere(rendezVous, parIA.second) ||
            douteIA(texte, rendezVous, parIA.second, false) != null) {
            if (System.getenv("EVERMIND_TRACE") != null) println("REJET IA « $texte » : ${doute(texte, null, parIA.first, rendezVous, parIA.second) ?: douteIA(texte, rendezVous, parIA.second, false)}")
            memoire.reprendre(avantMemoire)
            annulations.clear(); annulations += avantAnnulations
            return null
        }
        return parIA
    }

    /**
     * Une phrase pour le modèle d'abord : longue (plus de 10 mots, sans « euh », « du coup », « s'il te plaît »), parlée
     * (« euh », « du coup », « je voudrais », « tu pourrais »...), et sans ce que les règles font mieux que lui : une
     * date relative (la veille, deux jours avant, le deuxième mardi), plusieurs actions, une liste, une hypothèse, une
     * hésitation (« peut-être », « mais pas maintenant », « je ne suis pas sûr » : rien à faire, les règles le savent).
     */
    private fun pourLeModele(texte: String): Boolean {
        val n = sansAccents(texte.lowercase().replace('’', '\''))
        val utiles = n.replace(Regex("\\b(?:euh|bon|du coup|alors|s'il te plait|s'il vous plait|stp|svp)\\b"), " ")
        if (Regex("[\\p{L}\\d']+").findAll(utiles).count() <= 10) return false
        if (n.contient("\\b(?:peut-etre|pas maintenant|pas tout de suite|pas encore|plus tard|un jour|je me demande|je me pose la question|pas sur|pas certain|je sais pas si|je ne sais pas si|j'hesite|j'aurais aime|j'aurais voulu)\\b")) return false
        if (!n.contient("\\b(?:euh|du coup|je voudrais|j'aimerais|je veux|tu pourrais|je pense|il faudrait|faudrait|faut que|parce que|je te demande|je me dis|genre|bon|alors|oui|ah)\\b")) return false
        val verbe = "(?:mets|met|ajoute|rajoute|supprime|efface|decale|deplace|rappelle|previens|marque|annule|enleve)"
        return !n.contient("\\b(?:la veille|le lendemain|avant|apres|meme jour|premier|premiere|deuxieme|troisieme|dernier|derniere|" +
            "dans (?:\\d+|deux|trois|quatre|une|un) (?:semaines?|jours?|mois|heures?)|plus tot|plus tard|si je|si on|sauf|puis|ensuite|non,|en fait,|plutot|" +
            // (les choses à prendre : le modèle en fait parfois une note)
            "prenne|prendre|apporte|apporter|ramene|ramener|n'oublie pas)\\b|" +
            ",\\s*(?:et\\s+)?$verbe\\b|\\bet\\s+$verbe\\b|:")
    }

    /**
     * Ce qui rend une réponse douteuse (null si elle a l'air bonne) : rien de compris, un titre créé fait de bouts de
     * phrase, un « c'est changé » qui ne change rien, beaucoup de suppressions sans que « tout » ait été dit.
     */
    fun doute(texte: String, compris: ReponseIA?, res: Resultat, avant: List<RendezVous>, apres: List<RendezVous>): String? {
        val dit = res.dit
        if (dit.contient("je n'ai pas compris|je n'ai pas trouvé|je ne suis pas sûr|tu peux reformuler|Je ne trouve pas")) return "pas compris"
        val ids = avant.associateBy { it.id }
        val nouveaux = apres.filter { it.id !in ids }
        for (n in nouveaux) {
            // (un titre fait seulement de petits mots : « Le », « C'est »)
            if (sansAccents(n.titre).split(Regex("[\\s']+")).all { it.length <= 3 || it in setOf("c'est", "cest", "est", "sont", "avec", "pour", "dans") }) return "titre vide « ${n.titre} »"
            val mots = n.titre.split(Regex("\\s+"))
            val sansRdv = n.titre.replace(Regex("(?i)rendez-vous"), "rdv")
            if (mots.size > 8 || sansRdv.contient("\\b(?:je|j'|tu|il|on|nous|vous|mets|met|ajoute|rajoute|faut|veux|dois|peux|stp|donc|alors|plut[ôo]t|finalement|aussi|que|qui|quand)\\b")) return "titre « ${n.titre} »"
        }
        val partis = avant.filter { a -> apres.none { it.id == a.id } }
        // (une série supprimée en entier alors que la phrase dit lesquelles : « les deux prochains raids »)
        if (partis.any { it.repetition != null } && texte.contient("\\b(?:prochains?|prochaines?|deux|trois|quatre|cinq|seulement|juste|uniquement|de demain|cette semaine|ce mois|en (?:janvier|f[ée]vrier|mars|avril|mai|juin|juillet|ao[uû]t|septembre|octobre|novembre|d[ée]cembre))\\b") &&
            !texte.contient("\\b(?:tous|toutes|tout|toujours|d[ée]finitivement|la s[ée]rie)\\b")) return "série entière supprimée"
        if (partis.size >= 3 && !texte.contient("\\b(?:tout|tous|toutes|vide|agenda)\\b") && partis.any { !nomme(texte, it) }) return "${partis.size} suppressions"
        if (compris != null && compris.actions.any { it.action == "modifier" && vide(it) } && nouveaux.isEmpty() && partis.isEmpty() &&
            !agendaDiffere(avant, apres)) return "rien de changé"
        // « C'est changé : Foot, samedi 17 à 19 h » alors que rien n'a changé (on demandait dimanche) : pas compris
        // (le jour dit est celui de la phrase : le rendez-vous y était déjà, rien à changer)
        if (dit.startsWith("C'est changé") && !agendaDiffere(avant, apres)) {
            val jours = Regex("lundi|mardi|mercredi|jeudi|vendredi|samedi|dimanche").findAll(sansAccents(dit)).map { it.value }.toList()
            if (jours.none { sansAccents(texte).contains(it) }) return "« c'est changé » sans changement"
        }
        return null
    }

    /** La phrase nomme-t-elle ce rendez-vous ? (un mot de son titre) */
    private fun nomme(texte: String, rv: RendezVous): Boolean {
        val t = sansAccents(texte)
        return motsTitre(rv.titre).any { t.contains(it) } || sansAccents(rv.titre).split(' ').any { it.length >= 3 && t.contains(it) }
    }

    /** Une modification qui ne dit rien (aucun détail lu). */
    private fun vide(a: ActionIA) = a.phrase == null && a.titre == null && a.lieu == null && a.notes == null && a.personnes.isEmpty() &&
        a.chosesAPrendre.isEmpty() && a.important == null && a.rappels.isEmpty() && a.retirerPersonnes.isEmpty() && a.retirerRappels.isEmpty() &&
        a.retirerChoses.isEmpty() && a.chosesFaites.isEmpty() && a.chosesNonFaites.isEmpty() && a.effacer.isEmpty() && a.lien == null && a.remplacerChose == null

    private fun agendaDiffere(avant: List<RendezVous>, apres: List<RendezVous>) =
        avant.size != apres.size || avant.any { a -> apres.firstOrNull { it.id == a.id } != a }

    /** Ce qui rend la réponse de l'IA douteuse, comparée à la phrase et à ce que les règles ont fait. */
    fun douteIA(texte: String, avant: List<RendezVous>, apres: List<RendezVous>, changeRegles: Boolean): String? {
        val ids = avant.map { it.id }.toSet()
        val nouveaux = apres.filter { it.id !in ids }
        nouveaux.firstOrNull { !titreDit(it.titre, texte) }?.let { return "titre inventé « ${it.titre} »" }
        val partis = avant.filter { a -> apres.none { it.id == a.id } }
        if (partis.isNotEmpty() && !intentions(texte).supp && !texte.contient("\\b(?:annul|vire|enl[èe]v|retir|efface|d[ée]gage|plus besoin)") &&
            // (« je ne pourrai pas venir chez Max lundi » : il faut l'enlever)
            !texte.contient("\\b(?:pourrai|pourrais|peux|pourra|serai|irai|vais|viendrai)\\s+pas\\s+(?:venir|y aller|aller|[eê]tre l[àa]|y [eê]tre)\\b|\\bne viendrai pas\\b")) return "suppression non demandée"
        // (jamais un rendez-vous que la phrase ne nomme pas, sauf « tout »)
        if (partis.any { !nomme(texte, it) } && !texte.contient("\\b(?:tout|tous|toutes|vide)\\b")) return "suppression d'un rendez-vous non nommé"
        if (changeRegles && !agendaDiffere(avant, apres)) return "rien fait"
        return null
    }

    /** La demande à l'IA (comme sur PC), quand les règles n'ont pas compris ou que leur réponse est douteuse. */
    private suspend fun demanderIA(texte: String, rendezVous: List<RendezVous>, maintenant: LocalDateTime): Pair<Resultat, List<RendezVous>>? {
        val agenda = AgendaIA(rendezVous, maintenant)
        val moteur = moteur ?: return null
        val ctx = consignes()(agenda)
        if (!conversationRecente(maintenant)) conversation.clear()
        val messages = listOf(MessageIA("system", ctx.systeme)) + conversation.takeLast(6) + MessageIA("user", texte)
        var envoyes = messages
        var relance = false
        var reponse: ReponseIA
        while (true) {
            val brut = moteur.repondre(envoyes, SchemaIA)
            if (System.getenv("EVERMIND_TRACE") != null) println("IA « $texte » : $brut")
            reponse = ReponseIA.lire(brut) ?: return null
            val decision = decider(texte, reponse, relance, ctx.codes, agenda, conversationRecente(maintenant))
            if (decision.first != null && !relance) {
                relance = true
                envoyes = messages + MessageIA("assistant", reponse.versJson()) + MessageIA("user", decision.first!!)
                continue
            }
            reponse = decision.second!!
            break
        }
        // Retenu pour la phrase suivante (« lequel ? » puis « le dentiste »)
        conversation += MessageIA("user", texte)
        conversation += MessageIA("assistant", reponse.versJson())
        conversationQuand = maintenant
        val res = Actions(agenda, rappelsParDefaut, annulations).appliquer(reponse, ctx.codes, texte)
        retenir(rendezVous, agenda, reponse, maintenant)
        return res to agenda.liste.toList()
    }

    /** Le rendez-vous dont on vient de parler (créé, ou touché), pour « supprime-le » juste après. */
    private fun retenir(avant: List<RendezVous>, agenda: AgendaIA, rep: ReponseIA, maintenant: LocalDateTime) {
        val ids = avant.map { it.id }.toSet()
        val nouveau = agenda.liste.firstOrNull { it.id !in ids }
        val touche = rep.actions.lastOrNull { it.cible != null && agenda.get(it.cible!!.id) != null }?.cible
        (nouveau ?: touche)?.let { memoire.dernier = it.id }
        memoire.quand = maintenant
    }

    /**
     * Que faire de la réponse de l'IA (Get-DecisionIA) : (correction, null) pour lui redemander en lui signalant son
     * erreur, ou (null, réponse) à appliquer.
     */
    private fun decider(texte: String, rep: ReponseIA, relance: Boolean, codes: Map<String, String>, agenda: AgendaIA, recente: Boolean): Pair<String?, ReponseIA?> {
        fun appliquer(r: ReponseIA): Pair<String?, ReponseIA?> = null to r
        fun creerPhrase() = appliquer(ReponseIA(listOf(ActionIA("creer", phrase = texte)), ""))
        // « Je suis du matin lundi et mardi, de nuit jeudi » : des postes de travail, lus par les règles d'Evermind
        val hor = horairesTravail(texte, agenda.maintenant, horaires())
        val inH = intentions(texte)
        if (hor != null && !inH.supp && !inH.modif && !inH.question) {
            return appliquer(ReponseIA(listOf(ActionIA("horaires").apply { postes = hor }), ""))
        }
        // « Supprime l'événement » sans dire lequel : on demande
        questionVague(texte, codes, agenda, recente)?.let { return appliquer(ReponseIA(emptyList(), it)) }
        // L'action ne correspond pas au verbe : on lui redemande une fois ; si elle se trompe encore, rien n'est touché
        val ecart = ecartAction(texte, rep.actions, codes, agenda)
        if (ecart != null) {
            if (!relance) return ecart to null
            val inn = intentions(texte)
            if (inn.creer && !inn.supp && !inn.modif && !inn.question) {
                val p = lirePhrase(texte, agenda.maintenant)
                if (p.manque == Manque.RIEN && p.titre.isNotEmpty()) return creerPhrase()
            }
            if (inn.question) return appliquer(ReponseIA(emptyList(), rep.reponse))
            return appliquer(ReponseIA(emptyList(), "Je ne suis pas sûr d'avoir bien compris, je n'ai rien touché. Tu peux le redire autrement ?"))
        }
        val sansAction = rep.actions.isEmpty()
        // Un rendez-vous dit tel quel, sans verbe (« dentiste jeudi à 15 h ») : comme dans la barre, il est noté
        if (sansAction && !inH.question && !inH.supp && !inH.modif) {
            val p = lirePhrase(texte, agenda.maintenant)
            if (p.manque == Manque.RIEN && p.titre.isNotEmpty() && p.quand != null && p.titre.split(Regex("\\s+")).size <= 6 && titreDit(p.titre, texte)) return creerPhrase()
        }
        // Réponse qui dit « c'est fait » sans aucune action : on lui redemande une fois en le lui signalant
        if (sansAction && rep.reponse.contient("\\b(c'est (bien )?noté|j'ai (bien |déjà )?(noté|ajouté|créé|mis|supprimé|effacé|enlevé|décalé|déplacé|changé|modifié|annulé|programmé|planifié)|c'est fait|est (bien )?(supprimé|décalé|ajouté|noté|programmé|planifié))")) {
            if (!relance) return "Tu as répondu que c'était fait, mais \"actions\" est vide : rien n'a été fait. Redonne le JSON avec la bonne action." to null
            val p = lirePhrase(texte, agenda.maintenant)
            if (p.manque == Manque.RIEN && texte.contient("\\b(cr[ée]{1,2}[rsz]?|ajoute[rsz]?|note[rsz]?|met[st]?|mettre|programme[rsz]?)\\b") &&
                !texte.contient("\\b(supprime|efface|enl[èe]ve|retire|d[ée]cale|d[ée]place|change|modifie)")) return creerPhrase()
        }
        return appliquer(rep)
    }
}
