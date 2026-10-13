// L'assistant d'Evermind sur le téléphone : un petit modèle de langue (GGUF) avec llama.cpp, comme sur PC.
// Il reçoit la conversation (consignes, agenda, demande) et répond en JSON imposé par un schéma (le même que sur PC) :
// la réponse ne peut pas sortir de ce format. Tout tourne sur le téléphone.
//
// Pour aller vite, les consignes (toujours les mêmes) ne sont lues qu'une fois : Kotlin place la marque
// « [[[EVERMIND-CACHE]]] » juste après elles ; ce qui est avant est gardé en mémoire d'une demande à l'autre.
// Repris de l'exemple Android de llama.cpp (examples/llama.android).

#include <android/log.h>
#include <jni.h>
#include <unistd.h>

#include <string>
#include <vector>

#include <nlohmann/json.hpp>

#include "chat.h"
#include "common.h"
#include "llama.h"
#include "sampling.h"

#define LOG_TAG "EvermindAssistant"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static const char *MARQUE = "[[[EVERMIND-CACHE]]]";
constexpr int TAILLE_LOT = 512;

static llama_model *g_modele = nullptr;
static llama_context *g_contexte = nullptr;
static common_chat_templates_ptr g_modeles_chat;
static std::vector<llama_token> g_prefixe;     // les consignes déjà lues (au début de la mémoire)
static std::vector<uint8_t> g_etat_prefixe;    // pour les modèles « hybrides » : l'état juste après les consignes
static bool g_hybride = false;

static void journal(ggml_log_level niveau, const char *texte, void *) {
    if (niveau >= GGML_LOG_LEVEL_WARN) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, "%s", texte);
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_jordanjacquot_evermind_assistant_MoteurLlama_initialiser(JNIEnv *env, jobject, jstring jdossier) {
    llama_log_set(journal, nullptr);
    const char *dossier = env->GetStringUTFChars(jdossier, nullptr);
    ggml_backend_load_all_from_path(dossier);   // les variantes du processeur, la meilleure pour ce téléphone
    env->ReleaseStringUTFChars(jdossier, dossier);
    llama_backend_init();
}

