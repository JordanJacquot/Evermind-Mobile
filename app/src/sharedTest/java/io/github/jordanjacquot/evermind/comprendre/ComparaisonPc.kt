package io.github.jordanjacquot.evermind.comprendre

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDateTime

/**
 * Compare la lecture Kotlin des phrases à celle d'Evermind PC. Le fichier phrases-evermind-pc.json contient la réponse de
 * Read-Phrase (comprendre.ps1) pour toutes les phrases des catalogues d'Evermind, avec « maintenant » fixé au
 * samedi 10 octobre 2026 à 18 h 30. Pour le refaire après un changement sur PC : voir outils/exporter-phrases.ps1.
 * Partagé entre les tests sur PC (JVM) et sur le téléphone (Android lit les expressions régulières avec ICU).
 */
object ComparaisonPc {
    /** Les phrases dont la lecture diffère, avec ce qui diffère. */
    fun differences(json: String): Pair<Int, List<String>> {
        val racine = Json.parseToJsonElement(json).jsonObject
        val maintenant = LocalDateTime.parse(racine.getValue("Maintenant").jsonPrimitive.content)
        val phrases = racine.getValue("Phrases").jsonArray
        val erreurs = mutableListOf<String>()
        for (e in phrases) {
            val pc = e.jsonObject
            val phrase = pc.texte("Phrase")!!
            val k = try {
                lirePhrase(phrase, maintenant)
            } catch (x: Exception) {
                erreurs += "« $phrase » : plantage $x"
                continue
            }
            val ecarts = buildList {
                fun compare(nom: String, attendu: Any?, obtenu: Any?) {
                    if (attendu != obtenu) add("$nom : attendu $attendu, obtenu $obtenu")
                }
                compare("Titre", pc.texte("Titre"), k.titre)
                compare("Manque", pc.texte("Manque"), k.manque.code)
                compare("Quand", pc.texte("Quand"), k.quand?.toString())
                compare("Jour", pc.texte("Jour"), k.jour?.toString())
                compare("Heure", pc.texte("Heure"), k.heure?.toString())
                compare("Type", pc.texte("Type"), k.sorte)
                compare("Journee", pc.oui("Journee"), k.journee)
                compare("Fin", pc.texte("Fin"), k.fin?.toString())
                compare("Repetition", repetitionPc(pc["Repetition"]), k.repetition?.let { texteRepetition(it) })
                compare("Lieu", pc.texte("Lieu"), k.lieu)
                compare("Avec", pc.liste("Avec").orEmpty(), k.avec)
                compare("Lien", pc.texte("Lien"), k.lien)
                compare("Important", pc.oui("Important"), k.important)
                compare("Rappels", pc.liste("Rappels"), k.rappels)
                compare("Naissance", pc.texte("Naissance")?.toInt(), k.naissance)
                compare("APreparer", pc.liste("APreparer").orEmpty(), k.aPreparer)
                compare("Jours", pc.liste("Jours").orEmpty(), k.jours.map { it.toString() })
                compare("Blocs", pc.liste("Blocs").orEmpty(), k.blocs.map { "${it.debut}/${it.fin}" })
            }
            if (ecarts.isNotEmpty()) erreurs += "« $phrase »\n    " + ecarts.joinToString("\n    ")
        }
        return phrases.size to erreurs
    }

    private fun texteRepetition(r: Repetition) = listOf(
        "Type" to r.type, "Jour" to r.jour?.toString(), "Jours" to r.jours?.joinToString(","), "Mois" to r.mois?.toString(),
        "Rang" to r.rang?.toString(), "Intervalle" to r.intervalle?.toString(), "Jusqua" to r.jusqua, "Sauf" to r.sauf?.joinToString(","),
    ).filter { it.second != null }.joinToString("; ") { "${it.first}=${it.second}" }

    private fun repetitionPc(e: JsonElement?): String? {
        if (e == null || e is JsonNull) return null
        val o = e.jsonObject
        return listOf("Type", "Jour", "Jours", "Mois", "Rang", "Intervalle", "Jusqua", "Sauf")
            .mapNotNull { nom -> o.liste(nom)?.let { nom to it.joinToString(",") } }
            .joinToString("; ") { "${it.first}=${it.second}" }
    }

    private fun JsonObject.texte(nom: String): String? = (this[nom] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    private fun JsonObject.oui(nom: String): Boolean = (this[nom] as? JsonPrimitive)?.booleanOrNull ?: false

    // (PowerShell 5.1 écrit une liste d'un seul élément comme une simple valeur)
    private fun JsonObject.liste(nom: String): List<String>? = when (val e = this[nom]) {
        null, is JsonNull -> null
        is JsonArray -> e.map { it.jsonPrimitive.content }
        is JsonPrimitive -> listOf(e.content)
        else -> null
    }
}
