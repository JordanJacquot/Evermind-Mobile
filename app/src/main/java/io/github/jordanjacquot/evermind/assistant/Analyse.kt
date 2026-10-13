package io.github.jordanjacquot.evermind.assistant

import io.github.jordanjacquot.evermind.comprendre.Manque
import io.github.jordanjacquot.evermind.comprendre.Phrase
import io.github.jordanjacquot.evermind.comprendre.RxJour
import io.github.jordanjacquot.evermind.comprendre.RxMois
import io.github.jordanjacquot.evermind.comprendre.RxNombre
import io.github.jordanjacquot.evermind.comprendre.contient
import io.github.jordanjacquot.evermind.comprendre.lirePeriode
import io.github.jordanjacquot.evermind.comprendre.lirePhrase
import io.github.jordanjacquot.evermind.comprendre.nombre
import io.github.jordanjacquot.evermind.comprendre.remplace
import io.github.jordanjacquot.evermind.comprendre.tous
import io.github.jordanjacquot.evermind.comprendre.trouve
import io.github.jordanjacquot.evermind.donnees.RendezVous
import io.github.jordanjacquot.evermind.donnees.laRepetition
import java.text.Normalizer
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs

// Ce qu'Evermind lit lui-même dans une demande, sans l'IA, pour la vérifier (cerveau.ps1 sur PC) : les verbes, les
// rendez-vous nommés, ce qu'une modification dit clairement.

/** « Évènement » devient « evenement » : sans majuscules ni accents, pour comparer des mots. */
internal fun sansAccents(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}"), "")

/** Phrases qui ne sont pas un nouveau rendez-vous mais une demande (décaler, supprimer, question...), $RxCommande. */
private const val RxCommande = "^\\s*(?:(?:euh\\s*,?\\s*)?(?:est-ce que tu (?:peux|pourrais)|tu (?:peux|pourrais)|peux-tu|pourrais-tu|(?:j'aimerais|je voudrais|je veux|il faut) que tu)\\s+(?:me\\s+|m')?)?(?:d[ée]cale|d[ée]place|repousse|avance|reporte|change|modifie|renomme|supprime[rsz]?|enl[èe]ve[rsz]?|efface[rsz]?|retire[rsz]?|annule[rsz]?|ajoute\\s+\\S+.*\\s+(?:à|au|aux)\\s+(?:la|le|l'|mon|ma|mes|ce|cette)\\b|j'ai (?:fini|fait|appel|pay|achet)|c'est (?:fait|bon)|qu'est-ce|quand|est-ce que j'ai|combien|quels?|quelles?|dis-moi|montre|liste|rappelle-moi (?:quand|ce que))|\\?\\s*$"

fun estCommande(texte: String) = texte.replace('’', '\'').contient(RxCommande)

/** Ce que la phrase demande, d'après ses verbes (« pour retirer de l'argent » est ce qu'il y a à faire, pas un ordre). */
class Intentions(val supp: Boolean, val modif: Boolean, val creer: Boolean, val question: Boolean)

fun intentions(texte: String): Intentions {
    val t = " " + texte.lowercase().replace('’', '\'') + " "
    val pas = "(?<!\\b(?:pour|de|à|a)\\s)(?<!\\bd')"
    val question = (t.contient("\\?\\s*$") || t.contient("^\\s*(?:qu'est-ce|c'est quand|quand|o[ùu]|combien|quel(?:le)?s?|est-ce que j'ai|est-ce que je|ai-je|suis-je|j'ai quoi|dis-moi|rappelle-moi (?:quand|ce que|o[ùu]))\\b")) &&
        !t.contient("\\b(?:peux|pourrais|veux)[- ]tu\\b")
    return Intentions(
        supp = t.contient("$pas\\b(supprim|suprim|sucrim|effac|enl[èe]v|retir|vir[ée]|d[ée]gag)\\w*") ||
            t.contient("\\bannul\\w*\\s+(?:le|la|les|l'|mon|ma|mes|ce|cette|tous|toutes)\\s*(?!derni|ce que|ça|cela)\\w") || t.contient("\\best\\s+annul[ée]"),
        modif = t.contient("$pas\\b(d[ée]cal|d[ée]cad|d[ée]plac|repouss|recul|retard|avance|report|chang|modifi|renomm)\\w*"),
        creer = t.contient("\\b(cr[ée]{1,2}[rsz]?\\b|cr[ée]e-|ajout|rajout|mets|met\\b|mettre|mett(?:e|es|ez|ions)\\b|note[rsz]?\\b|programm|planifi|rappel|oubli|pense|fai[st][- ]moi|fai[st]\\s+(?:un|une)\\s+(?:nouvel(?:le)?\\s+)?(?:[ée]v[èée]nement|rendez-vous|rdv|rappel))"),
        question = question,
    )
}

