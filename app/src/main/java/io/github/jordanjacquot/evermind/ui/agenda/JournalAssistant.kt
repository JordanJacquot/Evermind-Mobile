package io.github.jordanjacquot.evermind.ui.agenda

import io.github.jordanjacquot.evermind.assistant.Resultat
import io.github.jordanjacquot.evermind.donnees.DepotRendezVous
import io.github.jordanjacquot.evermind.donnees.RendezVous

/**
 * Ce que l'assistant change dans la base. Il travaille sur une copie de l'agenda et peut réfléchir plusieurs secondes :
 * seules les différences entre l'agenda qu'il a reçu et celui qu'il rend sont appliquées (Merge-AgendaAssistant sur PC),
 * et « annule » ne défait que ce qu'il avait changé. Ce qui a été ajouté ou changé à la main entre-temps reste.
 */
class JournalAssistant(private val depot: DepotRendezVous) {
    /** Chaque action de l'assistant : l'agenda qu'il a reçu, celui qu'il a rendu (les 20 dernières). */
    private val faits = ArrayDeque<Pair<List<RendezVous>, List<RendezVous>>>()

    /** La réponse de l'assistant à une demande faite sur l'agenda « recu ». */
    suspend fun appliquer(recu: List<RendezVous>, resultat: Resultat, rendu: List<RendezVous>) {
        when {
            resultat.annulable -> {
                depot.appliquerDifferences(recu, rendu)
                faits.addLast(recu to rendu)
                while (faits.size > 20) faits.removeFirst()
            }
            // « annule » dit à l'assistant : il a remis sa copie d'avant ; on défait ici sa dernière action
            resultat.change -> if (!defaire()) depot.appliquerDifferences(recu, rendu)
        }
    }

    /** Défait la dernière action de l'assistant (bouton « Annuler » ou « annule ») ; faux s'il n'y en a pas. */
    suspend fun defaire(): Boolean {
        val (recu, rendu) = faits.removeLastOrNull() ?: return false
        depot.appliquerDifferences(rendu, recu)
        return true
    }
}
