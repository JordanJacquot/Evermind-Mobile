package io.github.jordanjacquot.evermind.comprendre

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/**
 * Un rendez-vous qui revient, écrit comme dans le rendez-vous.json d'Evermind PC :
 *   { "Type": "jour" } | { "Type": "semaine", "Jour": 0..6 (0 = dimanche), "Jours": [...] }
 *   | { "Type": "mois", "Jour": 1..31 } ou { "Type": "mois", "Jour": 0..6, "Rang": 1..4 ou -1 (le dernier) }
 *   | { "Type": "an", "Jour": 1..31, "Mois": 1..12 }
 * Intervalle : toutes les n fois ; Jusqua : dernier jour « yyyy-MM-dd » ; Sauf : jours enlevés de la série.
 * Un jour qui n'existe pas dans un mois (le 31) tombe le dernier jour de ce mois.
 */
@Serializable
data class Repetition(
    @SerialName("Type") var type: String,
    @SerialName("Jour") var jour: Int? = null,
    @SerialName("Jours") var jours: List<Int>? = null,
    @SerialName("Mois") var mois: Int? = null,
    @SerialName("Rang") var rang: Int? = null,
    @SerialName("Intervalle") var intervalle: Int? = null,
    @SerialName("Jusqua") var jusqua: String? = null,
    @SerialName("Sauf") var sauf: List<String>? = null,
) {
    fun versJson(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        fun depuisJson(texte: String?): Repetition? =
            texte?.let { runCatching { json.decodeFromString(serializer(), it) }.getOrNull() }
    }
}

/**
 * La prochaine fois à partir de « depuis » (compris) ; null s'il n'y en a plus (après « Jusqua »).
 * « premier » est la première fois : il donne l'heure et le point de départ des intervalles.
 */
fun prochaineFois(rep: Repetition?, premier: LocalDateTime, depuis: LocalDateTime): LocalDateTime? {
    if (rep == null) return premier
    var c = prochaine(rep, premier, depuis)
    val sauf = rep.sauf.orEmpty()
    var i = 0
    while (sauf.isNotEmpty() && i < 400 && c.toLocalDate().toString() in sauf) {
        c = prochaine(rep, premier, c.plusMinutes(1)); i++
    }
    val jusqua = rep.jusqua?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    if (jusqua != null && c.toLocalDate().isAfter(jusqua)) return null
    return c
}

private fun prochaine(rep: Repetition, premier: LocalDateTime, depuis: LocalDateTime): LocalDateTime {
    val base = if (depuis.isAfter(premier)) depuis else premier
    val heure = premier.toLocalTime()
    val n = rep.intervalle?.takeIf { it > 1 } ?: 1
    when (rep.type) {
        "jour" -> {
            // Tous les n jours, en comptant depuis le premier
            var c = base.toLocalDate().atTime(heure)
            if (c.isBefore(base)) c = c.plusDays(1)
            val ecart = ChronoUnit.DAYS.between(premier.toLocalDate(), c.toLocalDate()) % n
            if (ecart != 0L) c = c.plusDays(n - ecart)
            return c
        }
        "semaine" -> {
            // Un ou plusieurs jours de la semaine, toutes les n semaines (comptées depuis celle du premier)
            val jours = rep.jours?.takeIf { it.isNotEmpty() } ?: listOf(rep.jour ?: 0)
            val lundiPremier = lundiDe(premier.toLocalDate())
            for (k in 0..(7 * n + 7)) {
                val c = base.toLocalDate().plusDays(k.toLong()).atTime(heure)
                if (c.isBefore(base) || c.toLocalDate().jds !in jours) continue
                val semaines = Math.floorDiv(ChronoUnit.DAYS.between(lundiPremier, c.toLocalDate()), 7L)
                if (semaines % n == 0L) return c
            }
        }
        "mois" -> {
            // Un jour du mois (le 5), ou un rang (« le premier lundi », « le dernier vendredi »), tous les n mois
            val debutPremier = premier.toLocalDate().premierDuMois
            for (k in 0..(24 * n)) {
                val first = base.toLocalDate().premierDuMois.plusMonths(k.toLong())
                val ecartMois = (first.year - debutPremier.year) * 12 + first.monthValue - debutPremier.monthValue
                if (ecartMois % n != 0) continue
                val rang = rep.rang ?: 0
                val c = if (rang != 0) {
                    val wd = rep.jour ?: 0
                    val d = if (rang > 0) {
                        val d = first.plusDays((((wd - first.jds) + 7) % 7).toLong()).plusDays(7L * (rang - 1))
                        if (d.month != first.month) continue
                        d
                    } else {
                        val der = first.dernierDuMois
                        der.minusDays((((der.jds - wd) + 7) % 7).toLong())
                    }
                    d.atTime(heure)
                } else {
                    val d = minOf(rep.jour ?: 0, first.joursDuMois)
                    first.plusDays((d - 1).toLong()).atTime(heure)
                }
                if (!c.isBefore(base)) return c
            }
        }
        "an" -> {
            val mois = rep.mois ?: 1
            for (k in 0..(8 * n)) {
                val y = base.year + k
                if ((y - premier.year) % n != 0) continue
                val d = minOf(rep.jour ?: 1, YearMonth.of(y, mois).lengthOfMonth())
                val c = LocalDate.of(y, mois, d).atTime(heure)
                if (!c.isBefore(base)) return c
            }
        }
    }
    return premier
}
