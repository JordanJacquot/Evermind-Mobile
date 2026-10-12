package io.github.jordanjacquot.evermind.ui.agenda

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.jordanjacquot.evermind.EvermindApp
import io.github.jordanjacquot.evermind.donnees.RendezVous
import io.github.jordanjacquot.evermind.ui.enTexte
import io.github.jordanjacquot.evermind.ui.majuscule
import io.github.jordanjacquot.evermind.ui.quandEnTexte
import io.github.jordanjacquot.evermind.ui.theme.teinte
import java.time.LocalDate
import java.time.YearMonth

/** L'écran principal : le calendrier du mois, puis « À venir » ou les rendez-vous du jour choisi. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgendaEcran(ouvrirFiche: (String) -> Unit, ajouter: (LocalDate?) -> Unit) {
    val app = LocalContext.current.applicationContext as EvermindApp
    val vm = viewModel { AgendaViewModel(app.depot) }
    val etat by vm.etat.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Evermind", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { ajouter(etat.jourChoisi) }) {
                Icon(Icons.Default.Add, contentDescription = "Ajouter un rendez-vous")
            }
        },
    ) { marges ->
        LazyColumn(Modifier.fillMaxSize().padding(marges).padding(horizontal = 16.dp)) {
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
            items(etat.liste, key = { it.id }) { rdv ->
                CarteRendezVous(rdv, onClick = { ouvrirFiche(rdv.id) })
            }
            item { Spacer(Modifier.height(88.dp)) }   // de la place sous le bouton +
        }
    }
}

@Composable
private fun Calendrier(
    mois: YearMonth,
    jourChoisi: LocalDate?,
    parJour: Map<LocalDate, List<RendezVous>>,
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
private fun CaseJour(jour: LocalDate, estAujourdHui: Boolean, estChoisi: Boolean, rdvs: List<RendezVous>, onClick: () -> Unit) {
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
            rdvs.map { it.sorte }.distinct().take(3).forEach {
                Box(Modifier.size(5.dp).clip(CircleShape).background(it.teinte))
            }
        }
    }
}

@Composable
fun CarteRendezVous(rdv: RendezVous, onClick: () -> Unit) {
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
                Text(rdv.quandEnTexte(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (rdv.fait) Text("✓", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
        }
    }
}
