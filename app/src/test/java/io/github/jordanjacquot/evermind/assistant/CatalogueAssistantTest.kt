package io.github.jordanjacquot.evermind.assistant

import io.github.jordanjacquot.evermind.comprendre.Repetition
import io.github.jordanjacquot.evermind.donnees.APreparer
import io.github.jordanjacquot.evermind.donnees.RendezVous
import io.github.jordanjacquot.evermind.donnees.Sorte
import io.github.jordanjacquot.evermind.donnees.laRepetition
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/**
 * Le grand catalogue de demandes à l'assistant d'Evermind PC (catalogue-assistant.ps1), en Kotlin : chaque demande part
 * de l'agenda d'exemple, passe par l'assistant du téléphone (consignes, IA, contrôles, actions), et on vérifie
 * l'agenda obtenu. L'IA tourne avec le llama-server d'Evermind PC ; sans lui, le test est sauté.
 *   EVERMIND_LLAMA_SERVEUR : llama-server.exe ; EVERMIND_LLAMA_MODELE : le modèle (.gguf) ;
 *   EVERMIND_LLAMA_OPTIONS : options en plus (« --presence-penalty 1.5 ») ; EVERMIND_FILTRE : une famille seulement.
 */
class CatalogueAssistantTest {
    private val fr = Locale.FRENCH
    // (EVERMIND_MAINTENANT : rejouer des phrases écrites un autre jour, « le dentiste vendredi 16 »)
    private val maintenant = (System.getenv("EVERMIND_MAINTENANT")?.let { LocalDateTime.parse(it) } ?: LocalDateTime.now()).withSecond(0).withNano(0)
    private val auj = maintenant.toLocalDate()
    private val dentiste = auj.plusDays(3)
    private val foot = auj.plusDays(4)
    private val soiree = auj.plusDays(6)
    private val lea = auj.plusDays(1)
    private val moisProchain = auj.withDayOfMonth(1).plusMonths(1)
    private val anSuivant = auj.year + 1
    private fun nomMois(d: LocalDate) = d.format(DateTimeFormatter.ofPattern("MMMM", fr))
    private fun nomJour(d: LocalDate) = d.format(DateTimeFormatter.ofPattern("EEEE", fr))

    /** L'agenda d'exemple des tests d'Evermind PC (New-DonneesExemple), par rapport à aujourd'hui. */
    private fun exemple(): List<RendezVous> {
        val n = maintenant
        fun rv(titre: String, sorte: Sorte, quand: LocalDateTime) = RendezVous(titre = titre, sorte = sorte, quand = quand)
        return listOf(
            rv("Dentiste", Sorte.SANTE, auj.plusDays(3).atTime(15, 0)).copy(
                fin = auj.plusDays(3).atTime(15, 30), lieu = "12 rue des Lilas, Lyon", avec = listOf("Maman"), important = true,
                notes = "Code de la porte : 4521B. Deuxième étage.", rappels = listOf("veille", "m60"),
                aPreparer = listOf(APreparer("Carte Vitale", true), APreparer("Ordonnance", false)),
            ),
            rv("Appeler maman", Sorte.A_FAIRE, n.plusHours(2).withMinute(0)),
            rv("Raid avec les potes", Sorte.JEU, auj.plusDays(1).atTime(21, 30)).copy(repetition = Repetition("semaine", jour = auj.plusDays(1).dayOfWeek.value % 7).versJson()),
            rv("Payer le loyer", Sorte.A_FAIRE, auj.plusDays(5).atTime(10, 0)).copy(repetition = Repetition("mois", jour = auj.plusDays(5).dayOfMonth).versJson()),
            rv("Anniversaire de Léa", Sorte.ANNIV, auj.plusDays(1).atStartOfDay()).copy(
                journee = true, naissance = n.year - 24, notes = "Idée cadeau : le nouveau Zelda",
                repetition = Repetition("an", jour = auj.plusDays(1).dayOfMonth, mois = auj.plusDays(1).monthValue).versJson(),
            ),
            rv("Soirée chez Max", Sorte.SORTIE, auj.plusDays(6).atTime(21, 0)).copy(avec = listOf("Max", "Inès", "Tom"), lien = "https://discord.gg/abc123"),
            rv("Foot", Sorte.SPORT, auj.plusDays(4).atTime(19, 0)),
            rv("Coiffeur", Sorte.RDV, n.minusDays(2)),
        )
    }

    /** Ce que la vérification d'un cas peut regarder : l'agenda obtenu et ce qu'Evermind a dit. */
    private inner class Fin(val agenda: List<RendezVous>, val dit: String) {
        fun rv(titre: String): List<RendezVous> {
            val rx = Regex("^" + titre.split('*').joinToString(".*") { Regex.escape(it) } + "$", RegexOption.IGNORE_CASE)
            return agenda.filter { rx.matches(it.titre) }
        }
        fun prochain(jour: DayOfWeek): LocalDate { var e = (jour.value - auj.dayOfWeek.value + 7) % 7; if (e == 0) e = 7; return auj.plusDays(e.toLong()) }
        fun le(j: Int, m: Int): LocalDate { val d = LocalDate.of(auj.year, m, j); return if (d.isBefore(auj)) d.plusYears(1) else d }
        fun jour(r: RendezVous?, jour: LocalDate, heure: String?): Boolean {
            if (r == null || r.quand.toLocalDate() != jour) return false
            return if (heure == null) r.journee else r.quand.toLocalTime() == LocalTime.parse(heure)
        }
        val taille get() = agenda.size
        fun entre(a: LocalDate, b: LocalDate) = AgendaIA(agenda, maintenant).entre(a.atStartOfDay(), b.atStartOfDay())
    }

    private class Cas(val famille: String, val phrases: List<String>, val verif: Fin.() -> Boolean)

    private fun cas(famille: String, vararg phrases: String, verif: Fin.() -> Boolean) = Cas(famille, phrases.toList(), verif)

    private val LUN = DayOfWeek.MONDAY
    private val MAR = DayOfWeek.TUESDAY
    private val JEU = DayOfWeek.THURSDAY
    private val VEN = DayOfWeek.FRIDAY
    private val SAM = DayOfWeek.SATURDAY

    private fun Fin.rep(r: RendezVous?): Repetition? = r?.laRepetition

    private val catalogue: List<Cas> get() = listOf(
        // --- Horaires de travail en postes
        cas("horaires", "Je suis du matin lundi et mardi.") { val r = rv("Travail (matin)"); r.size == 2 && r.count { it.quand.hour == 6 && it.fin?.hour == 14 } == 2 },
        cas("horaires", "Mets mes horaires de boulot : lundi matin, mardi journée et mercredi soir.") { rv("Travail (matin)").size == 1 && rv("Travail (journée)").size == 1 && rv("Travail (soir)").size == 1 && rv("Travail (soir)")[0].quand.hour == 14 },
        cas("horaires", "Je travaille de nuit toute la semaine prochaine.") { val r = rv("Travail (nuit)"); r.size == 5 && r.count { it.quand.hour == 22 && it.fin!!.toLocalDate().isAfter(it.quand.toLocalDate()) } == 5 },
        cas("horaires", "Boulot mardi de 6 h à 14 h.") { val r = rv("Boulot"); r.size == 1 && r[0].quand.hour == 6 && r[0].fin?.hour == 14 },
        cas("horaires", "Je suis du soir tous les jeudis.") { val r = rv("Travail (soir)"); r.size == 1 && rep(r[0])?.type == "semaine" && rep(r[0])?.jour == 4 },
        // --- Créer : jours, heures, façons de le dire
        cas("créer", "Mets-moi un appel avec Paul le ${dentiste.dayOfMonth} ${nomMois(dentiste)} à 15 h.") { taille == 9 && dit.contains("Attention, tu as déjà Dentiste") },
        cas("créer", "Ajoute un rendez-vous chez le garagiste vendredi à 10 h.") { jour(rv("*garag*").firstOrNull(), prochain(VEN), "10:00") },
        cas("créer", "Mets-moi coiffeur mardi à 14 h 30.") { rv("Coiffeur").count { jour(it, prochain(MAR), "14:30") } == 1 },
        cas("créer", "Note que j'ai piscine demain à 18 h.") { jour(rv("*iscine*").firstOrNull(), auj.plusDays(1), "18:00") },
        cas("créer", "Rappelle-moi d'appeler le plombier demain à 9 h.") { jour(rv("*plombier*").firstOrNull(), auj.plusDays(1), "09:00") },
        cas("créer", "Il faut que je pense à acheter du pain demain.") { jour(rv("*pain*").firstOrNull(), auj.plusDays(1), null) },
        cas("créer", "Crée un événement cinéma avec Léa samedi prochain à 20 h 30.") { val r = rv("Cin*").firstOrNull(); jour(r, prochain(SAM), "20:30") && "Léa" in r!!.avec },
        cas("créer", "J'ai un entretien d'embauche le 3 novembre à 11 h.") { jour(rv("*ntretien*").firstOrNull(), le(3, 11), "11:00") },
        cas("créer", "Mets un rendez-vous chez le kiné le 1er ${nomMois(moisProchain)} à 8 h 45.") { jour(rv("*kin*").firstOrNull(), moisProchain, "08:45") },
        cas("créer", "Ajoute dentiste dans deux semaines à 15 h.") { val r = rv("*entiste*").filter { it.quand.toLocalDate() == auj.plusDays(14) }; r.size == 1 && r[0].quand.hour == 15 },
        cas("créer", "Programme une réunion demain de 14 h à 16 h.") { val r = rv("R*union*").firstOrNull(); jour(r, auj.plusDays(1), "14:00") && r!!.fin?.hour == 16 },
        cas("créer", "Mets-moi un rappel dans deux heures pour sortir le linge.") { val r = rv("*linge*").firstOrNull(); r != null && abs(java.time.Duration.between(r.quand, LocalDateTime.now().plusHours(2)).toMinutes()) < 3 },
        cas("créer", "Ajoute un rendez-vous chez le médecin le 3 mars à 10 h en $anSuivant.") { jour(rv("*decin*").firstOrNull(), LocalDate.of(anSuivant, 3, 3), "10:00") },
        cas("créer", "Crée un événement dentiste le 3 mars de l'année prochaine à 9 h.") { val r = rv("*entiste*").filter { it.quand.year == anSuivant }; r.size == 1 && r[0].quand.monthValue == 3 },
        cas("créer", "Ajoute un événement sans heure : faire le ménage samedi.") { jour(rv("*nage*").firstOrNull(), prochain(SAM), null) },
        cas("créer", "Crée l'anniversaire de Paul le 2 mai, il aura 30 ans.") { val r = rv("Anniversaire de Paul").firstOrNull(); r != null && r.sorte == Sorte.ANNIV && r.naissance == le(2, 5).year - 30 },
        cas("créer", "Fais-moi un événement courses samedi avec la liste : lait, œufs, pain et beurre.") { rv("*ourses*").firstOrNull()?.aPreparer?.size == 4 },
        cas("créer", "Ajoute un truc important : rendre le dossier vendredi à 12 h.") { rv("*dossier*").firstOrNull()?.important == true },
        cas("créer", "Ajoute réunion lundi à 10 h dans la salle B, avec Paul et Sophie.") { (rv("R*union*").firstOrNull()?.avec?.size ?: 0) >= 2 },
        // --- Créer : répétitions
        cas("répéter", "Mets-moi le sport tous les mardis à 18 h.") { val r = rv("*port*").firstOrNull(); rep(r)?.type == "semaine" && rep(r)?.jour == 2 },
        cas("répéter", "Ajoute médicaments tous les jours à 8 h.") { rep(rv("*dicament*").firstOrNull())?.type == "jour" },
        cas("répéter", "Crée badminton toutes les deux semaines le jeudi à 19 h.") { val r = rep(rv("*adminton*").firstOrNull()); r?.intervalle == 2 && r.jour == 4 },
        cas("répéter", "Mets réveil en semaine à 7 h.") { rep(rv("*veil*").firstOrNull())?.jours?.size == 5 },
        cas("répéter", "Ajoute vélo tous les week-ends à 9 h.") { rep(rv("V*lo").firstOrNull())?.jours?.size == 2 },
        cas("répéter", "Crée salle de sport le lundi et le jeudi à 18 h.") { rep(rv("*alle*").firstOrNull())?.jours?.size == 2 },
        cas("répéter", "Ajoute réunion d'équipe le premier lundi du mois à 9 h.") { rep(rv("*quipe*").firstOrNull())?.rang == 1 },
        cas("répéter", "Mets afterwork le dernier vendredi du mois à 18 h.") { rep(rv("*fterwork*").firstOrNull())?.rang == -1 },
        cas("répéter", "Crée un rappel payer l'électricité le 10 de chaque mois.") { val r = rep(rv("*lectricit*").firstOrNull()); r?.type == "mois" && r.jour == 10 },
        cas("répéter", "Mets un événement courses tous les lundis d'octobre et de novembre ${auj.year}.") { val r = rv("*ourses*"); r.size == 1 && rep(r[0])?.jusqua == "${auj.year}-11-30" },
        cas("répéter", "Ajoute yoga tous les mercredis jusqu'au 15 décembre à 19 h.") { rep(rv("*oga*").firstOrNull())?.jusqua?.endsWith("-12-15") == true },
        // --- Créer : plusieurs jours
        cas("plusieurs jours", "Ajoute un événement ElleFest sur le 27, le 28 et le 29 octobre.") { val r = rv("*Fest*"); r.size == 1 && r[0].fin?.dayOfMonth == 29 },
        cas("plusieurs jours", "Mets vacances du 20 décembre au 3 janvier.") { val r = rv("*acances*"); r.size == 1 && r[0].fin?.monthValue == 1 },
        cas("plusieurs jours", "Crée un salon pendant 3 jours à partir du 5 novembre.") { val r = rv("*alon*"); r.size == 1 && r[0].fin != null && java.time.temporal.ChronoUnit.DAYS.between(r[0].quand.toLocalDate(), r[0].fin!!.toLocalDate()) == 2L },
        cas("plusieurs jours", "Ajoute cours de guitare les 3 et 10 novembre à 18 h.") { rv("*uitare*").size == 2 },
        cas("plusieurs jours", "Je veux que tu me mettes un événement la première semaine des trois prochains mois Séminaire.") { val r = rv("S*minaire*"); r.size == 3 && r.count { it.fin != null && java.time.temporal.ChronoUnit.DAYS.between(it.quand.toLocalDate(), it.fin!!.toLocalDate()) == 6L && it.quand.dayOfWeek == LUN } == 3 },
        cas("plusieurs jours", "J'aimerais que tu mettes un événement Séminaire durant la première semaine des trois prochains mois.") { rv("S*minaire*").size == 3 },
        cas("plusieurs jours", "Mets-moi une formation la semaine du 9 novembre.") { val r = rv("Formation*"); r.size == 1 && r[0].quand.dayOfMonth == 9 && r[0].fin?.dayOfMonth == 15 },
        cas("plusieurs jours", "Mets séminaire le dernier week-end octobre.") { val r = rv("S*minaire*"); r.size == 1 && r[0].quand.dayOfWeek == SAM && r[0].quand.monthValue == 10 && r[0].fin != null },
        cas("plusieurs jours", "Ajoute camping le premier week-end de novembre.") { val r = rv("Camping*"); r.size == 1 && r[0].quand.dayOfMonth == 7 && r[0].fin?.dayOfMonth == 8 },
        cas("plusieurs jours", "Ajoute vacances toute la semaine prochaine.") { val r = rv("Vacances*"); r.size == 1 && r[0].fin != null },
        // --- Supprimer
        cas("supprimer", "Supprime le foot.") { rv("Foot").isEmpty() && rv("Dentiste").isNotEmpty() },
        cas("supprimer", "Efface le rendez-vous chez le dentiste.") { rv("Dentiste").isEmpty() && rv("Foot").isNotEmpty() },
        cas("supprimer", "Enlève l'événement du ${soiree.dayOfMonth} ${nomMois(soiree)}.") { rv("Soirée chez Max").isEmpty() && rv("Foot").isNotEmpty() },
        cas("supprimer", "Je veux que tu supprimes l'événement.", "Le foot.") { rv("Foot").isEmpty() && rv("Dentiste").isNotEmpty() },
        cas("supprimer", "Je veux que tu supprimes l'événement.") { taille == 8 && dit.startsWith("Lequel") },
        cas("supprimer", "Efface le rendez-vous.") { taille == 8 && dit.startsWith("Lequel") },
        cas("supprimer", "Je veux que tu supprimes l'événement.", "L'anniversaire de Léa.") { rv("Anniversaire de Léa").isEmpty() && rv("Foot").isNotEmpty() },
        cas("modifier", "Décale le rendez-vous.") { taille == 8 && dit.startsWith("Lequel") },
        cas("supprimer", "Supprime tout ce que j'ai demain.") { entre(lea, lea.plusDays(1)).isEmpty() && rv("Foot").isNotEmpty() },
        cas("supprimer", "Supprime les raids de novembre.") { (rep(rv("Raid avec les potes").firstOrNull())?.sauf?.count { it.contains("-11-") } ?: 0) >= 4 },
        cas("supprimer", "Arrête le raid à partir de décembre.") { rep(rv("Raid avec les potes").firstOrNull())?.jusqua?.endsWith("-11-30") == true },
        cas("supprimer", "Supprime le raid de demain seulement.") { val r = rv("Raid avec les potes").firstOrNull(); r != null && (rep(r)?.sauf?.contains(lea.toString()) == true || r.quand.toLocalDate().isAfter(lea)) },
        cas("supprimer", "Supprime tous les raids.") { rv("Raid avec les potes").isEmpty() },
        cas("supprimer", "Supprime le coiffeur.") { rv("Coiffeur").isEmpty() },
        cas("supprimer", "Sucrime le foot.") { rv("Foot").isEmpty() },
        cas("supprimer", "Suprime le dentiste stp.") { rv("Dentiste").isEmpty() },
        // --- Modifier
        cas("modifier", "Le coiffeur n'a pas eu lieu, décale-le à jeudi à 14 h.") { val r = rv("Coiffeur"); r.size == 1 && jour(r[0], prochain(JEU), "14:00") },
        cas("modifier", "Décale le coiffeur d'avant-hier à samedi 10 h.") { val r = rv("Coiffeur"); r.size == 1 && jour(r[0], prochain(SAM), "10:00") },
        cas("modifier", "Décale le foot à 20 h.") { jour(rv("Foot").firstOrNull(), foot, "20:00") },
        cas("modifier", "Décale mon dentiste à jeudi, même heure.") { jour(rv("Dentiste").firstOrNull(), prochain(JEU), "15:00") },
        cas("modifier", "Le dentiste, c'est plutôt à 16 h.") { jour(rv("Dentiste").firstOrNull(), dentiste, "16:00") },
        cas("modifier", "Repousse la soirée chez Max d'une heure.") { jour(rv("Soirée chez Max").firstOrNull(), soiree, "22:00") },
        cas("modifier", "Avance le foot à 18 h.") { jour(rv("Foot").firstOrNull(), foot, "18:00") },
        cas("modifier", "Ajoute Paul à la soirée chez Max.") { "Paul" in rv("Soirée chez Max")[0].avec },
        cas("modifier", "Pour le dentiste, pense à prendre mes radios.") { rv("Dentiste")[0].aPreparer.count { it.texte.contains("radio", true) } == 1 },
        cas("modifier", "Renomme le foot en match de foot.") { rv("*atch de foot*").isNotEmpty() },
        cas("modifier", "Le foot, c'est au stade Gerland.") { rv("Foot")[0].lieu.contains("Gerland") },
        cas("modifier", "Le raid de demain, mets-le plutôt à 22 h, et ajoute Tom.") { val r = rv("Raid avec les potes")[0]; r.quand.hour == 22 && "Tom" in r.avec },
        cas("modifier", "Étale le dentiste sur trois jours.") { val r = rv("Dentiste")[0]; r.fin?.toLocalDate() == dentiste.plusDays(2) && r.quand.toLocalDate() == dentiste },
        cas("modifier", "Mets le foot sur une semaine.") { rv("Foot")[0].fin?.toLocalDate() == foot.plusDays(6) },
        cas("modifier", "Mets la soirée chez Max sur deux jours.") { rv("Soirée chez Max")[0].fin?.toLocalDate()?.isAfter(soiree) == true },
        cas("modifier", "Le raid, c'est fini après novembre.") { rep(rv("Raid avec les potes").firstOrNull())?.jusqua?.endsWith("-11-30") == true },
        cas("modifier", "Marque le dentiste comme important.") { rv("Dentiste")[0].important },
        cas("modifier", "Décade le foot à 20 h.") { jour(rv("Foot").firstOrNull(), foot, "20:00") },
        // --- Fait, annuler
        cas("fait", "J'ai payé le loyer.") { rv("Payer le loyer")[0].fait },
        cas("fait", "C'est bon, j'ai appelé maman.") { rv("Appeler maman")[0].fait },
        cas("annuler", "Supprime le foot.", "Annule.") { rv("Foot").isNotEmpty() },
        cas("annuler", "Crée un tennis demain à 10 h.", "Non, annule.") { rv("*ennis*").isEmpty() },
        cas("annuler", "Crée un tennis demain à 10 h.", "Finalement, efface-le.") { rv("*ennis*").isEmpty() },
        // --- Questions et discussion : rien ne change
        cas("question", "Qu'est-ce que j'ai demain ?") { taille == 8 && dit.contains(Regex("Léa|raid", RegexOption.IGNORE_CASE)) },
        cas("question", "C'est quand mon prochain dentiste ?") { taille == 8 && (dit.contains("${dentiste.dayOfMonth}") || dit.contains(nomJour(dentiste), true)) },
        cas("question", "Qu'est-ce que je dois apporter chez le dentiste ?") { taille == 8 && dit.contains(Regex("Ordonnance|Vitale", RegexOption.IGNORE_CASE)) },
        cas("question", "Où est le dentiste ?") { taille == 8 && dit.contains("Lilas", true) },
        cas("question", "Est-ce que je suis libre samedi soir ?") { taille == 8 },
        cas("discussion", "Salut, ça va ?") { taille == 8 },
        cas("discussion", "Merci beaucoup !") { taille == 8 },
        cas("discussion", "Supprime-moi l'événement Troke Tops.") { taille == 8 },
        // --- Demandes doubles
        cas("double", "Supprime le foot et ajoute un tennis jeudi à 19 h.") { rv("Foot").isEmpty() && rv("*ennis*").isNotEmpty() },
        cas("double", "Crée un événement ElleFest le 29 octobre.", "Supprime le ElleFest du 29 et mets-le sur 3 jours, le 27, le 28 et le 29 octobre.") { val r = rv("*Fest*"); r.size == 1 && r[0].quand.dayOfMonth == 27 && r[0].fin?.dayOfMonth == 29 },
        cas("double", "Décale le foot à 20 h et ajoute Tom.") { val r = rv("Foot").firstOrNull(); jour(r, foot, "20:00") && "Tom" in r!!.avec },
        // --- Formules piégeuses
        cas("piège", "Annule le foot.") { rv("Foot").isEmpty() && rv("Dentiste").isNotEmpty() },
        cas("piège", "Le dentiste est annulé.") { rv("Dentiste").isEmpty() },
        cas("piège", "Je ne vais plus au foot jeudi, enlève-le.") { rv("Foot").isEmpty() },
        cas("piège", "Retire Tom de la soirée chez Max.") { val r = rv("Soirée chez Max").firstOrNull(); r != null && "Tom" !in r.avec && "Max" in r.avec },
        cas("piège", "Préviens-moi 2 heures avant le foot.") { rv("Foot")[0].rappels?.contains("m120") == true },
        cas("piège", "Rappelle-moi la veille pour la soirée chez Max.") { rv("Soirée chez Max")[0].rappels?.contains("veille") == true },
        cas("piège", "Déplace le foot à la semaine prochaine, même jour même heure.") { jour(rv("Foot").firstOrNull(), foot.plusDays(7), "19:00") },
        cas("piège", "Change l'heure du dentiste, mets 16 h 30.") { jour(rv("Dentiste").firstOrNull(), dentiste, "16:30") },
        cas("piège", "Ajoute une note au dentiste : demander pour la couronne.") { rv("Dentiste")[0].notes.contains("couronne") },
        cas("piège", "Mets le foot en important.") { rv("Foot")[0].important },
        cas("piège", "Le foot n'est plus important.") { rv("Foot").isNotEmpty() && !rv("Foot")[0].important },
        cas("piège", "Peux-tu me rajouter un rendez-vous chez l'ophtalmo jeudi prochain à 11 h, s'il te plaît ?") { jour(rv("*phtalmo*").firstOrNull(), prochain(JEU), "11:00") },
        cas("piège", "Euh, ok, donc tu me mets genre resto avec Inès vendredi soir.") { jour(rv("Resto*").firstOrNull(), prochain(VEN), "20:00") },
        cas("piège", "J'ai rendez-vous à la banque mardi à 9 h, note-le.") { jour(rv("*anque*").firstOrNull(), prochain(MAR), "09:00") },
        cas("piège", "Supprime mes rendez-vous de la semaine prochaine.") { val l = auj.plusDays(7L - ((auj.dayOfWeek.value + 6) % 7)); entre(l, l.plusDays(7)).none { it.rv.repetition == null || it.rv.sorte != Sorte.ANNIV } },
        cas("piège", "Qu'est-ce que j'ai de prévu ${nomJour(foot)} ?") { taille == 8 && dit.contains("foot", true) },
        cas("piège", "Tu peux me dire ce que j'ai demain ?") { taille == 8 },
        cas("piège", "Combien de rendez-vous j'ai cette semaine ?") { taille == 8 },
        cas("piège", "Ajoute dentiste vendredi à 15 h.") { rv("Dentiste").size == 2 },
        cas("piège", "Mets le foot à 20 h.") { val r = rv("Foot"); r.size == 1 && jour(r[0], foot, "20:00") },
        cas("piège", "Mets un rendez-vous foot samedi à 10 h.") { rv("Foot").size == 2 },
        cas("piège", "Le loyer, c'est le 3 maintenant.") { rep(rv("Payer le loyer").firstOrNull())?.jour == 3 },
        cas("piège", "Le raid passe au mardi.") { rep(rv("Raid avec les potes").firstOrNull())?.jour == 2 },
        cas("piège", "Le foot, c'est plutôt vendredi.") { jour(rv("Foot").firstOrNull(), prochain(VEN), "19:00") },
        cas("piège", "Déplace le dentiste à lundi à 10 h.") { jour(rv("Dentiste").firstOrNull(), prochain(LUN), "10:00") },
        cas("piège", "Mets le raid toutes les deux semaines.") { val r = rv("Raid avec les potes"); r.size == 1 && rep(r[0])?.intervalle == 2 },
        cas("piège", "Supprime le raid du ${lea.plusDays(14).dayOfMonth} ${nomMois(lea.plusDays(14))}.") { val r = rep(rv("Raid avec les potes").firstOrNull()); r?.sauf?.size == 1 && r.sauf!!.contains(lea.plusDays(14).toString()) },
        cas("piège", "Efface tout le raid.") { rv("Raid avec les potes").isEmpty() },
        cas("piège", "Ajoute un événement Noël chez mamie le 24 décembre.") { jour(rv("*mamie*").firstOrNull(), le(24, 12), null) },
        cas("piège", "Crée un rappel : rendre les livres à la médiathèque avant samedi.") { rv("*livres*").isNotEmpty() },
    )

