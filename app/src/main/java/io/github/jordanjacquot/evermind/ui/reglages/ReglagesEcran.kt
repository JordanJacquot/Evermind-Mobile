package io.github.jordanjacquot.evermind.ui.reglages

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import io.github.jordanjacquot.evermind.rappels.Notifications
import io.github.jordanjacquot.evermind.rappels.Planificateur
import io.github.jordanjacquot.evermind.rappels.ReglagesRappels
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jordanjacquot.evermind.EvermindApp
import io.github.jordanjacquot.evermind.voix.EtatInstallation
import io.github.jordanjacquot.evermind.voix.Modeles
import io.github.jordanjacquot.evermind.voix.Pack
import io.github.jordanjacquot.evermind.voix.VoixKokoro
import io.github.jordanjacquot.evermind.voix.enMo
import kotlinx.coroutines.launch

/** Les réglages : la voix (l'installer, la choisir), « OK Evermind ». */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReglagesEcran(retour: () -> Unit) {
    val app = LocalContext.current.applicationContext as EvermindApp
    val voix = app.voix
    val rappels = remember { ReglagesRappels(app) }
    var veille by remember { mutableStateOf(rappels.veille) }
    var matin by remember { mutableStateOf(rappels.matin) }
    var heureAvant by remember { mutableStateOf(rappels.heureAvant) }
    var notificationsPermises by remember { mutableStateOf(Notifications.permises(app)) }
    val demande = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notificationsPermises = it }
    val scope = rememberCoroutineScope()
    val etatVoix by voix.installation.etat(Pack.VOIX).collectAsStateWithLifecycle()
    val etatEveil by voix.installation.etat(Pack.EVEIL).collectAsStateWithLifecycle()
    val choisie by voix.voixChoisie.collectAsStateWithLifecycle()
    val eveilActif by voix.eveilActif.collectAsStateWithLifecycle()
    val repondre by voix.repondre.collectAsStateWithLifecycle()
    // (relu à chaque changement d'état de l'installation)
    val voixPrete = etatVoix.let { voix.voixInstallee }
    val eveilPret = etatEveil.let { voix.eveilInstalle }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Réglages") },
                navigationIcon = { IconButton(onClick = retour) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { marges ->
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize().padding(marges).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Text("Rappels", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Text(
                "Les rappels donnés par défaut. Pour un rendez-vous en particulier, tu peux en choisir d'autres dans son " +
                    "formulaire (ou les dire : « rappelle-moi 2 jours avant »).",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!notificationsPermises) {
                Text("Evermind n'a pas le droit d'envoyer des notifications : aucun rappel ne s'affichera.",
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    Button(onClick = { demande.launch(Manifest.permission.POST_NOTIFICATIONS) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Autoriser les notifications")
                    }
                }
            }
            Ligne("La veille à 20 h", veille) { veille = it; rappels.veille = it; Planificateur.reprogrammer(app) }
            Ligne("Le matin même à 8 h", matin) { matin = it; rappels.matin = it; Planificateur.reprogrammer(app) }
            Ligne("1 h avant", heureAvant) { heureAvant = it; rappels.heureAvant = it; Planificateur.reprogrammer(app) }
            Text(
                "Les choses à faire avec une heure sont rappelées à l'heure même.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("La voix", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Text(
                "Pour parler à Evermind comme sur PC : la dictée Whisper et la voix naturelle Kokoro. " +
                    "Téléchargées une seule fois, ensuite tout marche sans Internet et rien ne quitte le téléphone.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Installer(
                titre = "Installer la voix (${enMo(Modeles.taille(Pack.VOIX))})",
                pret = voixPrete,
                pretTexte = "La voix est installée : le micro utilise Whisper, et Evermind te répond.",
                etat = etatVoix,
                lancer = { voix.installation.lancer(Pack.VOIX) { voix.prechauffer() } },
            )
            if (voixPrete) {
                Ligne("Evermind répond à voix haute", repondre) { voix.activerReponse(it) }
                Text("Voix", style = MaterialTheme.typography.labelLarge)
                VoixKokoro.entries.forEach { v ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { voix.choisirVoix(v) },
                    ) {
                        RadioButton(selected = v == choisie, onClick = { voix.choisirVoix(v) })
                        Column(Modifier.weight(1f)) {
                            Text(v.nom)
                            Text(v.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { scope.launch { runCatching { voix.essayer(v) } } }) {
                            Icon(Icons.Default.PlayArrow, "Écouter ${v.nom}")
                        }
                    }
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("« OK Evermind »", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Text(
                "Dis « OK Evermind » quand l'agenda est à l'écran, puis ta demande. Une petite écoute sur le téléphone ne " +
                    "reconnaît que ces mots-là ; tout le reste est oublié aussitôt, rien n'est enregistré.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!voixPrete) {
                Text("Installe d'abord la voix.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Installer(
                    titre = "Installer « OK Evermind » (${enMo(Modeles.taille(Pack.EVEIL))})",
                    pret = eveilPret,
                    pretTexte = null,
                    etat = etatEveil,
                    lancer = { voix.installation.lancer(Pack.EVEIL) { voix.activerEveil(true) } },
                )
                if (eveilPret) Ligne("Écouter « OK Evermind »", eveilActif) { voix.activerEveil(it) }
            }
        }
    }
}

@Composable
private fun Installer(titre: String, pret: Boolean, pretTexte: String?, etat: EtatInstallation, lancer: () -> Unit) {
    when {
        pret -> pretTexte?.let { Text("✓ $it", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium) }
        etat is EtatInstallation.EnCours -> Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Téléchargement : ${etat.quoi}", fontWeight = FontWeight.Medium)
                LinearProgressIndicator(progress = { if (etat.total > 0) etat.fait.toFloat() / etat.total else 0f }, modifier = Modifier.fillMaxWidth())
                Text(
                    "${enMo(etat.fait)} sur ${enMo(etat.total)}. Garde Evermind ouvert ; le Wi-Fi est conseillé.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        else -> {
            if (etat is EtatInstallation.Echec) Text(etat.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Button(onClick = lancer, modifier = Modifier.fillMaxWidth()) { Text(titre) }
        }
    }
}

@Composable
private fun Ligne(texte: String, oui: Boolean, changer: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(texte, modifier = Modifier.weight(1f))
        Switch(checked = oui, onCheckedChange = changer)
    }
}
