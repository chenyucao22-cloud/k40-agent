#include <jni.h>
#include "infer.h"

static std::atomic_bool cancelled(false);
extern "C" JNIEXPORT void JNICALL
Java_dev_channing_androidagent_local_LocalNative_abort(JNIEnv*, jobject) { cancelled.store(true); }
extern "C" JNIEXPORT void JNICALL
Java_dev_channing_androidagent_local_LocalNative_reset(JNIEnv*, jobject) { cancelled.store(false); }

static std::string read(JNIEnv* env, jbyteArray bytes) {
    std::string result(env->GetArrayLength(bytes), '\0');
    env->GetByteArrayRegion(bytes, 0, result.size(), reinterpret_cast<jbyte*>(result.data()));
    return result;
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_dev_channing_androidagent_local_LocalNative_generate(JNIEnv* env, jobject, jbyteArray path, jbyteArray prompt, jint threads) {
    try {
        const auto result = infer(read(env, path), read(env, prompt), cancelled, threads);
        auto bytes = env->NewByteArray(result.size());
        if (bytes) env->SetByteArrayRegion(bytes, 0, result.size(), reinterpret_cast<const jbyte*>(result.data()));
        return bytes;
    } catch (const std::exception&) {
        return nullptr;
    }
}
