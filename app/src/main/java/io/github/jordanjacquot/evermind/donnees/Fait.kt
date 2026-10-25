package io.github.jordanjacquot.evermind.donnees

import io.github.jordanjacquot.evermind.comprendre.prochaineFois
import java.time.LocalDate
import java.time.LocalDateTime

// « C'est fait », comme sur PC (donnees.ps1) : pour un rendez-vous unique, « fait » ; pour une chose qui revient, chaque
// fois à part (« faitPour », les jours faits). Cocher « les poubelles » ce mardi ne coupe pas les mardis suivants.

/** La clé d'une fois dans « faitPour » (Get-CleFait) : aucune répétition ne revient plus d'une fois par jour. */
fun cleFait(quand: LocalDateTime): String = quand.toLocalDate().toString()

/** Cette fois est-elle faite ? (Test-FaitRV) ; pour un rendez-vous unique, la fois ne compte pas. */
fun RendezVous.estFait(quand: LocalDateTime?): Boolean =
    if (repetition == null) fait else quand != null && cleFait(quand) in faitPour

/**
 * Les fois de « jours » jours avant « jusqua » jusqu'à « jusqua » compris (Get-FoisAvant) ; 0 : assez loin pour trouver
 * la dernière fois, selon son rythme (2 jours pour une chose de chaque jour, 2 semaines pour une chose de chaque semaine...).
 */
fun RendezVous.foisAvant(jusqua: LocalDateTime, jours: Int): List<LocalDateTime> {
    val rep = laRepetition ?: return if (!quand.isAfter(jusqua)) listOf(quand) else emptyList()
    var j = jours.toLong()
    if (j <= 0) {
        val n = (rep.intervalle ?: 1).coerceAtLeast(1).toLong()
        j = when (rep.type) { "jour" -> n + 1; "semaine" -> 7 * n + 7; "mois" -> 31 * n + 31; else -> 366 * n + 366 }
    }
    val de = jusqua.toLocalDate().minusDays(j).atStartOfDay()
    val liste = mutableListOf<LocalDateTime>()
    var depuis = de
    while (liste.size < 400) {
        val q = prochaineFois(rep, quand, depuis) ?: break
        if (q.isAfter(jusqua) || q.isBefore(de)) break
        liste += q
        depuis = q.plusMinutes(1)
    }
    return liste
}

/**
 * « C'est fait » dit sans date (Get-FoisAFaire) : seulement une fois déjà commencée (ou qui commence dans moins de
 * 30 minutes), jamais une fois à venir plus tard dans la journée. Parmi elles, la plus récente pas encore cochée (choix
 * de Jordan, comme sur PC) : les cachets du soir dits faits à 9 h, ceux d'hier soir ; les poubelles du mardi dites
 * mercredi, celles de mardi. Rend cette fois (la dernière commencée si tout est coché), ou null si rien n'a commencé.
 */
fun RendezVous.foisAFaire(maintenant: LocalDateTime): LocalDateTime? {
    val fois = foisAvant(maintenant.plusMinutes(30), 0)
    if (fois.isEmpty()) return null
    return fois.lastOrNull { !estFait(it) } ?: fois.last()
}

/** « Pas encore fait » dit sans date : la dernière fois cochée parmi celles déjà commencées, ou null. */
fun RendezVous.derniereFoisFaite(maintenant: LocalDateTime): LocalDateTime? =
    foisAvant(maintenant.plusMinutes(30), 0).lastOrNull { estFait(it) }

/**
 * Coche ou décoche une fois (Set-FaitRV) ; pour un rendez-vous unique, « fait ». Les fois cochées de plus de 90 jours
 * sont oubliées.
 */
fun RendezVous.avecFait(quand: LocalDateTime?, valeur: Boolean, aujourdHui: LocalDate = LocalDate.now()): RendezVous {
    if (repetition == null) return copy(fait = valeur)
    if (quand == null) return this
    val cle = cleFait(quand)
    val limite = aujourdHui.minusDays(90).toString()
    val liste = faitPour.filter { it != cle && it >= limite }.toMutableList()
    if (valeur) liste += cle
    return copy(faitPour = liste.sorted(), fait = false)
}

/**
 * Avant « faitPour », « c'est fait » sur une chose qui revient la rendait muette pour toujours (fait = oui) : elle devient
 * « faite pour sa dernière fois passée » (ou sa première, si elle n'a pas encore commencé), comme Update-FaitAncien sur PC.
 */
fun RendezVous.sansFaitAncien(maintenant: LocalDateTime): RendezVous {
    if (!fait || repetition == null) return this
    val q = foisAvant(maintenant, 0).lastOrNull() ?: laRepetition?.let { prochaineFois(it, quand, quand) } ?: quand
    return copy(fait = false, faitPour = (faitPour + cleFait(q)).distinct().sorted())
}
