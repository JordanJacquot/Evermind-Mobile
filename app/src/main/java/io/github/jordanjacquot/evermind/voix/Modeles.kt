package io.github.jordanjacquot.evermind.voix

import android.content.Context
import java.io.File

/**
 * Les modèles de la voix, téléchargés une seule fois (« Installer la voix »), comme sur PC : versions et empreintes
 * fixées, rien d'autre n'est jamais envoyé ni reçu. Ce sont les mêmes qu'Evermind PC, en version allégée (int8)
 * pour le téléphone.
 *   « voix » : la dictée (Whisper small), la détection de la fin d'une phrase (Silero) et la voix d'Evermind (Kokoro)
 *   « eveil » : l'écoute de « OK Evermind » (Vosk, petit modèle français)
 */
enum class Pack(val nom: String) {
    VOIX("la voix"), EVEIL("« OK Evermind »"),
    // L'assistant : un seul, choisi selon la mémoire du téléphone (assistant/Cerveau.kt)
    IA_EVERMIND("l'assistant"), IA_GRAND("l'assistant"), IA_MOYEN("l'assistant"), IA_LEGER("l'assistant léger"),
}

/** Comment un fichier téléchargé est rangé : tel quel, ou une archive dont on garde le contenu (sans son premier dossier). */
enum class Forme { FICHIER, TAR_BZ2, ZIP }

data class FichierModele(
    val pack: Pack,
    val nom: String,
    val url: String,
    val sha256: String,
    val taille: Long,
    val forme: Forme,
    /** Où il va, dans le dossier de la voix : le fichier, ou le dossier où l'archive est ouverte. */
    val vers: String,
    /** Dans une archive : seulement ce qui commence par l'un de ces chemins (après le premier dossier). */
    val garder: List<String> = emptyList(),
    /** Le fichier qui montre que c'est installé. */
    val temoin: String = vers,
)

object Modeles {
    private const val HF_WHISPER = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-small/resolve/8f3c18b358db4d1f2fc1eae49d75cd20989e4309"

    val fichiers = listOf(
        FichierModele(Pack.VOIX, "dictée (Whisper)", "$HF_WHISPER/small-encoder.int8.onnx",
            "4cbe7b22fa9026b843b60a68640c747de05bafb1a11b57edc0e66c232d9f33a9", 112442483, Forme.FICHIER, "whisper/encoder.int8.onnx"),
        FichierModele(Pack.VOIX, "dictée (Whisper)", "$HF_WHISPER/small-decoder.int8.onnx",
            "acad50b5c782696e91b55914cc5ab4f756f1532f76e22aa6fc615f39fb69a8ee", 262226114, Forme.FICHIER, "whisper/decoder.int8.onnx"),
        FichierModele(Pack.VOIX, "dictée (Whisper)", "$HF_WHISPER/small-tokens.txt",
            "b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126", 816730, Forme.FICHIER, "whisper/tokens.txt"),
        FichierModele(Pack.VOIX, "fin de phrase (Silero)", "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad_v5.onnx",
            "6b99cbfd39246b6706f98ec13c7c50c6b299181f2474fa05cbc8046acc274396", 2313101, Forme.FICHIER, "silero_vad_v5.onnx"),
        FichierModele(Pack.VOIX, "voix d'Evermind (Kokoro)", "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-int8-multi-lang-v1_0.tar.bz2",
            "4c3052abaa60943a341f193888cf6abd68787dae6ab8ae5c925a706caa247e4e", 132303094, Forme.TAR_BZ2, "kokoro",
            garder = listOf("model.int8.onnx", "voices.bin", "tokens.txt", "espeak-ng-data/", "LICENSE"), temoin = "kokoro/model.int8.onnx"),
        // (entraîné pour Evermind par outils/entrainer, publié dans les versions du projet)
        FichierModele(Pack.IA_EVERMIND, "assistant (Evermind 0.6B)",
            "https://github.com/JordanJacquot/Evermind-Mobile/releases/download/assistant-v1/evermind-0.6b-q8_0.gguf",
            "23343a94c57b86f41921f68e96d0edb1b2504d78933fdb1efd2e56aef292afb0", 639446880, Forme.FICHIER, "assistant/evermind-0.6b-q8_0.gguf"),
        FichierModele(Pack.IA_GRAND, "assistant (Qwen3 4B, comme sur PC)",
            "https://huggingface.co/unsloth/Qwen3-4B-Instruct-2507-GGUF/resolve/a06e946bb6b655725eafa393f4a9745d460374c9/Qwen3-4B-Instruct-2507-Q4_K_M.gguf",
            "3605803b982cb64aead44f6c1b2ae36e3acdb41d8e46c8a94c6533bc4c67e597", 2497281120, Forme.FICHIER, "assistant/Qwen3-4B-Instruct-2507-Q4_K_M.gguf"),
        FichierModele(Pack.IA_MOYEN, "assistant (Qwen3.5 2B)",
            "https://huggingface.co/unsloth/Qwen3.5-2B-GGUF/resolve/f6d5376be1edb4d416d56da11e5397a961aca8ae/Qwen3.5-2B-Q4_K_M.gguf",
            "aaf42c8b7c3cab2bf3d69c355048d4a0ee9973d48f16c731c0520ee914699223", 1280835840, Forme.FICHIER, "assistant/Qwen3.5-2B-Q4_K_M.gguf"),
        FichierModele(Pack.IA_LEGER, "assistant léger (Qwen3.5 0.8B)",
            "https://huggingface.co/unsloth/Qwen3.5-0.8B-GGUF/resolve/6ab461498e2023f6e3c1baea90a8f0fe38ab64d0/Qwen3.5-0.8B-Q4_K_M.gguf",
            "bd258782e35f7f458f8aced1adc053e6e92e89bc735ba3be89d38a06121dc517", 532517120, Forme.FICHIER, "assistant/Qwen3.5-0.8B-Q4_K_M.gguf"),
        FichierModele(Pack.EVEIL, "écoute de « OK Evermind » (Vosk)", "https://alphacephei.com/vosk/models/vosk-model-small-fr-0.22.zip",
            "cabf6180e177eb9b3a9a9d43a437bd5e549f3a7d09525e5d69a3fed787be12ad", 42233323, Forme.ZIP, "vosk", temoin = "vosk/am/final.mdl"),
    )

    fun dossier(contexte: Context): File = File(contexte.filesDir, "voix")

    fun fichier(contexte: Context, chemin: String) = File(dossier(contexte), chemin)

    fun manquants(contexte: Context, pack: Pack) = fichiers.filter { it.pack == pack && !fichier(contexte, it.temoin).exists() }

    fun installe(contexte: Context, pack: Pack) = manquants(contexte, pack).isEmpty()

    fun taille(pack: Pack) = fichiers.filter { it.pack == pack }.sumOf { it.taille }
}

/** « 509 Mo » */
fun enMo(octets: Long) = "${(octets + 500_000) / 1_000_000} Mo"
