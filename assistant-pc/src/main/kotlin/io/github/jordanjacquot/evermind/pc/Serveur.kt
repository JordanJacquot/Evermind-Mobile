package io.github.jordanjacquot.evermind.pc

import io.github.jordanjacquot.evermind.assistant.MoteurIA
import io.github.jordanjacquot.evermind.assistant.MoteurLlama
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL

/**
 * Le modèle entraîné d'Evermind, lancé avec le llama-server d'Evermind PC. Il ne démarre qu'à la première phrase qui
 * en a besoin (les ordres courts passent par les règles), et s'arrête après 10 minutes sans question pour rendre la
 * mémoire aux jeux (comme le Qwen3 4B d'Evermind PC).
 */
class Serveur(private val exe: File, private val modele: File, private val veilleMs: Long = 600_000) : AutoCloseable {
    private var processus: Process? = null
    private var port = 0
    @Volatile private var dernier = System.currentTimeMillis()

    init {
        Thread {
            while (true) {
                Thread.sleep(5_000)
                synchronized(this) { if (processus != null && System.currentTimeMillis() - dernier > veilleMs) arreter() }
            }
        }.apply { isDaemon = true }.start()
    }

    @Synchronized
    private fun demarrer(): Boolean {
        dernier = System.currentTimeMillis()
        if (processus?.isAlive == true) return true
        port = ServerSocket(0).use { it.localPort }
        val threads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 8)
        // -ngl 99 : tout sur la carte graphique s'il y en a une, sinon le processeur ; la réflexion de Qwen3 coupée
        val pb = ProcessBuilder(exe.path, "-m", modele.path, "--host", "127.0.0.1", "--port", "$port", "-ngl", "99", "-c", "4096",
            "-t", "$threads", "--no-webui", "--reasoning-budget", "0")
            .directory(exe.parentFile).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD)
        pb.environment()["LLAMA_ARG_CHAT_TEMPLATE_KWARGS"] = """{"enable_thinking":false}"""
        val p = runCatching { pb.start() }.getOrElse { journal("llama-server impossible à démarrer : ${it.message}"); return false }
        processus = p
        val limite = System.currentTimeMillis() + 120_000
        while (System.currentTimeMillis() < limite && p.isAlive) {
            if (runCatching { (URL("http://127.0.0.1:$port/health").openConnection() as HttpURLConnection).responseCode == 200 }.getOrDefault(false)) return true
            Thread.sleep(250)
        }
        journal("le modèle ne répond pas (llama-server ${if (p.isAlive) "trop lent" else "arrêté, code ${p.exitValue()}"})")
        arreter()
        return false
    }

    @Synchronized
    private fun arreter() {
        processus?.let { runCatching { it.destroy() } }
        processus = null
    }

    val moteur = MoteurIA { messages, schema ->
        if (!demarrer()) return@MoteurIA null
        val corps = buildJsonObject {
            put("messages", JsonArray(messages.map { m ->
                buildJsonObject { put("role", m.role); put("content", m.contenu.replace(MoteurLlama.MARQUE_CACHE, "")) }
            }))
            put("temperature", 0)
            put("max_tokens", 400)
            put("response_format", buildJsonObject {
                put("type", "json_schema")
                put("json_schema", buildJsonObject { put("name", "reponse"); put("schema", Json.parseToJsonElement(schema)) })
            })
        }.toString()
        val r = runCatching {
            val c = URL("http://127.0.0.1:$port/v1/chat/completions").openConnection() as HttpURLConnection
            c.requestMethod = "POST"; c.doOutput = true; c.readTimeout = 120_000
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.outputStream.use { it.write(corps.toByteArray(Charsets.UTF_8)) }
            val o = Json.parseToJsonElement(c.inputStream.reader(Charsets.UTF_8).readText()).jsonObject
            o["choices"]!!.jsonArray[0].jsonObject["message"]!!.jsonObject["content"]!!.jsonPrimitive.content
        }.getOrElse { journal("le modèle n'a pas répondu : ${it.message}"); null }
        dernier = System.currentTimeMillis()
        r
    }

    override fun close() = arreter()
}

/** Les messages pour Evermind PC (son journal) : sur la sortie d'erreur, la sortie normale ne porte que les réponses. */
fun journal(m: String) = System.err.println(m)
