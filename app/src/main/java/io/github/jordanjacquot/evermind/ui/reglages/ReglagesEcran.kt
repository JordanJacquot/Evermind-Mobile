package io.github.jordanjacquot.evermind.ui.reglages

import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import io.github.jordanjacquot.evermind.assistant.CreneauxNoms
import io.github.jordanjacquot.evermind.assistant.HorairesDefaut
import io.github.jordanjacquot.evermind.assistant.PostesNoms
import io.github.jordanjacquot.evermind.assistant.lireHeures
import io.github.jordanjacquot.evermind.ui.majuscule
import java.time.LocalTime
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import io.github.jordanjacquot.evermind.ui.theme.BoutonAccent
import io.github.jordanjacquot.evermind.ui.theme.CouleursEvermind
import io.github.jordanjacquot.evermind.ui.theme.ThemeEvermind
import io.github.jordanjacquot.evermind.ui.theme.Themes
import io.github.jordanjacquot.evermind.ui.theme.Verre
import io.github.jordanjacquot.evermind.ui.theme.ev
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
import io.github.jordanjacquot.evermind.assistant.ModeleAssistant
import io.github.jordanjacquot.evermind.ui.theme.traduire
import io.github.jordanjacquot.evermind.voix.EtatInstallation
import io.github.jordanjacquot.evermind.voix.Modeles
import io.github.jordanjacquot.evermind.voix.Pack
import io.github.jordanjacquot.evermind.voix.VoixKokoro
import io.github.jordanjacquot.evermind.voix.enMo
import kotlinx.coroutines.launch