    /**
     * L'examen : d'autres façons de dire les mêmes choses, écrites avant de régler les règles d'Evermind et jamais
     * utilisées pour les régler. Il dit si Evermind comprend vraiment, pas seulement les phrases du catalogue.
     */
    private val examen: List<Cas> get() = listOf(
        // Supprimer
        cas("supprimer", "Vire le foot de mon agenda.") { rv("Foot").isEmpty() && rv("Dentiste").isNotEmpty() },
        cas("supprimer", "Tu peux enlever le dentiste ?") { rv("Dentiste").isEmpty() && rv("Foot").isNotEmpty() },
        cas("supprimer", "Retire la soirée chez Max.") { rv("Soirée chez Max").isEmpty() },
        cas("supprimer", "Efface mon rendez-vous chez le coiffeur s'il te plaît.") { rv("Coiffeur").isEmpty() },
        cas("supprimer", "Supprime l'anniversaire de Léa.") { rv("Anniversaire de Léa").isEmpty() && rv("Foot").isNotEmpty() },
        cas("supprimer", "J'ai plus besoin du rappel pour le loyer, supprime-le.") { rv("Payer le loyer").isEmpty() },
        cas("supprimer", "Annule la soirée chez Max, elle n'a plus lieu.") { rv("Soirée chez Max").isEmpty() },
        cas("supprimer", "Le foot est annulé cette semaine.") { rv("Foot").isEmpty() },
        cas("supprimer", "Supprime le raid de la semaine prochaine.") { val r = rv("Raid avec les potes").firstOrNull(); r != null && (rep(r)?.sauf?.contains(lea.plusDays(7).toString()) == true) },
        cas("supprimer", "Enlève tous les raids de décembre.") { (rep(rv("Raid avec les potes").firstOrNull())?.sauf?.count { it.contains("-12-") } ?: 0) >= 4 },
        cas("supprimer", "Plus de raid après le mois prochain.") { rep(rv("Raid avec les potes").firstOrNull())?.jusqua != null },
        cas("supprimer", "Supprime le rendez-vous de ${nomJour(foot)}.") { rv("Foot").isEmpty() && rv("Dentiste").isNotEmpty() },
        cas("supprimer", "Efface le truc du ${dentiste.dayOfMonth}.") { rv("Dentiste").isEmpty() && rv("Foot").isNotEmpty() },
        cas("supprimer", "Supprime.") { taille == 8 },
        // Modifier : le moment
        cas("modifier", "Mets le dentiste à 17 h.") { jour(rv("Dentiste").firstOrNull(), dentiste, "17:00") },
        cas("modifier", "Bouge le foot à 21 h.") { jour(rv("Foot").firstOrNull(), foot, "21:00") },
        cas("modifier", "Le foot c'est à 18 h 30 finalement.") { jour(rv("Foot").firstOrNull(), foot, "18:30") },
        cas("modifier", "Déplace la soirée chez Max à dimanche.") { val r = rv("Soirée chez Max").firstOrNull(); r != null && r.quand.dayOfWeek == DayOfWeek.SUNDAY && r.quand.hour == 21 },
        cas("modifier", "Repousse le dentiste à la semaine d'après.") { jour(rv("Dentiste").firstOrNull(), dentiste.plusDays(7), "15:00") },
        cas("modifier", "Avance le dentiste d'une demi-heure.") { jour(rv("Dentiste").firstOrNull(), dentiste, "14:30") },
        cas("modifier", "Recule le foot de deux heures.") { jour(rv("Foot").firstOrNull(), foot, "21:00") },
        cas("modifier", "Le coiffeur, mets-le demain à 11 h.") { jour(rv("Coiffeur").firstOrNull(), auj.plusDays(1), "11:00") },
        cas("modifier", "Change la date du foot : samedi à 15 h.") { jour(rv("Foot").firstOrNull(), prochain(SAM), "15:00") },
        cas("modifier", "Le raid ce sera le jeudi désormais.") { rep(rv("Raid avec les potes").firstOrNull())?.jour == 4 },
        cas("modifier", "Le loyer il faut le payer le 5 maintenant.") { rep(rv("Payer le loyer").firstOrNull())?.jour == 5 },
        cas("modifier", "Le dentiste dure une heure.") { rv("Dentiste")[0].fin?.toLocalTime() == LocalTime.of(16, 0) },
        // Modifier : les détails
        cas("modifier", "Invite Lucas à la soirée chez Max.") { "Lucas" in rv("Soirée chez Max")[0].avec },
        cas("modifier", "Rajoute Inès et Paul au foot.") { val a = rv("Foot")[0].avec; "Inès" in a && "Paul" in a },
        cas("modifier", "Enlève Max de la soirée.") { val a = rv("Soirée chez Max")[0].avec; "Max" !in a && "Tom" in a },
        cas("modifier", "Pour le foot, n'oublie pas les crampons.") { rv("Foot")[0].aPreparer.any { it.texte.contains("rampon") } },
        cas("modifier", "Il faut que je prenne ma carte de mutuelle pour le dentiste.") { rv("Dentiste")[0].aPreparer.any { it.texte.contains("mutuelle", true) } },
        cas("modifier", "Le dentiste c'est au 3 avenue Foch.") { rv("Dentiste")[0].lieu.contains("Foch") },
        cas("modifier", "Note pour la soirée : apporter des chips.") { val r = rv("Soirée chez Max")[0]; r.notes.contains("chips") || r.aPreparer.any { it.texte.contains("chips", true) } },
        cas("modifier", "Appelle le foot entraînement de foot.") { rv("*ntra*nement de foot").isNotEmpty() || rv("Foot").isNotEmpty() },
        cas("modifier", "Change le nom du dentiste en contrôle dentaire.") { rv("Contr*le dentaire").isNotEmpty() },
        cas("modifier", "Le dentiste c'est important.") { rv("Dentiste")[0].important },
        cas("modifier", "Rappelle-moi une heure avant la soirée chez Max.") { rv("Soirée chez Max")[0].rappels?.contains("m60") == true },
        cas("modifier", "Préviens-moi le matin même pour le foot.") { rv("Foot")[0].rappels?.contains("matin") == true },
        cas("modifier", "Étends les vacances... non, mets le foot sur deux jours.") { rv("Foot")[0].fin?.toLocalDate()?.isAfter(foot) == true },
        cas("modifier", "Le raid, une semaine sur deux.") { rep(rv("Raid avec les potes").firstOrNull())?.intervalle == 2 },
        cas("modifier", "Le raid s'arrête fin décembre.") { rep(rv("Raid avec les potes").firstOrNull())?.jusqua?.endsWith("-12-31") == true },
        // Fait, annuler
        cas("fait", "C'est fait pour le loyer.") { rv("Payer le loyer")[0].fait },
        cas("fait", "J'ai bien appelé maman.") { rv("Appeler maman")[0].fait },
        cas("fait", "Le loyer est payé.") { rv("Payer le loyer")[0].fait },
        cas("annuler", "Supprime le dentiste.", "Annule ce que tu viens de faire.") { rv("Dentiste").isNotEmpty() },
        cas("annuler", "Décale le foot à 21 h.", "Non, reviens en arrière.") { jour(rv("Foot").firstOrNull(), foot, "19:00") },
        cas("annuler", "Ajoute piscine demain à 8 h.", "Supprime-le.") { rv("*iscine*").isEmpty() },
        // Questions : rien ne change, et la réponse dit la bonne chose
        cas("question", "J'ai quoi demain ?") { taille == 8 && dit.contains(Regex("Léa|raid", RegexOption.IGNORE_CASE)) },
        cas("question", "Qu'est-ce que j'ai cette semaine ?") { taille == 8 && dit.contains("dentiste", true) },
        cas("question", "Quand est le foot ?") { taille == 8 && (dit.contains("${foot.dayOfMonth}") || dit.contains(nomJour(foot), true)) },
        cas("question", "À quelle heure est le dentiste ?") { taille == 8 && dit.contains("15") },
        cas("question", "Avec qui je vais à la soirée chez Max ?") { taille == 8 && dit.contains("Inès") },
        cas("question", "C'est où le dentiste ?") { taille == 8 && dit.contains("Lilas", true) },
        cas("question", "Quel est le code de la porte du dentiste ?") { taille == 8 && dit.contains("4521B") },
        cas("question", "Quand est l'anniversaire de Léa ?") { taille == 8 && (dit.contains("${lea.dayOfMonth}") || dit.contains("demain", true)) },
        cas("question", "Elle a quel âge Léa ?") { taille == 8 && dit.contains("24") },
        cas("question", "J'ai quelque chose ${nomJour(soiree)} soir ?") { taille == 8 && dit.contains("Max", true) },
        cas("question", "Combien de rendez-vous j'ai demain ?") { taille == 8 },
        // Discussion
        cas("discussion", "Bonjour Evermind !") { taille == 8 },
        cas("discussion", "Tu sers à quoi ?") { taille == 8 },
        cas("discussion", "Rien, laisse tomber.") { taille == 8 },
        // Doubles
        cas("double", "Supprime le coiffeur et mets le dentiste à 16 h.") { rv("Coiffeur").isEmpty() && jour(rv("Dentiste").firstOrNull(), dentiste, "16:00") },
        cas("double", "Ajoute Paul au foot et mets-le à 20 h.") { val r = rv("Foot").firstOrNull(); jour(r, foot, "20:00") && "Paul" in r!!.avec },
        cas("double", "Décale le dentiste à 16 h et préviens-moi la veille.") { val r = rv("Dentiste").firstOrNull(); jour(r, dentiste, "16:00") && r!!.rappels?.contains("veille") == true },
        // Créations qui ressemblent à des demandes
        cas("créer", "Ajoute un foot samedi à 11 h avec Tom.") { rv("Foot").size == 2 },
        cas("créer", "Mets-moi dentiste le 20 novembre à 9 h.") { rv("Dentiste").size == 2 },
        cas("créer", "Programme un appel avec le garage lundi à 9 h.") { rv("*ppel*").any { jour(it, prochain(LUN), "09:00") } },
        cas("créer", "N'oublie pas que je dois rendre les clés vendredi.") { rv("*cl*s*").isNotEmpty() },
        cas("créer", "Rajoute une soirée jeux chez Tom samedi à 20 h.") { rv("*jeux*").isNotEmpty() && rv("Soirée chez Max").isNotEmpty() },
    )

    /** Les demandes de tous les jours, sur tout l'agenda ou plusieurs rendez-vous à la fois. */
    private val bases: List<Cas> get() = listOf(
        // Tout supprimer
        cas("tout", "Supprime tous les rendez-vous.") { taille == 0 },
        cas("tout", "Supprime tous mes rendez-vous.") { taille == 0 },
        cas("tout", "Supprime tout.") { taille == 0 },
        cas("tout", "Efface tout mon agenda.") { taille == 0 },
        cas("tout", "Vide mon agenda.") { taille == 0 },
        cas("tout", "Supprime tous les événements.") { taille == 0 },
        cas("tout", "Enlève tout ce qu'il y a dans l'agenda.") { taille == 0 },
        cas("tout", "Supprime tous les rendez-vous.", "Annule.") { taille == 8 },
        cas("tout", "Supprime tous les rendez-vous passés.") { rv("Coiffeur").isEmpty() && taille == 7 },
        cas("tout", "Supprime tous les anniversaires.") { rv("Anniversaire de Léa").isEmpty() && taille == 7 },
        cas("tout", "Supprime toutes les choses à faire.") { rv("Payer le loyer").isEmpty() && rv("Appeler maman").isEmpty() && taille == 6 },
        cas("tout", "Supprime tout ce que j'ai cette semaine.") { rv("Dentiste").isEmpty() && rv("Coiffeur").isNotEmpty() },
        cas("tout", "Annule tous mes rendez-vous de demain.") { entre(lea, lea.plusDays(1)).none { it.rv.sorte != Sorte.ANNIV } },
        // Plusieurs à la fois
        cas("plusieurs", "Supprime le dentiste et le foot.") { rv("Dentiste").isEmpty() && rv("Foot").isEmpty() && taille == 6 },
        cas("plusieurs", "Supprime le foot, le dentiste et la soirée chez Max.") { taille == 5 && rv("Raid avec les potes").isNotEmpty() },
        cas("plusieurs", "Efface le coiffeur et le raid.") { rv("Coiffeur").isEmpty() && rv("Raid avec les potes").isEmpty() && taille == 6 },
        cas("plusieurs", "Mets le foot et le dentiste en important.") { rv("Foot")[0].important && rv("Dentiste")[0].important },
        // Désigner autrement
        cas("désigner", "Supprime le prochain rendez-vous.") { rv("Appeler maman").isEmpty() && taille == 7 },
        cas("désigner", "Supprime le rendez-vous de 15 h.") { rv("Dentiste").isEmpty() && taille == 7 },
        cas("désigner", "Décale le rendez-vous de 19 h à 20 h.") { jour(rv("Foot").firstOrNull(), foot, "20:00") || (maintenant.hour == 17 && dit.startsWith("Lequel")) },
        cas("désigner", "Supprime le rendez-vous avec Inès.") { rv("Soirée chez Max").isEmpty() && taille == 7 },
        // Changer, dit simplement
        cas("changer", "Mets le foot jeudi.") { jour(rv("Foot").firstOrNull(), prochain(JEU), "19:00") },
        cas("changer", "Mets le foot demain.") { jour(rv("Foot").firstOrNull(), auj.plusDays(1), "19:00") },
        cas("changer", "Change le foot en tennis.") { rv("Tennis").isNotEmpty() && rv("Foot").isEmpty() },
        cas("changer", "Le dentiste est déplacé à jeudi.") { jour(rv("Dentiste").firstOrNull(), prochain(JEU), "15:00") },
        cas("changer", "Décale le foot.") { taille == 8 && jour(rv("Foot").firstOrNull(), foot, "19:00") && dit.isNotBlank() },
        cas("changer", "Le foot c'est demain à 18 h.") { jour(rv("Foot").firstOrNull(), auj.plusDays(1), "18:00") },
        // Questions de tous les jours
        cas("question", "Quel est mon prochain rendez-vous ?") { taille == 8 && dit.contains("maman", true) },
        cas("question", "C'est quoi mon prochain rendez-vous ?") { taille == 8 && dit.contains("maman", true) },
        cas("question", "Liste mes rendez-vous.") { taille == 8 && dit.contains("Dentiste") && dit.contains("Foot") },
        cas("question", "Montre-moi mon agenda.") { taille == 8 && dit.contains("Dentiste") },
        cas("question", "Qu'est-ce que j'ai de prévu ?") { taille == 8 && dit.contains("Dentiste") },
        cas("question", "J'ai combien de rendez-vous ?") { taille == 8 && dit.contains(Regex("\\d")) },
        cas("question", "Quels sont mes rendez-vous de la semaine prochaine ?") { taille == 8 },
        cas("question", "J'ai des rendez-vous demain ?") { taille == 8 && dit.contains(Regex("Léa|raid", RegexOption.IGNORE_CASE)) },
        cas("question", "Est-ce que j'ai quelque chose ce soir ?") { taille == 8 },
        cas("question", "Quel jour on est ?") { taille == 8 && dit.contains("${auj.dayOfMonth}") },
        cas("question", "Quelle heure il est ?") { taille == 8 && dit.contains("${LocalTime.now().hour}") },
        cas("question", "C'est quand l'anniversaire de Léa ?") { taille == 8 && (dit.contains("demain") || dit.contains("${lea.dayOfMonth}")) },
        cas("question", "Quels sont mes anniversaires ?") { taille == 8 && dit.contains("Léa") },
        cas("question", "Qu'est-ce que j'ai à faire ?") { taille == 8 && dit.contains("loyer", true) },
        // Créer, dit simplement
        cas("créer", "Rendez-vous chez le médecin demain à 10 h.") { jour(rv("*decin*").firstOrNull(), auj.plusDays(1), "10:00") },
        cas("créer", "Ajoute un rendez-vous demain.") { taille in 8..9 },
        cas("créer", "Nouveau rendez-vous : garage lundi à 8 h.") { jour(rv("*arage*").firstOrNull(), prochain(LUN), "08:00") },
        cas("créer", "Je vais au cinéma samedi à 21 h.") { jour(rv("*in*ma*").firstOrNull(), prochain(SAM), "21:00") },
    )

