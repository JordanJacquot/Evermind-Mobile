package io.github.jordanjacquot.evermind.rappels

import android.content.Context
import io.github.jordanjacquot.evermind.comprendre.surPlusieursJours
import io.github.jordanjacquot.evermind.donnees.RendezVous
import io.github.jordanjacquot.evermind.donnees.Sorte
import io.github.jordanjacquot.evermind.donnees.estFait
import io.github.jordanjacquot.evermind.donnees.fois
import io.github.jordanjacquot.evermind.ui.enTexte
import io.github.jordanjacquot.evermind.ui.enTexteLong
import io.github.jordanjacquot.evermind.ui.majuscule
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import kotlin.math.floor
import kotlin.math.roundToLong

// Les rappels, comme sur PC (alerte.ps1) : « veille » la veille à 20 h, « matin » le matin même à 8 h,
// « m60 » 60 minutes avant. Chaque rendez-vous a ses rappels, ou (null) ceux par défaut de sa sorte.

/** Les rappels qu'on peut choisir pour un rendez-vous (comme dans le formulaire du PC). */
val RappelsChoix = listOf("m0", "m10", "m30", "m60", "matin", "veille", "m2880", "m10080")

/** Quels rappels par défaut sont voulus (Réglages) : on peut couper la veille, le matin ou l'heure avant. */
class ReglagesRappels(contexte: Context) {
    private val prefs = contexte.getSharedPreferences("rappels", Context.MODE_PRIVATE)
    var veille: Boolean
        get() = prefs.getBoolean("veille", true)
        set(v) { prefs.edit().putBoolean("veille", v).apply() }
    var matin: Boolean
        get() = prefs.getBoolean("matin", true)
        set(v) { prefs.edit().putBoolean("matin", v).apply() }
    var heureAvant: Boolean
        get() = prefs.getBoolean("m60", true)
        set(v) { prefs.edit().putBoolean("m60", v).apply() }
}

/** Les rappels par défaut d'une sorte (Get-RappelsDefaut sur PC), moins ceux coupés dans les réglages. */
fun rappelsDefaut(sorte: Sorte, journee: Boolean, r: ReglagesRappels): List<String> {
    val pc = when {
        journee && sorte == Sorte.A_FAIRE -> listOf("matin")   // une chose à faire dans la journée : le matin même
        journee || sorte == Sorte.ANNIV -> listOf("veille", "matin")
        sorte == Sorte.A_FAIRE -> listOf("m0")
        else -> listOf("veille", "matin", "m60")
    }
    return pc.filter {
        when (it) {
            "veille" -> r.veille
            "matin" -> r.matin
            "m60" -> r.heureAvant
            else -> true
        }
    }
}

fun RendezVous.rappelsVoulus(r: ReglagesRappels): List<String> = rappels ?: rappelsDefaut(sorte, journee, r)

/** Le moment d'un rappel pour une fois du rendez-vous. */
fun momentRappel(code: String, quand: LocalDateTime): LocalDateTime? = when {
    code == "veille" -> quand.toLocalDate().minusDays(1).atTime(20, 0)
    code == "matin" -> quand.toLocalDate().atTime(8, 0)
    code.startsWith("m") -> code.drop(1).toLongOrNull()?.let { quand.minusMinutes(it) }
    else -> null
}

/** Un rappel à donner : tel rendez-vous, telle fois, à tel moment. */
data class RappelPrevu(val rendezVous: RendezVous, val fois: LocalDateTime, val code: String, val moment: LocalDateTime) {
    /** Pour ne jamais donner deux fois le même rappel. */
    val cle get() = "${rendezVous.id}|$fois|$code"
}

/**
 * Les rappels dont le moment tombe entre « de » (exclu) et « a » (compris), dans l'ordre.
 * Comme sur PC : rien pour un « À faire » déjà fait (pour une chose qui revient, seulement la fois cochée), seulement le premier jour d'un événement sur plusieurs jours,
 * et pas de « matin même » après l'heure du rendez-vous (un rendez-vous à 7 h).
 */
fun rappelsEntre(tous: List<RendezVous>, de: LocalDateTime, a: LocalDateTime, r: ReglagesRappels): List<RappelPrevu> {
    val liste = mutableListOf<RappelPrevu>()
    for (rdv in tous) {
        if (rdv.repetition == null && rdv.fait) continue
        val codes = rdv.rappelsVoulus(r)
        if (codes.isEmpty()) continue
        // (les fois qui peuvent avoir un rappel dans la période : jusqu'à une semaine plus tard, le plus long rappel)
        for (f in rdv.fois(de.toLocalDate(), a.toLocalDate().plusDays(8))) {
            val debut = f.debut
            if (rdv.estFait(debut)) continue
            if (rdv.repetition == null && surPlusieursJours(rdv.quand, rdv.fin, rdv.journee, false) && debut.toLocalDate() != rdv.quand.toLocalDate()) continue
            for (code in codes) {
                val m = momentRappel(code, debut) ?: continue
                if (!m.isAfter(de) || m.isAfter(a)) continue
                if (!rdv.journee && m.isAfter(debut)) continue
                liste += RappelPrevu(rdv, debut, code, m)
            }
        }
    }
    return liste.sortedBy { it.moment }
}

