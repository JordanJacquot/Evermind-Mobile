package io.github.jordanjacquot.evermind.comprendre

import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Matcher
import java.util.regex.Pattern

// Petits outils autour des expressions régulières, pour écrire la lecture des phrases comme dans comprendre.ps1.
// Comme PowerShell, tout ignore la casse sauf demande contraire (ci = false), et \w, \b comprennent les accents :
// sur Android (ICU) c'est le cas par défaut, sur la JVM des tests il faut UNICODE_CHARACTER_CLASS.

private val surAndroid = System.getProperty("java.vm.name") == "Dalvik"
private val drapeaux = Pattern.UNICODE_CASE or (if (surAndroid) 0 else Pattern.UNICODE_CHARACTER_CLASS)
private val motifs = ConcurrentHashMap<String, Pattern>()
private val nomsDesGroupes = ConcurrentHashMap<String, List<String>>()
private val rxNomGroupe = Pattern.compile("\\(\\?<([a-zA-Z][a-zA-Z0-9]*)>")

internal fun motif(p: String, ci: Boolean = true): Pattern =
    motifs.getOrPut((if (ci) "i" else "c") + p) {
        Pattern.compile(p, if (ci) drapeaux or Pattern.CASE_INSENSITIVE else drapeaux)
    }

private fun groupes(p: String): List<String> = nomsDesGroupes.getOrPut(p) {
    val m = rxNomGroupe.matcher(p)
    buildList { while (m.find()) add(m.group(1)) }.distinct()
}

/** Un morceau trouvé (ou pas) : sa valeur, sa place et ses groupes nommés (null : pas dans ce morceau). */
internal class Trouve(
    val ok: Boolean,
    val valeur: String = "",
    val index: Int = -1,
    private val groupesTrouves: Map<String, String?> = emptyMap(),
) {
    fun g(nom: String): String? = groupesTrouves[nom]
    fun a(nom: String): Boolean = groupesTrouves[nom] != null

    companion object {
        val Rien = Trouve(false)
        fun de(m: Matcher, p: String) = Trouve(true, m.group(), m.start(), groupes(p).associateWith { m.group(it) })
    }
}

internal fun trouve(s: String, p: String, ci: Boolean = true): Trouve {
    val m = motif(p, ci).matcher(s)
    return if (m.find()) Trouve.de(m, p) else Trouve.Rien
}

internal fun tous(s: String, p: String, ci: Boolean = true): List<Trouve> {
    val m = motif(p, ci).matcher(s)
    return buildList { while (m.find()) add(Trouve.de(m, p)) }
}

internal fun String.contient(p: String, ci: Boolean = true): Boolean = motif(p, ci).matcher(this).find()

/** Comme -replace : « $1 » et « ${nom} » dans le remplacement. */
internal fun String.remplace(p: String, par: String, ci: Boolean = true): String = motif(p, ci).matcher(this).replaceAll(par)

internal fun String.remplace(p: String, ci: Boolean = true, par: (Trouve) -> String): String {
    val m = motif(p, ci).matcher(this)
    val sb = StringBuffer()
    while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement(par(Trouve.de(m, p))))
    m.appendTail(sb)
    return sb.toString()
}

/** Le texte en cours de lecture : chaque morceau compris en est retiré (ce qui reste fera le titre). */
internal class Lecture(var s: String) {
    fun morceau(p: String): Trouve {
        val t = trouve(s, p)
        if (t.ok) s = s.substring(0, t.index) + " " + s.substring(t.index + t.valeur.length)
        return t
    }
}