    /** Les mêmes familles que « bases », dites autrement (écrites après les règles, mesurées avant retouche). */
    private val basesVerif: List<Cas> get() = listOf(
        cas("tout", "Efface tous mes rendez-vous.") { taille == 0 },
        cas("tout", "Tu peux supprimer tous les rendez-vous ?") { taille == 0 },
        cas("tout", "Supprime l'agenda en entier.") { taille == 0 || taille == 8 },
        cas("tout", "Retire tous les trucs passés.") { rv("Coiffeur").isEmpty() && taille == 7 },
        cas("tout", "Supprime tous mes anniversaires.") { rv("Anniversaire de Léa").isEmpty() && taille == 7 },
        cas("tout", "Efface tous les rendez-vous de la semaine prochaine.") { rv("Coiffeur").isNotEmpty() && rv("Dentiste").isNotEmpty() || taille < 8 },
        cas("plusieurs", "Supprime le coiffeur et le dentiste.") { rv("Coiffeur").isEmpty() && rv("Dentiste").isEmpty() && taille == 6 },
        cas("plusieurs", "Enlève la soirée chez Max et le foot.") { rv("Soirée chez Max").isEmpty() && rv("Foot").isEmpty() && taille == 6 },
        cas("plusieurs", "Décale le foot et le dentiste à 18 h.") { jour(rv("Foot").firstOrNull(), foot, "18:00") && jour(rv("Dentiste").firstOrNull(), dentiste, "18:00") },
        cas("plusieurs", "J'ai payé le loyer et appelé maman.") { rv("Payer le loyer")[0].fait && rv("Appeler maman")[0].fait },
        cas("désigner", "Supprime mon prochain rendez-vous.") { rv("Appeler maman").isEmpty() && taille == 7 },
        cas("désigner", "Décale le rendez-vous de 15 h à 16 h.") { jour(rv("Dentiste").firstOrNull(), dentiste, "16:00") },
        cas("désigner", "Annule le rendez-vous avec Tom.") { rv("Soirée chez Max").isEmpty() && taille == 7 },
        cas("question", "C'est quoi mon prochain truc ?") { taille == 8 && dit.contains("maman", true) },
        cas("question", "Montre-moi mes rendez-vous.") { taille == 8 && dit.contains("Foot") },
        cas("question", "Il est quelle heure ?") { taille == 8 && dit.contains("${LocalTime.now().hour}") },
        cas("question", "On est le combien aujourd'hui ?") { taille == 8 && dit.contains("${auj.dayOfMonth}") },
        cas("question", "J'ai combien de trucs de prévus ?") { taille == 8 && dit.contains(Regex("\\d")) },
        cas("question", "Quelles sont mes tâches ?") { taille == 8 && dit.contains("loyer", true) },
        cas("changer", "Remplace le foot par du basket.") { rv("*asket*").isNotEmpty() && rv("Foot").isEmpty() },
    )

    /** Des demandes complexes du quotidien : longues, hésitantes, à calculer, en plusieurs étapes. */
    private val quotidien: List<Cas> get() = listOf(
        // Dit à l'oral, avec hésitations
        cas("oral", "Euh attends, le dentiste, en fait, tu peux le mettre jeudi plutôt, à la même heure ?") { jour(rv("Dentiste").firstOrNull(), prochain(JEU), "15:00") },
        cas("oral", "Alors, euh, le foot de ${nomJour(foot)}, ben finalement il est annulé.") { rv("Foot").isEmpty() && taille == 7 },
        cas("oral", "Ouais du coup tu me rajoutes Paul à la soirée chez Max, merci.") { "Paul" in rv("Soirée chez Max")[0].avec },
        cas("oral", "Hmm, je crois que j'ai payé le loyer, ouais, marque-le comme fait.") { rv("Payer le loyer")[0].fait },
        cas("oral", "Attends attends, non, laisse le foot comme il est.") { taille == 8 && jour(rv("Foot").firstOrNull(), foot, "19:00") },
        cas("oral", "Ok Evermind, supprime le coiffeur.") { rv("Coiffeur").isEmpty() && taille == 7 },
        // Questions qui demandent de calculer
        cas("calcul", "À quelle heure je finis chez le dentiste ?") { taille == 8 && dit.contains("15 h 30") },
        cas("calcul", "Combien de temps dure le dentiste ?") { taille == 8 && dit.contains(Regex("30 min|demi-heure")) },
        cas("calcul", "Dans combien de jours c'est l'anniversaire de Léa ?") { taille == 8 && dit.contains(Regex("demain|1 jour")) },
        cas("calcul", "Dans combien de temps j'ai le foot ?") { taille == 8 && dit.contains(Regex("4 jours|dans 4")) },
        cas("calcul", "C'est quel jour le foot ?") { taille == 8 && dit.contains(nomJour(foot), true) },
        cas("calcul", "J'ai quoi après le dentiste ?") { taille == 8 && dit.contains("Foot") },
        cas("calcul", "Je suis libre quand cette semaine ?") { taille == 8 && dit.isNotBlank() },
        cas("calcul", "Est-ce que j'ai le temps d'aller courir demain matin ?") { taille == 8 && dit.contains("libre", true) },
        cas("calcul", "Est-ce que le foot tombe en même temps que quelque chose ?") { taille == 8 && dit.isNotBlank() },
        cas("calcul", "Le raid c'est toutes les semaines ?") { taille == 8 && dit.contains(Regex("semaine|mercredi|lundi|mardi|jeudi|vendredi|samedi|dimanche")) },
        cas("calcul", "C'est quand le prochain raid ?") { taille == 8 && dit.contains(Regex("demain|${lea.dayOfMonth}")) },
        cas("calcul", "Qui vient chez le dentiste avec moi ?") { taille == 8 && dit.contains("Maman") },
        cas("calcul", "Il y a quoi dans les notes du dentiste ?") { taille == 8 && dit.contains("4521B") },
        cas("calcul", "Rappelle-moi ce que j'ai ${nomJour(dentiste)}.") { taille == 8 && dit.contains("Dentiste") },
        cas("calcul", "Qu'est-ce que je fais ce week-end ?") { taille == 8 && dit.isNotBlank() },
        cas("calcul", "Quels rendez-vous sont importants ?") { taille == 8 && dit.contains("Dentiste") },
        cas("calcul", "Quand est-ce que je dois payer le loyer ?") { taille == 8 && dit.contains(Regex("${auj.plusDays(5).dayOfMonth}|chaque mois")) },
        // Créer, avec beaucoup de détails
        cas("créer", "Rajoute un resto avec Tom et Inès vendredi à 20 h au Bouchon lyonnais.") { val r = rv("Resto*").firstOrNull(); jour(r, prochain(VEN), "20:00") && "Tom" in r!!.avec && "Inès" in r.avec },
        cas("créer", "Rendez-vous chez le médecin mardi prochain à 14 h 30, c'est important.") { val r = rv("*decin*").firstOrNull(); r != null && r.important && r.quand.hour == 14 && r.quand.minute == 30 },
        cas("créer", "Mets-moi un rappel pour appeler le plombier demain à 9 h et préviens-moi une heure avant.") { val r = rv("*plombier*").firstOrNull(); jour(r, auj.plusDays(1), "09:00") && r!!.rappels?.contains("m60") == true },
        cas("créer", "J'ai guitare tous les jeudis de 18 h à 19 h.") { val r = rv("*uitare*").firstOrNull(); rep(r)?.jour == 4 && r!!.fin?.hour == 19 },
        cas("créer", "Ajoute l'anniversaire de Tom le 12 décembre.") { rv("Anniversaire de Tom").isNotEmpty() },
        cas("créer", "Réunion de travail demain de 10 h à midi en visio avec l'équipe.") { val r = rv("R*union*").firstOrNull(); jour(r, auj.plusDays(1), "10:00") && r!!.fin?.hour == 12 },
        cas("créer", "Pense à acheter un cadeau pour Léa demain.") { rv("*cadeau*").isNotEmpty() },
        cas("créer", "Je pars en vacances du 20 au 27 décembre.") { rv("*acances*").firstOrNull()?.fin?.dayOfMonth == 27 },
        cas("créer", "Mets un rendez-vous chez le vétérinaire samedi matin à 10 h pour le chat.") { jour(rv("*t*rinaire*").firstOrNull(), prochain(SAM), "10:00") },
        // En plusieurs étapes
        cas("étapes", "Ajoute tennis samedi.", "À 10 h.") { jour(rv("Tennis").firstOrNull(), prochain(SAM), "10:00") },
        cas("étapes", "Décale le rendez-vous.", "Le dentiste.", "À 17 h.") { jour(rv("Dentiste").firstOrNull(), dentiste, "17:00") },
        cas("étapes", "Crée un apéro vendredi à 19 h.", "Ajoute Tom et Max.") { val a = rv("Ap*ro*").firstOrNull()?.avec.orEmpty(); "Tom" in a && "Max" in a },
        cas("étapes", "Crée un apéro vendredi à 19 h.", "Mets-le plutôt à 20 h.") { jour(rv("Ap*ro*").firstOrNull(), prochain(VEN), "20:00") },
        cas("étapes", "Supprime le foot.", "Et le coiffeur aussi.") { rv("Foot").isEmpty() && rv("Coiffeur").isEmpty() },
        cas("étapes", "Quand est le dentiste ?", "Décale-le d'une heure.") { jour(rv("Dentiste").firstOrNull(), dentiste, "16:00") },
        cas("étapes", "Quand est le dentiste ?", "Et le foot ?") { taille == 8 && dit.contains(Regex("19 h|${nomJour(foot)}")) },
        cas("étapes", "Supprime le dentiste.", "Non attends, remets-le.") { rv("Dentiste").isNotEmpty() },
        // Changer, plus finement
        cas("changer", "Décale le dentiste au lendemain.") { jour(rv("Dentiste").firstOrNull(), dentiste.plusDays(1), "15:00") },
        cas("changer", "Mets la soirée chez Max un jour plus tôt.") { jour(rv("Soirée chez Max").firstOrNull(), soiree.minusDays(1), "21:00") },
        cas("changer", "Repousse le foot à la semaine d'après à 20 h.") { jour(rv("Foot").firstOrNull(), foot.plusDays(7), "20:00") },
        cas("changer", "Le dentiste finit à 16 h.") { val r = rv("Dentiste")[0]; r.quand.hour == 15 && r.fin?.hour == 16 && r.fin?.minute == 0 },
        cas("changer", "Le foot commence à 18 h 30 et finit à 20 h.") { val r = rv("Foot")[0]; r.quand.hour == 18 && r.quand.minute == 30 && r.fin?.hour == 20 },
        cas("changer", "Ajoute à la soirée chez Max : apporter le dessert.") { val r = rv("Soirée chez Max")[0]; r.notes.contains("dessert") || r.aPreparer.any { it.texte.contains("dessert", true) } },
        cas("changer", "Plus de rappels pour le foot.") { rv("Foot")[0].rappels?.isEmpty() == true },
        cas("changer", "Enlève le rappel de la veille pour le dentiste.") { rv("Dentiste")[0].rappels?.contains("veille") == false },
        cas("changer", "Le raid c'est sur Discord.") { rv("Raid avec les potes")[0].lieu.contains("Discord") || rv("Raid avec les potes")[0].notes.contains("Discord") },
        cas("changer", "Le dentiste n'est plus avec maman.") { rv("Dentiste")[0].avec.isEmpty() },
        cas("changer", "Je n'ai plus besoin de l'ordonnance pour le dentiste.") { rv("Dentiste")[0].aPreparer.none { it.texte == "Ordonnance" } },
        cas("changer", "J'ai préparé ma carte vitale pour le dentiste.") { rv("Dentiste")[0].aPreparer.first { it.texte == "Carte Vitale" }.fait || taille == 8 },
        cas("changer", "Le loyer, c'est fini, je ne le paie plus.") { rv("Payer le loyer").isEmpty() || rep(rv("Payer le loyer")[0])?.jusqua != null },
        cas("changer", "Le raid, c'est le mercredi et le vendredi maintenant.") { rep(rv("Raid avec les potes").firstOrNull())?.jours?.size == 2 },
        // Supprimer, plus finement
        cas("supprimer", "Supprime tout sauf l'anniversaire de Léa.") { taille == 1 && rv("Anniversaire de Léa").isNotEmpty() },
        cas("supprimer", "Supprime tout ce qu'il y a après le foot cette semaine.") { taille <= 8 },
        cas("supprimer", "Supprime le raid de demain et celui de la semaine prochaine.") { rv("Raid avec les potes").size == 1 && entre(lea, lea.plusDays(1)).none { it.rv.titre.startsWith("Raid") } && entre(lea.plusDays(7), lea.plusDays(8)).none { it.rv.titre.startsWith("Raid") } && entre(lea.plusDays(14), lea.plusDays(15)).any { it.rv.titre.startsWith("Raid") } },
        cas("supprimer", "Annule tout ce que j'ai ce week-end.") { taille <= 8 },
        // Pièges : ne rien casser
        cas("piège", "Je me demande si je vais au foot.") { taille == 8 && jour(rv("Foot").firstOrNull(), foot, "19:00") },
        cas("piège", "Le foot c'était super la semaine dernière.") { taille == 8 && jour(rv("Foot").firstOrNull(), foot, "19:00") },
        cas("piège", "Mon dentiste s'appelle docteur Martin.") { rv("Dentiste").size == 1 && taille in 8..8 },
        cas("piège", "Supprime.", "Rien, laisse tomber.") { taille == 8 },
        cas("piège", "Ne supprime pas le foot.") { rv("Foot").isNotEmpty() },
        cas("piège", "Est-ce que tu peux me dire si le dentiste est important ?") { taille == 8 && rv("Dentiste")[0].important && dit.contains(Regex("oui|important", RegexOption.IGNORE_CASE)) },
        cas("piège", "Tu as bien noté le foot ?") { taille == 8 && dit.contains("Foot") },
    )

    /** Les mêmes familles que « quotidien », dites autrement (écrites après les corrections, mesurées avant retouche). */
    private val quotidienVerif: List<Cas> get() = listOf(
        cas("oral", "Bon ben, euh, tu peux virer le coiffeur, il est passé de toute façon.") { rv("Coiffeur").isEmpty() && taille == 7 },
        cas("oral", "Ouais alors la soirée chez Max, mets-la à 22 h.") { jour(rv("Soirée chez Max").firstOrNull(), soiree, "22:00") },
        cas("oral", "Surtout n'efface pas le dentiste.") { rv("Dentiste").isNotEmpty() && taille == 8 },
        cas("oral", "Ne touche pas au raid.") { taille == 8 && rep(rv("Raid avec les potes")[0])?.sauf == null },
        cas("calcul", "Le dentiste dure combien de temps ?") { taille == 8 && dit.contains("30 min") },
        cas("calcul", "Il reste combien de jours avant le foot ?") { taille == 8 && dit.contains("4 jours") },
        cas("calcul", "Je termine à quelle heure chez le dentiste ?") { taille == 8 && dit.contains("15 h 30") },
        cas("calcul", "Qu'est-ce qui vient après le foot ?") { taille == 8 && dit.contains("Payer le loyer|Soirée|Raid".toRegex()) },
        cas("calcul", "Le dentiste c'est important ?") { taille == 8 && dit.startsWith("Oui") },
        cas("calcul", "Le loyer, ça revient tous les mois ?") { taille == 8 && dit.contains("mois") },
        cas("calcul", "Je suis dispo samedi matin ?") { taille == 8 && dit.isNotBlank() },
        cas("étapes", "Note un ciné dimanche.", "Vers 20 h.") { jour(rv("Cin*").firstOrNull(), prochain(DayOfWeek.SUNDAY), "20:00") },
        cas("étapes", "Supprime le dentiste.", "Et le foot.") { rv("Dentiste").isEmpty() && rv("Foot").isEmpty() },
        cas("étapes", "Marque le dentiste comme important.", "Pareil pour le foot.") { rv("Foot")[0].important },
        cas("étapes", "C'est où le dentiste ?", "Et la soirée chez Max ?") { taille == 8 && dit.isNotBlank() && !dit.contains("Lilas") },
        cas("étapes", "Avec qui je vais à la soirée ?", "Retire Tom.") { "Tom" !in rv("Soirée chez Max")[0].avec && taille == 8 },
        cas("étapes", "Crée un footing demain à 7 h.", "Ah non, plutôt 8 h.") { jour(rv("Footing").firstOrNull(), auj.plusDays(1), "08:00") },
        cas("étapes", "Bouge le rendez-vous.", "Le foot.", "Dimanche.") { jour(rv("Foot").firstOrNull(), prochain(DayOfWeek.SUNDAY), "19:00") },
        cas("changer", "Recule la soirée chez Max au lendemain.") { jour(rv("Soirée chez Max").firstOrNull(), soiree.plusDays(1), "21:00") },
        cas("changer", "Mets le foot deux heures plus tard.") { jour(rv("Foot").firstOrNull(), foot, "21:00") },
        cas("changer", "La soirée chez Max finit à 2 h du matin.") { rv("Soirée chez Max")[0].fin?.hour == 2 },
        cas("changer", "Pas de rappel pour l'anniversaire de Léa.") { rv("Anniversaire de Léa")[0].rappels?.isEmpty() == true },
        cas("changer", "Supprime le rappel une heure avant pour le dentiste.") { rv("Dentiste")[0].rappels?.contains("m60") == false && rv("Dentiste").isNotEmpty() },
        cas("changer", "Inès ne vient plus à la soirée.") { "Inès" !in rv("Soirée chez Max")[0].avec && rv("Soirée chez Max").isNotEmpty() },
        cas("changer", "J'ai mis la carte vitale dans mon sac pour le dentiste.") { rv("Dentiste").isNotEmpty() && taille == 8 },
        cas("changer", "Je n'irai plus jamais au raid.") { rv("Raid avec les potes").isEmpty() || rep(rv("Raid avec les potes")[0])?.jusqua != null },
        cas("changer", "Le raid se fera le samedi et le dimanche.") { rep(rv("Raid avec les potes").firstOrNull())?.jours?.size == 2 },
        cas("supprimer", "Efface tout à part le dentiste.") { taille == 1 && rv("Dentiste").isNotEmpty() },
        cas("supprimer", "Supprime tous les rendez-vous sauf le foot et le dentiste.") { taille == 2 && rv("Foot").isNotEmpty() && rv("Dentiste").isNotEmpty() },
        cas("créer", "Rajoute un dîner chez Julie avec Paul et Sophie samedi à 20 h.") { val r = rv("D*ner*").firstOrNull(); r != null && "Paul" in r.avec && "Sophie" in r.avec },
        cas("créer", "Entretien demain de 14 h à 15 h 30, c'est important.") { val r = rv("*ntretien*").firstOrNull(); r != null && r.important && r.fin?.minute == 30 },
        cas("piège", "Je repense au foot de la semaine dernière.") { taille == 8 && jour(rv("Foot").firstOrNull(), foot, "19:00") },
        cas("piège", "Laisse tout comme c'est.") { taille == 8 },
    )

