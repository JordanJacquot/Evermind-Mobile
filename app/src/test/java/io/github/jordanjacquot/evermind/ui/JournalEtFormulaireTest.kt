package io.github.jordanjacquot.evermind.ui

import io.github.jordanjacquot.evermind.assistant.Assistant
import io.github.jordanjacquot.evermind.comprendre.Manque
import io.github.jordanjacquot.evermind.comprendre.Repetition
import io.github.jordanjacquot.evermind.comprendre.lirePhrase
import io.github.jordanjacquot.evermind.comprendre.versRendezVous
import io.github.jordanjacquot.evermind.donnees.DepotRendezVous
import io.github.jordanjacquot.evermind.donnees.FausseBase
import io.github.jordanjacquot.evermind.donnees.RendezVous
import io.github.jordanjacquot.evermind.donnees.Sorte
import io.github.jordanjacquot.evermind.donnees.attendre
import io.github.jordanjacquot.evermind.ui.agenda.JournalAssistant
import io.github.jordanjacquot.evermind.ui.formulaire.FormulaireViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** L'assistant et le formulaire écrivent dans la base sans rien perdre (constats 12, 13, 25q et 25r de l'audit du 25/10). */
class JournalEtFormulaireTest {
    private val maintenant = LocalDateTime.of(2026, 10, 10, 18, 30)
    private fun titres(base: FausseBase) = base.donnees.value.values.map { it.titre }.sorted()

    /** « annule » dit à l'assistant défait vraiment sa dernière action dans la base. */
    @Test fun annuleDefaitDansLaBase() = runBlocking {
        val base = FausseBase()
        val depot = DepotRendezVous(base)
        val journal = JournalAssistant(depot)
        val a = Assistant(null, { emptyList() })
        val recu1 = depot.liste()
        val r1 = a.demander("ajoute dentiste mardi à 15 h", recu1, maintenant)!!
        journal.appliquer(recu1, r1.first, r1.second)
        assertEquals(listOf("Dentiste"), titres(base))
        val recu2 = depot.liste()
        val r2 = a.demander("annule", recu2, maintenant.plusMinutes(1))!!
        assertTrue("« annule » est un changement à enregistrer", r2.first.change)
        journal.appliquer(recu2, r2.first, r2.second)
        assertEquals(emptyList<String>(), titres(base))
    }

    /** Un rendez-vous ajouté à la main pendant que l'assistant réfléchit reste ; « annule » ne l'enlève pas non plus. */
    @Test fun ajoutPendantLaReflexionGarde() = runBlocking {
        val base = FausseBase()
        val depot = DepotRendezVous(base)
        val journal = JournalAssistant(depot)
        val a = Assistant(null, { emptyList() })
        val recu = depot.liste()
        val r = a.demander("ajoute dentiste mardi à 15 h", recu, maintenant)!!
        // (pendant ce temps, avec « + »)
        depot.enregistrer(RendezVous(titre = "Coiffeur", quand = maintenant.plusDays(1)))
        journal.appliquer(recu, r.first, r.second)
        assertEquals(listOf("Coiffeur", "Dentiste"), titres(base))
        // « annule » : seul le dentiste part
        val recu2 = depot.liste()
        val r2 = a.demander("annule", recu2, maintenant.plusMinutes(1))!!
        journal.appliquer(recu2, r2.first, r2.second)
        assertEquals(listOf("Coiffeur"), titres(base))
    }

    /** Le bouton « Annuler » aussi ne défait que l'action de l'assistant. */
    @Test fun boutonAnnulerGardeLeReste() = runBlocking {
        val base = FausseBase(listOf(RendezVous(titre = "Foot", quand = maintenant.plusDays(2))))
        val depot = DepotRendezVous(base)
        val journal = JournalAssistant(depot)
        val a = Assistant(null, { emptyList() })
        val recu = depot.liste()
        val r = a.demander("supprime le foot", recu, maintenant)!!
        journal.appliquer(recu, r.first, r.second)
        assertEquals(emptyList<String>(), titres(base))
        depot.enregistrer(RendezVous(titre = "Coiffeur", quand = maintenant.plusDays(1)))
        assertNotNull(a.annulerDerniere())
        assertTrue(journal.defaire())
        assertEquals(listOf("Coiffeur", "Foot"), titres(base))
    }

