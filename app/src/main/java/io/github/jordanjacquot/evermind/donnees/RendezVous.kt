package io.github.jordanjacquot.evermind.donnees

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Un rendez-vous, avec les mêmes champs que dans le rendez-vous.json d'Evermind PC (donnees.ps1),
 * pour pouvoir un jour synchroniser les deux sans rien perdre.
 * Les dossiers reliés et « ouvrir au début » n'existent que sur PC et ne sont pas repris.
 */
@Entity(tableName = "rendez_vous")
data class RendezVous(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val titre: String,
    val sorte: Sorte = Sorte.RDV,
    /** Le début ; pour un rendez-vous répété, la première fois ; pour toute la journée, le jour à 0 h. */
    val quand: LocalDateTime,
    val journee: Boolean = false,
    val fin: LocalDateTime? = null,
    /** Répétition au format d'Evermind PC (JSON, par exemple {"Type":"semaine","Jour":1}), gardée telle quelle. */
    val repetition: String? = null,
    val lieu: String = "",
    val avec: List<String> = emptyList(),
    val lien: String = "",
    val notes: String = "",
    val aPreparer: List<APreparer> = emptyList(),
    /** null : les rappels par défaut des réglages ; liste vide : aucun rappel. */
    val rappels: List<String>? = null,
    val important: Boolean = false,
    /** Pour un « À faire » : terminé. */
    val fait: Boolean = false,
    /** Année de naissance, pour donner l'âge d'un anniversaire. */
    val naissance: Int? = null,
    val cree: LocalDateTime = LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES),
)

/** Une chose à préparer ou à prendre (« pour le foot prends mes crampons »). */
@Serializable
data class APreparer(val texte: String, val fait: Boolean = false)
