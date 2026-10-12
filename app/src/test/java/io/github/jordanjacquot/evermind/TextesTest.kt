package io.github.jordanjacquot.evermind

import io.github.jordanjacquot.evermind.donnees.Convertisseurs
import io.github.jordanjacquot.evermind.donnees.RendezVous
import io.github.jordanjacquot.evermind.donnees.Sorte
import io.github.jordanjacquot.evermind.ui.enTexte
import io.github.jordanjacquot.evermind.ui.quandEnTexte
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class TextesTest {
    private val lundi = LocalDate.of(2026, 10, 12)

    @Test fun heures() {
        assertEquals("15 h", LocalTime.of(15, 0).enTexte())
        assertEquals("9 h 05", LocalTime.of(9, 5).enTexte())
    }

    @Test fun jours() {
        assertEquals("aujourd'hui", lundi.enTexte(lundi))
        assertEquals("demain", lundi.plusDays(1).enTexte(lundi))
        assertEquals("jeudi 15 octobre", lundi.plusDays(3).enTexte(lundi))
        assertEquals("vendredi 1 janvier 2027", LocalDate.of(2027, 1, 1).enTexte(lundi))
    }

    @Test fun quand() {
        val rdv = RendezVous(titre = "dentiste", quand = LocalDateTime.of(2026, 10, 13, 15, 0))
        assertEquals("demain à 15 h", rdv.quandEnTexte(lundi))
        assertEquals("demain de 15 h à 16 h 30", rdv.copy(fin = LocalDateTime.of(2026, 10, 13, 16, 30)).quandEnTexte(lundi))
        assertEquals("demain (toute la journée)", rdv.copy(journee = true).quandEnTexte(lundi))
    }

    @Test fun formatDuFichierCommeEvermindPc() {
        val c = Convertisseurs()
        assertEquals("2027-02-27T15:00", c.dateVersTexte(LocalDateTime.of(2027, 2, 27, 15, 0)))
        assertEquals(Sorte.SANTE, c.texteVersSorte("sante"))
        assertEquals(Sorte.A_FAIRE, c.texteVersSorte("rappel"))
        assertEquals(Sorte.RDV, c.texteVersSorte("inconnue"))
    }
}
