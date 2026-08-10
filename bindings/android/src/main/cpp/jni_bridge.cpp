#include <jni.h>
#include "s2s/s2s_engine.h"
#include <memory>
#include <string>
#include <android/log.h>

#define LOG_TAG "S2S_JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static std::unique_ptr<s2s::SpeechToSpeechEngine> g_engine;
static JavaVM* g_jvm = nullptr;
static jobject g_engineObj = nullptr;
static jmethodID g_onTranscriptMethod = nullptr;
static jmethodID g_onAudioChunkMethod = nullptr;
static jmethodID g_onBargeInMethod = nullptr;
static jmethodID g_onSynthesizeTTSMethod = nullptr;
static jmethodID g_onTranscribeAudioMethod = nullptr;

static std::string jstringToString(JNIEnv* env, jstring jstr) {
    if (!jstr) return "";
    const char* chars = env->GetStringUTFChars(jstr, nullptr);
    std::string str(chars ? chars : "");
    if (chars) env->ReleaseStringUTFChars(jstr, chars);
    return str;
}

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    (void)reserved;
    g_jvm = vm;
    LOGI("JNI_OnLoad registered successfully.");
    return JNI_VERSION_1_6;
}

JNIEXPORT jboolean JNICALL
Java_com_s2s_mobile_S2SEngine_nativeInitialize(JNIEnv* env, jobject thiz, jstring vadPath, jstring sttPath, jstring llmPath, jstring ttsPath) {
    std::string sVad = jstringToString(env, vadPath);
    std::string sStt = jstringToString(env, sttPath);
    std::string sLlm = jstringToString(env, llmPath);
    std::string sTts = jstringToString(env, ttsPath);

    LOGI("Initializing S2SEngine Native — VAD: %s, STT: %s, LLM: %s, TTS: %s",
         sVad.c_str(), sStt.c_str(), sLlm.c_str(), sTts.c_str());

    s2s::EngineConfig config;
    config.vad.modelPath = sVad;
    config.stt.modelPath = sStt;
    config.llm.modelName = sLlm;
    config.tts.voicePath = sTts;

    g_engine = std::make_unique<s2s::SpeechToSpeechEngine>(config);

    if (g_engineObj) {
        env->DeleteGlobalRef(g_engineObj);
        g_engineObj = nullptr;
    }
    g_engineObj = env->NewGlobalRef(thiz);

    jclass cls = env->GetObjectClass(thiz);
    g_onTranscriptMethod = env->GetMethodID(cls, "onNativeTranscript", "(Ljava/lang/String;Z)V");
    g_onAudioChunkMethod = env->GetMethodID(cls, "onNativeAudioChunk", "([F)V");
    g_onBargeInMethod = env->GetMethodID(cls, "onNativeBargeIn", "()V");
    g_onSynthesizeTTSMethod = env->GetMethodID(cls, "onNativeSynthesizeTTS", "(Ljava/lang/String;)[F");
    g_onTranscribeAudioMethod = env->GetMethodID(cls, "onNativeTranscribeAudio", "([F)Ljava/lang/String;");

    g_engine->setSTTTranscribeCallback([](const std::vector<float>& samples) -> std::string {
        if (!g_jvm || !g_engineObj || !g_onTranscribeAudioMethod || samples.empty()) return "";

        JNIEnv* cbEnv = nullptr;
        int getEnvStat = g_jvm->GetEnv((void**)&cbEnv, JNI_VERSION_1_6);
        bool isAttached = false;
        if (getEnvStat == JNI_EDETACHED) {
            if (g_jvm->AttachCurrentThread(&cbEnv, nullptr) == 0) {
                isAttached = true;
            }
        }

        std::string result;
        if (cbEnv) {
            jfloatArray array = cbEnv->NewFloatArray(samples.size());
            cbEnv->SetFloatArrayRegion(array, 0, samples.size(), samples.data());
            auto jtext = (jstring)cbEnv->CallObjectMethod(g_engineObj, g_onTranscribeAudioMethod, array);
            cbEnv->DeleteLocalRef(array);

            if (jtext) {
                const char* utf = cbEnv->GetStringUTFChars(jtext, nullptr);
                if (utf) {
                    result = utf;
                    cbEnv->ReleaseStringUTFChars(jtext, utf);
                }
                cbEnv->DeleteLocalRef(jtext);
            }
        }

        if (isAttached) {
            g_jvm->DetachCurrentThread();
        }
        return result;
    });

    g_engine->setTTSSynthesizeCallback([](const std::string& text) -> std::vector<float> {
        if (!g_jvm || !g_engineObj || !g_onSynthesizeTTSMethod) return {};

        JNIEnv* cbEnv = nullptr;
        int getEnvStat = g_jvm->GetEnv((void**)&cbEnv, JNI_VERSION_1_6);
        bool isAttached = false;
        if (getEnvStat == JNI_EDETACHED) {
            if (g_jvm->AttachCurrentThread(&cbEnv, nullptr) == 0) {
                isAttached = true;
            }
        }

        std::vector<float> result;
        if (cbEnv) {
            jstring jtext = cbEnv->NewStringUTF(text.c_str());
            auto jarr = (jfloatArray)cbEnv->CallObjectMethod(g_engineObj, g_onSynthesizeTTSMethod, jtext);
            cbEnv->DeleteLocalRef(jtext);

            if (jarr) {
                jsize len = cbEnv->GetArrayLength(jarr);
                if (len > 0) {
                    result.resize(len);
                    cbEnv->GetFloatArrayRegion(jarr, 0, len, result.data());
                }
                cbEnv->DeleteLocalRef(jarr);
            }
        }

        if (isAttached) {
            g_jvm->DetachCurrentThread();
        }
        return result;
    });

    // Hook C++ transcript callback to Java callback
    g_engine->setTranscriptCallback([](const std::string& text, bool isUser, bool isFinal) {
        (void)isUser;
        if (!g_jvm || !g_engineObj || !g_onTranscriptMethod) return;

        JNIEnv* cbEnv = nullptr;
        int getEnvStat = g_jvm->GetEnv((void**)&cbEnv, JNI_VERSION_1_6);
        bool isAttached = false;
        if (getEnvStat == JNI_EDETACHED) {
            if (g_jvm->AttachCurrentThread(&cbEnv, nullptr) == 0) {
                isAttached = true;
            }
        }

        if (cbEnv) {
            jstring jtext = cbEnv->NewStringUTF(text.c_str());
            cbEnv->CallVoidMethod(g_engineObj, g_onTranscriptMethod, jtext, (jboolean)isFinal);
            cbEnv->DeleteLocalRef(jtext);
        }

        if (isAttached) {
            g_jvm->DetachCurrentThread();
        }
    });

    // Hook C++ TTS audio output callback to Java AudioTrack queue
    static int audioChunkCounter = 0;
    g_engine->setAudioOutputCallback([](const std::vector<float>& pcmSamples, s2s::GenerationId genId) {
        (void)genId;
        LOGI("audioOutputCallback fired: %zu samples, genId=%u, jvm=%p, obj=%p, method=%p",
             pcmSamples.size(), genId, g_jvm, g_engineObj, g_onAudioChunkMethod);
        if (!g_jvm || !g_engineObj || !g_onAudioChunkMethod || pcmSamples.empty()) {
            LOGE("audioOutputCallback ABORTED: jvm=%p obj=%p method=%p empty=%d",
                 g_jvm, g_engineObj, g_onAudioChunkMethod, pcmSamples.empty()?1:0);
            return;
        }

        JNIEnv* cbEnv = nullptr;
        int getEnvStat = g_jvm->GetEnv((void**)&cbEnv, JNI_VERSION_1_6);
        bool isAttached = false;
        if (getEnvStat == JNI_EDETACHED) {
            if (g_jvm->AttachCurrentThread(&cbEnv, nullptr) == 0) {
                isAttached = true;
            } else {
                LOGE("audioOutputCallback: AttachCurrentThread FAILED");
                return;
            }
        }

        if (cbEnv) {
            jfloatArray array = cbEnv->NewFloatArray(pcmSamples.size());
            cbEnv->SetFloatArrayRegion(array, 0, pcmSamples.size(), pcmSamples.data());
            cbEnv->CallVoidMethod(g_engineObj, g_onAudioChunkMethod, array);
            cbEnv->DeleteLocalRef(array);
            audioChunkCounter++;
            LOGI("audioOutputCallback: sent %zu samples to Java (total chunks sent: %d)",
                 pcmSamples.size(), audioChunkCounter);
        }

        if (isAttached) {
            g_jvm->DetachCurrentThread();
        }
    });

    return g_engine->initialize();
}

