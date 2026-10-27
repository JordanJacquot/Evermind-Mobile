package io.github.jordanjacquot.evermind.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder
import java.net.URLEncoder
import java.time.LocalDateTime

/** Audit du 25/10, 25u : une fiche avec « / » ne fait plus planter Evermind, « Compléter » garde ses espaces. */
class RoutesTest {
    /** Comme Uri.decode d'Android (ce qu'utilise la navigation) : les « %XX » seulement, un « + » reste un « + ». */
    private fun decoderCommeAndroid(s: String) = URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")

    @Test
    fun completerGardeLesEspaces() {
        val phrase = "garagiste lundi à 14h + vidange"
        // Contre-épreuve : avec URLEncoder seul, les espaces revenaient en « + », et le « + » de la phrase ne se distinguait plus
        assertEquals("garagiste+lundi+à+14h+++vidange", decoderCommeAndroid(URLEncoder.encode(phrase, "UTF-8")))
        val route = routeCompleter(phrase)
        assertFalse(route.removePrefix("formulaire?phrase=").contains('+'))
        assertEquals(phrase, decoderCommeAndroid(route.removePrefix("formulaire?phrase=")))
    }

    @Test
    fun ficheAvecBarreResteUnSeulMorceau() {
        val route = routeFiche("a/b")
        // Contre-épreuve : l'ancienne adresse « fiche/a/b » avait deux « / » et ne menait à aucun écran (plantage)
        assertEquals(2, "fiche/a/b".count { it == '/' })
        assertEquals(1, route.count { it == '/' })
        assertEquals("a/b", decoderCommeAndroid(route.removePrefix("fiche/")))
    }

    @Test
    fun ficheSurUneFois() {
        val route = routeFiche("2b1f0c4e-1d2a-4c55-9b8e-7f00aa11bb22", LocalDateTime.of(2026, 11, 3, 10, 0))
        assertEquals("fiche/2b1f0c4e-1d2a-4c55-9b8e-7f00aa11bb22?fois=2026-11-03T10%3A00", route)
        assertEquals("2026-11-03T10:00", decoderCommeAndroid(route.substringAfter("fois=")))
    }

    @Test
    fun seulementUnIdentifiantDeRendezVous() {
        assertEquals("2b1f0c4e-1d2a-4c55-9b8e-7f00aa11bb22", ficheValide("2b1f0c4e-1d2a-4c55-9b8e-7f00aa11bb22"))
        assertNull(ficheValide("a/b"))
        assertNull(ficheValide("agenda"+ "?x=1"))
        assertNull(ficheValide(""))
        assertNull(ficheValide(null))
        assertNull(ficheValide("a".repeat(65)))
        assertTrue(ficheValide("ABC-123") != null)
        assertEquals(LocalDateTime.of(2026, 11, 3, 10, 0), foisValide("2026-11-03T10:00"))
        assertNull(foisValide("demain"))
    }
}
