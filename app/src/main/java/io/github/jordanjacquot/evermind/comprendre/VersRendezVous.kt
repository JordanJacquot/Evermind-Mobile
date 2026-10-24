package io.github.jordanjacquot.evermind.comprendre

import io.github.jordanjacquot.evermind.donnees.APreparer
import io.github.jordanjacquot.evermind.donnees.RendezVous
import io.github.jordanjacquot.evermind.donnees.Sorte
import java.time.temporal.ChronoUnit

/** Le titre tel qu'on l'affiche : « avec monsieur Richard » (dictée) devient « Avec Monsieur Richard » (Get-TitreAffiche). */
fun titreAffiche(titre: String): String {
    if (titre.isBlank()) return "Rendez-vous"
    val t = titre.remplace("\\b(monsieur|madame|mademoiselle|docteur|ma[iî]tre)(?=\\s+\\p{Lu})", ci = false) { m ->
        m.valeur.replaceFirstChar { it.uppercaseChar() }
    }
    return t.replaceFirstChar { it.uppercaseChar() }
}

/** Ce qu'il faut dire quand la phrase ne suffit pas (les mêmes mots que sur PC). */
val Manque.message: String
    get() = when (this) {
        Manque.RIEN -> ""
        Manque.HEURE -> "Il me manque l'heure. Ajoute par exemple « à 15 h »."
        Manque.JOUR -> "Il me manque le jour. Ajoute par exemple « le 12 mars »."
        Manque.QUAND -> "Il me manque le jour et l'heure. Ajoute par exemple « demain à 15 h »."
        Manque.PASSE -> "Ce moment est déjà passé. Choisis un autre jour ou une autre heure."
        Manque.INVALIDE -> "Cette date n'existe pas. Vérifie le jour et le mois."
        Manque.MATIN -> "Le matin ou le soir ? Ajoute « du matin » ou « du soir » après l'heure."
    }

/**
 * Les rendez-vous à créer pour cette phrase : un seul le plus souvent, un par jour pour des jours séparés (« les 3 et
 * 10 novembre »), un par semaine pour des semaines séparées (« la première semaine des 3 prochains mois »).
 * Vide s'il manque quelque chose.
 */
fun Phrase.versRendezVous(): List<RendezVous> {
    val q = quand
    if (manque != Manque.RIEN || q == null) return emptyList()
    val base = RendezVous(
        titre = titreAffiche(titre),
        sorte = Sorte.depuisCode(sorte),
        quand = q,
        journee = journee,
        fin = fin,
        repetition = repetition?.versJson(),
        lieu = lieu,
        avec = avec,
        lien = lien,
        aPreparer = aPreparer.map { APreparer(it) },
        rappels = rappels,
        important = important,
        naissance = naissance,
    )
    return when {
        jours.size > 1 -> jours.map { j ->
            val decalage = ChronoUnit.DAYS.between(q.toLocalDate(), j)
            base.copy(id = java.util.UUID.randomUUID().toString(), quand = q.plusDays(decalage), fin = fin?.plusDays(decalage))
        }
        blocs.size > 1 -> blocs.map { b ->
            val debut = b.debut.atTime(q.toLocalTime())
            val finBloc = fin?.let { b.fin.atTime(it.toLocalTime()) }
            base.copy(id = java.util.UUID.randomUUID().toString(), quand = debut, fin = finBloc)
        }
        else -> listOf(base)
    }
}
