package io.github.jordanjacquot.evermind.pc

import io.github.jordanjacquot.evermind.assistant.Assistant
import io.github.jordanjacquot.evermind.assistant.Consignes
import io.github.jordanjacquot.evermind.donnees.APreparer
import io.github.jordanjacquot.evermind.donnees.RendezVous
import io.github.jordanjacquot.evermind.donnees.Sorte
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.io.PrintStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/*
 * evermind-assistant : l'assistant d'Evermind Mobile pour Evermind PC (docs/ASSISTANT-PC.md).
 *   evermind-assistant --serveur <llama-server.exe> --modele <evermind-0.6b.gguf> [--journal <fichier>]
 * Une demande par ligne sur l'entrée (JSON, UTF-8) :
 *   {"texte": "décale le dentiste à jeudi", "agenda": [<les rendez-vous du fichier rendez-vous.json>], "maintenant": "2026-10-19T14:00"}
 * Une réponse par ligne sur la sortie :
 *   {"compris": true, "dit": "...", "ecrit": "...", "change": true, "aCorriger": null, "agenda": [<le nouvel agenda>]}
 *   {"compris": false}  : ni les règles ni le modèle n'ont compris (rien n'est touché)
 * Le programme reste ouvert entre deux demandes : il se souvient de la conversation (« lequel ? » puis « le dentiste »)
 * et de quoi annuler (« annule »).
 */

private val FormatPc = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")
private val MarqueUtf8 = Char(0xFEFF).toString()   // (la marque UTF-8, BOM)
private val FormatCree =DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

fun main(args: Array<String>) {
    // (la sortie ne porte que les réponses : ce que le code du téléphone écrit, ses traces, part dans le journal)
    fun arg(nom: String) = args.indexOf(nom).takeIf { it >= 0 && it + 1 < args.size }?.let { args[it + 1] }
    val sortie = PrintStream(java.io.FileOutputStream(java.io.FileDescriptor.out), true, Charsets.UTF_8)
    // (--journal : le fichier où écrire ce qui se passe, sinon la sortie d'erreur)
    val journalFichier = arg("--journal")?.let { java.io.FileOutputStream(it, true) }
    System.setErr(PrintStream(journalFichier ?: java.io.FileOutputStream(java.io.FileDescriptor.err), true, Charsets.UTF_8))
    System.setOut(System.err)
    val exe = arg("--serveur")?.let(::File)
    val modele = arg("--modele")?.let(::File)
    val serveur = if (exe != null && modele != null && exe.exists() && modele.exists()) Serveur(exe, modele) else null
    // (le modèle s'arrête avec le programme, même s'il est fermé sans finir sa lecture)
    serveur?.let { s -> Runtime.getRuntime().addShutdownHook(Thread { s.close() }) }
    if (serveur == null) journal("sans le modèle (--serveur et --modele absents ou introuvables) : les règles seules")
    val assistant = Assistant(serveur?.moteur, ::rappelsPc, consignes = { Consignes::court })
    val entree = System.`in`.bufferedReader(Charsets.UTF_8)
    try {
        while (true) {
            // (Windows PowerShell met parfois la marque UTF-8 au début de ce qu'il envoie)
            val ligne = entree.readLine()?.removePrefix(MarqueUtf8) ?: break
            if (ligne.isBlank()) continue
            val reponse = runCatching { repondre(assistant, Json.parseToJsonElement(ligne).jsonObject) }
                .getOrElse { journal("demande illisible : ${it.message}"); buildJsonObject { put("compris", false); put("erreur", it.message ?: "") } }
            sortie.println(reponse.toString())
        }
    } finally {
        serveur?.close()
    }
}

private fun repondre(assistant: Assistant, d: JsonObject): JsonObject {
    val texte = d["texte"]!!.jsonPrimitive.content
    val brut = d["agenda"]?.jsonArray.orEmpty().map { it.jsonObject }
    val maintenant = d["maintenant"]?.jsonPrimitive?.contentOrNull?.let { LocalDateTime.parse(it, FormatPc) } ?: LocalDateTime.now()
    val agenda = brut.mapNotNull { runCatching { depuisPc(it) }.getOrElse { e -> journal("rendez-vous illisible ignoré : ${e.message}"); null } }
    val r = runBlocking { assistant.demander(texte, agenda, maintenant) } ?: return buildJsonObject { put("compris", false) }
    val (res, apres) = r
    // (ce que le téléphone ne connaît pas, les dossiers d'un rendez-vous, est repris tel quel du fichier du PC)
    val parId = brut.associateBy { it["Id"]?.jsonPrimitive?.contentOrNull }
    return buildJsonObject {
        put("compris", true)
        put("dit", res.dit)
        put("ecrit", res.ecrit)
        // (« annule » remet l'agenda d'avant sans être un changement pour le téléphone : c'en est un pour le fichier)
        put("change", res.change || apres.toSet() != agenda.toSet())
        put("aCorriger", res.aCorriger?.let { JsonPrimitive(it) } ?: JsonNull)
        put("agenda", buildJsonArray { for (x in apres) add(versPc(x, parId[x.id])) })
    }
}

