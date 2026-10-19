// L'assistant d'Evermind Mobile (les règles, les contrôles, les actions et le modèle entraîné) pour Evermind PC :
// les mêmes fichiers que l'app, compilés sans Android en un programme que le PC appelle (docs/ASSISTANT-PC.md).
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

// (les fichiers de l'app qui ne dépendent pas d'Android ; le reste est dans src/main/kotlin)
val communs = listOf(
    "io/github/jordanjacquot/evermind/assistant/Actions.kt",
    "io/github/jordanjacquot/evermind/assistant/AgendaIA.kt",
    "io/github/jordanjacquot/evermind/assistant/Analyse.kt",
    "io/github/jordanjacquot/evermind/assistant/Assistant.kt",
    "io/github/jordanjacquot/evermind/assistant/Consignes.kt",
    "io/github/jordanjacquot/evermind/assistant/Horaires.kt",
    "io/github/jordanjacquot/evermind/assistant/Ordres.kt",
    "io/github/jordanjacquot/evermind/assistant/ReponseIA.kt",
    "io/github/jordanjacquot/evermind/comprendre/*.kt",
    "io/github/jordanjacquot/evermind/donnees/RendezVous.kt",
    "io/github/jordanjacquot/evermind/donnees/Sorte.kt",
    "io/github/jordanjacquot/evermind/donnees/Fois.kt",
    "io/github/jordanjacquot/evermind/ui/Textes.kt",
)

sourceSets {
    main {
        kotlin {
            srcDir("../app/src/main/java")
            include(communs)
            include("io/github/jordanjacquot/evermind/pc/**", "io/github/jordanjacquot/evermind/assistant/MarqueCache.kt")
        }
        resources {
            srcDir("../app/src/main/resources")
            include("assistant/**")
        }
    }
}

kotlin { jvmToolchain(25) }

dependencies {
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.room.common)   // (les annotations de RendezVous)
}

application {
    mainClass.set("io.github.jordanjacquot.evermind.pc.PrincipalKt")
    applicationName = "evermind-assistant"
}
