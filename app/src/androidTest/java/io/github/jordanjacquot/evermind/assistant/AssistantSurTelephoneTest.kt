package io.github.jordanjacquot.evermind.assistant

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jordanjacquot.evermind.EvermindApp
import io.github.jordanjacquot.evermind.comprendre.Repetition
import io.github.jordanjacquot.evermind.donnees.RendezVous
import io.github.jordanjacquot.evermind.donnees.Sorte
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime

/**
 * L'assistant installé sur ce téléphone, sur quelques demandes : combien de temps il met, et ce qu'il fait.
 * Seulement si un assistant est installé (Réglages, « Installer l'assistant »). Rien n'est changé dans l'agenda.
 */
@RunWith(AndroidJUnit4::class)
class AssistantSurTelephoneTest {
    @Test fun quelquesDemandes() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as EvermindApp
        assumeTrue("pas d'assistant installé", app.cerveau.pret)
        val auj = LocalDateTime.now().toLocalDate()
        val agenda = listOf(
            RendezVous(titre = "Dentiste", sorte = Sorte.SANTE, quand = auj.plusDays(3).atTime(15, 0)),
            RendezVous(titre = "Foot", sorte = Sorte.SPORT, quand = auj.plusDays(4).atTime(19, 0)),
            RendezVous(titre = "Raid avec les potes", sorte = Sorte.JEU, quand = auj.plusDays(1).atTime(21, 30),
                repetition = Repetition("semaine", jour = auj.plusDays(1).dayOfWeek.value % 7).versJson()),
            RendezVous(titre = "Soirée chez Max", sorte = Sorte.SORTIE, quand = auj.plusDays(6).atTime(21, 0), avec = listOf("Max", "Tom")),
        )
        for (demande in listOf("Supprime le foot.", "Décale le dentiste à 16 h.", "Qu'est-ce que j'ai demain ?")) {
            app.cerveau.assistant.oublier()
            val debut = System.currentTimeMillis()
            val r = runBlocking { app.cerveau.assistant.demander(demande, agenda) }
            val duree = (System.currentTimeMillis() - debut) / 1000.0
            Log.i("EvermindAssistant", "TEST « $demande » en $duree s -> ${r?.first?.ecrit ?: "pas de réponse"}")
        }
    }
}