/** Un rappel à donner maintenant (le plus récent dû pour cette fois du rendez-vous), et les clés de tous ceux qu'il remplace. */
data class RappelADonner(val rappel: RappelPrevu, val cles: List<String>, val enRetard: Boolean)

/** Un rappel ordinaire peut avoir jusqu'à 15 minutes de retard (l'alarme d'Android n'est pas toujours à la seconde). */
const val RETARD_ORDINAIRE_MIN = 15L
/** Après une coupure, on rattrape au plus 8 jours (le plus long rappel est une semaine avant). */
const val RATTRAPAGE_JOURS = 8L

/**
 * Les rappels à donner maintenant, une seule fois chacun, un seul par fois d'un rendez-vous (le plus récent).
 * Ceux dus depuis 15 minutes au plus ; et, pour un rendez-vous qui n'a pas encore commencé, ceux manqués depuis le
 * dernier passage (téléphone éteint, redémarré et pas encore déverrouillé, alarme d'Android en retard) : ils sont donnés
 * « en retard » au lieu d'être perdus. Un rendez-vous déjà commencé n'a pas de vieux rappel.
 */
fun rappelsADonner(tous: List<RendezVous>, dernier: LocalDateTime?, maintenant: LocalDateTime, r: ReglagesRappels,
                   faits: Set<String>): List<RappelADonner> {
    val recent = maintenant.minusMinutes(RETARD_ORDINAIRE_MIN)
    // (une heure reculée met « dernier » dans le futur : on repart de maintenant, les clés évitent les doublons)
    val depuis = dernier?.let { maxOf(minOf(it, maintenant), maintenant.minusDays(RATTRAPAGE_JOURS)) } ?: recent
    fun pasCommence(p: RappelPrevu) =
        if (p.rendezVous.journee) !p.fois.toLocalDate().isBefore(maintenant.toLocalDate()) else p.fois.isAfter(maintenant)
    return rappelsEntre(tous, minOf(depuis, recent), maintenant, r)
        .filter { it.cle !in faits }
        .filter { it.moment.isAfter(recent) || (it.moment.isAfter(depuis) && pasCommence(it)) }
        .groupBy { it.rendezVous.id to it.fois }
        .values.map { dus ->
            val p = dus.maxBy { it.moment }
            RappelADonner(p, dus.map { it.cle }, enRetard = !p.moment.isAfter(recent))
        }
}

/** « Dans 10 min » : le rappel de telle fois revient à tel instant (gardé pour survivre à un redémarrage du téléphone). */
data class Report(val id: String, val fois: String, val quand: Instant) {
    fun enTexte() = "$id|$fois|${quand.toEpochMilli()}"
    companion object {
        /** null pour une ligne abîmée. */
        fun lire(texte: String): Report? {
            val a = texte.indexOf('|')
            val b = texte.lastIndexOf('|')
            if (a <= 0 || b <= a + 1) return null
            val ms = texte.substring(b + 1).toLongOrNull() ?: return null
            return Report(texte.substring(0, a), texte.substring(a + 1, b), Instant.ofEpochMilli(ms))
        }
    }
}

/** Les reports gardés : ceux à donner tout de suite (l'heure est passée, depuis moins d'un jour) et ceux à remettre sur une alarme. */
fun trierReports(textes: Set<String>, maintenant: Instant): Pair<List<Report>, List<Report>> {
    val reports = textes.mapNotNull { Report.lire(it) }
    val dus = reports.filter { !it.quand.isAfter(maintenant) && it.quand.isAfter(maintenant.minus(Duration.ofDays(1))) }
    val aVenir = reports.filter { it.quand.isAfter(maintenant) }
    return dus to aVenir
}

/** « dans 1 h », « dans 25 min », « demain », « c'est l'heure » (Get-TexteDans sur PC). */
fun texteDans(quand: LocalDateTime, maintenant: LocalDateTime, journee: Boolean): String {
    val jours = Duration.between(maintenant.toLocalDate().atStartOfDay(), quand.toLocalDate().atStartOfDay()).toDays()
    fun parJours() = when (jours) { 0L -> "aujourd'hui"; 1L -> "demain"; else -> "dans $jours jours" }
    if (journee) return parJours()
    val min = Duration.between(maintenant, quand).seconds / 60.0
    if (min <= 1) return "c'est l'heure"
    if (min < 59.5) return "dans ${min.roundToLong()} min"
    if (jours == 0L && min < 360) {
        val h = floor((min + 2) / 60).toLong()
        val m = (min - h * 60).roundToLong()
        return "dans $h h" + if (m >= 5) " %02d".format(m) else ""
    }
    return parJours()
}

/** Court : « 15 h » aujourd'hui, « Mercredi 14 octobre, 15 h » un autre jour, « Toute la journée » (Get-HeureAlerte). */
fun heureAlerte(rdv: RendezVous, quand: LocalDateTime, maintenant: LocalDateTime): String {
    val jour = if (quand.toLocalDate() == maintenant.toLocalDate()) "" else majuscule(quand.toLocalDate().enTexteLong(maintenant.toLocalDate()))
    if (rdv.journee) return jour.ifEmpty { "Toute la journée" }
    val h = quand.toLocalTime().enTexte()
    return if (jour.isEmpty()) h else "$jour, $h"
}
