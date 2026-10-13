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

    /** L'agenda devient exactement cette liste (ce qui n'y est plus est supprimé). */
    @Transaction
    suspend fun remplacerTout(liste: List<RendezVous>) {
        val gardes = liste.map { it.id }.toSet()
        for (r in liste()) if (r.id !in gardes) supprimer(r.id)
        for (r in liste) enregistrer(r)
    }
}
