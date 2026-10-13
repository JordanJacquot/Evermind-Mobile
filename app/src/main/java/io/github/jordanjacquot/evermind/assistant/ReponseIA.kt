package io.github.jordanjacquot.evermind.assistant

import io.github.jordanjacquot.evermind.donnees.RendezVous
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** Une action proposée par l'IA (le schéma $SchemaIA), plus ce qu'Evermind y ajoute lui-même. */
class ActionIA(
    var action: String,
    var id: String? = null,
    var phrase: String? = null,
    var titre: String? = null,
    var lieu: String? = null,
    var personnes: List<String> = emptyList(),
    var notes: String? = null,
    var chosesAPrendre: List<String> = emptyList(),
    var important: Boolean? = null,
    var periode: String? = null,
    var rappels: List<String> = emptyList(),
    var retirerPersonnes: List<String> = emptyList(),
    var dossiers: List<String> = emptyList(),
    var ouvrirAuDebut: Boolean? = null,
) {
    /** Le rendez-vous visé, choisi par Evermind (suppression de toute une période). */
    var cible: RendezVous? = null
    /** Des postes de travail (« je suis du matin lundi et mardi »), lus par Evermind. */
    var postes: List<Poste>? = null

    /** Le code tel que l'IA l'a écrit, sans crochets (« [e1fec9] » devient « e1fec9 »). */
    fun code(): String? = id?.trim('[', ']', ' ')?.takeIf { it.isNotEmpty() }

    fun versJson(): JsonObject = buildJsonObject {
        put("action", action)
        id?.let { put("id", it) }
        phrase?.let { put("phrase", it) }
        titre?.let { put("titre", it) }
        lieu?.let { put("lieu", it) }
        if (personnes.isNotEmpty()) put("personnes", liste(personnes))
        notes?.let { put("notes", it) }
        if (chosesAPrendre.isNotEmpty()) put("choses_a_prendre", liste(chosesAPrendre))
        important?.let { put("important", it) }
        periode?.let { put("periode", it) }
        if (rappels.isNotEmpty()) put("rappels", liste(rappels))
        if (retirerPersonnes.isNotEmpty()) put("retirer_personnes", liste(retirerPersonnes))
        if (dossiers.isNotEmpty()) put("dossiers", liste(dossiers))
        ouvrirAuDebut?.let { put("ouvrir_au_debut", it) }
    }

    private fun liste(l: List<String>) = buildJsonArray { l.forEach { add(JsonPrimitive(it)) } }
}

/** La réponse de l'IA : ce qu'Evermind doit faire, et ce qu'elle dit. */
class ReponseIA(val actions: List<ActionIA>, val reponse: String) {
    fun versJson(): String = buildJsonObject {
        put("actions", JsonArray(actions.map { it.versJson() }))
        put("reponse", reponse)
    }.toString()

    companion object {
        private val json = Json { isLenient = true }

        /** Lit la réponse de l'IA, en tolérant ses petites fautes (un texte au lieu d'une liste...) ; null si illisible. */
        fun lire(texte: String?): ReponseIA? {
            if (texte.isNullOrBlank()) return null
            // (seulement l'objet JSON, même si le modèle a écrit quelque chose avant ou après)
            val debut = texte.indexOf('{')
            val fin = texte.lastIndexOf('}')
            if (debut < 0 || fin <= debut) return null
            val o = runCatching { json.parseToJsonElement(texte.substring(debut, fin + 1)) as? JsonObject }.getOrNull() ?: return null
            val actions = (o["actions"] as? JsonArray).orEmpty().mapNotNull { e ->
                val a = e as? JsonObject ?: return@mapNotNull null
                val action = a.texte("action") ?: return@mapNotNull null
                ActionIA(
                    action = action, id = a.texte("id"), phrase = a.texte("phrase"), titre = a.texte("titre"), lieu = a.texte("lieu"),
                    personnes = a.liste("personnes"), notes = a.texte("notes"), chosesAPrendre = a.liste("choses_a_prendre"),
                    important = a.oui("important"), periode = a.texte("periode"), rappels = a.liste("rappels"),
                    retirerPersonnes = a.liste("retirer_personnes"), dossiers = a.liste("dossiers"), ouvrirAuDebut = a.oui("ouvrir_au_debut"),
                )
            }
            return ReponseIA(actions, o.texte("reponse") ?: "")
        }

        private fun JsonObject.texte(nom: String): String? =
            (this[nom] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull?.takeIf { it.isNotEmpty() }

        private fun JsonObject.oui(nom: String): Boolean? = (this[nom] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.booleanOrNull

        private fun JsonObject.liste(nom: String): List<String> = when (val e: JsonElement? = this[nom]) {
            is JsonArray -> e.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { s -> s.isNotBlank() } }
            is JsonPrimitive -> listOfNotNull(e.contentOrNull?.takeIf { it.isNotBlank() && e !is JsonNull })
            else -> emptyList()
        }
    }
}
