package io.github.jordanjacquot.evermind.comprendre

import io.github.jordanjacquot.evermind.assistant.horairesTravail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * Les corrections du lot 3 d'Evermind PC (« 6 h », « dans 1h30 », « la poste », « X et Y », « 10.11 », « en huit »),
 * avec des phrases que les catalogues du PC n'ont jamais vues. « Maintenant » : samedi 10 octobre 2026 à 18 h 30.
 */
class PhrasesLot3Test {
    private val maintenant = LocalDateTime.of(2026, 10, 10, 18, 30)
    private fun lis(t: String) = lirePhrase(t, maintenant)

    private fun quand(texte: String, attendu: String) {
        val p = lis(texte)
        assertEquals("« $texte » : manque", Manque.RIEN, p.manque)
        assertEquals("« $texte »", attendu, p.quand.toString())
    }

    @Test fun heuresDuPetitMatin() {
        quand("jeudi à 5 h 15 je me réveille pour le marché", "2026-10-15T05:15")
        quand("demain de bonne heure à 4 h pêche", "2026-10-11T04:00")
        assertEquals("pêche", lis("demain de bonne heure à 4 h pêche").titre)
        quand("cette nuit à 3 h 30 tétée", "2026-10-11T03:30")
        quand("je finis à 6 h demain", "2026-10-11T06:00")
        // Sans indice : l'après-midi
        quand("mercredi à 3 h goûter", "2026-10-14T15:00")
        quand("à la poste demain à 4 h", "2026-10-11T16:00")
        // 6 h, ou un départ : on demande
        for (t in listOf("navette pour l'aéroport vendredi à 4 h 45", "à 6 h 15 dimanche", "covoiturage lundi à 5 h", "chaque mardi à 6 h natation")) {
            val p = lis(t)
            assertEquals("« $t »", Manque.MATIN, p.manque)
            assertTrue("« $t » : la question", p.manque.message.startsWith("Le matin ou le soir ?"))
        }
        // Dit clairement : pas de question
        quand("vendredi à 5 h du matin navette", "2026-10-16T05:00")
        quand("vendredi à 6 h du soir apéro", "2026-10-16T18:00")
    }

    @Test fun dansUneHeureEtDemie() {
        quand("dans 1h15 relancer la machine", "2026-10-10T19:45")
        assertEquals("relancer la machine", lis("dans 1h15 relancer la machine").titre)
        quand("dans 2 heures 20 sortir le rôti", "2026-10-10T20:50")
        assertEquals("sortir le rôti", lis("dans 2 heures 20 sortir le rôti").titre)
        quand("dans une heure et quart appeler maman", "2026-10-10T19:45")
        quand("dans 4 heures moins le quart fermer les volets", "2026-10-10T22:15")
        quand("dans 3/4 d'heure bus", "2026-10-10T19:15")
        quand("dans 1 heure et 5 minutes four", "2026-10-10T19:35")
        quand("dans 20 mn thé", "2026-10-10T18:50")
        // Une durée en jours ne change pas
        assertEquals("2026-10-13", lis("dans 3 jours à 10 h banque").jour.toString())
    }

    @Test fun pasDesHorairesDeTravail() {
        for (t in listOf("chercher un colis au bureau de poste lundi matin", "déjeuner de travail mardi midi", "pot d'équipe vendredi soir",
                         "service client lundi après-midi", "match de l'équipe de foot dimanche après-midi"))
            assertNull("« $t » lu comme des horaires de travail", horairesTravail(t, maintenant))
        for (t in listOf("poste du soir mardi", "équipe de nuit jeudi", "je bosse le matin lundi", "je suis de nuit vendredi"))
            assertNotNull("« $t » : horaires pas compris", horairesTravail(t, maintenant))
        // L'heure d'un rendez-vous près de « la poste » est lue comme d'habitude
        quand("pot d'équipe vendredi à 5 h", "2026-10-16T17:00")
    }

    @Test fun jourEtDateQuiNeVontPasEnsemble() {
        val p = lis("mardi 4 novembre à 10 h coiffeur")
        assertEquals("2026-11-03T10:00", p.quand.toString())
        assertEquals("Le 4 novembre est un mercredi, pas un mardi : j'ai pris le mardi 3 novembre. Corrige si ce n'est pas le bon jour.", p.remarque)
        val q = lis("samedi 1er novembre à 14 h brocante")
        assertEquals("2026-10-31T14:00", q.quand.toString())
        assertTrue(q.remarque, q.remarque.startsWith("Le 1er novembre est un dimanche, pas un samedi"))
        // Le bon jour : rien à dire
        assertEquals("", lis("mercredi 4 novembre à 10 h coiffeur").remarque)
    }

    @Test fun plusieursJoursDits() {
        val p = lis("mardi et vendredi à 18 h yoga")
        assertEquals("2026-10-13T18:00", p.quand.toString())
        assertEquals(listOf("2026-10-13", "2026-10-16"), p.jours.map { it.toString() })
        assertEquals("yoga", p.titre)
        val k = lis("les 5 et 12 à 9 h kiné")
        assertEquals(listOf("2026-11-05", "2026-11-12"), k.jours.map { it.toString() })
        val d = lis("le 30 et le 1er déménagement")
        assertEquals(listOf("2026-10-30", "2026-11-01"), d.jours.map { it.toString() })
        // Des jours qui se suivent : un seul événement
        val w = lis("jeudi et vendredi séminaire")
        assertEquals("2026-10-15T00:00", w.quand.toString())
        assertEquals("2026-10-16T00:00", w.fin.toString())
    }

    @Test fun datesAvecUnPoint() {
        quand("le 24.12 à 20 h réveillon", "2026-12-24T20:00")
        quand("le 5-1-2027 à 9 h vaccin", "2027-01-05T09:00")
        quand("à 9.45 lundi garage", "2026-10-12T09:45")
        assertEquals("2026-10-12", lis("acheter 1.5 kg de pommes lundi").jour.toString())
        assertEquals("2026-10-12", lis("rembourser 10.5 euros à Paul lundi").jour.toString())
        assertEquals(Manque.INVALIDE, lis("le 30.02 à 10 h").manque)
    }

    @Test fun enHuitEnQuinze() {
        quand("vendredi en huit à 9 h contrôle technique", "2026-10-23T09:00")
        assertEquals("contrôle technique", lis("vendredi en huit à 9 h contrôle technique").titre)
        quand("mardi en quinze piscine à 10 h", "2026-10-27T10:00")
        quand("demain en quinze à 11 h notaire", "2026-10-25T11:00")
    }
}
