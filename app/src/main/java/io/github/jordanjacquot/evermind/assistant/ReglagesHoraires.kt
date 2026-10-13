package io.github.jordanjacquot.evermind.assistant

import android.content.Context
import java.time.LocalTime

/**
 * Les heures de chaque poste de travail (Réglages, ou « mon poste du matin, c'est de 7 h à 15 h »), comme le réglage
 * « Horaires » d'Evermind PC : chacun a les siens. Ce qui n'est pas réglé garde les heures habituelles.
 */
class ReglagesHoraires(contexte: Context) {
    private val prefs = contexte.getSharedPreferences("horaires", Context.MODE_PRIVATE)

    /** Les heures de tous les postes (« 6 h à 14 h »). */
    fun tous(): Map<String, String> = HorairesDefaut.mapValues { (c, defaut) -> prefs.getString(c, null) ?: defaut }

    fun changer(creneau: String, debut: LocalTime, fin: LocalTime) {
        prefs.edit().putString(creneau, ecrireHeures(debut, fin)).apply()
    }

    /** Les heures habituelles pour tous les postes. */
    fun remettre() { prefs.edit().clear().apply() }
}
