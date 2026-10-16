package io.github.jordanjacquot.evermind.maj

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jordanjacquot.evermind.EvermindApp
import io.github.jordanjacquot.evermind.ui.theme.BoutonAccent
import io.github.jordanjacquot.evermind.ui.theme.Verre
import io.github.jordanjacquot.evermind.ui.theme.ev
import io.github.jordanjacquot.evermind.voix.enMo

/**
 * Le bandeau des mises à jour, en haut de l'écran principal (comme dans les autres applis de la suite) : « Une mise à
 * jour est disponible », puis « Mettre à jour » télécharge, vérifie et lance l'installation d'un coup. Rien ne s'affiche
 * tant qu'il n'y a rien à proposer.
 */
@Composable
fun BandeauMiseAJour() {
    val maj = (LocalContext.current.applicationContext as EvermindApp).maj
    val etat by maj.etat.collectAsStateWithLifecycle()
    val (titre, detail, bouton) = when (val e = etat) {
        is EtatMaj.Disponible -> Triple("Une mise à jour est disponible", "Evermind ${e.v.nom} (tu as la ${maj.versionActuelle}).", "Mettre à jour")
        is EtatMaj.Telechargement -> Triple("Téléchargement de la mise à jour", "${enMo(e.octets)} sur ${enMo(e.v.taille)}...", null)
        is EtatMaj.Prete -> Triple("La mise à jour est prête", "Evermind ${e.v.nom} : Android va demander de confirmer l'installation.", "Installer")
        is EtatMaj.Echec -> if (e.v != null) Triple("La mise à jour a échoué", e.message, "Réessayer") else return
        else -> return
    }
    Verre(teinte = ev.p, modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                Text(titre, fontWeight = FontWeight.Bold, color = ev.texte)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = ev.discret, modifier = Modifier.padding(top = 2.dp))
            }
            if (bouton != null) {
                Spacer(Modifier.width(10.dp))
                BoutonAccent(bouton, { maj.mettreAJour() })
            }
        }
        (etat as? EtatMaj.Telechargement)?.let { e ->
            LinearProgressIndicator(
                progress = { if (e.v.taille > 0) (e.octets.toFloat() / e.v.taille).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            )
        }
    }
}