    /** Des phrases complexes : repères relatifs, explications, corrections en route, hypothèses, comptes, paroles rapportées. */
    private val complexe: List<Cas> get() = listOf(
        // Par rapport à un autre rendez-vous
        cas("relatif", "Ajoute un rappel pour acheter un cadeau deux jours avant l'anniversaire de Léa.") { rv("*cadeau*").firstOrNull()?.quand?.toLocalDate() == lea.minusDays(2).let { if (it.isBefore(auj)) it.plusYears(1) else it } || rv("*cadeau*").firstOrNull()?.quand?.toLocalDate() == lea.plusYears(1).minusDays(2) },
        cas("relatif", "Mets un footing le lendemain du foot à 8 h.") { jour(rv("Footing").firstOrNull(), foot.plusDays(1), "08:00") },
        cas("relatif", "Ajoute une séance de révision la veille du dentiste à 18 h.") { jour(rv("*vision*").firstOrNull(), dentiste.minusDays(1), "18:00") },
        cas("relatif", "Crée un apéro le même jour que la soirée chez Max, à 19 h.") { jour(rv("Ap*ro*").firstOrNull(), soiree, "19:00") },
        cas("relatif", "Rajoute une douche juste après le foot.") { rv("Douche").firstOrNull()?.quand?.let { it.toLocalDate() == foot && it.hour in 19..21 } == true },
        cas("relatif", "Décale le dentiste au même jour que le foot.") { jour(rv("Dentiste").firstOrNull(), foot, "15:00") },
        cas("relatif", "Mets le foot une heure après le dentiste, le même jour.") { jour(rv("Foot").firstOrNull(), dentiste, "16:30") || jour(rv("Foot").firstOrNull(), dentiste, "16:00") },
        // Avec une explication
        cas("explication", "Comme je suis malade, annule le foot.") { rv("Foot").isEmpty() && taille == 7 },
        cas("explication", "Le dentiste m'a appelé pour dire que c'est à 16 h au lieu de 15 h.") { jour(rv("Dentiste").firstOrNull(), dentiste, "16:00") },
        cas("explication", "Max m'a dit que la soirée commence à 20 h finalement.") { jour(rv("Soirée chez Max").firstOrNull(), soiree, "20:00") },
        cas("explication", "Vu que le stade est fermé, le foot passe au lendemain.") { jour(rv("Foot").firstOrNull(), foot.plusDays(1), "19:00") },
        cas("explication", "J'ai reçu un message : le coiffeur est reporté à samedi 10 h.") { jour(rv("Coiffeur").firstOrNull(), prochain(SAM), "10:00") },
        cas("explication", "Tom ne pourra pas venir à la soirée, enlève-le.") { val r = rv("Soirée chez Max"); r.size == 1 && "Tom" !in r[0].avec },
        cas("explication", "Ma mère ne vient plus chez le dentiste avec moi.") { rv("Dentiste").size == 1 && rv("Dentiste")[0].avec.isEmpty() },
        // Corrections en route
        cas("correction", "Mets le dentiste jeudi, non vendredi.") { jour(rv("Dentiste").firstOrNull(), prochain(VEN), "15:00") },
        cas("correction", "Ajoute piscine mardi, enfin plutôt mercredi, à 18 h.") { jour(rv("Piscine").firstOrNull(), prochain(DayOfWeek.WEDNESDAY), "18:00") },
        cas("correction", "Décale le foot à 20 h, ah non, à 21 h.") { jour(rv("Foot").firstOrNull(), foot, "21:00") },
        cas("correction", "Supprime le dentiste, euh non pardon, le coiffeur.") { rv("Coiffeur").isEmpty() && rv("Dentiste").isNotEmpty() },
        // Hypothèses, comparaisons, comptes
        cas("question", "Si je décale le foot à ${nomJour(dentiste)} 15 h, ça tombe en même temps que quelque chose ?") { taille == 8 && dit.contains("Dentiste") && jour(rv("Foot").firstOrNull(), foot, "19:00") },
        cas("question", "Qu'est-ce qui est le plus tôt, le dentiste ou le foot ?") { taille == 8 && dit.contains("Dentiste") },
        cas("question", "Combien de raids il me reste en novembre ?") { taille == 8 && dit.contains(Regex("[45]")) },
        cas("question", "J'ai combien de jours entre le dentiste et le foot ?") { taille == 8 && dit.contains("1") },
        cas("question", "Est-ce que j'ai quelque chose entre le dentiste et le foot ?") { taille == 8 && dit.isNotBlank() },
        cas("question", "Le dentiste c'est avant ou après le foot ?") { taille == 8 && dit.contains("avant", true) },
        cas("question", "C'est dans combien de temps la soirée chez Max ?") { taille == 8 && dit.contains("6 jours") },
        cas("question", "Quel est mon rendez-vous le plus important cette semaine ?") { taille == 8 && dit.contains("Dentiste") },
        cas("question", "Rappelle-moi avec qui je vais chez le dentiste et à quelle heure.") { taille == 8 && dit.contains("Maman") && dit.contains("15") },
        cas("question", "Est-ce que je serai libre ${nomJour(foot)} soir pour aller au cinéma ?") { taille == 8 && dit.contains("Foot") },
        // Créer, avec des contraintes
        cas("créer", "Rajoute un rendez-vous chez le kiné tous les mardis à 18 h jusqu'à fin décembre.") { val r = rep(rv("*in*").firstOrNull { it.titre.contains("ki", true) }); r?.jour == 2 && r.jusqua?.endsWith("-12-31") == true },
        cas("créer", "Je dois rendre mon dossier au plus tard vendredi midi, mets-le en important.") { val r = rv("*dossier*").firstOrNull(); r != null && r.important },
        cas("créer", "Crée un rendez-vous pour faire vacciner le chien dans quinze jours à 10 h.") { rv("*vaccin*").firstOrNull()?.quand?.toLocalDate() == auj.plusDays(15) },
        cas("créer", "Mets-moi un rappel le premier lundi de décembre pour payer les impôts.") { rv("*imp*ts*").firstOrNull()?.quand?.let { it.monthValue == 12 && it.dayOfWeek == LUN && it.dayOfMonth <= 7 } == true },
        cas("créer", "J'ai un mariage le samedi 20 juin, préviens-moi une semaine avant.") { val r = rv("Mariage*").firstOrNull(); r != null && r.rappels?.contains("m10080") == true },
        cas("créer", "Ajoute un dîner chez mes parents le dimanche dans deux semaines à midi.") { rv("D*ner*").firstOrNull()?.quand?.let { it.dayOfWeek == DayOfWeek.SUNDAY && it.hour == 12 } == true },
        // Plusieurs choses à la fois, dites longuement
        cas("plusieurs", "Pour la soirée chez Max, ajoute Paul, mets-la à 22 h et préviens-moi la veille.") { val r = rv("Soirée chez Max")[0]; "Paul" in r.avec && r.quand.hour == 22 && r.rappels?.contains("veille") == true },
        cas("plusieurs", "Supprime le raid de demain, et à la place mets un cinéma à 21 h avec Léa.") { jour(rv("Cin*").firstOrNull(), lea, "21:00") },
        cas("plusieurs", "Le dentiste, mets-le à 16 h, ajoute que je dois prendre mes radios, et marque-le comme important.") { val r = rv("Dentiste")[0]; r.quand.hour == 16 && r.important && r.aPreparer.any { it.texte.contains("radio", true) } },
        cas("plusieurs", "Annule le foot et le coiffeur, mais garde le dentiste.") { rv("Foot").isEmpty() && rv("Coiffeur").isEmpty() && rv("Dentiste").isNotEmpty() },
        // Une série créée puis enlevée en partie (essai de l'utilisateur, le 13 octobre 2026)
        cas("série", "Mets un événement course d'octobre à novembre ${auj.year}, tous les lundis.", "Supprime les courses, les événements courses de novembre ${auj.year}.") {
            val r = rv("Course*").firstOrNull(); r != null && entre(LocalDate.of(auj.year, 11, 1), LocalDate.of(auj.year, 12, 1)).none { it.rv.id == r.id } && entre(auj, LocalDate.of(auj.year, 11, 1)).any { it.rv.id == r.id } },
        cas("série", "Mets un événement course d'octobre à novembre ${auj.year}, tous les lundis.", "Supprime les événements courses de novembre ${auj.year}.") {
            val r = rv("Course*").firstOrNull(); r != null && entre(LocalDate.of(auj.year, 11, 1), LocalDate.of(auj.year, 12, 1)).none { it.rv.id == r.id } && rv("Foot").isNotEmpty() },
        cas("série", "Mets un événement course d'octobre à novembre ${auj.year}, tous les lundis.", "Enlève tous les lundis de novembre pour les courses.") {
            val r = rv("Course*").firstOrNull(); r != null && entre(LocalDate.of(auj.year, 11, 1), LocalDate.of(auj.year, 12, 1)).none { it.rv.id == r.id } },
        cas("liste", "Mets un événement course d'octobre à novembre ${auj.year}, tous les lundis.", "Ajoute une liste de courses à l'événement course : lait, œufs, pain et beurre.") {
            val r = rv("Course*").firstOrNull(); r != null && r.aPreparer.map { it.texte } == listOf("Lait", "Œufs", "Pain", "Beurre") && rep(r)?.jour == 1 },
        cas("liste", "Mets un événement course d'octobre à novembre ${auj.year}, tous les lundis.", "Pour les courses, il faut acheter du lait, des œufs et du pain.") {
            val r = rv("Course*").firstOrNull(); r != null && r.aPreparer.size == 3 && rep(r)?.jour == 1 },
        cas("liste", "Mets un événement course d'octobre à novembre ${auj.year}, tous les lundis.", "Ajoute du lait et des œufs à la liste des courses.") {
            rv("Course*").firstOrNull()?.aPreparer?.map { it.texte } == listOf("Lait", "Œufs") },
        cas("liste", "Ajoute à la soirée chez Max la liste : chips, bières et glaçons.") { rv("Soirée chez Max")[0].aPreparer.size == 3 },
        // Créer avec la liste (essai de l'utilisateur, le 13 octobre 2026)
        cas("liste", "Crée-moi un événement course pour vendredi et tu mets, donc je veux pain, beurre, crème fraîche.") {
            val r = rv("Courses").firstOrNull(); r != null && r.sorte == Sorte.A_FAIRE && r.aPreparer.map { it.texte } == listOf("Pain", "Beurre", "Crème fraîche") && r.quand.toLocalDate() == prochain(VEN) },
        cas("liste", "Ajoute des courses vendredi avec la liste : pain, beurre, crème fraîche.") { rv("Courses").firstOrNull()?.aPreparer?.size == 3 },
        cas("liste", "Mets un événement courses samedi matin, il faut acheter du pain, du beurre et de la crème fraîche.") { val r = rv("Courses").firstOrNull(); r != null && r.aPreparer.size == 3 },
        cas("liste", "Crée une course vendredi et mets-y pain, beurre et crème fraîche.") { val r = rv("Courses").firstOrNull(); r != null && r.aPreparer.size == 3 && r.sorte == Sorte.A_FAIRE },
        cas("liste", "Courses samedi à 10 h : lait, œufs, farine.") { jour(rv("Courses").firstOrNull(), prochain(SAM), "10:00") && rv("Courses")[0].aPreparer.size == 3 },
        cas("liste", "Ajoute une course à pied dimanche à 9 h.") { rv("Course à pied").firstOrNull()?.sorte == Sorte.SPORT },
        cas("liste", "Je dois faire les courses jeudi, il me faut des tomates, du riz et du poulet.") { val r = rv("Courses").firstOrNull(); r != null && r.aPreparer.size == 3 },
        cas("liste", "Ajoute un événement courses lundi à 18 h avec du lait, des yaourts et des pommes.") { val r = rv("Courses").firstOrNull(); jour(r, prochain(LUN), "18:00") && r!!.aPreparer.size == 3 },
        cas("liste", "Courses samedi à 10 h.", "Pour les courses de samedi, rajoute du café et du sucre.") { rv("Courses").firstOrNull()?.aPreparer?.map { it.texte } == listOf("Café", "Sucre") },
        cas("liste", "Courses samedi à 10 h.", "Dans les courses, mets aussi du papier toilette et de la lessive.") { rv("Courses").firstOrNull()?.aPreparer?.size == 2 && jour(rv("Courses").firstOrNull(), prochain(SAM), "10:00") },
        cas("liste", "Courses samedi à 10 h : café, sucre.", "Qu'est-ce que je dois acheter pour les courses ?") { taille == 9 && dit.contains("Café") },
        // Pièges
        cas("piège", "Je pensais supprimer le foot mais finalement non.") { rv("Foot").isNotEmpty() && taille == 8 },
        cas("piège", "Mon pote m'a demandé si le foot était annulé.") { rv("Foot").isNotEmpty() && taille == 8 },
        cas("piège", "Il faudrait peut-être que je décale le dentiste, mais pas maintenant.") { jour(rv("Dentiste").firstOrNull(), dentiste, "15:00") && taille == 8 },
        cas("piège", "Le foot c'est toujours à 19 h ?") { taille == 8 && dit.contains("19") && jour(rv("Foot").firstOrNull(), foot, "19:00") },
        cas("piège", "Si Max annule la soirée, je te le dirai.") { taille == 8 && rv("Soirée chez Max").isNotEmpty() },
    )

    /** Les mêmes familles que « complexe », dites autrement (écrites après les corrections, mesurées avant retouche). */
    private val complexeVerif: List<Cas> get() = listOf(
        cas("relatif", "Note un rappel pour réserver le resto trois jours avant la soirée chez Max.") { rv("*resto*").firstOrNull()?.quand?.toLocalDate() == soiree.minusDays(3) },
        cas("relatif", "Ajoute une sieste le surlendemain du dentiste à 14 h.") { jour(rv("Sieste").firstOrNull(), dentiste.plusDays(2), "14:00") },
        cas("relatif", "Mets un étirement juste avant le foot.") { rv("*tirement*").firstOrNull()?.quand?.let { it.toLocalDate() == foot && it.hour == 18 } == true },
        cas("relatif", "Déplace le coiffeur au même jour que le dentiste.") { rv("Coiffeur").firstOrNull()?.quand?.toLocalDate() == dentiste },
        cas("relatif", "Crée une réunion la veille de la soirée chez Max à 10 h.") { jour(rv("R*union").firstOrNull(), soiree.minusDays(1), "10:00") },
        cas("explication", "Puisque j'ai une réunion, décale le dentiste à 17 h.") { jour(rv("Dentiste").firstOrNull(), dentiste, "17:00") },
        cas("explication", "Supprime le coiffeur parce que j'y suis déjà allé.") { rv("Coiffeur").isEmpty() && taille == 7 },
        cas("explication", "Inès m'a dit qu'elle ne pourra pas venir à la soirée.") { "Inès" !in rv("Soirée chez Max")[0].avec && rv("Soirée chez Max").isNotEmpty() },
        cas("explication", "On m'a prévenu que le foot est déplacé à dimanche.") { jour(rv("Foot").firstOrNull(), prochain(DayOfWeek.SUNDAY), "19:00") },
        cas("explication", "Le cabinet a appelé, le dentiste passe à 16 h 30 au lieu de 15 h.") { jour(rv("Dentiste").firstOrNull(), dentiste, "16:30") },
        cas("correction", "Mets le foot samedi, enfin non, dimanche.") { jour(rv("Foot").firstOrNull(), prochain(DayOfWeek.SUNDAY), "19:00") },
        cas("correction", "Ajoute un tennis à 10 h, non, à 11 h, samedi.") { jour(rv("Tennis").firstOrNull(), prochain(SAM), "11:00") },
        cas("correction", "Enlève le foot, pardon, le raid de demain.") { rv("Foot").isNotEmpty() && rv("Raid avec les potes").firstOrNull()?.let { r -> entre(lea, lea.plusDays(1)).none { it.rv.id == r.id } } == true },
        cas("question", "Si je mets le dentiste à 19 h ${nomJour(foot)}, ça pose problème ?") { taille == 8 && dit.contains("Foot") && jour(rv("Dentiste").firstOrNull(), dentiste, "15:00") },
        cas("question", "Lequel vient en premier, la soirée chez Max ou le foot ?") { taille == 8 && dit.startsWith("Foot") },
        cas("question", "Le foot est avant ou après la soirée chez Max ?") { taille == 8 && dit.startsWith("Foot") },
        cas("question", "Rappelle-moi où est le dentiste et ce que je dois apporter.") { taille == 8 && dit.contains("Lilas") && dit.contains("Vitale", true) },
        cas("question", "Dis-moi à quelle heure est le foot et avec qui.") { taille == 8 && dit.contains("19") },
        cas("créer", "Mets-moi un rendez-vous chez le notaire le deuxième mardi de janvier à 10 h.") { rv("*otaire*").firstOrNull()?.quand?.let { it.monthValue == 1 && it.dayOfWeek == MAR && it.dayOfMonth in 8..14 && it.hour == 10 } == true },
        cas("créer", "Ajoute un brunch le samedi dans trois semaines à 11 h.") { rv("Brunch").firstOrNull()?.quand?.let { it.dayOfWeek == SAM && it.hour == 11 && it.toLocalDate().isAfter(auj.plusDays(13)) } == true },
        cas("créer", "Il faut que j'envoie le colis au plus tard jeudi, c'est important.") { rv("*colis*").firstOrNull()?.important == true },
        cas("plusieurs", "Pour le foot, mets-le à 20 h, ajoute Tom et préviens-moi une heure avant.") { val r = rv("Foot")[0]; r.quand.hour == 20 && "Tom" in r.avec && r.rappels?.contains("m60") == true },
        cas("plusieurs", "Supprime le coiffeur, et à la place mets un rendez-vous chez le barbier jeudi à 10 h.") { rv("Coiffeur").isEmpty() && jour(rv("*arbier*").firstOrNull(), prochain(JEU), "10:00") },
        cas("plusieurs", "Garde le foot mais supprime le coiffeur.") { rv("Foot").isNotEmpty() && rv("Coiffeur").isEmpty() },
        cas("plusieurs", "Crée un apéro samedi à 18 h, et ajoute Tom et Max.") { val r = rv("Ap*ro*").firstOrNull(); r != null && "Tom" in r.avec && "Max" in r.avec },
        cas("piège", "J'avais pensé à annuler le dentiste, mais en fait non.") { rv("Dentiste").isNotEmpty() && taille == 8 },
        cas("piège", "Si le foot est annulé, préviens-moi.") { rv("Foot").isNotEmpty() && taille == 8 },
        cas("piège", "Peut-être qu'un jour je supprimerai le raid.") { rv("Raid avec les potes").isNotEmpty() && taille == 8 },
        cas("piège", "Max demande si la soirée tient toujours.") { rv("Soirée chez Max").isNotEmpty() && taille == 8 },
    )

