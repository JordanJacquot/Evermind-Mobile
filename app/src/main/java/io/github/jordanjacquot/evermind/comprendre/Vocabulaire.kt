package io.github.jordanjacquot.evermind.comprendre

import java.time.LocalDate
import java.time.YearMonth

// Les mots des dates et des heures, les mêmes que dans comprendre.ps1

internal val MoisNoms: Map<String, Int> = linkedMapOf(
    "janvier" to 1, "février" to 2, "fevrier" to 2, "mars" to 3, "avril" to 4, "mai" to 5, "juin" to 6, "juillet" to 7,
    "août" to 8, "aout" to 8, "septembre" to 9, "octobre" to 10, "novembre" to 11, "décembre" to 12, "decembre" to 12,
    // abréviations (« le 25 oct. », « 2 févr ») : après les noms entiers, qui passent en premier
    "janv" to 1, "févr" to 2, "fevr" to 2, "fév" to 2, "fev" to 2, "avr" to 4, "juil" to 7, "sept" to 9, "oct" to 10,
    "nov" to 11, "déc" to 12, "dec" to 12,
)

/** Numéros des jours comme Evermind PC (et .NET) : dimanche = 0, lundi = 1... samedi = 6. */
internal val JoursNoms: Map<String, Int> = linkedMapOf(
    "lundi" to 1, "mardi" to 2, "mercredi" to 3, "jeudi" to 4, "vendredi" to 5, "samedi" to 6, "dimanche" to 0,
)

/** Nombres en lettres (la dictée peut écrire « trois » au lieu de « 3 »). */
internal val Nombres: Map<String, Int> = buildMap {
    put("une", 1); put("premier", 1); put("1er", 1)
    val unites = listOf("zéro", "un", "deux", "trois", "quatre", "cinq", "six", "sept", "huit", "neuf", "dix", "onze", "douze",
        "treize", "quatorze", "quinze", "seize", "dix-sept", "dix-huit", "dix-neuf")
    for (i in 1 until 20) { put(unites[i], i); put(unites[i].replace('-', ' '), i) }
    for ((dz, v) in listOf("vingt" to 20, "trente" to 30, "quarante" to 40, "cinquante" to 50)) {
        put(dz, v)
        put("$dz et un", v + 1); put("$dz-et-un", v + 1)
        for (u in 2..9) { put("$dz-${unites[u]}", v + u); put("$dz ${unites[u]}", v + u) }
    }
}

internal val RxNombre = "(?:\\d{1,2}|" + Nombres.keys.sortedByDescending { it.length }.joinToString("|") + ")"
/** Jamais au milieu d'un mot (« mai » dans « maintenant »). */
internal val RxMois = "(?:" + MoisNoms.keys.joinToString("|") + ")(?![\\p{L}])"
/** Les mêmes noms, sans ce qui suit (pour regarder en arrière). */
internal val RxMoisNu = "(?:" + MoisNoms.keys.joinToString("|") + ")"
internal val RxJour = "(?:" + JoursNoms.keys.joinToString("|") + ")"
internal const val RxMinMot = "(?:cinq|dix|quinze|vingt|vingt-cinq|trente|trente-cinq|quarante|quarante-cinq|cinquante|cinquante-cinq)"
internal const val RxFrac = "(?:et demie?|et quart|moins le quart|moins quart)"

// Ce qu'on dit autour du rendez-vous et qui n'en fait pas partie : politesse, demande, ordre
internal const val RxPolitesse = "(?:(?:ok|okay|o\\.?k\\.?|hey|h[ée])[\\s,]*(?:[eé]ver\\s*-?\\s*m[aeiï]+n[de]?|[èe]ve|eva)\\b|euh+|alors|ok|okay|bon|dis|hey|evermind|s'il te pla[îi]t|s'il vous pla[îi]t|stp|svp|merci)"
internal const val RxDemande = "(?:je dois|il faut que je|faut que je|j'ai besoin de|je ne dois pas oublier de|n'oublie pas que je dois|est-ce que tu (?:peux|pourrais|veux bien)|est-ce que je peux|tu (?:peux|pourrais|veux bien)|peux-tu|pourrais-tu|je (?:voudrais|veux|souhaite)|j'aimerais|il faut que tu|il faut|faut)"
internal const val RxOrdre = "(?:cr[ée]{1,2}[rsz]?|ajoute[rsz]?|rajoute[rsz]?|met[st]?|mettes?|mettre|note[rsz]?|programme[rsz]?|planifie[rsz]?|enregistre[rsz]?|cale[rsz]?|rappelle[s]?[- ]moi|fais[- ]moi penser|pr[ée]viens[- ]moi|pense[rsz]? [àa]|n'oublie pas)"
internal const val RxGenre = "(?:rendez[- ]vous|rdv|[ée]v[èée]nement|event|rappel|truc)"

