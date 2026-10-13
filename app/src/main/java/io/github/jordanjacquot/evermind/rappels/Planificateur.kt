package io.github.jordanjacquot.evermind.rappels

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import io.github.jordanjacquot.evermind.EvermindApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Donne les rappels à l'heure, même Evermind fermé : une seule alarme Android, réglée sur le prochain rappel.
 * Quand elle sonne, les rappels dus sont donnés (chacun une seule fois), puis l'alarme suivante est réglée.
 * À refaire à chaque changement de l'agenda ou des réglages, au démarrage du téléphone et au changement d'heure.
 */
object Planificateur {
    private const val ACTION_RAPPELS = "io.github.jordanjacquot.evermind.RAPPELS"
    const val ACTION_PLUS_TARD = "io.github.jordanjacquot.evermind.PLUS_TARD"
    const val ACTION_REPORTE = "io.github.jordanjacquot.evermind.REPORTE"
    const val EXTRA_ID = "id"
    const val EXTRA_FOIS = "fois"

    private val verrou = Mutex()

    /** Donne les rappels dus maintenant, puis règle l'alarme du suivant. */
    suspend fun passer(contexte: Context) = verrou.withLock {
        val app = contexte.applicationContext as EvermindApp
        val reglages = ReglagesRappels(contexte)
        val prefs = contexte.getSharedPreferences("rappels-faits", Context.MODE_PRIVATE)
        val maintenant = LocalDateTime.now()
        val tous = app.depot.liste()

        // Les rappels dus depuis le dernier passage (au plus 15 minutes en arrière : pas de vieux rappels
        // après une longue coupure), une seule fois chacun ; plusieurs du même rendez-vous : un seul
        val dernier = prefs.getString("dernier", null)?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
        val depuis = listOfNotNull(dernier, maintenant.minusMinutes(15)).max()
        val faits = prefs.getStringSet("cles", emptySet())!!.toMutableSet()
        rappelsEntre(tous, depuis, maintenant, reglages)
            .filter { it.cle !in faits }
            .groupBy { it.rendezVous.id to it.fois }
            .values.forEach { dus ->
                dus.forEach { faits += it.cle }
                val r = dus.maxBy { it.moment }
                Notifications.rappel(contexte, r.rendezVous, r.fois, maintenant)
            }
        // (on ne garde que les clés récentes)
        val limite = maintenant.minusDays(9).toString()
        faits.removeAll { cle -> cle.split('|').getOrNull(1)?.let { it < limite } ?: true }
        prefs.edit().putString("dernier", maintenant.toString()).putStringSet("cles", faits).apply()

        // L'alarme du prochain rappel (on regarde 40 jours devant ; au-delà, l'alarme sert juste à revenir voir)
        val prochain = rappelsEntre(tous, maintenant, maintenant.plusDays(40), reglages).firstOrNull()?.moment
            ?: maintenant.plusDays(30)
        regler(contexte, prochain, intention(contexte, ACTION_RAPPELS), 0)
    }

    /** « Dans 10 min » : le même rappel revient plus tard. */
    fun plusTard(contexte: Context, id: String, fois: String, minutes: Long = 10) {
        val i = intention(contexte, ACTION_REPORTE).putExtra(EXTRA_ID, id).putExtra(EXTRA_FOIS, fois)
        regler(contexte, LocalDateTime.now().plusMinutes(minutes), i, (id + fois).hashCode())
    }

    /** Refaire le planning en arrière-plan (après un changement). */
    fun reprogrammer(contexte: Context) {
        val app = contexte.applicationContext as EvermindApp
        app.portee.launch(Dispatchers.IO) { runCatching { passer(contexte) } }
    }

    private fun intention(contexte: Context, action: String) =
        Intent(contexte, RecepteurRappels::class.java).setAction(action)

    private fun regler(contexte: Context, quand: LocalDateTime, intent: Intent, code: Int) {
        val alarmes = contexte.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(contexte, code, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val t = quand.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        // À l'heure exacte quand Android le permet (Android 13 et après : toujours, pour un agenda) ;
        // sinon à quelques minutes près
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmes.canScheduleExactAlarms()) {
            alarmes.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi)
        } else {
            alarmes.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi)
        }
    }
}

/** L'alarme des rappels, et les boutons des notifications. */
class RecepteurRappels : BroadcastReceiver() {
    override fun onReceive(contexte: Context, intent: Intent) {
        val fin = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    Planificateur.ACTION_PLUS_TARD -> {
                        val id = intent.getStringExtra(Planificateur.EXTRA_ID) ?: return@launch
                        val fois = intent.getStringExtra(Planificateur.EXTRA_FOIS) ?: return@launch
                        Notifications.retirer(contexte, id, fois)
                        Planificateur.plusTard(contexte, id, fois)
                    }
                    Planificateur.ACTION_REPORTE -> {
                        val app = contexte.applicationContext as EvermindApp
                        val id = intent.getStringExtra(Planificateur.EXTRA_ID) ?: return@launch
                        val fois = intent.getStringExtra(Planificateur.EXTRA_FOIS)?.let { LocalDateTime.parse(it) } ?: return@launch
                        app.depot.liste().firstOrNull { it.id == id }?.let { Notifications.rappel(contexte, it, fois, LocalDateTime.now()) }
                    }
                    else -> Planificateur.passer(contexte)
                }
            } finally {
                fin.finish()
            }
        }
    }
}

/** Le téléphone redémarre, l'heure change, Evermind est mis à jour : les alarmes sont à refaire. */
class RecepteurDemarrage : BroadcastReceiver() {
    override fun onReceive(contexte: Context, intent: Intent) {
        val fin = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { Planificateur.passer(contexte) } finally { fin.finish() }
        }
    }
}