    /**
     * Tout ce que l'app sait faire, en phrases complexes : créer avec lieu, personnes, liste, rappels, lien, répétitions ;
     * changer chaque détail ; supprimer ; questions ; postes ; enchaînements. « dicté: » : la phrase passe d'abord par la
     * correction des noms de la dictée, comme sur le téléphone.
     */
    private val couverture: List<Cas> get() = listOf(
        // Créer, avec tous les détails
        cas("créer", "Rendez-vous chez le dentiste à Metz jeudi à 14 h 30.") { val r = rv("*entiste*").firstOrNull { it.quand.toLocalDate() == prochain(JEU) }; jour(r, prochain(JEU), "14:30") && r!!.lieu.contains("Metz") },
        cas("créer", "Dîner chez Paul à Nancy samedi à 20 h avec Julie et Marc.") { val r = rv("D*ner*").firstOrNull(); jour(r, prochain(SAM), "20:00") && r!!.lieu.contains("Nancy") && "Julie" in r.avec && "Marc" in r.avec },
        cas("créer", "Réunion de chantier à Thionville lundi de 8 h à 10 h, c'est important, préviens-moi la veille.") { val r = rv("R*union*").firstOrNull(); jour(r, prochain(LUN), "08:00") && r!!.fin?.hour == 10 && r.important && r.lieu.contains("Thionville") && r.rappels?.contains("veille") == true },
        cas("créer", "Ajoute un rendez-vous chez l'ophtalmologue au 12 rue Serpenoise à Metz le 3 novembre à 9 h, n'oublie pas mon ordonnance.") { val r = rv("*phtalmo*").firstOrNull(); jour(r, le(3, 11), "09:00") && r!!.lieu.contains("Serpenoise") && r.aPreparer.any { it.texte.contains("rdonnance") } },
        cas("créer", "Mets un rendez-vous à la CAF de Thionville mardi à 10 h, il faut apporter mon RIB et ma carte d'identité.") { val r = rv("*CAF*").firstOrNull(); jour(r, prochain(MAR), "10:00") && r!!.aPreparer.size == 2 },
        cas("créer", "Crée l'anniversaire de ma sœur Claire le 8 février, elle aura 30 ans.") { val r = rv("Anniversaire de*Claire*").firstOrNull(); r != null && r.naissance == le(8, 2).year - 30 },
        cas("créer", "Réunion en visio demain à 10 h, le lien c'est https://meet.google.com/abc-defg-hij") { val r = rv("R*union*").firstOrNull(); jour(r, auj.plusDays(1), "10:00") && r!!.lien.contains("meet.google.com") },
        cas("créer", "Cours de guitare tous les mercredis à 17 h sauf en décembre.") { val r = rv("*uitare*").firstOrNull(); rep(r)?.jour == 3 && r!!.quand.hour == 17 },
        cas("créer", "Rendez-vous chez le kiné à Woippy tous les lundis et jeudis à 18 h jusqu'au 20 décembre.") { val r = rv("*in*").firstOrNull { it.titre.contains("ki", true) }; rep(r)?.jours?.size == 2 && rep(r)?.jusqua?.endsWith("-12-20") == true && r!!.lieu.contains("Woippy") },
        cas("créer", "Séminaire à Strasbourg du 4 au 6 novembre.") { val r = rv("S*minaire*").firstOrNull(); r != null && r.fin?.dayOfMonth == 6 && r.lieu.contains("Strasbourg") },
        cas("créer", "Vacances à Biarritz du 20 au 27 décembre avec Julie.") { val r = rv("Vacances*").firstOrNull(); r != null && r.fin?.dayOfMonth == 27 && r.lieu.contains("Biarritz") && "Julie" in r.avec },
        cas("créer", "Rappelle-moi de payer la cantine le 5 de chaque mois.") { val r = rep(rv("*cantine*").firstOrNull()); r?.type == "mois" && r.jour == 5 },
        cas("créer", "Courses chez Leclerc à Woippy samedi à 10 h : pain, lait, œufs, jambon.") { val r = rv("Courses*").firstOrNull(); jour(r, prochain(SAM), "10:00") && r!!.aPreparer.size == 4 },
        cas("créer", "Ajoute un apéro chez Max demain à 19 h, ramène des chips et des bières.") { val r = rv("Ap*ro*").firstOrNull(); jour(r, auj.plusDays(1), "19:00") && r!!.aPreparer.size == 2 },
        cas("créer", "Entretien d'embauche chez Orange à Paris mardi prochain à 14 h, prends ton CV.") { val r = rv("*ntretien*").firstOrNull(); r != null && r.quand.hour == 14 && r.aPreparer.any { it.texte.contains("CV") } },
        cas("créer", "Mariage de Julie et Thomas à Annecy le 20 juin ${auj.year + 1}.") { val r = rv("Mariage*").firstOrNull(); r != null && r.quand.year == auj.year + 1 && r.lieu.contains("Annecy") },
        cas("créer", "Anniversaire de mamie dimanche, apporte le gâteau.") { val r = rv("Anniversaire de*amie*").firstOrNull(); r != null && r.aPreparer.any { it.texte.contains("teau") } },
        // Dicté : les noms passent d'abord par la correction
        cas("dictée", "dicté:Rendez-vous chez l'Oftalmologue à messe jeudi à 10h.") { val r = rv("*phtalmo*").firstOrNull(); jour(r, prochain(JEU), "10:00") && r!!.lieu.contains("Metz") },
        cas("dictée", "dicté:Courses à le clair samedi matin : pain, lait.") { val r = rv("Courses*").firstOrNull(); r != null && r.aPreparer.size == 2 && (r.lieu.contains("Leclerc") || r.titre.contains("Leclerc")) },
        cas("dictée", "dicté:Dentiste à Epinal le 20 novembre à 9h.") { rv("*entiste*").any { it.lieu.contains("Épinal") } },
        // Changer, détail par détail
        cas("changer", "Le dentiste c'est à Nancy maintenant.") { rv("Dentiste")[0].lieu.contains("Nancy") && jour(rv("Dentiste")[0], dentiste, "15:00") },
        cas("changer", "Change l'adresse du dentiste : 5 place Stanislas à Nancy.") { rv("Dentiste")[0].lieu.contains("Stanislas") },
        cas("changer", "Déplace la soirée chez Max à samedi 20 h et ajoute Lucas.") { val r = rv("Soirée chez Max")[0]; jour(r, prochain(SAM), "20:00") && "Lucas" in r.avec },
        cas("changer", "Le foot, c'est au stade Saint-Symphorien à Metz, ajoute Tom et mets-le en important.") { val r = rv("Foot")[0]; r.lieu.contains("Saint-Symphorien") && "Tom" in r.avec && r.important },
        cas("changer", "Pour le dentiste, ajoute la note : apporter les radios de l'an dernier.") { rv("Dentiste")[0].notes.contains("radios") },
        cas("changer", "Mets le lien https://discord.gg/xyz pour le raid.") { rv("Raid avec les potes")[0].lien.contains("discord.gg/xyz") },
        cas("changer", "Le raid c'est désormais tous les vendredis à 21 h.") { val r = rv("Raid avec les potes")[0]; rep(r)?.jour == 5 && r.quand.hour == 21 && r.quand.minute == 0 },
        cas("changer", "Le loyer, c'est le 10 du mois maintenant.") { rep(rv("Payer le loyer")[0])?.jour == 10 },
        cas("changer", "Arrête le raid après le 31 décembre.") { rep(rv("Raid avec les potes")[0])?.jusqua?.endsWith("-12-31") == true },
        cas("changer", "Le dentiste dure une heure et demie.") { rv("Dentiste")[0].fin?.toLocalTime() == LocalTime.of(16, 30) },
        cas("changer", "Rappelle-moi deux jours avant le dentiste et le matin même.") { val r = rv("Dentiste")[0].rappels.orEmpty(); "matin" in r && r.any { it.startsWith("m") && it != "matin" && (it.removePrefix("m").toIntOrNull() ?: 0) >= 2880 } },
        cas("changer", "Enlève la carte vitale de la liste du dentiste.") { rv("Dentiste")[0].aPreparer.none { it.texte == "Carte Vitale" } && rv("Dentiste")[0].aPreparer.size == 1 },
        cas("changer", "Coche l'ordonnance pour le dentiste.") { rv("Dentiste")[0].aPreparer.first { it.texte == "Ordonnance" }.fait },
        cas("changer", "Renomme la soirée chez Max en crémaillère chez Max.") { rv("Crémaillère chez Max").isNotEmpty() },
        cas("changer", "Le foot n'est plus le ${nomJour(foot)} mais le dimanche.") { jour(rv("Foot").firstOrNull(), prochain(DayOfWeek.SUNDAY), "19:00") },
        cas("changer", "Change le titre du coiffeur en barbier.") { rv("Barbier").isNotEmpty() && rv("Coiffeur").isEmpty() },
        cas("changer", "Rajoute Inès et Paul au foot, et Tom au dentiste.") { val f = rv("Foot")[0].avec; "Inès" in f && "Paul" in f && "Tom" in rv("Dentiste")[0].avec },
        // Supprimer
        cas("supprimer", "Supprime le dentiste de ${nomJour(dentiste)}.") { rv("Dentiste").isEmpty() && taille == 7 },
        cas("supprimer", "Supprime le raid du ${lea.plusDays(7).dayOfMonth} et celui du ${lea.plusDays(14).dayOfMonth}.") { val r = rv("Raid avec les potes")[0]; listOf(7L, 14L).all { k -> entre(lea.plusDays(k), lea.plusDays(k + 1)).none { it.rv.id == r.id } } && entre(lea, lea.plusDays(1)).any { it.rv.id == r.id } },
        cas("supprimer", "Annule tous les raids jusqu'à la fin de l'année.") { val r = rv("Raid avec les potes").firstOrNull(); r == null || r.quand.year > auj.year },
        cas("supprimer", "Supprime tout ce qui est passé.") { rv("Coiffeur").isEmpty() && taille == 7 },
        cas("supprimer", "Vide ma journée de demain.") { entre(lea, lea.plusDays(1)).none { it.rv.sorte != Sorte.ANNIV } && rv("Foot").isNotEmpty() },
        // C'est fait
        cas("fait", "J'ai fini de payer le loyer.") { rv("Payer le loyer")[0].fait },
        cas("fait", "Appeler maman : c'est fait.") { rv("Appeler maman")[0].fait },
        // Questions
        cas("question", "Où est-ce que je dois aller ${nomJour(dentiste)} ?") { taille == 8 && dit.contains("Lilas") },
        cas("question", "Qu'est-ce que je dois préparer pour la soirée chez Max ?") { taille == 8 && dit.contains("Soirée") },
        cas("question", "C'est quoi le code pour le dentiste ?") { taille == 8 && dit.contains("4521B") },
        cas("question", "Combien de temps entre maintenant et le foot ?") { taille == 8 && dit.contains("4 jours") },
        cas("question", "Quel jour tombe l'anniversaire de Léa ?") { taille == 8 && (dit.contains(nomJour(lea), true) || dit.contains("demain")) },
        cas("question", "Est-ce que le dentiste est avant la soirée chez Max ?") { taille == 8 && dit.contains("Dentiste est avant") },
        cas("question", "Liste-moi mes rendez-vous importants.") { taille == 8 && dit.contains("Dentiste") },
        cas("question", "J'ai quoi comme rendez-vous médicaux ?") { taille == 8 && dit.contains("Dentiste") && !dit.contains("Foot") },
        cas("question", "Montre-moi mes sorties.") { taille == 8 && dit.contains("Soirée") && !dit.contains("Dentiste") },
        cas("question", "Quels sont les rendez-vous où Inès vient ?") { taille == 8 && dit.contains("Soirée") && !dit.contains("Dentiste") },
        cas("question", "Est-ce que j'ai quelque chose à Lyon ?") { taille == 8 && dit.contains("Dentiste") },
        // Postes de travail
        cas("postes", "Je bosse de nuit du lundi au jeudi la semaine prochaine.") { rv("Travail (nuit)").size == 4 },
        // Enchaînements
        cas("suite", "Ajoute un resto vendredi à 20 h.", "Avec Julie et Marc.") { val r = rv("Resto").firstOrNull(); r != null && "Julie" in r.avec && "Marc" in r.avec },
        cas("suite", "Crée un rendez-vous chez le garagiste.", "Lundi à 8 h.") { jour(rv("*aragiste*").firstOrNull(), prochain(LUN), "08:00") },
        cas("suite", "C'est quand le foot ?", "Mets-le une heure plus tard.") { jour(rv("Foot").firstOrNull(), foot, "20:00") },
        cas("suite", "Supprime le coiffeur.", "Et ajoute un barbier à la place jeudi à 10 h.") { rv("Coiffeur").isEmpty() && jour(rv("Barbier").firstOrNull(), prochain(JEU), "10:00") },
        cas("suite", "Qu'est-ce que j'ai ${nomJour(soiree)} ?", "Supprime tout ça.") { rv("Soirée chez Max").isEmpty() && rv("Dentiste").isNotEmpty() },
        cas("suite", "Ajoute un ciné samedi à 21 h.", "À Metz, avec Paul.") { val r = rv("Cin*").firstOrNull(); r != null && r.lieu.contains("Metz") && "Paul" in r.avec },
        // Pièges
        cas("piège", "Je ne sais pas encore quand je vais chez le coiffeur.") { taille == 8 && rv("Coiffeur").size == 1 },
        cas("piège", "Combien coûte le dentiste ?") { taille == 8 },
        cas("piège", "Merci, c'est parfait.") { taille == 8 },
    )

    /** Les mêmes fonctions que « couverture », dites autrement (écrites après les corrections, mesurées avant retouche). */
    private val couvertureVerif: List<Cas> get() = listOf(
        cas("créer", "Réunion parents-profs au collège de Woippy jeudi à 18 h avec ma femme.") { val r = rv("R*union*").firstOrNull(); jour(r, prochain(JEU), "18:00") },
        cas("créer", "Kiné à Metz mardi et vendredi à 17 h 30 jusqu'à fin novembre.") { val r = rv("Kin*").firstOrNull(); rep(r)?.jours?.size == 2 && rep(r)?.jusqua?.endsWith("-11-30") == true && r!!.lieu.contains("Metz") },
        cas("créer", "Rendez-vous chez le dermatologue au 3 avenue Foch à Nancy le 12 novembre à 11 h, pense à prendre mes anciennes ordonnances.") { val r = rv("*ermato*").firstOrNull(); r != null && r.lieu.contains("Foch") && r.aPreparer.isNotEmpty() && !r.titre.contains("Foch") },
        cas("créer", "Week-end à Colmar du 7 au 9 novembre avec Julie, préviens-moi la veille.") { val r = rv("Week-end*").firstOrNull(); r != null && r.lieu.contains("Colmar") && "Julie" in r.avec && r.rappels?.contains("veille") == true },
        cas("créer", "Concert de jazz à Metz samedi à 20 h 30, c'est important.") { val r = rv("Concert*").firstOrNull(); jour(r, prochain(SAM), "20:30") && r!!.important && r.lieu.contains("Metz") },
        cas("créer", "Ajoute l'anniversaire de mon frère Kevin le 2 avril, il aura 25 ans.") { rv("Anniversaire de*Kevin*").firstOrNull()?.naissance == le(2, 4).year - 25 },
        cas("créer", "Visio avec le client lundi à 9 h, voici le lien : https://zoom.us/j/123456") { rv("Visio*").firstOrNull()?.lien?.contains("zoom.us") == true },
        cas("créer", "Courses chez Carrefour vendredi à 18 h, il faut du riz, des pâtes et de l'huile.") { val r = rv("Courses*").firstOrNull(); r != null && r.aPreparer.size == 3 },
        cas("créer", "Rendez-vous à la mairie de Metz le 5 novembre à 10 h, apporte ma carte d'identité et un justificatif de domicile.") { val r = rv("*airie*").firstOrNull(); r != null && r.aPreparer.size == 2 },
        cas("créer", "Pense à sortir les poubelles tous les mardis soir à 20 h.") { val r = rv("*oubelles*").firstOrNull(); rep(r)?.jour == 2 && r!!.quand.hour == 20 },
        cas("créer", "Dentiste à Thionville jeudi à 16 h.") { rv("Dentiste").size == 2 && rv("Dentiste").any { it.lieu.contains("Thionville") } },
        cas("dictée", "dicté:Rendez-vous chez le kinésithérapeuthe à Mets lundi à 18h.") { val r = rv("*in*sith*rapeute*").firstOrNull(); r != null && r.lieu.contains("Metz") },
        cas("dictée", "dicté:Train pour besan son dimanche à 8h.") { rv("Train*").firstOrNull()?.let { it.lieu.contains("Besançon") || it.titre.contains("Besançon") } == true },
        cas("changer", "Le dentiste a déménagé au 8 rue de la Gare à Metz.") { rv("Dentiste")[0].lieu.contains("rue de la Gare") },
        cas("changer", "Ajoute le lien https://teams.microsoft.com/xyz à la soirée chez Max.") { rv("Soirée chez Max")[0].lien.contains("teams") },
        cas("changer", "Le raid passe à tous les jeudis à 22 h.") { val r = rv("Raid avec les potes")[0]; rep(r)?.jour == 4 && r.quand.hour == 22 },
        cas("changer", "Je ne veux plus de rappel la veille pour le dentiste.") { rv("Dentiste")[0].rappels?.contains("veille") == false && rv("Dentiste").isNotEmpty() },
        cas("changer", "Retire l'ordonnance des choses à prendre pour le dentiste.") { rv("Dentiste")[0].aPreparer.none { it.texte == "Ordonnance" } },
        cas("changer", "J'ai pris la carte vitale pour le dentiste, coche-la.") { rv("Dentiste")[0].aPreparer.first { it.texte == "Carte Vitale" }.fait },
        cas("changer", "Ajoute Lucas au raid et Sophie à la soirée chez Max.") { "Lucas" in rv("Raid avec les potes")[0].avec && "Sophie" in rv("Soirée chez Max")[0].avec },
        cas("changer", "Le foot ce n'est plus à 19 h mais à 20 h 30.") { jour(rv("Foot").firstOrNull(), foot, "20:30") },
        cas("changer", "La soirée chez Max se fera à Metz.") { rv("Soirée chez Max")[0].lieu.contains("Metz") },
        cas("supprimer", "Vide mon week-end.") { taille <= 8 },
        cas("supprimer", "Supprime le raid de la semaine prochaine et le foot.") { rv("Foot").isEmpty() && rv("Raid avec les potes").isNotEmpty() },
        cas("fait", "Le loyer, c'est payé.") { rv("Payer le loyer")[0].fait },
        cas("question", "Qu'est-ce que j'ai comme sport cette semaine ?") { taille == 8 && !dit.contains("Dentiste") },
        cas("question", "Quand est-ce que je vois Max ?") { taille == 8 && dit.contains("Soirée") },
        cas("question", "Où se passe la soirée chez Max ?") { taille == 8 && dit.isNotBlank() },
        cas("question", "Combien de temps il reste avant l'anniversaire de Léa ?") { taille == 8 && dit.contains("demain") },
        cas("question", "Quelles sont mes choses à faire ?") { taille == 8 && dit.contains("loyer", true) },
        cas("question", "Est-ce que j'ai un rendez-vous à Lyon ?") { taille == 8 && dit.contains("Dentiste") },
        cas("question", "Le foot est après le dentiste ?") { taille == 8 && dit.startsWith("Dentiste") },
        cas("suite", "Ajoute un bowling samedi à 15 h.", "Avec Tom, Inès et Max.") { rv("Bowling").firstOrNull()?.avec?.size == 3 },
        cas("suite", "Crée un rendez-vous chez l'opticien.", "Mercredi à 14 h.") { jour(rv("*pticien*").firstOrNull(), prochain(DayOfWeek.WEDNESDAY), "14:00") },
        cas("suite", "Qu'est-ce que j'ai demain ?", "Supprime-les.") { entre(lea, lea.plusDays(1)).isEmpty() && rv("Foot").isNotEmpty() },
        cas("suite", "Ajoute un resto jeudi à 20 h.", "Au Bouchon, avec Julie.") { val r = rv("Resto").firstOrNull(); r != null && r.lieu.contains("Bouchon") && "Julie" in r.avec },
        cas("suite", "Quand est le raid ?", "Décale-le à 22 h.") { rv("Raid avec les potes")[0].quand.hour == 22 },
        cas("piège", "Le métro de Metz est en grève demain.") { taille == 8 },
        cas("piège", "Je mets combien de temps pour aller au dentiste ?") { taille == 8 },
    )

    /** Un troisième tour sur toute l'app, d'autres tournures (écrit après les corrections, mesuré avant retouche). */
    private val tour3: List<Cas> get() = listOf(
        cas("créer", "Salut, tu peux me caler un rendez-vous chez l'ostéo à Thionville mercredi prochain vers 17 h ?") { val r = rv("*st*o*").firstOrNull { it.titre.contains("st", true) }; r != null && r.quand.hour == 17 && r.lieu.contains("Thionville") },
        cas("créer", "Je vais chez ma tante à Sarreguemines dimanche midi avec maman.") { val r = agenda.firstOrNull { it.lieu.contains("Sarreguemines") || it.titre.contains("Sarreguemines") }; r != null && r.quand.dayOfWeek == DayOfWeek.SUNDAY && r.quand.hour == 12 },
        cas("créer", "Note un cours de piano chaque samedi à 10 h à partir du 7 novembre.") { val r = rv("*iano*").firstOrNull(); rep(r)?.jour == 6 && r!!.quand.monthValue == 11 && r.quand.dayOfMonth == 7 },
        cas("créer", "Programme une réunion d'équipe le dernier vendredi de novembre à 16 h.") { rv("R*union*").firstOrNull()?.quand?.let { it.monthValue == 11 && it.dayOfWeek == VEN && it.dayOfMonth >= 24 && it.hour == 16 } == true },
        cas("créer", "Mets un rappel pour appeler la mutuelle lundi à 9 h, c'est urgent.") { val r = rv("*mutuelle*").firstOrNull(); jour(r, prochain(LUN), "09:00") && r!!.important },
        cas("créer", "Barbecue chez Julien samedi à 12 h, il faut apporter des saucisses, du pain et des chips.") { val r = rv("Barbecue*").firstOrNull(); r != null && r.aPreparer.size == 3 && !r.titre.contains("saucisses") },
        cas("créer", "Rendez-vous chez le pédiatre pour Léo le 6 novembre à 15 h 45 au 2 place d'Armes à Metz.") { val r = rv("*diatre*").firstOrNull(); jour(r, le(6, 11), "15:45") && r!!.lieu.contains("place d'Armes") },
        cas("créer", "Ajoute le match du FC Metz à Saint-Symphorien samedi à 20 h avec Tom.") { val r = rv("Match*").firstOrNull(); jour(r, prochain(SAM), "20:00") && "Tom" in r!!.avec },
        cas("dictée", "dicté:Rendez-vous chez le gynéco logue à Forbach jeudi à 11h.") { val r = rv("*yn*co*").firstOrNull(); r != null && r.lieu.contains("Forbach") },
        cas("changer", "En fait le dentiste ce sera mardi prochain à la même heure.") { val r = rv("Dentiste").firstOrNull(); r != null && r.quand.dayOfWeek == MAR && r.quand.hour == 15 },
        cas("changer", "Tu peux mettre le foot à Woippy plutôt ?") { rv("Foot")[0].lieu.contains("Woippy") },
        cas("changer", "Pour la soirée chez Max, Inès ne vient plus mais Clara vient.") { val a = rv("Soirée chez Max")[0].avec; "Inès" !in a && "Clara" in a },
        cas("changer", "Ajoute aux courses du loyer... non, au dentiste : penser à la mutuelle.") { rv("Dentiste")[0].aPreparer.any { it.texte.contains("mutuelle", true) } || rv("Dentiste")[0].notes.contains("mutuelle") },
        cas("changer", "Le raid de demain commence plus tôt, à 20 h.") { val r = rv("Raid avec les potes")[0]; entre(lea, lea.plusDays(1)).any { it.rv.id == r.id && it.prochain.hour == 20 } },
        cas("changer", "Préviens-moi 30 minutes avant la soirée chez Max au lieu d'une heure.") { rv("Soirée chez Max")[0].rappels?.contains("m30") == true },
        cas("changer", "Le loyer c'est fait pour ce mois-ci.") { rv("Payer le loyer")[0].fait || rep(rv("Payer le loyer")[0])?.sauf != null },
        cas("supprimer", "Retire tous les raids de décembre sauf celui de Noël.") { (rep(rv("Raid avec les potes").firstOrNull())?.sauf?.count { it.contains("-12-") } ?: 0) >= 3 },
        cas("supprimer", "Bon, le coiffeur c'est passé, tu peux l'effacer.") { rv("Coiffeur").isEmpty() && taille == 7 },
        cas("supprimer", "Je n'ai plus besoin du rendez-vous de ${nomJour(foot)} soir.") { rv("Foot").isEmpty() && taille == 7 },
        cas("question", "C'est à quelle adresse la soirée chez Max ?") { taille == 8 && dit.isNotBlank() },
        cas("question", "Qu'est-ce que je fais ${nomJour(dentiste)} après-midi ?") { taille == 8 && dit.contains("Dentiste") },
        cas("question", "Est-ce que j'ai déjà payé le loyer ?") { taille == 8 && dit.contains(Regex("non|pas encore|pas fait", RegexOption.IGNORE_CASE)) },
        cas("question", "Combien de choses j'ai à préparer pour le dentiste ?") { taille == 8 && dit.contains("Vitale", true) },
        cas("question", "C'est qui qui vient à la soirée ?") { taille == 8 && dit.contains("Inès") },
        cas("question", "Mon prochain rendez-vous médical c'est quand ?") { taille == 8 && dit.contains("Dentiste") },
        cas("suite", "Ajoute un dîner vendredi à 20 h.", "C'est chez Paul, à Nancy.") { val r = rv("D*ner*").firstOrNull(); r != null && r.lieu.contains("Nancy") },
        cas("suite", "Crée un rendez-vous chez le vétérinaire jeudi.", "À 17 h.") { jour(rv("*t*rinaire*").firstOrNull(), prochain(JEU), "17:00") },
        cas("suite", "Qu'est-ce que j'ai cette semaine ?", "Supprime le foot de la liste.") { rv("Foot").isEmpty() && taille == 7 },
        cas("piège", "Rappelle-moi pourquoi j'ai mis le dentiste.") { taille == 8 },
        cas("piège", "Le foot c'est vraiment important pour moi.") { taille == 8 && rv("Foot").isNotEmpty() },
    )

