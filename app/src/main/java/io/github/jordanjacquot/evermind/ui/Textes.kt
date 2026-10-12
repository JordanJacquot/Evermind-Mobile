package io.github.jordanjacquot.evermind.ui

import io.github.jordanjacquot.evermind.comprendre.Repetition
import io.github.jordanjacquot.evermind.comprendre.surPlusieursJours
import io.github.jordanjacquot.evermind.donnees.RendezVous
import io.github.jordanjacquot.evermind.donnees.Sorte
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.Month
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

// Les dates et heures écrites comme Evermind PC : « mardi 14 octobre à 15 h 30 », « samedi 1er novembre à midi »
private val Fr = Locale.FRENCH
private val NomJour = DateTimeFormatter.ofPattern("EEEE", Fr)
private val MoisAnnee = DateTimeFormatter.ofPattern("MMMM yyyy", Fr)

fun majuscule(texte: String) = texte.replaceFirstChar { it.titlecase(Fr) }

fun LocalTime.enTexte(): String = when {
    hour == 12 && minute == 0 -> "midi"
    hour == 0 && minute == 0 -> "minuit"
    minute == 0 -> "$hour h"
    else -> "$hour h %02d".format(minute)
}

private fun nomMois(m: Int) = Month.of(m).getDisplayName(TextStyle.FULL, Fr)
private fun nomJour(jds: Int) = DayOfWeek.of(if (jds == 0) 7 else jds).getDisplayName(TextStyle.FULL, Fr)
private fun numero(d: Int) = if (d == 1) "1er" else "$d"

/** « jeudi 15 octobre », « samedi 1er novembre », avec l'année si ce n'est pas celle en cours. */
fun LocalDate.enTexteLong(aujourdHui: LocalDate = LocalDate.now()): String =
    "${format(NomJour)} ${numero(dayOfMonth)} ${nomMois(monthValue)}" + if (year != aujourdHui.year) " $year" else ""

/** Comme enTexteLong, mais « aujourd'hui » et « demain » quand c'est le cas. */
fun LocalDate.enTexte(aujourdHui: LocalDate = LocalDate.now()): String = when (this) {
    aujourdHui -> "aujourd'hui"
    aujourdHui.plusDays(1) -> "demain"
    else -> enTexteLong(aujourdHui)
}

fun YearMonth.enTexte(): String = majuscule(atDay(1).format(MoisAnnee))

/**
 * Quand a lieu ce rendez-vous (ou l'une de ses fois, s'il revient) : « demain à 15 h », « samedi 18 octobre de 14 h à
 * 16 h », « du mardi 27 au jeudi 29 octobre », « mardi 14 octobre (toute la journée) ».
 */
fun RendezVous.quandEnTexte(fois: LocalDateTime = quand, aujourdHui: LocalDate = LocalDate.now()): String {
    val finFois = fin?.let { fois.plus(Duration.between(quand, it)) }
    if (surPlusieursJours(fois, finFois, journee, repetition != null) && finFois != null) {
        val a = fois.toLocalDate()
        val b = finFois.toLocalDate()
        val debut = if (a.month == b.month && a.year == b.year) "${a.format(NomJour)} ${numero(a.dayOfMonth)}" else a.enTexteLong(aujourdHui)
        if (journee) return "du $debut au ${b.enTexteLong(aujourdHui)}"
        val heureFin = if (finFois.toLocalTime() < LocalTime.of(23, 59)) " à ${finFois.toLocalTime().enTexte()}" else ""
        return "du ${a.enTexteLong(aujourdHui)} à ${fois.toLocalTime().enTexte()} au ${b.enTexteLong(aujourdHui)}$heureFin"
    }
    val jour = fois.toLocalDate().enTexte(aujourdHui)
    if (journee) return if (sorte == Sorte.ANNIV) jour else "$jour (toute la journée)"
    if (finFois != null) return "$jour de ${fois.toLocalTime().enTexte()} à ${finFois.toLocalTime().enTexte()}"
    return "$jour à ${fois.toLocalTime().enTexte()}"
}

/** « tous les lundis », « tous les jours », « le 5 de chaque mois », « tous les ans le 12 mars » (Format-Repetition sur PC). */
fun Repetition.enTexte(aujourdHui: LocalDate = LocalDate.now()): String {
    val n = intervalle?.takeIf { it > 1 } ?: 1
    val js = jours.orEmpty()
    var t = when (type) {
        "jour" -> if (n > 1) "tous les $n jours" else "tous les jours"
        "semaine" -> when {
            js.size > 1 -> {
                val l = when {
                    js.sorted() == listOf(1, 2, 3, 4, 5) -> "en semaine (du lundi au vendredi)"
                    js.sorted() == listOf(0, 6) -> "le week-end"
                    else -> { val noms = js.map { nomJour(it) }; "le ${noms.dropLast(1).joinToString(", ")} et le ${noms.last()}" }
                }
                if (n > 1) "$l, une semaine sur $n" else l
            }
            n == 2 -> "un ${nomJour(jour ?: 0)} sur deux"
            n > 2 -> "toutes les $n semaines le ${nomJour(jour ?: 0)}"
            else -> "tous les ${nomJour(jour ?: 0)}s"
        }
        "mois" -> {
            val quoi = if ((rang ?: 0) != 0) {
                val r = when (rang) { 1 -> "premier"; 2 -> "deuxième"; 3 -> "troisième"; 4 -> "quatrième"; else -> "dernier" }
                "le $r ${nomJour(jour ?: 0)}"
            } else "le ${numero(jour ?: 1)}"
            if (n > 1) "tous les $n mois, $quoi" else "$quoi de chaque mois"
        }
        "an" -> (if (n > 1) "tous les $n ans" else "tous les ans") + " le ${numero(jour ?: 1)} ${nomMois(mois ?: 1)}"
        else -> ""
    }
    jusqua?.let { runCatching { LocalDate.parse(it) }.getOrNull() }?.let { j ->
        t += " jusqu'au ${numero(j.dayOfMonth)} ${nomMois(j.monthValue)}" + if (j.year != aujourdHui.year) " ${j.year}" else ""
    }
    // Les fois sautées à venir (seulement les jours où il aurait eu lieu)
    val sautees = sauf.orEmpty().mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }.filter { d ->
        !d.isBefore(aujourdHui) && when (type) {
            "semaine" -> if (js.isNotEmpty()) d.dayOfWeek.value % 7 in js else d.dayOfWeek.value % 7 == jour
            "mois" -> d.dayOfMonth == jour
            "an" -> d.dayOfMonth == jour && d.monthValue == mois
            else -> true
        }
    }.sorted()
    if (sautees.size == 1) t += " (sauf le ${numero(sautees[0].dayOfMonth)} ${nomMois(sautees[0].monthValue)})"
    else if (sautees.size > 1) t += " (sauf ${sautees.size} fois)"
    return t
}

/** Un rappel : « veille » (la veille à 20 h), « matin » (le matin même à 8 h), « m60 » (60 minutes avant). */
fun rappelEnTexte(code: String): String {
    when (code) {
        "veille" -> return "la veille à 20 h"
        "matin" -> return "le matin même à 8 h"
        "m0" -> return "à l'heure"
    }
    val n = code.removePrefix("m").toIntOrNull() ?: return code
    return when {
        n % 10080 == 0 -> (n / 10080).let { "$it semaine${if (it > 1) "s" else ""} avant" }
        n % 1440 == 0 -> (n / 1440).let { "$it jour${if (it > 1) "s" else ""} avant" }
        n % 60 == 0 -> "${n / 60} h avant"
        else -> "$n min avant"
    }
}
