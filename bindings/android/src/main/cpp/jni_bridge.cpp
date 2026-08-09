#include <jni.h>
#include "s2s/s2s_engine.h"
#include <memory>

static std::unique_ptr<s2s::SpeechToSpeechEngine> g_engine;

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_s2s_mobile_S2SEngine_nativeInitialize(JNIEnv* env, jobject thiz, jstring vadPath, jstring sttPath, jstring llmPath, jstring ttsPath) {
    (void)env; (void)thiz; (void)vadPath; (void)sttPath; (void)llmPath; (void)ttsPath;
    
    s2s::EngineConfig config;
    g_engine = std::make_unique<s2s::SpeechToSpeechEngine>(config);
    return g_engine->initialize();
}

JNIEXPORT jboolean JNICALL
Java_com_s2s_mobile_S2SEngine_nativeStart(JNIEnv* env, jobject thiz) {
    (void)env; (void)thiz;
    if (!g_engine) return JNI_FALSE;
    return g_engine->start();
}

JNIEXPORT void JNICALL
Java_com_s2s_mobile_S2SEngine_nativeStop(JNIEnv* env, jobject thiz) {
    (void)env; (void)thiz;
    if (g_engine) {
        g_engine->stop();
    }
}

JNIEXPORT void JNICALL
Java_com_s2s_mobile_S2SEngine_nativeFeedAudioFloat(JNIEnv* env, jobject thiz, jfloatArray pcmArray) {
    (void)thiz;
    if (!g_engine || !pcmArray) return;
    
    jsize len = env->GetArrayLength(pcmArray);
    jfloat* data = env->GetFloatArrayElements(pcmArray, nullptr);
    if (data) {
        g_engine->feedAudioInput(data, len);
        env->ReleaseFloatArrayElements(pcmArray, data, JNI_ABORT);
    }
}

JNIEXPORT void JNICALL
Java_com_s2s_mobile_S2SEngine_nativeFeedAudioShort(JNIEnv* env, jobject thiz, jshortArray pcmArray) {
    (void)thiz;
    if (!g_engine || !pcmArray) return;
    
    jsize len = env->GetArrayLength(pcmArray);
    jshort* data = env->GetShortArrayElements(pcmArray, nullptr);
    if (data) {
        g_engine->feedAudioInput(reinterpret_cast<const int16_t*>(data), len);
        env->ReleaseShortArrayElements(pcmArray, data, JNI_ABORT);
    }
}

JNIEXPORT void JNICALL
Java_com_s2s_mobile_S2SEngine_nativeInterrupt(JNIEnv* env, jobject thiz) {
    (void)env; (void)thiz;
    if (g_engine) {
        g_engine->interrupt();
    }
}

}
