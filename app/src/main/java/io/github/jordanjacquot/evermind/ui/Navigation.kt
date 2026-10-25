package io.github.jordanjacquot.evermind.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.jordanjacquot.evermind.ui.agenda.AgendaEcran
import io.github.jordanjacquot.evermind.ui.fiche.FicheEcran
import io.github.jordanjacquot.evermind.ui.formulaire.FormulaireEcran
import io.github.jordanjacquot.evermind.ui.reglages.ReglagesEcran
import java.net.URLEncoder
import io.github.jordanjacquot.evermind.donnees.FormatDate
import java.time.LocalDate
import java.time.LocalDateTime

/** Les écrans de l'app et comment on passe de l'un à l'autre. */
@Composable
fun EvermindNavigation(ficheDemandee: String? = null, ficheOuverte: () -> Unit = {}) {
    val nav = rememberNavController()
    LaunchedEffect(ficheDemandee) {
        if (ficheDemandee != null) { nav.navigate("fiche/$ficheDemandee"); ficheOuverte() }
    }
    NavHost(navController = nav, startDestination = "agenda") {
        composable("agenda") {
            AgendaEcran(
                ouvrirFiche = { id, fois -> nav.navigate("fiche/$id" + (fois?.let { "?fois=${it.format(FormatDate)}" } ?: "")) },
                ajouter = { jour -> nav.navigate("formulaire?jour=${jour ?: ""}") },
                completer = { phrase -> nav.navigate("formulaire?phrase=${URLEncoder.encode(phrase, "UTF-8")}") },
                reglages = { nav.navigate("reglages") },
            )
        }
        composable("reglages") { ReglagesEcran(retour = { nav.popBackStack() }) }
        composable(
            "fiche/{id}?fois={fois}",
            arguments = listOf(
                navArgument("id") { type = NavType.StringType },
                // (la fois touchée dans l'agenda, pour une chose qui revient : « C'est fait » coche celle-là)
                navArgument("fois") { type = NavType.StringType; nullable = true; defaultValue = null },
            ),
        ) { entree ->
            val id = entree.arguments?.getString("id").orEmpty()
            FicheEcran(
                id = id,
                fois = entree.arguments?.getString("fois")?.let { runCatching { LocalDateTime.parse(it, FormatDate) }.getOrNull() },
                retour = { nav.popBackStack() },
                modifier = { nav.navigate("formulaire?id=$id") },
            )
        }
        composable(
            "formulaire?id={id}&jour={jour}&phrase={phrase}",
            arguments = listOf(
                navArgument("id") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("jour") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("phrase") { type = NavType.StringType; nullable = true; defaultValue = null },
            ),
        ) { entree ->
            FormulaireEcran(
                id = entree.arguments?.getString("id"),
                jour = entree.arguments?.getString("jour")?.takeIf { it.isNotBlank() }?.let(LocalDate::parse),
                phrase = entree.arguments?.getString("phrase"),
                retour = { nav.popBackStack() },
            )
        }
    }
}
