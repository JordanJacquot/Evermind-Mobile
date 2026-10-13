package io.github.jordanjacquot.evermind.assistant

import io.github.jordanjacquot.evermind.comprendre.Manque
import io.github.jordanjacquot.evermind.comprendre.Phrase
import io.github.jordanjacquot.evermind.comprendre.Repetition
import io.github.jordanjacquot.evermind.comprendre.RxJour
import io.github.jordanjacquot.evermind.comprendre.RxMois
import io.github.jordanjacquot.evermind.comprendre.contient
import io.github.jordanjacquot.evermind.comprendre.lirePeriode
import io.github.jordanjacquot.evermind.comprendre.lirePhrase
import io.github.jordanjacquot.evermind.comprendre.message
import io.github.jordanjacquot.evermind.comprendre.remplace
import io.github.jordanjacquot.evermind.comprendre.titreAffiche
import io.github.jordanjacquot.evermind.comprendre.trouve
import io.github.jordanjacquot.evermind.donnees.APreparer
import io.github.jordanjacquot.evermind.donnees.RendezVous
import io.github.jordanjacquot.evermind.donnees.Sorte
import io.github.jordanjacquot.evermind.donnees.laRepetition
import io.github.jordanjacquot.evermind.ui.enTexte
import io.github.jordanjacquot.evermind.ui.majuscule
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

// Faire ce que l'IA propose, après vérification (Invoke-ActionsIA sur PC). Les dates passent toujours par la lecture
// d'Evermind, jamais par un calcul de l'IA ; un rendez-vous à changer doit exister ; avant chaque changement, l'agenda
// est mis de côté pour « annule ».

/** Ce qu'Evermind a fait : ce qu'il dit, ce qu'il écrit, et la phrase à compléter s'il manquait quelque chose. */
class Resultat(val dit: String, val ecrit: String, val aCorriger: String?, val change: Boolean)

private val JourLong = DateTimeFormatter.ofPattern("EEEE d MMMM", Fr)

/** « 15 h 30 » dit « 15 heures 30 » (Format-QuandParle). */
private fun parle(t: String) = t.remplace("(\\d+) h (\\d+)", "$1 heures $2").remplace("(\\d+) h$", "$1 heures")

