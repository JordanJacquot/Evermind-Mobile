package io.github.jordanjacquot.evermind.assistant

import io.github.jordanjacquot.evermind.comprendre.RxJour
import io.github.jordanjacquot.evermind.comprendre.RxMois
import io.github.jordanjacquot.evermind.comprendre.contient
import io.github.jordanjacquot.evermind.comprendre.jourSemaine
import io.github.jordanjacquot.evermind.comprendre.lundiDe
import io.github.jordanjacquot.evermind.comprendre.tous
import io.github.jordanjacquot.evermind.donnees.laRepetition
import io.github.jordanjacquot.evermind.ui.enTexte
import io.github.jordanjacquot.evermind.ui.majuscule
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

// Horaires de travail en postes, comme sur PC (Get-HorairesTravail) : « je suis du matin lundi et mardi », « boulot
// lundi matin, mardi journée et mercredi soir », « je travaille de nuit toute la semaine prochaine ». Chaque poste a
// ses heures ; des heures dites (« de 6 h à 14 h ») passent par la lecture habituelle.

/** Un poste à créer : la phrase lue par Evermind (« Travail le 13 octobre 2026 de 6 h à 14 h »), son titre et ses heures. */
data class Poste(val phrase: String, val titre: String, val jour: String, val creneau: String, val heures: String)

val HorairesDefaut = linkedMapOf("matin" to "6 h à 14 h", "journee" to "8 h à 17 h", "apresmidi" to "14 h à 22 h", "soir" to "14 h à 22 h", "nuit" to "22 h à 6 h")
val CreneauxNoms = linkedMapOf("matin" to "matin", "journee" to "journée", "apresmidi" to "après-midi", "soir" to "soir", "nuit" to "nuit")
/** « le poste du matin », « le poste de nuit »... */
val PostesNoms = linkedMapOf("matin" to "du matin", "journee" to "de journée", "apresmidi" to "d'après-midi", "soir" to "du soir", "nuit" to "de nuit")

/** « 6 h », « 7 h 30 » : comme les lit Evermind. */
private fun heureEcrite(h: LocalTime) = if (h.minute == 0) "${h.hour} h" else "${h.hour} h %02d".format(h.minute)

/** « 7 h 30 à 15 h » */
fun ecrireHeures(debut: LocalTime, fin: LocalTime) = "${heureEcrite(debut)} à ${heureEcrite(fin)}"

/** « 6 h à 14 h » (ou « 6h-14h », « 6:30 à 14 h ») : le début et la fin ; null si ce n'est pas ça. */
fun lireHeures(texte: String): Pair<LocalTime, LocalTime>? {
    val h = "(\\d{1,2})\\s*(?:h|:|heures?)\\s*(\\d{2})?"
    val m = Regex("^\\s*(?:de\\s+)?$h\\s*(?:à|a|-|–|jusqu'à|jusqu'a)\\s*$h\\s*$", RegexOption.IGNORE_CASE).find(texte) ?: return null
    fun heure(a: String, b: String) = a.toInt().takeIf { it in 0..23 }?.let { LocalTime.of(it, b.ifEmpty { "0" }.toInt().coerceIn(0, 59)) }
    val debut = heure(m.groupValues[1], m.groupValues[2]) ?: return null
    val fin = heure(m.groupValues[3], m.groupValues[4]) ?: return null
    return if (debut == fin) null else debut to fin
}

/**
 * « Mon poste du matin, c'est de 7 h à 15 h », « mes horaires de nuit sont 21 h - 5 h » : le poste et ses nouvelles
 * heures (une règle pour toujours, pas un jour précis) ; null sinon.
 */
