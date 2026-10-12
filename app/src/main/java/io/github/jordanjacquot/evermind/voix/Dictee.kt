package io.github.jordanjacquot.evermind.voix

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * La dictée, avec la reconnaissance vocale du téléphone, en mode « sur l'appareil » seulement : la voix ne quitte
 * pas le téléphone. Si le téléphone ne sait pas faire (avant Android 12, ou sans reconnaissance hors ligne),
 * Evermind le dit au lieu d'envoyer la voix ailleurs. Whisper (comme sur PC) viendra ensuite.
 * À utiliser depuis le fil principal.
 */
class Dictee(private val contexte: Context) {
    private var reco: SpeechRecognizer? = null

    private val intention = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
    }

    val disponible: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(contexte)

    /**
     * Écoute une phrase. « partiel » reçoit le texte pendant qu'on parle, « fini » la phrase entière,
     * « erreur » ce qu'il faut dire si ça n'a pas marché. « fin » est appelé dans tous les cas, une fois.
     */
    fun ecouter(partiel: (String) -> Unit, fini: (String) -> Unit, erreur: (String) -> Unit, fin: () -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || !disponible) {
            erreur("Ce téléphone ne sait pas reconnaître la voix sans Internet. La dictée d'Evermind (Whisper) arrive bientôt.")
            fin()
            return
        }
        arreter()
        val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(contexte)
        reco = r
        var termine = false
        fun terminer() { if (!termine) { termine = true; fin() } }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onPartialResults(resultats: Bundle?) {
                premier(resultats)?.let(partiel)
            }

            override fun onResults(resultats: Bundle?) {
                val texte = premier(resultats)
                if (texte.isNullOrBlank()) erreur("Je n'ai rien entendu.") else fini(texte)
                terminer()
            }

            override fun onError(code: Int) {
                erreur(messageErreur(r, code))
                terminer()
            }

            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        r.startListening(intention)
    }

    /** Arrête d'écouter (ce qui a déjà été dit est quand même lu). */
    fun arreter() {
        reco?.let { it.cancel(); it.destroy() }
        reco = null
    }

    private fun premier(resultats: Bundle?): String? =
        resultats?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    private fun messageErreur(r: SpeechRecognizer, code: Int): String = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Je n'ai rien entendu."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Il me faut le micro : autorise-le dans les réglages d'Evermind."
        SpeechRecognizer.ERROR_AUDIO -> "Le micro ne répond pas."
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> {
            // Le français n'est pas encore sur le téléphone : il se télécharge une fois (par Android, pas par Evermind)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                runCatching { r.triggerModelDownload(intention) }
                "Le téléphone télécharge la dictée en français (une seule fois). Réessaie dans un moment."
            } else "La dictée en français n'est pas installée sur ce téléphone."
        }
        else -> "La dictée n'a pas marché (erreur $code)."
    }
}
