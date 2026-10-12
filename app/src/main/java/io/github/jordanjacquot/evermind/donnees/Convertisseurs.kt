package io.github.jordanjacquot.evermind.donnees

import androidx.room.TypeConverter
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Format des dates d'Evermind PC ($DateFormat) ; triées comme du texte, elles restent dans l'ordre. */
val FormatDate: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")

/** Comment Room range ce qui n'est pas un simple texte ou nombre (les null sont gérés par Room). */
class Convertisseurs {
    private val json = Json { ignoreUnknownKeys = true }

    @TypeConverter fun dateVersTexte(d: LocalDateTime): String = d.format(FormatDate)
    @TypeConverter fun texteVersDate(t: String): LocalDateTime = LocalDateTime.parse(t, FormatDate)

    @TypeConverter fun sorteVersTexte(s: Sorte): String = s.code
    @TypeConverter fun texteVersSorte(t: String): Sorte = Sorte.depuisCode(t)

    @TypeConverter fun textesVersJson(l: List<String>): String = json.encodeToString(l)
    @TypeConverter fun jsonVersTextes(t: String): List<String> = json.decodeFromString(t)

    @TypeConverter fun aPreparerVersJson(l: List<APreparer>): String = json.encodeToString(l)
    @TypeConverter fun jsonVersAPreparer(t: String): List<APreparer> = json.decodeFromString(t)
}
