package io.github.jordanjacquot.evermind.ui.agenda

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jordanjacquot.evermind.comprendre.Manque
import io.github.jordanjacquot.evermind.comprendre.lirePhrase
import io.github.jordanjacquot.evermind.comprendre.versRendezVous
import io.github.jordanjacquot.evermind.donnees.DepotRendezVous
import io.github.jordanjacquot.evermind.donnees.Fois
import io.github.jordanjacquot.evermind.donnees.RendezVous
import io.github.jordanjacquot.evermind.donnees.fois
import io.github.jordanjacquot.evermind.donnees.prochaineFoisDepuis
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth

data class AgendaEtat(
    val mois: YearMonth = YearMonth.now(),
    /** null : la liste montre « À venir » ; sinon les rendez-vous de ce jour. */
    val jourChoisi: LocalDate? = null,
    /** Les rendez-vous de chaque jour du mois affiché (pour les pastilles du calendrier). */
    val parJour: Map<LocalDate, List<Fois>> = emptyMap(),
    val liste: List<Fois> = emptyList(),
)

/** Ce qu'a donné une phrase écrite dans la barre. */
sealed interface Ajout {
    data class Fait(val rendezVous: List<RendezVous>) : Ajout
    data class Incomplet(val manque: Manque) : Ajout
}

class AgendaViewModel(private val depot: DepotRendezVous) : ViewModel() {
    private val mois = MutableStateFlow(YearMonth.now())
    private val jourChoisi = MutableStateFlow<LocalDate?>(null)

    val etat: StateFlow<AgendaEtat> = combine(depot.tous, mois, jourChoisi) { tous, mois, jour ->
        val aujourdHui = LocalDate.now()
        val parJour = mutableMapOf<LocalDate, MutableList<Fois>>()
        for (r in tous) for (f in r.fois(mois.atDay(1), mois.atEndOfMonth())) {
            for (j in f.jours) if (YearMonth.from(j) == mois) parJour.getOrPut(j) { mutableListOf() } += f
        }
        AgendaEtat(
            mois = mois,
            jourChoisi = jour,
            parJour = parJour.mapValues { (_, l) -> l.sortedBy { it.debut } },
            liste = if (jour != null) tous.flatMap { it.fois(jour, jour) }.sortedBy { it.debut }
                    else tous.mapNotNull { it.prochaineFoisDepuis(aujourdHui) }.sortedBy { it.debut },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AgendaEtat())

    fun moisPrecedent() = mois.update { it.minusMonths(1) }
    fun moisSuivant() = mois.update { it.plusMonths(1) }

    /** Toucher le jour déjà choisi revient à « À venir ». */
    fun choisir(jour: LocalDate) = jourChoisi.update { if (it == jour) null else jour }
    fun aVenir() { jourChoisi.value = null }

    /** Crée le ou les rendez-vous d'une phrase, si elle dit tout ce qu'il faut. */
    fun ajouter(phrase: String): Ajout {
        val p = lirePhrase(phrase)
        val rdvs = p.versRendezVous()
        if (rdvs.isEmpty()) return Ajout.Incomplet(p.manque)
        viewModelScope.launch { rdvs.forEach { depot.enregistrer(it) } }
        // Le calendrier montre le mois du (premier) rendez-vous ajouté
        mois.value = YearMonth.from(rdvs.first().quand)
        return Ajout.Fait(rdvs)
    }

    fun annuler(rdvs: List<RendezVous>) {
        viewModelScope.launch { rdvs.forEach { depot.supprimer(it.id) } }
    }
}
