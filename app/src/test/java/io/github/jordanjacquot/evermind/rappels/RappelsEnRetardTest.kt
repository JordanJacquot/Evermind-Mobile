package io.github.jordanjacquot.evermind.rappels

import io.github.jordanjacquot.evermind.donnees.RendezVous
import io.github.jordanjacquot.evermind.donnees.Sorte
import io.github.jordanjacquot.evermind.donnees.reglagesSansAndroid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** Audit du 25/10, 25t : un rappel manqué n'est plus perdu, « Dans 10 min » survit à un redémarrage. */
class RappelsEnRetardTest {
    private val r = reglagesSansAndroid()
    private val medecin = RendezVous(titre = "Médecin", quand = LocalDateTime.of(2026, 11, 3, 10, 0), rappels = listOf("veille", "matin", "m60"))

    /** Ce que faisait passer() avant : au plus 15 minutes en arrière, quel que soit le rendez-vous. */
    private fun ancienCalcul(tous: List<RendezVous>, dernier: LocalDateTime?, maintenant: LocalDateTime) =
        rappelsEntre(tous, listOfNotNull(dernier, maintenant.minusMinutes(15)).max(), maintenant, r)

    @Test
    fun telephoneRedemarreLaNuitDeverrouilleApresLeRappel() {
        // Dernier passage la veille à 20 h (le rappel de la veille) ; téléphone redémarré la nuit, déverrouillé à 9 h 40
        val dernier = LocalDateTime.of(2026, 11, 2, 20, 0)
        val maintenant = LocalDateTime.of(2026, 11, 3, 9, 40)
        val dejaDonnes = setOf("${medecin.id}|${medecin.quand}|veille")
        // Contre-épreuve : l'ancien calcul ne donnait rien (le rappel de 8 h et celui de 9 h étaient perdus)
        assertTrue(ancienCalcul(listOf(medecin), dernier, maintenant).isEmpty())
        val dus = rappelsADonner(listOf(medecin), dernier, maintenant, r, dejaDonnes)
        assertEquals(1, dus.size)
        assertEquals("m60", dus[0].rappel.code)
        assertTrue(dus[0].enRetard)
        // le matin et l'heure avant : un seul rappel, mais les deux comptés comme donnés
        assertEquals(setOf("${medecin.id}|${medecin.quand}|matin", "${medecin.id}|${medecin.quand}|m60"), dus[0].cles.toSet())
        assertEquals("dans 20 min", texteDans(dus[0].rappel.fois, maintenant, false))
    }

    @Test
    fun rendezVousDejaCommenceRienDeVieux() {
        val dernier = LocalDateTime.of(2026, 11, 2, 20, 0)
        val maintenant = LocalDateTime.of(2026, 11, 3, 10, 30)
        assertTrue(rappelsADonner(listOf(medecin), dernier, maintenant, r, emptySet()).isEmpty())
        // (et à 9 h 59, juste avant, il est donné)
        assertEquals(1, rappelsADonner(listOf(medecin), dernier, maintenant.withHour(9).withMinute(59), r, emptySet()).size)
    }

    @Test
    fun rappelALHeureNEstPasEnRetard() {
        val maintenant = LocalDateTime.of(2026, 11, 3, 9, 0)
        val dus = rappelsADonner(listOf(medecin), maintenant.minusMinutes(5), maintenant, r, emptySet())
        assertEquals(1, dus.size)
        assertFalse(dus[0].enRetard)
        // pas deux fois
        assertTrue(rappelsADonner(listOf(medecin), maintenant.minusMinutes(5), maintenant, r, dus[0].cles.toSet()).isEmpty())
    }

    @Test
    fun premierLancementSeulementLes15DernieresMinutes() {
        // (pas de dernier passage : premier lancement ; rien de rattrapé plus loin que 15 min)
        val maintenant = LocalDateTime.of(2026, 11, 3, 9, 40)
        assertTrue(rappelsADonner(listOf(medecin), null, maintenant, r, emptySet()).isEmpty())
        assertEquals(1, rappelsADonner(listOf(medecin), null, LocalDateTime.of(2026, 11, 3, 9, 10), r, emptySet()).size)
    }

    @Test
    fun journeeEntiereRattrapeeDansLaJournee() {
        val anniv = RendezVous(titre = "Anniversaire de Léa", sorte = Sorte.ANNIV, quand = LocalDateTime.of(2026, 11, 3, 0, 0), journee = true, rappels = listOf("matin"))
        val dernier = LocalDateTime.of(2026, 11, 2, 22, 0)
        assertEquals(1, rappelsADonner(listOf(anniv), dernier, LocalDateTime.of(2026, 11, 3, 11, 0), r, emptySet()).size)
        assertTrue(rappelsADonner(listOf(anniv), dernier, LocalDateTime.of(2026, 11, 4, 9, 0), r, emptySet()).isEmpty())
    }

