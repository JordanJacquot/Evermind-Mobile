package io.github.jordanjacquot.evermind.ui.fiche

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.jordanjacquot.evermind.EvermindApp
import io.github.jordanjacquot.evermind.donnees.RendezVous
import io.github.jordanjacquot.evermind.donnees.Sorte
import io.github.jordanjacquot.evermind.donnees.laRepetition
import io.github.jordanjacquot.evermind.donnees.prochaineFoisDepuis
import io.github.jordanjacquot.evermind.rappels.ReglagesRappels
import io.github.jordanjacquot.evermind.rappels.rappelsDefaut
import io.github.jordanjacquot.evermind.ui.enTexte
import io.github.jordanjacquot.evermind.ui.majuscule
import io.github.jordanjacquot.evermind.ui.quandEnTexte
import io.github.jordanjacquot.evermind.ui.rappelEnTexte
import io.github.jordanjacquot.evermind.ui.theme.BoutonAccent
import io.github.jordanjacquot.evermind.ui.theme.BoutonPilule
import io.github.jordanjacquot.evermind.ui.theme.Etiquette
import io.github.jordanjacquot.evermind.ui.theme.IconeSorte
import io.github.jordanjacquot.evermind.ui.theme.Verre
import io.github.jordanjacquot.evermind.ui.theme.ev
import io.github.jordanjacquot.evermind.ui.theme.teinte
import io.github.jordanjacquot.evermind.ui.theme.traduire
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** La fiche d'un rendez-vous, comme sur PC : l'en-tête aux couleurs de la sorte, puis chaque détail avec son icône. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FicheEcran(id: String, retour: () -> Unit, modifier: () -> Unit) {
    val app = LocalContext.current.applicationContext as EvermindApp
    val vm = viewModel(key = "fiche-$id") { FicheViewModel(app.depot, id) }
    val rdv by vm.rendezVous.collectAsStateWithLifecycle()
    var confirmerSuppression by remember { mutableStateOf(false) }
    val c = ev

    Scaffold(
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground,
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = { IconButton(onClick = retour) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { marges ->
        val r = rdv ?: return@Scaffold
        val couleur = r.sorte.teinte
        // Pour un rendez-vous qui revient : la prochaine fois
        val prochaine = r.prochaineFoisDepuis(LocalDate.now())?.debut ?: r.quand
        Column(
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize().padding(marges).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
        ) {
            // L'en-tête : l'icône dans son anneau, la sorte, le nom, quand
            Verre(teinte = couleur, interieur = PaddingValues(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconeSorte(r.sorte, 58.dp)
                    Column(Modifier.padding(start = 16.dp).weight(1f)) {
                        Row {
                            Etiquette(r.sorte.nom, couleur)
                            if (r.important) Etiquette("  ★ important", c.theme.traduire(0xFFFFD84D))
                        }
                        Text(
                            majuscule(r.titre),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = c.texte,
                            textDecoration = if (r.fait) TextDecoration.LineThrough else null,
                        )
                        val jours = ChronoUnit.DAYS.between(LocalDate.now(), prochaine.toLocalDate())
                        val dans = when { jours == 0L -> "aujourd'hui"; jours == 1L -> "demain"; jours > 1 -> "dans $jours jours"; else -> null }
                        Text(
                            majuscule(r.quandEnTexte(prochaine, relatif = false)) + (dans?.let { "   ($it)" } ?: ""),
                            style = MaterialTheme.typography.bodyMedium,
                            color = c.texte.copy(alpha = 0.85f),
                        )
                        age(r, prochaine.year)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = couleur) }
                        r.laRepetition?.let { Text("↻ " + majuscule(it.enTexte()), style = MaterialTheme.typography.bodySmall, color = couleur) }
                    }
                }
            }

            // Les détails
            Verre(interieur = PaddingValues(18.dp)) {
                val uri = LocalUriHandler.current
                var premier = true
                @Composable
                fun section(icone: ImageVector, nom: String, contenu: @Composable ColumnScope.() -> Unit) {
                    if (!premier) Spacer(Modifier.size(14.dp))
                    premier = false
                    Detail(icone, nom, contenu)
                }
                if (r.lieu.isNotBlank()) section(Icons.Outlined.Place, "Où") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(r.lieu, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        BoutonPilule("Itinéraire", { runCatching { uri.openUri("geo:0,0?q=" + Uri.encode(r.lieu)) } })
                    }
                }
                if (r.avec.isNotEmpty()) section(Icons.Outlined.People, "Avec") { Text(r.avec.joinToString(", "), style = MaterialTheme.typography.bodyLarge) }
                if (r.lien.isNotBlank()) section(Icons.Outlined.Link, "Lien") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(r.lien, style = MaterialTheme.typography.bodyLarge, color = c.p, modifier = Modifier.weight(1f))
                        BoutonPilule("Ouvrir", { runCatching { uri.openUri(if (r.lien.contains("://")) r.lien else "https://${r.lien}") } })
                    }
                }
                val rappels = r.rappels ?: rappelsDefaut(r.sorte, r.journee, ReglagesRappels(LocalContext.current))
                section(Icons.Outlined.NotificationsNone, "Je te préviens") {
                    Text(
                        if (rappels.isEmpty()) "Aucun rappel" else majuscule(rappels.joinToString(", ") { rappelEnTexte(it) }),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                if (r.aPreparer.isNotEmpty()) {
                    val faits = r.aPreparer.count { it.fait }
                    section(Icons.Outlined.Checklist, "À préparer ($faits sur ${r.aPreparer.size})") {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            r.aPreparer.forEachIndexed { i, chose ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                        .background(if (chose.fait) c.bien.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.05f))
                                        .clickable { vm.cocher(i) }.padding(horizontal = 10.dp, vertical = 8.dp),
                                ) {
                                    Icon(if (chose.fait) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank, null,
                                        tint = if (chose.fait) c.bien else c.discret, modifier = Modifier.size(20.dp))
                                    Text(
                                        chose.texte,
                                        modifier = Modifier.padding(start = 10.dp),
                                        color = if (chose.fait) c.discret else c.texte,
                                        textDecoration = if (chose.fait) TextDecoration.LineThrough else null,
                                    )
                                }
                            }
                        }
                    }
                }
                if (r.notes.isNotBlank()) section(Icons.Outlined.Description, "Notes") { Text(r.notes, style = MaterialTheme.typography.bodyLarge) }
            }

            // Les boutons, comme sur PC
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (r.sorte == Sorte.A_FAIRE) BoutonPilule(if (r.fait) "Pas encore fait" else "C'est fait ✓", vm::basculerFait, couleur = c.bien)
                BoutonPilule("Dupliquer", { vm.dupliquer(retour) })
                BoutonPilule("Supprimer", { confirmerSuppression = true }, couleur = c.mal)
                BoutonAccent("Modifier", modifier)
            }
            Spacer(Modifier.size(24.dp))
        }
    }

    if (confirmerSuppression) {
        AlertDialog(
            onDismissRequest = { confirmerSuppression = false },
            title = { Text("Supprimer ce rendez-vous ?") },
            text = { rdv?.let { Text(majuscule(it.titre) + if (it.repetition != null) " (toutes les fois)" else "") } },
            confirmButton = {
                TextButton(onClick = { confirmerSuppression = false; vm.supprimer(retour) }) { Text("Supprimer", color = c.mal) }
            },
            dismissButton = { TextButton(onClick = { confirmerSuppression = false }) { Text("Garder") } },
        )
    }
}

/** Un détail : son icône, son nom en petites majuscules, puis son contenu. */
@Composable
private fun Detail(icone: ImageVector, nom: String, contenu: @Composable ColumnScope.() -> Unit) {
    val c = ev
    Row {
        Icon(icone, null, tint = c.discret, modifier = Modifier.padding(top = 1.dp).size(20.dp))
        Column(Modifier.padding(start = 14.dp).weight(1f)) {
            Etiquette(nom, c.discret)
            Spacer(Modifier.size(4.dp))
            contenu()
        }
    }
}

/** « 30 ans » pour un anniversaire dont on connaît l'année de naissance. */
private fun age(r: RendezVous, annee: Int): String? {
    val naissance = r.naissance ?: return null
    if (r.sorte != Sorte.ANNIV) return null
    return "${annee - naissance} ans"
}