static void tout_liberer() {
    g_modeles_chat.reset();
    if (g_contexte) { llama_free(g_contexte); g_contexte = nullptr; }
    if (g_modele) { llama_model_free(g_modele); g_modele = nullptr; }
    g_prefixe.clear();
    g_etat_prefixe.clear();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_io_github_jordanjacquot_evermind_assistant_MoteurLlama_charger(JNIEnv *env, jobject, jstring jchemin, jint n_ctx, jint n_fils) {
    tout_liberer();
    const char *chemin = env->GetStringUTFChars(jchemin, nullptr);
    llama_model_params mp = llama_model_default_params();
    g_modele = llama_model_load_from_file(chemin, mp);
    env->ReleaseStringUTFChars(jchemin, chemin);
    if (!g_modele) { LOGE("modèle illisible"); return JNI_FALSE; }

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = n_ctx;
    cp.n_batch = TAILLE_LOT;
    cp.n_ubatch = TAILLE_LOT;
    cp.n_threads = n_fils;
    cp.n_threads_batch = n_fils;
    g_contexte = llama_init_from_model(g_modele, cp);
    if (!g_contexte) { LOGE("contexte impossible"); tout_liberer(); return JNI_FALSE; }
    g_modeles_chat = common_chat_templates_init(g_modele, "");
    g_hybride = llama_model_is_recurrent(g_modele) || llama_model_is_hybrid(g_modele);
    LOGI("modèle chargé (%s)", g_hybride ? "hybride" : "attention");
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_jordanjacquot_evermind_assistant_MoteurLlama_liberer(JNIEnv *, jobject) {
    tout_liberer();
}

// Lit des jetons à partir de la position « debut » ; logits seulement pour le dernier si demandé
static bool lire(const std::vector<llama_token> &jetons, llama_pos debut, bool dernier_logit) {
    common_batch lot(g_contexte);
    for (size_t i = 0; i < jetons.size(); i += TAILLE_LOT) {
        const size_t n = std::min((size_t) TAILLE_LOT, jetons.size() - i);
        lot.clear();
        for (size_t j = 0; j < n; j++) {
            const bool logit = dernier_logit && (i + j == jetons.size() - 1);
            lot.add(jetons[i + j], debut + (llama_pos) (i + j), 0, logit);
        }
        if (llama_process(g_contexte, LLAMA_PROCESS_TYPE_DECODE, lot.get()) != 0) return false;
    }
    return true;
}

// La réponse (le JSON), ou null en cas de problème.
//   messages : [{"role":"system"|"user"|"assistant","content":"..."}] ; schema : le schéma JSON de la réponse
extern "C" JNIEXPORT jstring JNICALL
Java_io_github_jordanjacquot_evermind_assistant_MoteurLlama_repondre(JNIEnv *env, jobject, jstring jmessages, jstring jschema,
                                                                   jint max_jetons, jfloat penalite_presence) {
    if (!g_modele || !g_contexte) return nullptr;
    const char *cm = env->GetStringUTFChars(jmessages, nullptr);
    const char *cs = env->GetStringUTFChars(jschema, nullptr);
    std::string texte_messages(cm), schema(cs);
    env->ReleaseStringUTFChars(jmessages, cm);
    env->ReleaseStringUTFChars(jschema, cs);

    common_chat_templates_inputs entrees;
    try {
        for (const auto &m : nlohmann::json::parse(texte_messages)) {
            common_chat_msg msg;
            msg.role = m.at("role").get<std::string>();
            msg.content = m.at("content").get<std::string>();
            entrees.messages.push_back(msg);
        }
    } catch (const std::exception &e) { LOGE("messages illisibles : %s", e.what()); return nullptr; }
    entrees.json_schema = schema;
    entrees.use_jinja = true;
    entrees.enable_thinking = false;
    entrees.add_generation_prompt = true;

    common_chat_params params;
    try { params = common_chat_templates_apply(g_modeles_chat.get(), entrees); }
    catch (const std::exception &e) { LOGE("modèle de conversation : %s", e.what()); return nullptr; }

    // Les consignes (avant la marque) et le reste
    std::string invite = params.prompt, avant, apres;
    const size_t m = invite.find(MARQUE);
    if (m != std::string::npos) { avant = invite.substr(0, m); apres = invite.substr(m + strlen(MARQUE)); }
    else apres = invite;
    std::vector<llama_token> prefixe = avant.empty() ? std::vector<llama_token>() : common_tokenize(g_contexte, avant, true, true);
    std::vector<llama_token> suite = common_tokenize(g_contexte, apres, prefixe.empty(), true);
    if (suite.empty()) return nullptr;

    auto memoire = llama_get_memory(g_contexte);
    bool deja = !prefixe.empty() && prefixe == g_prefixe;
    if (deja) {
        if (g_hybride) {
            llama_memory_clear(memoire, true);
            deja = llama_state_seq_set_data(g_contexte, g_etat_prefixe.data(), g_etat_prefixe.size(), 0) > 0;
        } else {
            deja = llama_memory_seq_rm(memoire, 0, (llama_pos) prefixe.size(), -1);
        }
    }
    if (!deja) {
        llama_memory_clear(memoire, true);
        g_prefixe.clear();
        g_etat_prefixe.clear();
        if (!prefixe.empty()) {
            if (!lire(prefixe, 0, false)) { LOGE("lecture des consignes impossible"); return nullptr; }
            g_prefixe = prefixe;
            if (g_hybride) {
                g_etat_prefixe.resize(llama_state_seq_get_size(g_contexte, 0));
                llama_state_seq_get_data(g_contexte, g_etat_prefixe.data(), g_etat_prefixe.size(), 0);
            }
        }
    }
    llama_pos pos = (llama_pos) prefixe.size();
    if (pos + (llama_pos) suite.size() + max_jetons > (llama_pos) llama_n_ctx(g_contexte)) { LOGE("trop long pour la mémoire"); return nullptr; }
    if (!lire(suite, pos, true)) { LOGE("lecture de la demande impossible"); return nullptr; }
    pos += (llama_pos) suite.size();

    // La réponse, contrainte par le schéma (comme response_format sur PC), sans hasard (température 0)
    common_params_sampling sp;
    sp.temp = 0.0f;
    sp.penalty_present = penalite_presence;
    if (!params.grammar.empty()) sp.grammar = common_grammar(COMMON_GRAMMAR_TYPE_OUTPUT_FORMAT, params.grammar);
    common_sampler *echantillon = common_sampler_init(g_modele, sp);
    if (!echantillon) return nullptr;

    std::string reponse, attente;
    common_batch lot(g_contexte);
    const llama_vocab *vocab = llama_model_get_vocab(g_modele);
    for (int i = 0; i < max_jetons; i++) {
        const llama_token t = common_sampler_sample(echantillon, g_contexte, -1);
        common_sampler_accept(echantillon, t, true);
        if (llama_vocab_is_eog(vocab, t)) break;
        attente += common_token_to_piece(g_contexte, t);
        reponse += attente;
        attente.clear();
        lot.clear();
        lot.add(t, pos++, 0, true);
        if (llama_process(g_contexte, LLAMA_PROCESS_TYPE_DECODE, lot.get()) != 0) break;
    }
    common_sampler_free(echantillon);
    return env->NewStringUTF(reponse.c_str());
}
