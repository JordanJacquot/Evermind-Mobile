package io.github.jordanjacquot.evermind.maj

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Une version publiée d'Evermind Mobile (une « version » GitHub du projet, avec son APK). */
data class VersionPubliee(val nom: String, val numeros: List<Int>, val apk: String, val taille: Long, val sha256: String)

sealed interface EtatMaj {
    data object Rien : EtatMaj
    data object Verification : EtatMaj
    data object AJour : EtatMaj
    data class Disponible(val v: VersionPubliee) : EtatMaj
    data class Telechargement(val v: VersionPubliee, val octets: Long) : EtatMaj
    data class Prete(val v: VersionPubliee, val fichier: File) : EtatMaj
    /** (v : la version qu'on téléchargeait, pour « Réessayer » ; null si c'est la vérification qui a échoué) */
    data class Echec(val message: String, val v: VersionPubliee? = null) : EtatMaj
}

/**
 * Les mises à jour d'Evermind Mobile, comme pour les autres applis de la suite : au lancement (au plus une fois par
 * jour), Evermind demande à GitHub la liste des versions du projet. S'il y en a une plus récente, un bandeau la
 * propose ; « Mettre à jour » la télécharge, vérifie son empreinte (SHA-256, écrite dans les notes de la version) et
 * lance l'installation : Android affiche sa fenêtre de confirmation (une appli ne peut pas s'installer seule), et
 * refuse tout APK qui ne serait pas signé avec la même clé que l'appli installée. Rien n'est envoyé : c'est une simple
 * lecture de la page des versions.
 */
class MiseAJour(private val contexte: Context, private val portee: CoroutineScope) {
    private val reglages = contexte.getSharedPreferences("maj", Context.MODE_PRIVATE)
    private val _etat = MutableStateFlow<EtatMaj>(EtatMaj.Rien)
    val etat: StateFlow<EtatMaj> = _etat

    /** La version installée (« 0.2.1 »). */
    val versionActuelle: String = runCatching { contexte.packageManager.getPackageInfo(contexte.packageName, 0).versionName }.getOrNull() ?: "?"

    /** Chercher tout seul (activé par défaut ; Réglages > Apparence > Mises à jour). */
    var auto: Boolean
        get() = reglages.getBoolean("auto", true)
        set(v) { reglages.edit().putBoolean("auto", v).apply() }

    /** Au lancement : une vérification si la dernière date de plus d'un jour. */
    fun verifierSiBesoin() {
        val derniere = reglages.getLong("derniere", 0L)
        if (auto && System.currentTimeMillis() - derniere > 24 * 3600_000L) verifier()
    }

    /** Le bouton « Mettre à jour » du bandeau : télécharger (si besoin), vérifier, puis installer d'un coup. */
    fun mettreAJour() {
        when (val e = _etat.value) {
            is EtatMaj.Prete -> installer()
            is EtatMaj.Disponible -> telecharger(installerApres = true)
            is EtatMaj.Echec -> e.v?.let { _etat.value = EtatMaj.Disponible(it); telecharger(installerApres = true) }
            else -> {}
        }
    }

    /** Vérifie maintenant s'il existe une version plus récente. */
    fun verifier() {
        if (_etat.value is EtatMaj.Verification || _etat.value is EtatMaj.Telechargement) return
        _etat.value = EtatMaj.Verification
        portee.launch {
            val r = runCatching { withContext(Dispatchers.IO) { plusRecente() } }
            reglages.edit().putLong("derniere", System.currentTimeMillis()).apply()
            r.onFailure { _etat.value = EtatMaj.Echec("Impossible de joindre GitHub (${it.message ?: "réseau"}).") }
            r.onSuccess { v ->
                if (v == null) _etat.value = EtatMaj.AJour
                else {
                    // (déjà téléchargée et vérifiée lors d'un lancement précédent : prête tout de suite)
                    val f = fichier(v)
                    _etat.value = if (f.exists() && f.length() == v.taille) EtatMaj.Prete(v, f) else EtatMaj.Disponible(v)
                }
            }
        }
    }

    /** Télécharge la version disponible, vérifie son empreinte, puis la marque prête (et l'installe, si demandé). */
    fun telecharger(installerApres: Boolean = false) {
        val v = (_etat.value as? EtatMaj.Disponible)?.v ?: return
        _etat.value = EtatMaj.Telechargement(v, 0)
        portee.launch {
            val r = runCatching {
                withContext(Dispatchers.IO) {
                    val f = fichier(v)
                    f.parentFile?.mkdirs()
                    f.parentFile?.listFiles()?.filter { it != f }?.forEach { it.delete() }   // (les anciennes)
                    val partiel = File(f.path + ".part")
                    var connexion = ouvrir(v.apk)
                    connexion.inputStream.use { entree ->
                        partiel.outputStream().use { sortie ->
                            val tampon = ByteArray(256 * 1024)
                            var n = 0L
                            var dernier = 0L
                            while (true) {
                                val lu = entree.read(tampon)
                                if (lu < 0) break
                                sortie.write(tampon, 0, lu)
                                n += lu
                                if (n - dernier > 1_000_000) { _etat.value = EtatMaj.Telechargement(v, n); dernier = n }
                            }
                        }
                    }
                    connexion.disconnect()
                    if (!sha256(partiel).equals(v.sha256, ignoreCase = true)) {
                        partiel.delete()
                        error("le fichier reçu n'est pas le bon (empreinte différente), il a été effacé")
                    }
                    check(partiel.renameTo(f)) { "impossible de ranger la mise à jour" }
                    f
                }
            }
            r.onSuccess { _etat.value = EtatMaj.Prete(v, it); if (installerApres) installer() }
            r.onFailure { _etat.value = EtatMaj.Echec("La mise à jour n'a pas pu être téléchargée : ${it.message}", v) }
        }
    }

