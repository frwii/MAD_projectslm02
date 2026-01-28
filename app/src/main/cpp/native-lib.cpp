#include "llama/llama.h"
#include <vector>
#include <jni.h>
#include <string>
#include <cstring>
#include <algorithm>
#include <android/log.h>
#include <chrono>
#include <set>
#include <sstream>
#include <cctype>

#define LOG_TAG "SLM_NATIVE"

// =====================================================
// runModel(): Executes inference and returns
// "<MODEL_OUTPUT>|TTFT_MS=...;ITPS=...;OTPS=...;OET_MS=..."
// =====================================================
std::string runModel(const std::string& prompt, const std::string& model_path) {

    // ================= Metrics =================
    auto t_start = std::chrono::high_resolution_clock::now();
    bool first_token_seen = false;

    long ttft_ms = -1;
    long itps = -1;
    long otps = -1;
    long oet_ms = -1;
    int generated_tokens = 0;

    __android_log_print(ANDROID_LOG_INFO, LOG_TAG,
                        "runModel() started with path: %s", model_path.c_str());

    // ================= Backend =================
    llama_backend_init();

    // ================= Load model =================
    llama_model_params model_params = llama_model_default_params();
    llama_model* model = llama_model_load_from_file(model_path.c_str(), model_params);

    if (!model) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "Failed to load model");
        return "ERROR|Model Load Failed";
    }

    const llama_vocab* vocab = llama_model_get_vocab(model);

    // ================= Context =================
    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = 512;
    ctx_params.n_threads = 4;

    llama_context* ctx = llama_init_from_model(model, ctx_params);
    if (!ctx) {
        llama_free_model(model);
        return "ERROR|Context Failed";
    }

    // ================= Tokenize prompt =================
    std::vector<llama_token> prompt_tokens(prompt.size() + 8);
    int n_prompt = llama_tokenize(
            vocab, prompt.c_str(), prompt.size(),
            prompt_tokens.data(), prompt_tokens.size(),
            true, false
    );

    if (n_prompt <= 0) {
        llama_free(ctx);
        llama_free_model(model);
        return "ERROR|Tokenize Failed";
    }
    prompt_tokens.resize(n_prompt);

    // ================= Initial batch =================
    llama_batch batch = llama_batch_init(n_prompt, 0, ctx_params.n_ctx);
    batch.n_tokens = n_prompt;

    for (int i = 0; i < n_prompt; i++) {
        batch.token[i] = prompt_tokens[i];
        batch.pos[i]   = i;
        batch.seq_id[i][0] = 0;
        batch.n_seq_id[i]  = 1;
        batch.logits[i]    = false;
    }
    batch.logits[n_prompt - 1] = true;

    // ================= Prefill =================
    auto t_prefill_start = std::chrono::high_resolution_clock::now();
    if (llama_decode(ctx, batch) != 0) {
        llama_free(ctx);
        llama_free_model(model);
        return "ERROR|Decode Failed";
    }
    auto t_prefill_end = std::chrono::high_resolution_clock::now();

    long prefill_ms =
            std::chrono::duration_cast<std::chrono::milliseconds>(
                    t_prefill_end - t_prefill_start).count();

    if (prefill_ms > 0) {
        itps = (n_prompt * 1000L) / prefill_ms;
    }

    // ================= Sampler =================
    llama_sampler* sampler = llama_sampler_init_greedy();

    // ================= Generation =================
    std::string output;
    const int max_tokens = 64;
    int n_pos = 0;

    auto t_gen_start = std::chrono::high_resolution_clock::now();

    while (n_pos + batch.n_tokens < n_prompt + max_tokens) {

        llama_token token = llama_sampler_sample(sampler, ctx, -1);
        if (llama_vocab_is_eog(vocab, token)) break;

        if (!first_token_seen) {
            ttft_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                    std::chrono::high_resolution_clock::now() - t_start).count();
            first_token_seen = true;
        }

        char buf[128];
        int n = llama_token_to_piece(vocab, token, buf, sizeof(buf), 0, true);
        if (n > 0) {
            output.append(buf, n);
        }

        generated_tokens++;

        batch = llama_batch_get_one(&token, 1);
        if (llama_decode(ctx, batch) != 0) break;
        n_pos += batch.n_tokens;
    }

    long gen_ms =
            std::chrono::duration_cast<std::chrono::milliseconds>(
                    std::chrono::high_resolution_clock::now() - t_gen_start).count();

    if (gen_ms > 0) {
        otps = (generated_tokens * 1000L) / gen_ms;
    }
    oet_ms = gen_ms;

    // =====================================================
    // ✅ FIXED FILTER LOGIC — WORKS FOR ALL 7 MODELS
    // =====================================================
    static const std::set<std::string> ALLOWED_ALLERGENS = {
            "milk","egg","peanut","tree nut",
            "wheat","soy","fish","shellfish","sesame"
    };

    std::string normalized = output;
    std::transform(normalized.begin(), normalized.end(),
                   normalized.begin(), ::tolower);

    // remove punctuation
    normalized.erase(
            std::remove_if(normalized.begin(), normalized.end(),
                           [](char c) { return std::ispunct(c); }),
            normalized.end()
    );

    std::vector<std::string> detected;
    for (const auto& allergen : ALLOWED_ALLERGENS) {
        if (normalized.find(allergen) != std::string::npos) {
            detected.push_back(allergen);
        }
    }

    if (detected.empty()) {
        output = "EMPTY";
    } else {
        output.clear();
        for (size_t i = 0; i < detected.size(); i++) {
            if (i > 0) output += ",";
            output += detected[i];
        }
    }

    // ================= Cleanup =================
    llama_sampler_free(sampler);
    llama_free(ctx);
    llama_free_model(model);

    // ================= Final Output =================
    return output + "|" +
           "TTFT_MS=" + std::to_string(ttft_ms) +
           ";ITPS=" + std::to_string(itps) +
           ";OTPS=" + std::to_string(otps) +
           ";OET_MS=" + std::to_string(oet_ms);
}

// =====================================================
// JNI bridge — MUST match HomeFragment external fun
// =====================================================
extern "C"
JNIEXPORT jstring JNICALL
Java_edu_utem_ftmk_slm02_HomeFragment_inferAllergens(
        JNIEnv *env,
        jobject,
        jstring inputPrompt,
        jstring modelPath
) {
    const char *promptCstr = env->GetStringUTFChars(inputPrompt, nullptr);
    const char *pathCstr   = env->GetStringUTFChars(modelPath, nullptr);

    std::string prompt(promptCstr);
    std::string actualPath(pathCstr);

    env->ReleaseStringUTFChars(inputPrompt, promptCstr);
    env->ReleaseStringUTFChars(modelPath, pathCstr);

    std::string output = runModel(prompt, actualPath);
    return env->NewStringUTF(output.c_str());
}
