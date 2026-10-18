package io.github.jordanjacquot.evermind.assistant

import io.github.jordanjacquot.evermind.comprendre.Repetition
import io.github.jordanjacquot.evermind.donnees.APreparer
import io.github.jordanjacquot.evermind.donnees.RendezVous
import io.github.jordanjacquot.evermind.donnees.Sorte
import io.github.jordanjacquot.evermind.donnees.laRepetition
import io.github.jordanjacquot.evermind.ui.enTexte
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.random.Random

/**
 * La fabrique des exemples pour entraîner le modèle d'Evermind (outils/entrainer) : un agenda tiré au hasard, une
 * intention, et la bonne réponse JSON, vérifiée en l'appliquant avec les actions d'Evermind (seuls les exemples dont
 * le résultat est exactement celui voulu sont gardés). Les phrases de l'utilisateur sont écrites ensuite par une IA
 * (outils/entrainer/phrases.py), à partir de l'intention ; elles doivent contenir les « indices ».
 *   EVERMIND_FABRIQUE : le fichier .jsonl à écrire ; EVERMIND_FABRIQUE_N : combien ; EVERMIND_FABRIQUE_GRAINE.
 */
class FabriqueEntrainementTest {
    private val fr = Locale.FRENCH
    private val fJour = DateTimeFormatter.ofPattern("EEEE d MMMM", fr)
    private val fDate = DateTimeFormatter.ofPattern("d MMMM yyyy", fr)
    private val jours = listOf("lundi", "mardi", "mercredi", "jeudi", "vendredi", "samedi", "dimanche")
    private val mois = listOf("janvier", "février", "mars", "avril", "mai", "juin", "juillet", "août", "septembre", "octobre", "novembre", "décembre")

    private class Modele(val titre: String, val sorte: Sorte, val heure: LocalTime?, val repete: String? = null)

    private val modeles = listOf(
        Modele("Dentiste", Sorte.SANTE, LocalTime.of(15, 0)), Modele("Médecin", Sorte.SANTE, LocalTime.of(10, 30)),
        Modele("Kiné", Sorte.SANTE, LocalTime.of(18, 0)), Modele("Ophtalmo", Sorte.SANTE, LocalTime.of(14, 0)),
        Modele("Coiffeur", Sorte.RDV, LocalTime.of(11, 0)), Modele("Garagiste", Sorte.RDV, LocalTime.of(8, 30)),
        Modele("Notaire", Sorte.RDV, LocalTime.of(11, 0)), Modele("Banque", Sorte.RDV, LocalTime.of(9, 30)),
        Modele("Foot", Sorte.SPORT, LocalTime.of(19, 0)), Modele("Piscine", Sorte.SPORT, LocalTime.of(12, 15)),
        Modele("Tennis", Sorte.SPORT, LocalTime.of(10, 0)), Modele("Yoga", Sorte.SPORT, LocalTime.of(18, 30), "semaine"),
        Modele("Réunion d'équipe", Sorte.TRAVAIL, LocalTime.of(9, 0), "semaine"), Modele("Entretien", Sorte.TRAVAIL, LocalTime.of(14, 0)),
        Modele("Soirée chez Max", Sorte.SORTIE, LocalTime.of(21, 0)), Modele("Resto avec Julie", Sorte.SORTIE, LocalTime.of(20, 0)),
        Modele("Cinéma", Sorte.SORTIE, LocalTime.of(20, 30)), Modele("Apéro chez Tom", Sorte.SORTIE, LocalTime.of(19, 0)),
        Modele("Raid avec les potes", Sorte.JEU, LocalTime.of(21, 30), "semaine"), Modele("Session Dofus", Sorte.JEU, LocalTime.of(20, 0), "semaine"),
        Modele("Appeler maman", Sorte.A_FAIRE, LocalTime.of(18, 0)), Modele("Payer le loyer", Sorte.A_FAIRE, LocalTime.of(10, 0), "mois"),
        Modele("Courses", Sorte.A_FAIRE, LocalTime.of(10, 0)), Modele("Sortir les poubelles", Sorte.A_FAIRE, LocalTime.of(20, 0), "semaine"),
        Modele("Anniversaire de Léa", Sorte.ANNIV, null, "an"), Modele("Anniversaire de Paul", Sorte.ANNIV, null, "an"),
        Modele("Virement épargne", Sorte.A_FAIRE, LocalTime.of(9, 0), "mois"), Modele("Facture internet", Sorte.A_FAIRE, null, "mois"),
        Modele("Train pour Paris", Sorte.VOYAGE, LocalTime.of(7, 45)), Modele("Cours de guitare", Sorte.TRAVAIL, LocalTime.of(17, 0), "semaine"),
    )
    private val nouveaux = listOf(
        "Dîner chez Paul" to Sorte.RDV, "Ostéo" to Sorte.RDV, "Pédiatre" to Sorte.RDV, "Réunion de chantier" to Sorte.RDV, "Badminton" to Sorte.RDV, "Concert" to Sorte.RDV,
        "Visite chez mamie" to Sorte.RDV, "Contrôle technique" to Sorte.RDV, "Bowling" to Sorte.RDV, "Footing" to Sorte.RDV, "Atelier poterie" to Sorte.RDV, "Rendez-vous à la banque" to Sorte.RDV,
        "Vétérinaire" to Sorte.RDV, "Brunch" to Sorte.RDV, "Cours de guitare" to Sorte.RDV, "Cours de piano" to Sorte.RDV, "Cours de danse" to Sorte.RDV, "Cours d'anglais" to Sorte.RDV,
        "Cours de natation" to Sorte.RDV, "Cours de cuisine" to Sorte.RDV, "Cours de yoga" to Sorte.RDV, "Cours de boxe" to Sorte.RDV, "Leçon de conduite" to Sorte.RDV, "Code de la route" to Sorte.RDV,
        "Réunion" to Sorte.RDV, "Réunion de parents" to Sorte.RDV, "Réunion client" to Sorte.RDV, "Point avec le chef" to Sorte.RDV, "Entretien d'embauche" to Sorte.RDV, "Formation" to Sorte.RDV,
        "Séminaire" to Sorte.RDV, "Conférence" to Sorte.RDV, "Visio avec l'équipe" to Sorte.RDV, "Réunion de copropriété" to Sorte.RDV, "Dermatologue" to Sorte.RDV, "Gynécologue" to Sorte.RDV,
        "Cardiologue" to Sorte.RDV, "Orthodontiste" to Sorte.RDV, "Ophtalmologue" to Sorte.RDV, "Psychologue" to Sorte.RDV, "Prise de sang" to Sorte.RDV, "Radio du genou" to Sorte.RDV,
        "Échographie" to Sorte.RDV, "Vaccin" to Sorte.RDV, "Orthophoniste" to Sorte.RDV, "Podologue" to Sorte.RDV, "Pharmacie" to Sorte.RDV, "Kiné du dos" to Sorte.RDV,
        "Mécanicien" to Sorte.RDV, "Changer les pneus" to Sorte.RDV, "Vidange" to Sorte.RDV, "Lavage de la voiture" to Sorte.RDV, "Carrosserie" to Sorte.RDV, "Plombier" to Sorte.RDV,
        "Électricien" to Sorte.RDV, "Livraison du canapé" to Sorte.RDV, "Déménagement" to Sorte.RDV, "Ramoneur" to Sorte.RDV, "Visite de l'appartement" to Sorte.RDV, "État des lieux" to Sorte.RDV,
        "Mairie" to Sorte.RDV, "Préfecture" to Sorte.RDV, "Passeport" to Sorte.RDV, "Carte d'identité" to Sorte.RDV, "Impôts" to Sorte.RDV, "Assurance" to Sorte.RDV,
        "Notaire" to Sorte.RDV, "Avocat" to Sorte.RDV, "Comptable" to Sorte.RDV, "Poste" to Sorte.RDV, "Retirer un colis" to Sorte.RDV, "Pressing" to Sorte.RDV,
        "Cordonnier" to Sorte.RDV, "Opticien" to Sorte.RDV, "Anniversaire de Lucas" to Sorte.RDV, "Mariage de Sophie" to Sorte.RDV, "Baptême" to Sorte.RDV, "Fête des voisins" to Sorte.RDV,
        "Barbecue chez Marc" to Sorte.RDV, "Pique-nique" to Sorte.RDV, "Crémaillère chez Inès" to Sorte.RDV, "Soirée jeux" to Sorte.RDV, "Soirée pizza" to Sorte.RDV, "Karaoké" to Sorte.RDV,
        "Escape game" to Sorte.RDV, "Laser game" to Sorte.RDV, "Match de rugby" to Sorte.RDV, "Match de basket" to Sorte.RDV, "Match de hand" to Sorte.RDV, "Course à pied" to Sorte.RDV,
        "Vélo" to Sorte.RDV, "Randonnée" to Sorte.RDV, "Escalade" to Sorte.RDV, "Musculation" to Sorte.RDV, "Salle de sport" to Sorte.RDV, "Ski" to Sorte.RDV,
        "Patinoire" to Sorte.RDV, "Pétanque" to Sorte.RDV, "Golf" to Sorte.RDV, "Squash" to Sorte.RDV, "Zumba" to Sorte.RDV, "Pilates" to Sorte.RDV,
        "Théâtre" to Sorte.RDV, "Opéra" to Sorte.RDV, "Musée" to Sorte.RDV, "Expo photo" to Sorte.RDV, "Cinéma avec les enfants" to Sorte.RDV, "Festival" to Sorte.RDV,
        "Spectacle de danse" to Sorte.RDV, "Cirque" to Sorte.RDV, "Marché de Noël" to Sorte.RDV, "Brocante" to Sorte.RDV, "Vide-grenier" to Sorte.RDV, "Salon de l'auto" to Sorte.RDV,
        "Zoo" to Sorte.RDV, "Parc d'attractions" to Sorte.RDV, "Coiffeur des enfants" to Sorte.RDV, "Barbier" to Sorte.RDV, "Manucure" to Sorte.RDV, "Esthéticienne" to Sorte.RDV,
        "Massage" to Sorte.RDV, "Spa" to Sorte.RDV, "Tatouage" to Sorte.RDV, "Garderie" to Sorte.RDV, "Sortie scolaire" to Sorte.RDV, "Kermesse" to Sorte.RDV,
        "Spectacle de l'école" to Sorte.RDV, "Rentrée des classes" to Sorte.RDV, "Conseil de classe" to Sorte.RDV, "Récupérer les enfants" to Sorte.RDV, "Nounou" to Sorte.RDV, "Babysitting" to Sorte.RDV,
        "Anniversaire de Jade" to Sorte.RDV, "Appeler le plombier" to Sorte.RDV, "Appeler la banque" to Sorte.RDV, "Rappeler Julien" to Sorte.RDV, "Envoyer le dossier" to Sorte.RDV, "Payer la facture" to Sorte.RDV,
        "Faire le plein" to Sorte.RDV, "Arroser les plantes" to Sorte.RDV, "Nourrir le chat" to Sorte.RDV, "Promener le chien" to Sorte.RDV, "Ménage" to Sorte.RDV, "Repassage" to Sorte.RDV,
        "Lessive" to Sorte.RDV, "Jardinage" to Sorte.RDV, "Tondre la pelouse" to Sorte.RDV, "Vol pour Lisbonne" to Sorte.RDV, "Train pour Lyon" to Sorte.RDV, "Bus pour Strasbourg" to Sorte.RDV,
        "Vacances en Bretagne" to Sorte.RDV, "Week-end à Paris" to Sorte.RDV, "Location de voiture" to Sorte.RDV, "Hôtel à Nice" to Sorte.RDV, "Camping" to Sorte.RDV, "Croisière" to Sorte.RDV,
        "Session Minecraft" to Sorte.RDV, "Tournoi de Fortnite" to Sorte.RDV, "Partie d'échecs" to Sorte.RDV, "Jeu de rôle" to Sorte.RDV, "Soirée Warhammer" to Sorte.RDV, "Stream" to Sorte.RDV,
        "LAN party" to Sorte.RDV, "Raid WoW" to Sorte.RDV, "Apéro avec les collègues" to Sorte.RDV, "Déjeuner avec maman" to Sorte.RDV, "Café avec Léo" to Sorte.RDV, "Resto japonais" to Sorte.RDV,
        "Dîner aux chandelles" to Sorte.RDV, "Goûter chez mamie" to Sorte.RDV, "Repas de famille" to Sorte.RDV, "Afterwork" to Sorte.RDV, "Petit-déjeuner d'équipe" to Sorte.RDV)
    private val gens = listOf("Max", "Inès", "Tom", "Julie", "Paul", "Hugo", "Sarah", "Lucas", "Chloé", "Maman", "Marc", "Emma", "Nathan", "Léna", "Yanis", "Camille",
        "Papa", "Mamie", "Kevin", "Laura", "Mehdi", "Zoé", "Antoine", "Clara", "Romain", "Jade", "Bastien", "Manon")
    private val lieux = listOf("12 rue des Lilas, Lyon", "stade de Woippy", "Metz", "Nancy", "Thionville", "salle B", "chez Max", "gymnase", "3 avenue Foch, Metz", "Strasbourg",
        "Reims", "Épinal", "piscine municipale", "salle des fêtes", "8 rue Victor Hugo, Nancy", "centre commercial", "la mairie", "chez mamie", "bureau", "Luxembourg", "Sarreguemines")
    private val notesExistantes = listOf("Code de la porte 4521B", "Interphone : Martin", "2e étage, porte gauche", "Payer en liquide", "Parking derrière le bâtiment",
        "Rappeler pour confirmer", "Apporter 20 euros", "Code wifi : maison2024")
    private val choses = listOf("Carte Vitale", "Ordonnance", "Radios", "Crampons", "Maillot", "Lait", "Pain", "Œufs", "Chips", "Gâteau", "Dossier", "Chargeur")

