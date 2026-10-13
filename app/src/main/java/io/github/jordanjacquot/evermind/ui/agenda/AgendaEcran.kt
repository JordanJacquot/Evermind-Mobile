package io.github.jordanjacquot.evermind.ui.agenda

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.jordanjacquot.evermind.EvermindApp
import io.github.jordanjacquot.evermind.comprendre.Manque
import io.github.jordanjacquot.evermind.comprendre.lirePhrase
import io.github.jordanjacquot.evermind.comprendre.message
import io.github.jordanjacquot.evermind.comprendre.versRendezVous
import io.github.jordanjacquot.evermind.donnees.Fois
import io.github.jordanjacquot.evermind.donnees.laRepetition
import io.github.jordanjacquot.evermind.ui.enTexte
import io.github.jordanjacquot.evermind.ui.majuscule
import io.github.jordanjacquot.evermind.ui.quandEnTexte
import io.github.jordanjacquot.evermind.ui.theme.teinte
import io.github.jordanjacquot.evermind.voix.Dictee
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth

/** L'écran principal : le calendrier du mois, puis « À venir » ou les rendez-vous du jour choisi. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgendaEcran(ouvrirFiche: (String) -> Unit, ajouter: (LocalDate?) -> Unit, completer: (String) -> Unit, reglages: () -> Unit) {
    val contexte = LocalContext.current
    val app = contexte.applicationContext as EvermindApp
    val vm = viewModel { AgendaViewModel(app) }
    val etatVoix by vm.conversation.etat.collectAsStateWithLifecycle()
    val etat by vm.etat.collectAsStateWithLifecycle()
    val messages = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    var phrase by rememberSaveable { mutableStateOf("") }
    var manque by remember { mutableStateOf<Manque?>(null) }
    val dictee = remember { Dictee(contexte) }
    var ecoute by remember { mutableStateOf(false) }
    var alerte by remember { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) { onDispose { dictee.arreter() } }
    // Les rappels ont besoin des notifications : demandé une fois, au premier lancement
    val demandeNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        val prefs = contexte.getSharedPreferences("rappels", android.content.Context.MODE_PRIVATE)
        if (android.os.Build.VERSION.SDK_INT >= 33 && !prefs.getBoolean("notifications-demandees", false)) {
            prefs.edit().putBoolean("notifications-demandees", true).apply()
            demandeNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    // « OK Evermind » écoute seulement quand l'agenda est à l'écran
    val cycle = LocalLifecycleOwner.current
    DisposableEffect(cycle) {
        val observateur = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) vm.conversation.ecranVisible(true)
            if (e == Lifecycle.Event.ON_PAUSE) vm.conversation.ecranVisible(false)
        }
        cycle.lifecycle.addObserver(observateur)
        onDispose { cycle.lifecycle.removeObserver(observateur); vm.conversation.ecranVisible(false) }
    }

    fun montrer(r: Ajout) {
        when (r) {
            is Ajout.Incomplet -> manque = r.manque
            is Ajout.Fait -> {
                phrase = ""
                manque = null
                focus.clearFocus()
                val premier = r.rendezVous.first()
                val quoi = if (r.rendezVous.size > 1) "${r.rendezVous.size} rendez-vous" else "${premier.sorte.icone} ${premier.titre}"
                scope.launch {
                    messages.currentSnackbarData?.dismiss()
                    val choix = messages.showSnackbar("Ajouté : $quoi, ${premier.quandEnTexte()}", "Annuler", duration = SnackbarDuration.Long)
                    if (choix == SnackbarResult.ActionPerformed) vm.annuler(r.rendezVous)
                }
            }
        }
    }

    fun envoyer() {
        if (phrase.isBlank()) return
        montrer(vm.ajouter(phrase))
    }

    // Ce qui a été dit à voix haute s'affiche comme si on l'avait écrit
    LaunchedEffect(vm) {
        vm.conversation.dits.collect { d ->
            phrase = d.texte
            montrer(d.ajout)
        }
    }

    // Parler : la phrase s'écrit pendant qu'on parle, puis le rendez-vous est créé tout seul (comme sur PC)
    fun ecouter() {
        alerte = null
        manque = null
        focus.clearFocus()
        ecoute = true
        dictee.ecouter(
            partiel = { phrase = it },
            fini = { phrase = it; envoyer() },
            erreur = { alerte = it },
            fin = { ecoute = false },
        )
    }
    val demandeMicro = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { accorde ->
        if (accorde) { if (app.voix.voixInstallee) vm.conversation.parler() else ecouter() } else alerte = "Sans le micro, je ne peux pas t'écouter. Tu peux l'autoriser dans les réglages d'Evermind."
    }
    fun micro() {
        if (ecoute) { dictee.arreter(); ecoute = false; return }
        if (ContextCompat.checkSelfPermission(contexte, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) ecouter()
        else demandeMicro.launch(Manifest.permission.RECORD_AUDIO)
    }
    // Avec la voix installée : Whisper, comme sur PC ; sinon la dictée du téléphone
    fun parler() {
        if (!app.voix.voixInstallee) { micro(); return }
        if (ContextCompat.checkSelfPermission(contexte, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            alerte = null; manque = null; phrase = ""; focus.clearFocus()
            vm.conversation.parler()
        } else demandeMicro.launch(Manifest.permission.RECORD_AUDIO)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Evermind", fontWeight = FontWeight.Bold) },
                actions = { IconButton(onClick = reglages) { Icon(Icons.Default.Settings, "Réglages") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        snackbarHost = { SnackbarHost(messages) },
        floatingActionButton = {
            FloatingActionButton(onClick = { ajouter(etat.jourChoisi) }) {
                Icon(Icons.Default.Add, contentDescription = "Ajouter un rendez-vous")
            }
        },
    ) { marges ->
        LazyColumn(Modifier.fillMaxSize().padding(marges).padding(horizontal = 16.dp)) {
            item {
                Saisie(
                    phrase = phrase,
                    manque = manque,
                    ecoute = ecoute || etatVoix == EtatVoix.ECOUTE || etatVoix == EtatVoix.COMPREND,
                    invite = etatVoix.invite,
                    alerte = alerte,
                    changer = { phrase = it; manque = null; alerte = null },
                    envoyer = ::envoyer,
                    micro = ::parler,
                    completer = { completer(phrase); phrase = ""; manque = null },
                )
            }
            item {
                Calendrier(
                    mois = etat.mois,
                    jourChoisi = etat.jourChoisi,
                    parJour = etat.parJour,
                    precedent = vm::moisPrecedent,
                    suivant = vm::moisSuivant,
                    choisir = vm::choisir,
                )
            }
            item {
                Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = etat.jourChoisi?.let { majuscule(it.enTexte()) } ?: "À venir",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    if (etat.jourChoisi != null) TextButton(onClick = vm::aVenir) { Text("À venir") }
                }
            }
            if (etat.liste.isEmpty()) {
                item {
                    Text(
                        text = if (etat.jourChoisi != null) "Rien ce jour-là." else "Rien de prévu. Ajoute un rendez-vous avec +.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
            }
            items(etat.liste, key = { "${it.rendezVous.id}/${it.debut}" }) { f ->
                CarteRendezVous(f, onClick = { ouvrirFiche(f.rendezVous.id) })
            }
            item { Spacer(Modifier.height(88.dp)) }   // de la place sous le bouton +
        }
    }
}

@Composable
private fun Calendrier(
    mois: YearMonth,
    jourChoisi: LocalDate?,
    parJour: Map<LocalDate, List<Fois>>,
    precedent: () -> Unit,
    suivant: () -> Unit,
    choisir: (LocalDate) -> Unit,
) {
    val aujourdHui = LocalDate.now()
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = precedent) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Mois précédent") }
            Text(mois.enTexte(), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            IconButton(onClick = suivant) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Mois suivant") }
        }
        Row {
            listOf("L", "M", "M", "J", "V", "S", "D").forEach {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        // Semaines du lundi au dimanche ; les cases avant le 1er restent vides
        val decalage = mois.atDay(1).dayOfWeek.value - 1
        val cases = List(decalage) { null } + (1..mois.lengthOfMonth()).map { mois.atDay(it) }
        cases.chunked(7).forEach { semaine ->
            Row {
                semaine.forEach { jour ->
                    Box(Modifier.weight(1f).aspectRatio(1f).padding(2.dp)) {
                        if (jour != null) {
                            CaseJour(jour, jour == aujourdHui, jour == jourChoisi, parJour[jour].orEmpty()) { choisir(jour) }
                        }
                    }
                }
                repeat(7 - semaine.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun CaseJour(jour: LocalDate, estAujourdHui: Boolean, estChoisi: Boolean, rdvs: List<Fois>, onClick: () -> Unit) {
    val couleurs = MaterialTheme.colorScheme
    val forme = RoundedCornerShape(10.dp)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxSize()
            .clip(forme)
            .background(if (estChoisi) couleurs.primary.copy(alpha = 0.18f) else Color.Transparent)
            .then(if (estAujourdHui) Modifier.border(1.5.dp, couleurs.primary, forme) else Modifier)
            .clickable(onClick = onClick),
    ) {
        Text(
            "${jour.dayOfMonth}",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (estAujourdHui) FontWeight.Bold else FontWeight.Normal,
        )
        // Une pastille par sorte de rendez-vous ce jour-là (trois au plus)
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(top = 2.dp).height(5.dp)) {
            rdvs.map { it.rendezVous.sorte }.distinct().take(3).forEach {
                Box(Modifier.size(5.dp).clip(CircleShape).background(it.teinte))
            }
        }
    }
}

@Composable
fun CarteRendezVous(f: Fois, onClick: () -> Unit) {
    val rdv = f.rendezVous
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(12.dp)) {
            Box(Modifier.size(width = 4.dp, height = 36.dp).clip(RoundedCornerShape(2.dp)).background(rdv.sorte.teinte))
            Text(rdv.sorte.icone, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = (if (rdv.important) "❗ " else "") + majuscule(rdv.titre),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    rdv.quandEnTexte(f.debut) + if (rdv.repetition != null) "  ↻" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (rdv.fait) Text("✓", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
        }
    }
}

/**
 * La barre où l'on écrit un rendez-vous comme on le dirait (« mardi 15 h dentiste »).
 * En dessous, ce qu'Evermind a compris, au fur et à mesure.
 */
