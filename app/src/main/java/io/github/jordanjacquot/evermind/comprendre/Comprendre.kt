package io.github.jordanjacquot.evermind.comprendre

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit

// Comprendre un rendez-vous écrit (ou dit) en français de tous les jours : la même lecture que comprendre.ps1
// d'Evermind PC (Read-Phrase), réécrite en Kotlin. Rien n'est envoyé sur Internet.
// Les deux doivent rester d'accord : le test PhrasesEvermindPcTest compare leurs réponses sur près de 3 000 phrases.
// Les heures sont comptées en minutes depuis minuit (comme le TimeSpan de PowerShell, elles peuvent dépasser 24 h).

/** Une semaine (du lundi au dimanche) ou un week-end. */
data class Bloc(val debut: LocalDate, val fin: LocalDate)

/** Ce qu'il manque pour créer le rendez-vous. */
enum class Manque(val code: String) {
    RIEN(""),
    HEURE("heure"),    // le jour est compris, pas l'heure
    JOUR("jour"),      // l'heure ou la répétition, pas le jour
    QUAND("quand"),    // ni le jour ni l'heure
    PASSE("passe"),    // ce moment est déjà passé
    INVALIDE("invalide"), // cette date n'existe pas (31 février...)
}

/** Ce qui a été compris dans une phrase. */
data class Phrase(
    val titre: String,
    /** Le (premier) rendez-vous ; null s'il manque quelque chose. */
    val quand: LocalDateTime?,
    val repetition: Repetition?,
    val manque: Manque,
    /** Le jour et l'heure compris, même s'il manque l'autre (pour pré-remplir le formulaire). */
    val jour: LocalDate?,
    val heure: LocalTime?,
    /** Code de la sorte (anniv, sante... rdv). */
    val sorte: String,
    val journee: Boolean,
    val fin: LocalDateTime?,
    val lieu: String,
    val avec: List<String>,
    val lien: String,
    val important: Boolean,
    val rappels: List<String>?,
    val naissance: Int?,
    val aPreparer: List<String>,
    /** Des jours séparés (« les 3 et 10 novembre ») : un rendez-vous pour chacun. */
    val jours: List<LocalDate>,
    /** Plusieurs semaines (« la première semaine des 3 prochains mois ») : un rendez-vous pour chacune. */
    val blocs: List<Bloc>,
)

private class Details {
    var lieu = ""
    var avec: List<String> = emptyList()
    var lien = ""
    var important = false
    var rappels: List<String>? = null
    var naissance: Int? = null
    var aPreparer: List<String> = emptyList()
    var finJour: LocalDate? = null
    var nbJours: Int? = null
    var jours: List<LocalDate> = emptyList()
    var blocs: List<Bloc> = emptyList()
}

private class AgeDit(val n: Int, val futur: Boolean)

private fun heures(minutes: Int) = (minutes / 60) % 24

private fun LocalDate.a(minutes: Int): LocalDateTime = atStartOfDay().plusMinutes(minutes.toLong())

private fun minutesDe(h: Int, m: Int) = h * 60 + m

// ---------------------------------------------------------------------------
// Semaines entières, du lundi au dimanche (et week-ends)
// ---------------------------------------------------------------------------
private fun rangDe(s: String): Int {
    val t = s.lowercase()
    return when {
        t.contient("^(prem|1)") -> 1
        t.contient("^(deux|sec|2)") -> 2
        t.contient("^(trois|3)") -> 3
        t.contient("^(quat|4)") -> 4
        else -> -1
    }
}

private fun blocSemaine(debut: LocalDate, we: Boolean) = if (we) Bloc(debut, debut.plusDays(1)) else Bloc(debut, debut.plusDays(6))

/** La n-ième semaine d'un mois commence au n-ième lundi ; le n-ième week-end, au n-ième samedi. */
private fun nieme(an: Int, mois: Int, rang: Int, we: Boolean): Bloc {
    val premier = LocalDate.of(an, mois, 1)
    val der = premier.dernierDuMois
    val jour = if (we) 6 else 1
    if (rang > 0) return blocSemaine(premier.plusDays((((jour - premier.jds) + 7) % 7).toLong()).plusDays(7L * (rang - 1)), we)
    if (we) return blocSemaine(der.minusDays((((der.jds - 6) + 7) % 7).toLong()), true)
    var l = lundiDe(der)
    if (l.plusDays(6).isAfter(der)) l = l.minusDays(7)
    return blocSemaine(l, false)
}

private fun blocsSemaine(st: Lecture, today: LocalDate): List<Bloc>? {
    val rxRang = "(?<r>premi[èe]re?|1re|1er|1[eè]re|deuxi[èe]me|second|seconde|2e|troisi[èe]me|3e|quatri[èe]me|4e|derni[èe]re?)"
    val rxU = "(?<u>semaine|week-?end)"
    val rxDe = "(?:(?:de|d'|du mois de|du mois d'|en)\\s*)?"
    val rxN = "(\\d{1,2}|deux|trois|quatre|cinq|six|sept|huit|neuf|dix|onze|douze)"
    val avant = "\\b(?:pendant\\s+|durant\\s+|sur\\s+|tout\\s+|toute\\s+)?(?:la|le)\\s+"
    // « la première semaine des 3 prochains mois », « le premier week-end de chaque mois pendant 3 mois »
    var m = st.morceau("$avant$rxRang\\s+$rxU\\s+(?:des\\s+(?<n1>${rxN.drop(1)}\\s+prochains\\s+mois|de\\s+chaque\\s+mois\\s+(?:pendant|durant|sur)\\s+(?<n2>${rxN.drop(1)}\\s+mois)\\b")
    if (m.ok) {
        val r = rangDe(m.g("r")!!)
        val n = nombre(m.g("n1") ?: m.g("n2")) ?: 0
        val we = m.g("u")!!.contient("week")
        val blocs = mutableListOf<Bloc>()
        var mois = today.premierDuMois
        while (blocs.size < n && blocs.size < 24) {
            val b = nieme(mois.year, mois.monthValue, r, we)
            if (!b.debut.isBefore(today)) blocs += b   // une semaine déjà commencée ne compte pas
            mois = mois.plusMonths(1)
        }
        return blocs
    }
    // « la première semaine de novembre », « le dernier week-end d'octobre », « la deuxième semaine du mois prochain »
    m = st.morceau("$avant$rxRang\\s+$rxU\\s+(?:$rxDe(?<mo>$RxMois)(?:\\s+(?<y>\\d{4}))?|du\\s+mois\\s+(?<pro>prochain))")
    if (m.ok) {
        val r = rangDe(m.g("r")!!)
        val we = m.g("u")!!.contient("week")
        val mo: Int
        val y: Int
        if (m.a("pro")) { val x = today.premierDuMois.plusMonths(1); mo = x.monthValue; y = x.year }
        else { mo = mois(m.g("mo")!!); y = m.g("y")?.toInt() ?: today.year }
        var b = nieme(y, mo, r, we)
        if (!m.a("y") && !m.a("pro") && b.fin.isBefore(today)) b = nieme(y + 1, mo, r, we)
        return listOf(b)
    }
    // « les deux prochains week-ends », « les 3 prochaines semaines »
    m = st.morceau("\\b(?:pendant\\s+|durant\\s+)?les\\s+(?<n>${rxN.drop(1)}\\s+prochain(?:e)?s\\s+(?<u>semaines|week-?ends)\\b")
    if (m.ok) {
        val n = nombre(m.g("n")) ?: 0
        if (m.g("u")!!.contient("week")) {
            var sam = today.plusDays(((6 - today.jds + 7) % 7).toLong())
            if (today.jds == 0) sam = today.plusDays(6)
            if (today.jds == 6) sam = sam.plusDays(7)   // « les prochains » dit un samedi : à partir du suivant
            return (0 until n).map { blocSemaine(sam.plusDays(7L * it), true) }
        }
        // Les semaines qui viennent, d'un seul bloc : de lundi prochain à la fin de la dernière
        val l = lundiDe(today).plusDays(7)
        return listOf(Bloc(l, l.plusDays(7L * n - 1)))
    }
    // « la semaine du 9 novembre », « le week-end du 24 octobre »
    m = st.morceau("\\b(?:pendant\\s+|durant\\s+|toute\\s+|tout\\s+)?(?:la|le)\\s+$rxU\\s+du\\s+(?<d>$RxNombre)(?:er)?\\s+(?<mo>$RxMois)(?:\\s+(?<y>\\d{4}))?")
    if (m.ok) {
        val mo = mois(m.g("mo")!!)
        val d = nombre(m.g("d")) ?: 0
        val we = m.g("u")!!.contient("week")
        val y = m.g("y")?.toInt() ?: today.year
        var jour = dateSure(y, mo, d) ?: return null
        if (!m.a("y") && jour.plusDays(6).isBefore(today)) jour = jour.plusYears(1)
        val l = lundiDe(jour)
        return listOf(if (we) blocSemaine(l.plusDays(5), true) else blocSemaine(l, false))
    }
    // « toute la semaine prochaine », « pendant la semaine prochaine », « toute cette semaine »
    m = st.morceau("\\b(?:toute|pendant|durant|sur)\\s+(?:la\\s+)?(?<q>semaine\\s+prochaine|semaine\\s+d'apr[èe]s|cette\\s+semaine)\\b")
    if (m.ok) {
        var l = lundiDe(today)
        if (!m.g("q")!!.contient("cette")) l = l.plusDays(7)
        var b = blocSemaine(l, false)
        if (b.debut.isBefore(today)) b = b.copy(debut = today)
        return listOf(b)
    }
    // « ce week-end », « le week-end prochain » (samedi et dimanche)
    m = st.morceau("\\b(?:ce|le)\\s+week-?end(?:\\s+(?<pro>prochain))?\\b(?!\\s+(?:du|de|d'))")
    if (m.ok) {
        // Dit un dimanche, « ce week-end » c'est aujourd'hui ; « le week-end prochain », celui d'après
        if (today.jds == 0 && !m.a("pro")) return listOf(Bloc(today, today))
        var sam = today.plusDays(((6 - today.jds + 7) % 7).toLong())
        if (today.jds == 0) sam = today.plusDays(6)
        else if (today.jds == 6 && m.a("pro")) sam = sam.plusDays(7)   // dit un samedi : celui d'après
        return listOf(blocSemaine(sam, true))
    }
    return null
}