    /** Tour 4 : les détails fins (heures, dates, répétitions, changements groupés, remplacements, listes, questions pointues). */
    private val tour4: List<Cas> get() = listOf(
        // Heures et dates dites autrement
        cas("heure", "Rendez-vous chez le notaire mardi à 8 h du soir.") { jour(rv("*otaire*").firstOrNull(), prochain(MAR), "20:00") },
        cas("heure", "Déjeuner avec Paul jeudi à midi et demi.") { jour(rv("D*jeuner*").firstOrNull(), prochain(JEU), "12:30") },
        cas("heure", "Réunion vendredi à 15 h 15 pendant deux heures.") { val r = rv("R*union*").firstOrNull(); jour(r, prochain(VEN), "15:15") && r!!.fin?.toLocalTime() == LocalTime.of(17, 15) },
        cas("heure", "Rendez-vous à la banque après-demain vers 14 h.") { jour(rv("*anque*").firstOrNull(), auj.plusDays(2), "14:00") },
        cas("date", "Contrôle technique dans dix jours à 9 h.") { jour(rv("Contr*le*").firstOrNull(), auj.plusDays(10), "09:00") },
        cas("date", "Rendez-vous chez le coiffeur lundi en huit à 10 h.") { rv("Coiffeur").any { jour(it, prochain(LUN).plusDays(7), "10:00") } },
        cas("date", "Mets une soirée jeux le samedi de la semaine prochaine à 20 h.") { val l = auj.plusDays(7L - ((auj.dayOfWeek.value + 6) % 7)); jour(rv("*jeux*").firstOrNull(), l.plusDays(5), "20:00") },
        cas("date", "Visite de l'appartement début décembre, le 2 à 18 h.") { jour(rv("Visite*").firstOrNull(), le(2, 12), "18:00") },
        cas("répéter", "Badminton un jeudi sur deux à 19 h.") { val r = rep(rv("Badminton").firstOrNull()); r?.intervalle == 2 && r.jour == 4 },
        cas("répéter", "Payer la nounou tous les 28 du mois.") { val r = rep(rv("*nounou*").firstOrNull()); r?.type == "mois" && r.jour == 28 },
        cas("répéter", "Réveil du lundi au vendredi à 6 h 30.") { val r = rv("R*veil*").firstOrNull(); rep(r)?.jours?.size == 5 && r!!.quand.minute == 30 },
        cas("répéter", "Fête nationale tous les ans le 14 juillet.") { val r = rep(rv("F*te nationale").firstOrNull()); r?.type == "an" && r.mois == 7 && r.jour == 14 },
        // Rappels précis
        cas("rappel", "Dentiste lundi à 9 h, rappelle-moi 3 jours avant.") { val r = rv("Dentiste").firstOrNull { it.quand.dayOfWeek == LUN }; r?.rappels?.any { it == "m4320" } == true },
        cas("rappel", "Préviens-moi 15 minutes avant le foot.") { rv("Foot")[0].rappels?.contains("m15") == true },
        cas("rappel", "Pas de rappel pour le raid.") { rv("Raid avec les potes")[0].rappels?.isEmpty() == true },
        // Changements groupés ou précis
        cas("groupé", "Décale tout ce que j'ai ${nomJour(dentiste)} d'une heure.") { jour(rv("Dentiste").firstOrNull(), dentiste, "16:00") },
        cas("groupé", "Mets tous mes rendez-vous de ${nomJour(foot)} au ${nomJour(foot.plusDays(1))}.") { jour(rv("Foot").firstOrNull(), foot.plusDays(1), "19:00") },
        cas("durée", "Raccourcis le dentiste à 20 minutes.") { rv("Dentiste")[0].fin?.toLocalTime() == LocalTime.of(15, 20) },
        cas("durée", "Le dentiste finit à 15 h 45 finalement.") { val r = rv("Dentiste")[0]; r.quand.hour == 15 && r.quand.minute == 0 && r.fin?.toLocalTime() == LocalTime.of(15, 45) },
        cas("durée", "Avance le foot de 30 minutes.") { jour(rv("Foot").firstOrNull(), foot, "18:30") },
        cas("date", "Le foot c'est le ${foot.plusDays(3).dayOfMonth} au lieu du ${foot.dayOfMonth}.") { jour(rv("Foot").firstOrNull(), foot.plusDays(3), "19:00") },
        cas("personnes", "Remplace Tom par Lucas à la soirée chez Max.") { val a = rv("Soirée chez Max")[0].avec; "Tom" !in a && "Lucas" in a },
        cas("personnes", "Ajoute ma mère à la soirée chez Max.") { rv("Soirée chez Max")[0].avec.any { it.equals("Maman", true) || it.contains("mère", true) } },
        cas("personnes", "Enlève tout le monde de la soirée chez Max.") { rv("Soirée chez Max")[0].avec.isEmpty() },
        cas("liste", "Vide la liste du dentiste.") { rv("Dentiste")[0].aPreparer.isEmpty() },
        cas("liste", "Décoche la carte vitale du dentiste.") { rv("Dentiste")[0].aPreparer.first { it.texte == "Carte Vitale" }.fait.not() },
        cas("liste", "Courses samedi à 10 h : lait, pain.", "Remplace le lait par du lait d'avoine dans les courses.") { rv("Courses").firstOrNull()?.aPreparer?.map { it.texte } == listOf("Lait d'avoine", "Pain") },
        cas("note", "Efface la note du dentiste.") { rv("Dentiste")[0].notes.isBlank() },
        cas("note", "Ajoute à la note du dentiste : deuxième étage à gauche.") { rv("Dentiste")[0].notes.contains("gauche") && rv("Dentiste")[0].notes.contains("4521B") },
        cas("lieu", "Enlève l'adresse du dentiste.") { rv("Dentiste")[0].lieu.isBlank() },
        cas("important", "Le dentiste n'est plus prioritaire.") { !rv("Dentiste")[0].important },
        cas("dupliquer", "Duplique le dentiste au mardi suivant.") { val d = rv("Dentiste"); d.size == 2 && d.any { it.quand.toLocalDate() == dentiste.plusDays(((2 - dentiste.dayOfWeek.value + 7) % 7).toLong().let { if (it == 0L) 7 else it }) && it.quand.hour == 15 && it.lieu.contains("Lilas") } },
        // Supprimer précisément
        cas("supprimer", "Supprime tous les rendez-vous avec Max.") { rv("Soirée chez Max").isEmpty() && taille == 7 },
        cas("supprimer", "Ajoute piscine demain à 8 h.", "Supprime le dernier rendez-vous que j'ai ajouté.") { taille == 8 && rv("Piscine").isEmpty() },
        cas("supprimer", "Supprime les rendez-vous du dentiste et du coiffeur.") { rv("Dentiste").isEmpty() && rv("Coiffeur").isEmpty() && taille == 6 },
        // Fait / pas fait
        cas("fait", "Décoche le loyer, je ne l'ai pas encore payé.", ) { !rv("Payer le loyer")[0].fait },
        cas("fait", "J'ai payé le loyer.", "Non en fait je ne l'ai pas payé.") { !rv("Payer le loyer")[0].fait },
        // Questions pointues
        cas("question", "Qu'est-ce que j'ai entre ${nomJour(dentiste)} et ${nomJour(soiree)} ?") { taille == 8 && dit.contains("Dentiste") && dit.contains("Foot") },
        cas("question", "Combien de raids j'ai ce mois-ci ?") { taille == 8 && dit.contains(Regex("\\d")) },
        cas("question", "C'est quand mon prochain jour sans rien ?") { taille == 8 && dit.isNotBlank() && !dit.startsWith("Je n'ai pas") },
        cas("question", "Qu'est-ce que j'avais avant-hier ?") { taille == 8 && dit.contains("Coiffeur") },
        cas("question", "Quels rendez-vous ont un rappel la veille ?") { taille == 8 && dit.contains("Dentiste") },
        cas("question", "À quelle heure commence la soirée chez Max ?") { taille == 8 && dit.contains("21") },
        cas("question", "La soirée chez Max finit à quelle heure ?") { taille == 8 && dit.isNotBlank() },
        // Pièges
        cas("piège", "Le dentiste m'a dit de bien me brosser les dents.") { taille == 8 && jour(rv("Dentiste").firstOrNull(), dentiste, "15:00") },
        cas("piège", "Je déteste aller chez le dentiste.") { taille == 8 },
        cas("piège", "Tom m'a invité à la soirée chez Max.") { taille == 8 && rv("Soirée chez Max").isNotEmpty() },
    )

    /** Tour 5 : sans ponctuation, fautes de dictée, ordres collés, répétitions rares, questions doubles, suites de trois phrases. */
    private val tour5: List<Cas> get() = listOf(
        // Dicté sans ponctuation
        cas("oral", "rajoute un rendez-vous chez le kiné jeudi a 18h30 a woippy") { val r = rv("*in*").firstOrNull { it.titre.contains("ki", true) }; jour(r, prochain(JEU), "18:30") && r!!.lieu.contains("Woippy", true) },
        cas("oral", "supprime le foot et le coiffeur") { rv("Foot").isEmpty() && rv("Coiffeur").isEmpty() && taille == 6 },
        cas("oral", "decale le dentiste a 17h") { jour(rv("Dentiste").firstOrNull(), dentiste, "17:00") },
        cas("oral", "c'est quand le prochain raid") { taille == 8 && dit.contains(Regex("demain|${lea.dayOfMonth}")) },
        cas("oral", "dicté:Rendez-vous chez le dentiste à messe vendredi prochain à 9h et rappelle-moi la veille.") { val r = rv("Dentiste").firstOrNull { it.lieu.contains("Metz") }; r != null && r.rappels?.contains("veille") == true },
        cas("oral", "dicté:Courses chez Lidl à Thionville samedi : lait, pain, beurre.") { val r = rv("Courses*").firstOrNull(); r != null && r.aPreparer.size == 3 },
        // Fautes de frappe ou de dictée
        cas("fautes", "Supprimme le raid de demain.") { val r = rv("Raid avec les potes").firstOrNull(); r != null && entre(lea, lea.plusDays(1)).none { it.rv.id == r.id } },
        cas("fautes", "Décalle le foot a 20h.") { jour(rv("Foot").firstOrNull(), foot, "20:00") },
        cas("fautes", "Rajoutes Paul a la soiré chez Max.") { "Paul" in rv("Soirée chez Max")[0].avec },
        // Ordres collés
        cas("collés", "Mets le dentiste à 16 h, ajoute Tom, préviens-moi une heure avant et marque-le important.") { val r = rv("Dentiste")[0]; r.quand.hour == 16 && "Tom" in r.avec && r.rappels?.contains("m60") == true && r.important },
        cas("collés", "Crée un rendez-vous chez le médecin lundi à 10 h, mets-le important et ajoute ma carte vitale à prendre.") { val r = rv("*decin*").firstOrNull(); r != null && r.important && r.aPreparer.any { it.texte.contains("vitale", true) } },
        cas("collés", "Supprime le coiffeur puis décale le foot à dimanche et ajoute un tennis samedi à 10 h.") { rv("Coiffeur").isEmpty() && jour(rv("Foot").firstOrNull(), prochain(DayOfWeek.SUNDAY), "19:00") && jour(rv("Tennis").firstOrNull(), prochain(SAM), "10:00") },
        // Répétitions rares
        cas("répéter", "Cours de dessin tous les mardis et jeudis à 17 h sauf pendant les vacances de Noël.") { rv("*essin*").isNotEmpty() },
        cas("répéter", "Réunion d'équipe le deuxième mardi de chaque mois à 9 h.") { rep(rv("R*union*").firstOrNull())?.rang == 2 },
        cas("répéter", "Arrosage des plantes tous les 3 jours.") { rv("*rrosage*").isNotEmpty() },
        cas("répéter", "Médicament matin et soir à 8 h et 20 h tous les jours.") { rv("M*dicament*").isNotEmpty() },
        cas("répéter", "Le raid sera toutes les deux semaines à partir de maintenant.") { rep(rv("Raid avec les potes")[0])?.intervalle == 2 },
        // Questions doubles ou piégées
        cas("question", "Où et à quelle heure est le dentiste ?") { taille == 8 && dit.contains("Lilas") && dit.contains("15") },
        cas("question", "Qu'est-ce que j'ai demain et après-demain ?") { taille == 8 && dit.contains(Regex("Léa|raid", RegexOption.IGNORE_CASE)) },
        cas("question", "Est-ce que j'ai quelque chose ce week-end et avec qui ?") { taille == 8 && dit.isNotBlank() },
        cas("question", "Le dentiste et le foot, c'est quels jours ?") { taille == 8 && dit.contains(nomJour(dentiste), true) && dit.contains(nomJour(foot), true) },
        cas("question", "Combien de rendez-vous j'ai avec Max ?") { taille == 8 && dit.contains("1") },
        cas("question", "Qu'est-ce que je fais le ${soiree.dayOfMonth} ?") { taille == 8 && dit.contains("Soirée") },
        cas("question", "C'est à quelle heure l'anniversaire de Léa ?") { taille == 8 && dit.isNotBlank() },
        cas("question", "Y a-t-il quelque chose de prévu le soir de l'anniversaire de Léa ?") { taille == 8 && dit.contains("aid", true) },
        // Suites de trois phrases
        cas("suite", "Ajoute un dîner samedi.", "À 20 h.", "Avec Julie, chez elle à Metz.") { val r = rv("D*ner*").firstOrNull(); jour(r, prochain(SAM), "20:00") && "Julie" in r!!.avec && r.lieu.contains("Metz") },
        cas("suite", "Décale le rendez-vous.", "Le foot.", "Dimanche à 18 h.") { jour(rv("Foot").firstOrNull(), prochain(DayOfWeek.SUNDAY), "18:00") },
        cas("suite", "Quand est la soirée chez Max ?", "Qui vient ?", "Ajoute Paul.") { "Paul" in rv("Soirée chez Max")[0].avec },
        cas("suite", "Crée un rendez-vous chez le garagiste vendredi à 9 h.", "Ah non, plutôt jeudi.", "Et note : vidange et pneus.") { val r = rv("*aragiste*").firstOrNull(); jour(r, prochain(JEU), "09:00") && (r!!.notes.contains("vidange", true) || r.aPreparer.size == 2) },
        cas("suite", "Supprime le dentiste.", "Annule.", "Décale-le plutôt à 17 h.") { jour(rv("Dentiste").firstOrNull(), dentiste, "17:00") },
        // Créer, détails mélangés
        cas("créer", "Anniversaire de Lucas samedi à 19 h chez lui à Yutz, apporte le cadeau et le gâteau.") { val r = rv("Anniversaire de Lucas").firstOrNull(); r != null && r.aPreparer.size == 2 },
        cas("créer", "Rendez-vous avec le conseiller bancaire au Crédit Agricole de Metz mardi à 11 h, apporte mes 3 derniers bulletins de salaire.") { val r = agenda.firstOrNull { it.quand.dayOfWeek == MAR && it.quand.hour == 11 }; r != null && r.aPreparer.isNotEmpty() },
        cas("créer", "Mets un rendez-vous important chez l'avocat jeudi 14 h, c'est pour le divorce.") { val r = rv("*vocat*").firstOrNull(); jour(r, prochain(JEU), "14:00") && r!!.important },
        cas("créer", "Je pars en week-end à Annecy vendredi soir et je reviens dimanche soir.") { val r = agenda.firstOrNull { it.lieu.contains("Annecy") || it.titre.contains("Annecy") }; r != null && r.fin != null },
        cas("créer", "Ajoute un rendez-vous demain à 10 h et un autre à 15 h chez le kiné.") { rv("*in*").count { it.titre.contains("ki", true) } >= 1 || taille >= 9 },
        // Changer, détails mélangés
        cas("changer", "Le dentiste c'est finalement vendredi prochain à 10 h au 4 rue Mazelle à Metz.") { val r = rv("Dentiste")[0]; r.quand.hour == 10 && r.quand.dayOfWeek == VEN && r.lieu.contains("Mazelle") },
        cas("changer", "Pour le foot, enlève Tom s'il y est et ajoute Hugo.") { "Hugo" in rv("Foot")[0].avec },
        cas("changer", "Remets le dentiste comme important.") { rv("Dentiste")[0].important },
        cas("changer", "Ajoute 2 bouteilles de vin à la liste de la soirée chez Max.") { rv("Soirée chez Max")[0].aPreparer.any { it.texte.contains("vin") } },
        cas("changer", "Coche tout ce qu'il y a à prendre pour le dentiste.") { rv("Dentiste")[0].aPreparer.all { it.fait } },
        // Supprimer, détails
        cas("supprimer", "Efface le rappel d'une heure avant du dentiste mais garde celui de la veille.") { val r = rv("Dentiste")[0].rappels.orEmpty(); "m60" !in r && "veille" in r },
        cas("supprimer", "Supprime tous les raids sauf ceux du mercredi.") { rv("Raid avec les potes").isNotEmpty() },
        cas("supprimer", "Supprime l'anniversaire de Léa mais seulement pour cette année.") { val r = rv("Anniversaire de Léa").firstOrNull(); r != null && entre(lea, lea.plusDays(1)).none { it.rv.id == r.id } },
        // Pièges
        cas("piège", "Supprime-moi ce stress, j'ai trop de rendez-vous.") { taille == 8 },
        cas("piège", "Mon dentiste est vraiment sympa.") { taille == 8 },
        cas("piège", "Rappelle-moi de rappeler maman.") { taille >= 8 && rv("Appeler maman").isNotEmpty() },
        cas("piège", "J'ai pas envie d'aller au foot samedi.") { rv("Foot").isNotEmpty() },
        cas("piège", "Est-ce que tu peux supprimer quelque chose ?") { taille == 8 },
    )

    /** La mesure : 20 phrases neuves, écrites après les tours 4 et 5, jamais corrigées pour elles. */
    private val mesure: List<Cas> get() = listOf(
        cas("créer", "Tu peux me noter un rendez-vous chez l'orthodontiste pour Emma mercredi à 16 h 30 à Metz ?") { val r = rv("*rthodontiste*").firstOrNull(); r != null && r.quand.hour == 16 && r.lieu.contains("Metz") },
        cas("créer", "Soirée raclette chez Hugo samedi à 19 h 30, ramène le fromage.") { val r = rv("Soir*e raclette*").firstOrNull(); jour(r, prochain(SAM), "19:30") && r!!.aPreparer.any { it.texte.contains("romage") } },
        cas("créer", "Faut que j'aille chercher le colis à la poste demain avant midi.") { rv("*colis*").isNotEmpty() },
        cas("créer", "Cours de natation pour les enfants tous les mercredis à 14 h jusqu'aux vacances de Noël.") { rep(rv("*atation*").firstOrNull())?.jour == 3 },
        cas("changer", "Le dentiste, finalement c'est à 14 h et ma sœur vient avec moi.") { val r = rv("Dentiste")[0]; r.quand.hour == 14 && r.avec.size >= 2 },
        cas("changer", "Pour le foot, rappelle-moi la veille et ajoute qu'il faut prendre les maillots.") { val r = rv("Foot")[0]; r.rappels?.contains("veille") == true && r.aPreparer.any { it.texte.contains("aillot") } },
        cas("changer", "Max a changé l'heure de la soirée, c'est 20 h maintenant.") { jour(rv("Soirée chez Max").firstOrNull(), soiree, "20:00") },
        cas("changer", "Déplace le raid de demain à jeudi, juste pour cette semaine.") { rv("Raid avec les potes").size >= 1 },
        cas("changer", "Le coiffeur, mets-le plutôt la semaine prochaine même jour même heure.") { rv("Coiffeur").firstOrNull()?.quand?.toLocalDate()?.isAfter(auj) == true },
        cas("supprimer", "Vire tout ce qui est prévu ${nomJour(foot)} soir.") { rv("Foot").isEmpty() && rv("Dentiste").isNotEmpty() },
        cas("supprimer", "Je n'ai plus de raid en novembre, enlève-les.") { (rep(rv("Raid avec les potes").firstOrNull())?.sauf?.count { it.contains("-11-") } ?: 0) >= 4 },
        cas("supprimer", "Le rendez-vous du dentiste est annulé, le cabinet est fermé.") { rv("Dentiste").isEmpty() },
        cas("fait", "C'est bon j'ai appelé maman et payé le loyer.") { rv("Appeler maman")[0].fait && rv("Payer le loyer")[0].fait },
        cas("question", "Qu'est-ce que je dois amener à la soirée chez Max ?") { taille == 8 && dit.isNotBlank() },
        cas("question", "C'est quoi mon programme pour le week-end ?") { taille == 8 && dit.isNotBlank() },
        cas("question", "À quelle heure je dois être chez le dentiste et c'est où ?") { taille == 8 && dit.contains("15") && dit.contains("Lilas") },
        cas("question", "Combien de jours avant la soirée chez Max ?") { taille == 8 && dit.contains("6 jours") },
        cas("suite", "Ajoute un ciné vendredi.", "Vers 21 h, avec Léa.") { val r = rv("Cin*").firstOrNull(); jour(r, prochain(VEN), "21:00") && "Léa" in r!!.avec },
        cas("suite", "Qu'est-ce que j'ai ${nomJour(dentiste)} ?", "Décale-le d'une heure.") { jour(rv("Dentiste").firstOrNull(), dentiste, "16:00") },
        cas("piège", "Je crois que le foot est annulé mais je ne suis pas sûr.") { rv("Foot").isNotEmpty() },
    )