/** Les mots qui reconnaissent un rendez-vous (« Raid avec les potes » : raid, potes). */
fun motsTitre(titre: String): List<String> =
    tous(sansAccents(titre), "[a-z]{4,}", ci = false).map { it.valeur }
        .filter { it !in setOf("anniversaire", "rendez", "vous", "avec", "chez", "faire", "pour", "tous", "evenement") }

private val Civilites = setOf("monsieur", "madame", "mademoiselle", "mr", "mme", "mlle", "m", "mr.", "dr", "docteur", "maitre", "me",
    "le", "la", "les", "de", "du", "des", "chez", "dossier", "client", "societe", "ste", "sarl", "sas")

fun motsNom(nom: String): List<String> =
    tous(sansAccents(nom), "[a-z0-9]+", ci = false).map { it.valeur }.filter { it !in Civilites && it.length >= 2 }

/** Le titre vient-il des mots de l'utilisateur ? (l'IA a déjà écrit « foot » pour « sport ») */
fun titreDit(titre: String, texte: String): Boolean {
    val t = " " + sansAccents(texte) + " "
    val mots = tous(" " + sansAccents(titre) + " ", "[a-z]{3,}", ci = false).map { it.valeur }
        .filter { it !in setOf("anniversaire", "rendez", "vous", "avec", "chez", "des", "les", "une", "pour") }
    return mots.isEmpty() || mots.all { t.contient("\\b$it") }
}

/** Ce que contient un nom, pour le chercher dans une phrase sans majuscules ni accents. */
private fun nomme(r: RendezVous, d: String) = motsTitre(r.titre).any { d.contient("\\b$it") }

/**
 * La phrase désigne-t-elle un rendez-vous existant avec un article défini (« le foot », « mon dentiste », « à la
 * soirée ») ? Sans actions : n'importe quel rendez-vous de l'agenda envoyé à l'IA.
 */
fun designeExistant(texte: String, actions: List<ActionIA>, codes: Map<String, String>, agenda: AgendaIA): Boolean {
    val t = " " + sansAccents(texte) + " "
    val ids = if (actions.isNotEmpty()) actions.mapNotNull { it.code() }.mapNotNull { codes[it] } else codes.values.toList()
    for (id in ids) {
        val rv = agenda.get(id) ?: continue
        for (w in motsTitre(rv.titre)) {
            if (t.contient("(?:\\b(?:le|la|les|mon|ma|mes|ce|cette|ton|ta|du|au|aux|des)\\s+|\\bl')(?:\\w+\\s+){0,2}$w")) return true
        }
    }
    return false
}

/** Rappels dits dans une phrase : « la veille », « 2 heures avant », « le matin même », « à l'heure ». */
fun rappelsTexte(texte: String): List<String> =
    lirePhrase("x le 1/1/2099 à 12 h $texte", LocalDateTime.of(2026, 1, 1, 0, 0)).rappels.orEmpty()

