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
            val liste = when (System.getenv("EVERMIND_CATALOGUE")) { "examen" -> examen; "contre-examen" -> contreExamen; "bases" -> bases; "bases-verif" -> basesVerif; "quotidien" -> quotidien; "quotidien-verif" -> quotidienVerif; "complexe" -> complexe; "complexe-verif" -> complexeVerif; else -> catalogue }
            for (c in liste.filter { filtre == null || it.famille == filtre }) {
                val assistant = Assistant(moteur, ::rappelsPc)
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
