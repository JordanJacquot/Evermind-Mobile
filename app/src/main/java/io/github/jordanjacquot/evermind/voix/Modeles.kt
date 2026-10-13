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
enum class Pack(val nom: String) { VOIX("la voix"), EVEIL("« OK Evermind »") }

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
