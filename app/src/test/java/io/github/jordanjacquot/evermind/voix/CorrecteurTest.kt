package io.github.jordanjacquot.evermind.voix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * La correction des noms après la dictée. Les phrases « entendues » sont ce que Whisper small a vraiment écrit en
 * écoutant la voix française de Windows (banc d'essai) ; d'autres ne doivent surtout pas changer.
 */
class CorrecteurTest {
    private val correcteur = Correcteur.lire(
        File("src/main/assets/lexique/communes.txt").readLines().asSequence(),
        File("src/main/assets/lexique/noms.txt").readLines().asSequence(),
        File("src/main/assets/lexique/mots.txt").readLines().asSequence(),
    )

    private val corrections = listOf(
        "Rendez-vous chez l'Oftalmologue jeudi à 10h." to "Ophtalmologue",
        "train pour besan son dimanche à 8h." to "Besançon",
        "Osteopathe mercredi à 17h." to "Ostéopathe",
        "Soiré chez Mathilde à Sargemin samedi soir." to "Sarreguemines",
        "Je vais à Hausser voir Grégoire vendredi." to "Auxerre",
        "Rendez-vous chez le cardiologue à Epinal le 20 novembre." to "Épinal",
        "Match à Saint-Etienne samedi à 20h." to "Saint-Étienne",
        "Rendez-vous chez le podologue à Fortback-Mercreudy." to "Forbach",
        "Rendez-vous avec Maître Lefebvre à Rince Judit à 14h." to "Reims",
        "Déjeuner avec saio bone à exan Provence vendredi." to "Aix-en-Provence",
        "Rendez-vous chez l'autorinolaringologiste à l'île mardi." to "oto-rhino-laryngologiste",
        "Course à le clair samedi matin." to "Leclerc",
        "Rendez-vous à la CAF de messe lundi à 10h." to "Metz",
        "Control technique chez Noroto à WAPI jeudi." to "Norauto",
        "Control technique chez Noroto à WAPI jeudi." to "Woippy",
        "Rendez-vous à Pôle emploi à Montigny-les-Mess mardi à 9h." to "Montigny-lès-Metz",
        "Rendez-vous chez le kinésithérapeuthe lundi." to "kinésithérapeute",
        "Je vais à Mets samedi." to "Metz",
        "Rendez-vous chez le dermato logue vendredi." to "dermatologue",
        "Réunion à Grenoble-Mardi à 10h." to "Grenoble mardi",
        "Rendez-vous chez Lurologue à Nantes." to "l'urologue",
    )

    private val intactes = listOf(
        "Je vais à la messe dimanche.",
        "Rendez-vous chez le dentiste mardi à 15h.",
        "Je vais à la piscine samedi.",
        "Week-end à l'île de Ré.",
        "Réunion sur le projet lundi à 10h.",
        "Rappelle-moi de sortir les poubelles.",
        "Mets le foot à 20h.",
        "Dîner avec mes parents dimanche.",
        "Je vais à Paris demain.",
        "Anniversaire de Léa le 5 mars.",
        "Courses à Auchan samedi.",
        "J'ai rendez-vous à la banque à 9h.",
        "Je vais voir ma mère à l'hôpital.",
        "Rendez-vous au garage lundi.",
        "Je vais au cinéma avec Tom.",
        "Va chez le coiffeur demain.",
        "Rendez-vous à Nancy jeudi.",
        "Soirée chez Max samedi.",
        "Décale le dentiste à jeudi même heure.",
        "Supprime les raids de novembre.",
        "Qu'est-ce que j'ai demain ?",
        "Je suis du matin lundi et mardi.",
        "Ajoute Paul à la soirée.",
        "Le foot c'est au stade.",
        "Préviens-moi la veille.",
        "Rendez-vous à la gare à 8h.",
        "Pique-nique au parc dimanche.",
        "Je vais à la plage avec Julie.",
        "Rendez-vous à la mairie pour le mariage.",
        "Je travaille à la maison demain.",
        "Va à la boulangerie.",
        "Appeler mon frère ce soir.",
        "Mets un rappel pour le loyer.",
        "Concert à Montpellier le 3 décembre.",
        "Week-end à Annecy avec Julie.",
        "Réunion à Strasbourg mardi à 14h.",
        "Rendez-vous chez le psychiatre lundi à 9h.",
        "Anniversaire de mamie samedi.",
        "Je vais à Lyon en train.",
        "Rendez-vous à 10h sur place.",
        "Cours de piano mercredi.",
    )

    @Test fun corrigeLesNoms() {
        val ratees = corrections.filter { (entendu, attendu) -> !correcteur.corriger(entendu).contains(attendu) }
            .map { (entendu, attendu) -> "« $entendu » -> « ${correcteur.corriger(entendu)} » (attendu : $attendu)" }
        val abimees = intactes.filter { correcteur.corriger(it) != it }.map { "« $it » -> « ${correcteur.corriger(it)} »" }
        println("Corrigées : ${corrections.size - ratees.size} / ${corrections.size} ; intactes : ${intactes.size - abimees.size} / ${intactes.size}")
        (ratees + abimees).forEach { println(it) }
        assertTrue((ratees + abimees).joinToString("\n"), ratees.isEmpty() && abimees.isEmpty())
    }

    /**
     * Le banc d'essai (EVERMIND_BANC : « phrase avec [Noms] | ce que Whisper a écrit » par ligne) : combien de noms
     * sont bien écrits avant et après la correction.
     */
    @Test fun banc() {
        val f = System.getenv("EVERMIND_BANC")?.let { File(it) }
        org.junit.Assume.assumeTrue(f != null && f.exists())
        var avant = 0
        var apres = 0
        var total = 0
        for (ligne in f!!.readLines(Charsets.UTF_8).map { it.removePrefix("﻿") }.filter { '|' in it }) {
            val (phrase, entendu) = ligne.split('|', limit = 2).map { it.trim() }
            val corrige = correcteur.corriger(entendu)
            val noms = Regex("\\[([^\\]]+)]").findAll(phrase).map { it.groupValues[1] }.toList()
            for (n in noms) {
                total++
                if (entendu.contains(n, ignoreCase = true)) avant++
                if (corrige.contains(n, ignoreCase = true)) apres++
            }
            println((if (noms.all { corrige.contains(it, ignoreCase = true) }) "ok    " else "RATÉ  ") + "$entendu -> $corrige   $noms")
        }
        println("Noms bien écrits : avant $avant / $total, après $apres / $total")
    }

    @Test fun lesMotsDeLAgendaComptent() {
        val perso = listOf("Docteur Lefèvre", "Ostéo Maëlys Ferreira")
        assertEquals("Rendez-vous chez le docteur Lefèvre jeudi.", correcteur.corriger("Rendez-vous chez le docteur Lefebvre jeudi.", perso))
        assertEquals("Anniversaire de Maëlys samedi.", correcteur.corriger("Anniversaire de Maelys samedi.", perso))
    }
}
