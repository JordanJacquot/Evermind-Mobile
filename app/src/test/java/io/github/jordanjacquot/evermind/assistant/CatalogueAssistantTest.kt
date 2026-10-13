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
    private val maintenant = LocalDateTime.now().withSecond(0).withNano(0)
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

    @Test fun catalogue() {
        val exe = System.getenv("EVERMIND_LLAMA_SERVEUR")
        val modele = System.getenv("EVERMIND_LLAMA_MODELE")
        assumeTrue("pas d'IA pour ce test (EVERMIND_LLAMA_SERVEUR, EVERMIND_LLAMA_MODELE)", exe != null && modele != null)
        val filtre = System.getenv("EVERMIND_FILTRE")
        val parFamille = linkedMapOf<String, IntArray>()
        val echecs = mutableListOf<String>()
        val debut = System.currentTimeMillis()
        Serveur(exe!!, modele!!, System.getenv("EVERMIND_LLAMA_OPTIONS") ?: "").use { s ->
            for (c in catalogue.filter { filtre == null || it.famille == filtre }) {
                val assistant = Assistant(s.moteur, ::rappelsPc)
                var agenda = exemple()
                var dit = ""
                val traces = mutableListOf<String>()
                for (ph in c.phrases) {
                    val r = runBlocking { assistant.demander(ph, agenda, maintenant) }
                    if (r == null) { traces += "« $ph » -> pas de réponse"; continue }
                    agenda = r.second
                    dit = r.first.dit
                    traces += "« $ph » -> $dit"
                }
                val bon = runCatching { Fin(agenda, dit).(c.verif)() }.getOrDefault(false)
                parFamille.getOrPut(c.famille) { IntArray(2) }.let { it[1]++; if (bon) it[0]++ }
                if (!bon) echecs += "ÉCHEC [${c.famille}] ${c.phrases.joinToString(" / ")}\n    " + traces.joinToString("\n    ")
            }
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
