package io.github.jordanjacquot.evermind.assistant

import io.github.jordanjacquot.evermind.comprendre.Repetition
import io.github.jordanjacquot.evermind.comprendre.prochaineFois
import io.github.jordanjacquot.evermind.comprendre.surPlusieursJours
import io.github.jordanjacquot.evermind.comprendre.titreAffiche
import io.github.jordanjacquot.evermind.donnees.RendezVous
import io.github.jordanjacquot.evermind.donnees.Sorte
import io.github.jordanjacquot.evermind.donnees.estFait
import io.github.jordanjacquot.evermind.donnees.laRepetition
import io.github.jordanjacquot.evermind.ui.enTexte
import io.github.jordanjacquot.evermind.ui.enTexteLong
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.math.roundToLong

/** Une fois d'un rendez-vous (« Prochain » sur PC). */
data class FoisIA(val rv: RendezVous, val prochain: LocalDateTime)

/**
 * L'agenda tel que l'assistant le voit et le change (le $script:Agenda du PC) : une liste en mémoire, enregistrée
 * ensuite d'un coup. « maintenant » est fixé pour toute une demande.
 */
class AgendaIA(rendezVous: List<RendezVous>, val maintenant: LocalDateTime) {
    val liste: MutableList<RendezVous> = rendezVous.toMutableList()
    val aujourdHui: LocalDate get() = maintenant.toLocalDate()

    fun get(id: String?): RendezVous? = liste.firstOrNull { it.id == id }

    fun ajouter(r: RendezVous): RendezVous { liste += r; return r }

    fun supprimer(id: String) { liste.removeAll { it.id == id } }

    fun remplacer(r: RendezVous): RendezVous {
        val i = liste.indexOfFirst { it.id == r.id }
        check(i >= 0) { "ce rendez-vous n'existe plus" }
        liste[i] = r
        return r
    }

    /** Toutes les fois entre deux moments (Get-AgendaEntre), début compris, fin exclue, dans l'ordre. */
    fun entre(debut: LocalDateTime, fin: LocalDateTime): List<FoisIA> = buildList {
        for (r in liste) {
            val rep = r.laRepetition
            if (rep == null) {
                if (surPlusieursJours(r.quand, r.fin, r.journee, false)) {
                    // Sur plusieurs jours : il apparaît chaque jour (le premier à son heure, les suivants dès minuit)
                    var j = r.quand.toLocalDate()
                    while (!j.isAfter(r.fin!!.toLocalDate())) {
                        val q = if (j == r.quand.toLocalDate()) r.quand else j.atStartOfDay()
                        if (!q.isBefore(debut) && q.isBefore(fin)) add(FoisIA(r, q))
                        j = j.plusDays(1)
                    }
                    continue
                }
                if (!r.quand.isBefore(debut) && r.quand.isBefore(fin)) add(FoisIA(r, r.quand))
                continue
            }
            var depuis = debut
            for (i in 0 until 400) {
                val q = prochaineFois(rep, r.quand, depuis) ?: break
                if (!q.isBefore(fin) || q.isBefore(depuis)) break
                add(FoisIA(r, q))
                depuis = q.plusMinutes(1)
            }
        }
    }.sortedBy { it.prochain }

    /** Les rendez-vous à venir, avec leur prochaine fois (Get-Upcoming). */
    fun aVenir(): List<FoisIA> = liste.mapNotNull { r ->
        val depuis = if (r.journee) aujourdHui.atStartOfDay() else maintenant
        var next = prochaine(r, depuis)
        if (next == null || next.isBefore(depuis)) {
            if (surPlusieursJours(r.quand, r.fin, r.journee, r.repetition != null) && !r.fin!!.isBefore(depuis)) next = depuis.toLocalDate().atStartOfDay()
        }
        if (next != null && !next.isBefore(depuis) && !r.estFait(next)) FoisIA(r, next) else null
    }.sortedBy { it.prochain }

    /** La prochaine fois à partir d'un moment (null s'il n'y en a plus) ; le rendez-vous lui-même s'il ne revient pas. */
    fun prochaine(r: RendezVous, depuis: LocalDateTime): LocalDateTime? = prochaineFois(r.laRepetition, r.quand, depuis)

