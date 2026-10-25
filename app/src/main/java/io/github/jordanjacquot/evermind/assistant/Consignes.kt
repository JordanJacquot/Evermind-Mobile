package io.github.jordanjacquot.evermind.assistant

import io.github.jordanjacquot.evermind.donnees.estFait
import io.github.jordanjacquot.evermind.donnees.laRepetition
import io.github.jordanjacquot.evermind.ui.enTexte

// Ce que l'IA sait (Get-ContexteIA sur PC) : les consignes, la date, l'heure et l'agenda, avec un code court par
// rendez-vous. Sur le téléphone, les consignes (toujours les mêmes) viennent d'abord, puis la marque du cache, puis
// la date et l'agenda : les consignes ne sont lues qu'une fois.

/** Les consignes, mot pour mot celles d'Evermind PC (app/src/main/resources/assistant/consignes.txt). */
val ConsignesFixes: String by lazy {
    Consignes::class.java.classLoader!!.getResourceAsStream("assistant/consignes.txt")!!.reader(Charsets.UTF_8).readText().trimEnd()
}

/**
 * Le schéma de la réponse ($SchemaIA sur PC) : l'IA ne peut répondre que dans cette forme. La grammaire qui en est
 * tirée impose l'ordre des champs : il doit être celui des exemples d'entraînement (« action », « sujet », puis « id »
 * ou « periode »), sinon le modèle ne peut plus écrire un champ qu'il met plus tôt.
 */
const val SchemaIA = """{"type":"object","required":["actions","reponse"],"properties":{"actions":{"type":"array","items":{"type":"object","required":["action"],"properties":{"action":{"type":"string","enum":["creer","modifier","supprimer","fait","annuler","ouvrir","question"]},"sujet":{"type":"string","enum":["quand","ou","qui","age","notes","prendre","jour","prochain","libre","duree","fin","fois","apres","fait","dans","important","repete"]},"id":{"type":"string"},"phrase":{"type":"string"},"titre":{"type":"string"},"lieu":{"type":"string"},"personnes":{"type":"array","items":{"type":"string"}},"notes":{"type":"string"},"choses_a_prendre":{"type":"array","items":{"type":"string"}},"important":{"type":"boolean"},"periode":{"type":"string"},"rappels":{"type":"array","items":{"type":"string"}},"retirer_personnes":{"type":"array","items":{"type":"string"}},"dossiers":{"type":"array","items":{"type":"string"}},"ouvrir_au_debut":{"type":"boolean"}}}},"reponse":{"type":"string"}}}"""

/** Les consignes de cette demande, et les codes (« e1fec9 ») qui désignent les rendez-vous. */
class Contexte(val systeme: String, val codes: Map<String, String>)

/** Les consignes du modèle entraîné pour Evermind : il a appris la tâche, une phrase suffit (au lieu de 2 300 jetons). */
const val ConsignesCourtes = "Tu es l'assistant de l'agenda Evermind. Réponds seulement en JSON {\"actions\": [...], \"reponse\": \"...\"}."

object Consignes {
    /**
     * Pour le modèle entraîné : la consigne courte, puis la date et l'agenda (les mêmes que pour les autres modèles). La
     * marque du cache est retirée avant que le modèle lise (le texte est celui de l'entraînement).
     */
    fun court(agenda: AgendaIA): Contexte {
        val c = contexte(agenda)
        return Contexte(ConsignesCourtes + "\n\n" + MoteurLlama.MARQUE_CACHE + c.systeme.substringAfter(MoteurLlama.MARQUE_CACHE), c.codes)
    }

    fun contexte(agenda: AgendaIA): Contexte {
        val today = agenda.aujourdHui
        val vus = mutableSetOf<String>()
        val liste = mutableListOf<FoisIA>()
        for (it in agenda.entre(today.atStartOfDay(), today.plusDays(90).atStartOfDay())) if (vus.add(it.rv.id)) liste += it
        for (r in agenda.liste) {
            if (r.id in vus) continue
            val n = agenda.prochaine(r, today.atStartOfDay())
            if (n != null && !n.toLocalDate().isBefore(today)) { vus += r.id; liste += FoisIA(r, n) }
        }
        // Les rendez-vous des 30 derniers jours viennent après (« supprime le coiffeur » d'avant-hier)
        val passes = agenda.liste.filter {
            it.id !in vus && it.repetition == null && !it.quand.isBefore(today.minusDays(30).atStartOfDay()) && it.quand.isBefore(agenda.maintenant)
        }.sortedByDescending { it.quand }.take(10).map { FoisIA(it, it.quand) }

        val codes = linkedMapOf<String, String>()
        val lignes = mutableListOf<String>()
        for (it in liste.sortedBy { x -> x.prochain }.take(40) + passes) {
            val r = it.rv
            val c = "e" + r.id.replace("-", "").take(5)   // même code d'une phrase à l'autre
            codes[c] = r.id
            val l = StringBuilder("- [$c] ${titreDe(r)} (${r.sorte.nom}) : ${quandRV(r, it.prochain, today)} (${relatif(it.prochain, today)})")
            r.laRepetition?.let { rep ->
                l.append(", revient ${rep.enTexte(today)}")
                if (rep.jusqua == null) l.append(" (sans fin : il a lieu aussi tous les mois suivants)")
            }
            if (r.lieu.isNotBlank()) l.append(". Lieu : ${r.lieu}")
            if (r.avec.isNotEmpty()) l.append(". Avec : ${r.avec.joinToString(", ")}")
            if (r.aPreparer.isNotEmpty()) l.append(". À prendre : ${r.aPreparer.joinToString(", ") { a -> a.texte }}")
            if (r.notes.isNotBlank()) l.append(". Notes : ${r.notes.replace(Regex("\\s+"), " ")}")
            if (r.important) l.append(". Important")
            if (r.estFait(it.prochain)) l.append(". Déjà fait")
            lignes += l.toString()
        }
        if (lignes.isEmpty()) lignes += "(son agenda est vide)"
        val jour = today.format(java.time.format.DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Fr))
        val dynamique = "Nous sommes le $jour, il est ${agenda.maintenant.toLocalTime().enTexte()}.\n\n" +
            "Son agenda (les identifiants entre crochets servent à désigner un rendez-vous) :\n" + lignes.joinToString("\n")
        return Contexte(ConsignesFixes + "\n\n" + MoteurLlama.MARQUE_CACHE + dynamique, codes)
    }
}