/** Une phrase comprise par les règles dit-elle plus (ou autre chose) que celle de l'IA ? */
fun phrasePlusRiche(pu: Phrase, p: Phrase): Boolean {
    if (p.manque != Manque.RIEN) return true
    if (pu.repetition != null && p.repetition == null) return true
    if (pu.repetition != null && p.repetition != null && pu.repetition.versJson() != p.repetition.versJson()) return true
    if (pu.fin != null && p.fin == null) return true
    if (pu.jours.size > p.jours.size) return true
    val a = pu.quand
    val b = p.quand
    return a != null && b != null && abs(java.time.Duration.between(a, b).toMinutes()) >= 1
}

/**
 * « Supprime l'événement » sans dire lequel (ni nom, ni date), et rien dans la conversation qui le dise : la question
 * « Lequel ? » avec les noms des prochains rendez-vous ; sinon null.
 */
fun questionVague(texte: String, codes: Map<String, String>, agenda: AgendaIA, conversationRecente: Boolean): String? {
    if (conversationRecente) return null
    val inn = intentions(texte)
    if (!(inn.supp || inn.modif) || inn.creer) return null
    val t = texte.lowercase().replace('’', '\'')
    if (!t.contient("\\b(?:l'|le |la |un |une |cet |ce |cette |mon |ma )\\s*(?:[ée]v[èée]nement|rendez-vous|rdv|truc|rappel)\\b")) return null
    if (codes.isNotEmpty() && designeExistant(texte, emptyList(), codes, agenda)) return null
    val sans = " " + sansAccents(t) + " "
    if (codes.values.mapNotNull { agenda.get(it) }.any { nomme(it, sans) }) return null
    val p = lirePhrase("x $texte", agenda.maintenant)
    if (p.jour != null || p.repetition != null || p.heure != null || texte.contient("\\b(?:$RxJour|$RxMois|demain|aujourd'hui|hier|semaine|\\d{1,2})\\b")) return null
    val noms = agenda.aVenir().take(6).map { titreDe(it.rv) }.distinct()
    if (noms.isEmpty()) return "Ton agenda est vide, il n'y a rien à changer."
    val liste = if (noms.size > 1) noms.dropLast(1).joinToString(", ") + " ou " + noms.last() else noms[0]
    return "Lequel ? $liste ?"
}

/**
 * Garde-fou : le verbe de la demande décide de la sorte d'action (Get-EcartAction). Retourne ce qui ne va pas (pour le
 * dire à l'IA), ou null.
 */