    @Test
    fun heureReculeeNiDoublonNiOubli() {
        // « dernier » dans le futur (l'heure du téléphone a été reculée) : le rappel de 9 h est quand même donné
        val maintenant = LocalDateTime.of(2026, 11, 3, 9, 2)
        assertEquals(1, rappelsADonner(listOf(medecin), maintenant.plusHours(2), maintenant, r, emptySet()).size)
    }

    @Test
    fun longueCoupureAuPlusHuitJours() {
        val loin = RendezVous(titre = "Notaire", quand = LocalDateTime.of(2026, 11, 20, 10, 0), rappels = listOf("m10080"))
        // rappel le 13 à 10 h ; téléphone éteint du 1er au 15 : rattrapé (le notaire n'a pas encore eu lieu)
        assertEquals(1, rappelsADonner(listOf(loin), LocalDateTime.of(2026, 11, 1, 0, 0), LocalDateTime.of(2026, 11, 15, 9, 0), r, emptySet()).size)
        // téléphone éteint jusqu'au 22 : le rendez-vous est passé, rien
        assertTrue(rappelsADonner(listOf(loin), LocalDateTime.of(2026, 11, 1, 0, 0), LocalDateTime.of(2026, 11, 22, 9, 0), r, emptySet()).isEmpty())
    }

    @Test
    fun reportLuEtEcrit() {
        val rp = Report("2b1f0c4e-1d2a-4c55-9b8e-7f00aa11bb22", "2026-11-03T10:00", Instant.ofEpochMilli(1_793_000_000_000))
        assertEquals(rp, Report.lire(rp.enTexte()))
        assertNull(Report.lire("abîmé"))
        assertNull(Report.lire("id|fois|pas-un-nombre"))
        assertNull(Report.lire("|fois|12"))
    }

    @Test
    fun reportApresRedemarrage() {
        val maintenant = Instant.parse("2026-11-03T08:30:00Z")
        val passe = Report("a", "2026-11-03T10:00", maintenant.minusSeconds(120))        // l'heure est passée pendant le redémarrage
        val avenir = Report("b", "2026-11-03T10:00", maintenant.plusSeconds(300))       // à remettre sur une alarme
        val vieux = Report("c", "2026-11-01T10:00", maintenant.minus(Duration.ofDays(2))) // trop vieux : oublié
        val (dus, aVenir) = trierReports(setOf(passe.enTexte(), avenir.enTexte(), vieux.enTexte(), "abîmé"), maintenant)
        assertEquals(listOf(passe), dus)
        assertEquals(listOf(avenir), aVenir)
    }

    @Test
    fun dansDixMinutesLaNuitDuChangementDHeure() {
        // 25 octobre 2026, 2 h 55 heure d'été (00:55 UTC) : à 3 h, il est 2 h
        val paris = ZoneId.of("Europe/Paris")
        val demande = Instant.parse("2026-10-25T00:55:00Z")
        // Contre-épreuve : l'ancien calcul (heure du téléphone + 10 min) revenait 70 minutes plus tard
        val ancien = LocalDateTime.ofInstant(demande, paris).plusMinutes(10).atZone(paris).toInstant()
        assertEquals(70, Duration.between(demande, ancien).toMinutes())
        // Le report est compté en temps réel
        val rp = Report("a", "x", demande.plusSeconds(10 * 60))
        assertEquals(10, Duration.between(demande, Report.lire(rp.enTexte())!!.quand).toMinutes())
        assertNotEquals(ancien, rp.quand)
    }

    @Test
    fun boutonAutoriser() {
        // Android 13 : la première fois et après un seul refus, la fenêtre d'Android
        assertEquals(Autoriser.DEMANDER, commentAutoriser(33, dejaDemandee = false, androidExplique = false))
        assertEquals(Autoriser.DEMANDER, commentAutoriser(34, dejaDemandee = true, androidExplique = true))
        // après deux refus (Android n'explique plus et ne montre plus rien) : les réglages du téléphone
        assertEquals(Autoriser.REGLAGES, commentAutoriser(34, dejaDemandee = true, androidExplique = false))
        // avant Android 13 : pas de demande possible, les réglages
        assertEquals(Autoriser.REGLAGES, commentAutoriser(31, dejaDemandee = false, androidExplique = false))
        assertTrue(refusSansFenetre(40))
        assertFalse(refusSansFenetre(2_500))
    }
}