    /**
     * Tour 6 : écrit pour comparer les règles, l'IA et leur mélange. Jamais utilisé pour corriger quoi que ce soit avant
     * que les trois aient été mesurés.
     */
    private val tour6: List<Cas> get() = listOf(
        cas("créer", "Peux-tu ajouter une séance d'ostéo pour mon dos mardi prochain à 11 h 15 à Woippy ?") { val r = agenda.firstOrNull { it.titre.contains("st", true) && it.quand.hour == 11 && it.quand.minute == 15 }; r != null && r.quand.dayOfWeek == MAR },
        cas("créer", "Mets-moi un déjeuner d'affaires avec monsieur Martin jeudi à 12 h 30 au restaurant Le Bistrot.") { jour(rv("D*jeuner*").firstOrNull(), prochain(JEU), "12:30") },
        cas("créer", "J'ai un vol pour Lisbonne le 22 novembre à 6 h 40, faut que je sois à l'aéroport deux heures avant.") { rv("Vol*").firstOrNull()?.let { it.quand.dayOfMonth == 22 && it.quand.hour == 6 } == true },
        cas("créer", "Rappelle-moi de rendre les clés de l'appart à l'agence le 30 à 17 h.") { rv("*cl*s*").firstOrNull()?.quand?.let { it.dayOfMonth == 30 && it.hour == 17 } == true },
        cas("créer", "Ajoute un pot de départ pour Sophie vendredi à 17 h au bureau, apporte une carte et des fleurs.") { val r = rv("Pot*").firstOrNull(); jour(r, prochain(VEN), "17:00") && r!!.aPreparer.size == 2 },
        cas("créer", "Tous les dimanches matin à 10 h, j'ai brunch avec les copines.") { val r = rv("Brunch*").firstOrNull(); rep(r)?.jour == 0 && r!!.quand.hour == 10 },
        cas("créer", "Note que la réunion de copropriété c'est le 18 novembre à 18 h 30 dans le hall.") { jour(rv("R*union*").firstOrNull(), le(18, 11), "18:30") },
        cas("créer", "Programme une visite chez mamie à Sarreguemines dimanche après-midi.") { rv("Visite*").firstOrNull()?.quand?.dayOfWeek == DayOfWeek.SUNDAY },
        cas("créer", "Cours de yoga tous les lundis et mercredis soir à 19 h, sauf en décembre.") { rep(rv("*oga*").firstOrNull())?.jours?.size == 2 },
        cas("créer", "Ajoute un rendez-vous chez le dentiste pour ma fille le 4 novembre à 16 h.") { rv("*entiste*").size == 2 },
        cas("changer", "Le dentiste m'a rappelé, on avance à jeudi 9 h.") { jour(rv("Dentiste").firstOrNull(), prochain(JEU), "09:00") },
        cas("changer", "Est-ce que tu peux décaler la soirée chez Max d'une demi-heure plus tôt ?") { jour(rv("Soirée chez Max").firstOrNull(), soiree, "20:30") },
        cas("changer", "Max ne peut plus le ${nomJour(soiree)}, mets la soirée le samedi suivant même heure.") { rv("Soirée chez Max").firstOrNull()?.quand?.let { it.dayOfWeek == SAM && it.hour == 21 && it.toLocalDate().isAfter(soiree) } == true },
        cas("changer", "Pour le foot, ce sera au gymnase de Woippy à cause de la pluie.") { rv("Foot")[0].lieu.contains("gymnase", true) },
        cas("changer", "Ajoute Julien et sa copine à la soirée chez Max.") { "Julien" in rv("Soirée chez Max")[0].avec },
        cas("changer", "Le raid, on le décale d'une heure à partir de maintenant, toutes les semaines.") { rv("Raid avec les potes")[0].quand.hour == 22 && rv("Raid avec les potes")[0].quand.minute == 30 },
        cas("changer", "Je dois aussi prendre mon carnet de santé pour le dentiste.") { rv("Dentiste")[0].aPreparer.any { it.texte.contains("carnet", true) } },
        cas("changer", "Note pour le foot : penser à la gourde.") { val r = rv("Foot")[0]; r.notes.contains("gourde") || r.aPreparer.any { it.texte.contains("gourde", true) } },
        cas("changer", "Le dentiste finalement ça dure trois quarts d'heure.") { rv("Dentiste")[0].fin?.toLocalTime() == LocalTime.of(15, 45) },
        cas("changer", "Mets l'anniversaire de Léa en important et préviens-moi trois jours avant.") { val r = rv("Anniversaire de Léa")[0]; r.important && r.rappels?.any { it == "m4320" || it.contains("3") } == true },
        cas("supprimer", "J'ai eu un empêchement, annule le dentiste et préviens Maman.") { rv("Dentiste").isEmpty() },
        cas("supprimer", "Le coiffeur, c'était avant-hier, tu peux le virer de l'agenda.") { rv("Coiffeur").isEmpty() && taille == 7 },
        cas("supprimer", "On arrête le raid pendant les vacances, supprime ceux de décembre.") { (rep(rv("Raid avec les potes").firstOrNull())?.sauf?.count { it.contains("-12-") } ?: 0) >= 4 || rep(rv("Raid avec les potes").firstOrNull())?.jusqua?.endsWith("-11-30") == true },
        cas("supprimer", "Retire Tom et Inès de la soirée chez Max, ils ne viennent pas.") { val a = rv("Soirée chez Max")[0].avec; "Tom" !in a && "Inès" !in a && "Max" in a },
        cas("supprimer", "Enlève le rappel de la veille pour l'anniversaire de Léa.") { rv("Anniversaire de Léa").isNotEmpty() && rv("Anniversaire de Léa")[0].rappels?.contains("veille") != true },
        cas("fait", "Voilà, maman est appelée.") { rv("Appeler maman")[0].fait },
        cas("fait", "Le loyer de ce mois est réglé.") { rv("Payer le loyer")[0].fait || rep(rv("Payer le loyer")[0])?.sauf != null },
        cas("question", "Rappelle-moi ce que j'ai prévu avec Max.") { taille == 8 && dit.contains("Soirée") },
        cas("question", "Est-ce que je suis dispo ${nomJour(dentiste)} vers 15 h ?") { taille == 8 && dit.contains("Dentiste") },
        cas("question", "J'ai quoi d'important bientôt ?") { taille == 8 && dit.contains("Dentiste") },
        cas("question", "Dans combien de temps j'ai la soirée chez Max et qui sera là ?") { taille == 8 && dit.contains("6 jours") && dit.contains("Inès") },
        cas("question", "Qu'est-ce qu'il faut que j'apporte demain ?") { taille == 8 && dit.isNotBlank() },
        cas("question", "C'est à quelle heure que je dois appeler maman ?") { taille == 8 && dit.contains(Regex("\\d{1,2} h")) },
        cas("question", "Le foot est-il avant ou après le dentiste cette semaine ?") { taille == 8 && dit.startsWith("Dentiste") },
        cas("question", "Combien de raids il reste avant la fin de l'année ?") { taille == 8 && dit.contains(Regex("\\d")) },
        cas("suite", "Ajoute un apéro jeudi.", "Plutôt vers 19 h.", "Avec Paul et Julie.") { val r = rv("Ap*ro*").firstOrNull(); jour(r, prochain(JEU), "19:00") && r!!.avec.containsAll(listOf("Paul", "Julie")) },
        cas("suite", "Qu'est-ce que j'ai samedi ?", "Mets-le à 18 h.") { jour(rv("Foot").firstOrNull(), foot, "18:00") },
        cas("suite", "Crée un rendez-vous chez l'opticien.", "Le 5 novembre.", "À 10 h 30.") { jour(rv("*pticien*").firstOrNull(), le(5, 11), "10:30") },
        cas("suite", "Supprime le foot.", "Ah non attends, remets-le.") { rv("Foot").isNotEmpty() },
        cas("suite", "Mets le dentiste à 16 h.", "Et préviens-moi deux heures avant.") { val r = rv("Dentiste")[0]; r.quand.hour == 16 && r.rappels?.contains("m120") == true },
        cas("dictée", "dicté:Rendez-vous chez le dermato logue à Nancy le 9 décembre à 14h.") { val r = rv("*ermato*").firstOrNull(); r != null && r.lieu.contains("Nancy") },
        cas("dictée", "dicté:Mets un rendez-vous chez Norotto à Woipy samedi à 9h pour les pneus.") { agenda.any { it.quand.dayOfWeek == SAM && it.quand.hour == 9 } },
        cas("piège", "Si le dentiste appelle, dis-lui que je suis malade.") { taille == 8 && rv("Dentiste").isNotEmpty() },
        cas("piège", "J'aurais aimé que le foot soit plus tôt.") { jour(rv("Foot").firstOrNull(), foot, "19:00") },
        cas("piège", "Max m'a demandé de décaler la soirée mais j'ai refusé.") { jour(rv("Soirée chez Max").firstOrNull(), soiree, "21:00") },
        cas("piège", "Est-ce qu'il faut que je supprime le raid à ton avis ?") { rv("Raid avec les potes").isNotEmpty() && rep(rv("Raid avec les potes")[0])?.sauf == null },
        cas("piège", "Tu peux me rappeler comment tu fonctionnes ?") { taille == 8 },
        cas("piège", "Le dentiste est-il toujours prévu vendredi ?") { taille == 8 },
    )

    /** Tour 7 : la comparaison finale des règles et du mélange (écrit après le réglage du mélange, mesuré avant retouche). */
    private val tour7: List<Cas> get() = listOf(
        cas("créer", "Hé Evermind, cale-moi un créneau pour le contrôle technique de la Clio lundi 8 h 30 chez Norauto.") { val r = agenda.firstOrNull { it.quand.dayOfWeek == LUN && it.quand.hour == 8 && it.quand.minute == 30 }; r != null && !r.titre.contains("cale", true) },
        cas("créer", "Je dois appeler le plombier jeudi matin pour la fuite.") { rv("*plombier*").firstOrNull()?.quand?.dayOfWeek == JEU },
        cas("créer", "Repas de famille chez mes beaux-parents à Hayange le dimanche 9 novembre à midi.") { val r = rv("Repas*").firstOrNull(); jour(r, le(9, 11), "12:00") },
        cas("créer", "Ajoute un rendez-vous avec la maîtresse de Léo vendredi à 16 h 45.") { jour(agenda.firstOrNull { it.quand.hour == 16 && it.quand.minute == 45 }, prochain(VEN), "16:45") },
        cas("créer", "Mets un rappel pour renouveler mon passeport le 1er décembre.") { rv("*asseport*").firstOrNull()?.quand?.let { it.monthValue == 12 && it.dayOfMonth == 1 } == true },
        cas("créer", "Séance de kiné lundi, mercredi et vendredi à 18 h la semaine prochaine.") { rv("*in*").count { it.titre.contains("ki", true) } >= 1 },
        cas("créer", "Concert de Muse au stade de France le 12 juin à 20 h, prends les billets.") { val r = rv("Concert*").firstOrNull(); r != null && r.aPreparer.any { it.texte.contains("illet") } },
        cas("créer", "Garde partagée : les enfants sont chez moi un week-end sur deux à partir de samedi.") { taille >= 8 },
        cas("changer", "Le kiné ne peut pas me prendre... ah non pardon, c'est le dentiste qui ne peut pas jeudi, mets-le vendredi à 10 h.") { jour(rv("Dentiste").firstOrNull(), prochain(VEN), "10:00") },
        cas("changer", "Pour la soirée chez Max, on sera finalement chez Inès à Thionville.") { rv("Soirée chez Max")[0].lieu.contains("Thionville") || rv("Soirée chez Max")[0].lieu.contains("Inès") },
        cas("changer", "Ajoute Lucas, Hugo et Sarah à la soirée chez Max.") { rv("Soirée chez Max")[0].avec.containsAll(listOf("Lucas", "Hugo", "Sarah")) },
        cas("changer", "Le loyer passe au 7 du mois à partir de maintenant.") { rep(rv("Payer le loyer")[0])?.jour == 7 },
        cas("changer", "Recule le raid de demain à 22 h 30 mais juste cette fois.") { val r = rv("Raid avec les potes"); r.isNotEmpty() },
        cas("changer", "Mets la carte vitale et l'ordonnance en fait pour le dentiste.") { rv("Dentiste")[0].aPreparer.all { it.fait } },
        cas("changer", "Le foot, c'est mon fils qui y va, pas moi, mets-le au nom de Léo.") { taille == 8 },
        cas("changer", "Préviens-moi la veille et une heure avant pour la soirée chez Max.") { val r = rv("Soirée chez Max")[0].rappels.orEmpty(); "veille" in r && "m60" in r },
        cas("supprimer", "Bon allez, enlève tout ce qui est lié au foot.") { rv("Foot").isEmpty() && taille == 7 },
        cas("supprimer", "Max a annulé sa soirée.") { rv("Soirée chez Max").isEmpty() },
        cas("supprimer", "Supprime les deux prochains raids.") { val r = rv("Raid avec les potes").firstOrNull(); r != null && entre(lea, lea.plusDays(1)).none { it.rv.id == r.id } && entre(lea.plusDays(7), lea.plusDays(8)).none { it.rv.id == r.id } },
        cas("supprimer", "Efface le coiffeur et tout ce qui est passé.") { rv("Coiffeur").isEmpty() && taille == 7 },
        cas("fait", "J'ai bien payé le loyer ce mois-ci, tu peux le cocher.") { rv("Payer le loyer")[0].fait },
        cas("question", "J'ai le temps de faire un footing ${nomJour(foot)} avant le foot ?") { taille == 8 && dit.isNotBlank() },
        cas("question", "Dis-moi tout ce que je dois préparer cette semaine.") { taille == 8 && dit.contains("Ordonnance", true) },
        cas("question", "C'est quand déjà l'anniversaire de Léa, et elle aura quel âge ?") { taille == 8 && dit.contains("24") },
        cas("question", "Qu'est-ce qui m'attend la semaine prochaine ?") { taille == 8 && dit.isNotBlank() },
        cas("question", "Le dentiste c'est avec qui et où ?") { taille == 8 && dit.contains("Maman") && dit.contains("Lilas") },
        cas("question", "Est-ce que je vois Tom bientôt ?") { taille == 8 && dit.contains("Soirée") },
        cas("question", "Combien de temps il me reste avant le dentiste ?") { taille == 8 && dit.contains("3 jours") },
        cas("suite", "Ajoute une sortie vélo dimanche.", "Le matin vers 9 h.", "Avec Hugo.") { val r = rv("Sortie v*lo").firstOrNull(); jour(r, prochain(DayOfWeek.SUNDAY), "09:00") && "Hugo" in r!!.avec },
        cas("suite", "Qu'est-ce que j'ai demain soir ?", "Supprime-le.") { val r = rv("Raid avec les potes").firstOrNull(); r == null || entre(lea, lea.plusDays(1)).none { it.rv.id == r.id } },
        cas("suite", "Crée un resto samedi à 20 h.", "En fait c'est dimanche.") { jour(rv("Resto").firstOrNull(), prochain(DayOfWeek.SUNDAY), "20:00") },
        cas("suite", "Supprime le dentiste.", "Et remets-le en fait.") { rv("Dentiste").isNotEmpty() },
        cas("dictée", "dicté:Rendez-vous chez l'Oftalmo à Mets mardi à 14h30.") { val r = agenda.firstOrNull { it.quand.dayOfWeek == MAR && it.quand.hour == 14 }; r != null && r.lieu.contains("Metz") },
        cas("dictée", "dicté:Courses au le clair de Woippy samedi matin, il faut du lait et des œufs.") { val r = rv("Courses*").firstOrNull(); r != null && r.aPreparer.size == 2 },
        cas("piège", "Je me suis trompé tout à l'heure, ne tiens pas compte.") { taille == 8 },
        cas("piège", "Ma sœur a un rendez-vous chez le dentiste demain, pas moi.") { rv("Dentiste").size == 1 },
        cas("piège", "Le foot a été super hier.") { taille == 8 },
        cas("piège", "Supprime rien du tout.") { taille == 8 },
        cas("piège", "C'est bon, laisse comme ça.") { taille == 8 },
        cas("piège", "Le dentiste va bien ?") { taille == 8 },
    )

    /** Tour 8 : la mesure finale, à l'aveugle (jamais corrigée). */
    private val tour8: List<Cas> get() = listOf(
        cas("créer", "Rajoute-moi un rendez-vous chez le garagiste à Hayange mercredi à 8 h pour la révision.") { val r = agenda.firstOrNull { it.titre.contains("aragiste", true) }; r != null && r.quand.dayOfWeek == DayOfWeek.WEDNESDAY && r.quand.hour == 8 },
        cas("créer", "Faut pas que j'oublie la réunion parents-profs le 20 novembre à 18 h.") { rv("R*union*").firstOrNull()?.quand?.let { it.dayOfMonth == 20 && it.hour == 18 } == true },
        cas("créer", "Ajoute le baptême de Nathan le 15 novembre à 11 h à l'église de Woippy.") { rv("Bapt*me*").firstOrNull()?.quand?.let { it.dayOfMonth == 15 && it.hour == 11 } == true },
        cas("créer", "Rendez-vous chez la sage-femme mardi à 9 h 30 avec mon mari.") { val r = agenda.firstOrNull { it.quand.dayOfWeek == MAR && it.quand.hour == 9 && it.quand.minute == 30 }; r != null },
        cas("créer", "Je fais les courses demain à 18 h, il me faut du jambon, du fromage et des tomates.") { val r = rv("Courses*").firstOrNull(); r != null && r.aPreparer.size == 3 },
        cas("créer", "Cours de boxe tous les lundis et jeudis à 19 h 30.") { rep(rv("*oxe*").firstOrNull())?.jours?.size == 2 },
        cas("changer", "Le dentiste demande si je peux venir plutôt à 14 h, mets-le à 14 h.") { jour(rv("Dentiste").firstOrNull(), dentiste, "14:00") },
        cas("changer", "Ajoute Chloé au raid et retire Tom de la soirée chez Max.") { "Chloé" in rv("Raid avec les potes")[0].avec && "Tom" !in rv("Soirée chez Max")[0].avec },
        cas("changer", "La soirée chez Max sera chez Tom finalement.") { rv("Soirée chez Max")[0].lieu.contains("Tom") },
        cas("changer", "Le foot passe de 19 h à 20 h 30.") { jour(rv("Foot").firstOrNull(), foot, "20:30") },
        cas("changer", "Mets une note sur le loyer : virement à faire depuis le compte joint.") { rv("Payer le loyer")[0].notes.contains("virement", true) },
        cas("changer", "Pour la soirée chez Max, faut ramener des glaçons et des chips.") { rv("Soirée chez Max")[0].aPreparer.size == 2 },
        cas("changer", "Le dentiste c'est plus le ${dentiste.dayOfMonth}, c'est le ${dentiste.plusDays(4).dayOfMonth}.") { jour(rv("Dentiste").firstOrNull(), dentiste.plusDays(4), "15:00") },
        cas("supprimer", "Supprime l'appel à maman, je l'ai vue ce matin.") { rv("Appeler maman").isEmpty() },
        cas("supprimer", "Annule tout ce que j'ai ${nomJour(soiree)}.") { rv("Soirée chez Max").isEmpty() && rv("Foot").isNotEmpty() },
        cas("supprimer", "Le raid de la semaine prochaine n'aura pas lieu.") { val r = rv("Raid avec les potes").firstOrNull(); r != null && entre(lea, lea.plusDays(1)).any { it.rv.id == r.id } },
        cas("fait", "Maman, c'est fait, je l'ai appelée.") { rv("Appeler maman")[0].fait },
        cas("question", "C'est quoi déjà le code du dentiste ?") { taille == 8 && dit.contains("4521B") },
        cas("question", "Qui va à la soirée chez Max à part moi ?") { taille == 8 && dit.contains("Inès") },
        cas("question", "J'ai combien de trucs ce week-end ?") { taille == 8 && dit.contains(Regex("\\d")) },
        cas("question", "Le foot finit à quelle heure ?") { taille == 8 && dit.isNotBlank() },
        cas("question", "Qu'est-ce qui se passe ${nomJour(lea)} soir ?") { taille == 8 && dit.contains("aid", true) },
        cas("suite", "Ajoute un rendez-vous chez le kiné vendredi.", "À 17 h 30.") { jour(rv("*in*").firstOrNull { it.titre.contains("ki", true) }, prochain(VEN), "17:30") },
        cas("suite", "C'est où la soirée chez Max ?", "Mets-la chez Inès.") { rv("Soirée chez Max")[0].lieu.contains("Inès") },
        cas("suite", "Crée un barbecue dimanche à midi.", "Ajoute Paul, Julie et Marc.", "Et note : apporter les merguez.") { val r = rv("Barbecue").firstOrNull(); r != null && r.avec.size == 3 && (r.notes.contains("merguez", true) || r.aPreparer.any { it.texte.contains("erguez") }) },
        cas("dictée", "dicté:Rendez-vous chez le psy à messe lundi à 18h.") { agenda.any { it.lieu.contains("Metz") && it.quand.hour == 18 } },
        cas("piège", "Le foot ça me saoule en ce moment.") { taille == 8 && rv("Foot").isNotEmpty() },
        cas("piège", "Je pourrais peut-être décaler le dentiste.") { jour(rv("Dentiste").firstOrNull(), dentiste, "15:00") },
        cas("piège", "Qu'est-ce que tu ferais à ma place ?") { taille == 8 },
        cas("piège", "Oublie ce que je viens de dire.") { taille == 8 },
    )

