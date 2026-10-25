package io.github.jordanjacquot.evermind.donnees

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface RendezVousDao {
    @Query("SELECT * FROM rendez_vous ORDER BY quand")
    fun tous(): Flow<List<RendezVous>>

    @Query("SELECT * FROM rendez_vous")
    suspend fun liste(): List<RendezVous>

    @Query("SELECT * FROM rendez_vous WHERE id = :id")
    fun parId(id: String): Flow<RendezVous?>

    @Upsert
    suspend fun enregistrer(rendezVous: RendezVous)

    @Query("DELETE FROM rendez_vous WHERE id = :id")
    suspend fun supprimer(id: String)

    /** Seulement ce qui a changé, d'un seul coup : ces rendez-vous enregistrés, ceux-là supprimés. */
    @Transaction
    suspend fun appliquer(aEnregistrer: List<RendezVous>, aSupprimer: List<String>) {
        for (id in aSupprimer) supprimer(id)
        for (r in aEnregistrer) enregistrer(r)
    }
}
