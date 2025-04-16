#include <jni.h>
#include <string>
#include <android/log.h>

#define LOG_TAG "NativeLib"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
extern "C" JNIEXPORT jstring JNICALL
Java_com_example_testminify_MainActivity_stringFromJNI(
    JNIEnv* env,
    jobject /* this */) {
      LOGI("Native library loaded successfully!");
      std::string hello = "Hello from C++";
      return env->NewStringUTF(hello.c_str());
}