fun ecartAction(texte: String, actions: List<ActionIA>, codes: Map<String, String>, agenda: AgendaIA): String? {
    val inn = intentions(texte)
    val supp = inn.supp
    val modif = inn.modif
    val creer = inn.creer
    val sortes = actions.map { it.action }
    if (inn.question && !supp && !modif && !creer && sortes.any { it != "annuler" }) {
        return "C'est une QUESTION : réponds seulement dans \"reponse\", avec \"actions\" vide. Ne modifie rien."
    }
    if (creer && !supp && !modif && !inn.question) {
        if (sortes.isEmpty()) return "L'utilisateur demande de CRÉER un nouvel événement (il n'a pas besoin d'exister déjà). Utilise l'action \"creer\" avec sa phrase."
        if ("creer" !in sortes && "ouvrir" in sortes && !texte.contient("\\bouvr")) return "L'utilisateur demande de CRÉER un nouvel événement, il n'a pas demandé d'ouvrir quoi que ce soit : utilise \"creer\" avec sa phrase (et \"dossiers\" si un dossier est nommé)."
        if ("creer" !in sortes && "modifier" in sortes && codes.isNotEmpty()) {
            // Modifier n'a de sens que si la phrase parle d'un rendez-vous qui existe (« ajoute Paul à la soirée »)
            val t = " " + sansAccents(texte) + " "
            val cites = actions.filter { it.action == "modifier" }.mapNotNull { it.code()?.let { c -> codes[c] } }.mapNotNull { agenda.get(it) }.filter { nomme(it, t) }
            if (cites.isEmpty()) return "L'utilisateur demande de CRÉER un nouvel événement, pas de modifier un rendez-vous existant. Utilise l'action \"creer\" avec sa phrase."
        }
    }
    if (sortes.isEmpty()) return null
    // « annule le foot » : supprimer le foot ; « annule » tout seul : défaire la dernière action
    if ("annuler" in sortes && supp && codes.isNotEmpty() && designeExistant(texte, emptyList(), codes, agenda)) {
        return "Ici « annule » vise un rendez-vous précis : utilise \"supprimer\" avec son id, pas \"annuler\" (qui défait seulement la dernière action d'Evermind)."
    }
    // Création d'un NOUVEL événement qui porte le nom d'un existant (« ajoute dentiste vendredi »)
    if (creer && !supp && !modif && "modifier" in sortes && "creer" !in sortes && codes.isNotEmpty() && !designeExistant(texte, actions, codes, agenda)) {
        val pu = lirePhrase(texte, agenda.maintenant)
        if (pu.manque == Manque.RIEN && (pu.jour != null || pu.repetition != null)) return "L'utilisateur demande de CRÉER un NOUVEL événement (un autre, même s'il porte le même nom qu'un rendez-vous existant). Utilise \"creer\" avec sa phrase, ne modifie pas l'existant."
    }
    // « rappelle-moi la veille pour la soirée » : un rappel pour un rendez-vous existant, pas un nouvel événement
    if ("creer" in sortes && codes.isNotEmpty() && rappelsTexte(texte).isNotEmpty() && designeExistant(texte, emptyList(), codes, agenda) &&
        lirePhrase(texte, agenda.maintenant).manque != Manque.RIEN) {
        return "L'utilisateur veut être PRÉVENU pour un rendez-vous qui existe déjà : utilise \"modifier\" avec son id et \"rappels\" (par exemple [\"la veille\"]), ne crée rien."
    }
    if (supp && !modif && !creer && "supprimer" !in sortes) {
        return "L'utilisateur demande de SUPPRIMER, mais \"actions\" ne contient aucune action \"supprimer\". Utilise \"supprimer\" avec l'id ; s'il ne parle que de certaines dates d'un rendez-vous qui revient, ajoute \"periode\" avec ses mots. Ne modifie rien."
    }
    if (modif && !supp && !creer && ("supprimer" in sortes || "creer" in sortes)) {
        return "L'utilisateur demande de DÉCALER ou CHANGER un rendez-vous existant : utilise seulement \"modifier\", sans supprimer ni créer."
    }
    if (supp && (creer || modif) && "supprimer" in sortes && "creer" !in sortes && "modifier" !in sortes) {
        return "L'utilisateur demande DEUX choses : supprimer, mais aussi créer ou changer quelque chose. Tu n'as fait que la suppression. Si en fait il veut changer un rendez-vous (par exemple le mettre sur plusieurs jours), utilise seulement \"modifier\" avec la nouvelle \"phrase\" au lieu de le supprimer. Sinon, fais les deux actions."
    }
    if (creer && !supp && "supprimer" in sortes) return "L'utilisateur demande d'AJOUTER ou de CRÉER, pas de supprimer : n'utilise pas \"supprimer\"."
    return null
}

/**
 * L'IA glisse parfois ses champs dans la phrase (« courses samedi','choses_a_prendre(['lait']) ») : la phrase est
 * coupée et les champs récupérés (sans écraser ceux qu'elle a bien remplis), Repair-ActionIA.
 */
