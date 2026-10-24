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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
import io.github.jordanjacquot.evermind.ui.theme.BoutonAccent
import io.github.jordanjacquot.evermind.ui.theme.BoutonPilule
import io.github.jordanjacquot.evermind.ui.theme.BoutonRond
import io.github.jordanjacquot.evermind.ui.theme.Etiquette
import io.github.jordanjacquot.evermind.ui.theme.IconeSorte
import io.github.jordanjacquot.evermind.ui.theme.LogoEvermind
import io.github.jordanjacquot.evermind.ui.theme.Verre
import io.github.jordanjacquot.evermind.ui.theme.dessin
import io.github.jordanjacquot.evermind.ui.theme.ev
import io.github.jordanjacquot.evermind.ui.theme.teinte
import io.github.jordanjacquot.evermind.ui.theme.traduire
import io.github.jordanjacquot.evermind.voix.Dictee
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth

/** L'écran principal : le calendrier du mois, puis « À venir » ou les rendez-vous du jour choisi. */
@Composable
fun AgendaEcran(ouvrirFiche: (String) -> Unit, ajouter: (LocalDate?) -> Unit, completer: (String) -> Unit, reglages: () -> Unit) {
    val contexte = LocalContext.current
    val app = contexte.applicationContext as EvermindApp
    val vm = viewModel { AgendaViewModel(app) }
    val etatVoix by vm.conversation.etat.collectAsStateWithLifecycle()
    val reflechit by vm.reflechit.collectAsStateWithLifecycle()
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
            is Ajout.Repondu -> {
                // Ce que l'assistant a fait (ou répondu) ; une phrase à compléter revient dans la barre
                val res = r.resultat
                manque = null
                if (res.aCorriger != null) { phrase = res.aCorriger; alerte = res.ecrit; return }
                phrase = ""
                focus.clearFocus()
                scope.launch {
                    messages.currentSnackbarData?.dismiss()
                    val choix = messages.showSnackbar(res.ecrit, if (res.change) "Annuler" else null, duration = SnackbarDuration.Long)
                    if (choix == SnackbarResult.ActionPerformed) vm.annulerAssistant()
                }
            }
            is Ajout.Fait -> {
                phrase = ""
                manque = null
                focus.clearFocus()
                val premier = r.rendezVous.first()
                val quoi = if (r.rendezVous.size > 1) "${r.rendezVous.size} rendez-vous" else premier.titre
                scope.launch {
                    messages.currentSnackbarData?.dismiss()
                    val choix = messages.showSnackbar("Ajouté : $quoi, ${premier.quandEnTexte()}", "Annuler", duration = SnackbarDuration.Long)
                    if (choix == SnackbarResult.ActionPerformed) vm.annuler(r.rendezVous)
                }
            }
        }
    }

    fun envoyer() {
        if (phrase.isBlank() || reflechit) return
        val texte = phrase
        scope.launch { montrer(vm.traiter(texte, parLaVoix = false)) }
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
            fini = { phrase = io.github.jordanjacquot.evermind.voix.Correcteur.siPret()?.corriger(it) ?: it; envoyer() },
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
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground,
        snackbarHost = { SnackbarHost(messages) },
    ) { marges ->
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize().padding(marges).padding(horizontal = 16.dp),
        ) {
            item { EnTete(reglages = reglages, ajouter = { ajouter(etat.jourChoisi) }) }
            item { io.github.jordanjacquot.evermind.maj.BandeauMiseAJour() }
            item {
                Saisie(
                    phrase = phrase,
                    manque = manque,
                    ecoute = ecoute || etatVoix == EtatVoix.ECOUTE || etatVoix == EtatVoix.COMPREND,
                    invite = if (reflechit) "Je réfléchis..." else etatVoix.invite,
                    pourAssistant = { vm.pourAssistant(it, false) },
                    alerte = alerte,
                    voixInstallee = app.voix.voixInstallee,
                    changer = { phrase = it; manque = null; alerte = null },
                    envoyer = ::envoyer,
                    micro = ::parler,
                    completer = { completer(phrase); phrase = ""; manque = null },
                    installerVoix = reglages,
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
                    aujourdHui = vm::aujourdHui,
                )
            }
            item {
                Column(Modifier.padding(top = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = etat.jourChoisi?.let { majuscule(it.enTexte()) } ?: "À venir",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f),
                        )
                        if (etat.jourChoisi != null) BoutonPilule("À venir", vm::aVenir)
                    }
                    Text(
                        when (etat.liste.size) { 0 -> if (etat.jourChoisi != null) "Rien ce jour-là." else "Rien de prévu pour l'instant."; 1 -> "1 rendez-vous"; else -> "${etat.liste.size} rendez-vous" },
                        color = ev.discret,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            items(etat.liste, key = { "${it.rendezVous.id}/${it.debut}" }) { f ->
                CarteRendezVous(f, onClick = { ouvrirFiche(f.rendezVous.id) })
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/** L'en-tête, comme sur PC : le logo, « Evermind », le bonjour du thème ; les réglages et « + ». */
@Composable
private fun EnTete(reglages: () -> Unit, ajouter: () -> Unit) {
    val c = ev
    val bonjour = if (java.time.LocalTime.now().hour >= 18 || java.time.LocalTime.now().hour < 5) "Bonsoir" else "Bonjour"
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
        LogoEvermind(46.dp)
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text("Evermind", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = c.texte)
            Text(c.theme.bonjour.format(bonjour), style = MaterialTheme.typography.bodySmall, color = c.discret, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(42.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.08f))
                .border(1.dp, Color.White.copy(alpha = 0.14f), CircleShape).clickable(onClick = reglages),
        ) { Icon(Icons.Outlined.Settings, "Réglages", tint = c.texte) }
        Spacer(Modifier.width(8.dp))
        BoutonRond(onClick = ajouter, taille = 42.dp) { Icon(Icons.Default.Add, "Nouveau rendez-vous", tint = c.fondBas) }
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
    aujourdHui: () -> Unit,
) {
    val c = ev
    val today = LocalDate.now()
    Verre(interieur = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(mois.enTexte(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (mois != YearMonth.now() || jourChoisi != null) { BoutonPilule("Aujourd'hui", aujourdHui); Spacer(Modifier.width(6.dp)) }
            Fleche(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Mois précédent", precedent)
            Spacer(Modifier.width(6.dp))
            Fleche(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Mois suivant", suivant)
        }
        Row(Modifier.padding(top = 10.dp, bottom = 4.dp)) {
            listOf("lun.", "mar.", "mer.", "jeu.", "ven.", "sam.", "dim.").forEach {
                Text(it, color = c.discret, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
            }
        }
        // Semaines du lundi au dimanche ; les cases avant le 1er restent vides
        val decalage = mois.atDay(1).dayOfWeek.value - 1
        val cases = List(decalage) { null } + (1..mois.lengthOfMonth()).map { mois.atDay(it) }
        cases.chunked(7).forEach { semaine ->
            Row {
                semaine.forEach { jour ->
                    Box(Modifier.weight(1f).aspectRatio(0.86f).padding(2.dp)) {
                        if (jour != null) CaseJour(jour, jour == today, jour == jourChoisi, jour.isBefore(today), parJour[jour].orEmpty()) { choisir(jour) }
                    }
                }
                repeat(7 - semaine.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun Fleche(icone: androidx.compose.ui.graphics.vector.ImageVector, nom: String, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(34.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.08f))
            .border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape).clickable(onClick = onClick),
    ) { Icon(icone, nom, tint = ev.texte, modifier = Modifier.size(20.dp)) }
}

/** Une case du calendrier : une tuile en verre, le numéro, et une barre de couleur par rendez-vous (trois au plus). */
@Composable
private fun CaseJour(jour: LocalDate, estAujourdHui: Boolean, estChoisi: Boolean, passe: Boolean, rdvs: List<Fois>, onClick: () -> Unit) {
    val c = ev
    val forme = RoundedCornerShape(10.dp)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .clip(forme)
            .background(if (estChoisi) c.p.copy(alpha = 0.18f) else Color.White.copy(alpha = if (passe) 0.025f else 0.05f))
            .border(if (estAujourdHui) 1.5.dp else 1.dp, if (estAujourdHui) c.p else Color.White.copy(alpha = 0.07f), forme)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 3.dp),
    ) {
        Text(
            "${jour.dayOfMonth}",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = when { estAujourdHui -> c.p; passe -> c.tresDiscret; else -> c.texte },
        )
        Spacer(Modifier.weight(1f))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            rdvs.take(3).forEach {
                Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(it.rendezVous.sorte.teinte.copy(alpha = if (passe) 0.45f else 1f)))
            }
        }
    }
}

/** « Aujourd'hui », « Demain », « Dans 3 jours » (Format-Relatif sur PC). */
private fun relatif(jour: LocalDate): String {
    val n = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), jour)
    return when {
        n == 0L -> "Aujourd'hui"
        n == 1L -> "Demain"
        n == 2L -> "Après-demain"
        n < 0 -> "En cours"
        n < 14 -> "Dans $n jours"
        n < 60 -> "Dans ${n / 7} semaines"
        else -> "Dans ${Math.round(n / 30.4)} mois"
    }
}

/** Une carte « À venir », teintée de la couleur de sa sorte, comme sur PC. */
@Composable
fun CarteRendezVous(f: Fois, onClick: () -> Unit) {
    val c = ev
    val rdv = f.rendezVous
    val couleur = rdv.sorte.teinte
    Verre(teinte = couleur, onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconeSorte(rdv.sorte, 46.dp)
            Column(Modifier.padding(start = 14.dp).weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Etiquette(rdv.sorte.nom, couleur)
                    if (rdv.important) Etiquette("  ★ important", c.theme.traduire(0xFFFFD84D))
                }
                Text(
                    rdv.titre,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = c.texte,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textDecoration = if (rdv.fait) TextDecoration.LineThrough else null,
                )
                Text(majuscule(rdv.quandEnTexte(f.debut, relatif = false)), style = MaterialTheme.typography.bodySmall, color = c.discret)
                Row {
                    Text(relatif(f.debut.toLocalDate()), style = MaterialTheme.typography.bodySmall, color = c.p)
                    if (rdv.repetition != null) Text("   ↻ revient", style = MaterialTheme.typography.bodySmall, color = couleur)
                    if (rdv.fait) Text("   ✓ fait", style = MaterialTheme.typography.bodySmall, color = c.bien)
                }
            }
        }
    }
}