// ---------------------------------------------------------------------------
// Lire une phrase
// ---------------------------------------------------------------------------
fun lirePhrase(texte: String, maintenant: LocalDateTime = LocalDateTime.now()): Phrase {
    val now = maintenant
    val today = now.toLocalDate()
    val st = Lecture(" " + texte.replace(Regex("[’‘`´]"), "'") + " ")
    // Ce qui est demandé à l'assistant en plus de l'événement ne fait pas partie de l'événement : « ... et que tu ouvres son
    // dossier au début », « ... . Et tu ouvres directement son dossier » (sauf un autre ordre de création)
    st.s = st.s.remplace("(?:\\s*[.;!]\\s*(?:et\\s+|puis\\s+|ensuite\\s+)?|\\s*,?\\s*\\b(?:et|puis|ensuite)\\s+)(?:(?:je veux|j'aimerais|je voudrais|il faut)\\s+)?(?:que\\s+|qu')?tu\\s+(?>(?:m'|me\\s+|lui\\s+|les?\\s+|l'|nous\\s+)?)(?!(?:$RxOrdre|me|m|lui|les?|nous)\\b)\\p{L}+\\b.*$", " ")
    st.s = st.s.remplace("(?:\\s*[.,;!]\\s*|\\s+)(?:et|puis|ensuite)\\s+(?:m'|me\\s+)?ouvr\\w*(?:[- ]moi)?\\s+(?:son|ses|sa|le|les|mon|mes|leur|leurs)\\s.*$", " ")
    // Une phrase de plus qui s'adresse à l'assistant : « ... . Si tu pouvais lier ses dossiers », « ... . Merci »
    st.s = st.s.remplace("\\s*[.!?;]\\s+(?:et\\s+|alors\\s+|mais\\s+)?(?:si tu (?:pouvais|peux|pourrais)|tu (?:peux|pourrais)|peux-tu|pourrais-tu|je (?:veux|voudrais|souhaite) (?:juste |aussi |seulement )?que|j'aimerais (?:juste |aussi |bien )?que|il faut (?:juste |aussi )?que|n'oublie pas de|pense à|merci|c'est tout)\\b.*$", " ")
    st.s = st.s.remplace("[\\s,]+c'est tout\\s*[.!]?\\s*$", " ")
    // « avec les dossiers de M. Richard » : c'est son dossier à relier, le rendez-vous est avec lui
    st.s = st.s.remplace("\\bavec\\s+(?:le|les|ses|son|leurs?)\\s+(?:dossiers?|fichiers?|documents?)\\s+(?:de\\s+|du\\s+|d')", "avec ")
    // « tous les jours sauf le week-end » : du lundi au vendredi ; « tous les jours sauf le mercredi » : les autres jours
    st.s = st.s.remplace("\\b(?:tous les jours|chaque jour)(?<h>\\s+(?:à|a|vers|de)\\s+\\d[^,]*?)?\\s+(?:sauf|except[ée]|hors|à part)\\s+(?:le |les )?(?:week-?ends?|samedis? et (?:les |le )?dimanches?)\\b", "du lundi au vendredi\${h}")
    st.s = st.s.remplace("\\b(?:tous les jours|chaque jour)(?<h>\\s+(?:à|a|vers|de)\\s+\\d[^,]*?)?\\s+(?:sauf|except[ée]|hors|à part)\\s+(?:le |les )?(?<ja>$RxJour)s?(?:\\s+et\\s+(?:le |les )?(?<jb>$RxJour)s?)?\\b") { x ->
        val sans = listOfNotNull(x.g("ja"), x.g("jb")).map { jourSemaine(it) }
        val noms = JoursNoms.keys.filter { JoursNoms.getValue(it) !in sans }
        "les " + noms.dropLast(1).joinToString(", ") + " et " + noms.last() + (x.g("h") ?: "")
    }
    // « pour dans une minute », « pour demain » : le « pour » du moment
    st.s = st.s.remplace("\\bpour\\s+(?=dans\\s+\\S+\\s+(?:minutes?|heures?|jours?|semaines?|mois|ans?)\\b)", "")
    // « à 17 h pile », « à 9 h précises » : l'heure suffit
    st.s = st.s.remplace("(\\d\\s*(?:h|heures?)(?:\\s*\\d{2})?|\\bmidi|\\bminuit)\\s+(?:pile|pr[ée]cises?|tapantes?)\\b", "$1")
    // L'année, où qu'elle soit dite, va juste après le mois : « octobre, 2026 », « le 27 octobre en 2027 »,
    // « le 3 mars de l'année prochaine »
    st.s = st.s.remplace("\\b($RxMois)\\s*[,.;]\\s*((?:19|20)\\d{2})\\b", "$1 $2")
    var anneeDite: Int? = null
    if (st.s.contient("\\b(?:de\\s+)?l'ann[ée]e\\s+prochaine\\b")) {
        anneeDite = now.year + 1
        st.s = st.s.remplace("\\s*,?\\s*\\b(?:de\\s+)?l'ann[ée]e\\s+prochaine\\b", " ")
    } else {
        val x = trouve(st.s, "(?<!$RxMoisNu\\s{0,9})(?<!\\d\\s{0,9})(?:,\\s*|\\b(?:en|pour)\\s+)(?<an>(?:19|20)\\d{2})\\b")
        if (x.ok) {
            val y = x.g("an")!!.toInt()
            if (st.s.contient("\\b$RxMois\\b(?!\\s+\\d{4})") && y >= now.year && y <= now.year + 10) {
                anneeDite = y
                st.s = st.s.replace(x.valeur, " ")
            }
        }
    }
    if (anneeDite != null) st.s = st.s.remplace("\\b($RxMois)\\b(?!\\s+\\d{4})") { x -> "${x.valeur} $anneeDite" }

    var date: LocalDate? = null
    var heure: Int? = null
    var exact: LocalDateTime? = null
    var rep: Repetition? = null
    var periode: String? = null
    var qualif: String? = null
    var heureChiffres = false
    var invalide = false
    var semaineProchaine = false
    var journee = false

    // 0. Détails dits dans la phrase : lien, importance, rappels voulus, âge, durée, lieu donné tel quel
    //    (retirés en premier : « 2 jours avant » ou « jusqu'à 18 h » ne doivent pas devenir la date ou l'heure)
    val det = Details()
    var finHeure: Int? = null
    var dureeMin: Int? = null
    var ageDit: AgeDit? = null
    var m = st.morceau("(?<![\\w@])(?:https?://|www\\.|discord\\.gg/)[^\\s]+")
    if (m.ok) det.lien = m.valeur.trimEnd('.', ',', ';')
    if (st.morceau("\\b(?:c'est\\s+|tr[èe]s\\s+)?(?:important|urgent|prioritaire|[àa] ne (?:pas|surtout pas) (?:oublier|rater|louper))\\b").ok) det.important = true
    val rappels = mutableListOf<String>()
    while (true) {
        m = st.morceau("(?:\\b(?:rappelle|pr[ée]viens|avertis)[- ](?:moi|nous)\\s+|\\b(?:et\\s+)?(?:un\\s+)?rappel\\s+)?\\b(?<n>$RxNombre)\\s+(?<u>minutes?|min|heures?|h|jours?|semaines?)\\s+avant\\b")
        if (!m.ok) break
        val n = nombre(m.g("n")) ?: 0
        val u = m.g("u")!!.lowercase()
        val mult = when { u.startsWith("m") -> 1; u.startsWith("h") -> 60; u.startsWith("j") -> 1440; else -> 10080 }
        rappels += "m${n * mult}"
    }
    if (st.morceau("\\b(?:(?:rappelle|pr[ée]viens)[- ]moi\\s+)?la veille\\b").ok) rappels += "veille"
    if (st.morceau("\\b(?:(?:rappelle|pr[ée]viens)[- ]moi\\s+)?le matin m[êe]me\\b").ok) rappels += "matin"
    if (st.morceau("\\b(?:(?:rappelle|pr[ée]viens)[- ]moi\\s+)?(?:pile )?[àa] l'heure\\b").ok) rappels += "m0"
    if (rappels.isNotEmpty()) det.rappels = rappels.distinct()
    m = st.morceau("\\b(?:il|elle)\\s+(?<v>a|aura|va avoir|f[êe]te ses)\\s+(?<n>\\d{1,3})\\s+ans\\b")
    if (m.ok) ageDit = AgeDit(m.g("n")!!.toInt(), !m.g("v")!!.equals("a", ignoreCase = true))
    else {
        m = st.morceau("\\bn[ée]e?\\s+en\\s+(?<y>(?:19|20)\\d{2})\\b")
        if (m.ok) det.naissance = m.g("y")!!.toInt()
    }
    m = st.morceau("\\b(?:lieu|adresse)\\s*:?\\s+(?<l>[^,;]+)")
    if (m.ok) det.lieu = m.g("l")!!.trim(' ', '.')
    // Durée : « pendant 2 h », « pendant 1 h 30 », « pendant 45 minutes », « pendant une demi-heure »
    m = st.morceau("\\bpendant\\s+(?:(?<n>$RxNombre)\\s*(?<u>heures?|h(?![a-zà-ÿ])|minutes?|min)\\b(?:\\s*(?<m>\\d{2})\\b)?|(?<dh>une demi[- ]heure))")
    if (m.ok) {
        dureeMin = when {
            m.a("dh") -> 30
            m.g("u")!!.contient("^h") -> 60 * (nombre(m.g("n")) ?: 0) + (m.g("m")?.toInt() ?: 0)
            else -> nombre(m.g("n"))
        }
    }
    // Plage : « de 14 h à 16 h 30 » (le début devient l'heure), ou seulement la fin : « jusqu'à 18 h »
    val rxH = "(?<hX>$RxNombre)\\s*(?:h(?![a-zà-ÿ])|heures?\\b|:)(?:\\s*(?<mX>\\d{2})\\b)?"
    val h1 = rxH.replace("hX", "ha").replace("mX", "ma")
    val h2 = rxH.replace("hX", "hb").replace("mX", "mb")
    m = st.morceau("\\bde\\s+$h1\\s+(?:à|a|jusqu'à)\\s+$h2")
    if (m.ok) {
        heure = minutesDe((nombre(m.g("ha")) ?: 0) % 24, m.g("ma")?.toInt() ?: 0)
        finHeure = minutesDe((nombre(m.g("hb")) ?: 0) % 24, m.g("mb")?.toInt() ?: 0)
        heureChiffres = true
    } else {
        m = st.morceau("\\bjusqu'?\\s*(?:à|a|au)\\s+(?:$h2|(?<mm>midi|minuit))")
        if (m.ok) {
            finHeure = if (m.a("mm")) minutesDe(if (m.g("mm")!!.contient("midi")) 12 else 0, 0)
                       else minutesDe((nombre(m.g("hb")) ?: 0) % 24, m.g("mb")?.toInt() ?: 0)
        }
    }

    // 1. Rendez-vous qui reviennent, comme dans les grands agendas : tous les jours / toutes les semaines / tous les mois /
    //    tous les ans, avec un intervalle (« toutes les deux semaines », « un lundi sur deux »), plusieurs jours de la
    //    semaine (« en semaine », « le lundi et le jeudi ») ou un rang dans le mois (« le premier lundi du mois »)
    val rxN = "(?:\\d{1,2}|deux|trois|quatre|cinq|six|sept|huit|neuf|dix|onze|douze|quinze|vingt)"
    var periodeRep: String? = null
    fun nJours(s: String) = tous(s.lowercase(), "\\b$RxJour", ci = false).map { JoursNoms.getValue(it.valeur) }.distinct()
    run {
        m = st.morceau("\\b(?:le|chaque|tous les)\\s+(?<r>premiers?|1er|1re|deuxi[èe]mes?|second|2e|troisi[èe]mes?|3e|quatri[èe]mes?|4e|derniers?)\\s+(?<wd>$RxJour)s?\\s+(?:du|de chaque|de tous les|de)\\s+mois\\b")
        if (m.ok) { rep = Repetition("mois", jour = jourSemaine(m.g("wd")!!), rang = rangDe(m.g("r")!!)); return@run }
        m = st.morceau("\\b(?:en semaine|du lundi au vendredi|(?:tous les|chaque)\\s+jours?\\s+de\\s+(?:la\\s+)?semaine|les jours de semaine)\\b")
        if (m.ok) { rep = Repetition("semaine", jour = 1, jours = listOf(1, 2, 3, 4, 5)); return@run }
        m = st.morceau("\\b(?:tous les week-?ends?|chaque week-?end|le week-?end)\\b(?!\\s+(?:prochain|du|de|d'|dernier|(?:$RxMois)))")
        if (m.ok) { rep = Repetition("semaine", jour = 6, jours = listOf(6, 0)); return@run }
        m = st.morceau("\\b(?:tous les|chaque|le|les)\\s+$RxJour(?:s)?(?:\\s*(?:,|et)\\s*(?:le\\s+|les\\s+)?$RxJour(?:s)?)+\\b")
        if (m.ok) {
            val js = nJours(m.valeur)
            if (js.size >= 2) rep = Repetition("semaine", jour = js[0], jours = js)
            else st.s = st.s + " " + m.valeur   // un seul jour finalement : on le rend à la phrase
            return@run
        }
        m = st.morceau("\\bdu\\s+(?<a>$RxJour)\\s+au\\s+(?<b>$RxJour)\\b")
        if (m.ok) {
            val a = jourSemaine(m.g("a")!!); val b = jourSemaine(m.g("b")!!)
            val js = mutableListOf<Int>(); var k = a
            for (n in 0 until 7) { js += k; if (k == b) break; k = (k + 1) % 7 }
            rep = Repetition("semaine", jour = js[0], jours = js); return@run
        }
        m = st.morceau("\\b(?:un|une)\\s+(?<wd>$RxJour)\\s+sur\\s+(?<n>$rxN)\\b")
        if (m.ok) { rep = Repetition("semaine", jour = jourSemaine(m.g("wd")!!), intervalle = nombre(m.g("n"))); return@run }
        m = st.morceau("\\btou(?:te)?s les\\s+(?<n>$rxN)\\s+semaines\\b")
        if (m.ok) { rep = Repetition("semaine", intervalle = nombre(m.g("n"))); return@run }
        m = st.morceau("\\btou(?:te)?s les\\s+(?<n>$rxN)\\s+jours\\b")
        if (m.ok) {
            val n = nombre(m.g("n"))
            // « tous les 15 jours » se dit pour « toutes les deux semaines »
            rep = if (n == 14 || n == 15) Repetition("semaine", intervalle = 2) else Repetition("jour", intervalle = n)
            return@run
        }
        m = st.morceau("\\btou(?:te)?s les\\s+(?<n>$rxN)\\s+mois(?:\\s+le\\s+(?<d>$RxNombre)(?:er)?\\b)?")
        if (m.ok) { rep = Repetition("mois", jour = m.g("d")?.let { nombre(it.remplace("er$", "")) }, intervalle = nombre(m.g("n"))); return@run }
        m = st.morceau("\\btou(?:te)?s les\\s+(?<n>$rxN)\\s+(?:ans|ann[ée]es)\\b")
        if (m.ok) { rep = Repetition("an", intervalle = nombre(m.g("n"))); return@run }
        m = st.morceau("\\b(?:tous les|chaque)\\s+(?<wd>$RxJour)s?\\b")
        if (m.ok) { rep = Repetition("semaine", jour = jourSemaine(m.g("wd")!!)); return@run }
        m = st.morceau("\\b(?:tous les|chaque)\\s+(?<p>matins?|soirs?|apr[èe]s[- ]midis?)\\b")
        if (m.ok) { rep = Repetition("jour"); periodeRep = m.g("p")!!.lowercase().remplace("s$", ""); return@run }
        if (st.morceau("\\b(?:tous les jours|chaque jour)\\b").ok) { rep = Repetition("jour"); return@run }
        m = st.morceau("\\ble\\s+(?<d>$RxNombre)(?:er)?\\s+(?:de\\s+)?(?:chaque|tous les)\\s+mois\\b")
        if (!m.ok) m = st.morceau("(?<!semaine\\s{1,9}de\\s{1,9})\\b(?:tous les mois|chaque mois)(?:\\s+le\\s+(?<d>$RxNombre)(?:er)?\\b)?")
        if (m.ok) { rep = Repetition("mois", jour = m.g("d")?.let { nombre(it) }); return@run }
        if (st.morceau("\\b(?:toutes les semaines|chaque semaine)\\b").ok) { rep = Repetition("semaine"); return@run }
        if (st.morceau("\\b(?:tous les ans|toutes les ann[ée]es|chaque ann[ée]e)\\b").ok) { rep = Repetition("an"); return@run }
    }

    // 1 bis. Jusqu'à quand ils reviennent : « tous les lundis d'octobre (2026) », « en octobre », « jusqu'au 31 octobre »,
    //        « jusqu'à fin novembre », « jusqu'à la fin du mois », « jusqu'en décembre »
    var limDebut: LocalDate? = null
    var limFin: LocalDate? = null
    st.morceau("\\b(?:à|a)\\s+partir\\s+(?=(?:du|de|d')\\b)")   // « à partir du 2 novembre » : la date suit
    // Les fois sautées : « sauf en novembre », « excepté le 24 décembre », « hors vacances du 20 au 31 décembre ».
    // La plus longue suite de mots qui fait une période après « sauf » ; ses jours seront enlevés de la série
    var saufPer: Periode? = null
    if (rep != null) {
        val ms = trouve(st.s, "[\\s,]*\\b(?:sauf|except[ée]|hors|à part)\\s+(?<r>.+)$")
        if (ms.ok) {
            // (l'heure qui suit n'en fait pas partie : « sauf en novembre à 19 h »)
            val toutR = ms.g("r")!!
            val r = toutR.remplace("\\s+(?:à|a|vers|de|dès)\\s+\\d.*$|\\s+\\d{1,2}\\s*h.*$", "")
            val apres = toutR.substring(r.length)
            val mots = r.split(Regex("\\s+")).filter { it.isNotEmpty() }
            var n = minOf(mots.size, 8)
            while (n >= 1 && saufPer == null) {
                val essai = mots.take(n).joinToString(" ").trim(' ', ',', '.')
                if (!essai.contient("^(?:à|a|de|vers)\\s+\\d")) {   // (« sauf à 19 h » n'est pas une période)
                    val per = lirePeriode(essai, now)
                    if (per?.debut != null && per.fin != null) {
                        saufPer = per
                        st.s = st.s.substring(0, ms.index) + " " + mots.drop(n).joinToString(" ") + " " + apres + " "
                    }
                }
                n--
            }
        }
    }
    val r1 = rep
    if (r1 != null) {
        fun anDe(mo: Int, y: String?) = if (!y.isNullOrEmpty()) y.toInt() else if (mo < today.monthValue) today.year + 1 else today.year
        fun finMois(mo: Int, y: Int) = LocalDate.of(y, mo, 1).dernierDuMois
        // Plusieurs mois : « d'octobre et (de) novembre 2026 », « d'octobre à décembre », « en janvier, février et mars »
        val rxUnMois = "(?:fin\\s+|d[ée]but\\s+)?(?:d'|de\\s+|en\\s+)?$RxMois(?:\\s+\\d{4})?"
        m = st.morceau("\\b(?:(?:du|pour le|pendant le|tout le)\\s+mois\\s+)?(?:d'|de\\s+|en\\s+|pour\\s+|pendant\\s+)?(?<!\\d\\s)(?<!\\d(?:er|re|e|ème)\\s)$RxMois(?:\\s+\\d{4})?(?:\\s*(?:,|et|à|a|au|jusqu'à|jusqu'au|jusqu'en)\\s*$rxUnMois)+\\b")
        if (m.ok) {
            // Les mois dans l'ordre, avec leur année (dite, sinon celle du mois suivant, sinon la prochaine fois)
            class MoisDit(val mo: Int, var y: Int, val plage: Boolean)
            val liste = tous(m.valeur.lowercase(), "(?<lien>,|\\bet\\b|\\bà\\b|\\ba\\b|\\bau\\b|jusqu'à|jusqu'au|jusqu'en)?\\s*(?:fin\\s+|d[ée]but\\s+)?(?:d'|de\\s+|en\\s+|du\\s+mois\\s+(?:d'|de\\s+))?(?<mo>$RxMois)(?:\\s+(?<y>\\d{4}))?", ci = false)
                .map { MoisDit(MoisNoms.getValue(it.g("mo")!!), it.g("y")?.toInt() ?: 0, (it.g("lien") ?: "").contient("^(à|a|au|jusqu)")) }
            for (k in liste.indices.reversed()) {
                if (liste[k].y != 0) continue
                liste[k].y = if (k < liste.size - 1) { val s = liste[k + 1]; if (liste[k].mo > s.mo) s.y - 1 else s.y }
                             else if (liste[k].mo < today.monthValue) today.year + 1 else today.year
            }
            // Les mois gardés : chacun, plus ceux entre deux mois reliés par « à » / « jusqu'à »
            val garde = mutableListOf<LocalDate>()
            for (x in liste) {
                val d = LocalDate.of(x.y, x.mo, 1)
                if (x.plage && garde.isNotEmpty()) {
                    var p = garde.last().plusMonths(1)
                    while (p.isBefore(d) && garde.size < 36) { garde += p; p = p.plusMonths(1) }
                }
                if (d !in garde) garde += d
            }
            garde.sort()
            val debut = garde.first()
            val fin = garde.last().plusMonths(1).minusDays(1)
            limDebut = debut; limFin = fin
            // Un mois sauté au milieu (« octobre et décembre ») : ses jours sont enlevés de la série
            val sauf = generateSequence(debut) { it.plusDays(1) }.takeWhile { !it.isAfter(fin) }
                .filter { it.premierDuMois !in garde }.map { it.toString() }.toList()
            if (sauf.isNotEmpty()) r1.sauf = sauf
        } else if (st.morceau("\\bjusqu'?\\s*(?:à|a|au)\\s+(?:le\\s+)?(?<d>$RxNombre)\\s+(?<mo>$RxMois)(?:\\s+(?<y>\\d{4}))?\\b").also { m = it }.ok) {
            val mo = mois(m.g("mo")!!); val y = anDe(mo, m.g("y"))
            val d = nombre(m.g("d")) ?: 0
            if (d >= 1 && d <= LocalDate.of(y, mo, 1).joursDuMois) limFin = LocalDate.of(y, mo, d)
        } else if (st.morceau("\\bjusqu'?\\s*(?:à|a|au)\\s+(?:la\\s+)?fin\\s+(?:du\\s+mois\\s+)?(?:(?:d'|de\\s+)?(?<mo>$RxMois)(?:\\s+(?<y>\\d{4}))?\\b)?").also { m = it }.ok) {
            limFin = if (m.a("mo")) { val mo = mois(m.g("mo")!!); finMois(mo, anDe(mo, m.g("y"))) } else finMois(today.monthValue, today.year)
        } else if (st.morceau("\\bjusqu'en\\s+(?<mo>$RxMois)(?:\\s+(?<y>\\d{4}))?\\b").also { m = it }.ok) {
            val mo = mois(m.g("mo")!!); limFin = finMois(mo, anDe(mo, m.g("y")))
        } else if (st.morceau("\\b(?:(?:du|pour le|pendant le|tout le)\\s+mois\\s+(?:d'|de\\s+)|d'|de\\s+|en\\s+|pour\\s+|pendant\\s+)?(?<!\\d\\s)(?<!\\d(?:er|re|e|ème)\\s)(?<mo>$RxMois)(?:\\s+(?<y>\\d{4}))?\\b").also { m = it }.ok) {
            val mo = mois(m.g("mo")!!); val y = anDe(mo, m.g("y"))
            limDebut = LocalDate.of(y, mo, 1); limFin = finMois(mo, y)
        }
        limFin?.let { r1.jusqua = it.toString() }
        saufPer?.let { per ->
            val debut = per.debut!!
            val fin = per.fin!!
            val jours = per.jours?.takeIf { it.isNotEmpty() }
                ?: generateSequence(debut) { it.plusDays(1) }.takeWhile { !it.isAfter(fin) && it.isBefore(debut.plusDays(370)) }.toList()
            r1.sauf = (r1.sauf.orEmpty() + jours.map { it.toString() }).distinct()
        }
    }

    // 2. « dans 2 heures », « dans 30 minutes », « dans une demi-heure » : le moment exact
    m = st.morceau("\\bdans\\s+(?:(?<n>$RxNombre)\\s+(?<u>heures?|h|minutes?|min)\\b|(?<dh>une demi[- ]heure)|(?<qh>un quart d'heure))")
    if (m.ok) {
        val e = when {
            m.a("dh") -> now.plusMinutes(30)
            m.a("qh") -> now.plusMinutes(15)
            m.g("u")!!.contient("^h") -> now.plusHours((nombre(m.g("n")) ?: 0).toLong())
            else -> now.plusMinutes((nombre(m.g("n")) ?: 0).toLong())
        }
        exact = e.truncatedTo(ChronoUnit.MINUTES)
    }

    // 2 bis. Plusieurs jours : « du 27 au 29 octobre », « du lundi 27 au mercredi 29 », « le 27, le 28 et le 29 octobre »,
    //        « les 3 et 10 novembre », « pendant 3 jours ». Des jours qui se suivent : un seul événement (début et dernier
    //        jour) ; des jours séparés : un événement par jour (Jours).
    if (rep == null) {
        fun anDe(mo: Int, d: Int, y: String?) =
            if (!y.isNullOrEmpty()) y.toInt()
            else if (mo < today.monthValue || (mo == today.monthValue && d < today.dayOfMonth)) today.year + 1 else today.year
        fun num(s: String?) = nombre(s?.remplace("(er|re)$", ""))
        var jours: List<LocalDate>? = null
        m = st.morceau("\\b(?:du|de)\\s+(?:$RxJour\\s+)?(?<da>$RxNombre)(?:er)?(?:\\s+(?<ma>$RxMois))?(?:\\s+(?<ya>\\d{4}))?\\s+(?:au|à|a|jusqu'au)\\s+(?:$RxJour\\s+)?(?<db>$RxNombre)(?:er)?\\s+(?<mb>$RxMois)(?:\\s+(?<yb>\\d{4}))?\\b")
        if (m.ok) {
            val m2 = mois(m.g("mb")!!); val d2 = num(m.g("db")) ?: 0
            val m1 = m.g("ma")?.let { mois(it) } ?: m2; val d1 = num(m.g("da")) ?: 0
            val y1 = anDe(m1, d1, m.g("ya") ?: m.g("yb"))
            // (comme sur PC, une date qui n'existe pas, même remplacée ensuite, rend la phrase invalide)
            val a = dateSure(y1, m1, d1)
            var b = dateSure(y1, m2, d2)
            if (a != null && b != null) {
                if (m.a("yb")) b = dateSure(m.g("yb")!!.toInt(), m2, d2)
                else if (b.isBefore(a)) b = b.plusYears(1)
            }
            val fin = b
            if (a == null || fin == null) invalide = true
            else if (ChronoUnit.DAYS.between(a, fin) <= 366) jours = generateSequence(a) { it.plusDays(1) }.takeWhile { !it.isAfter(fin) }.toList()
        } else {
            m = st.morceau("\\b(?:les?\\s+)?(?:$RxJour\\s+)?$RxNombre(?:er)?(?:\\s+$RxMois)?(?:\\s*(?:,|et)\\s*(?:(?:le|les)\\s+)?(?:$RxJour\\s+)?$RxNombre(?:er)?(?:\\s+$RxMois)?)+\\s+(?<mo>$RxMois)(?:\\s+(?<y>\\d{4}))?\\b")
            if (!m.ok) m = st.morceau("\\b(?:les?\\s+)?(?:$RxJour\\s+)?$RxNombre(?:er)?\\s+(?<mo>$RxMois)(?:\\s*(?:,|et)\\s*(?:(?:le|les)\\s+)?(?:$RxJour\\s+)?$RxNombre(?:er)?\\s+$RxMois)+(?:\\s+(?<y>\\d{4}))?\\b")
            if (m.ok) {
                // Chaque jour avec son mois ; un jour sans mois prend celui du jour suivant (« le 27, 28 et 29 octobre »)
                class JourDit(val d: Int, var mo: Int)
                val items = tous(m.valeur, "(?:$RxJour\\s+)?\\b(?<d>$RxNombre)(?:er)?\\b(?:\\s+(?<mo>$RxMois)\\b)?")
                    .mapNotNull { x -> num(x.g("d"))?.takeIf { it in 1..31 }?.let { JourDit(it, x.g("mo")?.let { mo -> mois(mo) } ?: 0) } }
                for (k in items.indices.reversed()) {
                    if (items[k].mo == 0) items[k].mo = if (k < items.size - 1) items[k + 1].mo else mois(m.g("mo")!!)
                }
                val dates = items.map { dateSure(anDe(it.mo, it.d, m.g("y")), it.mo, it.d) }
                if (dates.any { it == null }) invalide = true
                else jours = dates.filterNotNull().distinct().sorted()
            }
        }
        if (!jours.isNullOrEmpty()) {
            date = jours[0]
            val suivis = (1 until jours.size).all { ChronoUnit.DAYS.between(jours[it - 1], jours[it]) == 1L }
            if (jours.size > 1) { if (suivis) det.finJour = jours.last() else det.jours = jours }
        }
        m = st.morceau("\\b(?:sur|pendant|durant)\\s+(?<n>$RxNombre)\\s+jours\\b")
        if (m.ok) { val n = nombre(m.g("n")) ?: 0; if (n in 2..366) det.nbJours = n }
        else {
            m = st.morceau("\\b(?:sur|pendant|durant)\\s+(?<n>une|un|deux|trois|quatre|\\d)\\s+semaines?\\b")
            if (m.ok) det.nbJours = 7 * (nombre(m.g("n")!!.remplace("^une?$", "un")) ?: 0)
        }
        // Des semaines entières (du lundi au dimanche) : « la première semaine des 3 prochains mois », « la semaine du
        // 9 novembre », « la dernière semaine de décembre », « toute la semaine prochaine », « ce week-end »
        if (jours.isNullOrEmpty()) {
            val blocs = blocsSemaine(st, today).orEmpty()
            if (blocs.isNotEmpty()) {
                date = blocs[0].debut
                if (blocs.size > 1) det.blocs = blocs
                if (blocs[0].fin.isAfter(blocs[0].debut)) det.finJour = blocs[0].fin
            }
        }
    }

    // 3. Jours relatifs
    m = st.morceau("\\bdans\\s+(?<n>$RxNombre)\\s+(?<u>jours?|semaines?|mois|ans?)\\b")
    if (m.ok) {
        val n = (nombre(m.g("n")) ?: 0).toLong()
        val u = m.g("u")!!.lowercase()
        date = when { u.startsWith("j") -> today.plusDays(n); u.startsWith("s") -> today.plusDays(7 * n); u.startsWith("m") -> today.plusMonths(n); else -> today.plusYears(n) }
    } else if (st.morceau("\\bapr[èe]s[- ]demain\\b").ok) date = today.plusDays(2)
    else if (st.morceau("\\baujourd'?\\s?hui\\b").ok) date = today
    else if (st.morceau("\\bdemain\\b").ok) date = today.plusDays(1)

    // 4. Dates : « le 27/02 », « le 27 février 2027 », « vendredi 27 février », « le 27 »
    var wdx: String? = null
    if (date == null) {
        m = st.morceau("\\b(?:le\\s+)?(?<d>\\d{1,2})/(?<mo>\\d{1,2})(?:/(?<y>\\d{2,4}))?\\b")
        if (!m.ok) m = st.morceau("\\b(?:(?:le|du|ce)\\s+)?(?:(?<wdx>$RxJour)\\s+)?(?<d>$RxNombre)\\s+(?<mo>$RxMois)(?:\\s+(?<y>\\d{4}))?\\b")
        if (m.ok) {
            wdx = m.g("wdx")
            val d = nombre(m.g("d")) ?: 0
            val moTexte = m.g("mo")!!
            val mo = if (moTexte.all { it.isDigit() }) moTexte.toInt() else mois(moTexte)
            if (m.a("y")) {
                var y = m.g("y")!!.toInt(); if (y < 100) y += 2000
                date = dateSure(y, mo, d)
            } else {
                // Sans année : la prochaine fois que cette date arrive (un 29 février attend l'année qui en a un)
                for (k in 0..4) {
                    val c = dateSure(today.year + k, mo, d)
                    if (c != null && !c.isBefore(today)) { date = c; break }
                }
            }
            if (date == null) invalide = true
        } else {
            m = st.morceau("\\b(?:(?:le|ce)\\s+(?:(?<wdxa>$RxJour)\\s+)?|(?<wdxb>$RxJour)\\s+)(?<d>\\d{1,2}|1er|premier)\\b(?!\\s*(?:h(?![a-zà-ÿ])|heures?\\b|:|jours?\\b|semaines?\\b|mois\\b|ans?\\b|minutes?\\b|min\\b))")
            if (m.ok) {
                wdx = m.g("wdxa") ?: m.g("wdxb")
                // « le 27 » : ce mois-ci, ou le mois suivant si le 27 est passé
                val d = nombre(m.g("d")) ?: 0
                for (k in 0..12) {
                    val first = today.premierDuMois.plusMonths(k.toLong())
                    val c = dateSure(first.year, first.monthValue, d)
                    if (c != null && !c.isBefore(today)) { date = c; break }
                }
                if (date == null) invalide = true
            }
        }
    }
    // Jour de la semaine qui ne colle pas avec le numéro (« jeudi 13 » alors que le 13 est un mardi) : le jour de la semaine l'emporte
    val dateLue = date
    if (dateLue != null && wdx != null) {
        val wdv = jourSemaine(wdx)
        if (dateLue.jds != wdv) date = today.plusDays((((wdv - today.jds) + 7) % 7).toLong())
    }

    // 5. Jour de la semaine : « mardi », « mardi prochain », « ce samedi », « mercredi de la semaine prochaine »
    if (st.morceau("\\b(?:de\\s+)?la semaine prochaine\\b").ok) semaineProchaine = true
    var wd: Int? = null
    m = st.morceau("\\b(?:(?<ce>ce\\s+)|le\\s+)?(?<wd>$RxJour)(?:\\s+(?:prochain|qui vient))?\\b")
    if (m.ok) {
        val w = jourSemaine(m.g("wd")!!)
        wd = w
        if (date == null && rep == null) {
            if (semaineProchaine) {
                val lundi = today.plusDays((7 - ((today.jds + 6) % 7)).toLong())
                date = lundi.plusDays(((w + 6) % 7).toLong())
            } else {
                var ecart = ((w - today.jds) + 7) % 7
                if (ecart == 0 && !m.a("ce")) ecart = 7   // « mardi » dit un mardi : celui d'après
                date = today.plusDays(ecart.toLong())
            }
        }
    } else if (semaineProchaine && date == null) {
        date = today.plusDays((7 - ((today.jds + 6) % 7)).toLong())   // lundi de la semaine prochaine
    }

    if (st.morceau("\\b(?:toute la journ[ée]e|la journ[ée]e enti[èe]re|journ[ée]e enti[èe]re)\\b").ok) journee = true

    // 6. Heure : « à midi et demi », « à 15 h 30 », « 15h30 », « à 3 h de l'après-midi », « quinze heures trente »
    val rxQualif = "(?:\\s+(?<q>du matin|de l'apr[èe]s[- ]midi|de l'aprem|du soir|de la nuit))?"
    m = st.morceau("(?:\\b(?:à|a|vers|pour)\\s+)?(?<ce>\\bce\\s+)?(?<![-'\\w])(?<mm>midi|minuit)(?:\\s+(?<f>$RxFrac)|\\s*(?<m>\\d{2})\\b)?")
    if (m.ok) {
        heure = minutesDe(if (m.g("mm")!!.contient("^midi$")) 12 else 0, m.g("m")?.toInt() ?: 0)
        if (m.a("ce") && date == null) date = today
    } else {
        m = st.morceau("(?:\\b(?:à|a|vers|pour|de)\\s+)?\\b(?<h>$RxNombre)\\s*(?:h(?![a-zà-ÿ])|heures?\\b|:)(?:\\s*(?:(?<m>\\d{2})\\b|(?<mw>$RxMinMot)\\b))?(?:\\s+(?<f>$RxFrac))?$rxQualif")
        if (m.ok) {
            var h = nombre(m.g("h"))
            val min = m.g("m")?.toInt() ?: m.g("mw")?.let { nombre(it) } ?: 0
            if (h != null && h <= 24 && min <= 59) {
                if (h == 24) h = 0
                heure = minutesDe(h, min)
                heureChiffres = true
                m.g("q")?.let { qualif = it.lowercase() }
            }
        }
    }
    val hFrac = heure
    if (m.ok && m.a("f") && hFrac != null) {
        val f = m.g("f")!!.lowercase()
        var h = hFrac
        if (f.contient("demi")) h += 30
        if (f.contient("et quart")) h += 15
        if (f.contient("moins")) { h -= 15; if (h < 0) h += 1440 }
        heure = h
    }

    // 7. Moment de la journée : « ce soir », « demain matin », « cet après-midi » (heure par défaut s'il n'y en a pas)
    m = st.morceau("\\b(?<ce>ce\\s+|cet\\s+|cette\\s+)?(?<p>matin|apr[èe]s[- ]midi|aprem|soir)\\b")
    if (m.ok) {
        periode = m.g("p")!!.lowercase()
        if (m.a("ce") && date == null) date = today
    }
    if (periode == null && periodeRep != null) periode = periodeRep   // « tous les matins »
    val moment = if (!qualif.isNullOrEmpty()) qualif else periode
    val h0 = heure
    if (h0 != null) {
        val fh = finHeure
        // « 8 h » le soir ou l'après-midi veut dire 20 h ; sans précision, « à 3 h » pour un rendez-vous veut dire 15 h
        if (moment != null && moment.contient("soir|midi|aprem") && heures(h0) < 12) heure = h0 + 720
        // (sauf dans une plage qui finit après 7 h, « de 6 h à 14 h », et pour le travail : « je commence à 6 h »)
        else if (moment.isNullOrEmpty() && heureChiffres && heures(h0) in 1..6 &&
                 !(fh != null && heures(fh) > heures(h0) && heures(fh) >= 7) &&
                 !texte.contient("\\b(?:travail\\w*|bosse\\w*|boulot|taf|poste|[ée]quipe|embauche|je commence|je finis|je d[ée]bute|shift)\\b")) heure = h0 + 720
    } else if (!moment.isNullOrEmpty()) {
        heure = when { moment.contient("matin") -> 540; moment.contient("midi|aprem") -> 840; else -> 1200 }
    }

    // Liste dite d'un coup : « faire les courses : lait, œufs et pain », « avec la liste lait, œufs, pain »
    val li = trouve(st.s, "(?:\\s*,?\\s*(?:avec\\s+)?(?:la|ma|une)\\s+liste\\s*(?:de\\s+courses\\s*)?(?:suivante\\s*)?:?|\\s*:)\\s*(?<l>[^:]+?)\\s*$")
    if (li.ok && (li.valeur.contient("liste") || li.g("l")!!.contains(','))) {
        det.aPreparer = li.g("l")!!.split(motif("\\s*(?:,|;|\\bet\\b|\\bpuis\\b)\\s*").toRegex())
            .map { it.trim(' ', '.') }.filter { it.isNotEmpty() }.map { it.replaceFirstChar { c -> c.uppercaseChar() } }
        st.s = st.s.substring(0, li.index)
    }

    // Titre : ce qui reste de la phrase, sans les ordres (« crée un rendez-vous ») ni les petits mots autour
    var titre = titreNettoye(st.s)
    // Où : « réunion à Paris », « concert au Zénith » (un nom propre, avec sa majuscule)
    if (det.lieu.isEmpty()) {
        val o = trouve(titre, "\\s+(?:à la|à l'|à|au|aux)\\s+(?<lieu>[A-ZÀ-Ý][\\w'-]*(?:\\s+(?:(?:de|du|des|la|le)\\s+|d')?[A-ZÀ-Ý][\\w'-]*)*)", ci = false)
        if (o.ok) {
            det.lieu = o.g("lieu")!!
            titre = titreNettoye(titre.substring(0, o.index) + " " + titre.substring(o.index + o.valeur.length))
        }
    }
    // Avec qui : « déjeuner avec Paul et Léa »
    val av = trouve(titre, "^(?<avant>.*?\\S)?\\s*\\bavec\\s+(?<qui>.+)$")
    if (av.ok) {
        det.avec = av.g("qui")!!.split(motif("\\s*(?:,|\\bet\\b)\\s*").toRegex()).map { it.trim(' ', '.') }.filter { it.isNotEmpty() }
        titre = titreNettoye(av.g("avant") ?: "")
        // « rendez-vous avec Madame Dupont » : sans autre titre, le rendez-vous porte le nom des personnes
        if (titre.isEmpty() && det.avec.isNotEmpty()) titre = "rendez-vous avec " + det.avec.joinToString(" et ")
    }

    // Sorte de rendez-vous, devinée d'après les mots. Un anniversaire revient chaque année et n'a pas besoin d'heure.
    val sorte = sorteAuto(titre)
    if (sorte == "anniv") {
        titre = titreAnniversaire(titre)
        if (rep == null) rep = Repetition("an")
        // Sans heure (ou « à minuit », dit pour « le jour même ») : toute la journée
        if ((heure == null || heure == 0) && exact == null) journee = true
    }
    // Un jour sans heure (« samedi, faire les courses ») : toute la journée, pas besoin d'heure
    if (heure == null && exact == null && (date != null || rep != null)) journee = true
    // Une chose à faire sans jour ni heure (« faire les courses ») : aujourd'hui, toute la journée
    if (heure == null && exact == null && date == null && rep == null && sorte == "rappel") { date = today; journee = true }
    if (journee) { heure = 0; exact = null }
    val depuis = if (journee) today.atStartOfDay() else now

    val res = Resultat(titre, date, if (journee) null else heure, sorte, journee)
    fun fini() = completer(res, det, finHeure, dureeMin, ageDit)
    if (invalide) { res.manque = Manque.INVALIDE; return fini() }
    val r = rep
    if (r != null) {
        val ex = exact
        if (heure == null && ex != null) heure = ex.hour * 60 + ex.minute
        if (r.type == "an" && date == null) { res.manque = Manque.JOUR; return fini() }
        val h = heure ?: run { res.manque = Manque.HEURE; return fini() }
        var ref = date ?: ex?.toLocalDate() ?: today
        when (r.type) {
            "semaine" -> if (r.jour == null) r.jour = wd ?: ref.jds
            "mois" -> if ((r.rang ?: 0) == 0 && (r.jour ?: 0) == 0) r.jour = ref.dayOfMonth
            "an" -> { r.jour = ref.dayOfMonth; r.mois = ref.monthValue }
        }
        // Chaque année : dès cette année (un 29 février se fête le 28 les autres années)
        if (r.type == "an" && r.mois != null && (r.jour ?: 0) != 0 && r.jusqua == null) {
            val mo = r.mois!!
            ref = LocalDate.of(today.year, mo, minOf(r.jour!!, LocalDate.of(today.year, mo, 1).joursDuMois))
            date = null   // (le premier : la prochaine fois à partir d'aujourd'hui, pas la date lue)
        }
        if (r.type == "mois" && (r.rang ?: 0) == 0 && (r.jour ?: 0).let { it < 1 || it > 31 }) { res.manque = Manque.INVALIDE; return fini() }
        val d = date
        var depart = if (d != null && d.isAfter(today)) d.a(h) else depuis
        val ld = limDebut
        if (ld != null && ld.atStartOfDay().isAfter(depart)) { depart = ld.a(h); if (date == null) ref = ld }
        // Le premier rendez-vous est la prochaine date possible ; l'intervalle (« toutes les deux semaines ») se compte depuis lui
        val sansIntervalle = r.copy(intervalle = null)
        var q = prochaineFois(sansIntervalle, ref.a(h), depart)
        if (q == null || q.isBefore(depart)) q = prochaineFois(sansIntervalle, depart.toLocalDate().a(h), depart)
        res.quand = q
        res.repetition = r
        if (q == null) res.manque = Manque.PASSE   // la fin demandée est déjà passée
        return fini()
    }
    val ex = exact
    if (ex != null) { res.quand = ex; return fini() }
    val d = date
    val h = heure
    if (d != null && h != null) res.quand = d.a(h)
    else if (d != null) { res.manque = Manque.HEURE; return fini() }
    else if (h != null && !journee) {
        // Juste une heure : aujourd'hui si elle n'est pas passée, sinon demain
        var q = today.a(h)
        if (!q.isAfter(now)) q = q.plusDays(1)
        res.quand = q
    } else { res.manque = if (journee) Manque.JOUR else Manque.QUAND; return fini() }
    val q = res.quand!!
    if (q.isBefore(depuis) || (!journee && q == now)) res.manque = Manque.PASSE
    return fini()
}

