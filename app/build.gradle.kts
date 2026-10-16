import java.net.URI
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "io.github.jordanjacquot.evermind"
    compileSdk = 37
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "io.github.jordanjacquot.evermind"
        minSdk = 26          // Android 8 : java.time et les canaux de notification sans bibliothèque en plus
        targetSdk = 36
        versionCode = 4
        versionName = "0.2.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Téléphones (arm) et émulateur du PC (x86_64) : la voix n'existe pas pour les autres
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
        // L'assistant (llama.cpp) : téléphones 64 bits et émulateur seulement
        externalNativeBuild {
            cmake {
                abiFilters += listOf("arm64-v8a", "x86_64")
                arguments += "-DCMAKE_BUILD_TYPE=Release"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }

    // Les bibliothèques sont sorties de l'APK à l'installation : llama.cpp y cherche la variante du processeur
    // la plus rapide pour ce téléphone (libggml-cpu-*.so)
    packaging {
        jniLibs { useLegacyPackaging = true }
    }

    // Ce qui sert aux tests sur PC et sur le téléphone : la comparaison avec Evermind PC et ses phrases
    sourceSets {
        getByName("test") {
            kotlin.directories += "src/sharedTest/java"
            resources.srcDir("src/sharedTest/resources")
        }
        getByName("androidTest") {
            kotlin.directories += "src/sharedTest/java"
            assets.srcDir("src/sharedTest/resources")
        }
    }
}

// sherpa-onnx (Whisper, Kokoro, détection de la parole) n'est pas sur Maven : son .aar est téléchargé une fois depuis
// sa page GitHub, à la même version que sur PC, et son empreinte est vérifiée
val sherpaOnnx: File = rootProject.file(".bibliotheques/sherpa-onnx-1.13.8.aar").also { aar ->
    val empreinte = "633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96"
    fun sha256(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
    if (!aar.exists() || sha256(aar) != empreinte) {
        aar.parentFile.mkdirs()
        val tmp = File(aar.path + ".part")
        URI("https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar").toURL().openStream().use { e -> tmp.outputStream().use { e.copyTo(it) } }
        check(sha256(tmp) == empreinte) { "sherpa-onnx : empreinte inattendue, fichier refusé" }
        tmp.renameTo(aar)
    }
}

// Le schéma de la base est gardé dans le dépôt : il sert à écrire les migrations
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.kotlinx.serialization.json)

    // La voix : sherpa-onnx (Whisper, Kokoro), Vosk (« OK Evermind »), et de quoi ouvrir les archives des modèles
    implementation(files(sherpaOnnx))
    implementation(libs.vosk.android)
    implementation(libs.commons.compress)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
}
