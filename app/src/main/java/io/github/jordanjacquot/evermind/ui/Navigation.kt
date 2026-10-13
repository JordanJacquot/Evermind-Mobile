package io.github.jordanjacquot.evermind.ui

import androidx.compose.runtime.Composable
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
import java.time.LocalDate

/** Les écrans de l'app et comment on passe de l'un à l'autre. */
@Composable
fun EvermindNavigation() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = "agenda") {
        composable("agenda") {
            AgendaEcran(
                ouvrirFiche = { id -> nav.navigate("fiche/$id") },
                ajouter = { jour -> nav.navigate("formulaire?jour=${jour ?: ""}") },
                completer = { phrase -> nav.navigate("formulaire?phrase=${URLEncoder.encode(phrase, "UTF-8")}") },
                reglages = { nav.navigate("reglages") },
            )
        }
        composable("reglages") { ReglagesEcran(retour = { nav.popBackStack() }) }
        composable("fiche/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { entree ->
            val id = entree.arguments?.getString("id").orEmpty()
            FicheEcran(
                id = id,
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
