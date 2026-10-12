package io.github.jordanjacquot.evermind.ui.agenda

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jordanjacquot.evermind.donnees.DepotRendezVous
import io.github.jordanjacquot.evermind.donnees.RendezVous
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.time.LocalDate
import java.time.YearMonth

data class AgendaEtat(
    val mois: YearMonth = YearMonth.now(),
    /** null : la liste montre « À venir » ; sinon les rendez-vous de ce jour. */
    val jourChoisi: LocalDate? = null,
    /** Les rendez-vous de chaque jour du mois affiché (pour les pastilles du calendrier). */
    val parJour: Map<LocalDate, List<RendezVous>> = emptyMap(),
    val liste: List<RendezVous> = emptyList(),
)

// Les rendez-vous répétés ne s'affichent qu'à leur première fois : les suivantes arrivent avec l'étape 2
class AgendaViewModel(depot: DepotRendezVous) : ViewModel() {
    private val mois = MutableStateFlow(YearMonth.now())
    private val jourChoisi = MutableStateFlow<LocalDate?>(null)

    val etat: StateFlow<AgendaEtat> = combine(depot.tous, mois, jourChoisi) { tous, mois, jour ->
        val aujourdHui = LocalDate.now()
        AgendaEtat(
            mois = mois,
            jourChoisi = jour,
            parJour = tous.filter { YearMonth.from(it.quand) == mois }.groupBy { it.quand.toLocalDate() },
            liste = if (jour != null) tous.filter { it.quand.toLocalDate() == jour }
                    else tous.filter { !it.quand.toLocalDate().isBefore(aujourdHui) },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AgendaEtat())

    fun moisPrecedent() = mois.update { it.minusMonths(1) }
    fun moisSuivant() = mois.update { it.plusMonths(1) }

    /** Toucher le jour déjà choisi revient à « À venir ». */
    fun choisir(jour: LocalDate) = jourChoisi.update { if (it == jour) null else jour }
    fun aVenir() { jourChoisi.value = null }
}
