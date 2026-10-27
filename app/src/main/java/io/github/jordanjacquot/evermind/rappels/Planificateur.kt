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
import java.time.Instant
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
    private const val CLE_REPORTS = "reportes"

    private val verrou = Mutex()

    /** Donne les rappels dus maintenant (et ceux manqués d'un rendez-vous pas encore commencé), puis règle l'alarme du suivant. */
    suspend fun passer(contexte: Context) = verrou.withLock {
        val app = contexte.applicationContext as EvermindApp
        val reglages = ReglagesRappels(contexte)
        val prefs = contexte.getSharedPreferences("rappels-faits", Context.MODE_PRIVATE)
        val maintenant = LocalDateTime.now()
        val tous = app.depot.liste()

        // Les rappels dus depuis le dernier passage, une seule fois chacun ; plusieurs du même rendez-vous : un seul
        val dernier = prefs.getString("dernier", null)?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
        val faits = prefs.getStringSet("cles", emptySet())!!.toMutableSet()
        rappelsADonner(tous, dernier, maintenant, reglages, faits).forEach { d ->
            faits += d.cles
            Notifications.rappel(contexte, d.rappel.rendezVous, d.rappel.fois, maintenant, d.enRetard)
        }
        // (on ne garde que les clés récentes)
        val limite = maintenant.minusDays(9).toString()
        faits.removeAll { cle -> cle.split('|').getOrNull(1)?.let { it < limite } ?: true }

        // Les « Dans 10 min » gardés : l'heure est passée (téléphone redémarré entre-temps), le rappel revient tout de
        // suite ; sinon son alarme est remise (elle disparaît quand le téléphone redémarre)
        val (dus, aVenir) = trierReports(prefs.getStringSet(CLE_REPORTS, emptySet())!!, Instant.now())
        dus.forEach { rp ->
            val fois = runCatching { LocalDateTime.parse(rp.fois) }.getOrNull() ?: return@forEach
            tous.firstOrNull { it.id == rp.id }?.let { Notifications.rappel(contexte, it, fois, maintenant) }
        }
        aVenir.forEach { reglerReport(contexte, it) }
        prefs.edit().putString("dernier", maintenant.toString()).putStringSet("cles", faits)
            .putStringSet(CLE_REPORTS, aVenir.map { it.enTexte() }.toSet()).commit()

        // L'alarme du prochain rappel (on regarde 40 jours devant ; au-delà, l'alarme sert juste à revenir voir)
        val prochain = rappelsEntre(tous, maintenant, maintenant.plusDays(40), reglages).firstOrNull()?.moment
            ?: maintenant.plusDays(30)
        regler(contexte, prochain.atZone(ZoneId.systemDefault()).toInstant(), intention(contexte, ACTION_RAPPELS), 0)
    }

    /**
     * « Dans 10 min » : le même rappel revient plus tard. Compté en temps réel (pas en heure du téléphone : la nuit du
     * changement d'heure, il revient bien 10 minutes après), et gardé pour revenir même après un redémarrage.
     */
    suspend fun plusTard(contexte: Context, id: String, fois: String, minutes: Long = 10) = verrou.withLock {
        val rp = Report(id, fois, Instant.now().plusSeconds(minutes * 60))
        val prefs = contexte.getSharedPreferences("rappels-faits", Context.MODE_PRIVATE)
        val gardes = prefs.getStringSet(CLE_REPORTS, emptySet())!!.filter { Report.lire(it)?.let { r -> r.id == id && r.fois == fois } != true }
        prefs.edit().putStringSet(CLE_REPORTS, (gardes + rp.enTexte()).toSet()).commit()
        reglerReport(contexte, rp)
    }

    /** L'alarme d'un « Dans 10 min » a sonné : il n'est plus à garder. */
    suspend fun reportDonne(contexte: Context, id: String, fois: String) = verrou.withLock {
        val prefs = contexte.getSharedPreferences("rappels-faits", Context.MODE_PRIVATE)
        val gardes = prefs.getStringSet(CLE_REPORTS, emptySet())!!.filter { Report.lire(it)?.let { r -> r.id == id && r.fois == fois } != true }
        prefs.edit().putStringSet(CLE_REPORTS, gardes.toSet()).commit()
    }

    private fun reglerReport(contexte: Context, rp: Report) {
        val i = intention(contexte, ACTION_REPORTE).putExtra(EXTRA_ID, rp.id).putExtra(EXTRA_FOIS, rp.fois)
        regler(contexte, rp.quand, i, (rp.id + rp.fois).hashCode())
    }

    /**
     * Juste après une restauration (nouveau téléphone, sauvegarde Google) : Evermind n'est pas encore lancé, aucune
     * alarme n'est posée. Une alarme dans une minute le réveille, et il pose alors celles de tous les rappels.
     */
    fun revenirBientot(contexte: Context) =
        regler(contexte, Instant.now().plusSeconds(60), intention(contexte, ACTION_RAPPELS), 0)

    /** Refaire le planning en arrière-plan (après un changement). */
    fun reprogrammer(contexte: Context) {
        val app = contexte.applicationContext as EvermindApp
        app.portee.launch(Dispatchers.IO) { runCatching { passer(contexte) } }
    }

    private fun intention(contexte: Context, action: String) =
        Intent(contexte, RecepteurRappels::class.java).setAction(action)

    private fun regler(contexte: Context, quand: Instant, intent: Intent, code: Int) {
        val alarmes = contexte.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(contexte, code, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val t = quand.toEpochMilli()
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
                        val texte = intent.getStringExtra(Planificateur.EXTRA_FOIS) ?: return@launch
                        Planificateur.reportDonne(contexte, id, texte)
                        val fois = runCatching { LocalDateTime.parse(texte) }.getOrNull() ?: return@launch
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

/**
 * Le téléphone redémarre, l'heure change, Evermind est mis à jour, Android rend le droit aux alarmes à l'heure
 * exacte : les alarmes sont à refaire.
 */
class RecepteurDemarrage : BroadcastReceiver() {
    override fun onReceive(contexte: Context, intent: Intent) {
        val fin = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { Planificateur.passer(contexte) } finally { fin.finish() }
        }
    }
}
