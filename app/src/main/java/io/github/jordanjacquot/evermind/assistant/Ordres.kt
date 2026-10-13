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
    /** La dernière demande sur un rendez-vous, et la dernière question (« et le coiffeur aussi », « et le foot ? »). */
    var derniereDemande: String? = null
    var derniereQuestion: String? = null
    /** Ce qu'Evermind a proposé de noter (« tu veux que je le note ? »), en attendant « oui ». */
    var proposition: String? = null
    /** Une création qui attend son jour (« crée un rendez-vous chez le garagiste », puis « lundi à 8 h »). */
    var incomplet: String? = null
    /** Les rendez-vous de la dernière liste donnée (« qu'est-ce que j'ai lundi ? », puis « supprime tout ça »). */
    var derniereListe: List<String> = emptyList()
    var quand: LocalDateTime? = null

    fun recente(maintenant: LocalDateTime) = quand?.let { Duration.between(it, maintenant).toMinutes() < 3 } == true

    fun oublier() { dernier = null; attente = null; quand = null; derniereDemande = null; derniereQuestion = null; incomplet = null; derniereListe = emptyList(); proposition = null }
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
    if (b.length >= 6 && a.length >= 5 && a[0] == b[0]) return distance(a, b) <= if (b.length >= 7) 2 else 1
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
private const val RxCreer = "\\b(?:(?:cree|creer|crees|creez|cre|ajout|rajout|note|noter|notes|programm|planifi|inscri|rappelle|rappel|oubli|pense|faut que je|faut que j'|il faut qu|faudrait que je|faudrait que j'|dois|fais-moi|fait-moi|fais moi|reserve|bloque)\\w*|(?:mets|met|mettre|mettes|mettez)\\b)"
/** Verbes avant « -le » qui renvoient au rendez-vous dont on vient de parler. */
private const val RxPronom = "\\b(?:supprim|suprim|effac|enlev|retir|vir|decal|deplac|met|repouss|avanc|recul|chang|boug|pass|annul|renomm|marqu|fai)\\w*-(?:le|la|les)\\b|\\b(?:le|la|l')\\s*(?:supprimer|effacer|enlever|decaler|deplacer|mettre|repousser|avancer|annuler|changer|bouger)\\b"
/** Pour couper « supprime le foot et ajoute un tennis » en deux demandes. */
private const val RxSuite = "(?:\\s*,?\\s+(?:et puis|et ensuite|et|puis|ensuite|mais)(?:\\s+(?:a la place|ensuite|aussi|apres|du coup))?\\s*,?\\s+|\\s*,\\s+)(?=(?:ajoute|rajoute|mets|met|supprime|efface|enleve|retire|decale|deplace|repousse|avance|recule|previens|rappelle|cree|note|change|renomme|marque|invite|programme|annule|passe|bouge|vire|fais|garde|conserve)(?:[- ](?:le|la|les|moi|lui))?\\b)"

private val FormatJourAn = DateTimeFormatter.ofPattern("d MMMM yyyy", Fr)

/** Les façons de dire un proche (« ma mère » pour Maman). */
private val Proches = mapOf(
    "maman" to listOf("mere", "ma mere", "mom"), "papa" to listOf("pere", "mon pere"), "mamie" to listOf("grand-mere", "ma grand-mere", "mamy", "mami"),
    "papi" to listOf("grand-pere", "mon grand-pere", "papy"), "mere" to listOf("maman"), "pere" to listOf("papa"),
)

/** Ce qu'une partie de la phrase demande : des actions, ou ce qu'Evermind répond. */
private class Lu(val actions: List<ActionIA> = emptyList(), val dit: String? = null, val attente: String? = null, val vise: RendezVous? = null)

class Ordres(private val agenda: AgendaIA, private val memoire: MemoireOrdres, private val horaires: Map<String, String> = HorairesDefaut) {
    private val maintenant get() = agenda.maintenant
    private val today get() = agenda.aujourdHui

    /** La demande comprise par les règles (actions, ou réponse), ou null si elles ne la reconnaissent pas. */
    fun comprendre(texte: String): ReponseIA? {
        var t = texte.trim().replace('’', '\'')
        discussion(net(t))?.let { return ReponseIA(emptyList(), it) }
        // « Mets le dentiste jeudi, non vendredi », « supprime le dentiste, euh non pardon, le coiffeur » : la correction
        t = corrigeEnRoute(t)
        // « Le foot n'est plus le samedi mais le dimanche » : ce qui compte, c'est après « mais »
        t = t.remplace("\\b(?:n'est|ne sera|ne se fait)\\s+plus\\s+(?:le\\s+|à\\s+|a\\s+|au\\s+)?[^,]+?,?\\s+mais\\s+", "c'est ")
        // « Comme je suis malade, annule le foot », « annule le foot parce que je suis malade » : la raison ne compte pas
        trouve(t, "^(?:[Cc]omme|[Vv]u que|[Pp]uisque|[ÉéEe]tant donn[ée] que|[Pp]arce que|[Dd]u coup|[Bb]ref|[Ee]n fait)\\b[^,]{3,},\\s*(?<r>.{4,})$").takeIf { it.ok }?.let { t = it.g("r")!! }
        t = t.remplace("\\s*,?\\s*\\b(?:parce que|parce qu'|car|puisque|vu que)\\b[^,]*[.!]?$", "").ifBlank { t }
        // « J'ai reçu un message : le coiffeur est reporté à samedi » : ce que dit le message
        trouve(t, "^(?:j'ai (?:re[çc]u|eu) (?:un|une) (?:message|sms|mail|e-mail|appel|texto|notification)|on m'a (?:dit|pr[ée]venu|[ée]crit)|on vient de me dire)\\s*(?::|que|qu')\\s*(?<r>.{4,})$").takeIf { it.ok }?.let { t = it.g("r")!! }
        t = t.remplace("^(?:(?:euh|bon|alors|donc|ok evermind|okay evermind|evermind|ok|okay|ben|bah|eh bien|genre|finalement|du coup|en fait|ouais|ouai|hmm+|hum|attends|bon ben|tiens|dis)\\s*,?\\s+)+", "")
            .remplace("\\s*,?\\s*\\b(?:s'il te pla[iî]t|s'il vous pla[iî]t|stp|svp|steuplait|merci)\\b\\s*", " ")
            .remplace("\\b(?:genre|euh|tu vois)\\b\\s*,?\\s*", "").trim()
        if (t.isEmpty()) return null
        val s = net(t)
        val recente = memoire.recente(maintenant)
        if (!recente) memoire.oublier()

        discussion(s)?.let { return ReponseIA(emptyList(), it) }
        // « Je pensais supprimer le foot, mais finalement non » : une idée abandonnée, rien à faire
        if (s.contient("\\b(?:mais|et)\\s+(?:finalement|en fait|au final|bon)\\s+non\\b\\s*[.!]*$|^(?:je pensais|j'allais|je voulais|j'avais pense a|j'hesite a|j'hesitais a)\\b.*\\bmais\\b")) {
            return ReponseIA(emptyList(), "D'accord, je ne touche à rien.")
        }
        // « Si je décale le foot à vendredi, ça tombe sur quelque chose ? », « si Max annule, je te le dirai » : une hypothèse
        if (s.contient("^si\\s+(?!possible|tu peux|tu veux|ca ne te|c'est possible)")) {
            if (estQuestion(s)) hypothese(t, s)?.let { return ReponseIA(emptyList(), it) }
            return ReponseIA(emptyList(), "D'accord, je ne change rien pour l'instant.")
        }
        // « Il faudrait peut-être que je décale le dentiste, mais pas maintenant », « peut-être qu'un jour je supprimerai le
        // raid » : une idée pour plus tard, pas une demande
        if (s.contient("\\bmais pas (?:maintenant|tout de suite|encore|pour l'instant)\\b|\\b(?:peut-etre|un jour)\\b.*\\b(?:mais pas|plus tard)\\b|\\bpeut-etre qu'un jour\\b|(?<!d')\\bun jour\\b(?!\\s+(?:plus|de plus|avant|apres|sur))|\\b(?:supprimerai|annulerai|decalerai|deplacerai|changerai|effacerai|enleverai|repousserai)\\b|\\bil (?:se peut|est possible) que\\b")) {
            return ReponseIA(emptyList(), "D'accord, je ne change rien pour l'instant. Dis-le-moi quand tu veux.")
        }
        // « Ne supprime pas le foot », « laisse le foot comme il est » : surtout ne rien toucher
        if (s.contient("\\b(?:ne|n')\\s*(?:le |la |les |l'|lui |me |m')?(?:supprim|effac|enlev|touch|chang|decal|modifi|annul|boug|deplac|retir|vir)\\w*\\s+(?:pas|rien|jamais|plus)\\b|\\blaisse[rz]?\\s+(?:le|la|les|l'|mon|ma|mes|tout|ca)\\b.*\\b(?:comme (?:il|elle|ils|elles|c'est|ca) ?(?:est|sont|etait)?|tel quel|telle quelle|tels quels|tranquille|en place)\\b")) {
            return ReponseIA(emptyList(), "D'accord, je n'y touche pas.")
        }
        // « Et le coiffeur aussi », « et le foot ? » : la même demande, ou la même question, pour un autre rendez-vous
        if (recente && s.contient("^(?:et|pareil pour|meme chose pour|idem pour)\\b")) {
            val c = chercher(mots(s)).singleOrNull()
            val q = memoire.derniereQuestion
            val d = memoire.derniereDemande
            if (c != null && q != null && (d == null || estQuestion(s))) { memoire.dernier = c.rv.id; return ReponseIA(emptyList(), surRendezVous(c.rv, net(q))) }
            if (c != null && d != null) partie(d, null, c)?.let { lu -> memoire.dernier = c.rv.id; return enReponse(listOf(lu)) }
        }
        // « Annule », « non, reviens en arrière », « non attends, remets-le » : défaire la dernière action
        if (s.contient("^(?:non\\s*,?\\s*|en fait\\s*,?\\s*)?(?:attends?\\s*,?\\s*)?(?:remets?[- ](?:le|la|les)(?: comme avant)?|remets? (?:tout )?comme (?:avant|c'etait)|annule(?:r)?|reviens en arriere|retour en arriere|defais(?:-le)?|annule (?:ca|cela|tout ca|la derniere (?:action|chose|modification)|ce que tu (?:viens|as) (?:de |d')?\\w+(?: de \\w+)?)|laisse tomber ce que tu (?:viens|as) \\w+(?: de faire)?)\\s*[.!]*$")) {
            return ReponseIA(listOf(ActionIA("annuler").apply { sure = true }), "")
        }
        // « Je suis du matin lundi et mardi » : des postes de travail
        val inn = intentions(t)
        if (!inn.supp && !inn.modif && !inn.question) {
            horairesTravail(t, maintenant, horaires)?.let { return ReponseIA(listOf(ActionIA("horaires").apply { postes = it; sure = true }), "") }
        }
        // « Supprime mes rendez-vous de la semaine prochaine »
        if (tous(mots(s)).none { it.score >= 9 }) suppressionsPeriode(t, agenda)?.let { l -> return ReponseIA(l.onEach { it.sure = true }, if (l.isEmpty()) "Tu n'as rien à ce moment-là, je n'ai rien supprimé." else "") }
        // « Qu'est-ce que j'ai lundi ? », puis « supprime tout ça » : ceux-là, pas tout l'agenda
        if (s.contient("\\b(?:supprim|effac|enlev|annul|vire|retir)\\w*[- ](?:tout ca|tout cela|tout ceci|les tous|-les|les)\\s*[.!]*$|^(?:supprime|efface|annule|enleve|vire)[- ]les\\b")) {
            val l = memoire.derniereListe.mapNotNull { agenda.get(it) }
            if (recente && l.isNotEmpty()) return ReponseIA(l.map { ActionIA("supprimer").apply { cible = it; sure = true } }, "")
            if (s.contient("\\btout (?:ca|cela|ceci)\\b")) return ReponseIA(emptyList(), "Tout quoi ? Dis-moi quels rendez-vous supprimer.")
        }
        // « Tu veux que je le note ? » — « oui »
        val proposee = memoire.proposition
        if (proposee != null && recente) {
            memoire.proposition = null
            if (s.contient("^(?:oui|ouais|ok|d'accord|vas-y|go|note-le|ouais vas-y|oui stp|oui merci)\\b")) return enReponse(listOf(Lu(listOf(creation(proposee)))))
            if (s.contient("^(?:non|pas besoin|laisse|c'est bon)\\b")) return ReponseIA(emptyList(), "D'accord, je ne note rien.")
        }
        // « Crée un rendez-vous chez le garagiste », puis « lundi à 8 h »
        val incomplet = memoire.incomplet
        if (incomplet != null && recente && !estQuestion(s) && !s.contient("$RxSupp|$RxModif")) {
            val lp = lirePhrase(t, maintenant)
            if ((lp.jour != null || lp.heure != null || lp.repetition != null) && lp.titre.isBlank()) {
                memoire.incomplet = null
                return enReponse(listOf(Lu(listOf(creation("$incomplet $t")))))
            }
        }
        // « Supprime tout », « vide mon agenda », « supprime tous les anniversaires »
        suppressionGroupe(s)?.let { return it }
        // Une question : on répond, sans rien toucher
        if (estQuestion(s)) return question(t, s)?.let { memoire.derniereQuestion = t; memoire.derniereDemande = null; memoire.quand = maintenant; ReponseIA(emptyList(), it) }

        // « Supprime l'événement » puis « le foot » : la demande en attente vise ce rendez-vous
        val attente = memoire.attente
        if (attente != null && recente && !s.contient(RxSupp) && !s.contient(RxModif) && !s.contient(RxCreer)) {
            val c = chercher(mots(s)).singleOrNull() ?: parDate(t, s)
            if (c != null) {
                memoire.attente = null
                val lu = partie(attente, null, c) ?: return null
                lu.vise?.let { memoire.dernier = it.id }
                return enReponse(listOf(lu))
            }
        }

        // « Deux jours avant l'anniversaire de Léa », « le lendemain du foot » : une vraie date
        val tr = resoudreRelatifs(resoudreRangs(t))
        if (tr != t) return comprendreDates(tr)
        return comprendreDates(t)
    }

