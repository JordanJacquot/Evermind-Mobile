package io.github.jordanjacquot.evermind.assistant

import io.github.jordanjacquot.evermind.comprendre.Manque
import io.github.jordanjacquot.evermind.comprendre.Periode
import io.github.jordanjacquot.evermind.comprendre.RxJour
import io.github.jordanjacquot.evermind.comprendre.RxMois
import io.github.jordanjacquot.evermind.comprendre.contient
import io.github.jordanjacquot.evermind.comprendre.lirePeriode
import io.github.jordanjacquot.evermind.comprendre.lirePhrase
import io.github.jordanjacquot.evermind.comprendre.nombre
import io.github.jordanjacquot.evermind.comprendre.remplace
import io.github.jordanjacquot.evermind.comprendre.tous
import io.github.jordanjacquot.evermind.comprendre.trouve
import io.github.jordanjacquot.evermind.donnees.RendezVous
import io.github.jordanjacquot.evermind.donnees.Sorte
import io.github.jordanjacquot.evermind.donnees.laRepetition
import io.github.jordanjacquot.evermind.ui.enTexte
import io.github.jordanjacquot.evermind.ui.enTexteLong
import io.github.jordanjacquot.evermind.ui.majuscule
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

// Ce qu'Evermind comprend tout seul, sans IA : supprimer, décaler, changer un détail, « c'est fait », « annule », les
// questions sur l'agenda, et deux demandes dans une phrase. C'est immédiat et toujours pareil ; l'IA ne sert plus
// qu'aux phrases que ces règles ne reconnaissent pas.

/** Ce dont Evermind se souvient d'une demande à l'autre (« supprime l'événement », puis « le foot »). */
class MemoireOrdres {
    /** Le dernier rendez-vous créé ou touché (« supprime-le »). */
    var dernier: String? = null
    /** La demande qui attend de savoir quel rendez-vous elle vise. */
    var attente: String? = null
    var quand: LocalDateTime? = null

    fun recente(maintenant: LocalDateTime) = quand?.let { Duration.between(it, maintenant).toMinutes() < 3 } == true

    fun oublier() { dernier = null; attente = null; quand = null }
}

/** Un mot de la phrase (sans majuscules ni accents) et sa place. */
private class Mot(val texte: String, val debut: Int, val fin: Int)

private fun mots(s: String) = Regex("[a-z0-9]+'?").findAll(s).map { Mot(it.value, it.range.first, it.range.last + 1) }.toList()

/** Un rendez-vous nommé dans la phrase : par quels mots, et avec quel article devant (« le foot » ou « un foot »). */
private class Cible(
    val rv: RendezVous, val score: Int, val premier: Int, val dernier: Int, val defini: Boolean, val indefini: Boolean,
    /** Les mots qui l'ont désigné autrement que par son nom (« le rendez-vous de 19 h ») : pas une nouvelle heure. */
    val masque: IntRange? = null,
)

private val Definis = setOf("le", "la", "les", "l'", "mon", "ma", "mes", "ton", "ta", "tes", "du", "au", "aux", "ce", "cette", "cet", "son", "sa", "ses", "notre", "votre")
private val Indefinis = setOf("un", "une", "nouveau", "nouvel", "nouvelle", "autre", "second", "seconde", "deuxieme")
private val MotsVides = setOf("les", "des", "une", "avec", "chez", "pour", "rendez", "vous", "faire", "tous", "toutes", "evenement",
    "aux", "mon", "mes", "son", "ses", "sur", "dans", "par", "que", "qui", "est", "the", "and")
/** Des mots qui ne suffisent pas à reconnaître un rendez-vous si un autre est nommé plus clairement. */
private val MotsFaibles = setOf("anniversaire", "reunion", "rappel", "appel")
/** « le rendez-vous de jeudi », « le truc du 12 » : un rendez-vous désigné par sa date. */
private const val RxGenerique = "\\b(?:rendez-vous|rendez vous|rdv|truc|machin|evenement|event|rappel|chose|activite|creneau)s?\\b"

private fun clesTitre(titre: String) = mots(sansAccents(titre)).map { it.texte.trimEnd('\'') }
    .filter { it.length >= 3 && it !in MotsVides && !it.all(Char::isDigit) }.distinct()

/** Deux mots presque pareils (pluriel, faute de dictée) : « raids » pour « raid », « appele » pour « appeler ». */
private fun proche(a: String, b: String): Boolean {
    if (a == b) return true
    if (b.length >= 3 && (a == b + "s" || a == b + "x")) return true
    if (b.length >= 5 && a.length >= 4 && a[0] == b[0]) return distance(a, b) <= if (b.length >= 7) 2 else 1
    return false
}

