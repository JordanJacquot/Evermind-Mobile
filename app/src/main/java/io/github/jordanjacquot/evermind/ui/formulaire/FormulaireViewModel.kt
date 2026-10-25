package io.github.jordanjacquot.evermind.ui.formulaire

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jordanjacquot.evermind.comprendre.Repetition
import io.github.jordanjacquot.evermind.comprendre.lirePhrase
import io.github.jordanjacquot.evermind.comprendre.prochaineFois
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
import java.time.Duration
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
    /** Le dernier jour, quand il n'est pas celui du début (des vacances du 20 au 27). */
    val jourFin: LocalDate? = null,
    /** Revient (format d'Evermind PC, comme RendezVous.repetition) ; null : une seule fois. */
    val repetition: String? = null,
    val lieu: String = "",
    /** Les noms séparés par des virgules. */
    val avec: String = "",
    val lien: String = "",
    val notes: String = "",
    val important: Boolean = false,
    /** null : les rappels par défaut ; une liste (même vide) : ceux-là. */
    val rappels: List<String>? = null,
) {
    val debut: LocalDateTime get() = if (journee) jour.atStartOfDay() else LocalDateTime.of(jour, heure)

    /**
     * La fin complète, date comprise. Sans autre jour de fin, une heure de fin avant celle du début tombe le lendemain
     * (une soirée de 20 h à 2 h) ; sur plusieurs jours sans heure de fin, le dernier jour à 23 h 59 (comme la phrase).
     */
    val finComplete: LocalDateTime?
        get() {
            val dernier = jourFin?.takeIf { it.isAfter(jour) }
            if (journee) return dernier?.atStartOfDay()
            val f = fin ?: return dernier?.atTime(23, 59)
            if (dernier != null) return LocalDateTime.of(dernier, f)
            return LocalDateTime.of(jour, f).let { if (f.isBefore(heure)) it.plusDays(1) else it }
        }

    /** La fin tombe un autre jour que le début (pour le dire dans le formulaire). */
    val finUnAutreJour: Boolean get() = finComplete?.toLocalDate()?.isAfter(jour) == true

    val peutEnregistrer get() = titre.isNotBlank() && finComplete?.isAfter(debut) != false
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
            val heure = p.heure ?: Brouillon().heure
            // Une phrase qui revient (« chaque mardi à 6 h natation ») : la répétition est gardée, et le jour proposé est
            // sa prochaine fois
            val rep = p.repetitionLue
            val premier = (p.jour ?: jour ?: LocalDate.now()).let { j ->
                rep?.let { prochaineFois(it, j.atTime(if (p.journee) LocalTime.MIDNIGHT else heure), LocalDate.now().atStartOfDay())?.toLocalDate() } ?: j
            }
            _brouillon.value = Brouillon(
                titre = if (p.titre.isBlank()) "" else titreAffiche(p.titre),
                sorte = Sorte.depuisCode(p.sorte),
                jour = premier,
                journee = p.journee,
                heure = heure,
                repetition = rep?.versJson(),
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
                fin = if (r.journee) null else r.fin?.toLocalTime(),
                // (une soirée qui finit le lendemain, avant l'heure du début : le formulaire le retrouve tout seul)
                jourFin = r.fin?.let { f ->
                    f.toLocalDate().takeIf { d ->
                        d.isAfter(r.quand.toLocalDate()) &&
                            (r.journee || d != r.quand.toLocalDate().plusDays(1) || !f.toLocalTime().isBefore(r.quand.toLocalTime()))
                    }
                },
                repetition = r.repetition,
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

    /** Un autre jour de début : le jour de fin suit (des vacances déplacées gardent leur durée). */
    fun changerJour(j: LocalDate) = _brouillon.update { b ->
        b.copy(jour = j, jourFin = b.jourFin?.plusDays(java.time.temporal.ChronoUnit.DAYS.between(b.jour, j)))
    }

    fun enregistrer(ensuite: () -> Unit) {
        val b = _brouillon.value
        if (!b.peutEnregistrer) return
        var quand = b.debut
        // Une nouvelle chose qui revient : son premier jour est une vraie fois de la répétition
        val rep = Repetition.depuisJson(b.repetition)
        if (rep != null && id == null) quand = prochaineFois(rep, quand, quand.toLocalDate().atStartOfDay()) ?: quand
        val fin = b.finComplete?.plus(Duration.between(b.debut, quand))
        val base = original ?: RendezVous(titre = b.titre, quand = quand)
        val rdv = base.copy(
            titre = b.titre.trim(),
            sorte = b.sorte,
            quand = quand,
            journee = b.journee,
            fin = fin,
            repetition = b.repetition,
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
