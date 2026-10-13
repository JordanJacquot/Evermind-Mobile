package io.github.jordanjacquot.evermind.assistant

import android.content.Context

/**
 * Le moteur de l'assistant (llama.cpp, comme sur PC), en C++ : voir app/src/main/cpp/assistant_jni.cpp.
 * Un seul modèle chargé à la fois ; à appeler hors du fil principal, une demande à la fois.
 */
object MoteurLlama {
    /** Placé dans les consignes, juste après la partie qui ne change jamais : elle n'est lue qu'une fois. */
    const val MARQUE_CACHE = "[[[EVERMIND-CACHE]]]"

    private var pret = false

    /** false si le téléphone ne peut pas faire tourner l'assistant (processeur 32 bits). */
    @Synchronized
    fun disponible(contexte: Context): Boolean {
        if (pret) return true
        return try {
            System.loadLibrary("evermind-assistant")
            initialiser(contexte.applicationInfo.nativeLibraryDir)
            pret = true
            true
        } catch (e: UnsatisfiedLinkError) {
            false
        }
    }

    private external fun initialiser(dossierLibs: String)
    external fun charger(chemin: String, nCtx: Int, nFils: Int): Boolean
    external fun liberer()

    /** La réponse JSON à la conversation (messages en JSON : [{"role", "content"}]), ou null. */
    external fun repondre(messages: String, schema: String, maxJetons: Int, penalitePresence: Float): String?
}