    /** « C'est fait » dit à l'assistant sur une chose qui revient : seulement la fois du jour. */
    @Test fun cEstFaitDitALAssistant() = runBlocking {
        val mardi = LocalDateTime.of(2026, 10, 13, 19, 10)
        val poubelles = RendezVous(titre = "Poubelles", sorte = Sorte.A_FAIRE, quand = LocalDateTime.of(2026, 10, 6, 19, 0),
            repetition = Repetition("semaine", jour = 2).versJson(), rappels = listOf("m0"), faitPour = listOf("2026-10-06"))
        val a = Assistant(null, { emptyList() })
        val r = a.demander("les poubelles c'est fait", listOf(poubelles), mardi)!!
        val apres = r.second.single()
        assertEquals(r.first.ecrit, listOf("2026-10-06", "2026-10-13"), apres.faitPour)
        assertEquals(false, apres.fait)
        assertNotNull(apres.repetition)
    }

    /** Modifier des vacances (du 20 au 27) dans le formulaire garde leur fin. */
    @Test fun leFormulaireGardeLaFin() {
        val debut = LocalDate.now().plusDays(20)
        val vacances = RendezVous(titre = "Vacances", quand = debut.atStartOfDay(), journee = true, fin = debut.plusDays(7).atStartOfDay())
        val base = FausseBase(listOf(vacances))
        val vm = FormulaireViewModel(DepotRendezVous(base), vacances.id, null)
        attendre { vm.brouillon.value.titre == "Vacances" }
        vm.changer { it.copy(lieu = "Bretagne") }
        vm.enregistrer {}
        attendre { base.donnees.value.getValue(vacances.id).lieu == "Bretagne" }
        assertEquals(debut.plusDays(7).atStartOfDay(), base.donnees.value.getValue(vacances.id).fin)
        // déplacées de 3 jours : elles gardent leur durée
        vm.changerJour(debut.plusDays(3))
        vm.enregistrer {}
        attendre { base.donnees.value.getValue(vacances.id).quand == debut.plusDays(3).atStartOfDay() }
        assertEquals(debut.plusDays(10).atStartOfDay(), base.donnees.value.getValue(vacances.id).fin)
        // un rendez-vous sur plusieurs jours avec ses heures (lundi 9 h à mercredi 18 h)
        val stage = RendezVous(titre = "Stage", quand = debut.atTime(9, 0), fin = debut.plusDays(2).atTime(18, 0))
        val base2 = FausseBase(listOf(stage))
        val vm2 = FormulaireViewModel(DepotRendezVous(base2), stage.id, null)
        attendre { vm2.brouillon.value.titre == "Stage" }
        vm2.changer { it.copy(titre = "Stage photo") }
        vm2.enregistrer {}
        attendre { base2.donnees.value.getValue(stage.id).titre == "Stage photo" }
        assertEquals(debut.plusDays(2).atTime(18, 0), base2.donnees.value.getValue(stage.id).fin)
    }