@Composable
private fun Saisie(
    phrase: String,
    manque: Manque?,
    ecoute: Boolean,
    invite: String?,
    alerte: String?,
    changer: (String) -> Unit,
    envoyer: () -> Unit,
    micro: () -> Unit,
    completer: () -> Unit,
) {
    val lue = remember(phrase) { if (phrase.isBlank()) null else lirePhrase(phrase) }
    val rdvs = remember(lue) { lue?.versRendezVous().orEmpty() }
    Column(Modifier.padding(bottom = 12.dp)) {
        OutlinedTextField(
            value = phrase,
            onValueChange = changer,
            placeholder = { Text(invite ?: if (ecoute) "Je t'écoute..." else "Dis ou écris : mardi 15 h dentiste...") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { envoyer() }),
            trailingIcon = {
                Row {
                    if (phrase.isNotBlank() && !ecoute) {
                        IconButton(onClick = envoyer) { Icon(Icons.AutoMirrored.Filled.Send, "Ajouter") }
                    }
                    IconButton(onClick = micro) {
                        if (ecoute) Icon(Icons.Default.Stop, "Arrêter d'écouter", tint = MaterialTheme.colorScheme.error)
                        else Icon(Icons.Default.Mic, "Parler", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        val couleurs = MaterialTheme.colorScheme
        val petit = MaterialTheme.typography.bodySmall
        when {
            alerte != null -> {
                Text(alerte, color = couleurs.error, style = petit, modifier = Modifier.padding(top = 6.dp, start = 4.dp))
            }
            manque != null -> {
                Text(manque.message, color = couleurs.error, style = petit, modifier = Modifier.padding(top = 6.dp, start = 4.dp))
                if (manque == Manque.HEURE || manque == Manque.JOUR || manque == Manque.QUAND) {
                    TextButton(onClick = completer) { Text("Compléter à la main") }
                }
            }
            // Ce qui sera créé
            rdvs.isNotEmpty() -> {
                val rdv = rdvs.first()
                val quand = rdv.laRepetition?.let { r -> majuscule(r.enTexte()) + ", dès " + rdv.quandEnTexte() } ?: rdv.quandEnTexte()
                val plus = if (rdvs.size > 1) " (et ${rdvs.size - 1} autres)" else ""
                Text("${rdv.sorte.icone} ${rdv.titre} : $quand$plus", color = couleurs.primary, style = petit,
                    modifier = Modifier.padding(top = 6.dp, start = 4.dp))
            }
            // Ce qu'il manque encore
            lue != null -> {
                Text(lue.manque.message, color = couleurs.onSurfaceVariant, style = petit,
                    modifier = Modifier.padding(top = 6.dp, start = 4.dp))
            }
        }
    }
}