private class Resultat(val titre: String, val jour: LocalDate?, val heure: Int?, val sorte: String, val journee: Boolean) {
    var quand: LocalDateTime? = null
    var repetition: Repetition? = null
    var manque = Manque.RIEN
}

/** Ajoute au résultat les détails compris (lieu, avec qui, lien, importance, rappels, âge) et l'heure de fin. */
private fun completer(res: Resultat, det: Details, finHeure: Int?, dureeMin: Int?, ageDit: AgeDit?): Phrase {
    var fin: LocalDateTime? = null
    var naissance = det.naissance
    val q = res.quand
    // Sur plusieurs jours : la fin est le dernier jour (à l'heure de fin si elle est dite)
    val finJour = det.finJour ?: if (det.nbJours != null && q != null) q.toLocalDate().plusDays(det.nbJours!! - 1L) else null
    if (q != null && finJour != null && finJour.isAfter(q.toLocalDate())) {
        fin = if (res.journee) finJour.atStartOfDay() else if (finHeure != null) finJour.a(finHeure) else finJour.atTime(23, 59)
    } else if (q != null && !res.journee) {
        if (dureeMin != null && dureeMin != 0) fin = q.plusMinutes(dureeMin.toLong())
        else if (finHeure != null) {
            var f = q.toLocalDate().a(finHeure)
            // « de 2 h à 4 h » : le début est passé à 14 h, la fin suit (16 h) ; « de 22 h à 1 h » finit le lendemain
            while (!f.isAfter(q) && ChronoUnit.MINUTES.between(q, f) > -24 * 60) f = f.plusHours(12)
            fin = f
        }
    }
    if (ageDit != null && q != null) naissance = q.year - ageDit.n - (if (ageDit.futur) 0 else 1)
    return Phrase(
        titre = res.titre,
        quand = res.quand,
        repetition = res.repetition,
        manque = res.manque,
        jour = res.jour,
        heure = res.heure?.let { LocalTime.of(heures(it), it % 60) },
        sorte = res.sorte,
        journee = res.journee,
        fin = fin,
        lieu = det.lieu,
        avec = det.avec,
        lien = det.lien,
        important = det.important,
        rappels = det.rappels,
        naissance = naissance,
        aPreparer = det.aPreparer,
        jours = det.jours,
        blocs = det.blocs,
    )
}

