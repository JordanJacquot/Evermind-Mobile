package io.github.jordanjacquot.evermind.ui.agenda

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jordanjacquot.evermind.EvermindApp
import io.github.jordanjacquot.evermind.assistant.Resultat
import io.github.jordanjacquot.evermind.assistant.estCommande
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
    /** L'assistant a répondu (et peut-être changé l'agenda). */
    data class Repondu(val resultat: Resultat) : Ajout
}

class AgendaViewModel(app: EvermindApp) : ViewModel() {
    private val depot: DepotRendezVous = app.depot
    private val cerveau = app.cerveau
    private val _reflechit = MutableStateFlow(false)
    /** L'assistant réfléchit à une demande. */
    val reflechit: StateFlow<Boolean> = _reflechit
    /** Parler à Evermind (micro ou « OK Evermind »). */
    val conversation = Conversation(app, app.voix, viewModelScope) { traiter(it, parLaVoix = true) }
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
    fun aujourdHui() { mois.value = YearMonth.now(); jourChoisi.value = null }

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

    /** L'assistant est-il prêt à prendre cette phrase ? (tout ce qui est dit ; une commande écrite, « décale... ») */
    fun pourAssistant(phrase: String, parLaVoix: Boolean) = cerveau.pret && (parLaVoix || estCommande(phrase))

    /**
     * Une phrase écrite ou dite : l'assistant s'en occupe s'il est là (comme sur PC), sinon les règles d'Evermind.
     * S'il ne répond pas, les règles prennent le relais.
     */
    suspend fun traiter(phrase: String, parLaVoix: Boolean): Ajout {
        if (pourAssistant(phrase, parLaVoix)) {
            _reflechit.value = true
            try {
                val r = cerveau.assistant.demander(phrase, depot.liste())
                if (r != null) {
                    if (r.first.change) depot.remplacerTout(r.second)
                    return Ajout.Repondu(r.first)
                }
                // Pas de réponse : une demande écrite le dit (comme sur PC) ; une phrase dite passe aux règles d'Evermind
                if (!parLaVoix) {
                    val t = "L'assistant n'a pas répondu. Réessaie dans un instant."
                    return Ajout.Repondu(Resultat(t, t, null, false))
                }
            } finally {
                _reflechit.value = false
            }
        }
        return ajouter(phrase)
    }

    /** « Annuler » après une action de l'assistant : l'agenda revient comme avant. */
    fun annulerAssistant() {
        val avant = cerveau.assistant.annulerDerniere() ?: return
        viewModelScope.launch { depot.remplacerTout(avant) }
    }

    fun annuler(rdvs: List<RendezVous>) {
        viewModelScope.launch { rdvs.forEach { depot.supprimer(it.id) } }
    }
}
