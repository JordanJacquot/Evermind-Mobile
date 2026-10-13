package io.github.jordanjacquot.evermind.ui.agenda

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import io.github.jordanjacquot.evermind.voix.Voix
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class EtatVoix(val invite: String?) {
    REPOS(null),
    VEILLE("Dis « OK Evermind »... ou écris : mardi 15 h dentiste"),
    ECOUTE("Je t'écoute..."),
    COMPREND("J'écris ce que tu as dit..."),
    REPOND(null),
}

/** Ce qui a été dit, et ce qu'Evermind en a fait. */
data class Dit(val texte: String, val ajout: Ajout)

/**
 * Parler à Evermind, comme sur PC : on parle (micro ou « OK Evermind »), Whisper écrit la phrase, Evermind la comprend,
 * crée le rendez-vous et répond avec sa voix. « OK Evermind » n'écoute que quand l'agenda est à l'écran.
 */
class Conversation(
    private val contexte: Context,
    private val voix: Voix,
    private val portee: CoroutineScope,
    private val ajouter: (String) -> Ajout,
) {
    private val _etat = MutableStateFlow(EtatVoix.REPOS)
    val etat: StateFlow<EtatVoix> = _etat
    private val _dits = MutableSharedFlow<Dit>(extraBufferCapacity = 4)
    val dits: SharedFlow<Dit> = _dits

    private var echange: Job? = null
    private var veille: Job? = null
    private var visible = false

    private val microPermis get() =
        ContextCompat.checkSelfPermission(contexte, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Le bouton micro : parler (ou arrêter d'écouter). */
    fun parler() {
        if (echange?.isActive == true) { echange?.cancel(); _etat.value = EtatVoix.REPOS; reprendreVeille(); return }
        val ecouteReveil = veille
        arreterVeille()
        // (l'écoute de « OK Evermind » rend d'abord le micro)
        echange = portee.launch { ecouteReveil?.join(); echanger(); reprendreVeille() }
    }

    private suspend fun echanger() {
        try {
            _etat.value = EtatVoix.ECOUTE
            val texte = voix.dicter(debut = {})
            if (texte == null) { voix.bip(false); return }
            _etat.value = EtatVoix.COMPREND
            val r = ajouter(texte)
            _dits.emit(Dit(texte, r))
            _etat.value = EtatVoix.REPOND
            voix.dire(if (r is Ajout.Fait) Voix.noteAjoute(r.rendezVous) else Voix.manque((r as Ajout.Incomplet).manque))
        } finally {
            _etat.value = EtatVoix.REPOS
        }
    }

    /** L'agenda est à l'écran (ou ne l'est plus) : « OK Evermind » écoute (ou s'arrête). */
    fun ecranVisible(oui: Boolean) {
        visible = oui
        if (oui) reprendreVeille() else { arreterVeille(); echange?.cancel() }
    }

    private fun reprendreVeille() {
        if (!visible || !voix.eveilActif.value || !voix.eveilInstalle || !voix.voixInstallee || !microPermis) { arreterVeille(); return }
        if (veille?.isActive == true || echange?.isActive == true) return
        veille = portee.launch {
            while (isActive) {
                _etat.value = EtatVoix.VEILLE
                if (!runCatching { voix.attendreReveil() }.getOrDefault(false)) break
                voix.bip()
                echanger()
            }
        }
    }

    private fun arreterVeille() {
        veille?.cancel()
        veille = null
        if (_etat.value == EtatVoix.VEILLE) _etat.value = EtatVoix.REPOS
    }
}