    /**
     * Ouvre l'installeur d'Android. La première fois, Android demande d'autoriser Evermind à installer des applis :
     * on ouvre ce réglage, puis il suffit de revenir et de toucher à nouveau « Installer ».
     */
    fun installer() {
        val f = (_etat.value as? EtatMaj.Prete)?.fichier ?: return
        if (!contexte.packageManager.canRequestPackageInstalls()) {
            contexte.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${contexte.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        val uri = FileProvider.getUriForFile(contexte, "${contexte.packageName}.maj", f)
        contexte.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun fichier(v: VersionPubliee) = File(contexte.cacheDir, "maj/Evermind-Mobile-${v.nom}.apk")

    /** La version publiée la plus récente, si elle est plus récente que celle installée (null sinon). */
    private fun plusRecente(): VersionPubliee? {
        val c = ouvrir("https://api.github.com/repos/$Depot/releases?per_page=20")
        val texte = c.inputStream.bufferedReader().use { it.readText() }
        c.disconnect()
        val actuelle = numeros(versionActuelle)
        return Json.parseToJsonElement(texte).jsonArray.mapNotNull { versionDe(it.jsonObject) }
            .filter { compare(it.numeros, actuelle) > 0 }
            .maxWithOrNull { a, b -> compare(a.numeros, b.numeros) }
    }

    /** Une version d'Evermind Mobile (étiquette « v0.2.1-beta », son APK et son empreinte), ou null (le modèle « assistant-v2 »...). */
    private fun versionDe(o: JsonObject): VersionPubliee? {
        val tag = o["tag_name"]?.jsonPrimitive?.content ?: return null
        if (!tag.matches(Regex("v\\d+(\\.\\d+)*(-[a-z0-9.]+)?"))) return null
        if (o["draft"]?.jsonPrimitive?.boolean == true) return null
        val apk = (o["assets"] as? JsonArray)?.map { it.jsonObject }?.firstOrNull { it["name"]?.jsonPrimitive?.content?.endsWith(".apk") == true } ?: return null
        val sha = Regex("SHA-256 de l'APK\\s*:\\s*([0-9a-fA-F]{64})").find(o["body"]?.jsonPrimitive?.content ?: "")?.groupValues?.get(1) ?: return null
        val url = apk["browser_download_url"]?.jsonPrimitive?.content ?: return null
        if (!url.startsWith("https://github.com/$Depot/releases/download/")) return null
        val nom = tag.removePrefix("v")
        return VersionPubliee(nom, numeros(nom), url, apk["size"]?.jsonPrimitive?.long ?: 0L, sha)
    }

    /** Une connexion en HTTPS seulement, en suivant les renvois de GitHub (vers ses serveurs de fichiers). */
    private fun ouvrir(adresse: String): HttpURLConnection {
        var url = URL(adresse)
        repeat(8) {
            require(url.protocol == "https") { "lien non sécurisé refusé" }
            val c = url.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 20_000
            c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", "Evermind-Mobile")
            c.setRequestProperty("Accept", if (adresse.contains("api.github.com")) "application/vnd.github+json" else "*/*")
            val code = c.responseCode
            if (code in 300..399) { url = URL(url, c.getHeaderField("Location")); c.disconnect(); return@repeat }
            if (code != 200) { c.disconnect(); throw java.io.IOException("réponse $code") }
            return c
        }
        throw java.io.IOException("trop de renvois")
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { e -> val t = ByteArray(1 shl 20); while (true) { val n = e.read(t); if (n < 0) break; md.update(t, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val Depot = "JordanJacquot/Evermind-Mobile"

        /** « 0.2.1-beta » → [0, 2, 1] (le suffixe ne compte pas). */
        fun numeros(version: String): List<Int> = version.substringBefore('-').split('.').mapNotNull { it.toIntOrNull() }

        fun compare(a: List<Int>, b: List<Int>): Int {
            for (i in 0 until maxOf(a.size, b.size)) {
                val d = (a.getOrNull(i) ?: 0) - (b.getOrNull(i) ?: 0)
                if (d != 0) return d
            }
            return 0
        }
    }
}