fun reglageHoraire(texte: String): Pair<String, String>? {
    val t = sansAccents(texte.replace('’', '\''))
    val m = Regex("\\b(?:postes?|horaires?|travail|boulot|equipes?|shifts?|je (?:suis|travaille|bosse|commence))\\s+(?:du|de|d'|en|le|la|l')?\\s*(?<c>matin(?:ee)?|journee|apres-midi|aprem|soir(?:ee)?|nuit)s?\\b(?<r>.*)$").find(t) ?: return null
    if (Regex("\\b(?:$RxJour|demain|aujourd'hui|semaine|le \\d|tous les|chaque)").containsMatchIn(t)) return null
    val r = m.groups["r"]!!.value.replace(Regex("^[\\s,:]*(?:c'est|ce sera|sont|est|ca sera|maintenant|desormais|commence|va|de)?\\s*(?:maintenant|desormais|plutot)?\\s*"), "")
        .replace(Regex("[\\s.!]+$"), "").replace(Regex("\\s+(?:maintenant|desormais)$"), "")
    val (a, b) = lireHeures(r) ?: return null
    return creneauCle(m.groups["c"]!!.value) to ecrireHeures(a, b)
}

private fun creneauCle(mot: String) = mot.lowercase().let {
    when {
        it.startsWith("mat") -> "matin"
        it.startsWith("jour") -> "journee"
        it.startsWith("ap") -> "apresmidi"
        it.startsWith("soir") -> "soir"
        else -> "nuit"
    }
}

private class Morceau(val pos: Int, val fin: Int, val sorte: Char, val jours: List<String>, val creneau: String?)

fun horairesTravail(texte: String, maintenant: LocalDateTime, horaires: Map<String, String> = HorairesDefaut): List<Poste>? {
    val today = maintenant.toLocalDate()
    val t = " " + texte.replace('’', '\'').lowercase() + " "
    if (!t.contient("\\b(?:travail\\w*|bosse\\w*|boulot|taf|poste|[ée]quipe|service|shift|horaires?|planning)\\b|\\bje suis (?:du|de|d'|en) ?(?:matin|journ|apr|aprem|soir|nuit)")) return null
    if (t.contient("\\b\\d{1,2}\\s*(?:h\\b|heures?\\b|h\\d{2}\\b|:\\d{2})")) return null
    val rxC = "(?:(?:du|de|en|le|la|l'|au|d')\\s*)?(?<c>matin(?:[ée]e)?s?|journ[ée]es?|apr[èe]s[- ]midis?|aprems?|soir(?:[ée]e)?s?|nuits?)\\b"
    val items = mutableListOf<Morceau>()
    fun ajoute(index: Int, longueur: Int, sorte: Char, jours: List<String>, creneau: String? = null) {
        if (items.any { index < it.fin && index + longueur > it.pos }) return
        items += Morceau(index, index + longueur, sorte, jours, creneau)
    }
    val fJour = DateTimeFormatter.ofPattern("d MMMM yyyy", Fr)
    // Les jours de travail (lundi à vendredi) d'une semaine, à partir d'aujourd'hui
    fun semaine(lundi: java.time.LocalDate) = (0..4).map { lundi.plusDays(it.toLong()) }.filter { !it.isBefore(today) }.map { "le " + it.format(fJour) }
    val lundi = lundiDe(today)
    for (m in tous(t, "\\b(?:toute\\s+)?(?:la\\s+)?semaine\\s+prochaine\\b", ci = false)) ajoute(m.index, m.valeur.length, 'D', semaine(lundi.plusDays(7)))
    for (m in tous(t, "\\b(?:toute\\s+)?(?:cette|la)\\s+semaine\\b", ci = false)) {
        val j = semaine(lundi).ifEmpty { semaine(lundi.plusDays(7)) }
        ajoute(m.index, m.valeur.length, 'D', j)
    }
    for (m in tous(t, "\\bdu\\s+(?<a>$RxJour)\\s+au\\s+(?<b>$RxJour)\\b", ci = false)) {
        val a = jourSemaine(m.g("a")!!)
        val b = jourSemaine(m.g("b")!!)
        var d = today
        while (d.dayOfWeek.value % 7 != a) d = d.plusDays(1)
        val j = mutableListOf<String>()
        for (k in 0 until 7) { j += "le " + d.format(fJour); if (d.dayOfWeek.value % 7 == b) break; d = d.plusDays(1) }
        ajoute(m.index, m.valeur.length, 'D', j)
    }
    // Les jours un par un : « tous les lundis », « lundi », « demain », « le 14 octobre »
    for (m in tous(t, "\\b(?:tous les|chaque)\\s+$RxJour\\w*", ci = false)) ajoute(m.index, m.valeur.length, 'D', listOf(m.valeur))
    for (m in tous(t, "\\b(?:le\\s+)?\\d{1,2}(?:er)?\\s+(?:$RxMois)(?:\\s+\\d{4})?\\b|\\ble\\s+\\d{1,2}(?:er)?\\b", ci = false)) ajoute(m.index, m.valeur.length, 'D', listOf(m.valeur.trim()))
    for (m in tous(t, "\\b(?:apr[èe]s-demain|demain|aujourd'hui)\\b", ci = false)) ajoute(m.index, m.valeur.length, 'D', listOf(m.valeur))
    for (m in tous(t, "\\b$RxJour(?:\\s+prochain)?\\b", ci = false)) ajoute(m.index, m.valeur.length, 'D', listOf(m.valeur))
    // Les postes
    for (m in tous(t, rxC, ci = false)) ajoute(m.index, m.valeur.length, 'C', emptyList(), creneauCle(m.g("c")!!))
    items.sortBy { it.pos }
    if (items.none { it.sorte == 'C' } || items.none { it.sorte == 'D' }) return null
    // Qui va avec quoi : « du matin lundi et mardi » (le poste d'abord, pour les jours qui suivent) ;
    // « lundi matin, mardi soir » (le jour d'abord, le poste pour les jours d'avant)
    val res = mutableListOf<Pair<String, String>>()
    val attente = mutableListOf<String>()
    var poste: String? = null
    val posteDabord = items[0].sorte == 'C'
    for (it in items) {
        if (it.sorte == 'C') {
            if (!posteDabord) { attente.forEach { j -> res += j to it.creneau!! }; attente.clear() }
            poste = it.creneau
        } else {
            for (j in it.jours) if (posteDabord) poste?.let { p -> res += j to p } else attente += j
        }
    }
    poste?.let { p -> attente.forEach { j -> res += j to p } }
    if (res.isEmpty()) return null
    return res.map { (jour, c) ->
        val h = horaires[c] ?: HorairesDefaut.getValue(c)
        // (la phrase sans le nom du poste, qui serait lu comme un moment de la journée ; le titre le reprend)
        Poste("Travail $jour de $h", "Travail (${CreneauxNoms[c]})", jour, c, h)
    }
}

