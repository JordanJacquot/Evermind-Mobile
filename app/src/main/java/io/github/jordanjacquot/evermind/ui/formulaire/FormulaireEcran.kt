package io.github.jordanjacquot.evermind.ui.formulaire

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.jordanjacquot.evermind.EvermindApp
import io.github.jordanjacquot.evermind.donnees.Sorte
import io.github.jordanjacquot.evermind.ui.theme.BoutonAccent
import io.github.jordanjacquot.evermind.ui.theme.Etiquette
import io.github.jordanjacquot.evermind.ui.theme.Verre
import io.github.jordanjacquot.evermind.ui.theme.dessin
import io.github.jordanjacquot.evermind.ui.theme.ev
import io.github.jordanjacquot.evermind.ui.theme.teinte
import androidx.compose.foundation.layout.size
import io.github.jordanjacquot.evermind.rappels.RappelsChoix
import io.github.jordanjacquot.evermind.rappels.ReglagesRappels
import io.github.jordanjacquot.evermind.rappels.rappelsDefaut
import io.github.jordanjacquot.evermind.ui.rappelEnTexte
import io.github.jordanjacquot.evermind.ui.enTexte
import io.github.jordanjacquot.evermind.ui.majuscule
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

/** Ajouter un rendez-vous à la main (id null), ou modifier celui-ci. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FormulaireEcran(id: String?, jour: LocalDate?, phrase: String?, retour: () -> Unit) {
    val contexte = LocalContext.current
    val app = contexte.applicationContext as EvermindApp
    val vm = viewModel(key = "formulaire-$id-$phrase") { FormulaireViewModel(app.depot, id, jour, phrase) }
    val b by vm.brouillon.collectAsStateWithLifecycle()
    var choixJour by remember { mutableStateOf(false) }
    var choixHeure by remember { mutableStateOf(false) }
    var choixFin by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground,
        topBar = {
            TopAppBar(
                title = { Text(if (id == null) "Nouveau rendez-vous" else "Modifier") },
                navigationIcon = { IconButton(onClick = retour) { Icon(Icons.Default.Close, "Annuler") } },
                actions = {
                    TextButton(onClick = { vm.enregistrer(retour) }, enabled = b.peutEnregistrer) { Text("Enregistrer") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
            )
        },
    ) { marges ->
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(marges)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
          // Quoi, et de quelle sorte
          Verre(modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = b.titre,
                onValueChange = { t -> vm.changer { it.copy(titre = t) } },
                label = { Text("Quoi ?") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )

            Etiquette("Sorte", ev.discret, Modifier.padding(top = 12.dp, bottom = 4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Sorte.entries.forEach { s ->
                    FilterChip(
                        selected = b.sorte == s,
                        onClick = { vm.changer { it.copy(sorte = s) } },
                        label = { Text(s.nom) },
                        leadingIcon = { Icon(s.dessin, null, tint = s.teinte, modifier = Modifier.size(18.dp)) },
                    )
                }
            }
          }
          // Quand
          Verre(modifier = Modifier.fillMaxWidth()) {

            OutlinedButton(onClick = { choixJour = true }, modifier = Modifier.fillMaxWidth()) {
                Text(majuscule(b.jour.enTexte()))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Toute la journée", modifier = Modifier.weight(1f))
                Switch(checked = b.journee, onCheckedChange = { v -> vm.changer { it.copy(journee = v) } })
            }
            if (!b.journee) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { choixHeure = true }, modifier = Modifier.weight(1f)) {
                        Text("À ${b.heure.enTexte()}")
                    }
                    OutlinedButton(onClick = { choixFin = true }, modifier = Modifier.weight(1f)) {
                        Text(b.fin?.let { "Jusqu'à ${it.enTexte()}" } ?: "Heure de fin")
                    }
                }
                if (b.fin?.isAfter(b.heure) == false) {
                    Text("La fin doit être après le début.", color = MaterialTheme.colorScheme.error)
                }
            }
          }
          // Les détails
          Verre(modifier = Modifier.fillMaxWidth()) {

            Champ("Lieu", b.lieu) { t -> vm.changer { it.copy(lieu = t) } }
            Champ("Avec (séparés par des virgules)", b.avec) { t -> vm.changer { it.copy(avec = t) } }
            Champ("Lien", b.lien) { t -> vm.changer { it.copy(lien = t) } }
            OutlinedTextField(
                value = b.notes,
                onValueChange = { t -> vm.changer { it.copy(notes = t) } },
                label = { Text("Notes") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            // Les rappels : ceux par défaut (Réglages), ou ceux choisis pour ce rendez-vous
            val parDefaut = rappelsDefaut(b.sorte, b.journee, ReglagesRappels(contexte))
            val choisis = b.rappels ?: parDefaut
            Etiquette("Je te préviens", ev.discret, Modifier.padding(top = 12.dp, bottom = 4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = b.rappels == null,
                    onClick = { vm.changer { it.copy(rappels = if (it.rappels == null) parDefaut else null) } },
                    label = { Text("Par défaut") },
                )
                RappelsChoix.filter { !b.journee || !it.startsWith("m") }.forEach { code ->
                    FilterChip(
                        selected = code in choisis,
                        onClick = { vm.changer { it.copy(rappels = if (code in choisis) choisis - code else RappelsChoix.filter { c -> c in choisis || c == code }) } },
                        label = { Text(rappelEnTexte(code)) },
                    )
                }
            }
            if (choisis.isEmpty()) Text("Aucun rappel pour ce rendez-vous.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Important", modifier = Modifier.weight(1f))
                Switch(checked = b.important, onCheckedChange = { v -> vm.changer { it.copy(important = v) } })
            }
          }
            BoutonAccent("Enregistrer", { vm.enregistrer(retour) }, Modifier.fillMaxWidth().padding(vertical = 8.dp), actif = b.peutEnregistrer)
        }
    }

    if (choixJour) {
        // Le sélecteur de date compte en millisecondes UTC
        val etat = rememberDatePickerState(initialSelectedDateMillis = b.jour.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { choixJour = false },
            confirmButton = {
                TextButton(onClick = {
                    etat.selectedDateMillis?.let { ms ->
                        val j = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
                        vm.changer { it.copy(jour = j) }
                    }
                    choixJour = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { choixJour = false }) { Text("Annuler") } },
        ) { DatePicker(state = etat) }
    }
    if (choixHeure) {
        ChoixHeure(b.heure, fermer = { choixHeure = false }) { h -> vm.changer { it.copy(heure = h) } }
    }
    if (choixFin) {
        ChoixHeure(
            initiale = b.fin ?: b.heure.plusHours(1),
            fermer = { choixFin = false },
            effacer = { vm.changer { it.copy(fin = null) } },
        ) { h -> vm.changer { it.copy(fin = h) } }
    }
}

@Composable
private fun Champ(nom: String, valeur: String, changer: (String) -> Unit) {
    OutlinedTextField(
        value = valeur,
        onValueChange = changer,
        label = { Text(nom) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChoixHeure(initiale: LocalTime, fermer: () -> Unit, effacer: (() -> Unit)? = null, choisir: (LocalTime) -> Unit) {
    val etat = rememberTimePickerState(initialHour = initiale.hour, initialMinute = initiale.minute, is24Hour = true)
    AlertDialog(
        onDismissRequest = fermer,
        text = { TimePicker(state = etat) },
        confirmButton = {
            TextButton(onClick = { choisir(LocalTime.of(etat.hour, etat.minute)); fermer() }) { Text("OK") }
        },
        dismissButton = {
            Row {
                if (effacer != null) TextButton(onClick = { effacer(); fermer() }) { Text("Sans fin") }
                TextButton(onClick = fermer) { Text("Annuler") }
            }
        },
    )
}