fun reparer(a: ActionIA) {
    val ph = a.phrase ?: return
    val m = trouve(ph, "\\s*['\"]\\s*[,;{]\\s*['\"]?[a-z_]+['\"]?\\s*[:=(]|\\s*['\"]?\\s*[,;]?\\s*['\"]?\\b[a-z]+_[a-z_]+['\"]?\\s*[:=(\\[]|\\s*['\"]\\s*[}\\]]|\\s*\\{\\s*['\"]")
    if (!m.ok) return
    val reste = ph.substring(m.index)
    for (c in tous(reste, "['\"]?(?<k>dossiers|ouvrir_au_debut|personnes|choses_a_prendre|lieu|notes|important|rappels|titre)['\"]?\\s*[:=(]\\s*\\(?\\s*(?<v>\\[[^\\]]*\\]|true|false|\"[^\"]*\"|'[^']*')")) {
        val k = c.g("k")!!
        val v = c.g("v")!!
        val liste = if (v.startsWith("[")) tous(v, "['\"](?<s>[^'\"]+)['\"]").map { it.g("s")!! } else null
        val texte = v.trim('\'', '"')
        when (k) {
            "personnes" -> if (a.personnes.isEmpty()) a.personnes = liste ?: listOf(texte)
            "choses_a_prendre" -> if (a.chosesAPrendre.isEmpty()) a.chosesAPrendre = liste ?: listOf(texte)
            "rappels" -> if (a.rappels.isEmpty()) a.rappels = liste ?: listOf(texte)
            "dossiers" -> if (a.dossiers.isEmpty()) a.dossiers = liste ?: listOf(texte)
            "lieu" -> if (a.lieu.isNullOrBlank()) a.lieu = texte
            "notes" -> if (a.notes.isNullOrBlank()) a.notes = texte
            "titre" -> if (a.titre.isNullOrBlank()) a.titre = texte
            "important" -> if (a.important == null) a.important = v == "true"
            "ouvrir_au_debut" -> if (a.ouvrirAuDebut == null) a.ouvrirAuDebut = v == "true"
        }
    }
    a.phrase = ph.substring(0, m.index).remplace("['\"\\[\\]\\(\\)\\{\\},;:\\s]+$", "").trim()
}

/**
 * L'IA se trompe parfois d'identifiant (« décale le dentiste » appliqué au raid). Si la demande et sa réponse parlent
 * d'un seul rendez-vous de l'agenda, et pas de celui qu'elle a désigné, c'est celui-là qui compte (Get-CibleIA).
 */
fun cible(rv: RendezVous?, codes: Map<String, String>, dit: String, agenda: AgendaIA): RendezVous? {
    val d = " " + sansAccents(dit) + " "
    if (rv != null && nomme(rv, d)) return rv
    val autres = codes.values.distinct().mapNotNull { agenda.get(it) }.filter { nomme(it, d) }
    if (rv != null && motsTitre(rv.titre).isEmpty() && autres.size != 1) return rv
    if (autres.size == 1) return autres[0]
    return rv
}

/**
 * « supprime mes rendez-vous de la semaine prochaine », « supprime tout ce que j'ai demain » : Evermind trouve lui-même
 * tout ce qui tombe dans la période (Get-SuppressionsPeriode). null si la phrase ne demande pas ça.
 */
fun suppressionsPeriode(texte: String, agenda: AgendaIA): List<ActionIA>? {
    val m = trouve(texte.trim().replace('’', '\''), "\\b(?:supprim|suprim|efface|enl[èe]v|vide|annul|retir|vire)\\w*\\s+(?:moi\\s+)?(?:(?<tout>tous|toutes|tout|toute)\\s+)?(?:(?:mes|les)\\s+(?<quoi>rendez-vous|rdv|[ée]v[ée]nements?|trucs|activit[ée]s|cr[ée]neaux)|(?<toutb>tout ce que j'ai(?: de pr[ée]vu)?|tout mon agenda|mon agenda))\\s+(?<p>.+?)\\s*[.!?]?$")
    if (!m.ok) return null
    val per = lirePeriode(m.g("p")!!.remplace("^(?:(?:de|du|des|pour|pendant)\\s+|d')", ""), agenda.maintenant)
    if (per?.debut == null || per.fin == null) return null
    val tout = m.a("tout") || m.a("toutb") || (m.g("quoi") ?: "").contient("v[ée]nement|truc|activit")
    val vus = mutableSetOf<String>()
    return agenda.entre(per.debut.atStartOfDay(), per.fin.plusDays(1).atStartOfDay())
        .filter { tout || it.rv.sorte != io.github.jordanjacquot.evermind.donnees.Sorte.ANNIV }
        .filter { vus.add(it.rv.id) }
        .map { ActionIA("supprimer", periode = m.g("p")).apply { cible = it.rv } }
}