/** Les mots de chaque sorte, dans l'ordre : la première sorte dont un mot est dans le titre l'emporte. */
internal val MotsDesSortes: List<Pair<String, String>> = listOf(
    "anniv" to "anniv(?:ersaires?)?|birthday",
    "sante" to "m[ée]decin|docteur|doc|dentiste|kin[ée](?:sith[ée]rapeute)?|ophtalmo(?:logue)?|dermato(?:logue)?|gyn[ée]co(?:logue)?|psy(?:chologue|chiatre)?|orthodontiste|ost[ée]o(?:pathe)?|infirmi[èe]re?|h[ôo]pital|clinique|radio|prise de sang|vaccin|pharmacie|labo(?:ratoire)?|analyses?|ordonnance|m[ée]dicaments?|cachets?|sant[ée]|urgences",
    "voyage" to "voyage|vacances|train|avion|vol|a[ée]roport|gare|valise|h[ôo]tel|road ?trip|week-end [àa]",
    "travail" to "travail|boulot|taf|r[ée]union|meeting|entretien|rendu|deadline|cours|[ée]cole|fac|lyc[ée]e|coll[èe]ge|examen|exam|partiel|devoirs?|bureau|client|formation|stage|pr[ée]sentation",
    "sport" to "foot(?:ball)?|sport|salle|muscu(?:lation)?|piscine|natation|course|running|footing|jogging|v[ée]lo|basket|tennis|badminton|padel|yoga|boxe|judo|karat[ée]|danse|entra[îi]nement|match|rando(?:nn[ée]e)?|escalade|ski",
    "jeu" to "raid|donjon|tournoi|partie|game|games|jeux?|lan|stream|ranked|class[ée]e?s?|session|valo(?:rant)?|lol|fortnite|dofus|minecraft|warzone|apex|wow",
    "sortie" to "soir[ée]e|f[êe]te|resto|restaurant|cin[ée](?:ma)?|concert|ap[ée]ro|bar|bo[îi]te|mariage|bapt[êe]me|barbecue|bbq|pique-nique|repas|d[îi]ner|d[ée]jeuner|brunch|sortie|festival|spectacle|th[ée][âa]tre|expo(?:sition)?|mus[ée]e|salon",
    "rappel" to "appeler|t[ée]l[ée]phoner|payer|acheter|envoyer|poster|rendre|r[ée]cup[ée]rer|chercher|sortir|poubelles?|courses|penser|faire|r[ée]server|renouveler|imprimer|laver|ranger|arroser|virement|facture|loyer|imp[ôo]ts?",
)

internal fun nombre(texte: String?): Int? {
    if (texte == null) return null
    val t = texte.trim().lowercase().replace(Regex("\\s+"), " ")
    if (t.isNotEmpty() && t.all { it.isDigit() }) return t.toIntOrNull()
    return Nombres[t]
}

internal fun mois(nom: String): Int = MoisNoms.getValue(nom.lowercase())
internal fun jourSemaine(nom: String): Int = JoursNoms.getValue(nom.lowercase())

/** Une date qui existe vraiment (pas de 31 février), sinon null. */
internal fun dateSure(y: Int, m: Int, d: Int): LocalDate? {
    if (m !in 1..12 || y !in 1..9998 || d < 1 || d > YearMonth.of(y, m).lengthOfMonth()) return null
    return LocalDate.of(y, m, d)
}

/** Le numéro du jour comme Evermind PC : dimanche = 0. */
internal val LocalDate.jds: Int get() = dayOfWeek.value % 7

internal val LocalDate.joursDuMois: Int get() = lengthOfMonth()
internal val LocalDate.premierDuMois: LocalDate get() = withDayOfMonth(1)
internal val LocalDate.dernierDuMois: LocalDate get() = withDayOfMonth(lengthOfMonth())
internal fun lundiDe(d: LocalDate): LocalDate = d.minusDays(((d.jds + 6) % 7).toLong())