private fun distance(a: String, b: String): Int {
    var prec = IntArray(b.length + 1) { it }
    for (i in 1..a.length) {
        val cour = IntArray(b.length + 1)
        cour[0] = i
        for (j in 1..b.length) cour[j] = minOf(prec[j] + 1, cour[j - 1] + 1, prec[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
        prec = cour
    }
    return prec[b.length]
}

/** Sans majuscules ni accents, apostrophes droites : pour reconnaître les mots (même longueur que la phrase). */
private fun net(t: String) = sansAccents(t.replace('’', '\'')).let { if (it.length == t.length) it else t.lowercase() }

// Les verbes, lus sur la phrase sans accents
private const val Pas = "(?<!\\b(?:pour|de|a)\\s)(?<!\\bd')"
private const val RxSupp = "$Pas\\b(?:supprim|suprim|sucrim|effac|enlev|retir|vire\\b|virer|virez|degag)\\w*"
private const val RxModif = "$Pas\\b(?:decal|decad|deplac|repouss|recul|retard|avanc|report|bouge|chang|modifi|renomm|rebaptis)\\w*"
private const val RxCreer = "\\b(?:cree|creer|crees|creez|cre|ajout|rajout|mets|met|mettre|mettes|mettez|note|noter|notes|programm|planifi|inscri|rappelle|rappel|oubli|pense|faut que je|faudrait que je|dois|fais-moi|fait-moi|fais moi|reserve|bloque)\\w*"
/** Verbes avant « -le » qui renvoient au rendez-vous dont on vient de parler. */
private const val RxPronom = "\\b(?:supprim|suprim|effac|enlev|retir|vir|decal|deplac|met|repouss|avanc|recul|chang|boug|pass|annul|renomm|marqu|fai)\\w*-(?:le|la|les)\\b|\\b(?:le|la|l')\\s*(?:supprimer|effacer|enlever|decaler|deplacer|mettre|repousser|avancer|annuler|changer|bouger)\\b"
/** Pour couper « supprime le foot et ajoute un tennis » en deux demandes. */
private const val RxSuite = "\\s*,?\\s+(?:et puis|et ensuite|et|puis|ensuite)\\s+(?=(?:ajoute|rajoute|mets|met|supprime|efface|enleve|retire|decale|deplace|repousse|avance|recule|previens|rappelle|cree|note|change|renomme|marque|invite|programme|annule|passe|bouge|vire|fais)\\b)"

private val FormatJourAn = DateTimeFormatter.ofPattern("d MMMM yyyy", Fr)

/** Ce qu'une partie de la phrase demande : des actions, ou ce qu'Evermind répond. */
private class Lu(val actions: List<ActionIA> = emptyList(), val dit: String? = null, val attente: String? = null)

class Ordres(private val agenda: AgendaIA, private val memoire: MemoireOrdres, private val horaires: Map<String, String> = HorairesDefaut) {
    private val maintenant get() = agenda.maintenant
    private val today get() = agenda.aujourdHui

    /** La demande comprise par les règles (actions, ou réponse), ou null si elles ne la reconnaissent pas. */
    fun comprendre(texte: String): ReponseIA? {
        var t = texte.trim().replace('’', '\'')
        // « Étends les vacances... non, mets le foot sur deux jours » : seulement ce qui vient après la correction
        trouve(t, "^.+?(?:\\.\\.\\.|…|,)\\s*(?:non|enfin|pardon)\\s*,?\\s+(?<r>.{6,})$").takeIf { it.ok && !net(t).contient("^\\s*non\\b") }?.let { t = it.g("r")!! }
        t = t.remplace("^(?:(?:euh|bon|alors|donc|ok|okay|ben|bah|eh bien|genre|finalement|du coup|en fait)\\s*,?\\s+)+", "")
            .remplace("\\s*,?\\s*\\b(?:s'il te pla[iî]t|s'il vous pla[iî]t|stp|svp|steuplait|merci)\\b\\s*", " ")
            .remplace("\\b(?:genre|euh|tu vois)\\b\\s*,?\\s*", "").trim()
        if (t.isEmpty()) return null
        val s = net(t)
        val recente = memoire.recente(maintenant)
        if (!recente) memoire.oublier()

        discussion(s)?.let { return ReponseIA(emptyList(), it) }
        // « Annule », « non, reviens en arrière » : défaire la dernière action
        if (s.contient("^(?:non\\s*,?\\s*|en fait\\s*,?\\s*)?(?:annule(?:r)?|reviens en arriere|retour en arriere|defais(?:-le)?|annule (?:ca|cela|tout ca|la derniere (?:action|chose|modification)|ce que tu (?:viens|as) (?:de |d')?\\w+(?: de \\w+)?)|laisse tomber ce que tu (?:viens|as) \\w+(?: de faire)?)\\s*[.!]*$")) {
            return ReponseIA(listOf(ActionIA("annuler").apply { sure = true }), "")
        }
        // « Je suis du matin lundi et mardi » : des postes de travail
        val inn = intentions(t)
        if (!inn.supp && !inn.modif && !inn.question) {
            horairesTravail(t, maintenant, horaires)?.let { return ReponseIA(listOf(ActionIA("horaires").apply { postes = it; sure = true }), "") }
        }
        // « Supprime mes rendez-vous de la semaine prochaine »
        suppressionsPeriode(t, agenda)?.let { l -> return ReponseIA(l.onEach { it.sure = true }, if (l.isEmpty()) "Tu n'as rien à ce moment-là, je n'ai rien supprimé." else "") }
        // « Supprime tout », « vide mon agenda », « supprime tous les anniversaires »
        suppressionGroupe(s)?.let { return it }
        // Une question : on répond, sans rien toucher
        if (estQuestion(s)) return question(t, s)?.let { ReponseIA(emptyList(), it) }

        // « Supprime l'événement » puis « le foot » : la demande en attente vise ce rendez-vous
        val attente = memoire.attente
        if (attente != null && recente && !s.contient(RxSupp) && !s.contient(RxModif) && !s.contient(RxCreer)) {
            val c = chercher(mots(s)).singleOrNull() ?: parDate(t, s)
            if (c != null) {
                memoire.attente = null
                val lu = partie(attente, null, c) ?: return null
                return enReponse(listOf(lu))
            }
        }

        // Deux demandes dans une phrase (« supprime le coiffeur et mets le dentiste à 16 h »)
        val coupes = Regex(RxSuite).findAll(s).map { it.range }.toList()
        val parties = mutableListOf<String>()
        var debut = 0
        for (r in coupes) { parties += t.substring(debut, r.first); debut = r.last + 1 }
        parties += t.substring(debut)
        val lus = mutableListOf<Lu>()
        var precedente: RendezVous? = null
        for ((i, p) in parties.withIndex()) {
            val lu = partie(p.trim(' ', ',', '.'), precedente, null, seule = parties.size == 1, phraseEntiere = t) ?: return null
            lus += lu
            precedente = lu.actions.lastOrNull()?.cible ?: precedente
            // « Supprime le ElleFest du 29 et mets-le sur 3 jours » : c'est un changement, pas une suppression
            if (i > 0 && lus[i - 1].actions.singleOrNull()?.action == "supprimer" && lu.actions.any { it.action == "modifier" && it.cible?.id == lus[i - 1].actions[0].cible?.id }) {
                lus[i - 1] = Lu()
            }
        }
        lus.firstOrNull { it.attente != null }?.let { memoire.attente = it.attente; memoire.quand = maintenant }
        return enReponse(lus)
    }

    private fun enReponse(lus: List<Lu>): ReponseIA {
        val actions = lus.flatMap { it.actions }.onEach { it.sure = true }
        return ReponseIA(actions, lus.mapNotNull { it.dit }.joinToString(" "))
    }

    // ---------------------------------------------------------------------------
    // Une demande : supprimer, c'est fait, changer, ou créer
    // ---------------------------------------------------------------------------
    private fun partie(t: String, precedente: RendezVous?, imposee: Cible?, seule: Boolean = true, phraseEntiere: String = t): Lu? {
        if (t.isBlank()) return Lu()

        val s = net(t)
        val m = mots(s)
        val cibles = imposee?.let { listOf(it) } ?: chercher(m)
        val pronom = s.contient(RxPronom)
        val supp = s.contient(RxSupp) || s.contient("\\bannul\\w*\\s+(?:le|la|les|l'|mon|ma|mes|ce|cette|tous|toutes|tout)\\s*(?!derni|ce que|ca\\b|cela)\\w") ||
            s.contient("\\b(?:est|sont)\\s+annule|\\bn'a plus lieu|\\bn'aura pas lieu|\\bne (?:vais|va) plus\\b|\\bn'y vais plus|\\blaisse tomber\\s+(?:le|la|les|l'|mon|ma)") ||
            (imposee != null && s.contient("\\bannul"))
        val creer = s.contient(RxCreer)
        // « Supprime le dentiste et le foot » : plusieurs rendez-vous nommés à la suite, la même chose pour chacun
        if (imposee == null) {
            val plusieurs = enumeres(m, s)
            if (plusieurs.size >= 2) {
                return when {
                    supp -> Lu(plusieurs.map { x -> ActionIA("supprimer").apply { cible = x.rv } })
                    estFait(s) -> Lu(plusieurs.map { x -> ActionIA("fait").apply { cible = x.rv } })
                    creer && plusieurs.none { it.defini } -> null
                    else -> Lu(plusieurs.map { x -> (modification(t, s, x) ?: return null).apply { cible = x.rv } })
                }
            }
        }
        // Le rendez-vous visé : nommé, sinon désigné par sa date, sinon « le », celui dont on vient de parler
        var c: Cible? = when {
            cibles.size == 1 -> cibles[0]
            cibles.size > 1 -> departager(cibles, t) ?: return Lu(dit = lequel(cibles.map { it.rv }), attente = t)
            else -> null
        }
        // (« crée l'anniversaire de Paul » ne vise pas celui de Léa)
        if (c != null && c.score < 10 && creer && imposee == null) c = null
        val generiqueDefini = s.contient("\\b(?:le|l'|ce|cet|cette|mon|ma|mes|les)\\s*$RxGenerique")
        if (c == null && (generiqueDefini || ((supp || s.contient(RxModif)) && !creer))) c = designe(t, s)
        if (c == null && pronom) {
            val r = precedente ?: memoire.dernier?.let { agenda.get(it) }
            if (r != null) c = Cible(r, 1, -1, -1, true, false)
        }
        // « Crée un rendez-vous chez le dentiste pour mon fils », « j'ai une soirée chez Julie » : un nouveau, pas celui qui existe
        if (c != null && imposee == null && !supp) {
            val objet = trouve(s, "\\b(?:ajout|rajout|cree|creer|mets|met|mettre|programm|planifi|note|fais|reserve|j'ai|il y a|y a)\\S*\\s+(?:moi\\s+|-moi\\s+)?(?:un|une)\\s+(?<n>[a-z'-]+)")
            val detail = objet.g("n")?.let { it.contient("^(?:notes?|precision|commentaire|info|information|personne)$") || (it == "rappel" && rappelsTexte(t).isNotEmpty()) } ?: true
            if (objet.ok && !detail && (c.premier < 0 || mots(s).indexOfFirst { it.debut >= objet.index } <= c.premier)) c = null
        }
        // « …, et rappelle-moi une heure avant », « et ajoute Tom » : la suite parle du même rendez-vous
        val lp0 = lirePhrase(t, maintenant)
        if (c == null && precedente != null && imposee == null &&
            (!creer || rappelsTexte(t).isNotEmpty() || (lp0.jour == null && lp0.heure == null && lp0.repetition == null))) c = Cible(precedente, 1, -1, -1, true, false)
        if (c == null && precedente != null && trouve(t, "^(?:[Aa]joute|[Rr]ajoute|[Ii]nvite)\\s+[A-ZÉÈÀ][\\p{L}-]+(?:\\s*(?:,|et)\\s*[A-ZÉÈÀ][\\p{L}-]+)*\\s*[.!]?$", ci = false).ok) {
            c = Cible(precedente, 1, -1, -1, true, false)
        }

        // --- Supprimer (ou retirer quelqu'un)
        if (supp) {
            val rv = c?.rv ?: return introuvable(t, s)
            val gens = retires(s, rv)
            if (gens.isNotEmpty()) return Lu(listOf(ActionIA("modifier", retirerPersonnes = gens).apply { cible = rv }))
            if (rv.laRepetition != null && !s.contient("\\b(?:toujours|completement|entierement|definitivement)\\b")) {
                periodeApres(t, c)?.let { return Lu(listOf(ActionIA("supprimer", periode = it).apply { cible = rv })) }
            }
            return Lu(listOf(ActionIA("supprimer").apply { cible = rv }))
        }
        // --- Une série qui s'arrête (« le raid, c'est fini après novembre »)
        if (c != null && c.rv.laRepetition != null && s.contient("\\b(?:arret\\w*|stop\\w*|fini|finie|termine\\w*|plus de|plus d')\\b")) {
            val a = trouve(s, "\\b(?<v>a partir d(?:u\\s|e\\s|')|apres\\s|des\\s|depuis\\s|fin\\s|a la fin d(?:u\\s|e\\s|'))\\s*(?<p>.+?)\\s*[.!]?$")
            if (a.ok) {
                val reste = t.substring(a.index).let { x -> x.substring(x.length - a.g("p")!!.length).trim(' ', '.', '!') }
                val v = a.g("v")!!
                val periode = if (v.startsWith("fin") || v.startsWith("a la fin")) "après $reste" else "${t.substring(a.index, a.index + v.length)} $reste"
                if (lirePeriode(periode, maintenant) != null) return Lu(listOf(ActionIA("supprimer", periode = periode).apply { cible = c.rv }))
            }
        }
        // --- C'est fait
        if (c != null && estFait(s)) return Lu(listOf(ActionIA("fait").apply { cible = c!!.rv }))
        // --- Créer : un verbe pour créer, et pas « le foot » qui existe déjà
        val lp = lirePhrase(t, maintenant)
        if ((creer && (c == null || !c.defini)) || c?.indefini == true) {
            return Lu(listOf(ActionIA("creer", phrase = if (seule) phraseEntiere else t)))
        }
        // --- Changer un rendez-vous qui existe
        if (c != null) {
            val a = modification(t, s, c) ?: return null
            return Lu(listOf(a.apply { cible = c.rv }))
        }
        // --- Un rendez-vous dit tel quel (« dentiste jeudi à 15 h ») : noté
        if (lp.manque == Manque.RIEN && lp.titre.isNotEmpty() && (lp.jour != null || lp.repetition != null || lp.quand != null) && !s.contient(RxModif)) {
            return Lu(listOf(ActionIA("creer", phrase = if (seule) phraseEntiere else t)))
        }
        // « Décale le rendez-vous », « supprime » : on demande lequel
        if (s.contient(RxModif)) return introuvable(t, s)
        return null
    }

    /** Le rendez-vous n'est pas dit (on demande lequel) ou n'existe pas (on le dit). */
    private fun introuvable(t: String, s: String): Lu {
        // Un nom qu'Evermind ne connaît pas (« supprime l'événement Troke Tops »)
        val nom = trouve(t, "(?:[ée]v[èée]nement|rendez-vous|rdv|rappel|truc)\\s+(?<n>[A-ZÉÈ][\\p{L}'-]*(?:\\s+[A-ZÉÈ\\p{L}][\\p{L}'-]*){0,3})\\s*[.!?]*$", ci = false)
        if (nom.ok) return Lu(dit = "Je ne trouve pas « ${nom.g("n")} » dans ton agenda, je n'ai rien touché.")
        val sansVerbe = s.remplace("$RxSupp|$RxModif|\\b(?:je veux que tu|j'aimerais que tu|tu peux|peux-tu|il faut)\\b|$RxGenerique|\\b(?:le|la|les|l'|mon|ma|mes|un|une|ce|cette|moi|me|m')\\b|[-.,!?]", " ").trim()
        if (sansVerbe.isEmpty()) {
            val noms = agenda.aVenir().take(6).map { it.rv }
            if (noms.isEmpty()) return Lu(dit = "Ton agenda est vide, il n'y a rien à changer.")
            return Lu(dit = lequel(noms), attente = t)
        }
        return Lu(dit = "Je n'ai pas trouvé de quel rendez-vous tu parles, je n'ai rien touché.")
    }

    private fun lequel(rvs: List<RendezVous>): String {
        val noms = rvs.map { titreDe(it) }.distinct()
        val liste = if (noms.size > 1) noms.dropLast(1).joinToString(", ") + " ou " + noms.last() else noms[0]
        return "Lequel ? $liste ?"
    }

    /** Les rendez-vous nommés dans la phrase, les mieux reconnus d'abord (« le raid » : Raid avec les potes). */
    private fun chercher(m: List<Mot>): List<Cible> {
        val res = tous(m)
        val max = res.maxOfOrNull { it.score } ?: return emptyList()
        return res.filter { it.score == max }
    }

    /** « le dentiste et le foot », « le foot, le dentiste et la soirée » : des rendez-vous différents, l'un après l'autre. */
    private fun enumeres(m: List<Mot>, s: String): List<Cible> {
        val l = tous(m).filter { it.score >= 10 }.sortedBy { it.premier }
        if (l.size < 2 || l.map { it.premier }.distinct().size != l.size) return emptyList()
        for ((a, b) in l.zipWithNext()) {
            if (a.dernier >= b.premier) return emptyList()
            val entre = m.subList(a.dernier + 1, b.premier).map { it.texte }
            if (entre.isEmpty() || entre.size > 3 || entre.none { it == "et" || it == "ou" } && !s.substring(m[a.dernier].fin, m[b.premier].debut).contains(',')) return emptyList()
        }
        return l
    }

    private fun tous(m: List<Mot>): List<Cible> {
        val res = mutableListOf<Cible>()
        for (rv in agenda.liste) {
            var fort = 0
            var faible = 0
            val places = mutableListOf<Int>()
            for (cle in clesTitre(rv.titre)) {
                val i = m.indexOfFirst { proche(it.texte.trimEnd('\''), cle) }
                if (i < 0) continue
                places += i
                if (cle in MotsFaibles) faible++ else fort++
            }
            if (places.isEmpty()) continue
            val p = places.min()
            var def = false
            var indef = false
            for (j in p - 1 downTo maxOf(0, p - 4)) {
                val w = m[j].texte
                if (w in Definis) { def = true; break }
                if (w in Indefinis) { indef = true; break }
            }
            res += Cible(rv, fort * 10 + faible, p, places.max(), def, indef)
        }
        return res
    }

    /** Plusieurs rendez-vous du même nom : celui de la date dite, sinon le prochain. */
    private fun departager(cibles: List<Cible>, t: String): Cible? {
        val jour = dateDite(t)
        if (jour != null) {
            val ce = cibles.filter { c -> agenda.entre(jour.atStartOfDay(), jour.plusDays(1).atStartOfDay()).any { it.rv.id == c.rv.id } }
            if (ce.size == 1) return ce[0]
        }
        if (cibles.map { titreDe(it.rv) }.distinct().size == 1) {
            return cibles.minByOrNull { agenda.prochaine(it.rv, maintenant) ?: LocalDateTime.MAX }
        }
        return null
    }

    /**
     * Un rendez-vous désigné sans son nom : « le prochain rendez-vous », « le rendez-vous de 15 h », « le rendez-vous
     * avec Inès », ou sa date.
     */
    private fun designe(t: String, s: String): Cible? {
        if (s.contient("\\b(?:prochain|suivant)\\s+$RxGenerique|$RxGenerique\\s+suivant")) {
            val v = agenda.aVenir()
            val r = (v.firstOrNull { !it.rv.journee } ?: v.firstOrNull())?.rv ?: return null
            return Cible(r, 1, -1, -1, true, false)
        }
        val h = trouve(s, "$RxGenerique\\s+(?:de|d'|a|prevu a)\\s*(?<h>\\d{1,2})\\s*h(?:\\s*(?<mi>\\d{2}))?")
        if (h.ok) {
            val heure = h.g("h")!!.toInt()
            val minute = h.g("mi")?.toInt() ?: 0
            val ce = agenda.liste.filter { r ->
                val q = agenda.prochaine(r, today.atStartOfDay()) ?: r.quand
                !r.journee && !r.fait && q.hour == heure && q.minute == minute
            }
            val r = ce.singleOrNull() ?: ce.filter { (agenda.prochaine(it, maintenant) ?: it.quand).isAfter(maintenant) }
                .minByOrNull { agenda.prochaine(it, maintenant) ?: it.quand } ?: return null
            return Cible(r, 1, -1, -1, true, false, masque = h.index until h.index + h.valeur.length)
        }
        val avec = trouve(s, "$RxGenerique\\s+avec\\s+(?<n>[a-z-]+)")
        if (avec.ok) {
            val ce = agenda.liste.filter { r -> r.avec.any { sansAccents(it) == avec.g("n") } }
            ce.singleOrNull()?.let { return Cible(it, 1, -1, -1, true, false, masque = avec.index until avec.index + avec.valeur.length) }
        }
        return parDate(t, s)
    }

    /** « Supprime tout », « vide mon agenda », « supprime tous les anniversaires », « … les rendez-vous passés ». */
    private fun suppressionGroupe(s: String): ReponseIA? {
        val m = trouve(s, "\\b(?:supprim|suprim|sucrim|effac|enlev|retir|vire|vide|annul|degag|nettoi)\\w*\\s+(?:moi\\s+|-moi\\s+)?(?<q>tout mon agenda|mon agenda|l'agenda|tout|tous|toutes)\\b(?<r>.*)$")
        if (!m.ok) return null
        val reste = m.g("r")!!
        // « supprime tout le raid », « tous les raids de novembre » : un rendez-vous nommé, pas tout l'agenda
        if (tous(mots(reste)).any { it.score >= 10 }) return null
        val sorte = mapOf(
            "anniversaires?|anniv" to Sorte.ANNIV, "(?:choses|trucs) a faire|taches|a faire|rappels" to Sorte.A_FAIRE,
            "sports?" to Sorte.SPORT, "travail|boulot|ecole|cours" to Sorte.TRAVAIL, "sorties|soirees" to Sorte.SORTIE,
            "jeux|parties" to Sorte.JEU, "sante|medecins?|medicaux" to Sorte.SANTE, "voyages|vacances" to Sorte.VOYAGE,
        ).entries.firstOrNull { reste.contient("\\b(?:${it.key})\\b") }?.value
        val passes = reste.contient("\\b(?:passes?|passees?|anciens?|anciennes?|d'avant|termines?|terminees?|finis?)\\b")
        val faits = reste.contient("\\b(?:faits?|faites?|coches?|cochees?)\\b") && !reste.contient("\\ba faire\\b")
        // Une période dite (« de décembre ») sans être lue plus haut : pas tout l'agenda, on laisse les autres règles
        if (dateDite(reste) != null || reste.contient("\\b(?:semaine|mois|week-end|$RxMois)\\b")) return null
        val vises = agenda.liste.filter { r ->
            (sorte == null || r.sorte == sorte) &&
                (!passes || (r.laRepetition == null && (r.fin ?: r.quand).isBefore(if (r.journee) today.atStartOfDay() else maintenant))) &&
                (!faits || r.fait)
        }
        if (vises.isEmpty()) return ReponseIA(emptyList(), "Il n'y a rien à supprimer.")
        return ReponseIA(vises.map { ActionIA("supprimer").apply { cible = it; sure = true } }, "")
    }

    /** « Supprime le rendez-vous de jeudi », « efface le truc du 16 » : le seul rendez-vous ce jour-là. */
    private fun parDate(t: String, s: String): Cible? {
        val jour = dateDite(t) ?: return null
        val ce = agenda.entre(jour.atStartOfDay(), jour.plusDays(1).atStartOfDay()).map { it.rv }.distinctBy { it.id }
        val choix = ce.filter { it.sorte != Sorte.ANNIV && it.laRepetition == null }.ifEmpty { ce }
        return choix.singleOrNull()?.let { Cible(it, 1, -1, -1, true, false) }
    }

    /** Le jour dit dans la phrase (« jeudi », « le 16 », « demain »), ou null. */
    private fun dateDite(t: String): LocalDate? {
        val x = trouve(net(t), "\\b(?:(?:$RxJour)(?:\\s+(?:prochain|\\d{1,2}(?:er)?(?:\\s+$RxMois)?))?|(?:le|du|au)\\s+\\d{1,2}(?:er)?(?:\\s+$RxMois)?|\\d{1,2}(?:er)?\\s+$RxMois|demain|apres-demain|aujourd'hui|hier|avant-hier)\\b")
        if (!x.ok) return null
        val d = x.valeur.remplace("^(?:du|au)\\s+", "le ")
        if (d == "hier") return today.minusDays(1)
        if (d == "avant-hier") return today.minusDays(2)
        val p = lirePhrase("x $d", maintenant)
        return p.jour ?: p.quand?.toLocalDate()
    }

    /** « Retire Tom de la soirée » : ceux qui y étaient et que la phrase enlève. */
    private fun retires(s: String, rv: RendezVous): List<String> {
        if (rv.avec.isEmpty()) return emptyList()
        val m = trouve(s, "\\b(?:retire|enleve|supprime|vire|efface|desinvite)s?\\s+(?<n>[a-z' -]+?)\\s+(?:de|du|des|d')\\b")
        if (!m.ok) return emptyList()
        val dits = m.g("n")!!.split(Regex("\\s*(?:,|\\bet\\b)\\s*")).map { it.trim() }
        return rv.avec.filter { n -> dits.any { it == sansAccents(n) } }
    }

    /** La période dite après le nom (« les raids de novembre », « le raid de demain seulement »), lue par Evermind. */
    private fun periodeApres(t: String, c: Cible): String? {
        val m = mots(net(t))
        val apres = if (c.dernier in m.indices) t.substring(m[c.dernier].fin) else t
        val reste = apres.remplace("\\b(?:seulement|uniquement|stp|svp|merci|aussi|alors)\\b", "").trim(' ', ',', '.', '!', '?')
            .remplace("^(?:avec les potes|chez \\S+)\\s*", "")
        val cands = mutableListOf(reste)
        for (x in tous(reste, "\\s(?=(?:le|la|du|de|des|d'|en|ce|cette|à|a|apr[èe]s|avant|d[èe]s|jusqu|demain|aujourd|$RxJour|$RxMois|\\d))")) cands += reste.substring(x.index).trim()
        for (cand in cands) {
            val p = cand.remplace("^du\\s+(?=\\d)", "le ").remplace("^(?:de\\s+|d')(?=demain|aujourd|apr[èe]s-demain|$RxJour)", "")
            if (p.isBlank() || p.contient("^(?:est|sont|a|ont)\\b")) continue
            val per = lirePeriode(p, maintenant) ?: continue
            if (per.debut == null && per.fin == null) continue
            return p
        }
        return null
    }

    private fun estFait(s: String): Boolean =
        s.contient("\\bc'est (?:fait|bon|regle|ok)\\b|\\bcomme (?:fait|faite|termine|terminee|fini|finie|regle|reglee)\\b|^\\s*\\S+(?:\\s+\\S+)?\\s+(?:paye|payee|fait|faite|regle|reglee|fini|finie|termine|terminee|envoye|achete|rendu)\\s*[.!]*$|\\b(?:est|sont) (?:bien |deja )?(?:paye|payee|payes|fait|faite|regle|reglee|fini|finie|termine|terminee|envoye|envoyee|achete|rendu|rendue)\\b") ||
            trouve(s, "\\b(?:j'ai|on a|je l'ai|je les ai)\\s+(?:bien\\s+|deja\\s+|enfin\\s+|finalement\\s+)?(?<v>[a-z]{3,}(?:e|i|is|it|u|ert|es))\\b").let {
                it.ok && it.g("v") !in setOf("rendez", "une", "plus", "envie", "quoi", "rien", "tout", "deja", "bien", "besoin", "personne", "jamais", "aussi", "quelque")
            }

    // ---------------------------------------------------------------------------
    // Changer un rendez-vous : le moment, sa durée, ses détails
    // ---------------------------------------------------------------------------
    private fun modification(t: String, s: String, c: Cible): ActionIA? {
        val rv = c.rv
        val lu = modifsTexte(c.masque?.let { t.removeRange(it) } ?: t, rv, agenda)
        val a = ActionIA("modifier")
        var fait = false
        if (lu.retirer.isNotEmpty() || retires(s, rv).isNotEmpty()) { a.retirerPersonnes = (lu.retirer + retires(s, rv)).distinct(); fait = true }
        // Renommer (« renomme le foot en match de foot », « appelle le foot entraînement de foot »)
        var titre = lu.titre
        if (titre == null && c.dernier >= 0) {
            val r = trouve(s, "^(?:appelle|nomme|renomme)[- ]?\\s*(?:le|la|les|l')")
            if (r.ok) titre = t.substring(mots(s)[c.dernier].fin).trim(' ', ',', '.', '!', '«', '»', '"').remplace("^(?:en|plut[ôo]t)\\s+", "").takeIf { it.length >= 3 }
        }
        // « Change le foot en tennis »
        if (titre == null && c.dernier >= 0 && s.contient("^\\s*(?:change|transforme|remplace)")) {
            val r = trouve(t.substring(mots(s)[c.dernier].fin), "^\\s+(?:en|par)\\s+(?<t>.+?)\\s*[.!]?$")
            if (r.ok && !momentDit(r.g("t")!!) && !net(r.g("t")!!).contient("important|priorit|urgent")) titre = r.g("t")
        }
        if (titre != null) { a.titre = majuscule(titre); fait = true }
        // Important, ou plus du tout
        if (s.contient("\\bimportant|\\bprioritaire|\\burgent")) {
            a.important = !s.contient("\\b(?:n'est plus|plus|pas|n'est pas|enleve|retire)\\b[^.]*\\b(?:important|prioritaire|urgent)")
            fait = true
        }
        if (lu.personnes.isNotEmpty()) { a.personnes = lu.personnes.filter { p -> clesTitre(rv.titre).none { proche(sansAccents(p), it) } }; fait = fait || a.personnes.isNotEmpty() }
        // Une note (« ajoute une note au dentiste : demander pour la couronne »)
        val note = trouve(t, "\\b(?:[Nn]ote|[Nn]otes|[Pp]r[ée]cise|[Ii]ndique)\\b[^:]*:\\s*(?<n>.+?)\\s*[.!]?$", ci = false)
        if (note.ok) { a.notes = majuscule(note.g("n")!!); fait = true }
        // Les choses à prendre (« pour le foot, n'oublie pas les crampons », « il faut que je prenne ma carte de mutuelle »)
        if (!note.ok) {
            val ch = trouve(t, "\\b(?:prendre|apporter|emporter|amener|prenne|prennes|prends|apporte|emporte|am[èe]ne|n'oublie pas|oublie pas|n'oublies pas)\\s+(?<o>.+?)\\s*(?:\\bpour\\s+(?:le|la|les|l'|mon|ma|mes)\\b.*|\\b(?:au|à la|chez le|chez la)\\s+\\S+.*)?[.!]?$")
            if (ch.ok) {
                var o = ch.g("o")!!.remplace("^(?:de\\s+|d')?(?:prendre|apporter|emporter|amener)\\s+", "")
                    .remplace("^(?:mes|ma|mon|les|la|le|l'|des|du|de la|un|une|son|sa|ses|ton|ta|tes)\\s*", "").trim(' ', '.', ',', '!')
                // (sans le nom du rendez-vous, s'il est resté au bout)
                for (k in clesTitre(rv.titre)) o = o.remplace("\\s+(?:pour|au|à|chez)\\s+(?:le|la|l')?\\s*$k\\w*$", "")
                if (o.isNotEmpty() && !o.contient("^(?:que|de|d')\\b")) { a.chosesAPrendre = listOf(majuscule(o)); fait = true }
            }
        }
        // Le lieu (« le foot, c'est au stade Gerland »)
        val lieu = trouve(t, "\\b(?:c'est|ce sera|ça se passe|ca se passe|se passe|a lieu|aura lieu|se fait)\\s+(?:plut[ôo]t\\s+)?(?<p>à la|à l'|au|aux|à|a|chez|en|dans|sur)\\s+(?<l>.+?)\\s*[.!]?$")
        if (lieu.ok && !momentDit(lieu.g("l")!!)) {
            val l = lieu.g("l")!!.remplace("\\s+(?:finalement|maintenant|d[ée]sormais)$", "")
            a.lieu = if (lieu.g("p")!!.lowercase() == "chez") "chez $l" else l
            fait = true
        }
        if (lu.rappels.isNotEmpty() && s.contient("\\b(?:previens|rappelle|avertis|alerte|rappel|notifie)")) { a.rappels = lu.rappels; fait = true }
        if (fait) return a

        // --- Le moment
        val phrase = moment(t, s, c, lu) ?: return null
        a.phrase = phrase
        return a
    }

    /** Une heure ou une date dans ces mots (« à 16 h », « jeudi ») : ce n'est pas un lieu. */
    private fun momentDit(x: String): Boolean =
        net(x).contient("^(?:\\d{1,2}\\s*h\\b|\\d{1,2}\\s*heures?|midi|minuit|$RxJour|demain|apres-demain|aujourd'hui|\\d{1,2}(?:er)?\\s+$RxMois|la semaine|le \\d|l'heure)")

    /** La nouvelle date ou heure, écrite comme une phrase qu'Evermind relira ; null si la phrase n'en dit pas. */
    private fun moment(t: String, s: String, c: Cible, lu: Modifs): String? {
        val rv = c.rv
        lu.etendre?.let { return it }
        lu.decalage?.let { return it }
        // La durée (« le dentiste dure une heure »)
        val d = trouve(s, "\\b(?:dure|durera|duree de|pendant|prend)\\s+(?<d>.+?)\\s*[.!]?$")
        if (d.ok && !rv.journee) {
            duree(d.g("d")!!)?.let { min ->
                val q = agenda.prochaine(rv, today.atStartOfDay()) ?: rv.quand
                val f = q.plusMinutes(min)
                return "le ${q.format(FormatJourAn)} de ${heurePc(q)} à ${heurePc(f)}"
            }
        }
        // Une semaine sur deux
        val rep = rv.laRepetition
        if (rep != null && s.contient("\\b(?:une semaine sur deux|toutes les (?:deux|2) semaines|tous les (?:quinze|15) jours|un (?:\\w+ )?sur deux)\\b")) {
            val q = agenda.prochaine(rv, maintenant) ?: rv.quand
            val jour = q.format(DateTimeFormatter.ofPattern("EEEE", Fr))
            return "toutes les deux semaines le $jour" + if (rv.journee) "" else " à ${heurePc(q)}"
        }
        if (lu.jour != null) return lu.jour
        // Sinon : ce qui reste quand on enlève le nom du rendez-vous et ce qui le désigne (« le raid de demain »)
        val m = mots(s)
        var reste = c.masque?.let { t.removeRange(it) } ?: t
        if (c.premier >= 0 && c.dernier in m.indices) {
            var debut = m[c.premier].debut
            for (j in c.premier - 1 downTo maxOf(0, c.premier - 4)) { if (m[j].texte in Definis || m[j].texte in setOf("de", "d'", "chez", "rendez", "vous")) debut = m[j].debut else break }
            var fin = m[c.dernier].fin
            val qual = trouve(t.substring(fin), "^\\s*(?:(?:de|du|d')\\s*(?:hier|avant-hier|demain|aujourd'hui|ce soir|$RxJour|\\d{1,2}(?:er)?(?:\\s+$RxMois)?)\\b|(?:chez|avec)\\s+(?:le|la|les)?\\s*\\S+)")
            if (qual.ok) fin += qual.valeur.length
            reste = t.substring(0, debut) + " " + t.substring(fin)
        }
        reste = reste.remplace("\\b(?:mets|met|bouge|passe|d[ée]cale|d[ée]place|repousse|avance|recule|change|modifie|reporte)[- ]?(?:le|la|les|moi)?\\b", " ")
            .remplace("\\b(?:c'est|ce sera|ça sera|ca sera|plut[ôo]t|finalement|maintenant|d[ée]sormais|en fait|l'heure|la date|l'horaire|le jour)\\b", " ")
            .remplace("\\b(?:du|de la|de l')\\s*:", " ").remplace("\\s+", " ").trim(' ', ',', '.', ':', '!')
        if (reste.isEmpty()) return null
        val p = lirePhrase("x $reste", maintenant)
        // Plusieurs jours dits un par un (« le 27, le 28 et le 29 octobre ») : du premier au dernier
        if (p.jours.size > 1) {
            val js = p.jours.sorted()
            if (js.zipWithNext().all { (a, b) -> b == a.plusDays(1) }) return "du ${js.first().format(FormatJourAn)} au ${js.last().format(FormatJourAn)}"
        }
        if (p.jour == null && p.heure == null && p.repetition == null && p.quand == null) return null
        // Juste une heure (« à 20 h ») : le même jour, à cette heure-là
        if (p.jour == null && p.repetition == null && p.heure != null && p.jours.isEmpty()) {
            val base = agenda.prochaine(rv, today.atStartOfDay()) ?: rv.quand
            val q = base.toLocalDate().atTime(p.heure)
            val fin = p.fin?.takeIf { it.toLocalDate() == p.quand?.toLocalDate() }?.let { q.toLocalDate().atTime(it.toLocalTime()) }
            return "le ${q.format(FormatJourAn)} " + if (fin != null) "de ${heurePc(q)} à ${heurePc(fin)}" else "à ${heurePc(q)}"
        }
        return reste
    }

    /** « une heure », « 45 minutes », « 1 h 30 », « une demi-heure » : en minutes. */
    private fun duree(x: String): Long? {
        val s = net(x)
        if (s.contient("^une demi[- ]heure")) return 30
        if (s.contient("^un quart d'heure")) return 15
        val m = trouve(s, "^(?<n>\\d+|une?|deux|trois|quatre|cinq|six|sept|huit|dix|quinze|vingt|trente|quarante-cinq)\\s*(?<u>heures?|h\\b|minutes?|min\\b)(?:\\s*(?<m>\\d{1,2})|\\s+et\\s+(?<demi>demie))?")
        if (!m.ok) return null
        val n = nombre(m.g("n")!!.remplace("^une?$", "un")) ?: (if (m.g("n")!!.startsWith("un")) 1 else return null)
        return if (m.g("u")!!.startsWith("h")) n * 60L + (m.g("m")?.toLong() ?: 0) + (if (m.a("demi")) 30 else 0) else n.toLong()
    }

    // ---------------------------------------------------------------------------
    // Questions
    // ---------------------------------------------------------------------------
    private fun estQuestion(s: String): Boolean {
        val q = s.contient("\\?\\s*$") || s.contient("^\\s*(?:qu'est-ce|c'est quand|quand|ou\\b|ou est|c'est ou|combien|quel(?:le)?s?|a quelle|avec qui|est-ce que j'ai|est-ce que je|ai-je|suis-je|j'ai quoi|j'ai quelque chose|dis-moi|montre|liste|rappelle-moi (?:quand|ce que|ou))\\b")
        if (!q) return false
        // « Tu peux enlever le dentiste ? » : une demande polie, pas une question
        return !s.contient("\\b(?:tu peux|peux-tu|tu pourrais|pourrais-tu|tu veux bien|est-ce que tu peux|est-ce que tu pourrais)\\s+(?!(?:me\\s+|m')?(?:dire|montrer|lister|rappeler ce|rappeler quand|donner))")
    }

    private fun question(t: String, s: String): String? {
        val cibles = chercher(mots(s))
        val c = if (cibles.size == 1) cibles[0] else if (cibles.size > 1) departager(cibles, t) else null
        val surPeriode = s.contient("\\b(?:qu'est-ce que j'ai|j'ai quoi|qu'ai-je|j'ai quelque chose|j'ai des?|ce que j'ai|qu'est-ce qu'il y a|qu'est-ce que je fais|je fais quoi|libre|occupe|combien|programme|prevu|planning|agenda)\\b")
        // « Quels sont mes anniversaires ? », « qu'est-ce que j'ai à faire ? » : une sorte de rendez-vous
        val sorte = when {
            s.contient("\\banniversaires\\b") -> Sorte.ANNIV
            s.contient("\\b(?:a faire|taches|choses a faire)\\b") -> Sorte.A_FAIRE
            else -> null
        }
        if (c != null && sorte == null && !(surPeriode && c.rv.sorte == Sorte.ANNIV && s.contient("\\b(?:combien|qu'est-ce que j'ai|j'ai quoi)\\b"))) return surRendezVous(c.rv, s)
        // L'heure et la date
        if (s.contient("\\bquelle heure\\b|\\bil est quelle heure\\b")) return "Il est ${maintenant.toLocalTime().enTexte()}."
        if (s.contient("\\bquel jour\\b|\\bon est (?:le )?(?:quel|combien)\\b|\\bquelle (?:est la )?date\\b|\\bon est le combien\\b")) {
            return "On est ${today.enTexteLong(today)}."
        }
        // « Quel est mon prochain rendez-vous ? »
        if (s.contient("\\bprochain(?:e)?\\s+(?:$RxGenerique|chose)")) {
            val v = agenda.aVenir().filter { sorte == null || it.rv.sorte == sorte }
            val f = v.firstOrNull { !it.rv.journee } ?: v.firstOrNull() ?: return "Tu n'as rien de prévu."
            return "Ton prochain rendez-vous : ${titreDe(f.rv)}, ${quandRV(f.rv, f.prochain, today)}."
        }
        val per = periodeQuestion(t, s)
        if (per != null) return surLaPeriode(per, s, sorte)
        // Sans période : ce qui vient (« liste mes rendez-vous », « qu'est-ce que j'ai de prévu ? »)
        if (surPeriode || sorte != null || s.contient("\\b(?:liste|montre|mes rendez-vous|mes rdv|mon agenda|quels sont)\\b")) {
            val v = agenda.aVenir().filter { sorte == null || it.rv.sorte == sorte }
            if (v.isEmpty()) return "Tu n'as rien de prévu."
            if (s.contient("\\bcombien\\b")) return "Tu as ${v.size} ${if (v.size > 1) "rendez-vous" else "rendez-vous"} à venir. Le prochain : ${titreDe(v[0].rv)}, ${quandRV(v[0].rv, v[0].prochain, today)}."
            return "À venir : " + v.take(8).joinToString(" ; ") { "${ligne(it, true)}" } + "."
        }
        if (s.contient("\\b(?:sers a quoi|sais faire|tu fais quoi|qui es-tu|t'es qui|comment ca marche)\\b")) return Aide
        return null
    }

    private fun surRendezVous(rv: RendezVous, s: String): String {
        val nom = titreDe(rv)
        val depuis = if (rv.journee) today.atStartOfDay() else maintenant
        val fois = agenda.prochaine(rv, depuis) ?: rv.quand
        val quand = quandRV(rv, fois, today)
        return when {
            s.contient("\\b(?:ou|adresse|lieu|endroit)\\b") ->
                if (rv.lieu.isBlank()) "Je n'ai pas d'adresse pour $nom." else "$nom : ${rv.lieu}."
            s.contient("\\b(?:avec qui|qui (?:vient|viendra|sera|est invite|y va|est la))\\b") ->
                if (rv.avec.isEmpty()) "Personne n'est noté pour $nom." else "$nom, avec ${liste(rv.avec)}."
            s.contient("\\b(?:age|ans)\\b") && rv.naissance != null -> {
                val qui = trouve(nom, "^Anniversaire (?:de |d')(?<n>.+)$").g("n") ?: nom
                "$qui aura ${fois.year - rv.naissance!!} ans ${fois.toLocalDate().enTexte(today)}."
            }
            s.contient("\\b(?:code|digicode|etage|note|notes|idee|cadeau|infos?|details?)\\b") ->
                if (rv.notes.isBlank()) "Je n'ai rien de noté pour $nom." else "$nom : ${rv.notes}"
            s.contient("\\b(?:apporter|prendre|preparer|emporter|amener|besoin|oublier)\\b") ->
                if (rv.aPreparer.isEmpty()) "Rien à prendre pour $nom." else "Pour $nom, prends : ${liste(rv.aPreparer.map { it.texte })}."
            else -> "$nom : $quand."
        }
    }

    /** La période d'une question (« demain », « samedi soir », « cette semaine »), son nom et le moment de la journée. */
    private fun periodeQuestion(t: String, s: String): Triple<Periode, String, String?>? {
        val partie = trouve(s, "\\b(?:ce |le |dans la |en )?(?<p>soir(?:ee)?|matin(?:ee)?|apres-midi|midi)\\b").g("p")
        val x = trouve(s, "\\b(?<p>aujourd'hui|ce soir|ce matin|cet apres-midi|apres-demain|demain|cette semaine|la semaine prochaine|semaine prochaine|ce week-end|le week-end prochain|ce mois(?:-ci)?|le mois prochain|(?:$RxJour)(?:\\s+prochain|\\s+\\d{1,2}(?:er)?(?:\\s+$RxMois)?)?|(?:le\\s+)?\\d{1,2}(?:er)?(?:\\s+$RxMois)?|(?:en\\s+)?$RxMois)\\b")
        if (!x.ok) return null
        val p = x.g("p")!!
        val nom = majuscule(t.substring(x.index, x.index + x.valeur.length).trim())
        fun jour(d: LocalDate) = Periode(d, d)
        val per = when {
            p.startsWith("aujourd") || p.startsWith("ce ") && p != "ce week-end" && !p.startsWith("ce mois") || p == "cet apres-midi" -> jour(today)
            p == "ce week-end" || p == "le week-end prochain" -> {
                var sam = today
                while (sam.dayOfWeek != java.time.DayOfWeek.SATURDAY && sam.dayOfWeek != java.time.DayOfWeek.SUNDAY) sam = sam.plusDays(1)
                if (p == "le week-end prochain" && sam == today) sam = sam.plusDays(7)
                if (sam.dayOfWeek == java.time.DayOfWeek.SUNDAY) Periode(sam, sam) else Periode(sam, sam.plusDays(1))
            }
            p == "semaine prochaine" -> lirePeriode("la semaine prochaine", maintenant)
            else -> lirePeriode(p, maintenant) ?: lirePhrase("x $p", maintenant).let { ph -> (ph.jour ?: ph.quand?.toLocalDate())?.let { jour(it) } }
        } ?: return null
        val moment = when {
            p == "ce soir" -> "soir"
            p == "ce matin" -> "matin"
            p == "cet apres-midi" -> "apres-midi"
            else -> partie?.let { if (it.startsWith("soir")) "soir" else if (it.startsWith("matin")) "matin" else if (it == "midi") null else "apres-midi" }
        }
        return Triple(per, nom, moment)
    }

    private fun surLaPeriode(q: Triple<Periode, String, String?>, s: String, sorte: Sorte? = null): String {
        val (per, nom, moment) = q
        val debut = per.debut ?: today
        val fin = per.fin ?: debut
        var fois = agenda.entre(debut.atStartOfDay(), fin.plusDays(1).atStartOfDay()).filter { !it.rv.fait && (sorte == null || it.rv.sorte == sorte) }
        if (debut == today) fois = fois.filter { it.rv.journee || !it.prochain.isBefore(maintenant.minusHours(1)) }
        if (moment != null) fois = fois.filter { f ->
            !f.rv.journee && f.prochain.hour.let { h -> when (moment) { "soir" -> h >= 17; "matin" -> h < 12; else -> h in 12..17 } }
        }
        val quand = nom + (if (moment != null && !nom.contient(moment)) " $moment" else "")
        if (s.contient("\\b(?:libre|dispo|disponible)\\b")) {
            if (fois.isEmpty()) return "$quand, tu es libre."
            return "$quand, tu as déjà ${liste(fois.map { ligne(it, false) })}."
        }
        if (fois.isEmpty()) return "$quand, tu n'as rien de prévu."
        val unJour = debut == fin
        val lignes = if (unJour) fois.map { ligne(it, false) } else fois.groupBy { it.prochain.toLocalDate() }.map { (j, l) ->
            "${j.enTexte(today)} : ${l.joinToString(", ") { ligne(it, false) }}"
        }
        val n = fois.size
        if (s.contient("\\bcombien\\b")) return "$quand, tu as $n ${if (n > 1) "choses" else "chose"} : ${lignes.joinToString(if (unJour) ", " else " ; ")}."
        return "$quand : ${lignes.joinToString(if (unJour) ", " else " ; ")}."
    }

    private fun ligne(f: FoisIA, jour: Boolean): String {
        val r = f.rv
        val t = titreDe(r)
        val j = if (jour) " ${f.prochain.toLocalDate().enTexte(today)}" else ""
        if (r.journee) return if (r.sorte == Sorte.ANNIV) "$t$j" else "$t$j (toute la journée)"
        return "$t à ${f.prochain.toLocalTime().enTexte()}" + if (jour) " ${f.prochain.toLocalDate().enTexte(today)}" else ""
    }

    private fun liste(l: List<String>) = if (l.size > 1) l.dropLast(1).joinToString(", ") + " et " + l.last() else l.firstOrNull() ?: ""

    // ---------------------------------------------------------------------------
    // Discussion
    // ---------------------------------------------------------------------------
    private fun discussion(s: String): String? {
        val sans = s.trim(' ', '.', '!', '?', ',')
        if (sans.contient("^(?:merci(?: beaucoup| bien)?|super merci|parfait(?: merci)?|top|genial|nickel)(?:\\s+evermind)?$")) return "Avec plaisir !"
        if (sans.contient("^(?:salut|bonjour|bonsoir|coucou|hello|hey|yo)(?:\\s+evermind)?\\s*(?:,?\\s*(?:ca va|comment ca va|comment vas-tu|tu vas bien))?$")) {
            return if (sans.contient("ca va|vas")) "Ça va bien, merci ! Qu'est-ce que je peux faire pour ton agenda ?" else "Salut ! Qu'est-ce que je peux faire pour ton agenda ?"
        }
        if (sans.contient("^(?:ca va|comment ca va|tu vas bien)$")) return "Ça va bien, merci ! Qu'est-ce que je peux faire pour ton agenda ?"
        if (sans.contient("^(?:rien|non|non merci|laisse tomber|oublie|c'est tout|ok|d'accord|ca marche|rien,? laisse tomber|non rien|rien du tout|pas grand-chose|c'est bon,? merci)$")) return "D'accord."
        if (sans.contient("\\b(?:tu sers a quoi|qu'est-ce que tu sais faire|que sais-tu faire|tu sais faire quoi|aide-moi|comment ca marche)$")) return Aide
        return null
    }

    private companion object {
        const val Aide = "Je tiens ton agenda. Dis-moi par exemple « dentiste jeudi à 15 h », « décale le foot à 20 h », « supprime les raids de novembre » ou « qu'est-ce que j'ai demain ? »."
    }
}
