package io.github.jordanjacquot.evermind.donnees

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/** La base de données du téléphone. Changer RendezVous demande d'augmenter la version et d'écrire une migration. */
@Database(entities = [RendezVous::class], version = 1)
@TypeConverters(Convertisseurs::class)
abstract class BaseEvermind : RoomDatabase() {
    abstract fun rendezVous(): RendezVousDao

    companion object {
        fun ouvrir(context: Context): BaseEvermind =
            Room.databaseBuilder(context, BaseEvermind::class.java, "evermind.db").build()
    }
}
