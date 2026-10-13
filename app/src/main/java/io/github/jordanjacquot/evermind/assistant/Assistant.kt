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
        val agenda = AgendaIA(rendezVous, maintenant)
        // D'abord les règles d'Evermind : immédiat, et toujours la même chose pour la même phrase
        val compris = Ordres(agenda, memoire, horaires()).comprendre(texte)
        if (compris != null) {
            val res = Actions(agenda, rappelsParDefaut, annulations).appliquer(compris, emptyMap(), texte)
            retenir(rendezVous, agenda, compris, maintenant)
            return res to agenda.liste.toList()
        }
        val moteur = moteur ?: return null
        val ctx = Consignes.contexte(agenda)
        if (!conversationRecente(maintenant)) conversation.clear()
        val messages = listOf(MessageIA("system", ctx.systeme)) + conversation.takeLast(6) + MessageIA("user", texte)
        var envoyes = messages
        var relance = false
        var reponse: ReponseIA
        while (true) {
            reponse = ReponseIA.lire(moteur.repondre(envoyes, SchemaIA)) ?: return null
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