    private fun comprendreDates(t: String): ReponseIA? {
        val s = net(t)
        // Deux demandes dans une phrase (« supprime le coiffeur et mets le dentiste à 16 h »)
        val coupes = (Regex(RxSuite).findAll(s).map { it.range } + Regex("\\s*,\\s*et\\s+(?=[a-z][a-z-]+(?:\\s+et\\s+[a-z-]+)?\\s+(?:au|aux|a la|a l')\\s)").findAll(s).map { it.range } +
            // « ajoute Lucas au raid et Sophie à la soirée » : deux rendez-vous, chacun ses gens
            Regex("\\s+et\\s+(?=[a-z][a-z-]+\\s+(?:au|aux|a la|a l')\\s)").findAll(s).filter { x -> s.substring(0, x.range.first).contient("\\b(?:au|aux|a la|a l')\\s+[a-z]") }.map { it.range } +
            // « supprime le raid de la semaine prochaine et le foot » : un autre rendez-vous nommé après « et »
            Regex("\\s+et\\s+(?=(?:le|la|les|l'|mon|ma|mes)\\s*[a-z])").findAll(s).filter { x ->
                val apres = tous(mots(s.substring(x.range.last + 1))).filter { it.score >= 9 && it.premier <= 1 }
                val avant = tous(mots(s.substring(0, x.range.first))).filter { it.score >= 9 }
                apres.isNotEmpty() && avant.isNotEmpty() && apres.none { a -> avant.any { it.rv.id == a.rv.id } } && s.substring(0, x.range.first).split(' ').size > 4
            }.map { it.range }).sortedBy { it.first }
            // (deux règles peuvent trouver le même « , et » : les coupures ne se chevauchent pas)
            .fold(mutableListOf<IntRange>()) { l, r -> if (l.isEmpty() || r.first > l.last().last) l += r else if (r.last > l.last().last) l[l.size - 1] = l.last().first..r.last; l }.toList()
        val liens = listOf("") + coupes.map { s.substring(it) }
        val parties = mutableListOf<String>()
        var debut = 0
        for (r in coupes) { parties += t.substring(debut, r.first); debut = r.last + 1 }
        parties += t.substring(debut)
        val lus = mutableListOf<Lu>()
        var precedente: RendezVous? = null
        for ((i, p0) in parties.withIndex()) {
            var p = p0.trim(' ', ',', '.')
            // « …, et à la place mets un cinéma à 21 h » : le jour de ce qui vient d'être supprimé
            if (i > 0 && (liens[i] + " " + net(p0)).contient("\\ba la place\\b") && lirePhrase(p, maintenant).jour == null) {
                lus[i - 1].actions.firstOrNull { it.action == "supprimer" }?.let { a ->
                    val j = a.periode?.let { lirePeriode(it, maintenant)?.debut } ?: a.cible?.let { (agenda.prochaine(it, maintenant) ?: it.quand).toLocalDate() }
                    if (j != null) p = "$p le ${j.format(FormatJourAn)}"
                }
            }
            // (« …, et Tom au dentiste » : le verbe de la première partie)
            if (i > 0 && !net(p).contient("$RxSupp|$RxModif|$RxCreer|^(?:garde|conserve|laisse|previens|rappelle)")) {
                trouve(parties[0], "^\\s*(?<v>[\\p{L}]+)\\b").g("v")?.takeIf { net(it).contient("^(?:ajout|rajout|invit|retir|enlev|supprim|mets|met)") }?.let { p = "$it $p" }
            }
            val sp = net(p)
            // « Tom ne pourra pas venir à la soirée, enlève-le » : c'est déjà fait
            if (i > 0 && sp.contient("^(?:enleve|retire|vire|supprime|desinvite)[- ](?:le|la|les)\\s*[.!]?$") && lus[i - 1].actions.any { it.retirerPersonnes.isNotEmpty() }) { lus += Lu(); continue }
            // « …, mets-le en important » juste après une création : pour le nouveau rendez-vous
            val nomsSeuls = trouve(p, "^(?:[Aa]joute|[Rr]ajoute|[Ii]nvite)s?\\s+[A-ZÉÈÀ][\\p{L}-]+(?:\\s*(?:,|et)\\s*[A-ZÉÈÀ][\\p{L}-]+)*\\s*(?:aussi)?\\s*[.!]?$", ci = false).ok
            if (i > 0 && lus[i - 1].actions.any { it.action == "creer" } && (sp.contient(RxPronom) || nomsSeuls || chercher(mots(sp)).isEmpty()) &&
                !sp.contient("$RxSupp|^(?:ajoute|rajoute|cree|mets|met|programme|note)s?\\s+(?:moi\\s+)?(?:un|une|des)\\b")) {
                val ph = lus[i - 1].actions.first { it.action == "creer" }.phrase ?: ""
                val lp = lirePhrase(ph, maintenant)
                val provisoire = AgendaIA.nouveau(lp.titre.ifBlank { "x" }, lp.quand ?: maintenant, lp.repetition, Sorte.RDV, lp.journee, maintenant)
                val a = listeDite(p, sp)?.let { l -> ActionIA("modifier", chosesAPrendre = l) } ?: modification(p, sp, Cible(provisoire, 1, -1, -1, true, false))
                if (a != null) {
                    // (une liste pour « une course » : ce sont des courses à faire)
                    if (a.chosesAPrendre.isNotEmpty()) lus[i - 1].actions.first { it.action == "creer" }.let { c -> c.phrase = c.phrase?.remplace("\\b([Cc])ourse\\b(?!s)", "$1ourses") }
                    lus += Lu(listOf(a)); continue
                }
            }
            var lu = partie(p, precedente, null, seule = parties.size == 1, phraseEntiere = t)
            // « Pour la soirée chez Max, ajoute Paul, … » : d'abord de quoi on parle
            if (lu == null && i == 0 && parties.size > 1 && !sp.contient("$RxSupp|$RxModif|$RxCreer")) {
                val c = chercher(mots(sp)).singleOrNull()
                if (c != null) { precedente = c.rv; lus += Lu(); continue }
            }
            if (lu == null) return null
            lus += lu
            precedente = lu.actions.lastOrNull()?.cible ?: lu.vise ?: precedente
            // « Change l'heure du dentiste, mets 16 h 30 » : la question « quand ? » de la première partie a sa réponse
            if (i > 0 && lus[i - 1].actions.isEmpty() && lus[i - 1].vise != null && lu.actions.any { it.cible?.id == lus[i - 1].vise!!.id }) lus[i - 1] = Lu()
            // « Supprime le ElleFest du 29 et mets-le sur 3 jours » : c'est un changement, pas une suppression
            if (i > 0 && lus[i - 1].actions.singleOrNull()?.action == "supprimer" && lu.actions.any { it.action == "modifier" && it.cible?.id == lus[i - 1].actions[0].cible?.id }) {
                lus[i - 1] = Lu()
            }
        }
        lus.firstOrNull { it.attente != null }?.let { memoire.attente = it.attente; memoire.quand = maintenant }
        lus.lastOrNull { it.vise != null }?.let { memoire.dernier = it.vise!!.id; memoire.quand = maintenant }
        // (retenue pour « et le coiffeur aussi »)
        if (parties.size == 1 && lus.single().actions.any { it.cible != null }) { memoire.derniereDemande = t; memoire.derniereQuestion = null }
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
            s.contient("\\b(?:j'ai |je n'ai )?plus besoin d(?:u|e la|e l'|es)\\s+(?:$RxGenerique|rdv)|\\b(?:est|sont)\\s+annule|\\bn'a plus lieu|\\bn'aura pas lieu|\\bne (?:vais|va) plus\\b|\\bn'y vais plus|\\blaisse tomber\\s+(?:le|la|les|l'|mon|ma)") ||
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
        // (« crée l'anniversaire de Paul », « anniversaire de mamie » ne visent pas celui de Léa)
        if (c != null && c.score < 9 && imposee == null && !supp && !s.contient(RxModif)) c = null
        // « Rendez-vous chez le dentiste à Metz jeudi à 14 h 30 », « Dentiste à Épinal le 20 novembre à 9 h » : un rendez-vous
        // dit tel quel, comme dans la barre, est un nouveau rendez-vous (« le dentiste, c'est jeudi » change l'existant)
        if (c != null && imposee == null && !supp && !s.contient(RxModif) && !pronom && !estFait(s)) {
            val lpn = lirePhrase(t, maintenant)
            val debutNom = c.premier == 0 || s.contient("^(?:rendez-vous|rendez vous|rdv|nouveau rendez-vous|nouveau rdv)\\b")
            if (debutNom && !c.defini && (lpn.jour != null || lpn.repetition != null) && lpn.heure != null) return Lu(listOf(creation(if (seule) phraseEntiere else t)))
            if (s.contient("^(?:rendez-vous|rendez vous|rdv)\\s+(?:chez|avec|a|au)\\b") && (lpn.jour != null || lpn.repetition != null)) return Lu(listOf(creation(if (seule) phraseEntiere else t)))
        }
        val generiqueDefini = s.contient("\\b(?:le|l'|ce|cet|cette|mon|ma|mes|les)\\s*$RxGenerique")
        if (c == null && (generiqueDefini || ((supp || s.contient(RxModif)) && !creer))) {
            c = designe(t, s)
            if (c == null && ambigus.isNotEmpty()) return Lu(dit = lequel(ambigus), attente = t)
        }
        if (c == null && pronom) {
            val r = precedente ?: memoire.dernier?.let { agenda.get(it) }
            if (r != null) c = Cible(r, 1, -1, -1, true, false)
        }
        // « Crée un rendez-vous chez le dentiste pour mon fils », « j'ai une soirée chez Julie » : un nouveau, pas celui qui existe
        if (c != null && imposee == null && !supp && !(pronom && c.premier < 0)) {
            val objet = trouve(s, "\\b(?:ajout|rajout|cree|creer|mets|met|mettre|programm|planifi|note|fais|reserve|j'ai|il y a|y a)\\S*\\s+(?:moi\\s+|-moi\\s+)?(?:un|une)\\s+(?<n>[a-z'-]+)")
            val detail = objet.g("n")?.let { it.contient("^(?:notes?|precision|commentaire|info|information|personne|liste|article|produit|heure|demi-heure|minute|jour|semaine|fois|lien)$") || (it == "rappel" && rappelsTexte(t).isNotEmpty()) } ?: true
            if (objet.ok && !detail && (c.premier < 0 || mots(s).indexOfFirst { it.debut >= objet.index } <= c.premier)) c = null
        }
        // « …, et rappelle-moi une heure avant », « et ajoute Tom » : la suite parle du même rendez-vous
        val lp0 = lirePhrase(t, maintenant)
        if (c == null && precedente != null && imposee == null &&
            (!creer || rappelsTexte(t).isNotEmpty() || (lp0.jour == null && lp0.heure == null && lp0.repetition == null))) c = Cible(precedente, 1, -1, -1, true, false)
        // « Ajoute Tom et Max » juste après avoir parlé d'un rendez-vous : c'est pour lui
        val dernier = (precedente ?: memoire.dernier?.let { agenda.get(it) })
        if ((c == null || c.premier >= 0 && !c.defini) && dernier != null && imposee == null && trouve(t, "^(?:[Aa]joute|[Rr]ajoute|[Ii]nvite)s?\\s+[A-ZÉÈÀ][\\p{L}-]+(?:\\s*(?:,|et)\\s*[A-ZÉÈÀ][\\p{L}-]+)*\\s*(?:aussi|avec)?\\s*[.!]?$", ci = false).ok) {
            c = Cible(dernier, 1, -1, -1, true, false)
        }
        // « Avec Julie et Marc. », « À Metz, avec Paul. » juste après avoir parlé d'un rendez-vous : ses détails
        if ((c == null || s.contient("^(?:et\\s+)?avec\\s")) && dernier != null && imposee == null && !supp && !creer) {
            // « C'est chez Paul, à Nancy. »
            val sansCest = t.remplace("^(?:[Cc]'est|[Cc]e sera|[Çç]a se passe|[Ee]t c'est)\\s+", "")
            val cl = trouve(sansCest, "^(?:[Àà]|[Aa]u|[Cc]hez)\\s+(?<l>[^.!?]+?)\\s*[.!]?$")
            if (sansCest != t && cl.ok && !momentDit(cl.g("l")!!) && !cl.g("l")!!.contains(Regex("\\bavec\\b"))) {
                val lieu = sansCest.trim().trimEnd('.', '!').remplace("^(?:[Àà]|[Aa]u)\\s+", "").remplace(",\\s*(?:à|a|au)\\s+", ", ")
                return Lu(listOf(ActionIA("modifier", lieu = lieu).apply { cible = dernier }), vise = dernier)
            }
            val d = trouve(t, "^(?:(?:[Àà]|[Aa]u|[Cc]hez)\\s+(?<l>[^,]+?)\\s*(?:,\\s*|$))?(?:(?:et\\s+)?[Aa]vec\\s+(?<p>[A-ZÉÈÀ][\\p{L}-]+(?:\\s*(?:,|et)\\s*[A-ZÉÈÀ][\\p{L}-]+)*))?\\s*[.!]?$", ci = false)
            if (d.ok && (d.a("l") || d.a("p")) && !momentDit(d.g("l") ?: "x")) {
                val a = ActionIA("modifier")
                d.g("l")?.let { a.lieu = it.trim() }
                d.g("p")?.let { a.personnes = it.split(Regex("\\s*(?:,|\\bet\\b)\\s*")).filter { x -> x.isNotBlank() } }
                return Lu(listOf(a.apply { cible = dernier }), vise = dernier)
            }
        }
        // « À 10 h. », « ah non, plutôt 8 h » juste après « ajoute tennis samedi » : seulement un moment, pour le rendez-vous dont on parle
        val tMoment = t.remplace("^(?:(?:ah|oh|bah|ben|non|mais|enfin|disons|euh|plut[ôo]t|finalement|en fait|vers|plus|pour|mets[- ]le|mets[- ]la|mets|met|passe|fixe)\\b[\\s,]*)+", "")
        val lpm = lirePhrase(tMoment, maintenant)
        if (c == null && dernier != null && imposee == null && !supp && (!creer || tMoment != t) && lpm.titre.isBlank() && (lpm.heure != null || lpm.jour != null)) {
            c = Cible(dernier, 1, -1, -1, true, false)
        }
        // « Retire Tom. » juste après avoir parlé de la soirée
        if (c == null && dernier != null && imposee == null && dernier.avec.isNotEmpty() &&
            trouve(s, "^(?:retire|enleve|vire|supprime|desinvite)s?\\s+[a-z' -]+[.!]?$").ok && dernier.avec.any { s.contient("\\b" + Regex.escape(sansAccents(it)) + "\\b") }) {
            c = Cible(dernier, 1, -1, -1, true, false)
        }

        // « Inès ne vient plus mais Clara vient », « Lucas sera là aussi » : qui vient, qui ne vient plus
        if (c != null && !supp) {
            val viennent = tous(t, "\\b(?<n>[A-ZÉÈÀ][\\p{L}-]+)\\s+(?:vient|viendra|sera l[àa]|nous rejoint|se joint|est invit[ée]e?)\\b(?!\\s+(?:plus|pas))", ci = false)
                .map { it.g("n")!! }.filter { n -> clesTitre(c!!.rv.titre).none { sansAccents(n) == it } && c!!.rv.avec.none { it.equals(n, true) } }
            if (viennent.isNotEmpty()) {
                val a = detailRetire(t, s, c.rv, false) ?: ActionIA("modifier")
                a.personnes = viennent
                return Lu(listOf(a.apply { cible = c!!.rv }), vise = c.rv)
            }
        }
        // --- Retirer un détail (un rappel, quelqu'un, une chose à prendre), pas le rendez-vous lui-même
        if (c != null) {
            val gens = retires(s, c.rv)
            if (supp && gens.isNotEmpty()) return Lu(listOf(ActionIA("modifier", retirerPersonnes = gens).apply { cible = c!!.rv }))
            detailRetire(t, s, c.rv, supp, parInvite = c.masque != null)?.let { a -> return Lu(listOf(a.apply { cible = c!!.rv })) }
        }

        // « … mais garde le dentiste » : rien à faire pour lui
        if (c != null && s.contient("^\\s*(?:garde|conserve|laisse)\\b")) return Lu(dit = "Je garde ${titreDe(c.rv)}.")

        // --- Supprimer
        if (supp) {
            val rv = c?.rv ?: return introuvable(t, s)
            // « tous les raids de décembre sauf celui de Noël » : chaque fois de la période, sauf celle-là
            val sauf = trouve(t, "\\s*,?\\s*\\bsauf\\s+(?:celui|celle|ceux|celles|le|la)?\\s*(?:de|du|d')?\\s*(?<x>.+?)\\s*[.!]?$")
            if (rv.laRepetition != null && sauf.ok) {
                val sansSauf = t.substring(0, sauf.index)
                val p = periodeDans(sansSauf.substring(minOf(sansSauf.length, if (c.dernier >= 0) m[c.dernier].fin else 0)))
                    ?: if (c.premier >= 0) periodeDans(sansSauf.substring(0, m[c.premier].debut)) else null
                val per = p?.let { lirePeriode(it, maintenant) }
                val x = net(sauf.g("x")!!)
                val garde: LocalDate? = when {
                    x.contient("\\bnoel\\b") -> LocalDate.of(per?.debut?.year ?: today.year, 12, 25)
                    x.contient("\\b(?:nouvel an|jour de l'an)\\b") -> LocalDate.of((per?.debut?.year ?: today.year) + 1, 1, 1)
                    else -> dateDite(sauf.g("x")!!)
                }
                if (per?.debut != null && per.fin != null && garde != null) {
                    val fois = agenda.entre(per.debut.atStartOfDay(), per.fin.plusDays(1).atStartOfDay()).filter { it.rv.id == rv.id }.map { it.prochain.toLocalDate() }
                    // (celle du même jour, sinon celle de la même semaine)
                    val gardee = fois.firstOrNull { it == garde } ?: fois.firstOrNull { io.github.jordanjacquot.evermind.comprendre.lundiDe(it) == io.github.jordanjacquot.evermind.comprendre.lundiDe(garde) }
                    val enlever = fois.filter { it != gardee }
                    if (enlever.isNotEmpty()) return Lu(enlever.map { j -> ActionIA("supprimer", periode = "le ${j.format(FormatJourAn)}").apply { cible = rv } })
                }
            }
            if (rv.laRepetition != null && !s.contient("\\b(?:toujours|completement|entierement|definitivement)\\b")) {
                // « le raid de demain et celui de la semaine prochaine » : deux périodes
                val et = trouve(t, "\\s+et\\s+(?:celui|celle|ceux|celles)\\b(?<r>.*)$")
                if (et.ok) {
                    val p1 = periodeDans(t.substring(if (c.dernier >= 0) m[c.dernier].fin else 0, et.index))
                    val p2 = periodeDans(et.g("r")!!)
                    if (p1 != null && p2 != null) return Lu(listOf(p1, p2).map { p -> ActionIA("supprimer", periode = p).apply { cible = rv } })
                }
                periodeApres(t, c)?.let { return Lu(listOf(ActionIA("supprimer", periode = it).apply { cible = rv })) }
                if (c.premier >= 0) {
                    val avant = t.substring(0, m[c.premier].debut).remplace("(?:\\s+(?:pour|de|du|des|les|le|la|l'|au|aux|à|a))+\\s*$", "")
                    periodeDans(avant)?.let { return Lu(listOf(ActionIA("supprimer", periode = it).apply { cible = rv })) }
                }
            }
            return Lu(listOf(ActionIA("supprimer").apply { cible = rv }))
        }
        // --- Une série qui s'arrête pour de bon (« le loyer, je ne le paie plus »)
        if (c != null && c.rv.laRepetition != null && !s.contient("\\b(?:a partir|apres|des|depuis|fin)\\b") &&
            s.contient("\\b(?:ne|n')\\s*(?:le |la |les |l'|y )?\\w+\\s+plus\\b|\\bplus jamais\\b|\\bn'existe plus\\b|\\bc'est termine\\b")) {
            return Lu(listOf(ActionIA("supprimer", periode = "à partir d'aujourd'hui").apply { cible = c!!.rv }))
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
        // --- Une liste (« la liste de courses : lait, œufs, pain », « il faut acheter du lait et des œufs »)
        if (c != null && !supp && (c.defini || c.premier < 0 || ditAvecArticle(m, c.rv))) listeDite(t, s)?.let { l -> return Lu(listOf(ActionIA("modifier", chosesAPrendre = l).apply { cible = c!!.rv }), vise = c.rv) }
        // --- C'est fait
        if (c != null && estFait(s) && !s.contient(RxModif)) return Lu(listOf(ActionIA("fait").apply { cible = c!!.rv }))
        // --- Créer : un verbe pour créer, et pas « le foot » qui existe déjà
        val lp = lirePhrase(t, maintenant)
        if ((creer && (c == null || !c.defini)) || c?.indefini == true) return Lu(listOf(creation(if (seule) phraseEntiere else t)))
        // --- Changer un rendez-vous qui existe
        if (c != null) {
            val a = modification(t, s, c)
                // « Décale le dentiste » sans dire quand : on le demande, et la réponse (« à 17 h ») ira à lui
                ?: return if (s.contient(RxModif) || s.contient("^\\s*(?:mets|met|passe)\\b")) Lu(dit = "Quand veux-tu mettre ${titreDe(c.rv)} ?", vise = c.rv) else null
            return Lu(listOf(a.apply { cible = c.rv }), vise = c.rv)
        }
        // --- Une phrase qui raconte quelque chose (« le métro de Metz est en grève demain ») : on propose de la noter
        if (!creer && lp.heure == null && s.contient("^(?:le|la|les|l'|mon|ma|mes|il|elle|ils|on)\\b.*\\b(?:est|sont|sera|seront|a|ont|fait|fera)\\b") && lp.titre.split(' ').size >= 4) {
            memoire.proposition = if (seule) phraseEntiere else t
            return Lu(dit = "D'accord. Tu veux que je le note dans ton agenda ?")
        }
        // --- Un rendez-vous dit tel quel (« dentiste jeudi à 15 h ») : noté
        if (lp.manque == Manque.RIEN && lp.titre.isNotEmpty() && (lp.jour != null || lp.repetition != null || lp.quand != null) && !s.contient(RxModif)) {
            return Lu(listOf(creation(if (seule) phraseEntiere else t)))
        }
        // « Décale le rendez-vous », « supprime » : on demande lequel
        if (s.contient(RxModif)) return introuvable(t, s)
        return null
    }

    /** Un nouveau rendez-vous : la phrase telle quelle, et ce que la lecture habituelle laisse passer. */
    private fun creation(phrase: String): ActionIA {
        // « de 10 h à midi » : lu comme « de 10 h à 12 h »
        val ph = phrase.remplace("\\bau plus (?:tard|t[ôo]t)\\s+", "").remplace("(\\bde\\s+\\d{1,2}\\s*h(?:\\s*\\d{2})?\\s+(?:à|a|jusqu'à)\\s+)midi\\b", "$112 h")
            .remplace("(\\bde\\s+\\d{1,2}\\s*h(?:\\s*\\d{2})?\\s+(?:à|a|jusqu'à)\\s+)minuit\\b", "$10 h")
        var phCree = ph.remplace("\\s*,?\\s*\\b[àa] la place\\b", "")
        // « jusqu'à fin novembre » : jusqu'au dernier jour du mois
        trouve(phCree, "\\bjusqu'(?:à|a|au)\\s+(?:la\\s+)?fin\\s+(?:de\\s+|d')?(?<m>$RxMois)\\b").takeIf { it.ok }?.let { x ->
            val mo = io.github.jordanjacquot.evermind.comprendre.mois(x.g("m")!!)
            val an = if (mo < today.monthValue) today.year + 1 else today.year
            phCree = phCree.substring(0, x.index) + "jusqu'au ${java.time.YearMonth.of(an, mo).atEndOfMonth().format(FormatJourAn)}" + phCree.substring(x.index + x.valeur.length)
        }
        // « mardi et vendredi … jusqu'au … » : chaque semaine ces jours-là
        if (net(phCree).contient("\\bjusqu")) phCree = phCree.remplace("(?<!\\b(?:le|les|tous les|chaque)\\s)\\b($RxJour)\\s+et\\s+($RxJour)\\b(?!s)", "tous les $1s et $2s")
        var articles: List<String> = emptyList()
        var adresse: String? = null
        // « au 12 rue Serpenoise à Metz » : l'adresse (et la ville)
        trouve(phCree, "\\s*,?\\s*\\b(?:au|à|a)\\s+(?<a>\\d+(?:\\s*(?:bis|ter))?,?\\s+(?:rue|avenue|av\\.?|boulevard|bd|place|chemin|all[ée]e|impasse|route|quai|cours|square|rond-point)\\s+[^,]+?)(?:\\s+(?:à|a)\\s+(?<v>[A-ZÉÈ][\\p{L}'-]+(?:[ -][A-ZÉÈ\\p{L}][\\p{L}'-]+){0,3}))?(?=\\s+(?:le\\s+\\d|$RxJour|demain|ce\\s|cette\\s|à\\s+\\d|a\\s+\\d|\\d{1,2}\\s*h)|\\s*,|\\s*$)", ci = false).takeIf { it.ok }?.let { x ->
            adresse = x.g("a")!!.trim() + (x.g("v")?.let { ", $it" } ?: "")
            phCree = phCree.substring(0, x.index) + " " + phCree.substring(x.index + x.valeur.length)
        }
        // « …, n'oublie pas mon ordonnance », « prends ton CV », « ramène des chips et des bières » : à prendre
        trouve(phCree, "\\s*,\\s*(?:et\\s+)?(?:n'oublie pas|oublie pas|pense à prendre|prends|prend|ram[èe]ne|apporte|emporte|am[èe]ne)\\s+(?<l>[^,.]+?)\\s*[.!]?$").takeIf { it.ok }?.let { x ->
            val l = x.g("l")!!.split(Regex("\\s*(?:,|\\bet\\b)\\s*")).map { it.trim().remplace("^(?:(?:du|de la|des|de|un|une|le|la|les|mon|ma|mes|ton|ta|tes|son|sa|ses)\\s+|(?:de l'|d'|l'))", "") }.filter { it.isNotBlank() }
            if (l.isNotEmpty() && l.none { it.split(' ').size > 4 }) { articles = l.map { majuscule(it) }; phCree = phCree.substring(0, x.index) }
        }
        val marque = tous(ph, "(?:\\s+avec\\s+(?=(?:du|de la|de l'|des|d')\\s*\\p{L})|\\s*[,.]?\\s*(?:et\\s+|donc\\s+)?(?:il (?:me |nous )?faut(?: acheter| prendre)?|je veux|j'ai besoin d[e']|à acheter)\\b|\\s*[,.]?\\s*(?:et|donc|puis)?\\s*(?:tu\\s+)?(?:mets|met|note|ajoute|rajoute)(?:[- ]y|[- ]moi|[- ]dedans)?\\b|\\s*[,.]?\\s*(?:et\\s+|donc\\s+)?(?:avec\\s+)?(?:la|ma|une)\\s+liste\\b(?:\\s+de courses)?|\\s*:)").firstOrNull { it.index > 3 } ?: io.github.jordanjacquot.evermind.comprendre.Trouve.Rien
        if (marque.ok && marque.index > 3) {
            val reste = ph.substring(marque.index + marque.valeur.length)
                .remplace("^[\\s,:]*(?:(?:donc|alors|euh|bon|et|il (?:me |nous )?faut|je veux|j'ai besoin d[e']|on a besoin d[e']|acheter|à acheter|y a|il y a|c'est|dedans)\\b[\\s,:]*)*", "")
            val l = reste.split(Regex("\\s*(?:,|;|\\bet\\b|\\bpuis\\b)\\s*"))
                .map { it.trim().remplace("^(?:(?:du|de la|des|de|un|une|le|la|les|quelques)\\s+|(?:de l'|d'|l'))", "").trim(' ', '.', '!') }
                .filter { it.isNotEmpty() }
            if (l.size >= 2 && l.none { it.split(' ').size > 4 || net(it).contient("\\b(?:$RxJour|demain|\\d{1,2}\\s*h)\\b") }) {
                articles = l.map { majuscule(it) }
                phCree = ph.substring(0, marque.index).trim(' ', ',', '.')
                // (une liste d'achats : « les courses », pas une course à pied)
                phCree = phCree.remplace("\\b([Cc])ourse\\b(?!s)", "$1ourses").remplace("\\b(?:des|les|mes)\\s+(courses)\\b", "$1").remplace("\\b(?:je dois |il faut que je |faut que je )?faire\\s+(courses)\\b", "$1")
                // « pour vendredi » : le jour seulement
                phCree = phCree.remplace("\\bpour\\s+(?=(?:$RxJour|demain|apr[èe]s-demain|le\\s+\\d|ce\\s+|cette\\s+|la semaine))", "")
            }
        }
        phCree = phCree.remplace("^((?:[Aa]joute|[Rr]ajoute|[Cc]r[ée]e|[Mm]ets|[Pp]rogramme|[Nn]ote)(?:[- ]moi)?)\\s+(?:le|la|les)\\s+(?=\\p{L})", "$1 un ")
        val a = ActionIA("creer", phrase = phCree.remplace("\\s+", " ").trim())
        if (articles.isNotEmpty()) a.chosesAPrendre = articles
        adresse?.let { a.lieu = it }
        // « avec Tom et Inès » oublié quand un lieu suit (« … au Bouchon lyonnais »)
        run {
            val g = trouve(phCree, "\\bavec\\s+(?<n>[A-ZÉÈÀ][\\p{L}-]+(?:\\s*(?:,|et)\\s*[A-ZÉÈÀ][\\p{L}-]+)*)", ci = false)
            if (g.ok) a.personnes = g.g("n")!!.split(Regex("\\s*(?:,|\\bet\\b)\\s*")).filter { it.isNotBlank() }
        }
        return a
    }

    /**
     * « Enlève le rappel de la veille pour le dentiste », « plus de rappels pour le foot », « le dentiste n'est plus
     * avec maman », « je n'ai plus besoin de l'ordonnance », « j'ai préparé ma carte vitale » : un détail du rendez-vous.
     */
    private fun detailRetire(t: String, s: String, rv: RendezVous, supp: Boolean, parInvite: Boolean = false): ActionIA? {
        val enlever = supp || s.contient("\\b(?:plus de|pas de|sans|plus besoin|pas besoin|n'est plus|ne sera plus|ne vient plus|ne viendra plus|ne vient pas|ne viendra pas|n'a plus|ne (?:pourra|peut|pourra finalement) pas (?:venir|etre la)|ne sera pas la|se desiste|annule sa venue)\\b")
        if (!enlever) {
            val pretes = rv.aPreparer.filter { !it.fait && mentionne(s, it.texte) }
            if (pretes.isNotEmpty() && (s.contient("\\b(?:j'ai|c'est|deja|bien)\\b.*\\b(?:prepare|pris|mis|range|sorti|imprime|trouve|recupere|achete)") || s.contient("^\\s*(?:coche|cocher|valide|marque)\\b"))) {
                return ActionIA("modifier").apply { chosesFaites = pretes.map { it.texte } }
            }
            return null
        }
        // (« supprime le rappel pour le loyer » : le loyer est lui-même une chose à faire, c'est lui qu'on enlève)
        if (s.contient("\\b(?:rappels?|alertes?|notifications?|alarmes?)\\b") && !(supp && rv.sorte == Sorte.A_FAIRE && rappelsTexte(t).isEmpty())) {
            return ActionIA("modifier").apply { retirerRappels = rappelsTexte(t).ifEmpty { listOf("*") } }
        }
        val cles = clesTitre(rv.titre)
        val gens = rv.avec.filter { n -> sansAccents(n) !in cles && nomme(s, n) }
        if (gens.isNotEmpty() && !parInvite) return ActionIA("modifier", retirerPersonnes = gens)
        val choses = rv.aPreparer.filter { mentionne(s, it.texte) }
        if (choses.isNotEmpty()) return ActionIA("modifier").apply { retirerChoses = choses.map { it.texte } }
        return null
    }

    /** Tous les mots d'une chose à prendre (« Carte Vitale ») sont dans la phrase. */
    private fun mentionne(s: String, texte: String): Boolean {
        val w = mots(s).map { it.texte.trimEnd('\'') }
        val ms = clesTitre(texte)
        return ms.isNotEmpty() && ms.all { k -> w.any { proche(it, k) } }
    }

    /** « le premier lundi de décembre », « le dimanche dans deux semaines » : la vraie date. */
    private fun resoudreRangs(t: String): String {
        var r = t
        val rang = trouve(net(r), "\\b(?:le\\s+)?(?<n>premier|1er|deuxieme|second|2e|troisieme|3e|quatrieme|4e|dernier)\\s+(?<j>$RxJour)\\s+(?:de|d'|du mois de|du mois d')\\s*(?<m>$RxMois)(?:\\s+(?<a>\\d{4}))?\\b")
        if (rang.ok) {
            val mo = io.github.jordanjacquot.evermind.comprendre.mois(rang.g("m")!!)
            val an = rang.g("a")?.toInt() ?: if (mo < today.monthValue) today.year + 1 else today.year
            val jour = java.time.DayOfWeek.of(((io.github.jordanjacquot.evermind.comprendre.jourSemaine(rang.g("j")!!) + 6) % 7) + 1)
            val n = rang.g("n")!!
            val d = if (n == "dernier") LocalDate.of(an, mo, 1).with(java.time.temporal.TemporalAdjusters.lastInMonth(jour))
                else LocalDate.of(an, mo, 1).with(java.time.temporal.TemporalAdjusters.dayOfWeekInMonth(when { n.startsWith("prem") || n == "1er" -> 1; n.startsWith("deux") || n.startsWith("sec") || n == "2e" -> 2; n.startsWith("trois") || n == "3e" -> 3; else -> 4 }, jour))
            r = r.substring(0, rang.index) + "le ${d.format(FormatJourAn)}" + r.substring(rang.index + rang.valeur.length)
        }
        val dans = trouve(net(r), "\\b(?:le\\s+)?(?<j>$RxJour)\\s+(?:dans|d'ici)\\s+(?<n>une|deux|trois|quatre|\\d+)\\s+semaines?\\b")
        if (dans.ok) {
            val n = nombre(dans.g("n")!!.remplace("^une$", "un")) ?: 1
            val lundi = io.github.jordanjacquot.evermind.comprendre.lundiDe(today.plusWeeks(n.toLong()))
            val d = lundi.plusDays(((io.github.jordanjacquot.evermind.comprendre.jourSemaine(dans.g("j")!!) + 6) % 7).toLong())
            r = r.substring(0, dans.index) + "le ${d.format(FormatJourAn)}" + r.substring(dans.index + dans.valeur.length)
        }
        return r
    }

    /** « avec maman », « avec Tom et Max » : ce mot est quelqu'un qui vient, pas le nom d'un rendez-vous. */
    private fun apresAvec(m: List<Mot>, k: Int): Boolean {
        var j = k - 1
        while (j >= 0 && j >= k - 6) {
            val w = m[j].texte
            if (w == "avec") return true
            if (w != "et" && w != "ma" && w != "mon" && w != "mes" && !w[0].isLetter()) return false
            if (w in setOf("le", "la", "les", "l'", "du", "au", "chez", "pour", "a", "de", "d'")) return false
            j--
        }
        return false
    }

    /** Le rendez-vous est-il nommé quelque part avec « le », « la », « l'événement » ? */
    private fun ditAvecArticle(m: List<Mot>, rv: RendezVous): Boolean {
        val cles = clesTitre(rv.titre)
        for ((i, w) in m.withIndex()) {
            if (cles.none { proche(w.texte.trimEnd('\''), it) }) continue
            for (j in i - 1 downTo maxOf(0, i - 3)) { if (m[j].texte in Definis) return true; if (m[j].texte in Indefinis) break }
        }
        return false
    }

    /**
     * Les articles d'une liste dite pour un rendez-vous : « liste : lait, œufs, pain », « il faut acheter du lait, des œufs
     * et du pain », « ajoute du lait et des œufs à la liste », « la liste c'est lait, pain et pâtes ». null sinon.
     */
    private fun listeDite(t: String, s: String): List<String>? {
        if (s.contient("\\bnotes?\\b")) return null
        val partitif = trouve(t, "(?:^|,\\s*)(?:[Mm]ets|[Mm]et|[Aa]joute|[Rr]ajoute|[Nn]ote|[Pp]rends|[Aa]ch[èe]te)s?\\s+(?:aussi\\s+|encore\\s+|en plus\\s+)?(?<l>(?:du|de la|de l'|des|d')\\s*\\p{L}.+?)\\s*[.!]?$")
        val brut = when {
            partitif.ok && !t.contains(':') -> partitif.g("l")
            trouve(t, "^(?:[Mm]ets|[Mm]et|[Aa]joute|[Rr]ajoute|[Nn]ote)[- ](?:y|dedans|moi|-y)\\s+(?<l>.+?)\\s*[.!]?$").ok ->
                trouve(t, "^(?:[Mm]ets|[Mm]et|[Aa]joute|[Rr]ajoute|[Nn]ote)[- ](?:y|dedans|moi|-y)\\s+(?<l>.+?)\\s*[.!]?$").g("l")
            trouve(t, "\\b(?:[Aa]joute|[Rr]ajoute|[Mm]ets|[Nn]ote)s?\\s+(?<l>.+?)\\s+(?:à|a|dans|sur)\\s+(?:la|ma)\\s+liste\\b").ok ->
                trouve(t, "\\b(?:[Aa]joute|[Rr]ajoute|[Mm]ets|[Nn]ote)s?\\s+(?<l>.+?)\\s+(?:à|a|dans|sur)\\s+(?:la|ma)\\s+liste\\b").g("l")
            s.contient("\\bliste\\b[^:]*?(?:c'est|ce sera|il y a)\\b") -> trouve(t, "\\bliste\\b[^:]*?(?:c'est|ce sera|il y a)\\s+(?<l>.+?)\\s*[.!]?$").g("l")
            t.contains(':') && s.contient("\\b(?:liste|courses?|acheter|achats?|ajoute|rajoute|mets|prendre)\\b") -> t.substringAfter(':').trim().trimEnd('.', '!')
            else -> trouve(t, "\\b(?:acheter|ach[èe]te|il (?:me |nous )?faut(?: acheter| prendre)?|j'ai besoin d[e']|on a besoin d[e'])\\s+(?<l>(?:du|de la|de l'|des|d'|un|une|quelques|\\d+)\\b.+?)\\s*[.!]?$").g("l")
        } ?: return null
        val coupe = brut.remplace("\\s+(?:pour|à|a|dans)\\s+(?:la|le|les|l')\\s*(?:liste|courses?|[ée]v[ée]nement).*$", "")
        val articles = coupe.split(Regex("\\s*(?:,|;|\\bet\\b|\\bpuis\\b)\\s*"))
            .map { it.trim().remplace("^(?:aussi|encore|en plus)\\s+", "").remplace("^(?:(?:du|de la|des|de|un|une|le|la|les|quelques|mon|ma|mes)\\s+|(?:de l'|d'|l'))", "").trim(' ', '.', '!') }
            .filter { it.isNotEmpty() }
        // (une vraie liste : des articles courts, pas une phrase)
        if (articles.isEmpty() || articles.any { it.split(' ').size > 4 } || articles.any { net(it).contient("^(?:$RxJour|demain|\\d{1,2}\\s*h)|\\b(?:pour|demain|$RxJour)\\b") }) return null
        if (articles.size < 2 && !s.contient("\\bliste\\b|:") && !partitif.ok) return null
        return articles.map { majuscule(it) }
    }

    /** Le moment sans « au lieu de … » (« c'est à 16 h au lieu de 15 h »). */
    private fun momentSans(t: String, c: Cible, lu: Modifs): String? {
        val sans = t.remplace("\\s*,?\\s*\\bau lieu d(?:e|u|es|')\\s*[^,.;!?]*", "")
        return moment(sans, net(sans), c, lu)
    }

    /**
     * « Mets le dentiste jeudi, non vendredi », « décale le foot à 20 h, ah non, à 21 h », « supprime le dentiste, euh
     * non pardon, le coiffeur » : ce qui suit la correction remplace la fin de ce qui la précède.
     */
    private fun corrigeEnRoute(t: String): String {
        val m = trouve(t, "^(?<a>.{4,}?)(?:\\s*(?:\\.\\.\\.|…)\\s*|\\s*,\\s*)(?:(?:euh|ah|oh|bah)\\s*,?\\s*)?(?:non|enfin|pardon|ou plut[ôo]t|je veux dire|je voulais dire)(?:\\s*,?\\s*(?:pardon|plut[ôo]t|non|euh))*\\s*,?\\s+(?<b>.+)$")
        if (!m.ok) return t
        val a = m.g("a")!!.trim()
        val b = m.g("b")!!.trim()
        val sb = net(b)
        // Une nouvelle demande entière (« … non, mets le foot sur deux jours ») : elle seule
        if (sb.contient("^(?:$RxSupp|$RxModif|$RxCreer)") || sb.contient("^(?:annule|garde|laisse)\\b")) return b
        // Sinon, la même sorte de chose à la fin de « a » : une heure, un jour, ou un rendez-vous
        val rx = when {
            sb.contient("^(?:a|vers|pour)?\\s*\\d{1,2}\\s*h") -> "(?:à|a|vers|pour)?\\s*\\d{1,2}\\s*h(?:\\s*\\d{2})?"
            sb.contient("^(?:le\\s+)?(?:$RxJour|demain|apres-demain|\\d{1,2}(?:er)?\\b)") -> "(?:le\\s+)?(?:$RxJour|demain|apr[èe]s-demain|\\d{1,2}(?:er)?(?:\\s+$RxMois)?)(?:\\s+prochain)?"
            sb.contient("^(?:le|la|les|l'|mon|ma|mes|du|au)\\b") -> "(?:le|la|les|l'|mon|ma|mes|du|au)\\s*[\\p{L}' -]+"
            else -> return t
        }
        val dernier = tous(a, rx).lastOrNull() ?: return t
        return a.substring(0, dernier.index).trimEnd() + " " + b
    }

    /**
     * « Si je décale le foot à vendredi 15 h, ça tombe en même temps que quelque chose ? » : on regarde, sans rien
     * changer.
     */
    private fun hypothese(t: String, s: String): String? {
        val sansSi = t.remplace("^\\s*[Ss]i\\s+(?:je\\s+|j'|on\\s+)?", "").remplace("\\s*,.*$", "")
        val lu = partie(sansSi.replaceFirstChar { it.uppercase() }, null, null) ?: return null
        val a = lu.actions.singleOrNull { it.action == "modifier" && it.phrase != null } ?: return null
        val rv = a.cible ?: return null
        val p = lirePhrase(a.phrase!!, maintenant)
        val q = p.quand ?: return null
        val fin = p.fin ?: rv.fin?.let { q.plus(java.time.Duration.between(rv.quand, it)) }
        val conf = agenda.conflits(q, fin, rv.id)
        return if (conf.isEmpty()) "Non, ${quandRV(rv.copy(quand = q, fin = fin), q, today)}, tu n'as rien d'autre. Je n'ai rien changé."
        else "Oui, ça tomberait en même temps que ${conf.joinToString(" et ") { titreDe(it.rv) }}. Je n'ai rien changé."
    }

    /**
     * « deux jours avant l'anniversaire de Léa », « le lendemain du foot », « la veille du dentiste », « le même jour que
     * la soirée », « juste après le foot », « une heure après le dentiste » : remplacés par la vraie date (et l'heure).
     */
    private fun resoudreRelatifs(t: String): String {
        val s = net(t)
        // (« préviens-moi une heure avant le foot » : un rappel, pas une date)
        if (s.contient("\\b(?:previens|rappelle|avertis|alerte|notifie)[- ]moi\\b") && !s.contient("\\b(?:ajoute|cree|mets|met)\\b")) return t
        if (s.contient("\\b(?:enleve|retire|supprime|plus de|pas de|sans)\\b.*\\brappels?\\b")) return t
        val n = "(?:un|une|deux|trois|quatre|cinq|six|sept|huit|dix|quinze|\\d+)"
        val x = trouve(s, "(?:\\b(?:au|a|le|la)\\s+)?\\b(?<r>(?<n>$n)\\s+(?<u>jours?|heures?|semaines?|minutes?|min)\\s+(?<s>avant|apres)|(?<l>(?:le |la )?(?:lendemain|surlendemain|veille)\\s+(?:de la |de l'|du |des |de |d')|(?:le )?meme jour que\\s+(?:le |la |l')?|juste (?<j>avant|apres)\\s+(?:le |la |l')?))(?:\\s*(?:de la |de l'|du |des |de |d'|le |la |l'))?")
        if (!x.ok) return t
        // Le rendez-vous de référence : nommé juste après
        val apres = s.substring(x.index + x.valeur.length)
        val decal = x.index + x.valeur.length
        val m = mots(s)
        val ref = tous(m).filter { it.score >= 9 && m[it.premier].debut >= decal && m[it.premier].debut - decal <= 12 }.minByOrNull { it.premier } ?: return t
        val finRef = m[ref.dernier].fin
        val rv = ref.rv
        fun date(r: LocalDateTime): LocalDateTime? {
            val l = x.g("l")
            val j = x.g("j")
            return when {
                l != null && l.contains("surlendemain") -> r.plusDays(2)
                l != null && l.contains("lendemain") -> r.plusDays(1)
                l != null && l.contains("veille") -> r.minusDays(1)
                l != null && l.contains("meme jour") -> r
                j == "apres" -> rv.fin?.let { r.plus(java.time.Duration.between(rv.quand, it)) } ?: r.plusHours(1)
                j == "avant" -> r.minusHours(1)
                else -> {
                    val k = (nombre(x.g("n")!!.remplace("^une?$", "un")) ?: 1).toLong() * if (x.g("s") == "avant") -1 else 1
                    val u = x.g("u")!!
                    when { u.startsWith("j") -> r.plusDays(k); u.startsWith("h") -> r.plusHours(k); u.startsWith("s") -> r.plusWeeks(k); else -> r.plusMinutes(k) }
                }
            }
        }
        // (une série : la première fois qui tombe encore à venir)
        var depuis = today.atStartOfDay().minusDays(31)
        var d: LocalDateTime? = null
        for (k in 0 until 60) {
            val r = agenda.prochaine(rv, depuis) ?: break
            val c = date(r) ?: break
            if (!c.toLocalDate().isBefore(today)) { d = c; break }
            depuis = r.plusMinutes(1)
            if (rv.laRepetition == null) break
        }
        if (d == null) return t
        val avecHeure = x.g("u")?.let { it.startsWith("h") || it.startsWith("m") } == true || x.g("j") != null
        val dit = "le ${d.format(FormatJourAn)}" + if (avecHeure && !rv.journee) " à ${heurePc(d)}" else ""
        return (t.substring(0, x.index) + dit + " " + t.substring(finRef)).remplace("\\s+", " ").remplace("\\s+([,.!?])", "$1")
            .remplace(",?\\s*le m[êe]me jour\\b", "").trim()
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
        val l = tous(m).filter { it.score >= 9 }.sortedBy { it.premier }
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
            val fins = mutableListOf<Int>()
            for (cle in clesTitre(rv.titre)) {
                val i = m.indices.firstOrNull { k -> proche(m[k].texte.trimEnd('\''), cle) && !apresAvec(m, k) } ?: -1
                if (i < 0) continue
                places += i
                fins += m.indexOfLast { proche(it.texte.trimEnd('\''), cle) }
                val w = m[i].texte.trimEnd('\'')
                val exact = w == cle || w == cle + "s" || w == cle + "x"
                if (cle in MotsFaibles) faible++ else fort += if (exact) 10 else 9
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
            res += Cible(rv, fort + faible, p, fins.max(), def, indef)
        }
        return res
    }

    /** Plusieurs rendez-vous du même nom : celui de la date dite, sinon le prochain. */
    private fun departager(cibles: List<Cible>, t: String): Cible? {
        // « le dentiste n'est plus avec maman » : « maman » est quelqu'un qui va au dentiste, pas « Appeler maman »
        val s = net(t)
        val sansInvites = cibles.filter { x ->
            val dits = clesTitre(x.rv.titre).filter { k -> mots(s).any { proche(it.texte.trimEnd('\''), k) } }
            !(dits.isNotEmpty() && cibles.any { y -> y !== x && dits.all { d -> y.rv.avec.any { sansAccents(it) == d } } })
        }
        if (sansInvites.size == 1) return sansInvites[0]
        // « Le dentiste m'a appelé » : celui qui est dit avec un article, pas « Appeler maman »
        cibles.filter { it.defini }.singleOrNull()?.let { if (cibles.count { !it.defini } >= 1) return it }
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
    /** Plusieurs rendez-vous désignés de la même façon (deux à 19 h) : on demandera lequel. */
    private var ambigus: List<RendezVous> = emptyList()

    private fun designe(t: String, s: String): Cible? {
        ambigus = emptyList()
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
            if (ce.size > 1) { ambigus = ce; return null }
            val r = ce.singleOrNull() ?: return null
            return Cible(r, 1, -1, -1, true, false, masque = h.index until h.index + h.valeur.length)
        }
        val avec = trouve(s, "$RxGenerique\\s+avec\\s+(?<n>[a-z-]+)")
        if (avec.ok) {
            val ce = agenda.liste.filter { r -> r.avec.any { sansAccents(it) == avec.g("n") } }
            ce.singleOrNull()?.let { return Cible(it, 1, -1, -1, true, false, masque = avec.index until avec.index + avec.valeur.length) }
            if (ce.size > 1) { ambigus = ce; return null }
        }
        return parDate(t, s)
    }

    /** « Supprime tout », « vide mon agenda », « supprime tous les anniversaires », « … les rendez-vous passés ». */
    private fun suppressionGroupe(s: String): ReponseIA? {
        val m = trouve(s, "\\b(?:supprim|suprim|sucrim|effac|enlev|retir|vire|vide|annul|degag|nettoi)\\w*\\s+(?:moi\\s+|-moi\\s+)?(?<q>tout mon agenda|mon agenda|l'agenda|tout|tous|toutes)\\b(?<r>.*)$")
        if (!m.ok) return null
        // (« tous les raids de décembre sauf celui de Noël » : un rendez-vous nommé)
        val avantSauf = m.g("r")!!.split(Regex("\\b(?:sauf|a part|excepte|hormis|mis a part|en dehors d[eu])\\b"))[0]
        if (tous(mots(avantSauf)).any { it.score >= 9 }) return null
        // « Supprime tout sauf l'anniversaire de Léa » : ceux-là restent
        val sauf = trouve(m.g("r")!!, "\\b(?:sauf|a part|excepte|hormis|mis a part|en dehors d[eu])\\b(?<x>.*)$")
        val gardes = if (sauf.ok) tous(mots(sauf.g("x")!!)).filter { it.score >= 9 || tous(mots(sauf.g("x")!!)).none { o -> o.score >= 9 } }.map { it.rv.id }.toSet() else emptySet()
        if (sauf.ok && gardes.isEmpty()) return ReponseIA(emptyList(), "Je n'ai pas trouvé ce que tu veux garder, je n'ai rien supprimé.")
        val reste = if (sauf.ok) m.g("r")!!.substring(0, sauf.index) else m.g("r")!!
        // « supprime tout le raid », « tous les raids de novembre » : un rendez-vous nommé, pas tout l'agenda
        if (tous(mots(reste)).any { it.score >= 9 }) return null
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
            r.id !in gardes && (sorte == null || r.sorte == sorte) &&
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

    /** La phrase parle-t-elle de cette personne ? (« ma mère » pour Maman, « mon père » pour Papa) */
    private fun nomme(s: String, n: String): Boolean {
        val x = sansAccents(n)
        val autres = Proches[x].orEmpty()
        return s.contient("\\b" + Regex.escape(x) + "\\b") || autres.any { s.contient("\\b$it\\b") }
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
        return periodeDans(apres)
    }

    /** La première période lisible dans ces mots (« de demain seulement », « de la semaine prochaine »). */
    private fun periodeDans(apres0: String): String? {
        val apres = apres0.remplace("\\b(?:tous les|toutes les|chaque|les)\\s+(?:$RxJour)s?\\s+(?:de\\s+|d'|du mois de\\s+|du mois d')?(?=$RxMois|la semaine|ce mois|le mois)", "")
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
        // Une adresse dite (« a déménagé au 8 rue de la Gare à Metz »)
        trouve(t, "\\b(?:au|à|a)\\s+(?<a>\\d+(?:\\s*(?:bis|ter))?,?\\s+(?:rue|avenue|av\\.?|boulevard|bd|place|chemin|all[ée]e|impasse|route|quai|cours|square)\\s+[^,.]+?)(?:\\s+(?:à|a)\\s+(?<v>[A-ZÉÈ][\\p{L}'-]+(?:[ -][A-ZÉÈ\\p{L}][\\p{L}'-]+){0,3}))?\\s*[.!]?$", ci = false).takeIf { it.ok && !s.contient("\\badresse\\b[^:]*:") }?.let {
            a.lieu = it.g("a")!!.trim() + (it.g("v")?.let { v -> ", $v" } ?: ""); return a
        }
        // L'adresse (« change l'adresse du dentiste : 5 place Stanislas à Nancy »)
        trouve(t, "\\b(?:adresse|lieu|endroit)\\b[^:]*:\\s*(?<l>.+?)\\s*[.!]?$").takeIf { it.ok }?.let { a.lieu = it.g("l"); fait = true }
        // Un lien (« mets le lien https://discord.gg/xyz pour le raid »)
        trouve(t, "(?<u>https?://\\S+|www\\.\\S+)").takeIf { it.ok }?.let { a.lien = it.g("u")!!.trimEnd('.', ',', ')'); fait = true }
        if (fait && a.lieu != null) return a
        // Le lieu (« le foot, c'est au stade Gerland »)
        val lieu = trouve(t, "\\b(?:c'est|ce sera|ça se passe|ca se passe|se passe|se passera|a lieu|aura lieu|se fait|se fera|se d[ée]roule|se d[ée]roulera|sera|se trouve|a d[ée]m[ée]nag[ée]|d[ée]m[ée]nage)\\s+(?:plut[ôo]t\\s+)?(?<p>à la|à l'|au|aux|à|a|chez|en|dans|sur)\\s+(?<l>.+?)\\s*[.!]?$")
        if (lieu.ok && !momentDit(lieu.g("l")!!)) {
            val l = lieu.g("l")!!.remplace("\\s+(?:finalement|maintenant|d[ée]sormais)$", "")
            a.lieu = if (lieu.g("p")!!.lowercase() == "chez") "chez $l" else l
            fait = true
        }
        if (lu.rappels.isNotEmpty() && s.contient("\\b(?:previens|rappelle|avertis|alerte|rappel|notifie)")) { a.rappels = lu.rappels; fait = true }
        if (fait) return a

        // --- Le moment
        val phrase = moment(t, s, c, lu) ?: run {
            // « Mets le foot à Woippy plutôt » : pas un moment, un lieu
            val l = trouve(t, "\\b(?:à|au|aux|chez)\\s+(?<l>[A-ZÉÈ][\\p{L}'-]+(?:[ -][A-ZÉÈ\\p{L}][\\p{L}'-]+){0,3})\\s*(?:plut[ôo]t|maintenant|finalement|d[ée]sormais)?\\s*[.!?]?$", ci = false)
            if (l.ok && !momentDit(l.g("l")!!) && s.contient("\\b(?:mets|met|mettre|deplace|bouge|change|passe)\\b")) { a.lieu = l.g("l"); return a }
            return null
        }
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
        val base = agenda.prochaine(rv, today.atStartOfDay()) ?: rv.quand
        fun le(q: java.time.LocalDateTime) = "le ${q.format(FormatJourAn)}" + if (rv.journee) "" else " à ${heurePc(q)}"
        // Une heure dite en plus (« à la semaine d'après à 20 h ») : elle remplace celle du rendez-vous
        val heureDite = trouve(s, "\\ba\\s+(?<h>\\d{1,2})\\s*h(?:\\s*(?<m>\\d{2}))?\\b")
        fun avecHeure(q: java.time.LocalDateTime) =
            if (heureDite.ok && !rv.journee) q.withHour(heureDite.g("h")!!.toInt().coerceIn(0, 23)).withMinute(heureDite.g("m")?.toInt() ?: 0) else q
        // « au lendemain », « un jour plus tôt », « deux heures plus tard »
        if (s.contient("\\b(?:au|le) lendemain\\b")) return le(avecHeure(base.plusDays(1)))
        val pt = trouve(s, "\\b(?<n>un|une|deux|trois|quatre|cinq|six|sept|huit|dix|quinze|\\d+)\\s+(?<u>jours?|heures?|semaines?|minutes?|min)\\s+plus\\s+(?<s>tot|tard)\\b")
        if (pt.ok) {
            val n = (nombre(pt.g("n")!!.remplace("^une?$", "un")) ?: 1).toLong() * if (pt.g("s") == "tot") -1 else 1
            val u = pt.g("u")!!
            return le(when { u.startsWith("j") -> base.plusDays(n); u.startsWith("h") -> base.plusHours(n); u.startsWith("s") -> base.plusWeeks(n); else -> base.plusMinutes(n) })
        }
        lu.decalage?.let { dec ->
            if (!heureDite.ok || rv.journee || !s.contient("\\bsemaine|\\bjours?\\b")) return dec
            val q = lirePhrase("x $dec", maintenant).quand ?: return dec
            return le(avecHeure(q))
        }
        // « Le dentiste finit à 16 h », « le foot commence à 18 h 30 et finit à 20 h »
        val rxH = "(?:a\\s+|vers\\s+)?(?<h>\\d{1,2})\\s*h(?:\\s*(?<m>\\d{2}))?"
        val fin = trouve(s, "\\b(?:finit|finira|fini|termine|se termine|se finit|s'arrete|jusqu'a)\\s+$rxH")
        if (fin.ok && !rv.journee) {
            val deb = trouve(s, "\\b(?:commence|debute|demarre|commencera)\\s+$rxH")
            val q = if (deb.ok) base.withHour(deb.g("h")!!.toInt().coerceIn(0, 23)).withMinute(deb.g("m")?.toInt() ?: 0) else base
            var f = base.withHour(fin.g("h")!!.toInt().coerceIn(0, 23)).withMinute(fin.g("m")?.toInt() ?: 0)
            if (!f.isAfter(q)) f = f.plusDays(1)
            return "le ${q.format(FormatJourAn)} de ${heurePc(q)} à ${heurePc(f)}"
        }
        // Une série chaque semaine qui passe à plusieurs jours (« le raid, c'est le mercredi et le vendredi maintenant »)
        val rep0 = rv.laRepetition
        if (rep0 != null && rep0.type == "semaine") {
            val jours = tous(s, "\\b$RxJour", ci = false).map { it.valeur }.distinct()
            if (jours.size >= 2) return "le ${jours.joinToString(" et le ")}" + if (rv.journee) "" else " à ${heurePc(avecHeure(base))}"
        }
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
        // (« à 16 h au lieu de 15 h » : l'ancienne heure ne compte pas)
        if (s.contient("\\bau lieu d")) return momentSans(t, c, lu)
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
        val q = s.contient("\\?\\s*$") || s.contient("^\\s*(?:qu'est-ce|c'est quand|quand|ou\\b|ou est|c'est ou|combien|quel(?:le)?s?|a quelle|avec qui|est-ce que j'ai|est-ce que je|ai-je|suis-je|j'ai quoi|j'ai quelque chose|dis-moi|montre|liste(?! de courses| des courses| d'achats| de choses)|rappelle-moi (?:quand|ce que|ou|avec qui|a quelle|qui|combien|si|comment))\\b")
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
            s.contient("\\b(?:medica(?:l|ux)|sante|medecins?|soins)\\b") -> Sorte.SANTE
            s.contient("\\b(?:sorties|soirees|fetes)\\b") -> Sorte.SORTIE
            s.contient("\\b(?:sports|seances de sport|entrainements)\\b|\\bcomme (?:sport|seance)\\b|\\bde sport\\b") -> Sorte.SPORT
            s.contient("\\b(?:travail|boulot|reunions|pro|professionnels)\\b") && !s.contient("\\bje\\b") -> Sorte.TRAVAIL
            s.contient("\\b(?:jeux|parties|raids|sessions)\\b") && c == null -> Sorte.JEU
            s.contient("\\b(?:voyages|deplacements)\\b") -> Sorte.VOYAGE
            else -> null
        }
        // « Quels sont les rendez-vous où Inès vient ? », « qu'est-ce que je fais avec Tom ? »
        val gens = agenda.liste.flatMap { it.avec }.distinct().filter { n -> s.contient("\\b" + Regex.escape(sansAccents(n)) + "\\b") }
            .filter { n -> c == null || clesTitre(c.rv.titre).none { k -> k == sansAccents(n) } }
        if (gens.isNotEmpty() && c?.let { it.score < 9 || clesTitre(it.rv.titre).any { k -> gens.any { g -> sansAccents(g) == k } } } != false) {
            val v = agenda.aVenir().filter { f -> f.rv.avec.any { it in gens } }
            memoire.derniereListe = v.map { it.rv.id }
            return if (v.isEmpty()) "Rien de prévu avec ${liste(gens)}." else "Avec ${liste(gens)} : " + v.joinToString(" ; ") { ligne(it, true) } + "."
        }
        // « Est-ce que j'ai quelque chose à Lyon ? »
        if (c == null) trouve(s, "\\b(?:a|au|sur|vers)\\s+(?<l>[a-z][a-z' -]{2,})\\s*\\??$").g("l")?.let { l ->
            val v = agenda.aVenir().filter { sansAccents(it.rv.lieu).contains(l.trim()) }
            if (v.isNotEmpty()) { memoire.derniereListe = v.map { it.rv.id }; return "À ${majuscule(l.trim())} : " + v.joinToString(" ; ") { ligne(it, true) } + "." }
        }
        // « Qu'est-ce qui est le plus tôt, le dentiste ou le foot ? », « le dentiste c'est avant ou après le foot ? »
        val deux = enumeres(mots(s), s).ifEmpty { tous(mots(s)).filter { it.score >= 9 }.distinctBy { it.rv.id }.takeIf { it.size == 2 }.orEmpty() }
        if (deux.size >= 2 && s.contient("\\b(?:plus tot|plus tard|avant ou apres|apres ou avant|en premier|d'abord|le premier|lequel|avant|apres)\\b")) {
            val f = deux.map { it.rv to (agenda.prochaine(it.rv, if (it.rv.journee) today.atStartOfDay() else maintenant) ?: it.rv.quand) }.sortedBy { it.second }
            val (a, b) = f[0] to f[1]
            return "${titreDe(a.first)} est avant : ${quandRV(a.first, a.second, today)}. Puis ${titreDe(b.first)}, ${quandRV(b.first, b.second, today)}."
        }
        if (c != null && sorte == null) {
            memoire.dernier = c.rv.id
            // « avec qui je vais chez le dentiste et à quelle heure » : chaque chose demandée
            val bouts = s.split(Regex("\\s+et\\s+(?=(?:a quelle|ou|avec qui|qui|quand|combien|c'est quand|quel|ce que|ce qu'|qu'est-ce|si|comment))"))
            return bouts.map { surRendezVous(c.rv, it) }.distinct().joinToString(" ")
        }
        // « Quels rendez-vous sont importants ? »
        if (s.contient("\\bimportants?\\b|\\bimportantes?\\b")) {
            val v = agenda.aVenir().filter { it.rv.important }
            return if (v.isEmpty()) "Aucun rendez-vous à venir n'est marqué important." else "Importants : " + v.joinToString(" ; ") { ligne(it, true) } + "."
        }
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

    /** « 30 min », « 1 h », « 1 h 30 », « 3 jours » */
    private fun enDuree(d: java.time.Duration): String {
        val min = d.toMinutes()
        return when {
            min >= 24 * 60 && min % (24 * 60) == 0L -> "${min / (24 * 60)} jours"
            min < 60 -> "$min min"
            min % 60 == 0L -> "${min / 60} h"
            else -> "${min / 60} h ${"%02d".format(min % 60)}"
        }
    }

    private fun surRendezVous(rv: RendezVous, s: String): String {
        val nom = titreDe(rv)
        val depuis = if (rv.journee) today.atStartOfDay() else maintenant
        val fois = agenda.prochaine(rv, depuis) ?: rv.quand
        val quand = quandRV(rv, fois, today)
        val duree = rv.fin?.let { java.time.Duration.between(rv.quand, it) }
        return when {
            // « Est-ce que j'ai déjà payé le loyer ? »
            s.contient("\\b(?:deja|bien)\\b.*\\b(?:paye|fait|appele|envoye|regle|achete|rendu|pris)\\b|\\bc'est fait\\b|\\best (?:deja )?fait\\b") ->
                if (rv.fait) "Oui, $nom est fait." else "Non, $nom n'est pas encore fait ($quand)."
            // « J'ai quoi après le dentiste ? »
            s.contient("\\bapres\\b") && s.contient("\\b(?:quoi|qu'est-ce|ensuite|suite|suivant)\\b") -> {
                val finFois = duree?.let { fois.plus(it) } ?: fois.plusMinutes(1)
                val suite = agenda.entre(finFois, finFois.plusDays(31)).firstOrNull { it.rv.id != rv.id && !it.rv.fait }
                if (suite == null) "Après $nom, tu n'as rien de prévu pour l'instant." else "Après $nom, tu as ${titreDe(suite.rv)}, ${quandRV(suite.rv, suite.prochain, today)}."
            }
            s.contient("\\bimportante?\\b") -> if (rv.important) "Oui, $nom est marqué important." else "Non, $nom n'est pas marqué important."
            // « Dans combien de jours c'est l'anniversaire de Léa ? »
            s.contient("\\bdans combien\\b|\\bcombien de jours (?:avant|jusqu)|\\bc'est dans combien\\b|\\bentre maintenant et\\b|\\bd'ici (?:le|la|l')|\\bcombien de temps (?:avant|jusqu|il reste|reste)") -> {
                val r = relatif(fois, today)
                "$nom, c'est $r ($quand)."
            }
            s.contient("\\bcombien de temps\\b|\\bdure\\b|\\bduree\\b") ->
                if (duree == null || rv.journee) "Je n'ai pas d'heure de fin pour $nom." else "$nom dure ${enDuree(duree)} ($quand)."
            s.contient("\\b(?:finis|finit|finira|termine|fin|sors|sortirai)\\b") ->
                if (duree == null || rv.journee) "Je n'ai pas d'heure de fin pour $nom." else "$nom finit à ${fois.plus(duree).toLocalTime().enTexte()}."
            s.contient("\\b(?:toutes les|tous les|chaque|revient|se repete)\\b") ->
                rv.laRepetition?.let { "$nom revient ${it.enTexte(today)}. La prochaine fois : $quand." } ?: "Non, $nom n'a lieu qu'une fois : $quand."
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
            s.contient("\\b(?:apporter|prendre|preparer|emporter|amener|besoin|oublier|acheter|achats|liste)\\b") ->
                if (rv.aPreparer.isEmpty()) "Rien à prendre pour $nom." else if (s.contient("\\b(?:acheter|achats|liste)\\b")) "Pour $nom : ${liste(rv.aPreparer.filter { !it.fait }.map { it.texte })}." else "Pour $nom, prends : ${liste(rv.aPreparer.map { it.texte })}."
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
        if (s.contient("\\b(?:libre|dispo|disponible|le temps|du temps|un creneau|de la place)\\b")) {
            if (fois.isEmpty()) return "$quand, tu es libre."
            return "$quand, tu as déjà ${liste(fois.map { ligne(it, false) })}."
        }
        if (fois.isEmpty()) return "$quand, tu n'as rien de prévu."
        memoire.derniereListe = fois.map { it.rv.id }.distinct()
        if (s.contient("^(?:ou|c'est ou)\\b|\\bou (?:est-ce que|dois|je dois|vais|je vais)\\b|\\badresses?\\b")) {
            return "$quand : " + fois.joinToString(" ; ") { ligne(it, debut != fin) + if (it.rv.lieu.isNotBlank()) ", ${it.rv.lieu}" else ", sans adresse" } + "."
        }
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
