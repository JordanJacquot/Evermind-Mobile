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
import java.time.format.DateTimeFormatter

// Horaires de travail en postes, comme sur PC (Get-HorairesTravail) : « je suis du matin lundi et mardi », « boulot
// lundi matin, mardi journée et mercredi soir », « je travaille de nuit toute la semaine prochaine ». Chaque poste a
// ses heures ; des heures dites (« de 6 h à 14 h ») passent par la lecture habituelle.

/** Un poste à créer : la phrase lue par Evermind (« Travail le 13 octobre 2026 de 6 h à 14 h »), son titre et ses heures. */
data class Poste(val phrase: String, val titre: String, val jour: String, val creneau: String, val heures: String)

val HorairesDefaut = linkedMapOf("matin" to "6 h à 14 h", "journee" to "8 h à 17 h", "apresmidi" to "14 h à 22 h", "soir" to "14 h à 22 h", "nuit" to "22 h à 6 h")
val CreneauxNoms = linkedMapOf("matin" to "matin", "journee" to "journée", "apresmidi" to "après-midi", "soir" to "soir", "nuit" to "nuit")

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