    private val MotsSupprimer = "supprim|annul|enleve|retire|efface|vire|degage|plus lieu|tombe a l.eau|oublie|laisse tomber|pas aller|y vais pas|irai pas|pourrai pas venir|peux pas venir|pas venir|plus venir|stop|arret"
    private val MotsDecaler = "decal|repousse|avance|recul|plus tard|plus tot|retard|bouge|deplace|pousse"
    private fun code(r: RendezVous) = "e" + r.id.replace("-", "").take(5)
    private fun h(t: LocalTime) = if (t.minute == 0) "${t.hour} h" else "${t.hour} h %02d".format(t.minute)
    private fun cle(titre: String) = motsTitre(titre).firstOrNull() ?: sansAccents(titre).split(' ').first()
    private fun prochain(auj: LocalDate, j: DayOfWeek): LocalDate { var e = (j.value - auj.dayOfWeek.value + 7) % 7; if (e == 0) e = 7; return auj.plusDays(e.toLong()) }

    private fun agendaAuHasard(rnd: Random, n: LocalDateTime): List<RendezVous> {
        val auj = n.toLocalDate()
        return modeles.shuffled(rnd).take(5 + rnd.nextInt(5)).map { m ->
            // (quelques rendez-vous déjà passés : « supprime le coiffeur » d'avant-hier)
            val d = if (m.repete == null && rnd.nextInt(4) == 0) auj.minusDays((1 + rnd.nextInt(20)).toLong()) else auj.plusDays((1 + rnd.nextInt(12)).toLong())
            var r = RendezVous(titre = m.titre, sorte = m.sorte, quand = if (m.heure == null) d.atStartOfDay() else d.atTime(m.heure), journee = m.heure == null)
            r = when (m.repete) {
                "semaine" -> r.copy(repetition = Repetition("semaine", jour = d.dayOfWeek.value % 7).versJson())
                "mois" -> r.copy(repetition = Repetition("mois", jour = d.dayOfMonth).versJson())
                "an" -> r.copy(repetition = Repetition("an", jour = d.dayOfMonth, mois = d.monthValue).versJson(), naissance = n.year - (20 + rnd.nextInt(40)))
                else -> r
            }
            if (m.heure != null && m.repete == null && rnd.nextInt(3) == 0) r = r.copy(fin = r.quand.plusMinutes(listOf(30L, 60L, 90L).random(rnd)))
            if (rnd.nextInt(3) == 0) r = r.copy(lieu = lieux.random(rnd))
            if (rnd.nextInt(3) == 0) r = r.copy(avec = gens.shuffled(rnd).take(1 + rnd.nextInt(3)))
            if (rnd.nextInt(4) == 0) r = r.copy(aPreparer = choses.shuffled(rnd).take(1 + rnd.nextInt(2)).map { APreparer(it) })
            if (rnd.nextInt(6) == 0) r = r.copy(important = true)
            if (rnd.nextInt(5) == 0) r = r.copy(notes = notesExistantes.random(rnd))
            r
        }
    }

    /** Une intention : ce que l'utilisateur veut (pour l'IA qui écrit sa phrase), la bonne réponse, et sa vérification. */
    private class Tache(val type: String, val description: String, val cible: String, val indices: List<String>, val verif: (List<RendezVous>, Resultat) -> Boolean)

