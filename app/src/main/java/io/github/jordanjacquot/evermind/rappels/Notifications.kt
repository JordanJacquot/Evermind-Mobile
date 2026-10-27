package io.github.jordanjacquot.evermind.rappels

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.jordanjacquot.evermind.MainActivity
import io.github.jordanjacquot.evermind.R
import io.github.jordanjacquot.evermind.donnees.RendezVous
import java.time.LocalDateTime

/**
 * Les notifications de rappel, aux couleurs d'Evermind : l'icône et la couleur de la sorte, le nom, l'heure,
 * « Rappel · dans 1 h », et deux boutons : « Voir » (la fiche) et « Dans 10 min ».
 */
object Notifications {
    private const val CANAL = "rappels"
    const val EXTRA_FICHE = "fiche"
    /** La fois rappelée (pour ouvrir la fiche sur elle et retirer la notification quand on touche « Voir »). */
    const val EXTRA_FOIS = "fois"

    fun creerCanal(contexte: Context) {
        val canal = NotificationChannel(CANAL, "Rappels", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Les rappels de tes rendez-vous (la veille, le matin même, avant...)"
        }
        contexte.getSystemService(NotificationManager::class.java).createNotificationChannel(canal)
    }

    /**
     * Les rappels peuvent-ils s'afficher ? Le droit d'Android 13, mais aussi, à toutes les versions, les notifications
     * d'Evermind ou celles des rappels coupées dans les réglages du téléphone.
     */
    fun permises(contexte: Context): Boolean {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(contexte, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        val gestion = NotificationManagerCompat.from(contexte)
        if (!gestion.areNotificationsEnabled()) return false
        return gestion.getNotificationChannel(CANAL)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    /** La page des notifications d'Evermind dans les réglages du téléphone (ou, à défaut, celle de l'appli). */
    fun ouvrirReglages(contexte: Context) {
        val notifications = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, contexte.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val appli = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", contexte.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { contexte.startActivity(notifications) }.onFailure { runCatching { contexte.startActivity(appli) } }
    }

    private fun numero(id: String, fois: String) = (id + fois).hashCode()

    /** [enRetard] : le moment du rappel est passé depuis longtemps (téléphone éteint ou pas encore déverrouillé). */
    fun rappel(contexte: Context, rdv: RendezVous, fois: LocalDateTime, maintenant: LocalDateTime, enRetard: Boolean = false) {
        if (!permises(contexte)) return
        creerCanal(contexte)
        val couleur = rdv.sorte.couleur.toInt()
        val dans = texteDans(fois, maintenant, rdv.journee)
        val heure = heureAlerte(rdv, fois, maintenant)
        val sujet = if (enRetard) "Rappel en retard" else "Rappel"
        val etiquette = "$sujet  ·  $dans"

        // (repliée, Android ne laisse la place qu'à deux lignes : l'heure rejoint l'étiquette ; dépliée, elle a sa ligne)
        fun vue(layout: Int, repliee: Boolean) = RemoteViews(contexte.packageName, layout).apply {
            setTextViewText(R.id.icone, rdv.sorte.icone)
            setTextViewText(R.id.etiquette, if (repliee) "$heure  ·  $dans" else etiquette)
            setTextColor(R.id.etiquette, couleur)
            setTextViewText(R.id.titre, rdv.titre)
            setTextViewText(R.id.heure, heure)
            setViewVisibility(R.id.heure, if (repliee) android.view.View.GONE else android.view.View.VISIBLE)
            setInt(R.id.barre, "setBackgroundColor", couleur)
        }

        val ouvrir = PendingIntent.getActivity(
            contexte, numero(rdv.id, fois.toString()),
            Intent(contexte, MainActivity::class.java).putExtra(EXTRA_FICHE, rdv.id).putExtra(EXTRA_FOIS, fois.toString()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val plusTard = PendingIntent.getBroadcast(
            contexte, numero(rdv.id, fois.toString()),
            Intent(contexte, RecepteurRappels::class.java).setAction(Planificateur.ACTION_PLUS_TARD)
                .putExtra(Planificateur.EXTRA_ID, rdv.id).putExtra(Planificateur.EXTRA_FOIS, fois.toString()),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(contexte, CANAL)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(couleur)
            .setContentTitle(rdv.titre)
            .setContentText("$heure · $dans")
            .setSubText(sujet)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(vue(R.layout.notification_rappel, repliee = true))
            .setCustomBigContentView(vue(R.layout.notification_rappel, repliee = false))
            .setCustomHeadsUpContentView(vue(R.layout.notification_rappel, repliee = true))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(ouvrir)
            .setAutoCancel(true)
            .addAction(0, "Voir", ouvrir)
            .addAction(0, "Dans 10 min", plusTard)
            .build()
        runCatching { NotificationManagerCompat.from(contexte).notify(numero(rdv.id, fois.toString()), n) }
    }

    fun retirer(contexte: Context, id: String, fois: String) =
        NotificationManagerCompat.from(contexte).cancel(numero(id, fois))
}

/** Ce que fait le bouton « Autoriser les notifications ». */
enum class Autoriser { DEMANDER, REGLAGES }

/**
 * Après deux refus (ou une fenêtre fermée sans répondre), Android n'affiche plus sa demande : le bouton ne ferait plus
 * rien. On envoie alors vers les réglages du téléphone. Avant Android 13, il n'y a pas de demande : les réglages aussi.
 * [dejaDemandee] : Evermind a déjà posé la question une fois ; [androidExplique] : shouldShowRequestPermissionRationale.
 */
fun commentAutoriser(sdk: Int, dejaDemandee: Boolean, androidExplique: Boolean): Autoriser =
    if (sdk >= 33 && (!dejaDemandee || androidExplique)) Autoriser.DEMANDER else Autoriser.REGLAGES

/** Une réponse « refusé » arrivée si vite qu'Android n'a pas pu montrer sa fenêtre (moins d'un tiers de seconde). */
fun refusSansFenetre(dureeMs: Long) = dureeMs in 0 until 350
