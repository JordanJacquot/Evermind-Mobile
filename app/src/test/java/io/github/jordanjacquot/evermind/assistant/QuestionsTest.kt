package io.github.jordanjacquot.evermind.assistant

import io.github.jordanjacquot.evermind.comprendre.Repetition
import io.github.jordanjacquot.evermind.donnees.APreparer
import io.github.jordanjacquot.evermind.donnees.RendezVous
import io.github.jordanjacquot.evermind.donnees.Sorte
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * L'action « question » du modèle : il dit seulement ce qui est demandé et sur quoi, Evermind calcule la réponse avec
 * les règles (l'âge, la date, ce qu'il faut prendre, le programme d'un jour), et rien ne change dans l'agenda.
 */
class QuestionsTest {
    // Un mercredi à 14 h
    private val maintenant = LocalDateTime.of(2026, 10, 14, 14, 0)
    private val dentiste = RendezVous(titre = "Dentiste", sorte = Sorte.SANTE, quand = maintenant.plusDays(1).withHour(15),
        lieu = "12 rue des Lilas, Lyon", aPreparer = listOf(APreparer("Carte Vitale"), APreparer("Radios")))
    private val foot = RendezVous(titre = "Foot", sorte = Sorte.SPORT, quand = maintenant.plusDays(1).withHour(19), avec = listOf("Tom", "Max"))
    private val lea = RendezVous(titre = "Anniversaire de Léa", sorte = Sorte.ANNIV, quand = LocalDateTime.of(2026, 10, 20, 0, 0), journee = true,
        repetition = Repetition("an", jour = 20, mois = 10).versJson(), naissance = 2002)
    private val codes = mapOf("eden" to dentiste.id, "efoo" to foot.id, "elea" to lea.id)

    private fun demander(json: String): Pair<Resultat, List<RendezVous>> {
        val agenda = AgendaIA(listOf(dentiste, foot, lea), maintenant)
        val res = Actions(agenda, { emptyList() }, mutableListOf()).appliquer(ReponseIA.lire(json)!!, codes, "")
        return res to agenda.liste.toList()
    }

    @Test fun lAgeEstCalculeParEvermind() {
        // (même si le modèle a écrit une réponse fausse, c'est celle d'Evermind qui compte)
        val (res, apres) = demander("""{"actions":[{"action":"question","sujet":"age","id":"elea"}],"reponse":"Léa aura 30 ans."}""")
        assertEquals("Léa aura 24 ans mardi 20 octobre.", res.dit)
        assertFalse(res.change)
        assertEquals(listOf(dentiste, foot, lea), apres)
    }

    @Test fun quandOuEtCeQuIlFautPrendre() {
        demander("""{"actions":[{"action":"question","sujet":"quand","id":"eden"}],"reponse":""}""").first.dit.let { assertTrue(it, it == "Dentiste : jeudi 15 octobre à 15 h.") }
        assertEquals("Dentiste : 12 rue des Lilas, Lyon.", demander("""{"actions":[{"action":"question","sujet":"ou","id":"[eden]"}],"reponse":""}""").first.dit)
        assertEquals("Pour Dentiste, prends : Carte Vitale et Radios.", demander("""{"actions":[{"action":"question","sujet":"prendre","id":"eden"}],"reponse":""}""").first.dit)
        assertEquals("Foot, avec Tom et Max.", demander("""{"actions":[{"action":"question","sujet":"qui","id":"efoo"}],"reponse":""}""").first.dit)
    }

    @Test fun leProgrammeDeDemain() {
        assertEquals("Demain : Dentiste à 15 h, Foot à 19 h.", demander("""{"actions":[{"action":"question","sujet":"jour","periode":"demain"}],"reponse":""}""").first.dit)
        assertEquals("Demain soir, tu as déjà Foot à 19 h.", demander("""{"actions":[{"action":"question","sujet":"libre","periode":"demain soir"}],"reponse":""}""").first.dit)
        demander("""{"actions":[{"action":"question","sujet":"prochain"}],"reponse":""}""").first.dit.let { assertTrue(it, it.startsWith("Ton prochain rendez-vous : Dentiste")) }
    }

    @Test fun lAncienneReponseMarcheToujours() {
        val (res, _) = demander("""{"actions":[],"reponse":"Léa aura 24 ans le 20 octobre."}""")
        assertEquals("Léa aura 24 ans le 20 octobre.", res.dit)
    }
}