    /** Une soirée de 20 h à 2 h s'enregistre, et finit le lendemain. */
    @Test fun soireeQuiFinitLeLendemain() {
        val samedi = LocalDate.now().plusDays(5)
        val soiree = RendezVous(titre = "Soirée", quand = samedi.atTime(20, 0), fin = samedi.plusDays(1).atTime(2, 0))
        val base = FausseBase(listOf(soiree))
        val vm = FormulaireViewModel(DepotRendezVous(base), soiree.id, null)
        attendre { vm.brouillon.value.titre == "Soirée" }
        assertTrue(vm.brouillon.value.peutEnregistrer)
        assertEquals(null, vm.brouillon.value.jourFin)
        vm.changer { it.copy(titre = "Soirée chez Max") }
        vm.enregistrer {}
        attendre { base.donnees.value.getValue(soiree.id).titre == "Soirée chez Max" }
        assertEquals(samedi.plusDays(1).atTime(2, 0), base.donnees.value.getValue(soiree.id).fin)
        // une nouvelle, écrite à la main : de 22 h à 1 h
        val base2 = FausseBase()
        val vm2 = FormulaireViewModel(DepotRendezVous(base2), null, samedi)
        vm2.changer { it.copy(titre = "Concert", heure = LocalTime.of(22, 0), fin = LocalTime.of(1, 0)) }
        assertTrue(vm2.brouillon.value.peutEnregistrer)
        vm2.enregistrer {}
        attendre { base2.donnees.value.isNotEmpty() }
        assertEquals(samedi.plusDays(1).atTime(1, 0), base2.donnees.value.values.single().fin)
        // une fin à la même heure que le début, le même jour : refusée
        vm2.changer { it.copy(fin = LocalTime.of(22, 0)) }
        assertEquals(false, vm2.brouillon.value.peutEnregistrer)
    }

    /** « Compléter » une phrase qui revient garde la répétition (et le jour : un mardi). */
    @Test fun completerGardeLaRepetition() {
        val phrase = "chaque mardi à 6 h natation"
        assertEquals(Manque.MATIN, lirePhrase(phrase).manque)
        val base = FausseBase()
        val vm = FormulaireViewModel(DepotRendezVous(base), null, null, phrase)
        assertEquals(DayOfWeek.TUESDAY, vm.brouillon.value.jour.dayOfWeek)
        vm.changer { it.copy(heure = LocalTime.of(6, 0)) }
        vm.enregistrer {}
        attendre { base.donnees.value.isNotEmpty() }
        val r = base.donnees.value.values.single()
        assertEquals(Repetition("semaine", jour = 2), Repetition.depuisJson(r.repetition))
        assertEquals(DayOfWeek.TUESDAY, r.quand.dayOfWeek)
        assertEquals(LocalTime.of(6, 0), r.quand.toLocalTime())
        assertTrue(!r.quand.toLocalDate().isBefore(LocalDate.now()))
        // la même phrase, complète : la même répétition qu'avant
        assertEquals(Repetition("semaine", jour = 2), lirePhrase("chaque mardi à 6 h du matin natation").repetition)
        // une phrase unique : rien ne revient
        val vm2 = FormulaireViewModel(DepotRendezVous(FausseBase()), null, null, "mardi à 6 h natation")
        assertEquals(null, vm2.brouillon.value.repetition)
        assertTrue(lirePhrase("vacances du 20 au 27 octobre", maintenant).versRendezVous().isNotEmpty())
    }

    /**
     * Un réveil par une alarme (ou le redémarrage du téléphone) ne passe que par Application.onCreate : rien n'y charge la
     * voix, l'IA, le lexique ni la mise à jour (ils attendent l'écran, preparerEcran).
     */
    @Test fun leReveilParUneAlarmeNeChargeRien() {
        val source = File("src/main/java/io/github/jordanjacquot/evermind/EvermindApp.kt").readText()
        val onCreate = Regex("override fun onCreate\\(\\) \\{(.*?)\\n    \\}", RegexOption.DOT_MATCHES_ALL).find(source)!!.groupValues[1]
        for (lourd in listOf("voix", "cerveau", "maj.", "Correcteur", "preparerEcran")) assertTrue("onCreate : $lourd", !onCreate.contains(lourd))
        val activite = File("src/main/java/io/github/jordanjacquot/evermind/MainActivity.kt").readText()
        assertTrue(activite.contains("app.preparerEcran()"))
    }
}
