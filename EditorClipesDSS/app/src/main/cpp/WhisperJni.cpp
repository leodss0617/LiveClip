#include <jni.h>
#include <algorithm>
#include <string>
#include <vector>
#include <thread>
#include "whisper.h"

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_domain_analyzer_WhisperNativeBridge_initContext(JNIEnv* env, jobject, jstring modelPath) {
    if (!modelPath) return 0;
    const char* path = env->GetStringUTFChars(modelPath, nullptr);
    whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    whisper_context* ctx = whisper_init_from_file_with_params(path, cp);
    env->ReleaseStringUTFChars(modelPath, path);
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_example_domain_analyzer_WhisperNativeBridge_transcribe(JNIEnv* env, jobject, jlong handle, jfloatArray audio, jstring language) {
    auto* ctx = reinterpret_cast<whisper_context*>(handle);
    if (!ctx || !audio) return nullptr;
    const char* lang = language ? env->GetStringUTFChars(language, nullptr) : "pt";
    jfloat* samples = env->GetFloatArrayElements(audio, nullptr);
    const jsize count = env->GetArrayLength(audio);
    whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    p.print_realtime = false; p.print_progress = false; p.print_timestamps = false; p.print_special = false;
    p.translate = false; p.language = lang; p.n_threads = std::max(2, std::min(4, (int)std::thread::hardware_concurrency()));
    p.no_context = true; p.single_segment = false; p.suppress_blank = true;
    const int status = whisper_full(ctx, p, samples, count);
    env->ReleaseFloatArrayElements(audio, samples, JNI_ABORT);
    if (language) env->ReleaseStringUTFChars(language, lang);
    if (status != 0) return nullptr;
    const int n = whisper_full_n_segments(ctx);
    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray result = env->NewObjectArray(n, stringClass, nullptr);
    for (int i = 0; i < n; ++i) {
        const long long start = whisper_full_get_segment_t0(ctx, i) * 10;
        const long long end = whisper_full_get_segment_t1(ctx, i) * 10;
        std::string line = std::to_string(start) + "|" + std::to_string(end) + "|" + whisper_full_get_segment_text(ctx, i);
        jstring value = env->NewStringUTF(line.c_str());
        env->SetObjectArrayElement(result, i, value);
        env->DeleteLocalRef(value);
    }
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_domain_analyzer_WhisperNativeBridge_freeContext(JNIEnv*, jobject, jlong handle) {
    auto* ctx = reinterpret_cast<whisper_context*>(handle);
    if (ctx) whisper_free(ctx);
}
