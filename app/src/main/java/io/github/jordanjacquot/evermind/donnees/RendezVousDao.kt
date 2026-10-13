package io.github.jordanjacquot.evermind.donnees

import androidx.room.Dao
import androidx.room.Query
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
}
