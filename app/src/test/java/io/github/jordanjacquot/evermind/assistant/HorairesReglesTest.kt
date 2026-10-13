package io.github.jordanjacquot.evermind.assistant

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.LocalTime

/** Chacun ses heures de travail : réglées (à la voix ou dans les Réglages), puis utilisées pour les postes. */
class HorairesReglesTest {
    private val maintenant = LocalDateTime.of(2026, 10, 13, 9, 0)

    @Test fun lireLeReglage() {
        assertEquals("matin" to "7 h à 15 h", reglageHoraire("Mon poste du matin, c'est de 7 h à 15 h."))
        assertEquals("nuit" to "21 h à 5 h", reglageHoraire("Mes horaires de nuit sont 21h-5h"))
        assertEquals("soir" to "15 h 30 à 23 h", reglageHoraire("Le travail du soir c'est maintenant de 15 h 30 à 23 h"))
        assertEquals("apresmidi" to "13 h à 21 h", reglageHoraire("Poste d'après-midi : 13 h à 21 h"))
        // Un jour précis : un poste à noter, pas un réglage
        assertNull(reglageHoraire("Je suis du matin lundi de 7 h à 15 h"))
        assertNull(reglageHoraire("Je suis du matin lundi et mardi"))
    }

    @Test fun lesPostesSuiventLeReglage() {
        val heures = HorairesDefaut.toMutableMap()
        val assistant = Assistant(null, { emptyList() }, { heures }) { c, h -> heures[c] = h }
        val r1 = runBlocking { assistant.demander("Mon poste du matin, c'est de 7 h à 15 h.", emptyList(), maintenant) }!!
        assertTrue(r1.first.dit, r1.first.dit.contains("7 h à 15 h"))
        assertEquals("7 h à 15 h", heures["matin"])
        val r2 = runBlocking { assistant.demander("Je suis du matin lundi et mardi.", r1.second, maintenant) }!!
        val postes = r2.second.filter { it.titre == "Travail (matin)" }
        assertEquals(2, postes.size)
        assertTrue(postes.all { it.quand.toLocalTime() == LocalTime.of(7, 0) && it.fin?.toLocalTime() == LocalTime.of(15, 0) })
    }
}