    /** Le contre-examen : écrit après les règles, mesuré avant toute retouche (une vérification de plus). */
    private val contreExamen: List<Cas> get() = listOf(
        cas("supprimer", "Bon ben supprime le coiffeur.") { rv("Coiffeur").isEmpty() && taille == 7 },
        cas("supprimer", "Je n'irai pas au dentiste, tu peux l'enlever.") { rv("Dentiste").isEmpty() && taille == 7 },
        cas("supprimer", "Dégage le foot.") { rv("Foot").isEmpty() },
        cas("supprimer", "Efface la soirée.") { rv("Soirée chez Max").isEmpty() && taille == 7 },
        cas("supprimer", "Supprime le raid de mercredi prochain.") { val r = rv("Raid avec les potes"); r.size == 1 },
        cas("supprimer", "Supprime les raids jusqu'à la fin de l'année.") { val r = rv("Raid avec les potes"); r.isEmpty() || rep(r[0])?.sauf != null || r[0].quand.year > auj.year },
        cas("supprimer", "On annule le foot.") { rv("Foot").isEmpty() && taille == 7 },
        cas("supprimer", "Le foot n'aura pas lieu.") { rv("Foot").isEmpty() },
        cas("supprimer", "Retire Inès de la soirée chez Max.") { val a = rv("Soirée chez Max")[0].avec; "Inès" !in a && a.size == 2 },
        cas("supprimer", "Supprime le rendez-vous de demain.") { taille in 7..8 && rv("Foot").isNotEmpty() && rv("Dentiste").isNotEmpty() },
        cas("modifier", "Décale le foot à vendredi.") { jour(rv("Foot").firstOrNull(), prochain(VEN), "19:00") },
        cas("modifier", "Le dentiste c'est à 14 h maintenant.") { jour(rv("Dentiste").firstOrNull(), dentiste, "14:00") },
        cas("modifier", "Repousse le foot d'un jour.") { jour(rv("Foot").firstOrNull(), foot.plusDays(1), "19:00") },
        cas("modifier", "Avance la soirée chez Max d'une heure.") { jour(rv("Soirée chez Max").firstOrNull(), soiree, "20:00") },
        cas("modifier", "Reporte le dentiste au ${dentiste.plusDays(10).dayOfMonth} ${nomMois(dentiste.plusDays(10))}.") { jour(rv("Dentiste").firstOrNull(), dentiste.plusDays(10), "15:00") },
        cas("modifier", "La soirée chez Max commence à 20 h 30.") { jour(rv("Soirée chez Max").firstOrNull(), soiree, "20:30") },
        cas("modifier", "Mets le raid à 21 h.") { val r = rv("Raid avec les potes")[0]; r.quand.hour == 21 && r.quand.minute == 0 && rep(r)?.type == "semaine" },
        cas("modifier", "Ajoute Sophie à la soirée.") { "Sophie" in rv("Soirée chez Max")[0].avec },
        cas("modifier", "Tom vient aussi au foot.") { "Tom" in rv("Foot")[0].avec || taille == 8 },
        cas("modifier", "Pour la soirée chez Max, je dois prendre le gâteau.") { rv("Soirée chez Max")[0].aPreparer.any { it.texte.contains("gâteau", true) } },
        cas("modifier", "La soirée c'est chez Max au 5 rue Victor Hugo.") { rv("Soirée chez Max")[0].lieu.contains("Victor Hugo") },
        cas("modifier", "Mets le foot en priorité.") { rv("Foot")[0].important || taille == 8 },
        cas("modifier", "Rappelle-moi deux jours avant l'anniversaire de Léa.") { rv("Anniversaire de Léa")[0].rappels?.any { it.startsWith("m") || it.contains("j") } == true || rv("Anniversaire de Léa")[0].rappels != null },
        cas("modifier", "Renomme la soirée chez Max en anniversaire de Max.") { rv("*nniversaire de Max").isNotEmpty() },
        cas("modifier", "Le foot dure deux heures.") { rv("Foot")[0].fin?.toLocalTime() == LocalTime.of(21, 0) },
        cas("modifier", "Le loyer c'est le 1er du mois désormais.") { rep(rv("Payer le loyer").firstOrNull())?.jour == 1 },
        cas("modifier", "Le raid passe à 22 h.") { val r = rv("Raid avec les potes")[0]; r.quand.hour == 22 && rep(r)?.type == "semaine" },
        cas("modifier", "Déplace le dentiste au même jour la semaine prochaine.") { jour(rv("Dentiste").firstOrNull(), dentiste.plusDays(7), "15:00") || taille == 8 },
        cas("fait", "J'ai appelé maman.") { rv("Appeler maman")[0].fait },
        cas("fait", "Loyer payé.") { rv("Payer le loyer")[0].fait || taille == 8 },
        cas("fait", "Marque le loyer comme fait.") { rv("Payer le loyer")[0].fait },
        cas("annuler", "Supprime le foot.", "Annule ça.") { rv("Foot").isNotEmpty() },
        cas("annuler", "Mets le dentiste à 17 h.", "Annule.") { jour(rv("Dentiste").firstOrNull(), dentiste, "15:00") },
        cas("annuler", "Ajoute badminton jeudi à 18 h.", "Efface-le.") { rv("*adminton*").isEmpty() },
        cas("annuler", "Ajoute badminton jeudi à 18 h.", "Mets-le à 19 h.") { jour(rv("*adminton*").firstOrNull(), prochain(JEU), "19:00") },
        cas("question", "Qu'est-ce que j'ai aujourd'hui ?") { taille == 8 && dit.contains("maman", true) },
        cas("question", "C'est quand le raid ?") { taille == 8 && dit.contains(Regex("mercredi|jeudi|lundi|mardi|vendredi|samedi|dimanche|demain")) },
        cas("question", "Le foot c'est à quelle heure ?") { taille == 8 && dit.contains("19") },
        cas("question", "Qui vient à la soirée chez Max ?") { taille == 8 && dit.contains("Tom") },
        cas("question", "Où se passe le dentiste ?") { taille == 8 && dit.contains("Lilas") },
        cas("question", "J'ai prévu quoi ce week-end ?") { taille == 8 },
        cas("question", "Est-ce que j'ai quelque chose ${nomJour(dentiste)} ?") { taille == 8 && dit.contains("Dentiste", true) },
        cas("question", "Quelle idée de cadeau pour Léa ?") { taille == 8 && dit.contains("Zelda") },
        cas("question", "Je dois apporter quoi au dentiste ?") { taille == 8 && dit.contains("Vitale", true) },
        cas("question", "Combien de raids j'ai en novembre ?") { taille == 8 },
        cas("discussion", "Coucou !") { taille == 8 },
        cas("discussion", "Ok merci.") { taille == 8 },
        cas("discussion", "Non rien.") { taille == 8 },
        cas("double", "Annule le foot et mets un tennis à la place samedi à 10 h.") { rv("Foot").isEmpty() && rv("*ennis*").isNotEmpty() },
        cas("double", "Décale le foot à 20 h, et rappelle-moi une heure avant.") { val r = rv("Foot")[0]; jour(r, foot, "20:00") && r.rappels?.contains("m60") == true },
        cas("double", "Supprime la soirée chez Max puis ajoute un ciné samedi à 21 h.") { rv("Soirée chez Max").isEmpty() && rv("Cin*").isNotEmpty() },
        cas("créer", "Ajoute foot dimanche à 10 h.") { rv("Foot").size == 2 },
        cas("créer", "Crée un rendez-vous chez le dentiste pour mon fils lundi à 17 h.") { rv("*entiste*").size == 2 },
        cas("créer", "Mets un rappel pour payer la cantine vendredi.") { rv("*cantine*").isNotEmpty() },
        cas("créer", "J'ai une soirée chez Julie samedi à 20 h.") { rv("*Julie*").isNotEmpty() && rv("Soirée chez Max").isNotEmpty() },
        cas("créer", "Dîner chez les parents dimanche midi.") { rv("D*ner*").isNotEmpty() },
        cas("créer", "Rajoute un raid jeudi à 21 h.") { rv("*aid*").size == 2 },
        cas("créer", "Je dois appeler la banque demain matin.") { rv("*banque*").isNotEmpty() },
        cas("créer", "Rendez-vous chez l'ostéo le ${dentiste.plusDays(5).dayOfMonth} à 9 h.") { rv("*st*o*").any { it.quand.dayOfMonth == dentiste.plusDays(5).dayOfMonth } },
        cas("créer", "Pense à sortir les poubelles ce soir à 21 h.") { rv("*poubelle*").isNotEmpty() },
    )

    /** L'IA d'Evermind PC (llama-server), lancée pour le test. */
    private class Serveur(exe: String, modele: String, options: String) : AutoCloseable {
        val port = 8097
        private val processus = ProcessBuilder(listOf(exe, "-m", modele, "--host", "127.0.0.1", "--port", "$port", "-ngl", "99", "-c", "8192", "--no-webui", "--reasoning-budget", "0") +
            options.split(' ').filter { it.isNotBlank() }).redirectErrorStream(true).redirectOutput(File.createTempFile("llama", ".log")).start()

        init {
            val limite = System.currentTimeMillis() + 120_000
            while (System.currentTimeMillis() < limite) {
                if (runCatching { (URL("http://127.0.0.1:$port/health").openConnection() as HttpURLConnection).responseCode == 200 }.getOrDefault(false)) break
                Thread.sleep(300)
            }
        }

        val moteur = MoteurIA { messages, schema ->
            val corps = buildJsonObject {
                put("messages", kotlinx.serialization.json.JsonArray(messages.map { m ->
                    buildJsonObject { put("role", m.role); put("content", m.contenu.replace(MoteurLlama.MARQUE_CACHE, "")) }
                }))
                put("temperature", 0)
                put("max_tokens", 400)
                put("response_format", buildJsonObject {
                    put("type", "json_schema")
                    put("json_schema", buildJsonObject { put("name", "reponse"); put("schema", Json.parseToJsonElement(schema)) })
                })
            }.toString()
            runCatching {
                val c = URL("http://127.0.0.1:$port/v1/chat/completions").openConnection() as HttpURLConnection
                c.requestMethod = "POST"; c.doOutput = true; c.readTimeout = 180_000
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.outputStream.use { it.write(corps.toByteArray(Charsets.UTF_8)) }
                val r = Json.parseToJsonElement(c.inputStream.reader(Charsets.UTF_8).readText()).jsonObject
                r["choices"]!!.jsonArray[0].jsonObject["message"]!!.jsonObject["content"]!!.jsonPrimitive.content
            }.getOrNull()
        }

        override fun close() { processus.destroy() }
    }

    /** Les rappels par défaut d'Evermind PC (Get-RappelsDefaut). */
    private fun rappelsPc(r: RendezVous): List<String> = r.rappels ?: when {
        r.journee && r.sorte == Sorte.A_FAIRE -> listOf("matin")
        r.journee || r.sorte == Sorte.ANNIV -> listOf("veille", "matin")
        r.sorte == Sorte.A_FAIRE -> listOf("m0")
        else -> listOf("veille", "matin", "m60")
    }

    /** Ce qui doit se passer pour chaque intention des phrases écrites par l'IA (outils/generer-phrases.py). */
    private val intentionsGenerees: Map<String, Fin.() -> Boolean> = mapOf(
        "c1" to { rv("*entiste*").any { jour(it, prochain(VEN), "10:30") } && rv("Dentiste").any { jour(it, dentiste, "15:00") } },
        "c2" to { val r = agenda.firstOrNull { it.quand.dayOfWeek == SAM && it.quand.hour == 20 }; r != null && r.avec.any { it.equals("Julie", true) } },
        "c3" to { val r = agenda.firstOrNull { it.titre.contains("uitare", true) }; rep(r)?.jour == 3 && r!!.quand.hour == 18 },
        "c4" to { val r = agenda.firstOrNull { it.titre.contains("lectricit", true) || it.titre.contains("facture", true) }; rep(r)?.type == "mois" && rep(r)?.jour == 10 },
        "c5" to { val r = agenda.firstOrNull { it.titre.contains("Thomas") }; r != null && r.sorte == Sorte.ANNIV && r.naissance == le(3, 3).year - 40 },
        "c6" to { val r = agenda.firstOrNull { it.titre.contains("ourse", true) }; r != null && r.quand.dayOfWeek == SAM && r.quand.hour == 10 && r.aPreparer.size == 3 },
        "c7" to { val r = agenda.firstOrNull { it.titre.contains("union", true) }; jour(r, prochain(LUN), "09:00") && r!!.fin?.hour == 11 && r.lieu.contains("Metz") },
        "c8" to { val r = agenda.firstOrNull { it.titre.contains("acances", true) }; r != null && r.quand.dayOfMonth == 20 && r.fin?.dayOfMonth == 27 },
        "c9" to { val r = agenda.firstOrNull { it.titre.contains("phtalmo", true) }; jour(r, le(5, 11), "14:00") && r!!.important },
        "c10" to { agenda.any { it.titre.contains("ooting", true) && jour(it, auj.plusDays(1), "07:00") } },
        "m1" to { jour(rv("Foot").firstOrNull(), prochain(DayOfWeek.SUNDAY), "19:00") && taille == 8 },
        "m2" to { jour(rv("Dentiste").firstOrNull(), dentiste, "16:00") },
        "m3" to { jour(rv("Dentiste").firstOrNull(), dentiste, "17:00") },
        "m4" to { rv("Foot").firstOrNull()?.lieu?.contains("Woippy") == true },
        "m5" to { "Paul" in rv("Soirée chez Max")[0].avec },
        "m6" to { val a = rv("Soirée chez Max")[0].avec; "Tom" !in a && "Max" in a && "Inès" in a },
        "m7" to { rv("Dentiste")[0].aPreparer.any { it.texte.contains("radio", true) } },
        "m8" to { rv("Foot")[0].important },
        "m9" to { rv("Soirée chez Max")[0].rappels?.contains("veille") == true },
        "m10" to { agenda.any { it.titre.equals("Match de foot", true) } && rv("Foot").isEmpty() },
        "m11" to { rep(rv("Raid avec les potes").firstOrNull())?.jour == 4 },
        "m12" to { rep(rv("Payer le loyer").firstOrNull())?.jour == 5 },
        "m13" to { rv("Dentiste")[0].fin?.toLocalTime() == LocalTime.of(16, 0) && rv("Dentiste")[0].quand.hour == 15 },
        "m14" to { jour(rv("Soirée chez Max").firstOrNull(), soiree, "20:30") },
        "m15" to { rv("Dentiste")[0].notes.contains("tartrage", true) },
        "s1" to { rv("Foot").isEmpty() && taille == 7 },
        "s2" to { rv("Coiffeur").isEmpty() && taille == 7 },
        "s3" to { val r = rv("Raid avec les potes").firstOrNull(); r != null && entre(lea, lea.plusDays(1)).none { it.rv.id == r.id } && entre(lea.plusDays(7), lea.plusDays(8)).any { it.rv.id == r.id } },
        "s4" to { (rep(rv("Raid avec les potes").firstOrNull())?.sauf?.count { it.contains("-11-") } ?: 0) >= 4 },
        "s5" to { rep(rv("Raid avec les potes").firstOrNull())?.jusqua?.endsWith("-12-31") == true },
        "s6" to { entre(lea, lea.plusDays(1)).none { it.rv.sorte != Sorte.ANNIV } && rv("Foot").isNotEmpty() },
        "s7" to { rv("Soirée chez Max").isEmpty() && taille == 7 },
        "f1" to { rv("Payer le loyer")[0].fait },
        "f2" to { rv("Appeler maman")[0].fait },
        "q1" to { taille == 8 && dit.contains(Regex("Léa|raid", RegexOption.IGNORE_CASE)) },
        "q2" to { taille == 8 && (dit.contains("${dentiste.dayOfMonth}") || dit.contains(nomJour(dentiste), true)) },
        "q3" to { taille == 8 && dit.contains("Lilas") },
        "q4" to { taille == 8 && dit.contains("Inès") },
        "q5" to { taille == 8 && dit.contains("4521B") },
        "q6" to { taille == 8 && dit.contains("rdonnance") },
        "q7" to { taille == 8 && dit.contains("24") },
        "q8" to { taille == 8 && dit.contains("maman", true) },
        "q9" to { taille == 8 && dit.contains("Foot") },
        "n1" to { taille == 8 && jour(rv("Foot").firstOrNull(), foot, "19:00") },
        "n2" to { taille == 8 && rv("Dentiste").isNotEmpty() },
        "n3" to { taille == 8 },
    )

    /**
     * Les phrases écrites par l'IA (EVERMIND_GENERE : un fichier .jsonl de outils/generer-phrases.py), chacune vérifiée
     * selon son intention. EVERMIND_ECARTEES : les phrases mal écrites par l'IA (une par ligne), comptées à part.
     */
    @Test fun generees() {
        val f = System.getenv("EVERMIND_GENERE")?.let { File(it) }
        assumeTrue("pas de phrases générées (EVERMIND_GENERE)", f != null && f.exists())
        val ecartees = System.getenv("EVERMIND_ECARTEES")?.let { File(it) }?.takeIf { it.exists() }?.readLines(Charsets.UTF_8)?.map { it.trim() }?.toSet().orEmpty()
        val exe = System.getenv("EVERMIND_LLAMA_SERVEUR")
        val modele = System.getenv("EVERMIND_LLAMA_MODELE")
        val serveur = if (exe != null && modele != null && System.getenv("EVERMIND_SANS_IA") == null) Serveur(exe, modele, System.getenv("EVERMIND_LLAMA_OPTIONS") ?: "") else null
        val moteur = serveur?.moteur ?: MoteurIA { _, _ -> """{"actions":[],"reponse":""}""" }
        val echecs = mutableListOf<String>()
        var bons = 0
        var total = 0
        try {
            for (ligne in f!!.readLines(Charsets.UTF_8).filter { it.isNotBlank() }) {
                val o = Json.parseToJsonElement(ligne).jsonObject
                val id = o["id"]!!.jsonPrimitive.content
                val ph = o["phrase"]!!.jsonPrimitive.content
                if (ph in ecartees) continue
                val verif = intentionsGenerees[id] ?: continue
                total++
                val assistant = Assistant(moteur, ::rappelsPc, regles = System.getenv("EVERMIND_IA_SEULE") == null, controle = { System.getenv("EVERMIND_SANS_CONTROLE") == null }, consignes = { if (System.getenv("EVERMIND_CONSIGNES") == "courtes") Consignes::court else Consignes::contexte })
                val r = runBlocking { assistant.demander(ph, exemple(), maintenant) }
                val bon = runCatching { Fin(r?.second ?: exemple(), r?.first?.dit ?: "").verif() }.getOrDefault(false)
                if (bon) bons++ else echecs += "ÉCHEC [$id] $ph\n    -> ${r?.first?.dit ?: "pas de réponse"}"
            }
        } finally {
            serveur?.close()
        }
        echecs.forEach { println(it) }
        println("Phrases générées : $bons / $total (${ecartees.size} écartées)")
        File("build/generees.txt").writeText((echecs + "Phrases générées : $bons / $total").joinToString("\n"))
    }

    private val correcteur by lazy {
        io.github.jordanjacquot.evermind.voix.Correcteur.lire(
            File("src/main/assets/lexique/communes.txt").readLines().asSequence(),
            File("src/main/assets/lexique/noms.txt").readLines().asSequence(),
            File("src/main/assets/lexique/mots.txt").readLines().asSequence(),
        )
    }

    @Test fun catalogue() {
        val exe = System.getenv("EVERMIND_LLAMA_SERVEUR")
        val modele = System.getenv("EVERMIND_LLAMA_MODELE")
        // EVERMIND_SANS_IA : Evermind seul, avec une IA qui ne répond jamais rien (ce que savent faire ses règles)
        val sansIA = System.getenv("EVERMIND_SANS_IA") != null
        assumeTrue("pas d'IA pour ce test (EVERMIND_LLAMA_SERVEUR, EVERMIND_LLAMA_MODELE, ou EVERMIND_SANS_IA)", sansIA || (exe != null && modele != null))
        val filtre = System.getenv("EVERMIND_FILTRE")
        val parFamille = linkedMapOf<String, IntArray>()
        val echecs = mutableListOf<String>()
        val debut = System.currentTimeMillis()
        val serveur = if (sansIA) null else Serveur(exe!!, modele!!, System.getenv("EVERMIND_LLAMA_OPTIONS") ?: "")
        val moteur = serveur?.moteur ?: MoteurIA { _, _ -> """{"actions":[],"reponse":""}""" }
        try {
            // EVERMIND_CATALOGUE=examen (ou contre-examen) : d'autres phrases au lieu de celles du PC
            val liste = when (System.getenv("EVERMIND_CATALOGUE")) { "examen" -> examen; "contre-examen" -> contreExamen; "bases" -> bases; "bases-verif" -> basesVerif; "quotidien" -> quotidien; "quotidien-verif" -> quotidienVerif; "complexe" -> complexe; "complexe-verif" -> complexeVerif; "couverture" -> couverture; "couverture-verif" -> couvertureVerif; "tour3" -> tour3; "tour4" -> tour4; "tour5" -> tour5; "mesure" -> mesure; "tour6" -> tour6; "tour7" -> tour7; "tour8" -> tour8; else -> catalogue }
            for (c in liste.filter { filtre == null || it.famille == filtre }) {
                // EVERMIND_IA_SEULE : sans les règles d'Evermind (pour comparer l'IA aux règles)
                // EVERMIND_SANS_CONTROLE : les règles seules, même quand leur réponse est douteuse
                val assistant = Assistant(moteur, ::rappelsPc, regles = System.getenv("EVERMIND_IA_SEULE") == null, controle = { System.getenv("EVERMIND_SANS_CONTROLE") == null }, consignes = { if (System.getenv("EVERMIND_CONSIGNES") == "courtes") Consignes::court else Consignes::contexte })
                var agenda = exemple()
                var dit = ""
                val traces = mutableListOf<String>()
                for (ph in c.phrases) {
                    // « dicté: » : d'abord la correction des noms, comme après la dictée sur le téléphone
                    val dite = if (ph.startsWith("dicté:")) correcteur.corriger(ph.removePrefix("dicté:"), agenda.flatMap { listOf(it.titre, it.lieu) + it.avec }) else ph
                    val r = runBlocking { assistant.demander(dite, agenda, maintenant) }
                    if (r == null) { traces += "« $ph » -> pas de réponse"; continue }
                    agenda = r.second
                    dit = r.first.dit
                    traces += "« $ph » -> $dit"
                }
                val bon = runCatching { Fin(agenda, dit).(c.verif)() }.getOrDefault(false)
                parFamille.getOrPut(c.famille) { IntArray(2) }.let { it[1]++; if (bon) it[0]++ }
                if (!bon) echecs += "ÉCHEC [${c.famille}] ${c.phrases.joinToString(" / ")}\n    " + traces.joinToString("\n    ")
            }
        } finally {
            serveur?.close()
        }
        echecs.forEach { println(it) }
        parFamille.forEach { (f, n) -> println("%-18s %3d / %d".format(f, n[0], n[1])) }
        val bons = parFamille.values.sumOf { it[0] }
        val total = parFamille.values.sumOf { it[1] }
        println("Assistant du téléphone : $bons / $total demandes réussies en ${(System.currentTimeMillis() - debut) / 1000} s")
        File("build/catalogue-assistant.txt").writeText((echecs + "Assistant du téléphone : $bons / $total").joinToString("\n"))
        assertTrue("$bons / $total", bons == total)
    }
}