/**
 * La barre où l'on écrit ou dit un rendez-vous comme on le dirait (« mardi 15 h dentiste »), dans son panneau de verre,
 * avec le micro rond. En dessous, ce qu'Evermind a compris, au fur et à mesure.
 */
@Composable
private fun Saisie(
    phrase: String,
    manque: Manque?,
    ecoute: Boolean,
    invite: String?,
    pourAssistant: (String) -> Boolean,
    alerte: String?,
    voixInstallee: Boolean,
    changer: (String) -> Unit,
    envoyer: () -> Unit,
    micro: () -> Unit,
    completer: () -> Unit,
    installerVoix: () -> Unit,
) {
    val c = ev
    val lue = remember(phrase) { if (phrase.isBlank()) null else lirePhrase(phrase) }
    val rdvs = remember(lue) { lue?.versRendezVous().orEmpty() }
    Verre(interieur = PaddingValues(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = phrase,
                onValueChange = changer,
                placeholder = { Text(invite ?: if (ecoute) "Je t'écoute..." else "Écris ou dis ton rendez-vous : « mardi 15 h dentiste »", color = c.tresDiscret) },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Color.White.copy(alpha = 0.06f),
                    unfocusedContainerColor = Color.White.copy(alpha = 0.06f),
                    focusedBorderColor = c.p.copy(alpha = 0.7f),
                    unfocusedBorderColor = Color.White.copy(alpha = 0.12f),
                ),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { envoyer() }),
                trailingIcon = if (phrase.isNotBlank() && !ecoute) { { IconButton(onClick = envoyer) { Icon(Icons.AutoMirrored.Filled.Send, "Ajouter", tint = c.p) } } } else null,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            // Le micro : rond, en dégradé ; rouge pendant qu'Evermind écoute
            BoutonRond(onClick = micro, taille = 52.dp, couleurs = if (ecoute) listOf(c.t, c.s) else null) {
                Icon(if (ecoute) Icons.Default.Stop else Icons.Default.Mic, if (ecoute) "Arrêter d'écouter" else "Parler", tint = c.fondBas)
            }
        }
        val petit = MaterialTheme.typography.bodySmall
        val marge = Modifier.padding(top = 8.dp, start = 4.dp)
        when {
            alerte != null -> Text(alerte, color = c.mal, style = petit, modifier = marge)
            manque != null -> {
                Text(manque.message, color = c.mal, style = petit, modifier = marge)
                if (manque == Manque.HEURE || manque == Manque.JOUR || manque == Manque.QUAND || manque == Manque.MATIN) {
                    BoutonPilule("Compléter à la main", completer, Modifier.padding(top = 8.dp))
                }
            }
            // Une demande (« décale le dentiste à jeudi ») : l'assistant s'en occupera
            phrase.isNotBlank() && pourAssistant(phrase) -> Text("Evermind s'en occupe : envoie ta demande.", color = c.p, style = petit, modifier = marge)
            // Ce qui sera créé
            rdvs.isNotEmpty() -> {
                val rdv = rdvs.first()
                val quand = rdv.laRepetition?.let { r -> majuscule(r.enTexte()) + ", dès " + rdv.quandEnTexte() } ?: rdv.quandEnTexte()
                val plus = if (rdvs.size > 1) " (et ${rdvs.size - 1} autres)" else ""
                Row(verticalAlignment = Alignment.CenterVertically, modifier = marge) {
                    Icon(rdv.sorte.dessin, null, tint = rdv.sorte.teinte, modifier = Modifier.size(16.dp))
                    Text(" ${rdv.titre} : $quand$plus", color = c.p, style = petit)
                }
                // Jour de la semaine et date qui ne vont pas ensemble (« lundi 3 novembre ») : on dit le jour pris
                lue?.remarque?.takeIf { it.isNotEmpty() }?.let { Text(it, color = c.mal, style = petit, modifier = marge) }
            }
            // Ce qu'il manque encore
            lue != null -> Text(lue.manque.message, color = c.discret, style = petit, modifier = marge)
            // Comme sur PC : la voix à installer, proposée là où on en a besoin
            !voixInstallee -> {
                Verre(teinte = c.p, forme = RoundedCornerShape(14.dp), interieur = PaddingValues(12.dp), modifier = Modifier.padding(top = 10.dp)) {
                    Text(
                        "Pour comprendre ta voix et te répondre avec une voix naturelle, comme sur PC, installe la voix (510 Mo, une seule fois). Ensuite ta voix ne quitte jamais le téléphone.",
                        style = petit, color = c.texte,
                    )
                    BoutonAccent("Installer la voix", installerVoix, Modifier.padding(top = 8.dp))
                }
            }
        }
    }
}
