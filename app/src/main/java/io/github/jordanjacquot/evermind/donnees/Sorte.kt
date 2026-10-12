package io.github.jordanjacquot.evermind.donnees

/**
 * Sortes de rendez-vous, les mêmes qu'Evermind PC ($Types dans comprendre.ps1) :
 * même code dans le fichier, même nom, même icône et même couleur par défaut.
 */
enum class Sorte(val code: String, val nom: String, val icone: String, val couleur: Long) {
    ANNIV("anniv", "Anniversaire", "🎂", 0xFFFF2EB5),
    SANTE("sante", "Santé", "💊", 0xFF3DDC97),
    VOYAGE("voyage", "Voyage", "✈", 0xFF5EEAD4),
    TRAVAIL("travail", "Travail / école", "💼", 0xFF4EA8FF),
    SPORT("sport", "Sport", "⚽", 0xFF9BE15D),
    JEU("jeu", "Jeu", "🎮", 0xFFB04BFF),
    SORTIE("sortie", "Sortie", "🎉", 0xFFFFB547),
    A_FAIRE("rappel", "À faire", "🔔", 0xFFFFD84D),
    RDV("rdv", "Rendez-vous", "📅", 0xFF00E5FF);

    companion object {
        /** Une sorte inconnue (fichier d'une autre version) devient un simple rendez-vous. */
        fun depuisCode(code: String?): Sorte = entries.firstOrNull { it.code == code } ?: RDV
    }
}