/** Ce qu'une demande de modification dit clairement, lu sans l'IA (Get-ModifsTexte). */
class Modifs {
    var decalage: String? = null
    var titre: String? = null
    var personnes = mutableListOf<String>()
    var arret: String? = null
    var retirer = mutableListOf<String>()
    var rappels = listOf<String>()
    var jour: String? = null
    var etendre: String? = null
}

private val FormatJourAn = DateTimeFormatter.ofPattern("d MMMM yyyy", Fr)

fun modifsTexte(texte: String, rv: RendezVous?, agenda: AgendaIA): Modifs {
    val res = Modifs()
    val t = texte.trim().replace('’', '\'')
    val majX = "[A-ZÉÈÊÀÂÎÏÔÛÇ][\\p{L}'-]+"
    // « retire Tom de la soirée » : seulement des gens qui y sont
    for (m in tous(t, "\\b(?:[Rr]etire|[Ee]nl[èe]ve|[Ss]upprime|[Vv]ire)\\s+(?<n>$majX(?:\\s*(?:,|et)\\s*$majX)*)\\s+(?:de|du|des|d')", ci = false)) {
        res.retirer += m.g("n")!!.split(Regex("\\s*(?:,|\\bet\\b)\\s*")).filter { n -> rv != null && rv.avec.any { it.equals(n, ignoreCase = true) } }
    }
    if (t.contient("\\b(?:pr[ée]viens|rappelle|avertis|rappel)")) res.rappels = rappelsTexte(t)
    val base = { rv?.let { agenda.prochaine(it, agenda.maintenant) ?: it.quand } }
    // « mets la soirée sur deux jours », « étale le séminaire sur une semaine » (sans autre date) : à partir de son jour
    if (rv != null && !t.contient("\\b(?:$RxJour|$RxMois|demain|aujourd'hui|apr[èe]s-demain)\\b")) {
        val e = trouve(t, "\\b(?:sur|pendant|durant)\\s+(?<n>$RxNombre|une|un)\\s+(?<u>jours?|semaines?)\\b")
        if (e.ok) {
            var n = nombre(if (e.g("n")!!.contient("^une?$")) "1" else e.g("n")) ?: 0
            if (e.g("u")!!.lowercase().startsWith("s")) n *= 7
            if (n in 2..366) {
                val b = agenda.prochaine(rv, agenda.aujourdHui.atStartOfDay()) ?: rv.quand
                res.etendre = "le ${b.format(FormatJourAn)}" + (if (!rv.journee) " à ${heurePc(b)}" else "") + " sur $n jours"
            }
        }
    }
    // « déplace le foot à la semaine prochaine (même jour, même heure) »
    if (rv != null && t.contient("\\b(?:d[ée]cal|d[ée]plac|repouss|report|mets|passe)\\w*\\b.*?\\b(?:à|a)\\s+la\\s+semaine\\s+(?:prochaine|d'apr[èe]s|suivante)")) {
        val n7 = base()!!.plusDays(7)
        res.decalage = "le ${n7.format(FormatJourAn)}" + (if (!rv.journee) " à ${n7.hour} h %02d".format(n7.minute) else "")
    }
    // Nouveau jour de la semaine (« le raid passe au mardi », « le foot c'est plutôt vendredi »)
    if (rv != null && res.decalage == null && (intentions(t).modif || t.contient("\\b(?:passe|bouge|plut[ôo]t|maintenant)\\b"))) {
        val wds = tous(t.lowercase(), "\\b$RxJour", ci = false).map { it.valeur }.distinct()
        if (wds.size == 1 && !t.contient("\\b\\d{1,2}\\s+$RxMois")) {
            val h = trouve(t, "\\b\\d{1,2}\\s*h(?:\\s*\\d{2})?\\b|\\bmidi\\b")
            res.jour = wds[0] + if (h.ok) " à ${h.valeur}" else ""
        }
    }
    // Série chaque mois : « le loyer, c'est le 3 maintenant »
    val rep = rv?.laRepetition
    if (rv != null && rep != null && rep.type == "mois" && (rep.rang ?: 0) == 0) {
        val m = trouve(t, "\\b(?:le|au)\\s+(?<d>\\d{1,2}|1er|premier)\\b(?!\\s*(?:(?:$RxMois)\\b|/|h\\b|heures?|:))")
        if (m.ok) { val d = nombre(m.g("d")!!.remplace("er$", "")); if (d != null && d in 1..31) res.decalage = "le $d de chaque mois" }
    }
    val m = trouve(t, "\\b(?<v>repouss|d[ée]cal|recul|retard|avanc)\\w*\\b.*?\\b(?:de\\s+|d')(?:(?<n>\\d+|une?|deux|trois|quatre|cinq|six|sept|huit|dix|quinze|trente|quarante-cinq)\\s*(?<u>heures?|h\\b|minutes?|min\\b|jours?|semaines?)|(?<dh>une demi[- ]heure)|(?<qh>un quart d'heure))")
    if (m.ok && rv != null) {
        val (n, u) = when {
            m.a("dh") -> 30 to 'm'
            m.a("qh") -> 15 to 'm'
            else -> (nombre(m.g("n")!!.remplace("^une?$", "un")) ?: 0) to m.g("u")!!.lowercase()[0]
        }
        if (n > 0) {
            val signe = if (m.g("v")!!.lowercase().startsWith("avanc")) -1L else 1L
            val b = base()!!
            val nouveau = when (u) {
                'h' -> b.plusHours(signe * n)
                'm' -> b.plusMinutes(signe * n)
                'j' -> b.plusDays(signe * n)
                else -> b.plusDays(7 * signe * n)
            }
            res.decalage = "le ${nouveau.format(FormatJourAn)}" + (if (!rv.journee) " à ${nouveau.hour} h %02d".format(nouveau.minute) else "")
        }
    }
    var r = trouve(t, "\\b(?:renomme|rebaptise|appelle)[- ]?(?:le|la|les|l')?\\b.*?\\s+en\\s+(?<t>.+?)\\s*[.!]?$")
    if (!r.ok) r = trouve(t, "\\bchange\\s+(?:le\\s+)?(?:nom|titre)\\b.*?\\s+en\\s+(?<t>.+?)\\s*[.!]?$")
    if (r.ok) res.titre = r.g("t")!!.trim(' ', '«', '»', '"', '\'')
    for (x in tous(t, "\\b(?:[Aa]joute|[Rr]ajoute|[Ii]nvite)\\s+(?<n>$majX(?:\\s*(?:,|et)\\s*$majX)*)", ci = false)) {
        res.personnes += x.g("n")!!.split(Regex("\\s*(?:,|\\bet\\b)\\s*")).filter { it.isNotEmpty() && it[0] in "ABCDEFGHIJKLMNOPQRSTUVWXYZÉÈÊÀÂÎÏÔÛÇ" }
    }
    if (rv != null && rep != null && t.contient("\\b(?:c'est fini|fini|termin\\w*|arr[êe]t\\w*|stop\\w*|plus de|plus d')\\b")) {
        val a = trouve(t, "(?<p>(?:apr[èe]s|[àa] partir d(?:u|e|')|d[èe]s|depuis)\\s*.+?)\\s*[.!]?$")
        if (a.ok && lirePeriode(a.g("p")!!, agenda.maintenant) != null) res.arret = a.g("p")
    }
    return res
}

/** L'heure comme sur PC (Format-Heure) : « midi », « 15 h », « 15 h 30 ». */
internal fun heurePc(d: LocalDateTime): String = when {
    d.hour == 12 && d.minute == 0 -> "midi"
    d.hour == 0 && d.minute == 0 -> "minuit"
    d.minute == 0 -> "${d.hour} h"
    else -> "${d.hour} h %02d".format(d.minute)
}
