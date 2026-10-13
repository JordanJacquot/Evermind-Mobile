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

    suspend fun supprimer(id: String) {
        dao.supprimer(id)
        apresChangement()
    }
}