/** Ce qui reste de la phrase, sans les ordres (« crée un rendez-vous ») ni les petits mots autour. */
internal fun titreNettoye(texte: String): String {
    var t = texte.remplace("\\s+", " ")
    t = t.remplace("\\b(?:dans|sur|à|a) (?:mon|l'|le|ton|ma) ?(?:agenda|calendrier)\\b", " ")
    var avant: String? = null
    while (avant != t) {
        avant = t
        t = t.remplace("\\s+([,.;:!?])", "$1").remplace("([,;:])(?:\\s*[,;:])+", "$1")
        t = t.trim(' ', ',', '.', ';', ':', '!', '?', '-')
        t = t.remplace("^$RxPolitesse\\b[\\s,]*", "")
        t = t.remplace("[\\s,]*\\b$RxPolitesse$", "")
        t = t.remplace("^$RxDemande\\b\\s*", "")
        // « que tu me crées... », « et que tu mettes... » (on parle comme à quelqu'un)
        t = t.remplace("^(?:et\\s+)?(?:que\\s+|qu')?(?:tu\\s+)?(?:me\\s+|m'|nous\\s+)(?=$RxOrdre\\b)", "")
        t = t.remplace("^(?:et\\s+)?que\\s+tu\\s+(?=$RxOrdre\\b)", "")
        t = t.remplace("^$RxOrdre\\b(?:[- ]?(?:moi|nous|me))?\\s*(?:(?:de|que)\\s+|d'|qu')?", "")
        t = t.remplace("^(?:j'ai|il y a|y a|pr[ée]vois)\\b\\s*(?:(?:de|que)\\s+|d'|qu')?", "")
        t = t.remplace("^(?:un |une |le |la |mon |ma |l')?(?:nouvel? |nouvelle )?$RxGenre\\b\\s*(?:(?:de|du|des|pour)\\s+|d'|:)?", "")
        t = t.remplace("^(?:et|puis|à|a|au|pour|vers|de|du)\\s+", "")
        // « une réunion », « un café avec Paul » : l'article ne fait pas partie du nom
        t = t.remplace("^(?:un|une)\\s+(?=\\S)", "")
        t = t.remplace("\\s+(?:à|a|au|le|la|de|du|pour|et|vers|ce|cet|cette)$", "")
        t = t.remplace("\\s+(?:à|a|au|le|la|de|du|pour|et|vers)\\s*([,;])", "$1")
    }
    return t
}