/** « C'est noté : 3 postes. Lundi 12 matin (6 h – 14 h)... » (Get-TexteHoraires) : l'écrit, et le dit plus court. */
fun texteHoraires(faits: List<Pair<Poste, io.github.jordanjacquot.evermind.donnees.RendezVous>>, today: java.time.LocalDate): Pair<String, String> {
    if (faits.isEmpty()) return "Ces postes sont déjà dans ton agenda." to "Ces postes sont déjà dans ton agenda."
    fun quand(x: Pair<Poste, io.github.jordanjacquot.evermind.donnees.RendezVous>) =
        x.second.laRepetition?.enTexte(today)
            ?: x.second.quand.format(JourNum)
    val ecrits = faits.map { "${quand(it)} ${CreneauxNoms[it.first.creneau]} (${it.first.heures.replace(" à ", " – ")})" }
    val dits = faits.map { "${quand(it)} ${CreneauxNoms[it.first.creneau]}" }
    fun liste(l: List<String>) = if (l.size > 1) l.dropLast(1).joinToString(", ") + " et " + l.last() else l[0]
    val n = if (faits.size > 1) "${faits.size} postes" else "un poste"
    return "C'est noté : $n. ${majuscule(liste(ecrits))}." to "C'est noté : $n, ${liste(dits)}."
}
