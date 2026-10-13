package io.github.jordanjacquot.evermind.voix

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

/** Où en est l'installation d'un pack. */
sealed interface EtatInstallation {
    data object Repos : EtatInstallation
    data class EnCours(val quoi: String, val fait: Long, val total: Long) : EtatInstallation
    data class Echec(val message: String) : EtatInstallation
    data object Fini : EtatInstallation
}

/**
 * Télécharge et range les modèles d'un pack. Un téléchargement coupé reprend où il s'était arrêté ; un fichier dont
 * l'empreinte ne correspond pas est refusé (et effacé). Il faut garder Evermind ouvert pendant ce temps.
 */
class Installation(private val contexte: Context, private val portee: CoroutineScope) {
    private val etats = Pack.entries.associateWith { MutableStateFlow<EtatInstallation>(EtatInstallation.Repos) }
    private var travaux = mutableMapOf<Pack, Job>()

    fun etat(pack: Pack): StateFlow<EtatInstallation> = etats.getValue(pack)

    fun lancer(pack: Pack, quandFini: () -> Unit = {}) {
        if (travaux[pack]?.isActive == true) return
        val etat = etats.getValue(pack)
        travaux[pack] = portee.launch(Dispatchers.IO) {
            try {
                val aFaire = Modeles.manquants(contexte, pack)
                val total = aFaire.sumOf { it.taille }
                var avant = 0L
                for (f in aFaire) {
                    etat.value = EtatInstallation.EnCours(f.nom, avant, total)
                    installer(f) { n -> etat.value = EtatInstallation.EnCours(f.nom, avant + n, total) }
                    avant += f.taille
                }
                etat.value = EtatInstallation.Fini
                quandFini()
            } catch (e: Exception) {
                etat.value = EtatInstallation.Echec(
                    if (e is java.io.IOException) "Le téléchargement s'est arrêté (${e.message}). Réessaie : il reprendra où il en était."
                    else "Installation impossible : ${e.message}"
                )
            }
        }
    }

    private suspend fun installer(f: FichierModele, progres: (Long) -> Unit) {
        val dossier = Modeles.dossier(contexte)
        val partiel = File(dossier, "telechargements/" + f.url.substringAfterLast('/') + ".part")
        partiel.parentFile!!.mkdirs()
        telecharger(f, partiel, progres)
        val empreinte = sha256(partiel)
        if (!empreinte.equals(f.sha256, ignoreCase = true)) {
            partiel.delete()
            error("${f.nom} : le fichier reçu n'est pas le bon (empreinte différente), il a été effacé")
        }
        val cible = File(dossier, f.vers)
        when (f.forme) {
            Forme.FICHIER -> {
                cible.parentFile!!.mkdirs()
                cible.delete()
                check(partiel.renameTo(cible)) { "impossible de ranger ${f.nom}" }
            }
            // (une archive est ouverte à côté, puis mise en place d'un coup : à moitié ouverte, elle ne compte pas)
            Forme.TAR_BZ2 -> {
                val ouverte = File(cible.path + ".part").apply { deleteRecursively() }
                partiel.inputStream().buffered().use { brut ->
                    TarArchiveInputStream(BZip2CompressorInputStream(brut)).use { tar ->
                        while (true) {
                            val e = tar.nextEntry ?: break
                            ranger(ouverte, e.name, e.isDirectory, f.garder, tar)
                        }
                    }
                }
                mettreEnPlace(ouverte, cible)
                partiel.delete()
            }
            Forme.ZIP -> {
                val ouverte = File(cible.path + ".part").apply { deleteRecursively() }
                ZipInputStream(partiel.inputStream().buffered()).use { zip ->
                    while (true) {
                        val e = zip.nextEntry ?: break
                        ranger(ouverte, e.name, e.isDirectory, f.garder, zip)
                    }
                }
                mettreEnPlace(ouverte, cible)
                partiel.delete()
            }
        }
    }

    private fun mettreEnPlace(ouverte: File, cible: File) {
        cible.deleteRecursively()
        check(ouverte.renameTo(cible)) { "impossible de ranger ${cible.name}" }
    }

    /** Range un élément d'archive, sans son premier dossier ; jamais en dehors du dossier prévu. */
    private fun ranger(cible: File, nom: String, estDossier: Boolean, garder: List<String>, contenu: InputStream) {
        val chemin = nom.substringAfter('/', "")
        if (chemin.isEmpty() || estDossier) return
        if (garder.isNotEmpty() && garder.none { chemin == it || (it.endsWith("/") && chemin.startsWith(it)) }) return
        val f = File(cible, chemin)
        require(f.canonicalPath.startsWith(cible.canonicalPath + File.separator)) { "archive refusée : $nom" }
        f.parentFile!!.mkdirs()
        f.outputStream().use { contenu.copyTo(it) }
    }

    private suspend fun telecharger(f: FichierModele, partiel: File, progres: (Long) -> Unit) {
        var deja = if (partiel.exists()) partiel.length() else 0L
        if (deja >= f.taille) { progres(f.taille); return }
        var url = URL(f.url)
        var connexion: HttpURLConnection
        // (les liens de GitHub et Hugging Face renvoient ailleurs ; on suit, en HTTPS seulement)
        var sauts = 0
        while (true) {
            connexion = url.openConnection() as HttpURLConnection
            connexion.instanceFollowRedirects = false
            connexion.connectTimeout = 20_000
            connexion.readTimeout = 30_000
            if (deja > 0) connexion.setRequestProperty("Range", "bytes=$deja-")
            val code = connexion.responseCode
            if (code in 300..399 && sauts++ < 8) {
                val suite = URL(url, connexion.getHeaderField("Location"))
                require(suite.protocol == "https") { "lien non sécurisé refusé" }
                connexion.disconnect()
                url = suite
                continue
            }
            if (code == 200 && deja > 0) { deja = 0; partiel.delete() }   // le serveur ne sait pas reprendre : on recommence
            if (code != 200 && code != 206) throw java.io.IOException("réponse $code")
            break
        }
        connexion.inputStream.use { entree ->
            java.io.FileOutputStream(partiel, deja > 0).use { sortie ->
                val tampon = ByteArray(256 * 1024)
                var n = deja
                var dernier = 0L
                while (coroutineContext.isActive) {
                    val lu = entree.read(tampon)
                    if (lu < 0) break
                    sortie.write(tampon, 0, lu)
                    n += lu
                    if (n - dernier > 1_000_000) { progres(n); dernier = n }
                }
                progres(n)
            }
        }
        connexion.disconnect()
        coroutineContext.ensureActive()   // (arrêté en route : ce qui est reçu est gardé pour reprendre)
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { e ->
            val tampon = ByteArray(1024 * 1024)
            while (true) { val n = e.read(tampon); if (n < 0) break; md.update(tampon, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
