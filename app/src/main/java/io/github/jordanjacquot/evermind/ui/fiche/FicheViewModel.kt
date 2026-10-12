package io.github.jordanjacquot.evermind.ui.fiche

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jordanjacquot.evermind.donnees.DepotRendezVous
import io.github.jordanjacquot.evermind.donnees.RendezVous
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class FicheViewModel(private val depot: DepotRendezVous, private val id: String) : ViewModel() {
    val rendezVous: StateFlow<RendezVous?> = depot.rendezVous(id)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Coche ou décoche une chose à préparer. */
    fun cocher(index: Int) {
        val rdv = rendezVous.value ?: return
        val liste = rdv.aPreparer.toMutableList()
        liste[index] = liste[index].let { it.copy(fait = !it.fait) }
        viewModelScope.launch { depot.enregistrer(rdv.copy(aPreparer = liste)) }
    }

    fun basculerFait() {
        val rdv = rendezVous.value ?: return
        viewModelScope.launch { depot.enregistrer(rdv.copy(fait = !rdv.fait)) }
    }

    fun supprimer(ensuite: () -> Unit) {
        viewModelScope.launch {
            depot.supprimer(id)
            ensuite()
        }
    }
}