/** La sorte d'un rendez-vous d'après son titre (code : anniv, sante... ; rdv si aucun mot ne parle). */
fun sorteAuto(titre: String): String =
    MotsDesSortes.firstOrNull { (_, mots) -> titre.contient("(?<![\\w])(?:$mots)(?![\\w])") }?.first ?: "rdv"

/** « anniversaire Jordan », « anniv de Léa », « mon anniversaire » : « Anniversaire de Jordan »... */
internal fun titreAnniversaire(titre: String): String {
    val m = trouve(titre, "^(?:(?:mon|l'|un|une|le|ton|son)\\s*)?anniv(?:ersaire)?\\b\\s*(?:(?:de|du|des|pour|à|a)\\s+|d')?(?<nom>.*)$")
    if (!m.ok) return titre
    var nom = m.g("nom")!!.trim()
    if (nom.isEmpty()) return "Anniversaire"
    nom = nom.replaceFirstChar { it.uppercaseChar() }
    return if (nom.contient("^[AEIOUYÉÈÊaeiouyéèê]")) "Anniversaire d'$nom" else "Anniversaire de $nom"
}

// ---------------------------------------------------------------------------
// Une période dite avec des mots : « en octobre 2026 », « à partir de novembre », « jusqu'au 15 mars », « le 19 octobre »,
// « du 3 au 10 novembre », « cette semaine », « le mois prochain »... (jours compris ; l'un ou l'autre peut manquer)
// ---------------------------------------------------------------------------
data class Periode(val debut: LocalDate?, val fin: LocalDate?, val jours: List<LocalDate>? = null)

