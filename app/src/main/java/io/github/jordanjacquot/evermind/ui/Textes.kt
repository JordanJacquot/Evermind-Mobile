package io.github.jordanjacquot.evermind.ui

import io.github.jordanjacquot.evermind.donnees.RendezVous
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

// Les dates et heures écrites comme Evermind PC : « mardi 14 octobre à 15 h 30 »
private val Fr = Locale.FRENCH
private val JourMois = DateTimeFormatter.ofPattern("EEEE d MMMM", Fr)
private val JourMoisAnnee = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Fr)
private val MoisAnnee = DateTimeFormatter.ofPattern("MMMM yyyy", Fr)

fun majuscule(texte: String) = texte.replaceFirstChar { it.titlecase(Fr) }

fun LocalTime.enTexte(): String = if (minute == 0) "$hour h" else "$hour h %02d".format(minute)

fun LocalDate.enTexte(aujourdHui: LocalDate = LocalDate.now()): String = when (this) {
    aujourdHui -> "aujourd'hui"
    aujourdHui.plusDays(1) -> "demain"
    else -> format(if (year == aujourdHui.year) JourMois else JourMoisAnnee)
}

fun YearMonth.enTexte(): String = majuscule(atDay(1).format(MoisAnnee))

/** « demain à 15 h », « samedi 18 octobre de 14 h à 16 h », « mardi 14 octobre (toute la journée) » */
fun RendezVous.quandEnTexte(aujourdHui: LocalDate = LocalDate.now()): String {
    val jour = quand.toLocalDate().enTexte(aujourdHui)
    if (journee) return "$jour (toute la journée)"
    val fin = fin
    return if (fin != null && fin.toLocalDate() == quand.toLocalDate())
        "$jour de ${quand.toLocalTime().enTexte()} à ${fin.toLocalTime().enTexte()}"
    else "$jour à ${quand.toLocalTime().enTexte()}"
}
