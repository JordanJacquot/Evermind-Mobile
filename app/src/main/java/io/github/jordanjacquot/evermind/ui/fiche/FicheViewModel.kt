package io.github.jordanjacquot.evermind.ui.fiche

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jordanjacquot.evermind.donnees.DepotRendezVous
import io.github.jordanjacquot.evermind.donnees.RendezVous
import io.github.jordanjacquot.evermind.donnees.avecFait
import io.github.jordanjacquot.evermind.donnees.estFait
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDateTime

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

    /** « C'est fait » / « Pas encore fait » : pour une chose qui revient, seulement cette fois-là (comme sur PC). */
    fun basculerFait(fois: LocalDateTime) {
        val rdv = rendezVous.value ?: return
        viewModelScope.launch { depot.enregistrer(rdv.avecFait(fois, !rdv.estFait(fois))) }
    }

    /** Une copie de ce rendez-vous (à modifier ensuite), comme « Dupliquer » sur PC. */
    fun dupliquer(ensuite: () -> Unit) {
        val rdv = rendezVous.value ?: return
        viewModelScope.launch {
            depot.enregistrer(rdv.copy(id = java.util.UUID.randomUUID().toString(), fait = false, faitPour = emptyList(), cree = java.time.LocalDateTime.now().withSecond(0).withNano(0)))
            ensuite()
        }
    }

    fun supprimer(ensuite: () -> Unit) {
        viewModelScope.launch {
            depot.supprimer(id)
            ensuite()
        }
    }
}