JNIEXPORT jboolean JNICALL
Java_com_s2s_mobile_S2SEngine_nativeStart(JNIEnv* env, jobject thiz) {
    (void)env; (void)thiz;
    if (!g_engine) return JNI_FALSE;
    LOGI("Native S2SEngine starting audio processing pipeline...");
    return g_engine->start();
}

JNIEXPORT void JNICALL
Java_com_s2s_mobile_S2SEngine_nativeStop(JNIEnv* env, jobject thiz) {
    (void)env; (void)thiz;
    if (g_engine) {
        LOGI("Native S2SEngine stopping pipeline...");
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
Java_com_s2s_mobile_S2SEngine_nativeFeedTextPrompt(JNIEnv* env, jobject thiz, jstring promptText) {
    (void)thiz;
    if (!g_engine || !promptText) {
        LOGE("nativeFeedTextPrompt: engine=%p promptText=%p - SKIPPED", g_engine.get(), promptText);
        return;
    }
    std::string text = jstringToString(env, promptText);
    LOGI("nativeFeedTextPrompt: '%.80s' (len=%zu)", text.c_str(), text.size());
    g_engine->feedTextPrompt(text);
    LOGI("nativeFeedTextPrompt: feedTextPrompt returned");
}

JNIEXPORT void JNICALL
Java_com_s2s_mobile_S2SEngine_nativeInterrupt(JNIEnv* env, jobject thiz) {
    (void)env; (void)thiz;
    if (g_engine) {
        g_engine->interrupt();
    }
}

}
