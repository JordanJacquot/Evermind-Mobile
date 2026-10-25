package io.github.jordanjacquot.evermind.donnees

import kotlinx.coroutines.flow.Flow

/**
 * Le seul endroit par où l'app lit et écrit les rendez-vous. Après chaque changement, « apresChangement » refait
 * le planning des rappels (c'est aussi ici que viendra la synchronisation).
 */
class DepotRendezVous(private val dao: RendezVousDao, private val apresChangement: () -> Unit = {}) {
    val tous: Flow<List<RendezVous>> = dao.tous()

    suspend fun liste(): List<RendezVous> = dao.liste()

    fun rendezVous(id: String): Flow<RendezVous?> = dao.parId(id)

    suspend fun enregistrer(rendezVous: RendezVous) {
        dao.enregistrer(rendezVous)
        apresChangement()
    }

    /**
     * Les différences entre deux agendas (ajouts, rendez-vous changés, suppressions), appliquées sur l'agenda actuel
     * dans une seule transaction, comme Merge-AgendaAssistant sur PC : ce qui a changé à côté entre-temps reste.
     */
    suspend fun appliquerDifferences(avant: List<RendezVous>, apres: List<RendezVous>) {
        val avantParId = avant.associateBy { it.id }
        val restent = apres.map { it.id }.toSet()
        val aEnregistrer = apres.filter { avantParId[it.id] != it }
        val aSupprimer = avant.map { it.id }.filter { it !in restent }
        if (aEnregistrer.isEmpty() && aSupprimer.isEmpty()) return
        dao.appliquer(aEnregistrer, aSupprimer)
        apresChangement()
    }

    suspend fun supprimer(id: String) {
        dao.supprimer(id)
        apresChangement()
    }
}
