package io.github.jordanjacquot.evermind.voix

import android.content.Context
import java.text.Normalizer

// La dictée écrit mal les noms : « Oftalmologue », « à messe » pour Metz, « à Hausser » pour Auxerre, « le clair » pour
// Leclerc, « Epinal » sans accent. Evermind corrige ce qu'il entend en comparant les mots « à l'oreille » (une écriture
// phonétique simplifiée du français) avec ce qu'il connaît : les communes de France, les métiers et les lieux de tous
// les jours, et les mots de l'agenda. Une ville n'est corrigée qu'à sa place (« à … », « de … », « vers … »), pour ne
// pas changer « la messe » en « la Metz ».

/** Le son d'un mot ou d'un groupe de mots, écrit simplement : « ophtalmologue » et « Oftalmologue » donnent « oftalmolog ». */
fun phonetique(texte: String): String {
    var s = texte.lowercase().replace("œ", "e").replace("æ", "e").replace("ç", "s")
    s = NonLettres.replace(s, "")
    if (s.isEmpty()) return ""
    for ((de, vers) in Regles) s = de.replace(s, vers)
    return s
}

private val NonLettres = Regex("[^a-zàâäéèêëîïôöùûüÿ]")

/** Les règles de l'écriture phonétique, dans l'ordre. */
private val Regles: List<Pair<Regex, String>> = listOf(
        "tz" to "s", "sch" to "S", "chr" to "kr", "ch" to "S", "ph" to "f", "th" to "t", "rh" to "r", "gn" to "N",
        "qu" to "k", "q" to "k", "ck" to "k", "cc(?=[eéèêiy])" to "ks", "c(?=[eéèêiîy])" to "s", "c" to "k",
        "gu(?=[eéèêiy])" to "G", "ge(?=[aoâôu])" to "j", "g(?=[eéèêiîy])" to "j", "w" to "U",
        "^h" to "", "(?<=[^cspt])h" to "",
        "eaux?" to "o", "aux?$" to "o", "au" to "o", "[oô]u" to "U", "[oô][iî]" to "ua", "oy" to "uai",
        "(?:ain|aim|ein|eim)(?![aeiouyéèênm])" to "I",
        "(?:ai|aî|ei|ay|ey)(?![nm][aeiouyéèê])" to "E", "[éèêë]" to "E",
        "(?:in|im|yn|ym|un|um)(?![aeiouyéèênm])" to "I",
        "(?:an|am|en|em)(?![aeiouyéèênm])" to "A", "(?:on|om)(?![aeiouyéèênm])" to "O",
        "[àâä]" to "a", "[îïy]" to "i", "[ôö]" to "o", "[ùûü]" to "u",
        "(?:er|ez|et)$" to "E", "e?[sxz]$" to "", "(?<=[^aeiouAEIOU])[td]$" to "", "e$" to "",
        "(?<=[aeiouAEIOU])s(?=[aeiouAEIOU])" to "z", "x" to "ks", "ts$" to "s",
        "e(?=[bcdfgjklmnpqrstvzS]{2}|[bcdfgjklmnpqrstvzS]$)" to "E", "e" to "",
        "G" to "g", "(.)\\1+" to "$1",
    ).map { (de, vers) -> Regex(de) to vers }

private fun sansAccents(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}"), "")

/** « Saint-Étienne » s'écrit « saintetienne » : sans accents, majuscules, traits d'union ni espaces. */
private fun ecrit(s: String) = sansAccents(s).lowercase().replace(Regex("[^a-z]"), "")