/** Un rendez-vous du fichier d'Evermind PC (ConvertTo-RendezVous). */
fun depuisPc(o: JsonObject): RendezVous {
    fun t(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    fun b(k: String) = (o[k] as? JsonPrimitive)?.booleanOrNull ?: false
    fun liste(k: String) = (o[k] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.takeIf { s -> s.isNotBlank() } }
    return RendezVous(
        id = t("Id")!!,
        titre = t("Titre").orEmpty(),
        sorte = Sorte.depuisCode(t("Type")),
        quand = LocalDateTime.parse(t("Quand")!!, FormatPc),
        journee = b("Journee"),
        fin = t("Fin")?.takeIf { it.isNotBlank() }?.let { runCatching { LocalDateTime.parse(it, FormatPc) }.getOrNull() },
        repetition = (o["Repetition"] as? JsonObject)?.toString(),
        lieu = t("Lieu").orEmpty(),
        avec = liste("Avec").orEmpty(),
        lien = t("Lien").orEmpty(),
        notes = t("Notes").orEmpty(),
        aPreparer = (o["APreparer"] as? JsonArray).orEmpty().mapNotNull { x ->
            val a = x as? JsonObject ?: return@mapNotNull null
            val texte = (a["Texte"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            APreparer(texte, (a["Fait"] as? JsonPrimitive)?.booleanOrNull ?: false)
        },
        rappels = if (o["Rappels"] is JsonArray) liste("Rappels") else null,
        important = b("Important"),
        fait = b("Fait"),
        naissance = (o["Naissance"] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() },
        cree = t("Cree")?.let { c -> runCatching { LocalDateTime.parse(c) }.getOrNull() } ?: LocalDateTime.now().withNano(0),
    )
}

/** Le même rendez-vous, écrit comme le fichier d'Evermind PC (Get-AgendaDonnees). */
fun versPc(r: RendezVous, avant: JsonObject?): JsonObject = buildJsonObject {
    put("Id", r.id)
    put("Titre", r.titre)
    put("Type", r.sorte.code)
    put("Journee", r.journee)
    put("Quand", r.quand.format(FormatPc))
    put("Fin", r.fin?.let { JsonPrimitive(it.format(FormatPc)) } ?: JsonNull)
    put("Repetition", r.repetition?.let { Json.parseToJsonElement(it) } ?: JsonNull)
    put("Lieu", r.lieu)
    put("Avec", JsonArray(r.avec.map { JsonPrimitive(it) }))
    put("Lien", r.lien)
    put("Notes", r.notes)
    put("APreparer", JsonArray(r.aPreparer.map { buildJsonObject { put("Texte", it.texte); put("Fait", it.fait) } }))
    put("Rappels", r.rappels?.let { l -> JsonArray(l.map { JsonPrimitive(it) }) } ?: JsonNull)
    put("Important", r.important)
    put("Fait", r.fait)
    put("Naissance", r.naissance?.let { JsonPrimitive(it) } ?: JsonNull)
    put("Fichiers", avant?.get("Fichiers") as? JsonArray ?: JsonArray(emptyList()))
    put("OuvrirAuDebut", (avant?.get("OuvrirAuDebut") as? JsonPrimitive)?.booleanOrNull ?: false)
    put("Cree", (avant?.get("Cree") as? JsonPrimitive)?.contentOrNull ?: r.cree.format(FormatCree))
}

/** Les rappels par défaut d'Evermind PC (Get-RappelsDefaut). */
private fun rappelsPc(r: RendezVous): List<String> = r.rappels ?: when {
    r.journee && r.sorte == Sorte.A_FAIRE -> listOf("matin")
    r.journee || r.sorte == Sorte.ANNIV -> listOf("veille", "matin")
    r.sorte == Sorte.A_FAIRE -> listOf("m0")
    else -> listOf("veille", "matin", "m60")
}