    /** Les autres rendez-vous au même moment (une heure par défaut sans heure de fin), Get-Conflits. */
    fun conflits(debut: LocalDateTime, fin: LocalDateTime?, saufId: String): List<FoisIA> {
        val f = fin ?: debut.plusHours(1)
        return entre(debut.toLocalDate().atStartOfDay(), debut.toLocalDate().plusDays(1).atStartOfDay()).filter { x ->
            val finX = x.rv.fin?.let { x.prochain.plus(Duration.between(x.rv.quand, it)) } ?: x.prochain.plusHours(1)
            x.rv.id != saufId && !x.rv.journee && !x.rv.estFait(x.prochain) && x.prochain.isBefore(f) && finX.isAfter(debut)
        }
    }

    companion object {
        fun nouveau(titre: String, quand: LocalDateTime, rep: Repetition?, sorte: Sorte, journee: Boolean, maintenant: LocalDateTime) =
            RendezVous(
                id = UUID.randomUUID().toString(), titre = titre.trim(), sorte = sorte, quand = quand, journee = journee,
                repetition = rep?.versJson(), cree = maintenant.truncatedTo(ChronoUnit.MINUTES),
            )
    }
}

// ---------------------------------------------------------------------------
// Les dates écrites comme sur PC (Format-Jour, Format-QuandRV, Format-Relatif)
// ---------------------------------------------------------------------------
internal val Fr: java.util.Locale = java.util.Locale.FRENCH
internal val JourNum: java.time.format.DateTimeFormatter = java.time.format.DateTimeFormatter.ofPattern("EEEE d", Fr)
internal val JourNumMois: java.time.format.DateTimeFormatter = java.time.format.DateTimeFormatter.ofPattern("EEEE d MMMM", Fr)

/** « vendredi 27 février à 15 h », « samedi 18 octobre de 14 h à 16 h », « du mardi 27 au jeudi 29 octobre ». */
fun quandRV(r: RendezVous, d: LocalDateTime, aujourdHui: LocalDate): String {
    val fin = r.fin
    if (fin != null && surPlusieursJours(r.quand, fin, r.journee, r.repetition != null)) {
        val a = r.quand.toLocalDate()
        val b = fin.toLocalDate()
        val fb = b.enTexteLong(aujourdHui)
        if (r.journee) {
            val fa = if (a.month == b.month && a.year == b.year) a.format(JourNum) else a.format(JourNumMois)
            return "du $fa au $fb"
        }
        val heureFin = if (fin.toLocalTime().isBefore(java.time.LocalTime.of(23, 59))) " à ${fin.toLocalTime().enTexte()}" else ""
        return "du ${a.enTexteLong(aujourdHui)} à ${r.quand.toLocalTime().enTexte()} au $fb$heureFin"
    }
    val jour = d.toLocalDate().enTexteLong(aujourdHui)
    if (!r.journee) {
        if (fin != null) { val f = d.plus(Duration.between(r.quand, fin)); return "$jour de ${d.toLocalTime().enTexte()} à ${f.toLocalTime().enTexte()}" }
        return "$jour à ${d.toLocalTime().enTexte()}"
    }
    return if (r.sorte == Sorte.ANNIV) jour else "$jour, toute la journée"
}

/** « aujourd'hui », « demain », « dans 5 jours », « dans 3 semaines »... */
fun relatif(d: LocalDateTime, aujourdHui: LocalDate): String {
    val jours = ChronoUnit.DAYS.between(aujourdHui, d.toLocalDate())
    return when {
        jours == 0L -> "aujourd'hui"
        jours == 1L -> "demain"
        jours == 2L -> "après-demain"
        jours < 0 -> "passé"
        jours < 14 -> "dans $jours jours"
        jours < 60 -> "dans ${jours / 7} semaines"
        else -> "dans ${(jours / 30.4).roundToLong()} mois"
    }
}

fun titreDe(r: RendezVous) = titreAffiche(r.titre)
