package io.github.jordanjacquot.evermind.donnees

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.time.LocalDateTime

/** La base de données du téléphone. Changer RendezVous demande d'augmenter la version et d'écrire une migration. */
@Database(entities = [RendezVous::class], version = 2)
@TypeConverters(Convertisseurs::class)
abstract class BaseEvermind : RoomDatabase() {
    abstract fun rendezVous(): RendezVousDao

    companion object {
        fun ouvrir(context: Context): BaseEvermind =
            Room.databaseBuilder(context, BaseEvermind::class.java, "evermind.db")
                .addMigrations(Migrations.UN_A_DEUX)
                .build()
    }
}

/** Ce dont une migration a besoin de la base : Room sur le téléphone, une base SQLite ordinaire dans les tests sur PC. */
interface BaseSql {
    fun executer(sql: String, args: Array<Any?> = emptyArray())
    fun lignes(sql: String): List<List<String?>>
}

/** Les migrations de la base : l'agenda reste quand l'appli est mise à jour. */
object Migrations {
    private val conv = Convertisseurs()

    /**
     * 1 vers 2 : « faitPour », les fois faites d'une chose qui revient. Une chose qui revient déjà cochée « fait »
     * (muette pour toujours avant cette version) devient « faite pour sa dernière fois passée » (Fait.kt).
     */
    fun de1a2(base: BaseSql, maintenant: LocalDateTime = LocalDateTime.now()) {
        base.executer("ALTER TABLE `rendez_vous` ADD COLUMN `faitPour` TEXT NOT NULL DEFAULT '[]'")
        for ((id, quand, repetition) in base.lignes("SELECT `id`, `quand`, `repetition` FROM `rendez_vous` WHERE `fait` = 1 AND `repetition` IS NOT NULL")) {
            // (une date illisible : le rendez-vous est laissé tel quel, rien n'est perdu)
            val r = runCatching {
                RendezVous(id = id!!, titre = "", quand = conv.texteVersDate(quand!!), repetition = repetition, fait = true).sansFaitAncien(maintenant)
            }.getOrNull() ?: continue
            base.executer("UPDATE `rendez_vous` SET `fait` = 0, `faitPour` = ? WHERE `id` = ?", arrayOf(conv.textesVersJson(r.faitPour), id))
        }
    }

    val UN_A_DEUX = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) = de1a2(object : BaseSql {
            override fun executer(sql: String, args: Array<Any?>) = if (args.isEmpty()) db.execSQL(sql) else db.execSQL(sql, args)
            override fun lignes(sql: String): List<List<String?>> = db.query(sql).use { c ->
                buildList { while (c.moveToNext()) add((0 until c.columnCount).map { if (c.isNull(it)) null else c.getString(it) }) }
            }
        })
    }
}
