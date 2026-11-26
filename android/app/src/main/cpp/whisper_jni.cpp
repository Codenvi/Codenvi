// JNI bridge between app.codenvi.speech.WhisperNative and whisper.cpp (docs/design.md §10.4).
// All three functions are called from one background thread at a time (WhisperManager serialises them).
#include <jni.h>

#include <string>

#include "whisper.h"

extern "C" {

JNIEXPORT jlong JNICALL
Java_app_codenvi_speech_WhisperNative_nativeInit(JNIEnv *env, jobject /*thiz*/, jstring path) {
    const char *p = env->GetStringUTFChars(path, nullptr);
    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(p, cparams);
    env->ReleaseStringUTFChars(path, p);
    return reinterpret_cast<jlong>(ctx);
}

// Transcribes 16 kHz mono float samples (English model). Returns the text as UTF-8 bytes (empty on failure).
JNIEXPORT jbyteArray JNICALL
Java_app_codenvi_speech_WhisperNative_nativeTranscribe(JNIEnv *env, jobject /*thiz*/, jlong handle,
                                                       jfloatArray samples, jint threads) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    std::string text;
    if (ctx != nullptr) {
        const jsize n = env->GetArrayLength(samples);
        jfloat *data = env->GetFloatArrayElements(samples, nullptr);

        whisper_full_params wp = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
        wp.n_threads = threads;
        wp.language = "en";
        wp.translate = false;
        wp.detect_language = false;
        wp.no_context = true;
        wp.single_segment = false;
        wp.print_special = false;
        wp.print_progress = false;
        wp.print_realtime = false;
        wp.print_timestamps = false;
        wp.suppress_blank = true;
        wp.suppress_nst = true;
        wp.temperature = 0.0f;
        wp.no_speech_thold = 0.6f;

        if (whisper_full(ctx, wp, data, n) == 0) {
            const int segments = whisper_full_n_segments(ctx);
            for (int i = 0; i < segments; i++) {
                const char *piece = whisper_full_get_segment_text(ctx, i);
                if (piece != nullptr) text += piece;
            }
        }
        env->ReleaseFloatArrayElements(samples, data, JNI_ABORT);
    }
    jbyteArray out = env->NewByteArray(static_cast<jsize>(text.size()));
    if (!text.empty()) env->SetByteArrayRegion(out, 0, static_cast<jsize>(text.size()), reinterpret_cast<const jbyte *>(text.data()));
    return out;
}

JNIEXPORT void JNICALL
Java_app_codenvi_speech_WhisperNative_nativeFree(JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    if (ctx != nullptr) whisper_free(ctx);
}

}  // extern "C"