/** Les réglages, par onglets : l'apparence, l'agenda (rappels, horaires de travail), la voix, l'assistant. */
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
    var onglet by rememberSaveable { mutableIntStateOf(0) }

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground,
        topBar = {
            TopAppBar(
                title = { Text("Réglages") },
                navigationIcon = { IconButton(onClick = retour) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
            )
        },
    ) { marges ->
        Column(Modifier.fillMaxSize().padding(marges)) {
        // Par onglets : chaque partie tient sur l'écran, sans tout faire défiler
        PrimaryTabRow(
            selectedTabIndex = onglet,
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            contentColor = MaterialTheme.colorScheme.primary,
            divider = {},
        ) {
            Onglets.forEachIndexed { i, nom ->
                Tab(
                    selected = onglet == i,
                    onClick = { onglet = i },
                    text = { Text(nom, maxLines = 1, softWrap = false, fontWeight = if (onglet == i) FontWeight.Bold else FontWeight.Normal) },
                    selectedContentColor = MaterialTheme.colorScheme.primary,
                    unselectedContentColor = ev.discret,
                )
            }
        }
        key(onglet) {
        Column(
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            if (onglet == 0) Section("Thème", "Les couleurs d'Evermind, les mêmes thèmes que sur PC. Tes rendez-vous et réglages ne changent pas.") {
                val themeChoisi by app.theme.collectAsStateWithLifecycle()
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Themes.chunked(2).forEach { ligne ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            ligne.forEach { t ->
                                CarteTheme(t, t.id == themeChoisi, Modifier.weight(1f)) { app.choisirTheme(t.id) }
                            }
                            if (ligne.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }

            if (onglet == 1) Section("Rappels", "Les rappels donnés par défaut. Pour un rendez-vous en particulier, tu peux en choisir d'autres dans son formulaire (ou les dire : « rappelle-moi 2 jours avant »).") {
                if (!notificationsPermises) {
                    Text("Evermind n'a pas le droit d'envoyer des notifications : aucun rappel ne s'affichera.",
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        BoutonAccent("Autoriser les notifications", { demande.launch(Manifest.permission.POST_NOTIFICATIONS) }, Modifier.padding(vertical = 6.dp))
                    }
                }
                Ligne("La veille à 20 h", veille) { veille = it; rappels.veille = it; Planificateur.reprogrammer(app) }
                Ligne("Le matin même à 8 h", matin) { matin = it; rappels.matin = it; Planificateur.reprogrammer(app) }
                Ligne("1 h avant", heureAvant) { heureAvant = it; rappels.heureAvant = it; Planificateur.reprogrammer(app) }
                Text("Les choses à faire avec une heure sont rappelées à l'heure même.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (onglet == 2) Section("La voix", "Pour parler à Evermind comme sur PC : la dictée Whisper et la voix naturelle Kokoro. Téléchargées une seule fois, ensuite tout marche sans Internet et rien ne quitte le téléphone.") {
                Installer(
                    titre = "Installer la voix (${enMo(Modeles.taille(Pack.VOIX))})",
                    pret = voixPrete,
                    pretTexte = "La voix est installée : le micro utilise Whisper, et Evermind te répond.",
                    etat = etatVoix,
                    lancer = { voix.installation.lancer(Pack.VOIX) { voix.prechauffer() } },
                )
                if (voixPrete) {
                    Ligne("Evermind répond à voix haute", repondre) { voix.activerReponse(it) }
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
                                Icon(Icons.Default.PlayArrow, "Écouter ${v.nom}", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }

            if (onglet == 3) SectionAssistant()

            if (onglet == 1) SectionHoraires()

            if (onglet == 2) Section("« OK Evermind »", "Dis « OK Evermind » quand l'agenda est à l'écran, puis ta demande. Une petite écoute sur le téléphone ne reconnaît que ces mots-là ; tout le reste est oublié aussitôt, rien n'est enregistré.") {
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
            Spacer(Modifier.height(24.dp))
        }
        }
        }
    }
}

private val Onglets = listOf("Apparence", "Agenda", "Voix", "Assistant")

/**
 * Les heures de chaque poste de travail, pour « je suis du matin lundi et mardi » (comme le réglage « Horaires » du
 * PC) : on touche un poste, on choisit le début puis la fin.
 */
@Composable
private fun SectionHoraires() {
    val app = LocalContext.current.applicationContext as EvermindApp
    val reglages = app.cerveau.horaires
    var heures by remember { mutableStateOf(reglages.tous()) }
    var modifie by remember { mutableStateOf<String?>(null) }
    var debut by remember { mutableStateOf<LocalTime?>(null) }
    Section(
        "Horaires de travail",
        "Pour « je suis du matin lundi et mardi » ou « je travaille de nuit toute la semaine prochaine » : les heures de " +
            "chacun de tes postes. Tu peux aussi le dire : « mon poste du matin, c'est de 7 h à 15 h ».",
    ) {
        HorairesDefaut.keys.forEach { c ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { modifie = c; debut = null }.padding(vertical = 8.dp),
            ) {
                Text(majuscule(CreneauxNoms.getValue(c)), modifier = Modifier.weight(1f))
                Text(heures.getValue(c).replace(" à ", " – "), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
            }
        }
        if (heures != HorairesDefaut) {
            TextButton(onClick = { reglages.remettre(); heures = reglages.tous() }) { Text("Remettre les heures habituelles") }
        }
    }
    val c = modifie ?: return
    val actuelles = lireHeures(heures.getValue(c)) ?: (LocalTime.of(8, 0) to LocalTime.of(17, 0))
    val d = debut
    if (d == null) {
        ChoixHeure("Poste ${PostesNoms[c]} : début", actuelles.first, fermer = { modifie = null }) { debut = it }
    } else {
        ChoixHeure("Poste ${PostesNoms[c]} : fin", actuelles.second, fermer = { modifie = null; debut = null }) { f ->
            if (f != d) { reglages.changer(c, d, f); heures = reglages.tous() }
            modifie = null; debut = null
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChoixHeure(titre: String, initiale: LocalTime, fermer: () -> Unit, choisir: (LocalTime) -> Unit) {
    val etat = rememberTimePickerState(initialHour = initiale.hour, initialMinute = initiale.minute, is24Hour = true)
    AlertDialog(
        onDismissRequest = fermer,
        title = { Text(titre) },
        text = { TimePicker(state = etat) },
        confirmButton = { TextButton(onClick = { choisir(LocalTime.of(etat.hour, etat.minute)) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = fermer) { Text("Annuler") } },
    )
}

/** Une section des réglages : un panneau de verre, son titre et ce qu'elle fait. */
@Composable
private fun Section(titre: String, description: String, contenu: @Composable ColumnScope.() -> Unit) {
    Verre(modifier = Modifier.fillMaxWidth()) {
        Text(titre, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = ev.texte)
        Text(description, style = MaterialTheme.typography.bodySmall, color = ev.discret, modifier = Modifier.padding(top = 4.dp, bottom = 10.dp))
        contenu()
    }
}

/** Un thème, avec son petit aperçu comme sur PC : le logo, un bouton, quelques cases du calendrier et une carte. */
@Composable
private fun CarteTheme(t: ThemeEvermind, choisi: Boolean, modifier: Modifier, choisir: () -> Unit) {
    val ce = CouleursEvermind(t)
    val forme = RoundedCornerShape(16.dp)
    Column(
        modifier
            .clip(forme)
            .background(Color.White.copy(alpha = if (choisi) 0.10f else 0.04f))
            .border(if (choisi) 2.dp else 1.dp, if (choisi) Brush.linearGradient(listOf(ce.p, ce.s)) else Brush.linearGradient(listOf(Color.White.copy(alpha = 0.15f), Color.White.copy(alpha = 0.05f))), forme)
            .clickable(onClick = choisir)
            .padding(8.dp),
    ) {
        // L'aperçu
        Box(
            Modifier.fillMaxWidth().height(70.dp).clip(RoundedCornerShape(10.dp))
                .background(Brush.linearGradient(listOf(ce.fondHaut, ce.fondBas)))
                .background(Brush.radialGradient(listOf(ce.p.copy(alpha = 0.35f), Color.Transparent), radius = 160f, center = androidx.compose.ui.geometry.Offset(60f, 20f)))
                .background(Brush.radialGradient(listOf(ce.t.copy(alpha = 0.35f), Color.Transparent), radius = 180f, center = androidx.compose.ui.geometry.Offset(400f, 200f)))
                .padding(8.dp),
        ) {
            Box(Modifier.size(18.dp).border(2.dp, Brush.sweepGradient(listOf(ce.p, ce.s, ce.t, ce.p)), CircleShape))
            Box(Modifier.align(Alignment.TopEnd).size(width = 40.dp, height = 8.dp).clip(CircleShape).background(Brush.horizontalGradient(listOf(ce.p, ce.s))))
            Row(Modifier.align(Alignment.BottomStart), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                listOf(ce.p, Color.Transparent, ce.t, ce.s, Color.Transparent).forEach { barre ->
                    Column(Modifier.size(14.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = 0.08f)), verticalArrangement = Arrangement.Bottom) {
                        Box(Modifier.fillMaxWidth().height(3.dp).background(barre))
                    }
                }
            }
            Box(Modifier.align(Alignment.BottomEnd).size(26.dp).clip(RoundedCornerShape(6.dp)).background(ce.t.copy(alpha = 0.25f)).border(1.dp, ce.t.copy(alpha = 0.6f), RoundedCornerShape(6.dp)))
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
            Text(t.nom, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium, fontFamily = t.police)
            if (choisi) Text("  ✓", color = ce.p, fontWeight = FontWeight.Bold)
        }
        Text(t.description, style = MaterialTheme.typography.labelSmall, color = ev.discret, maxLines = 3)
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
            BoutonAccent(titre, lancer, Modifier.padding(top = 4.dp))
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

/**
 * L'assistant : celui que la mémoire du téléphone permet (Evermind ne doit jamais prendre toute la mémoire), ou un plus
 * léger au choix. Téléchargé une fois, comme la voix.
 */
@Composable
private fun SectionAssistant() {
    val app = LocalContext.current.applicationContext as EvermindApp
    val cerveau = app.cerveau
    val choisi by cerveau.choisi.collectAsStateWithLifecycle()
    val actif by cerveau.actif.collectAsStateWithLifecycle()
    val conseille = cerveau.conseille
    val ram = "%.1f".format(cerveau.ramGo).replace('.', ',')
    // Le modèle dont on parle : celui installé, sinon celui choisi dans la liste, sinon le conseillé
    var vise by remember { mutableStateOf(choisi ?: conseille) }
    val etats = ModeleAssistant.entries.associateWith { m -> app.voix.installation.etat(m.pack).collectAsStateWithLifecycle().value }
    val moteurOk = remember { io.github.jordanjacquot.evermind.assistant.MoteurLlama.disponible(app) }

    Section(
        "L'assistant",
        "Parle-lui librement : « décale le dentiste à jeudi », « supprime les raids de novembre », « qu'est-ce que j'ai " +
            "demain ? ». Evermind comprend seul ces demandes, tout de suite et sur tous les téléphones. En plus, une IA sur " +
            "le téléphone (sans Internet) peut s'occuper des phrases qu'il ne reconnaît pas.",
    ) {
        Text("Ce téléphone a $ram Go de mémoire.", style = MaterialTheme.typography.bodySmall, color = ev.discret)
        when {
            !moteurOk -> Text("Ce téléphone ne peut pas faire tourner l'IA (processeur 32 bits). Evermind comprend quand même tes demandes avec ses règles.",
                style = MaterialTheme.typography.bodyMedium)
            conseille == null -> Text("Il n'a pas assez de mémoire pour une IA sans ralentir le reste. Evermind comprend quand même tes demandes avec ses règles, la voix et les rappels marchent normalement.",
                style = MaterialTheme.typography.bodyMedium)
            else -> {
                // Les assistants possibles : ceux que la mémoire du téléphone permet, le conseillé en premier
                ModeleAssistant.entries.filter { it.ramConseillee <= cerveau.ramGo }.forEach { m ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { vise = m }) {
                        RadioButton(selected = vise == m, onClick = { vise = m })
                        Column(Modifier.weight(1f)) {
                            Text(m.nom + (if (m == conseille) "  (conseillé)" else "") + "  ·  " + enMo(m.fichier.taille), fontWeight = FontWeight.Medium)
                            Text(m.description, style = MaterialTheme.typography.bodySmall, color = ev.discret)
                        }
                    }
                }
                val m = vise ?: conseille
                val installe = Modeles.installe(app, m.pack)
                if (installe && choisi == m) {
                    Text("✓ ${m.nom} est installé.", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                    Ligne("Utiliser l'IA pour les phrases inhabituelles", actif) { cerveau.activer(it) }
                } else if (installe) {
                    BoutonAccent("Utiliser ${m.nom}", { cerveau.choisir(m) }, Modifier.padding(top = 4.dp))
                } else {
                    Installer(
                        titre = "Installer l'IA ${m.nom} (${enMo(m.fichier.taille)})",
                        pret = false,
                        pretTexte = null,
                        etat = etats.getValue(m),
                        lancer = {
                            app.voix.installation.lancer(m.pack) {
                                // Un seul assistant sur le téléphone : l'ancien laisse sa place
                                ModeleAssistant.entries.filter { it != m }.forEach { autre -> Modeles.fichier(app, autre.fichier.vers).delete() }
                                cerveau.choisir(m)
                            }
                        },
                    )
                }
            }
        }
    }
}