    private fun json(actions: List<Map<String, Any>>, reponse: String = "") = buildJsonObject {
        put("actions", JsonArray(actions.map { a ->
            buildJsonObject {
                for ((k, v) in a) when (v) {
                    is String -> put(k, v)
                    is Boolean -> put(k, v)
                    is List<*> -> put(k, JsonArray(v.map { JsonPrimitive(it.toString()) }))
                }
            }
        }))
        put("reponse", reponse)
    }.toString()

    /** Une question : ce qui est demandé, sur un rendez-vous (son code) ou une période (« samedi soir »). */
    private fun question(sujet: String, r: RendezVous? = null, periode: String? = null) =
        json(listOf(buildMap { put("action", "question"); put("sujet", sujet); r?.let { put("id", code(it)) }; periode?.let { put("periode", it) } }))

    private fun tacheAuHasard(rnd: Random, ag: List<RendezVous>, n: LocalDateTime): Tache? {
        val auj = n.toLocalDate()
        val agenda = AgendaIA(ag, n)
        fun fois(r: RendezVous) = agenda.prochaine(r, if (r.journee) auj.atStartOfDay() else n) ?: r.quand
        fun quand(r: RendezVous) = quandRV(r, fois(r), auj)
        fun nom(r: RendezVous) = "« ${titreDe(r)} » (${quand(r)})"
        val horaire = ag.filter { !it.journee && it.repetition == null && it.quand.isAfter(n) }
        val series = ag.filter { it.laRepetition?.type == "semaine" }
        val tous = ag.filter { it.quand.isAfter(n) || it.repetition != null }
        val types = listOf("m_jour", "m_heure", "m_decal", "m_lieu", "m_pers", "m_retire", "m_chose", "m_important", "m_rappel", "m_titre", "m_note", "m_duree",
            "s_un", "s_fois", "s_mois", "s_arret", "f_un", "c_simple", "c_avec", "c_lieu", "c_liste", "c_repet", "c_mois", "c_anniv", "c_plusieurs", "c_rappel",
            "q_quand", "q_ou", "q_qui", "q_jour", "q_prendre", "n_rien", "d_double",
            "c_important", "c_plage", "c_meme", "s_passe", "s_apres", "q_age", "q_note", "q_prochain", "m_note", "s_un", "c_simple", "s_passe", "s_passe", "q_age", "q_age", "m_jourmois", "m_jourmois", "m_serie", "m_serie", "c_simple", "c_plage", "c_important", "c_repet", "m_note2", "m_note2", "s_empeche", "s_empeche", "s_apres", "c_mois", "q_libre", "q_duree",
            // (nuit 3 : les tournures que les règles faisaient mieux que le modèle)
            "c_jours", "c_jours", "c_jusqua", "c_apporte", "c_apporte", "c_huit", "c_huit", "c_soir", "c_soir", "c_lieu", "c_lieu",
            "d_ajout2", "d_ajout2", "d_ajout2", "d_suppr_creer", "d_suppr_creer", "q_double", "q_double",
            // (nuit 4 : une création qui dit tout à la fois, et une série avec une plage horaire)
            "c_complet", "c_complet", "c_complet", "c_complet", "c_complet", "c_complet", "c_repet_plage", "c_repet_plage", "q_double", "q_double")
        val type = types.random(rnd)
        fun choisir(l: List<RendezVous>) = l.randomOrNull(rnd)
        fun memeHeure(apres: List<RendezVous>, r: RendezVous, jour: LocalDate, heure: LocalTime) = apres.firstOrNull { it.id == r.id }?.let { it.quand.toLocalDate() == jour && it.quand.toLocalTime() == heure } == true
        when (type) {
            "m_jour" -> {
                val r = choisir(horaire) ?: return null
                val j = DayOfWeek.entries.filter { it != r.quand.dayOfWeek }.random(rnd)
                val d = prochain(auj, j)
                val w = jours[j.value - 1]
                return Tache(type, "déplacer ${nom(r)} à $w, à la même heure", json(listOf(mapOf("action" to "modifier", "id" to code(r), "phrase" to "$w à ${h(r.quand.toLocalTime())}"))),
                    listOf(cle(r.titre), w)) { a, _ -> memeHeure(a, r, d, r.quand.toLocalTime()) }
            }
            "m_heure" -> {
                val r = choisir(horaire) ?: return null
                val t = LocalTime.of(8 + rnd.nextInt(13), listOf(0, 0, 30, 15, 45).random(rnd))
                if (t == r.quand.toLocalTime()) return null
                return Tache(type, "mettre ${nom(r)} à ${h(t)}, le même jour", json(listOf(mapOf("action" to "modifier", "id" to code(r), "phrase" to "le ${r.quand.format(fDate)} à ${h(t)}"))),
                    listOf(cle(r.titre), "${t.hour}")) { a, _ -> memeHeure(a, r, r.quand.toLocalDate(), t) }
            }
            "m_decal" -> {
                val r = choisir(horaire) ?: return null
                val k = listOf(-2L, -1L, 1L, 2L, 3L).random(rnd)
                val t = r.quand.plusHours(k)
                if (t.toLocalDate() != r.quand.toLocalDate()) return null
                val dit = if (k > 0) "${if (k == 1L) "d'une heure" else "de $k heures"} plus tard" else "${if (k == -1L) "d'une heure" else "de ${-k} heures"} plus tôt"
                return Tache(type, "décaler ${nom(r)} $dit", json(listOf(mapOf("action" to "modifier", "id" to code(r), "phrase" to "le ${t.format(fDate)} à ${h(t.toLocalTime())}"))),
                    listOf(cle(r.titre), MotsDecaler)) { a, _ -> memeHeure(a, r, t.toLocalDate(), t.toLocalTime()) }
            }
            "m_lieu" -> {
                val r = choisir(tous) ?: return null
                val l = lieux.filter { it != r.lieu }.random(rnd)
                return Tache(type, "dire que ${nom(r)} a lieu ${if (l.first().isDigit()) "au $l" else "à $l"}", json(listOf(mapOf("action" to "modifier", "id" to code(r), "lieu" to l))),
                    listOf(cle(r.titre), sansAccents(l.split(',')[0].split(' ').last()))) { a, _ -> a.first { it.id == r.id }.lieu == l }
            }
            "m_pers" -> {
                val r = choisir(tous.filter { it.sorte != Sorte.ANNIV }) ?: return null
                val p = gens.filter { it !in r.avec }.random(rnd)
                return Tache(type, "ajouter $p à ${nom(r)}", json(listOf(mapOf("action" to "modifier", "id" to code(r), "personnes" to listOf(p)))),
                    listOf(cle(r.titre), sansAccents(p))) { a, _ -> p in a.first { it.id == r.id }.avec }
            }
            "m_retire" -> {
                val r = choisir(tous.filter { it.avec.size >= 1 }) ?: return null
                val p = r.avec.random(rnd)
                return Tache(type, "retirer $p de ${nom(r)} (les autres restent)", json(listOf(mapOf("action" to "modifier", "id" to code(r), "retirer_personnes" to listOf(p)))),
                    listOf(cle(r.titre), sansAccents(p))) { a, _ -> a.first { it.id == r.id }.let { x -> p !in x.avec && x.avec.size == r.avec.size - 1 } }
            }
            "m_chose" -> {
                val r = choisir(tous) ?: return null
                val c = choses.filter { x -> r.aPreparer.none { it.texte == x } }.random(rnd)
                return Tache(type, "ajouter « $c » aux choses à prendre pour ${nom(r)}", json(listOf(mapOf("action" to "modifier", "id" to code(r), "choses_a_prendre" to listOf(c)))),
                    listOf(cle(r.titre), sansAccents(c.lowercase()).split(' ').first())) { a, _ -> a.first { it.id == r.id }.aPreparer.any { it.texte.equals(c, true) } }
            }
            "m_important" -> {
                val r = choisir(tous.filter { !it.important }) ?: return null
                return Tache(type, "marquer ${nom(r)} comme important", json(listOf(mapOf("action" to "modifier", "id" to code(r), "important" to true))),
                    listOf(cle(r.titre), "important|priorit|urgent|rouge|etoile|crucial|essentiel")) { a, _ -> a.first { it.id == r.id }.important }
            }
            "m_rappel" -> {
                val r = choisir(tous) ?: return null
                val (dit, code) = listOf("la veille" to "veille", "une heure avant" to "m60", "deux jours avant" to "m2880", "le matin même" to "matin", "30 minutes avant" to "m30").random(rnd)
                if (r.journee && code.startsWith("m") && code != "matin") return null
                return Tache(type, "demander un rappel $dit pour ${nom(r)}", json(listOf(mapOf("action" to "modifier", "id" to code(r), "rappels" to listOf(dit)))),
                    listOf(cle(r.titre), "rappel|previen|alerte|notif|sonne")) { a, _ -> a.first { it.id == r.id }.rappels?.contains(code) == true }
            }
            "m_titre" -> {
                val r = choisir(tous.filter { it.sorte != Sorte.ANNIV }) ?: return null
                val t = listOf("Match de ${titreDe(r).lowercase()}", "${titreDe(r)} du mois", "Séance de ${titreDe(r).lowercase()}", "${titreDe(r)} important").random(rnd)
                return Tache(type, "renommer ${nom(r)} en « $t »", json(listOf(mapOf("action" to "modifier", "id" to code(r), "titre" to t))),
                    listOf(cle(r.titre))) { a, _ -> a.first { it.id == r.id }.titre.equals(t, true) }
            }
            "m_note" -> {
                val r = choisir(tous) ?: return null
                val note = listOf("demander un devis", "code de la porte 1234", "prévoir de la monnaie", "se garer derrière", "rappeler la veille pour confirmer", "apporter le dossier bleu").random(rnd)
                return Tache(type, "ajouter la note « $note » à ${nom(r)}", json(listOf(mapOf("action" to "modifier", "id" to code(r), "notes" to note.replaceFirstChar { it.uppercase() }))),
                    listOf(cle(r.titre), sansAccents(note).split(' ').last())) { a, _ -> a.first { it.id == r.id }.notes.contains(note, true) }
            }
            "m_duree" -> {
                val r = choisir(horaire) ?: return null
                val min = listOf(30L, 45L, 60L, 90L, 120L).random(rnd)
                val f = r.quand.plusMinutes(min)
                if (f.toLocalDate() != r.quand.toLocalDate()) return null
                val dit = when (min) { 30L -> "une demi-heure"; 45L -> "trois quarts d'heure"; 60L -> "une heure"; 90L -> "une heure et demie"; else -> "deux heures" }
                return Tache(type, "dire que ${nom(r)} dure $dit", json(listOf(mapOf("action" to "modifier", "id" to code(r), "phrase" to "le ${r.quand.format(fDate)} de ${h(r.quand.toLocalTime())} à ${h(f.toLocalTime())}"))),
                    listOf(cle(r.titre), "dure|duree|jusqu|finit|termine|fin ")) { a, _ -> a.first { it.id == r.id }.let { x -> x.quand == r.quand && x.fin == f } }
            }
            "s_un" -> {
                val r = choisir(ag) ?: return null
                return Tache(type, "supprimer ${nom(r)}${if (r.repetition != null) " (toutes les fois)" else ""}", json(listOf(mapOf("action" to "supprimer", "id" to code(r)))),
                    listOf(cle(r.titre), MotsSupprimer)) { a, _ -> a.none { it.id == r.id } && a.size == ag.size - 1 }
            }
            "s_fois" -> {
                val r = choisir(series) ?: return null
                val f = fois(r).toLocalDate()
                return Tache(type, "supprimer seulement ${nom(r)} du ${f.format(fJour)} (les autres semaines restent)",
                    json(listOf(mapOf("action" to "supprimer", "id" to code(r), "periode" to "le ${f.format(fDate)}"))), listOf(cle(r.titre), MotsSupprimer)) { a, _ ->
                    val x = a.firstOrNull { it.id == r.id } ?: return@Tache false
                    val ax = AgendaIA(a, n)
                    ax.entre(f.atStartOfDay(), f.plusDays(1).atStartOfDay()).none { it.rv.id == r.id } && ax.entre(f.plusDays(7).atStartOfDay(), f.plusDays(8).atStartOfDay()).any { it.rv.id == x.id }
                }
            }
            "s_mois" -> {
                val r = choisir(series) ?: return null
                val m = auj.plusMonths(1 + rnd.nextInt(2).toLong()).monthValue
                val nm = mois[m - 1]
                return Tache(type, "supprimer tous les « ${titreDe(r)} » du mois de $nm (les autres mois restent)", json(listOf(mapOf("action" to "supprimer", "id" to code(r), "periode" to nm))),
                    listOf(cle(r.titre), sansAccents(nm), MotsSupprimer)) { a, _ ->
                    val x = a.firstOrNull { it.id == r.id } ?: return@Tache false
                    val an = if (m < auj.monthValue) auj.year + 1 else auj.year
                    val d1 = LocalDate.of(an, m, 1)
                    val ax = AgendaIA(a, n)
                    ax.entre(d1.atStartOfDay(), d1.plusMonths(1).atStartOfDay()).none { it.rv.id == r.id } && ax.entre(d1.plusMonths(1).atStartOfDay(), d1.plusMonths(2).atStartOfDay()).any { it.rv.id == x.id }
                }
            }
            "s_arret" -> {
                val r = choisir(series) ?: return null
                val m = auj.plusMonths(2).monthValue
                val nm = mois[m - 1]
                return Tache(type, "arrêter « ${titreDe(r)} » à partir de $nm (il continue jusque-là)", json(listOf(mapOf("action" to "supprimer", "id" to code(r), "periode" to "à partir de $nm"))),
                    listOf(cle(r.titre), sansAccents(nm))) { a, _ -> a.firstOrNull { it.id == r.id }?.laRepetition?.jusqua != null }
            }
            "f_un" -> {
                val r = choisir(ag.filter { it.sorte == Sorte.A_FAIRE }) ?: return null
                return Tache(type, "dire que « ${titreDe(r)} » est fait", json(listOf(mapOf("action" to "fait", "id" to code(r)))), listOf(cle(r.titre))) { a, _ -> a.first { it.id == r.id }.fait }
            }
            "c_simple", "c_avec", "c_lieu", "c_rappel" -> {
                val (t, _) = nouveaux.random(rnd)
                val heure = LocalTime.of(8 + rnd.nextInt(13), listOf(0, 0, 30, 15).random(rnd))
                val (dit, d) = when (rnd.nextInt(3)) {
                    0 -> "demain" to auj.plusDays(1)
                    1 -> { val j = DayOfWeek.entries.random(rnd); jours[j.value - 1] to prochain(auj, j) }
                    else -> { val d = auj.plusDays((3 + rnd.nextInt(40)).toLong()); "le ${d.dayOfMonth} ${mois[d.monthValue - 1]}" to d }
                }
                val action = mutableMapOf<String, Any>("action" to "creer", "phrase" to "$t $dit à ${h(heure)}")
                var desc = "créer « $t » $dit à ${h(heure)}"
                val ind = mutableListOf(sansAccents(t).split(' ', '\'').first { it.length > 2 }, "${heure.hour}")
                var extra: (RendezVous) -> Boolean = { true }
                when (type) {
                    "c_avec" -> { val p = gens.shuffled(rnd).take(1 + rnd.nextInt(2)); action["personnes"] = p; desc += " avec ${p.joinToString(" et ")}"; ind += sansAccents(p[0]); extra = { r -> r.avec.containsAll(p) } }
                    "c_lieu" -> { val l = lieux.random(rnd); action["lieu"] = l; desc += if (l.first().isDigit()) " au $l" else " à $l"; ind += sansAccents(l.split(',')[0].split(' ').last()); extra = { r -> r.lieu == l } }
                    "c_rappel" -> { action["rappels"] = listOf("la veille"); desc += ", avec un rappel la veille"; ind += "veille"; extra = { r -> r.rappels?.contains("veille") == true } }
                }
                return Tache(type, desc, json(listOf(action)), ind) { a, _ ->
                    a.filter { x -> ag.none { it.id == x.id } }.singleOrNull()?.let { r -> r.quand.toLocalDate() == d && r.quand.toLocalTime() == heure && extra(r) } == true
                }
            }
            "c_liste" -> {
                val l = listOf("lait", "pain", "œufs", "beurre", "pâtes", "riz", "jambon", "fromage", "tomates", "café").shuffled(rnd).take(2 + rnd.nextInt(3))
                val j = DayOfWeek.entries.random(rnd)
                val d = prochain(auj, j)
                val heure = LocalTime.of(9 + rnd.nextInt(10), 0)
                return Tache(type, "créer des courses ${jours[j.value - 1]} à ${h(heure)} avec la liste : ${l.joinToString(", ")}",
                    json(listOf(mapOf("action" to "creer", "phrase" to "courses ${jours[j.value - 1]} à ${h(heure)}", "choses_a_prendre" to l))), listOf("course", jours[j.value - 1]) + l.take(2).map { sansAccents(it) }) { a, _ ->
                    a.filter { x -> ag.none { it.id == x.id } }.singleOrNull()?.let { r -> r.quand.toLocalDate() == d && r.aPreparer.size == l.size } == true
                }
            }
            "c_repet" -> {
                val (t, _) = nouveaux.random(rnd)
                val j = DayOfWeek.entries.random(rnd)
                val heure = LocalTime.of(8 + rnd.nextInt(13), listOf(0, 30).random(rnd))
                return Tache(type, "créer « $t » tous les ${jours[j.value - 1]}s à ${h(heure)}", json(listOf(mapOf("action" to "creer", "phrase" to "$t tous les ${jours[j.value - 1]}s à ${h(heure)}"))),
                    listOf(sansAccents(t).split(' ', '\'').first { it.length > 2 }, jours[j.value - 1])) { a, _ ->
                    a.filter { x -> ag.none { it.id == x.id } }.singleOrNull()?.let { r -> r.laRepetition?.type == "semaine" && r.laRepetition?.jour == j.value % 7 && r.quand.toLocalTime() == heure } == true
                }
            }
            "c_mois" -> {
                val t = listOf("Payer la cantine", "Payer l'électricité", "Virement épargne", "Payer la nounou", "Facture d'électricité", "Facture de gaz",
                    "Facture internet", "Payer le forfait téléphone", "Abonnement salle de sport", "Payer l'assurance auto", "Envoyer la facture au client",
                    "Relevé de compteur", "Payer la mutuelle", "Virement à maman", "Payer le crédit", "Faire les comptes", "Changer les draps",
                    "Payer la femme de ménage", "Cotisation du club", "Remplir la déclaration Urssaf", "Payer l'eau", "Recharger le pass Navigo",
                    "Payer le loyer du garage", "Rendre le rapport mensuel", "Abonnement Netflix").random(rnd)
                val dj = 1 + rnd.nextInt(28)
                return Tache(type, "créer un rappel « $t » le $dj de chaque mois", json(listOf(mapOf("action" to "creer", "phrase" to "$t le $dj de chaque mois"))),
                    listOf(sansAccents(t).split(' ').last(), "$dj")) { a, _ -> a.filter { x -> ag.none { it.id == x.id } }.singleOrNull()?.laRepetition?.let { it.type == "mois" && it.jour == dj } == true }
            }
            "c_anniv" -> {
                val p = listOf("Thomas", "Nathalie", "Kevin", "Camille", "Jules", "Manon", "Élodie", "Yasmine", "Rémi", "Sophie", "Ahmed", "Lucie", "Bruno",
                    "Océane", "Théo", "Inès", "Grégory", "Maëlys", "Noah", "Christine", "Pierre", "Anaïs", "Mathis", "Sylvie", "Karim", "Louise", "papa",
                    "maman", "mamie", "papi", "ma sœur", "mon frère", "tonton Jean", "tata Marie").random(rnd)
                val d = auj.plusDays((5 + rnd.nextInt(300)).toLong())
                val age = 5 + rnd.nextInt(70)
                return Tache(type, "créer l'anniversaire de $p le ${d.dayOfMonth} ${mois[d.monthValue - 1]}, qui aura $age ans",
                    json(listOf(mapOf("action" to "creer", "phrase" to "anniversaire de $p le ${d.dayOfMonth} ${mois[d.monthValue - 1]}, il aura $age ans"))),
                    listOf(sansAccents(p), "$age")) { a, _ -> a.filter { x -> ag.none { it.id == x.id } }.singleOrNull()?.let { it.sorte == Sorte.ANNIV && it.naissance == d.year - age } == true }
            }
            "c_plusieurs" -> {
                val d1 = auj.plusDays((5 + rnd.nextInt(60)).toLong())
                val d2 = d1.plusDays((1 + rnd.nextInt(6)).toLong())
                val t = listOf("Vacances", "Séminaire", "Salon", "Week-end à Annecy", "Formation", "Vacances en Espagne", "Stage de foot", "Colonie des enfants",
                    "Congés", "Voyage à Rome", "Camping", "Festival", "Déplacement à Lyon", "Séjour au ski", "Tournoi", "Mission à Bruxelles", "Week-end en amoureux",
                    "Retraite yoga", "Hospitalisation", "Arrêt maladie").random(rnd)
                val dit = if (d1.monthValue == d2.monthValue) "du ${d1.dayOfMonth} au ${d2.dayOfMonth} ${mois[d2.monthValue - 1]}" else "du ${d1.dayOfMonth} ${mois[d1.monthValue - 1]} au ${d2.dayOfMonth} ${mois[d2.monthValue - 1]}"
                return Tache(type, "créer « $t » $dit", json(listOf(mapOf("action" to "creer", "phrase" to "$t $dit"))), listOf(sansAccents(t).split(' ').first(), "${d2.dayOfMonth}")) { a, _ ->
                    a.filter { x -> ag.none { it.id == x.id } }.singleOrNull()?.let { it.quand.toLocalDate() == d1 && it.fin?.toLocalDate() == d2 } == true
                }
            }
            // Les questions : le modèle dit ce qui est demandé (« sujet ») et sur quoi (le code ou la période) ; Evermind
            // calcule la réponse, et la vérification regarde qu'elle contient la bonne information
            "q_quand" -> {
                val r = choisir(tous) ?: return null
                return Tache(type, "demander quand a lieu « ${titreDe(r)} »", question("quand", r), listOf(cle(r.titre))) { a, res -> a == ag && res.dit.contains(titreDe(r)) && res.dit.contains(quand(r)) }
            }
            "q_ou" -> {
                val r = choisir(tous.filter { it.lieu.isNotBlank() }) ?: return null
                return Tache(type, "demander où a lieu « ${titreDe(r)} »", question("ou", r), listOf(cle(r.titre))) { a, res -> a == ag && res.dit.contains(r.lieu) }
            }
            "q_qui" -> {
                val r = choisir(tous.filter { it.avec.isNotEmpty() }) ?: return null
                return Tache(type, "demander qui vient à « ${titreDe(r)} »", question("qui", r), listOf(cle(r.titre))) { a, res -> a == ag && r.avec.all { res.dit.contains(it) } }
            }
            "q_prendre" -> {
                val r = choisir(tous.filter { it.aPreparer.isNotEmpty() }) ?: return null
                return Tache(type, "demander ce qu'il faut prendre pour « ${titreDe(r)} »", question("prendre", r),
                    listOf(cle(r.titre), "prendre|apporter|emporter|amener|emmener|besoin|prevoir|sac")) { a, res -> a == ag && r.aPreparer.all { res.dit.contains(it.texte) } }
            }
            "q_jour" -> {
                val (dit, d) = if (rnd.nextBoolean()) "demain" to auj.plusDays(1) else { val j = DayOfWeek.entries.random(rnd); jours[j.value - 1] to prochain(auj, j) }
                val f = agenda.entre(d.atStartOfDay(), d.plusDays(1).atStartOfDay())
                return Tache(type, "demander ce qui est prévu $dit", question("jour", periode = dit), listOf(dit)) { a, res ->
                    a == ag && res.dit.startsWith(dit.replaceFirstChar { it.uppercase() }) &&
                        (if (f.isEmpty()) res.dit.contains("rien") else f.all { x -> res.dit.contains(if (x.rv.journee) titreDe(x.rv) else "${titreDe(x.rv)} à ${h(x.prochain.toLocalTime())}") })
                }
            }
            "q_libre" -> {
                val j = DayOfWeek.entries.random(rnd)
                val (dit, d) = if (rnd.nextInt(3) == 0) "demain" to auj.plusDays(1) else jours[j.value - 1] to prochain(auj, j)
                val (moment, heures) = listOf("soir" to 17..23, "matin" to 0..11, "" to 0..23).random(rnd)
                val per = if (moment.isEmpty()) dit else "$dit $moment"
                val f = agenda.entre(d.atStartOfDay(), d.plusDays(1).atStartOfDay()).filter { moment.isEmpty() || (!it.rv.journee && it.prochain.hour in heures) }
                return Tache(type, "demander s'il est libre $per", question("libre", periode = per), listOf(dit, "libre|dispo|disponible|occupe|quelque chose|prevu|du temps")) { a, res ->
                    a == ag && if (f.isEmpty()) res.dit.contains("tu es libre") else res.dit.contains("tu as déjà") && f.all { res.dit.contains(titreDe(it.rv)) }
                }
            }
            "q_duree" -> {
                val r = choisir(horaire.filter { it.fin != null }) ?: return null
                val min = java.time.Duration.between(r.quand, r.fin).toMinutes()
                val dit = if (min < 60) "$min min" else if (min % 60 == 0L) "${min / 60} h" else "${min / 60} h %02d".format(min % 60)
                return Tache(type, "demander combien de temps dure « ${titreDe(r)} »", question("duree", r),
                    listOf(cle(r.titre), "dure|duree|combien de temps|longtemps|finit|termine")) { a, res -> a == ag && res.dit.contains("dure $dit") }
            }
            "n_rien" -> {
                val r = choisir(tous) ?: return null
                val (desc, rep) = listOf(
                    "raconter que « ${titreDe(r)} » s'est bien passé la dernière fois, sans rien demander" to "Tant mieux !",
                    "hésiter à supprimer « ${titreDe(r)} », sans vraiment le demander" to "D'accord, je ne change rien. Dis-le-moi quand tu seras sûr.",
                ).random(rnd)
                return Tache(type, desc, json(emptyList(), rep), listOf(cle(r.titre))) { a, _ -> a == ag }
            }
            "c_important", "c_plage", "c_meme" -> {
                val existant = if (type == "c_meme") choisir(horaire) ?: return null else null
                val t = existant?.let { titreDe(it) } ?: nouveaux.random(rnd).first
                val d = auj.plusDays((1 + rnd.nextInt(30)).toLong())
                if (existant != null && d == existant.quand.toLocalDate()) return null
                val dit = if (d.isBefore(auj.plusDays(7))) jours[d.dayOfWeek.value - 1] else "le ${d.dayOfMonth} ${mois[d.monthValue - 1]}"
                val h1 = LocalTime.of(8 + rnd.nextInt(10), listOf(0, 0, 30).random(rnd))
                val h2 = h1.plusMinutes(listOf(60L, 90L, 120L, 180L).random(rnd))
                val action = mutableMapOf<String, Any>("action" to "creer", "phrase" to "$t $dit " +
                    if (type == "c_plage") "de ${h(h1)} à ${h(h2)}" else "à ${h(h1)}")
                var desc = if (type == "c_meme") "créer un NOUVEAU « $t » $dit à ${h(h1)} (un autre que celui qui existe déjà)" else "créer « $t » $dit " + if (type == "c_plage") "de ${h(h1)} à ${h(h2)}" else "à ${h(h1)}"
                val ind = mutableListOf(sansAccents(t).split(' ', '\'').first { it.length > 2 }, "${h1.hour}")
                if (type == "c_important") { action["important"] = true; desc += ", et dire que c'est important"; ind += "important|urgent|priorit|crucial|essentiel|indispensable" }
                if (type == "c_plage") { ind += "${h2.hour}"; if (rnd.nextBoolean()) { val l = lieux.random(rnd); action["lieu"] = l; desc += if (l.first().isDigit()) " au $l" else " à $l"; ind += sansAccents(l.split(',')[0].split(' ').last()) } }
                if (type == "c_meme") ind += "nouveau|autre|deuxieme|second|encore|aussi|en plus|ajoute|rajoute|cree|mets"
                return Tache(type, desc, json(listOf(action)), ind) { a, _ ->
                    val r = a.filter { x -> ag.none { it.id == x.id } }.singleOrNull() ?: return@Tache false
                    r.quand == d.atTime(h1) && (type != "c_plage" || r.fin == d.atTime(h2)) && (type != "c_important" || r.important) &&
                        (existant == null || a.first { it.id == existant.id } == existant)
                }
            }
            "s_passe" -> {
                val r = choisir(ag.filter { it.repetition == null && it.quand.isBefore(n) }) ?: return null
                return Tache(type, "supprimer ${nom(r)}, qui est déjà passé", json(listOf(mapOf("action" to "supprimer", "id" to code(r)))),
                    listOf(cle(r.titre), MotsSupprimer)) { a, _ -> a.none { it.id == r.id } && a.size == ag.size - 1 }
            }
            "s_apres" -> {
                val r = choisir(series) ?: return null
                val m = auj.plusMonths(1).monthValue
                val d1 = auj.plusMonths(1).withDayOfMonth(1)
                return Tache(type, "arrêter « ${titreDe(r)} » après ${mois[m - 1]} (le dernier en ${mois[m - 1]}, plus aucun ensuite)",
                    json(listOf(mapOf("action" to "supprimer", "id" to code(r), "periode" to "après ${mois[m - 1]}"))),
                    listOf(cle(r.titre), sansAccents(mois[m - 1]))) { a, _ ->
                    val ax = AgendaIA(a, n)
                    a.any { it.id == r.id } && ax.entre(d1.atStartOfDay(), d1.plusMonths(1).atStartOfDay()).any { it.rv.id == r.id } &&
                        ax.entre(d1.plusMonths(1).atStartOfDay(), d1.plusMonths(3).atStartOfDay()).none { it.rv.id == r.id }
                }
            }
            "q_age" -> {
                val r = choisir(ag.filter { it.naissance != null }) ?: return null
                val p = titreDe(r).substringAfter(" de ").substringAfter(" d'")
                val f = fois(r)
                return Tache(type, "demander quel âge aura $p", question("age", r),
                    listOf(sansAccents(p), "age|ans|vieux|vieille")) { a, res -> a == ag && res.dit.contains("$p aura ${f.year - r.naissance!!} ans") }
            }
            "q_note" -> {
                val r = choisir(tous.filter { it.notes.isNotBlank() }) ?: return null
                val (q, ind) = when {
                    r.notes.startsWith("Code de la porte") -> "le code de la porte" to "code"
                    r.notes.startsWith("Code wifi") -> "le code wifi" to "wifi"
                    else -> "les notes" to "note|noté|detail|info|precision|rappelle-moi"
                }
                return Tache(type, "demander $q pour « ${titreDe(r)} »", question("notes", r), listOf(cle(r.titre), ind)) { a, res -> a == ag && res.dit.contains(r.notes) }
            }
            "q_prochain" -> {
                agenda.entre(n, n.plusDays(60)).firstOrNull() ?: return null
                // (le prochain qui a une heure : un anniversaire de la journée ne passe pas devant)
                val r = agenda.aVenir().let { v -> v.firstOrNull { !it.rv.journee } ?: v.first() }
                return Tache(type, "demander quel est son prochain rendez-vous", question("prochain"),
                    listOf("prochain|suivant|apres|ensuite|arrive")) { a, res -> a == ag && res.dit.contains("${titreDe(r.rv)}, ${quandRV(r.rv, r.prochain, auj)}") }
            }
            "m_jourmois" -> {
                val r = choisir(ag.filter { it.laRepetition?.type == "mois" }) ?: return null
                val j = (1..28).filter { it != r.laRepetition?.jour }.random(rnd)
                val heure = if (r.journee) "" else " à ${h(r.quand.toLocalTime())}"
                return Tache(type, "dire que « ${titreDe(r)} » est désormais le $j de chaque mois (au lieu du ${r.laRepetition?.jour})",
                    json(listOf(mapOf("action" to "modifier", "id" to code(r), "phrase" to "le $j de chaque mois$heure"))), listOf(cle(r.titre), "$j")) { a, _ ->
                    a.firstOrNull { it.id == r.id }?.laRepetition?.let { it.type == "mois" && it.jour == j } == true && a.size == ag.size
                }
            }
            "m_serie" -> {
                val r = choisir(series.filter { !it.journee }) ?: return null
                val j = DayOfWeek.entries.filter { it.value % 7 != r.laRepetition?.jour }.random(rnd)
                val w = jours[j.value - 1]
                return Tache(type, "dire que « ${titreDe(r)} » aura désormais lieu le $w, toutes les semaines (à la même heure)",
                    json(listOf(mapOf("action" to "modifier", "id" to code(r), "phrase" to "tous les ${w}s à ${h(r.quand.toLocalTime())}"))), listOf(cle(r.titre), w)) { a, _ ->
                    a.firstOrNull { it.id == r.id }?.let { x -> x.laRepetition?.type == "semaine" && x.laRepetition?.jour == j.value % 7 && x.quand.toLocalTime() == r.quand.toLocalTime() } == true && a.size == ag.size
                }
            }
            "m_note2" -> {
                val r = choisir(tous.filter { it.sorte == Sorte.SANTE || it.sorte == Sorte.RDV }) ?: return null
                val chose = listOf("un détartrage", "un devis", "une ordonnance pour mes lunettes", "un certificat médical", "le renouvellement de l'ordonnance",
                    "un arrêt de travail", "une facture", "un rendez-vous de contrôle", "une radio", "le résultat des analyses", "un échéancier", "une attestation").random(rnd)
                return Tache(type, "dire qu'au rendez-vous « ${titreDe(r)} » (${quand(r)}) il faudra demander $chose (une note, rien d'autre ne change)",
                    json(listOf(mapOf("action" to "modifier", "id" to code(r), "notes" to "Demander $chose"))),
                    listOf(cle(r.titre), sansAccents(chose).split(' ').last())) { a, _ -> a.first { it.id == r.id }.notes.contains(chose, true) && a.first { it.id == r.id }.quand == r.quand }
            }
            "s_empeche" -> {
                val r = choisir(horaire) ?: return null
                return Tache(type, "dire qu'on ne pourra pas aller à ${nom(r)} (il faut donc l'enlever de l'agenda)", json(listOf(mapOf("action" to "supprimer", "id" to code(r)))),
                    listOf(cle(r.titre), "pourrai pas|peux pas|pourrais pas|serai pas|vais pas|irai pas|pas venir|pas aller|empeche|annul|supprim|enleve")) { a, _ -> a.none { it.id == r.id } && a.size == ag.size - 1 }
            }
            "c_jours" -> {
                val (t, _) = nouveaux.random(rnd)
                val js = DayOfWeek.entries.shuffled(rnd).take(2).sortedBy { it.value }
                val heure = LocalTime.of(8 + rnd.nextInt(13), listOf(0, 30).random(rnd))
                val dit = "tous les ${jours[js[0].value - 1]}s et ${jours[js[1].value - 1]}s à ${h(heure)}"
                return Tache(type, "créer « $t » $dit", json(listOf(mapOf("action" to "creer", "phrase" to "$t $dit"))),
                    listOf(sansAccents(t).split(' ', '\'').first { it.length > 2 }, jours[js[0].value - 1], jours[js[1].value - 1])) { a, _ ->
                    a.filter { x -> ag.none { it.id == x.id } }.singleOrNull()?.let { r ->
                        val rep = r.laRepetition
                        rep?.type == "semaine" && (rep.jours?.toSet() ?: setOfNotNull(rep.jour)) == js.map { it.value % 7 }.toSet() && r.quand.toLocalTime() == heure
                    } == true
                }
            }
            "c_jusqua" -> {
                val (t, _) = nouveaux.random(rnd)
                val j = DayOfWeek.entries.random(rnd)
                val heure = LocalTime.of(8 + rnd.nextInt(13), listOf(0, 30).random(rnd))
                val fin = auj.plusDays((30 + rnd.nextInt(150)).toLong())
                val dit = "tous les ${jours[j.value - 1]}s à ${h(heure)} jusqu'au ${fin.dayOfMonth} ${mois[fin.monthValue - 1]}"
                return Tache(type, "créer « $t » $dit", json(listOf(mapOf("action" to "creer", "phrase" to "$t $dit"))),
                    listOf(sansAccents(t).split(' ', '\'').first { it.length > 2 }, jours[j.value - 1], "jusqu", "${fin.dayOfMonth}")) { a, _ ->
                    a.filter { x -> ag.none { it.id == x.id } }.singleOrNull()?.let { r -> r.laRepetition?.type == "semaine" && r.laRepetition?.jusqua != null && r.quand.toLocalTime() == heure } == true
                }
            }
            "c_apporte" -> {
                val (t, _) = nouveaux.random(rnd)
                val l = choses.shuffled(rnd).take(2 + rnd.nextInt(2))
                val j = DayOfWeek.entries.random(rnd)
                val d = prochain(auj, j)
                val heure = LocalTime.of(8 + rnd.nextInt(13), listOf(0, 30).random(rnd))
                val dit = "${jours[j.value - 1]} à ${h(heure)}"
                return Tache(type, "créer « $t » $dit, et il faudra apporter : ${l.joinToString(", ") { it.lowercase() }}",
                    json(listOf(mapOf("action" to "creer", "phrase" to "$t $dit", "choses_a_prendre" to l))),
                    listOf(sansAccents(t).split(' ', '\'').first { it.length > 2 }, jours[j.value - 1]) + l.take(2).map { sansAccents(it).split(' ').first() }) { a, _ ->
                    a.filter { x -> ag.none { it.id == x.id } }.singleOrNull()?.let { r -> r.quand.toLocalDate() == d && r.quand.toLocalTime() == heure && r.aPreparer.size == l.size } == true
                }
            }
            "c_huit" -> {
                val (t, _) = nouveaux.random(rnd)
                val j = DayOfWeek.entries.random(rnd)
                val d = prochain(auj, j).plusDays(7)
                val heure = LocalTime.of(8 + rnd.nextInt(13), listOf(0, 30).random(rnd))
                return Tache(type, "créer « $t » ${jours[j.value - 1]} en huit (le ${d.dayOfMonth} ${mois[d.monthValue - 1]}) à ${h(heure)} ; la phrase dit « ${jours[j.value - 1]} en huit », pas la date",
                    json(listOf(mapOf("action" to "creer", "phrase" to "$t le ${d.format(fDate)} à ${h(heure)}"))),
                    listOf(sansAccents(t).split(' ', '\'').first { it.length > 2 }, "${jours[j.value - 1]} en huit")) { a, _ ->
                    a.filter { x -> ag.none { it.id == x.id } }.singleOrNull()?.let { r -> r.quand.toLocalDate() == d && r.quand.toLocalTime() == heure } == true
                }
            }
            "c_soir" -> {
                val (t, _) = nouveaux.random(rnd)
                val heure = LocalTime.of(19 + rnd.nextInt(4), listOf(0, 0, 30).random(rnd))
                val (dit, d) = if (rnd.nextBoolean()) "demain" to auj.plusDays(1) else { val j = DayOfWeek.entries.random(rnd); jours[j.value - 1] to prochain(auj, j) }
                val parle = "${heure.hour - 12} h${if (heure.minute == 30) " et demie" else ""} du soir"
                return Tache(type, "créer « $t » $dit à $parle (soit ${h(heure)}) ; la phrase dit « $parle »",
                    json(listOf(mapOf("action" to "creer", "phrase" to "$t $dit à ${h(heure)}"))),
                    listOf(sansAccents(t).split(' ', '\'').first { it.length > 2 }, "${heure.hour - 12}", "soir")) { a, _ ->
                    a.filter { x -> ag.none { it.id == x.id } }.singleOrNull()?.let { r -> r.quand.toLocalDate() == d && r.quand.toLocalTime() == heure } == true
                }
            }
            "d_ajout2" -> {
                val r1 = choisir(tous) ?: return null
                val r2 = choisir(tous.filter { it.id != r1.id }) ?: return null
                val p = gens.filter { it !in r1.avec }.random(rnd)
                val c = choses.filter { x -> r2.aPreparer.none { it.texte.equals(x, true) } }.random(rnd)
                return Tache(type, "ajouter $p à ${nom(r1)}, et ajouter « $c » aux choses à prendre pour ${nom(r2)} (deux demandes dans la même phrase)",
                    json(listOf(mapOf("action" to "modifier", "id" to code(r1), "personnes" to listOf(p)), mapOf("action" to "modifier", "id" to code(r2), "choses_a_prendre" to listOf(c)))),
                    listOf(cle(r1.titre), cle(r2.titre), sansAccents(p), sansAccents(c).split(' ').first())) { a, _ ->
                    a.size == ag.size && a.first { it.id == r1.id }.avec.contains(p) && a.first { it.id == r2.id }.aPreparer.any { it.texte.equals(c, true) }
                }
            }
            "d_suppr_creer" -> {
                val r1 = choisir(horaire) ?: return null
                val (t, _) = nouveaux.random(rnd)
                val j = DayOfWeek.entries.random(rnd)
                val d = prochain(auj, j)
                val heure = LocalTime.of(8 + rnd.nextInt(13), listOf(0, 30).random(rnd))
                return Tache(type, "supprimer ${nom(r1)} et créer « $t » ${jours[j.value - 1]} à ${h(heure)} (deux demandes dans la même phrase)",
                    json(listOf(mapOf("action" to "supprimer", "id" to code(r1)), mapOf("action" to "creer", "phrase" to "$t ${jours[j.value - 1]} à ${h(heure)}"))),
                    listOf(cle(r1.titre), MotsSupprimer, sansAccents(t).split(' ', '\'').first { it.length > 2 }, jours[j.value - 1])) { a, _ ->
                    a.none { it.id == r1.id } && a.filter { x -> ag.none { it.id == x.id } }.singleOrNull()?.let { r -> r.quand.toLocalDate() == d && r.quand.toLocalTime() == heure } == true
                }
            }
            "q_double" -> {
                val r = choisir(tous.filter { it.lieu.isNotBlank() || it.avec.isNotEmpty() }) ?: return null
                // (chaque sujet : ce qu'on demande, ses indices, ce que la réponse doit contenir)
                val sujets = buildList {
                    add(Triple("quand", "quand a lieu « ${titreDe(r)} »", "quand|quel jour|quelle heure|c'est quand"))
                    if (r.lieu.isNotBlank()) add(Triple("ou", "où a lieu « ${titreDe(r)} »", "ou|adresse|lieu|endroit"))
                    if (r.avec.isNotEmpty()) add(Triple("qui", "qui vient à « ${titreDe(r)} »", "qui|avec qui|personnes"))
                }.shuffled(rnd).take(2)
                if (sujets.size < 2) return null
                fun contient(s: String, dit: String) = when (s) { "quand" -> dit.contains(quand(r)); "ou" -> dit.contains(r.lieu); else -> r.avec.all { dit.contains(it) } }
                return Tache(type, "demander, dans la même phrase, ${sujets[0].second} et ${sujets[1].second.substringBefore(" «")}",
                    json(sujets.map { mapOf("action" to "question", "sujet" to it.first, "id" to code(r)) }),
                    listOf(cle(r.titre), sujets[0].third, sujets[1].third)) { a, res -> a == ag && sujets.all { contient(it.first, res.dit) } }
            }
            "c_complet" -> {
                val (t, _) = nouveaux.random(rnd)
                val d = auj.plusDays((1 + rnd.nextInt(30)).toLong())
                val dit = when {
                    d == auj.plusDays(1) -> "demain"
                    d.isBefore(auj.plusDays(7)) -> jours[d.dayOfWeek.value - 1]
                    else -> "le ${d.dayOfMonth} ${mois[d.monthValue - 1]}"
                }
                val h1 = LocalTime.of(8 + rnd.nextInt(13), listOf(0, 0, 30, 15).random(rnd))
                val h2 = if (rnd.nextInt(3) == 0) h1.plusMinutes(listOf(60L, 90L, 120L).random(rnd)) else null
                if (h2 != null && h2.isBefore(h1)) return null
                val quandDit = if (h2 != null) "de ${h(h1)} à ${h(h2)}" else "à ${h(h1)}"
                val action = mutableMapOf<String, Any>("action" to "creer", "phrase" to "$t $dit $quandDit")
                val desc = StringBuilder("créer « $t » $dit $quandDit")
                val ind = mutableListOf(sansAccents(t).split(' ', '\'').first { it.length > 2 }, "${h1.hour}")
                // (deux à quatre détails à la fois, dans un ordre qui change)
                val details = listOf("lieu", "personnes", "choses", "important", "rappel").shuffled(rnd).take(2 + rnd.nextInt(3))
                val l = lieux.random(rnd)
                val p = gens.shuffled(rnd).take(1 + rnd.nextInt(2))
                val c = choses.shuffled(rnd).take(1 + rnd.nextInt(2))
                for (x in details) when (x) {
                    "lieu" -> { action["lieu"] = l; desc.append(if (l.first().isDigit()) ", au $l" else ", à $l"); ind += sansAccents(l.split(',')[0].split(' ').last()) }
                    "personnes" -> { action["personnes"] = p; desc.append(", avec ${p.joinToString(" et ")}"); ind += sansAccents(p[0]) }
                    "choses" -> { action["choses_a_prendre"] = c; desc.append(", il faudra apporter : ${c.joinToString(", ") { it.lowercase() }}"); ind += sansAccents(c[0]).split(' ').first() }
                    "important" -> { action["important"] = true; desc.append(", c'est important"); ind += "important|urgent|priorit|crucial|essentiel|indispensable" }
                    "rappel" -> { action["rappels"] = listOf("la veille"); desc.append(", avec un rappel la veille"); ind += "veille" }
                }
                desc.append(" (tous ces détails dans la même phrase)")
                return Tache(type, desc.toString(), json(listOf(action)), ind) { a, _ ->
                    a.filter { x -> ag.none { it.id == x.id } }.singleOrNull()?.let { r ->
                        r.quand == d.atTime(h1) && (h2 == null || r.fin == d.atTime(h2)) &&
                            ("lieu" !in details || r.lieu == l) && ("personnes" !in details || r.avec.containsAll(p)) &&
                            ("choses" !in details || r.aPreparer.size == c.size) && ("important" !in details || r.important) &&
                            ("rappel" !in details || r.rappels?.contains("veille") == true)
                    } == true
                }
            }
            "c_repet_plage" -> {
                val (t, _) = nouveaux.random(rnd)
                val j = DayOfWeek.entries.random(rnd)
                val h1 = LocalTime.of(8 + rnd.nextInt(12), listOf(0, 30).random(rnd))
                val h2 = h1.plusMinutes(listOf(60L, 90L, 120L).random(rnd))
                if (h2.isBefore(h1)) return null
                val dit = "tous les ${jours[j.value - 1]}s de ${h(h1)} à ${h(h2)}"
                return Tache(type, "créer « $t » $dit", json(listOf(mapOf("action" to "creer", "phrase" to "$t $dit"))),
                    listOf(sansAccents(t).split(' ', '\'').first { it.length > 2 }, jours[j.value - 1], "${h2.hour}")) { a, _ ->
                    a.filter { x -> ag.none { it.id == x.id } }.singleOrNull()?.let { r ->
                        r.laRepetition?.type == "semaine" && r.quand.toLocalTime() == h1 && r.fin?.toLocalTime() == h2
                    } == true
                }
            }
            "d_double" -> {
                val r1 = choisir(horaire) ?: return null
                val r2 = choisir(horaire.filter { it.id != r1.id }) ?: return null
                val t = LocalTime.of(8 + rnd.nextInt(13), 0)
                if (t == r2.quand.toLocalTime()) return null
                return Tache(type, "supprimer ${nom(r1)} et mettre ${nom(r2)} à ${h(t)} le même jour",
                    json(listOf(mapOf("action" to "supprimer", "id" to code(r1)), mapOf("action" to "modifier", "id" to code(r2), "phrase" to "le ${r2.quand.format(fDate)} à ${h(t)}"))),
                    listOf(cle(r1.titre), cle(r2.titre), MotsSupprimer, "${t.hour}")) { a, _ -> a.none { it.id == r1.id } && memeHeure(a, r2, r2.quand.toLocalDate(), t) }
            }
        }
        return null
    }

