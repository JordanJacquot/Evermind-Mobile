package io.github.jordanjacquot.evermind.donnees

import io.github.jordanjacquot.evermind.comprendre.Repetition
import io.github.jordanjacquot.evermind.comprendre.prochaineFois
import io.github.jordanjacquot.evermind.comprendre.surPlusieursJours
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

/** Une fois où le rendez-vous a lieu (le rendez-vous lui-même, ou l'une des fois d'un rendez-vous qui revient). */
data class Fois(val rendezVous: RendezVous, val debut: LocalDateTime) {
    /** Les jours qu'elle occupe : un seul, sauf pour un événement sur plusieurs jours. */
    val jours: List<LocalDate>
        get() {
            val r = rendezVous
            val fin = r.fin?.let { debut.plus(Duration.between(r.quand, it)) }
            if (fin == null || !surPlusieursJours(debut, fin, r.journee, r.repetition != null)) return listOf(debut.toLocalDate())
            return generateSequence(debut.toLocalDate()) { it.plusDays(1) }.takeWhile { !it.isAfter(fin.toLocalDate()) }.take(400).toList()
        }
}

val RendezVous.laRepetition: Repetition? get() = Repetition.depuisJson(repetition)

/** Les fois qui touchent la période (du jour « de » au jour « a », compris). */
fun RendezVous.fois(de: LocalDate, a: LocalDate): List<Fois> {
    val rep = laRepetition
    if (rep == null) {
        val f = Fois(this, quand)
        return if (f.jours.any { !it.isBefore(de) && !it.isAfter(a) }) listOf(f) else emptyList()
    }
    val liste = mutableListOf<Fois>()
    var c = prochaineFois(rep, quand, de.atStartOfDay())
    while (c != null && !c.toLocalDate().isAfter(a) && liste.size < 400) {
        liste += Fois(this, c)
        c = prochaineFois(rep, quand, c.plusMinutes(1))
    }
    return liste
}

/** La prochaine fois à partir d'aujourd'hui (celle en cours compte encore), ou null s'il n'y en a plus. */
fun RendezVous.prochaineFoisDepuis(aujourdHui: LocalDate): Fois? {
    val rep = laRepetition ?: return Fois(this, quand).takeIf { f -> f.jours.last() >= aujourdHui }
    return prochaineFois(rep, quand, aujourdHui.atStartOfDay())?.let { Fois(this, it) }
}
