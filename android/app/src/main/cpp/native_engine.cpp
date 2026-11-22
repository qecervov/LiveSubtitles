#include <jni.h>
#include <atomic>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>
#include <algorithm>
#include "whisper.h"
#include "ctranslate2/translator.h"
#include "sentencepiece_processor.h"

struct Engine {
    whisper_context* whisper = nullptr;
    std::unique_ptr<ctranslate2::Translator> translator;
    sentencepiece::SentencePieceProcessor tokenizer;
    std::atomic<bool> cancelled{false};
    int threads = 4;
    ~Engine() { if (whisper) whisper_free(whisper); }
};

static std::string utf8(JNIEnv* env, jstring value) {
    const char* bytes = env->GetStringUTFChars(value, nullptr);
    std::string result(bytes);
    env->ReleaseStringUTFChars(value, bytes);
    return result;
}
static jstring java_string(JNIEnv* env, const std::string& value) {
    // Java's modified UTF-8 cannot represent all Whisper output. Decode real UTF-8.
    jbyteArray data = env->NewByteArray(static_cast<jsize>(value.size()));
    env->SetByteArrayRegion(data, 0, static_cast<jsize>(value.size()), reinterpret_cast<const jbyte*>(value.data()));
    jclass string_class = env->FindClass("java/lang/String");
    jmethodID constructor = env->GetMethodID(string_class, "<init>", "([BLjava/lang/String;)V");
    jstring encoding = env->NewStringUTF("UTF-8");
    auto result = static_cast<jstring>(env->NewObject(string_class, constructor, data, encoding));
    env->DeleteLocalRef(data); env->DeleteLocalRef(encoding); env->DeleteLocalRef(string_class);
    return result;
}
static void fail(JNIEnv* env, const char* message) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message);
}
static bool abort_inference(void* state) { return static_cast<Engine*>(state)->cancelled.load(); }
static void quiet_log(enum ggml_log_level, const char*, void*) {}

extern "C" JNIEXPORT jlong JNICALL Java_org_livesubtitles_android_NativeEngine_create(
    JNIEnv* env, jclass, jstring model, jstring translation, jstring spm, jint threads) {
    try {
        auto engine = std::make_unique<Engine>();
        engine->threads = std::clamp(static_cast<int>(threads), 1, 4);
        whisper_log_set(quiet_log, nullptr);
        auto params = whisper_context_default_params();
        params.use_gpu = false;
        engine->whisper = whisper_init_from_file_with_params(utf8(env, model).c_str(), params);
        if (!engine->whisper) throw std::runtime_error("Не удалось загрузить модель распознавания.");
        auto status = engine->tokenizer.Load(utf8(env, spm));
        if (!status.ok()) throw std::runtime_error("Не удалось загрузить токенизатор перевода.");
        ctranslate2::ReplicaPoolConfig config;
        config.num_threads_per_replica = engine->threads;
        config.max_queued_batches = 1;
        engine->translator = std::make_unique<ctranslate2::Translator>(utf8(env, translation),
            ctranslate2::Device::CPU, ctranslate2::ComputeType::INT8, std::vector<int>{0}, false, config);
        return reinterpret_cast<jlong>(engine.release());
    } catch (const std::exception&) { fail(env, "Не удалось загрузить локальные модели. Проверьте установку моделей и свободную память."); return 0; }
}

extern "C" JNIEXPORT jobjectArray JNICALL Java_org_livesubtitles_android_NativeEngine_process(
    JNIEnv* env, jclass, jlong handle, jshortArray samples, jstring language) {
    auto* engine = reinterpret_cast<Engine*>(handle);
    if (!engine || engine->cancelled.load()) return nullptr;
    try {
        jsize count = env->GetArrayLength(samples);
        std::vector<jshort> input(count);
        env->GetShortArrayRegion(samples, 0, count, input.data());
        std::vector<float> audio(count);
        for (jsize i = 0; i < count; ++i) audio[i] = input[i] / 32768.0f;
        auto source = utf8(env, language);
        auto params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
        params.n_threads = engine->threads;
        params.language = source.c_str();
        params.translate = source != "en" && source != "ru";
        params.no_context = true;
        params.no_timestamps = true;
        params.single_segment = true;
        params.print_realtime = false;
        params.print_progress = false;
        params.print_timestamps = false;
        params.print_special = false;
        params.temperature = 0;
        params.temperature_inc = 0;
        params.suppress_blank = true;
        params.suppress_nst = true;
        params.abort_callback = abort_inference;
        params.abort_callback_user_data = engine;
        if (whisper_full(engine->whisper, params, audio.data(), count) != 0) {
            if (engine->cancelled.load()) return nullptr;
            throw std::runtime_error("Распознавание не завершилось.");
        }
        if (engine->cancelled.load()) return nullptr;
        std::string recognized;
        for (int i = 0; i < whisper_full_n_segments(engine->whisper); ++i) {
            if (whisper_full_get_segment_no_speech_prob(engine->whisper, i) < 0.7f)
                recognized += whisper_full_get_segment_text(engine->whisper, i);
        }
        if (recognized.find_first_not_of(" \r\n\t") == std::string::npos) return nullptr;
        std::string translated = recognized;
        if (source != "ru") {
            std::vector<std::string> tokens;
            auto status = engine->tokenizer.Encode(recognized, &tokens);
            if (!status.ok()) throw std::runtime_error("Ошибка токенизации.");
            ctranslate2::TranslationOptions options;
            options.beam_size = 2;
            options.num_hypotheses = 1;
            options.max_decoding_length = 192;
            options.length_penalty = 0.2f;
            options.replace_unknowns = true;
            auto results = engine->translator->translate_batch({tokens}, options);
            if (engine->cancelled.load()) return nullptr;
            status = engine->tokenizer.Decode(results.at(0).hypotheses.at(0), &translated);
            if (!status.ok()) throw std::runtime_error("Ошибка декодирования перевода.");
        }
        auto output = env->NewObjectArray(2, env->FindClass("java/lang/String"), nullptr);
        auto a = java_string(env, translated), b = java_string(env, recognized);
        env->SetObjectArrayElement(output, 0, a); env->SetObjectArrayElement(output, 1, b);
        env->DeleteLocalRef(a); env->DeleteLocalRef(b);
        return output;
    } catch (const std::exception&) { fail(env, "Ошибка локального распознавания или перевода."); return nullptr; }
}

extern "C" JNIEXPORT void JNICALL Java_org_livesubtitles_android_NativeEngine_cancel(JNIEnv*, jclass, jlong handle) {
    if (handle) reinterpret_cast<Engine*>(handle)->cancelled.store(true);
}
extern "C" JNIEXPORT void JNICALL Java_org_livesubtitles_android_NativeEngine_destroy(JNIEnv*, jclass, jlong handle) {
    delete reinterpret_cast<Engine*>(handle);
}