    @Test fun fabriquer() {
        val chemin = System.getenv("EVERMIND_FABRIQUE")
        assumeTrue("pas de fabrique demandée (EVERMIND_FABRIQUE)", chemin != null)
        val n = System.getenv("EVERMIND_FABRIQUE_N")?.toInt() ?: 2000
        val rnd = Random(System.getenv("EVERMIND_FABRIQUE_GRAINE")?.toInt() ?: 1)
        var gardes = 0
        var rejets = 0
        val parType = sortedMapOf<String, Int>()
        File(chemin!!).bufferedWriter(Charsets.UTF_8).use { out ->
            var essais = 0
            while (gardes < n && essais < n * 20) {
                essais++
                val maintenant = LocalDateTime.of(2026, 1, 5, 8, 0).plusDays(rnd.nextInt(700).toLong()).withHour(8 + rnd.nextInt(13)).withMinute(listOf(0, 10, 25, 40).random(rnd))
                val ag = agendaAuHasard(rnd, maintenant)
                val t = tacheAuHasard(rnd, ag, maintenant) ?: continue
                val agenda = AgendaIA(ag, maintenant)
                val ctx = Consignes.court(agenda)
                val res = runCatching { Actions(agenda, { emptyList() }, mutableListOf()).appliquer(ReponseIA.lire(t.cible)!!, ctx.codes, "") }.getOrNull()
                if (res == null || !runCatching { t.verif(agenda.liste, res) }.getOrDefault(false)) { rejets++; if (System.getenv("EVERMIND_TRACE") != null) println("REJET ${t.type} : ${t.cible} -> ${res?.dit} | ${t.description} | apres=${agenda.liste.filter { x -> ag.none { it == x } }.map { it.titre + " " + it.quand + " " + it.id.take(6) }}"); continue }
                // (l'agenda en clair, pour l'IA qui écrit la phrase)
                val enClair = ag.joinToString("\n") { r -> "- ${titreDe(r)} : ${quandRV(r, agenda.prochaine(r, if (r.journee) maintenant.toLocalDate().atStartOfDay() else maintenant) ?: r.quand, maintenant.toLocalDate())}" +
                    (r.laRepetition?.let { ", ${it.enTexte(maintenant.toLocalDate())}" } ?: "") +
                    (if (r.lieu.isNotBlank()) ", ${r.lieu}" else "") + (if (r.avec.isNotEmpty()) ", avec ${r.avec.joinToString(", ")}" else "") }
                out.write(buildJsonObject {
                    put("type", t.type); put("intention", t.description); put("systeme", ctx.systeme); put("cible", t.cible)
                    put("agenda", enClair); put("maintenant", maintenant.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, H 'h' mm", fr)))
                    put("indices", JsonArray(t.indices.map { JsonPrimitive(it) }))
                }.toString() + "\n")
                gardes++
                parType[t.type] = (parType[t.type] ?: 0) + 1
            }
        }
        println("Fabrique : $gardes exemples gardés, $rejets réponses rejetées par la vérification. $parType")
    }
}
