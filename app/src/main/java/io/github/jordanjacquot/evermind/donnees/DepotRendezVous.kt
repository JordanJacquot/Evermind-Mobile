package io.github.jordanjacquot.evermind.donnees

import kotlinx.coroutines.flow.Flow

/**
 * Le seul endroit par où l'app lit et écrit les rendez-vous.
 * C'est ici que viendront plus tard les rappels (à reprogrammer à chaque changement) et la synchronisation.
 */
class DepotRendezVous(private val dao: RendezVousDao) {
    val tous: Flow<List<RendezVous>> = dao.tous()

    fun rendezVous(id: String): Flow<RendezVous?> = dao.parId(id)

    suspend fun enregistrer(rendezVous: RendezVous) = dao.enregistrer(rendezVous)

    suspend fun supprimer(id: String) = dao.supprimer(id)
}