class Actions(
    private val agenda: AgendaIA,
    /** Les rappels d'un rendez-vous qui n'en a pas de propres (ceux par défaut des réglages). */
    private val rappelsParDefaut: (RendezVous) -> List<String>,
    private val annulations: MutableList<List<RendezVous>>,
) {
    private val maintenant get() = agenda.maintenant
    private val today get() = agenda.aujourdHui

    private fun sauverAnnulation() {
        annulations += agenda.liste.toList()
        while (annulations.size > 20) annulations.removeAt(0)
    }

    private fun annuler(): Boolean {
        if (annulations.isEmpty()) return false
        val avant = annulations.removeAt(annulations.size - 1)
        agenda.liste.clear()
        agenda.liste += avant
        return true
    }

    /** « Dentiste, mardi 14 octobre à 15 h 30 » : l'écrit, et le dit (Get-PhraseNote). */
    private fun note(r: RendezVous): Pair<String, String> {
        val rep = r.laRepetition?.enTexte(today)
        if (r.journee) { val q = rep ?: quandRV(r, r.quand, today); return "${r.titre}, $q" to "${r.titre}, $q" }
        if (rep != null) return "${r.titre}, $rep à ${heurePc(r.quand)}" to "${r.titre}, $rep à ${parle(heurePc(r.quand))}"
        val prochain = agenda.prochaine(r, maintenant) ?: r.quand
        return "${r.titre}, ${quandRV(r, prochain, today)}" to "${r.titre}, ${parle(quandRV(r, prochain, today))}"
    }

    fun appliquer(rep: ReponseIA, codes: Map<String, String>, texte: String): Resultat {
        val dits = mutableListOf<String>()
        val ecrits = mutableListOf<String>()
        var aCorriger: String? = null
        var cree: RendezVous? = null
        var actions = rep.actions
        // Un même rendez-vous supprimé puis modifié dans la même réponse : c'est un changement
        val modifies = actions.filter { it.action == "modifier" }.mapNotNull { it.code() }
        if (modifies.isNotEmpty()) actions = actions.filter { !(it.action == "supprimer" && it.code() in modifies) }
        // « supprime mes rendez-vous de la semaine prochaine » : Evermind trouve lui-même tout ce qui tombe dans la période
        if (actions.none { it.sure }) suppressionsPeriode(texte, agenda)?.let { actions = it }
        val change = actions.any { it.action != "annuler" }
        if (change) sauverAnnulation()
        val inn = intentions(texte)
        for (a in actions) {
            var rv: RendezVous? = a.cible?.let { agenda.get(it.id) } ?: a.code()?.let { codes[it] }?.let { agenda.get(it) }
            // « crée la réunion et ajoute Paul » : une modification qui accompagne une création vise le nouvel événement
            val surCree = cree != null && a.cible == null &&
                ((a.action in listOf("modifier", "ouvrir") && rv == null) || (a.action == "modifier" && a.phrase == null && !inn.modif && !inn.supp))
            if (surCree) rv = cree
            else if (a.cible == null && a.action !in listOf("creer", "annuler", "horaires") &&
                actions.count { it.action !in listOf("creer", "annuler", "horaires") } == 1) rv = cible(rv, codes, "$texte ${rep.reponse}", agenda)
            val nom = rv?.let { titreDe(it) } ?: ""
            if (a.action in listOf("creer", "modifier") && a.phrase != null) reparer(a)
            when {
                a.action == "creer" -> {
                    val r = creer(a, actions.size == 1, texte, dits, ecrits) { aCorriger = it }
                    if (r != null) cree = r
                }
                a.action == "horaires" -> {
                    val faits = mutableListOf<Pair<Poste, RendezVous>>()
                    for (h in a.postes.orEmpty()) {
                        val p = lirePhrase(h.phrase, maintenant)
                        val q = p.quand
                        if (p.manque != Manque.RIEN || q == null) continue
                        if (agenda.entre(q, q.plusMinutes(1)).any { it.rv.titre == h.titre }) continue
                        val r = details(AgendaIA.nouveau(h.titre, q, p.repetition, Sorte.TRAVAIL, false, maintenant), p).copy(fin = p.fin)
                        faits += h to agenda.ajouter(r)
                    }
                    val (e, d) = texteHoraires(faits, today)
                    dits += d; ecrits += e
                }
                a.action == "annuler" -> {
                    if (annuler()) { dits += "J'ai annulé ma dernière action."; ecrits += "Dernière action annulée." }
                    else { dits += "Il n'y a rien à annuler."; ecrits += "Rien à annuler." }
                }
                a.action == "ouvrir" -> {
                    dits += "Sur le téléphone, je ne peux pas ouvrir de dossiers."; ecrits += "Pas de dossiers à ouvrir sur le téléphone."
                }
                rv == null -> { dits += "Je n'ai pas trouvé de quel rendez-vous tu parles."; ecrits += "Rendez-vous introuvable." }
                a.action == "supprimer" && !a.sure && modifsTexte(texte, rv, agenda).retirer.isNotEmpty() -> {
                    val gens = modifsTexte(texte, rv, agenda).retirer
                    modifier(rv, ActionIA("modifier", retirerPersonnes = gens))
                    dits += "J'ai retiré ${gens.joinToString(" et ")} de $nom."; ecrits += "Retiré de $nom : ${gens.joinToString(", ")}."
                }
                a.action == "supprimer" -> {
                    val p = a.periode
                    if (p != null) { val (d, e) = supprimerPeriode(rv, p); dits += d; ecrits += e }
                    else {
                        agenda.supprimer(rv.id)
                        val tout = if (rv.repetition != null) " (toutes les fois)" else ""
                        dits += "J'ai supprimé $nom$tout."; ecrits += "Supprimé : $nom$tout. (Dis « annule » pour revenir en arrière.)"
                    }
                }
                a.action == "fait" -> {
                    agenda.remplacer(rv.copy(fait = true))
                    dits += "Bien joué, $nom c'est fait."; ecrits += "Fait : $nom."
                }
                a.action == "modifier" -> {
                    // « même heure » : seule la date change, quelle que soit l'heure que l'IA a cru lire
                    if (a.phrase != null && !a.sure && texte.contient("\\bm[êe]me (heure|horaire)")) {
                        a.phrase = a.phrase!!.remplace("\\s*(?:(?:de|à|a)\\s+)?\\d{1,2}\\s*h(?:\\s*\\d{2})?(?:\\s*(?:à|a|-)\\s*\\d{1,2}\\s*h(?:\\s*\\d{2})?)?", "").trim()
                    }
                    // Ce qu'Evermind lit lui-même dans la phrase, plus sûr que l'IA pour ces cas-là
                    val lu = if (surCree || a.sure) Modifs() else modifsTexte(texte, rv, agenda)
                    val arret = lu.arret
                    if (arret != null) {
                        // « le raid, c'est fini après novembre » : la série s'arrête (les fois d'après sont enlevées)
                        val (d, e) = supprimerPeriode(rv, arret); dits += d; ecrits += e
                    } else {
                        when {
                            lu.etendre != null -> a.phrase = lu.etendre
                            lu.decalage != null -> a.phrase = lu.decalage
                            lu.jour != null && !(a.phrase ?: "").contient("\\b" + lu.jour!!.split(' ')[0]) -> a.phrase = lu.jour
                        }
                        if (lu.retirer.isNotEmpty()) { a.retirerPersonnes = (a.retirerPersonnes + lu.retirer).distinct(); if ((a.notes?.length ?: 99) < 40) a.notes = null }
                        if (lu.rappels.isNotEmpty()) { a.rappels = (a.rappels + lu.rappels).distinct(); if (a.notes?.contient("pr[ée]ven|rappel") == true) a.notes = null }
                        lu.titre?.let { t -> a.titre = t; if (a.phrase?.trim() == t) a.phrase = null }
                        if (lu.personnes.isNotEmpty()) {
                            a.personnes = (a.personnes + lu.personnes).distinct()
                            // « Paul est invité » dans les notes : c'était juste la personne
                            val n = a.notes
                            if (n != null && n.length < 40 && lu.personnes.any { n.contains(it) }) a.notes = null
                        }
                        val r = modifier(rv, a)
                        if (!surCree) {
                            val (e, d) = note(r)
                            dits += "C'est changé : $d."; ecrits += "C'est changé : $e."
                        }
                    }
                }
            }
        }
        if (actions.isEmpty()) {
            val t = rep.reponse.ifBlank { "Je n'ai pas compris, tu peux reformuler ?" }
            dits += t; ecrits += t
        }
        return Resultat(dits.distinct().joinToString(" "), ecrits.distinct().joinToString(" "), aCorriger, change)
    }

    /** Les détails compris dans une phrase (Get-DetailsPhrase). */
    private fun details(r: RendezVous, p: Phrase) = r.copy(
        fin = p.fin, lieu = p.lieu, avec = p.avec, lien = p.lien, rappels = p.rappels, important = p.important,
        naissance = p.naissance, aPreparer = p.aPreparer.map { APreparer(it) },
    )

    private fun creer(a: ActionIA, seuleAction: Boolean, texte: String, dits: MutableList<String>, ecrits: MutableList<String>, corriger: (String) -> Unit): RendezVous? {
        var p = lirePhrase(a.phrase ?: "", maintenant)
        // Une simple création : si la phrase de l'utilisateur, lue par les règles d'Evermind, dit plus que celle de
        // l'IA (répétition, plusieurs jours, autre jour ou heure), c'est elle qui compte
        val inn = intentions(texte)
        val seule = seuleAction && inn.creer && !inn.supp && !inn.modif
        var pu: Phrase? = null
        if (seule) {
            val lu = lirePhrase(texte, maintenant)
            pu = lu
            if (lu.manque == Manque.RIEN && lu.titre.isNotEmpty() && (phrasePlusRiche(lu, p) || !titreDit(p.titre, texte))) {
                // Si le titre lu a gardé plusieurs phrases ou s'allonge beaucoup, celui de l'IA (court et propre) reste
                p = if (p.manque == Manque.RIEN && p.titre.isNotEmpty() && titreDit(p.titre, texte) &&
                    (lu.titre.contient("[.!?;]\\s") || lu.titre.length > p.titre.length + 20)) lu.copy(titre = p.titre, avec = p.avec) else lu
            }
        }
        if (p.manque != Manque.RIEN) {
            corriger(a.phrase ?: texte)
            dits += p.manque.message; ecrits += p.manque.message
            return null
        }
        var lieu = p.lieu
        var avec = p.avec
        var notes = ""
        var aPreparer = p.aPreparer
        var important = p.important
        var rappels = p.rappels
        a.lieu?.let { lieu = it }
        if (a.personnes.isNotEmpty()) avec = (avec + a.personnes).filter { it.isNotBlank() }.distinct()
        a.notes?.let { notes = it }
        if (a.chosesAPrendre.isNotEmpty()) aPreparer = (aPreparer + a.chosesAPrendre.filter { it.isNotBlank() }.map { majuscule(it) }).distinct()
        if (a.important == true) important = true
        if (seule && pu != null && pu.manque == Manque.RIEN) {
            // Les détails dits par l'utilisateur comptent même si l'IA les a oubliés
            if (pu.important) important = true
            if (pu.rappels != null && rappels == null) rappels = pu.rappels
            if (pu.lieu.isNotBlank() && lieu.isBlank()) lieu = pu.lieu
            if (pu.avec.isNotEmpty()) avec = (avec + pu.avec).filter { it.isNotBlank() }.distinct()
            if (pu.aPreparer.isNotEmpty() && aPreparer.isEmpty()) aPreparer = pu.aPreparer
        }
        val titre = p.titre.ifEmpty { a.titre ?: "" }
        val q = p.quand!!
        // Déjà là : même nom, même moment et mêmes personnes
        val avecDit = avec.flatMap { motsNom(it) }.sorted().joinToString(" ")
        val deja = agenda.entre(q.toLocalDate().atStartOfDay(), q.toLocalDate().plusDays(1).atStartOfDay()).filter {
            it.rv.titre == titreAffiche(titre) && (p.journee || Duration.between(it.prochain, q).abs().toMinutes() < 1) &&
                it.rv.avec.flatMap { n -> motsNom(n) }.sorted().joinToString(" ") == avecDit
        }
        if (deja.isNotEmpty()) {
            dits += "${titreAffiche(titre)} est déjà dans ton agenda."; ecrits += "Déjà dans l'agenda : ${titreAffiche(titre)}."
            return null
        }
        // Les événements à créer : un seul ; un par jour séparé ; un par bloc de jours (Get-CreationsPhrase)
        val creations: List<Pair<LocalDateTime, LocalDateTime?>> = when {
            p.blocs.size > 1 -> p.blocs.map { b -> b.debut.atTime(q.toLocalTime()) to (if (p.journee) b.fin.atStartOfDay() else b.fin.atTime(p.fin!!.toLocalTime())) }
            p.jours.size > 1 -> p.jours.map { j -> j.atTime(q.toLocalTime()) to null }
            else -> listOf(q to p.fin)
        }
        var premier: RendezVous? = null
        for ((quand, fin) in creations) {
            val r = AgendaIA.nouveau(titreAffiche(titre), quand, p.repetition, io.github.jordanjacquot.evermind.donnees.Sorte.depuisCode(p.sorte), p.journee, maintenant).copy(
                fin = fin, lieu = lieu, avec = avec, lien = p.lien, notes = notes, aPreparer = aPreparer.map { APreparer(it) },
                rappels = rappels, important = important, naissance = p.naissance,
            )
            agenda.ajouter(r)
            if (premier == null) premier = r
        }
        val r = premier!!
        val t = titreAffiche(titre)
        if (p.blocs.size > 1) {
            val liste = creations.map { (d, f) -> "du %02d au %s".format(d.dayOfMonth, f!!.format(DateTimeFormatter.ofPattern("d MMMM", Fr))) }
            val txt = liste.dropLast(1).joinToString(", ") + " et " + liste.last()
            ecrits += "C'est noté : $t, $txt (${creations.size} événements)."; dits += "C'est noté : $t, $txt."
            return r
        }
        if (creations.size > 1) {
            val liste = creations.map { it.first.format(JourNum) }
            val jours = liste.dropLast(1).joinToString(", ") + " et " + liste.last() + " " + creations.last().first.format(DateTimeFormatter.ofPattern("MMMM", Fr))
            ecrits += "C'est noté : $t, $jours (${creations.size} événements)."; dits += "C'est noté : $t, $jours."
            return r
        }
        val (e, d) = note(r)
        ecrits += "C'est noté : $e."
        var dit = "C'est noté : $d."
        val conf = if (!r.journee) agenda.conflits(r.quand, r.fin, r.id) else emptyList()
        if (conf.isNotEmpty()) { dit += " Attention, tu as déjà ${titreDe(conf[0].rv)} à ce moment-là."; ecrits += "⚠ En même temps que ${titreDe(conf[0].rv)}." }
        // Un mot de date resté dans le titre : une partie de la phrase n'a pas été comprise, on le dit
        val reste = trouve(" ${r.titre} ", "(?:\\b(?:et|de|d'|du|en|à)\\s+)*\\b(?:$RxMois|$RxJour)s?\\b(?:\\s+\\d{4})?|\\b(?:19|20)\\d{2}\\b")
        if (reste.ok && !titre.contient("^anniversaire")) {
            dit += " Par contre, je n'ai pas compris « ${reste.valeur.trim()} » : vérifie l'événement."
            ecrits += "⚠ Pas compris : « ${reste.valeur.trim()} ». Vérifie l'événement."
        }
        dits += dit
        return r
    }

    /** Modifier : nouveau moment (lu par les règles d'Evermind), titre, lieu, personnes, notes, choses à prendre. */
    fun modifier(rv: RendezVous, a: ActionIA): RendezVous {
        var quand = rv.quand
        var rep = rv.laRepetition
        var journee = rv.journee
        val duree = rv.fin?.let { Duration.between(rv.quand, it) }
        var fin = rv.fin
        val ph = a.phrase
        if (ph != null) {
            val p = lirePhrase(ph, maintenant)
            val base = (agenda.prochaine(rv, today.atStartOfDay()) ?: rv.quand).toLocalDate()
            var nouveau: LocalDateTime? = null
            // Juste un jour (« jeudi ») pour un rendez-vous qui a une heure : il garde son heure (sauf pour le mettre
            // sur plusieurs jours : « du 27 au 29 octobre »)
            val pq = p.quand
            val plusieurs = p.fin != null && pq != null && p.fin.toLocalDate().isAfter(pq.toLocalDate())
            val jourSeul = p.journee && !journee && p.heure == null && !ph.contient("journée") && !plusieurs
            when {
                pq != null && !jourSeul -> { nouveau = pq; journee = p.journee }
                p.jour != null -> nouveau = p.jour.atTime(rv.quand.toLocalTime())
                p.heure != null -> { nouveau = base.atTime(p.heure); journee = false }
                p.repetition != null -> nouveau = io.github.jordanjacquot.evermind.comprendre.prochaineFois(p.repetition, today.atTime(rv.quand.toLocalTime()), today.atStartOfDay()) ?: rv.quand
            }
            if (nouveau != null) {
                quand = nouveau
                if (p.repetition != null) rep = p.repetition
                else if (rep != null) {
                    // Un rendez-vous qui revient suit son nouveau jour (et garde sa date de fin et son rythme)
                    val ancien = rep
                    rep = when (rep.type) {
                        "semaine" -> Repetition("semaine", jour = quand.toLocalDate().dayOfWeek.value % 7)
                        "mois" -> Repetition("mois", jour = quand.dayOfMonth)
                        "an" -> Repetition("an", jour = quand.dayOfMonth, mois = quand.monthValue)
                        else -> rep
                    }
                    if (rep !== ancien) { rep.jusqua = ancien.jusqua; rep.intervalle = ancien.intervalle }
                }
                fin = p.fin ?: if (duree != null && !journee) quand.plus(duree) else null
            }
        }
        var r = rv.copy(quand = quand, repetition = rep?.versJson(), journee = journee, fin = fin)
        a.lieu?.let { r = r.copy(lieu = it) }
        if (a.personnes.isNotEmpty()) r = r.copy(avec = (rv.avec + a.personnes).filter { it.isNotBlank() }.distinct())
        a.notes?.let { r = r.copy(notes = listOf(rv.notes, it).filter { n -> n.isNotBlank() }.joinToString("\n")) }
        if (a.chosesAPrendre.isNotEmpty()) {
            val deja = rv.aPreparer.map { it.texte }
            r = r.copy(aPreparer = rv.aPreparer + a.chosesAPrendre.filter { it.isNotBlank() && it !in deja }.map { APreparer(majuscule(it)) })
        }
        a.important?.let { r = r.copy(important = it) }
        if (a.rappels.isNotEmpty()) {
            val codes = a.rappels.flatMap { if (it.contient("^(m\\d+|veille|matin)$")) listOf(it) else rappelsTexte(it) }
            if (codes.isNotEmpty()) r = r.copy(rappels = (rappelsParDefaut(rv) + codes).distinct())
        }
        if (a.retirerPersonnes.isNotEmpty()) r = r.copy(avec = r.avec.filter { x -> a.retirerPersonnes.none { it.equals(x, ignoreCase = true) } })
        val titre = a.titre?.takeIf { it != rv.titre } ?: rv.titre
        r = r.copy(titre = titreAffiche(titre).trim())
        return agenda.remplacer(r)
    }

    /**
     * Supprimer seulement certaines fois d'un rendez-vous qui revient (Remove-PeriodeRV) : plus rien ne reste, il est
     * supprimé ; les premières fois, il recommence plus tard ; les dernières, il s'arrête plus tôt ; au milieu, ces
     * jours-là sont sautés. Retourne ce qui est dit et écrit.
     */
    fun supprimerPeriode(rv: RendezVous, periode: String): Pair<String, String> {
        val nom = titreDe(rv)
        var per = lirePeriode(periode, maintenant)
        if (per == null && periode.contient("\\b(?:tous|toutes|tout|toujours|chaque|enti[èe]re?)\\b")) per = io.github.jordanjacquot.evermind.comprendre.Periode(null, null)
        if (per == null) return "Je n'ai pas compris quelles dates enlever pour $nom, je n'ai rien touché." to "Rien supprimé : je n'ai pas compris « $periode »."
        val d0 = per.debut
        val f0 = per.fin
        val js = per.jours.orEmpty()
        fun dans(j: LocalDate) = if (js.isNotEmpty()) j in js else (d0 == null || !j.isBefore(d0)) && (f0 == null || !j.isAfter(f0))
        val annule = " (Dis « annule » pour revenir en arrière.)"
        val rep = rv.laRepetition
        if (rep == null) {
            if (dans(rv.quand.toLocalDate())) { agenda.supprimer(rv.id); return "J'ai supprimé $nom." to "Supprimé : $nom.$annule" }
            return "$nom n'est pas à ces dates-là, je n'ai rien supprimé." to "Rien supprimé : $nom n'est pas à ces dates-là."
        }
        // Toutes les fois de la série (jusqu'à sa fin, ou sur 3 ans si elle ne s'arrête jamais)
        val occ = mutableListOf<LocalDateTime>()
        val limite = rv.quand.plusYears(3)
        var depuis = rv.quand
        for (i in 0 until 1200) {
            val q = io.github.jordanjacquot.evermind.comprendre.prochaineFois(rep, rv.quand, depuis) ?: break
            if (q.isBefore(depuis) || q.isAfter(limite)) break
            occ += q
            depuis = q.plusMinutes(1)
        }
        val fini = rep.jusqua != null
        val enlevees = occ.filter { dans(it.toLocalDate()) }
        if (enlevees.isEmpty()) return "Il n'y a pas de $nom à ces dates-là, je n'ai rien supprimé." to "Rien supprimé : pas de $nom à ces dates-là."
        val avant = occ.filter { d0 != null && it.toLocalDate().isBefore(d0) }
        val apres = occ.filter { f0 != null && it.toLocalDate().isAfter(f0) }
        val nouvelle = rep.copy()
        var quand = rv.quand
        val n = if (enlevees.size == 1) "le ${enlevees[0].format(JourLong)}" else "${enlevees.size} fois"
        val dit: String
        if (avant.isEmpty() && apres.isEmpty() && (fini || f0 == null)) {
            agenda.supprimer(rv.id)
            return "J'ai supprimé $nom (toutes les fois)." to "Supprimé : $nom, toutes les fois.$annule"
        }
        if (avant.isEmpty()) {
            quand = apres[0]
            dit = "J'ai enlevé $nom $n. Il reprend le ${quand.format(JourLong)}."
        } else if (apres.isEmpty() && (fini || f0 == null)) {
            nouvelle.jusqua = d0!!.minusDays(1).toString()
            dit = if (f0 != null) "J'ai enlevé $nom $n. Le dernier est le ${avant.last().format(JourLong)}."
                  else "C'est noté : $nom s'arrête. Le dernier est le ${avant.last().format(JourLong)}."
        } else {
            nouvelle.sauf = (nouvelle.sauf.orEmpty() + enlevees.map { it.toLocalDate().toString() }).distinct().takeLast(300)
            dit = "J'ai enlevé $nom $n. Les autres fois restent."
        }
        val fin = rv.fin?.let { quand.plus(Duration.between(rv.quand, it)) }
        agenda.remplacer(rv.copy(quand = quand, repetition = nouvelle.versJson(), fin = fin))
        return dit to "$dit$annule"
    }
}
