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
        Modele("Train pour Paris", Sorte.VOYAGE, LocalTime.of(7, 45)), Modele("Cours de guitare", Sorte.TRAVAIL, LocalTime.of(17, 0), "semaine"),
    )
    private val nouveaux = listOf("Dîner chez Paul" to Sorte.SORTIE, "Ostéo" to Sorte.SANTE, "Pédiatre" to Sorte.SANTE, "Réunion de chantier" to Sorte.TRAVAIL,
        "Badminton" to Sorte.SPORT, "Concert" to Sorte.SORTIE, "Visite chez mamie" to Sorte.RDV, "Contrôle technique" to Sorte.RDV, "Bowling" to Sorte.SORTIE,
        "Footing" to Sorte.SPORT, "Atelier poterie" to Sorte.SORTIE, "Rendez-vous à la banque" to Sorte.RDV, "Vétérinaire" to Sorte.SANTE, "Brunch" to Sorte.SORTIE)
    private val gens = listOf("Max", "Inès", "Tom", "Julie", "Paul", "Hugo", "Sarah", "Lucas", "Chloé", "Maman", "Marc", "Emma")
    private val lieux = listOf("12 rue des Lilas, Lyon", "stade de Woippy", "Metz", "Nancy", "Thionville", "salle B", "chez Max", "gymnase", "3 avenue Foch, Metz")
    private val choses = listOf("Carte Vitale", "Ordonnance", "Radios", "Crampons", "Maillot", "Lait", "Pain", "Œufs", "Chips", "Gâteau", "Dossier", "Chargeur")

    private fun code(r: RendezVous) = "e" + r.id.replace("-", "").take(5)
    private fun h(t: LocalTime) = if (t.minute == 0) "${t.hour} h" else "${t.hour} h %02d".format(t.minute)
    private fun cle(titre: String) = motsTitre(titre).firstOrNull() ?: sansAccents(titre).split(' ').first()
    private fun prochain(auj: LocalDate, j: DayOfWeek): LocalDate { var e = (j.value - auj.dayOfWeek.value + 7) % 7; if (e == 0) e = 7; return auj.plusDays(e.toLong()) }

    private fun agendaAuHasard(rnd: Random, n: LocalDateTime): List<RendezVous> {
        val auj = n.toLocalDate()
        return modeles.shuffled(rnd).take(5 + rnd.nextInt(5)).map { m ->
            val d = auj.plusDays((1 + rnd.nextInt(12)).toLong())
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
            "q_quand", "q_ou", "q_qui", "q_jour", "q_prendre", "n_rien", "d_double")
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
                    listOf(cle(r.titre))) { a, _ -> memeHeure(a, r, t.toLocalDate(), t.toLocalTime()) }
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
                    listOf(cle(r.titre))) { a, _ -> a.first { it.id == r.id }.important }
            }
            "m_rappel" -> {
                val r = choisir(tous) ?: return null
                val (dit, code) = listOf("la veille" to "veille", "une heure avant" to "m60", "deux jours avant" to "m2880", "le matin même" to "matin", "30 minutes avant" to "m30").random(rnd)
                if (r.journee && code.startsWith("m") && code != "matin") return null
                return Tache(type, "demander un rappel $dit pour ${nom(r)}", json(listOf(mapOf("action" to "modifier", "id" to code(r), "rappels" to listOf(dit)))),
                    listOf(cle(r.titre))) { a, _ -> a.first { it.id == r.id }.rappels?.contains(code) == true }
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
                    listOf(cle(r.titre))) { a, _ -> a.first { it.id == r.id }.let { x -> x.quand == r.quand && x.fin == f } }
            }
            "s_un" -> {
                val r = choisir(ag) ?: return null
                return Tache(type, "supprimer ${nom(r)}${if (r.repetition != null) " (toutes les fois)" else ""}", json(listOf(mapOf("action" to "supprimer", "id" to code(r)))),
                    listOf(cle(r.titre))) { a, _ -> a.none { it.id == r.id } && a.size == ag.size - 1 }
            }
            "s_fois" -> {
                val r = choisir(series) ?: return null
                val f = fois(r).toLocalDate()
                return Tache(type, "supprimer seulement ${nom(r)} du ${f.format(fJour)} (les autres semaines restent)",
                    json(listOf(mapOf("action" to "supprimer", "id" to code(r), "periode" to "le ${f.format(fDate)}"))), listOf(cle(r.titre))) { a, _ ->
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
                    listOf(cle(r.titre), sansAccents(nm))) { a, _ ->
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
                val (t, _) = listOf("Payer la cantine" to 1, "Payer l'électricité" to 1, "Virement épargne" to 1, "Payer la nounou" to 1).random(rnd)
                val dj = 1 + rnd.nextInt(28)
                return Tache(type, "créer un rappel « $t » le $dj de chaque mois", json(listOf(mapOf("action" to "creer", "phrase" to "$t le $dj de chaque mois"))),
                    listOf(sansAccents(t).split(' ').last(), "$dj")) { a, _ -> a.filter { x -> ag.none { it.id == x.id } }.singleOrNull()?.laRepetition?.let { it.type == "mois" && it.jour == dj } == true }
            }
            "c_anniv" -> {
                val p = listOf("Thomas", "Nathalie", "Kevin", "Camille", "Jules", "Manon").random(rnd)
                val d = auj.plusDays((5 + rnd.nextInt(300)).toLong())
                val age = 5 + rnd.nextInt(70)
                return Tache(type, "créer l'anniversaire de $p le ${d.dayOfMonth} ${mois[d.monthValue - 1]}, qui aura $age ans",
                    json(listOf(mapOf("action" to "creer", "phrase" to "anniversaire de $p le ${d.dayOfMonth} ${mois[d.monthValue - 1]}, il aura $age ans"))),
                    listOf(sansAccents(p), "$age")) { a, _ -> a.filter { x -> ag.none { it.id == x.id } }.singleOrNull()?.let { it.sorte == Sorte.ANNIV && it.naissance == d.year - age } == true }
            }
            "c_plusieurs" -> {
                val d1 = auj.plusDays((5 + rnd.nextInt(60)).toLong())
                val d2 = d1.plusDays((1 + rnd.nextInt(6)).toLong())
                val t = listOf("Vacances", "Séminaire", "Salon", "Week-end à Annecy", "Formation").random(rnd)
                val dit = if (d1.monthValue == d2.monthValue) "du ${d1.dayOfMonth} au ${d2.dayOfMonth} ${mois[d2.monthValue - 1]}" else "du ${d1.dayOfMonth} ${mois[d1.monthValue - 1]} au ${d2.dayOfMonth} ${mois[d2.monthValue - 1]}"
                return Tache(type, "créer « $t » $dit", json(listOf(mapOf("action" to "creer", "phrase" to "$t $dit"))), listOf(sansAccents(t).split(' ').first(), "${d2.dayOfMonth}")) { a, _ ->
                    a.filter { x -> ag.none { it.id == x.id } }.singleOrNull()?.let { it.quand.toLocalDate() == d1 && it.fin?.toLocalDate() == d2 } == true
                }
            }
            "q_quand" -> {
                val r = choisir(tous) ?: return null
                return Tache(type, "demander quand a lieu « ${titreDe(r)} »", json(emptyList(), "${titreDe(r)} : ${quand(r)}."), listOf(cle(r.titre))) { a, res -> a == ag && res.dit.contains(titreDe(r)) }
            }
            "q_ou" -> {
                val r = choisir(tous.filter { it.lieu.isNotBlank() }) ?: return null
                return Tache(type, "demander où a lieu « ${titreDe(r)} »", json(emptyList(), "${titreDe(r)} : ${r.lieu}."), listOf(cle(r.titre))) { a, res -> a == ag && res.dit.contains(r.lieu) }
            }
            "q_qui" -> {
                val r = choisir(tous.filter { it.avec.isNotEmpty() }) ?: return null
                return Tache(type, "demander qui vient à « ${titreDe(r)} »", json(emptyList(), "${titreDe(r)}, avec ${r.avec.joinToString(", ")}."), listOf(cle(r.titre))) { a, res -> a == ag && res.dit.contains(r.avec[0]) }
            }
            "q_prendre" -> {
                val r = choisir(tous.filter { it.aPreparer.isNotEmpty() }) ?: return null
                return Tache(type, "demander ce qu'il faut prendre pour « ${titreDe(r)} »", json(emptyList(), "Pour ${titreDe(r)} : ${r.aPreparer.joinToString(", ") { it.texte }}."),
                    listOf(cle(r.titre))) { a, res -> a == ag && res.dit.contains(r.aPreparer[0].texte) }
            }
            "q_jour" -> {
                val (dit, d) = if (rnd.nextBoolean()) "demain" to auj.plusDays(1) else { val j = DayOfWeek.entries.random(rnd); jours[j.value - 1] to prochain(auj, j) }
                val f = agenda.entre(d.atStartOfDay(), d.plusDays(1).atStartOfDay())
                val rep = if (f.isEmpty()) "${dit.replaceFirstChar { it.uppercase() }}, tu n'as rien de prévu." else "${dit.replaceFirstChar { it.uppercase() }} : " +
                    f.joinToString(", ") { x -> if (x.rv.journee) titreDe(x.rv) else "${titreDe(x.rv)} à ${h(x.prochain.toLocalTime())}" } + "."
                return Tache(type, "demander ce qui est prévu $dit", json(emptyList(), rep), listOf(dit)) { a, res -> a == ag && res.dit == rep }
            }
            "n_rien" -> {
                val r = choisir(tous) ?: return null
                val (desc, rep) = listOf(
                    "raconter que « ${titreDe(r)} » s'est bien passé la dernière fois, sans rien demander" to "Tant mieux !",
                    "hésiter à supprimer « ${titreDe(r)} », sans vraiment le demander" to "D'accord, je ne change rien. Dis-le-moi quand tu seras sûr.",
                    "dire qu'on n'a pas envie d'aller à « ${titreDe(r)} », sans rien demander" to "Je comprends. Si tu veux que je change quelque chose, dis-le-moi.",
                ).random(rnd)
                return Tache(type, desc, json(emptyList(), rep), listOf(cle(r.titre))) { a, _ -> a == ag }
            }
            "d_double" -> {
                val r1 = choisir(horaire) ?: return null
                val r2 = choisir(horaire.filter { it.id != r1.id }) ?: return null
                val t = LocalTime.of(8 + rnd.nextInt(13), 0)
                if (t == r2.quand.toLocalTime()) return null
                return Tache(type, "supprimer ${nom(r1)} et mettre ${nom(r2)} à ${h(t)} le même jour",
                    json(listOf(mapOf("action" to "supprimer", "id" to code(r1)), mapOf("action" to "modifier", "id" to code(r2), "phrase" to "le ${r2.quand.format(fDate)} à ${h(t)}"))),
                    listOf(cle(r1.titre), cle(r2.titre), "${t.hour}")) { a, _ -> a.none { it.id == r1.id } && memeHeure(a, r2, r2.quand.toLocalDate(), t) }
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