private fun distance(a: String, b: String): Int {
    var prec = IntArray(b.length + 1) { it }
    for (i in 1..a.length) {
        val cour = IntArray(b.length + 1)
        cour[0] = i
        for (j in 1..b.length) cour[j] = minOf(prec[j] + 1, cour[j - 1] + 1, prec[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
        prec = cour
    }
    return prec[b.length]
}

/** Ce qu'Evermind connaît : une ville (et sa population), un nom de tous les jours, ou un mot de l'agenda. */
class Entree(val texte: String, val sorte: Char, val population: Int = 0, val cle: String = phonetique(texte))

class Correcteur(private val entrees: List<Entree>, private val courants: Set<String> = emptySet()) {
    private val parCle = entrees.groupBy { it.cle }
    private val parEcrit = entrees.groupBy { ecrit(it.texte) }
    private val clesLongues = entrees.filter { it.cle.length >= 3 && (it.sorte != 'V' || it.population >= 10000) }

    /**
     * La phrase dictée, avec les noms corrigés. « perso » : les mots de l'agenda (titres, lieux, personnes), qui
     * comptent en premier.
     */
    fun corriger(dicte: String, perso: Collection<String> = emptyList()): String {
        // « Grenoble-Mardi » : le jour collé au nom par la dictée
        val texte = Regex("-(lundi|mardi|mercredi|jeudi|vendredi|samedi|dimanche)\\b", RegexOption.IGNORE_CASE).replace(dicte) { " " + it.groupValues[1].lowercase() }
        // (les mots de l'agenda qui sont des mots courants, comme « docteur », ne servent pas)
        val ajouts = perso.flatMap { decouper(it) }.distinct().filter { ecrit(it) !in courants }.map { Entree(it, 'P') }
        val persoCles = ajouts.groupBy { it.cle }
        val persoEcrits = ajouts.groupBy { ecrit(it.texte) }
        // Les mots de la phrase et leur place (« l'île » : « l » et « île »)
        val mots = Regex("[\\p{L}]+").findAll(texte).toList()
        val res = StringBuilder()
        var pos = 0
        var i = 0
        while (i < mots.size) {
            var fait = false
            for (n in minOf(4, mots.size - i) downTo 1) {
                val bloc = mots.subList(i, i + n)
                // (les mots d'un même nom se suivent sans ponctuation, sauf trait d'union ou apostrophe)
                if ((1 until n).any { k -> texte.substring(bloc[k - 1].range.last + 1, bloc[k].range.first).any { it !in " -'’" } }) continue
                val surface = texte.substring(bloc.first().range.first, bloc.last().range.last + 1)
                val e = trouver(surface, bloc.map { it.value }, texte.substring(0, bloc.first().range.first), persoCles, persoEcrits) ?: continue
                res.append(texte, pos, bloc.first().range.first).append(e)
                pos = bloc.last().range.last + 1
                i += n
                fait = true
                break
            }
            if (!fait) i++
        }
        return res.append(texte.substring(pos)).toString()
    }

    private fun trouver(surface: String, mots: List<String>, avant: String, perso: Map<String, List<Entree>>, persoEcrits: Map<String, List<Entree>>): String? {
        val bas = mots.map { sansAccents(it).lowercase() }
        if (bas.any { it in MotsDuTemps } || bas.first() in Outils && bas.first() !in Articles) return null
        // (« Noroto à » n'est pas un nom ; « besan son » peut l'être, s'il sonne exactement comme une ville)
        val finOutil = bas.last() in Outils
        if (finOutil && mots.size == 1) return null
        val cle = phonetique(surface)
        if (cle.length < 3) return null
        val ec = bas.joinToString("")
        val avantBas = avant.lowercase()
        val enLieu = RxLieu.containsMatchIn(avantBas)
        // « à le clair » n'est pas du français : la dictée a mal entendu un nom
        val incorrect = Regex("(?:^|\\s)(?:à|a)\\s*$").containsMatchIn(avantBas) && bas.first() in setOf("le", "les")
        val majuscule = surface.first().isUpperCase() && avant.trim().isNotEmpty() && !avant.trim().endsWith(".")
        val article = mots.size > 1 && bas.first() in Articles
        // Des mots courants (« la gare », « sur place », « messe ») : seulement une grande ville, à la place d'un lieu
        val courant = bas.filter { it !in Articles }.all { it in courants } && !majuscule
        fun garde(e: Entree) = if (e.sorte == 'M' && surface.first().isUpperCase()) e.texte.replaceFirstChar { it.uppercase() } else e.texte
        fun nouveau(e: Entree) = garde(e).takeIf { it != surface && !(e.sorte == 'P' && it.equals(surface, ignoreCase = true)) }

        // 1. La même écriture sans les accents ni les traits d'union (« Epinal », « Saint-Etienne », « Osteopathe »)
        if (!finOutil) for (e in persoEcrits[ec].orEmpty() + parEcrit[ec].orEmpty()) {
            val ok = when (e.sorte) {
                'P', 'M' -> true
                'E' -> enLieu || majuscule || incorrect
                else -> (majuscule && !article) || (enLieu && !courant && e.population >= 2000)
            }
            if (ok) return nouveau(e)
        }
        // 2. Le même son (« Oftalmologue », « de messe », « à le clair »)
        val memes = (perso[cle].orEmpty() + parCle[cle].orEmpty()).filter { e ->
            when (e.sorte) {
                'P' -> cle.length >= 4 && !courant
                'M' -> cle.length >= 5 && !courant && !article
                'E' -> (enLieu || majuscule) && (!courant || incorrect || mots.size == 1 && e.texte.length > 3) && (!article || incorrect)
                else -> enLieu && e.population >= 2000 && (!article || incorrect) && (!courant || e.population >= 20000)
            } && (!finOutil || (e.sorte == 'V' || e.sorte == 'E') && enLieu)
        }
        memes.sortedWith(compareBy<Entree> { it.sorte != 'P' }.thenByDescending { it.population }).firstOrNull()?.let { return nouveau(it) }
        // L'apostrophe avalée (« Lurologue », « Dermatologue » collé) : « l'urologue »
        if (mots.size == 1 && surface.length >= 6 && surface[0].lowercaseChar() in "ld") {
            val reste = phonetique(surface.substring(1))
            parCle[reste].orEmpty().firstOrNull { it.sorte == 'M' }?.let { return surface[0].lowercaseChar() + "'" + it.texte }
        }
        // 3. Presque le même son, pour un nom assez long ou une grande ville à sa place (« Fortback », « à Hausser »)
        if (courant || article || finOutil) return null
        val proches = (perso.values.flatten() + clesLongues).filter { e ->
            val d = if (kotlin.math.abs(e.cle.length - cle.length) > 2) 9 else distance(e.cle, cle)
            when (e.sorte) {
                'P' -> (d <= 1 && cle.length >= 6) || (d <= 2 && cle.length >= 9)
                'M' -> (d <= 1 && cle.length >= 8) || (d <= 2 && cle.length >= 11)
                'E' -> (enLieu || majuscule) && d <= 1 && cle.length >= 5
                else -> enLieu && ((d <= 1 && (cle.length >= 5 || e.population >= 30000 && majuscule)) || (d <= 2 && cle.length >= 6 && e.population >= 10000))
            }
        }
        return proches.minWithOrNull(compareBy<Entree> { if (it.sorte == 'P') -1 else 0 }.thenBy { distance(it.cle, cle) }.thenByDescending { it.population })?.let { nouveau(it) }
    }

    companion object {
        /** Juste avant une ville : « à », « de », « vers », « pour », « près de »... */
        private val RxLieu = Regex("(?:^|\\s)(?:à|a|au|de|d'|du|vers|pour|sur|près de|pres de|direction|depuis|jusqu'à|habite|chez)\\s*$")
        private val Articles = setOf("le", "la", "les", "l", "un", "une", "des", "du", "de", "d", "mon", "ma", "mes", "ce", "cette")
        /** Des petits mots qui ne finissent jamais un nom (« Noroto à » n'est pas un nom). */
        private val Outils = setOf("a", "au", "aux", "de", "du", "d", "le", "la", "les", "l", "et", "ou", "en", "sur", "sous", "chez", "pour", "avec",
            "un", "une", "des", "mon", "ma", "mes", "ton", "ta", "tes", "son", "sa", "ses", "ce", "cette", "par", "dans", "vers", "que", "qui", "je", "tu",
            "il", "elle", "on", "nous", "vous", "me", "te", "se", "y", "ne", "pas", "plus")
        private val MotsDuTemps = setOf("lundi", "mardi", "mercredi", "jeudi", "vendredi", "samedi", "dimanche", "janvier", "fevrier", "mars", "avril",
            "mai", "juin", "juillet", "aout", "septembre", "octobre", "novembre", "decembre", "demain", "aujourd", "hui", "matin", "soir", "midi", "minuit",
            "heure", "heures", "h", "semaine", "mois", "an", "ans", "prochain", "prochaine", "dernier", "avant", "apres", "veille", "rendez", "vous")

        /** Les mots d'un texte de l'agenda (« Ophtalmologue à Woippy » : Ophtalmologue, Woippy). */
        fun decouper(t: String) = Regex("[\\p{L}][\\p{L}'-]*").findAll(t).map { it.value }.filter { it.length >= 4 && sansAccents(it).lowercase() !in MotsDuTemps }.toList()

        /**
         * Le lexique d'Evermind : les villes (« Metz;122572;messe|mets » : nom, population, façons de le dire), les noms de
         * tous les jours (« ophtalmologue », « E:Leclerc;leclère » pour une enseigne), et les mots courants du français.
         */
        fun lire(villes: Sequence<String>, noms: Sequence<String>, mots: Sequence<String> = emptySequence()): Correcteur {
            val l = ArrayList<Entree>(36000)
            for (ligne in villes) {
                val p = ligne.split(';')
                if (p[0].isBlank()) continue
                val pop = p.getOrNull(1)?.toIntOrNull() ?: 0
                val dits = p.getOrNull(2)?.split('|')?.filter { it.isNotBlank() }.orEmpty().ifEmpty { listOf(p[0]) }
                for (d in dits) l += Entree(p[0], 'V', pop, phonetique(d))
            }
            for (ligne in noms) {
                val x = ligne.trim()
                if (x.isEmpty() || x.startsWith("#")) continue
                val sorte = if (x.startsWith("E:")) 'E' else 'M'
                val p = x.removePrefix("E:").split(';')
                val dits = p.getOrNull(1)?.split('|')?.filter { it.isNotBlank() }.orEmpty().ifEmpty { listOf(p[0]) }
                for (d in dits) l += Entree(p[0], sorte, 0, phonetique(d))
            }
            return Correcteur(l, mots.map { sansAccents(it.trim()).lowercase() }.filter { it.isNotEmpty() }.toSet())
        }

        @Volatile private var charge: Correcteur? = null

        /** Le lexique s'il est déjà prêt (sinon la phrase reste telle quelle). */
        fun siPret(): Correcteur? = charge

        /**
         * Le lexique du téléphone (assets/lexique), lu une fois. Les sons des 35 000 noms sont calculés à la première
         * fois après l'installation (quelques secondes), puis gardés dans les fichiers d'Evermind.
         */
        fun du(contexte: Context): Correcteur = charge ?: synchronized(this) {
            charge ?: run {
                val a = contexte.assets
                val m = a.open("lexique/mots.txt").bufferedReader().readLines()
                val version = contexte.packageManager.getPackageInfo(contexte.packageName, 0).lastUpdateTime
                val cache = java.io.File(contexte.filesDir, "lexique/sons-$version.txt")
                val pret = runCatching {
                    if (!cache.exists()) null
                    else Correcteur(cache.readLines().mapNotNull { l ->
                        val p = l.split('\t')
                        if (p.size == 4) Entree(p[3], p[0][0], p[1].toInt(), p[2]) else null
                    }, m.map { sansAccents(it.trim()).lowercase() }.toSet())
                }.getOrNull()
                pret ?: lire(
                    a.open("lexique/communes.txt").bufferedReader().readLines().asSequence(),
                    a.open("lexique/noms.txt").bufferedReader().readLines().asSequence(),
                    m.asSequence(),
                ).also { c ->
                    runCatching {
                        cache.parentFile?.listFiles()?.forEach { it.delete() }
                        cache.parentFile?.mkdirs()
                        cache.writeText(c.entrees.joinToString("\n") { "${it.sorte}\t${it.population}\t${it.cle}\t${it.texte}" })
                    }
                }
            }.also { charge = it }
        }
    }
}