fun lirePeriode(texte: String, maintenant: LocalDateTime = LocalDateTime.now()): Periode? {
    val today = maintenant.toLocalDate()
    var t = texte.replace(Regex("[’‘`´]"), "'").lowercase().trim(' ', '.', ',', '!', '?')
    t = t.remplace("^(?:pour|pendant|sur|durant)\\s+", "").remplace("^(?:(?:de|des)\\s+|d')(?=la\\s+semaine|ce\\s+mois|cette|le\\s+mois|(?:$RxMois)\\b)", "")
    if (t.isEmpty()) return null
    // Un jour seul, ou un mois entier (le début ou la fin du mois selon le côté)
    fun jour(texteJour: String, fin: Boolean): LocalDate? {
        val s = texteJour.trim(' ', '.', ',')
        var x = trouve(s, "^(?:(?:le|la)\\s+)?(?:(?<fin>fin|d[ée]but)\\s+(?:de\\s+|d')?)?(?:(?:du\\s+)?mois\\s+(?:de\\s+|d'))?(?:de\\s+|d'|en\\s+)?(?<mo>$RxMois)(?:\\s+(?<y>\\d{4}))?$")
        if (x.ok) {
            val mo = mois(x.g("mo")!!)
            val y = x.g("y")?.toInt() ?: if (mo < today.monthValue) today.year + 1 else today.year
            val premier = LocalDate.of(y, mo, 1)
            val dernier = premier.dernierDuMois
            val f = x.g("fin")
            if (f.equals("fin", ignoreCase = true)) return dernier
            if (!f.isNullOrEmpty()) return premier
            return if (fin) dernier else premier
        }
        x = trouve(s, "^(?:le\\s+)?mois\\s+prochain$")
        if (x.ok) { val p = today.premierDuMois.plusMonths(1); return if (fin) p.dernierDuMois else p }
        val p = lirePhrase("x $s", maintenant)
        return p.jour ?: p.quand?.toLocalDate()
    }
    // (« du 27 au 29 », « le 27 au 29 octobre », « le 27 octobre au 29 octobre », « entre le 27 et le 29 octobre »)
    var x = trouve(t, "^(?:du|de|le)\\s+(?<a>.+?)\\s+(?:au|à|a|jusqu'au|jusqu'à)\\s+(?<b>.+)$")
    if (!x.ok) x = trouve(t, "^entre\\s+(?:le\\s+)?(?<a>.+?)\\s+et\\s+(?:le\\s+)?(?<b>.+)$")
    if (x.ok) {
        val sa = x.g("a")!!
        val sb = x.g("b")!!
        var a = jour(sa, false)
        val b = jour(sb, true)
        // « du 3 au 10 novembre » : le mois (et l'année) de la fin vaut aussi pour le début
        if (a == null && sa.contient("^\\d{1,2}(?:er)?$")) {
            val mb = trouve(sb, "(?<m>$RxMois(?:\\s+\\d{4})?)$")
            if (mb.ok) a = jour("$sa ${mb.g("m")}", false)
        }
        if (a != null && b != null) return Periode(a, b)
    }
    x = trouve(t, "^(?:à|a)\\s+partir\\s+(?:du\\s+|de\\s+|d')?(?<a>.+)$")
    if (!x.ok) x = trouve(t, "^d[èe]s\\s+(?:le\\s+)?(?<a>.+)$")
    if (x.ok) jour(x.g("a")!!, false)?.let { return Periode(it, null) }
    x = trouve(t, "^apr[èe]s\\s+(?:le\\s+)?(?<a>.+)$")
    if (x.ok) jour(x.g("a")!!, true)?.let { return Periode(it.plusDays(1), null) }
    x = trouve(t, "^jusqu'?\\s*(?:au|à|a|en)\\s+(?<b>.+)$")
    if (x.ok) jour(x.g("b")!!, true)?.let { return Periode(null, it) }
    x = trouve(t, "^avant\\s+(?:le\\s+)?(?<b>.+)$")
    if (x.ok) jour(x.g("b")!!, false)?.let { return Periode(null, it.minusDays(1)) }
    if (t.contient("^(?:cette|la)\\s+semaine(?:-ci)?$")) return Periode(today, lundiDe(today).plusDays(6))
    if (t.contient("^la\\s+semaine\\s+prochaine$")) { val l = lundiDe(today).plusDays(7); return Periode(l, l.plusDays(6)) }
    if (t.contient("^ce\\s+mois(?:-ci)?$")) return Periode(today, today.dernierDuMois)
    // Plusieurs jours dits un par un (« le 27, le 28 et le 29 octobre ») : ces jours-là seulement, du premier au dernier
    if (t.contient("\\d.*(?:,|\\bet\\b).*\\d")) {
        val pl = lirePhrase("x $t", maintenant)
        val js = pl.jours.distinct().sorted()
        if (js.size > 1) return Periode(js.first(), js.last(), js)
        // (des jours qui se suivent sont lus comme « du 27 au 29 »)
        val q = pl.quand
        val f = pl.fin
        if (q != null && f != null && f.toLocalDate().isAfter(q.toLocalDate())) return Periode(q.toLocalDate(), f.toLocalDate())
    }
    // Un mois entier, ou un seul jour
    val a = jour(t, false)
    val b = jour(t, true)
    if (a != null && b != null) return Periode(a, b)
    return null
}

/** Sur plusieurs jours (une soirée de 21 h à 2 h finit le lendemain, mais ce n'est pas un événement sur plusieurs jours). */
fun surPlusieursJours(quand: LocalDateTime, fin: LocalDateTime?, journee: Boolean, repete: Boolean): Boolean =
    fin != null && !repete && fin.toLocalDate().isAfter(quand.toLocalDate()) &&
        (journee || ChronoUnit.HOURS.between(quand, fin) >= 24)
