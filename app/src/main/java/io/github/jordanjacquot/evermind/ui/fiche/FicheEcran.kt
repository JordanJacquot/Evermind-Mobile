package io.github.jordanjacquot.evermind.ui.fiche

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.jordanjacquot.evermind.EvermindApp
import io.github.jordanjacquot.evermind.donnees.RendezVous
import io.github.jordanjacquot.evermind.donnees.Sorte
import io.github.jordanjacquot.evermind.donnees.laRepetition
import io.github.jordanjacquot.evermind.donnees.prochaineFoisDepuis
import io.github.jordanjacquot.evermind.ui.enTexte
import io.github.jordanjacquot.evermind.ui.rappelEnTexte
import io.github.jordanjacquot.evermind.rappels.ReglagesRappels
import io.github.jordanjacquot.evermind.rappels.rappelsDefaut
import io.github.jordanjacquot.evermind.ui.majuscule
import io.github.jordanjacquot.evermind.ui.quandEnTexte
import io.github.jordanjacquot.evermind.ui.theme.teinte
import java.time.LocalDate

/** La fiche détaillée d'un rendez-vous. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FicheEcran(id: String, retour: () -> Unit, modifier: () -> Unit) {
    val app = LocalContext.current.applicationContext as EvermindApp
    val vm = viewModel(key = "fiche-$id") { FicheViewModel(app.depot, id) }
    val rdv by vm.rendezVous.collectAsStateWithLifecycle()
    var confirmerSuppression by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = { IconButton(onClick = retour) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") } },
                actions = {
                    IconButton(onClick = modifier) { Icon(Icons.Default.Edit, "Modifier") }
                    IconButton(onClick = { confirmerSuppression = true }) { Icon(Icons.Default.Delete, "Supprimer") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { marges ->
        val r = rdv ?: return@Scaffold
        Column(
            Modifier.fillMaxSize().padding(marges).padding(horizontal = 20.dp).verticalScroll(rememberScrollState()),
        ) {
            Text("${r.sorte.icone}  ${r.sorte.nom}", color = r.sorte.teinte, style = MaterialTheme.typography.labelLarge)
            Text(
                text = (if (r.important) "❗ " else "") + majuscule(r.titre),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 4.dp),
            )
            // Pour un rendez-vous qui revient : la prochaine fois
            val prochaine = r.prochaineFoisDepuis(LocalDate.now())?.debut ?: r.quand
            Text(majuscule(r.quandEnTexte(prochaine)), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            age(r, prochaine.year)?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            r.laRepetition?.let { Text("↻ " + majuscule(it.enTexte()), color = MaterialTheme.colorScheme.onSurfaceVariant) }

            HorizontalDivider(Modifier.padding(vertical = 16.dp))

            if (r.lieu.isNotBlank()) Detail("Lieu", r.lieu)
            if (r.avec.isNotEmpty()) Detail("Avec", r.avec.joinToString(", "))
            if (r.lien.isNotBlank()) {
                val uri = LocalUriHandler.current
                Detail("Lien", r.lien)
                TextButton(onClick = { runCatching { uri.openUri(r.lien) } }) { Text("Ouvrir le lien") }
            }
            if (r.notes.isNotBlank()) Detail("Notes", r.notes)
            val rappels = r.rappels ?: rappelsDefaut(r.sorte, r.journee, ReglagesRappels(LocalContext.current))
            Detail("Rappels", if (rappels.isEmpty()) "aucun" else rappels.joinToString(", ") { c -> rappelEnTexte(c) } + if (r.rappels == null) " (par défaut)" else "")
            if (r.aPreparer.isNotEmpty()) {
                Text("À préparer", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 12.dp))
                r.aPreparer.forEachIndexed { i, chose ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = chose.fait, onCheckedChange = { vm.cocher(i) })
                        Text(chose.texte)
                    }
                }
            }
            if (r.sorte == Sorte.A_FAIRE) {
                OutlinedButton(onClick = vm::basculerFait, modifier = Modifier.padding(top = 16.dp)) {
                    Text(if (r.fait) "Pas encore fait" else "C'est fait ✓")
                }
            }
        }
    }

    if (confirmerSuppression) {
        AlertDialog(
            onDismissRequest = { confirmerSuppression = false },
            title = { Text("Supprimer ce rendez-vous ?") },
            text = { rdv?.let { Text(majuscule(it.titre)) } },
            confirmButton = {
                TextButton(onClick = { confirmerSuppression = false; vm.supprimer(retour) }) { Text("Supprimer") }
            },
            dismissButton = { TextButton(onClick = { confirmerSuppression = false }) { Text("Garder") } },
        )
    }
}

@Composable
private fun Detail(nom: String, valeur: String) {
    Text(nom, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp))
    Text(valeur, style = MaterialTheme.typography.bodyLarge)
}

/** « 30 ans » pour un anniversaire dont on connaît l'année de naissance. */
private fun age(r: RendezVous, annee: Int): String? {
    val naissance = r.naissance ?: return null
    if (r.sorte != Sorte.ANNIV) return null
    return "${annee - naissance} ans"
}
