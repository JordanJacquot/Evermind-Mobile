package io.github.jordanjacquot.evermind.ui

import io.github.jordanjacquot.evermind.donnees.FormatDate
import java.net.URLEncoder
import java.time.LocalDateTime

// Les adresses des écrans (Navigation.kt). Tout ce qu'on y met est codé : un « / » dans un identifiant ne doit pas
// changer d'écran (ni faire planter l'appli), et une phrase garde ses espaces.

/**
 * Un morceau d'adresse codé comme le décode la navigation d'Android (Uri.decode) : les espaces en « %20 ».
 * (URLEncoder seul les écrit « + », que Uri.decode laisse tels quels : « garagiste+lundi ».)
 */
fun encoderArgument(texte: String): String = URLEncoder.encode(texte, "UTF-8").replace("+", "%20")

/** La fiche d'un rendez-vous, sur une fois précise (une chose qui revient) ou non. */
fun routeFiche(id: String, fois: LocalDateTime? = null): String =
    "fiche/" + encoderArgument(id) + (fois?.let { "?fois=" + encoderArgument(it.format(FormatDate)) } ?: "")

/** Le formulaire prérempli par une phrase (« Compléter »). */
fun routeCompleter(phrase: String): String = "formulaire?phrase=" + encoderArgument(phrase)

/** Le formulaire d'un rendez-vous à modifier. */
fun routeModifier(id: String): String = "formulaire?id=" + encoderArgument(id)

/**
 * La fiche demandée par une autre appli ou une notification (l'écran d'Evermind est ouvert à toutes les applis du
 * téléphone) : seulement un identifiant de rendez-vous (lettres, chiffres, tirets), sinon rien.
 */
fun ficheValide(texte: String?): String? = texte?.takeIf { Regex("[0-9A-Za-z-]{1,64}").matches(it) }

/** La fois rappelée (« 2026-10-13T19:00 »), si elle se lit. */
fun foisValide(texte: String?): LocalDateTime? = texte?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }

/** Une fiche à ouvrir (« Voir » dans un rappel). */
data class FicheDemandee(val id: String, val fois: LocalDateTime?)
