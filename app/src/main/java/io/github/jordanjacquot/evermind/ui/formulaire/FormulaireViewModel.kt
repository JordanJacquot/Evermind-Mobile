package io.github.jordanjacquot.evermind.ui.formulaire

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jordanjacquot.evermind.comprendre.lirePhrase
import io.github.jordanjacquot.evermind.comprendre.titreAffiche
import io.github.jordanjacquot.evermind.donnees.APreparer
import io.github.jordanjacquot.evermind.donnees.DepotRendezVous
import io.github.jordanjacquot.evermind.donnees.RendezVous
import io.github.jordanjacquot.evermind.donnees.Sorte
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** Ce qui est en train d'être écrit dans le formulaire. */
data class Brouillon(
    val titre: String = "",
    val sorte: Sorte = Sorte.RDV,
    val jour: LocalDate = LocalDate.now(),
    val journee: Boolean = false,
    val heure: LocalTime = LocalTime.of(LocalTime.now().hour, 0).plusHours(1),
    val fin: LocalTime? = null,
    val lieu: String = "",
    /** Les noms séparés par des virgules. */
    val avec: String = "",
    val lien: String = "",
    val notes: String = "",
    val important: Boolean = false,
    /** null : les rappels par défaut ; une liste (même vide) : ceux-là. */
    val rappels: List<String>? = null,
) {
    val peutEnregistrer get() = titre.isNotBlank() && (journee || fin == null || fin.isAfter(heure))
}

class FormulaireViewModel(
    private val depot: DepotRendezVous,
    private val id: String?,
    jour: LocalDate?,
    /** Une phrase écrite dans la barre, à laquelle il manquait quelque chose : ce qui a été compris pré-remplit le formulaire. */
    phrase: String? = null,
) : ViewModel() {
    private val _brouillon = MutableStateFlow(Brouillon(jour = jour ?: LocalDate.now()))
    val brouillon: StateFlow<Brouillon> = _brouillon

    /** Le rendez-vous modifié : les champs que le formulaire ne montre pas sont gardés tels quels. */
    private var original: RendezVous? = null

    init {
        if (phrase != null) {
            val p = lirePhrase(phrase)
            _brouillon.value = Brouillon(
                titre = if (p.titre.isBlank()) "" else titreAffiche(p.titre),
                sorte = Sorte.depuisCode(p.sorte),
                jour = p.jour ?: jour ?: LocalDate.now(),
                journee = p.journee,
                heure = p.heure ?: Brouillon().heure,
                lieu = p.lieu,
                avec = p.avec.joinToString(", "),
                lien = p.lien,
                important = p.important,
                rappels = p.rappels,
            )
            // (l'année de naissance et la liste à préparer ne sont pas dans le formulaire mais restent)
            original = RendezVous(
                titre = "",
                quand = LocalDateTime.now(),
                naissance = p.naissance,
                aPreparer = p.aPreparer.map { APreparer(it) },
            )
        }
        if (id != null) viewModelScope.launch {
            val r = depot.rendezVous(id).first() ?: return@launch
            original = r
            _brouillon.value = Brouillon(
                titre = r.titre,
                sorte = r.sorte,
                jour = r.quand.toLocalDate(),
                journee = r.journee,
                heure = r.quand.toLocalTime(),
                fin = r.fin?.toLocalTime(),
                lieu = r.lieu,
                avec = r.avec.joinToString(", "),
                lien = r.lien,
                notes = r.notes,
                important = r.important,
                rappels = r.rappels,
            )
        }
    }

    fun changer(modif: (Brouillon) -> Brouillon) = _brouillon.update(modif)

    fun enregistrer(ensuite: () -> Unit) {
        val b = _brouillon.value
        if (!b.peutEnregistrer) return
        val quand = if (b.journee) b.jour.atStartOfDay() else LocalDateTime.of(b.jour, b.heure)
        val base = original ?: RendezVous(titre = b.titre, quand = quand)
        val rdv = base.copy(
            titre = b.titre.trim(),
            sorte = b.sorte,
            quand = quand,
            journee = b.journee,
            fin = if (b.journee) null else b.fin?.let { LocalDateTime.of(b.jour, it) },
            lieu = b.lieu.trim(),
            avec = b.avec.split(',').map { it.trim() }.filter { it.isNotEmpty() },
            lien = b.lien.trim(),
            notes = b.notes.trim(),
            important = b.important,
            rappels = b.rappels,
        )
        viewModelScope.launch {
            depot.enregistrer(rdv)
            ensuite()
        }
    }
}
