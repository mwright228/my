package main

/*
#include <jni.h>
#include <stdlib.h>

static JavaVM* g_vm = NULL;
static jclass g_bridgeClass = NULL;
static jmethodID g_protectMid = NULL;
static jmethodID g_logMid = NULL;

static inline void initJavaVM(JNIEnv* env) {
    if (!g_vm && env) {
        (*env)->GetJavaVM(env, &g_vm);
    }
    if (g_vm && env && !g_bridgeClass) {
        jclass clazz = (*env)->FindClass(env, "id/my/mub/service/NativeCoreBridge");
        if (clazz) {
            g_bridgeClass = (jclass)(*env)->NewGlobalRef(env, clazz);
            g_protectMid = (*env)->GetStaticMethodID(env, g_bridgeClass, "protectSocket", "(I)Z");
            g_logMid = (*env)->GetStaticMethodID(env, g_bridgeClass, "onNativeLog", "(Ljava/lang/String;Ljava/lang/String;)V");
        }
    }
}

static inline char* getStringUTFChars(JNIEnv* env, jstring jstr) {
    if (!jstr) return NULL;
    return (char*)(*env)->GetStringUTFChars(env, jstr, NULL);
}

static inline void releaseStringUTFChars(JNIEnv* env, jstring jstr, char* str) {
    if (jstr && str) {
        (*env)->ReleaseStringUTFChars(env, jstr, str);
    }
}

static inline jstring newStringUTF(JNIEnv* env, const char* str) {
    if (!str) return NULL;
    return (*env)->NewStringUTF(env, str);
}

static inline JNIEnv* getJNIEnv() {
    if (!g_vm) return NULL;
    JNIEnv* env_ptr = NULL;
    int status = (*g_vm)->GetEnv(g_vm, &env_ptr, JNI_VERSION_1_6);
    if (status != JNI_OK) {
        status = (*g_vm)->AttachCurrentThreadAsDaemon(g_vm, &env_ptr, NULL);
        if (status != JNI_OK) return NULL;
    }
    return env_ptr;
}

static inline int callProtectSocket(int fd) {
    JNIEnv* env = getJNIEnv();
    if (!env) return 0;
    if (!g_bridgeClass || !g_protectMid) {
        jclass clazz = (*env)->FindClass(env, "id/my/mub/service/NativeCoreBridge");
        if (clazz) {
            g_bridgeClass = (jclass)(*env)->NewGlobalRef(env, clazz);
            g_protectMid = (*env)->GetStaticMethodID(env, g_bridgeClass, "protectSocket", "(I)Z");
            g_logMid = (*env)->GetStaticMethodID(env, g_bridgeClass, "onNativeLog", "(Ljava/lang/String;Ljava/lang/String;)V");
        }
    }
    jboolean res = 0;
    if (g_bridgeClass && g_protectMid) {
        res = (*env)->CallStaticBooleanMethod(env, g_bridgeClass, g_protectMid, (jint)fd);
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);
        }
    }
    return res ? 1 : 0;
}

static inline void callNativeLog(const char* tag, const char* msg) {
    JNIEnv* env = getJNIEnv();
    if (!env) return;
    if (!g_bridgeClass || !g_logMid) {
        jclass clazz = (*env)->FindClass(env, "id/my/mub/service/NativeCoreBridge");
        if (clazz) {
            g_bridgeClass = (jclass)(*env)->NewGlobalRef(env, clazz);
            g_protectMid = (*env)->GetStaticMethodID(env, g_bridgeClass, "protectSocket", "(I)Z");
            g_logMid = (*env)->GetStaticMethodID(env, g_bridgeClass, "onNativeLog", "(Ljava/lang/String;Ljava/lang/String;)V");
        }
    }
    if (g_bridgeClass && g_logMid) {
        jstring jTag = (*env)->NewStringUTF(env, tag);
        jstring jMsg = (*env)->NewStringUTF(env, msg);
        (*env)->CallStaticVoidMethod(env, g_bridgeClass, g_logMid, jTag, jMsg);
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);
        }
        if (jTag) (*env)->DeleteLocalRef(env, jTag);
        if (jMsg) (*env)->DeleteLocalRef(env, jMsg);
    }
}
*/
import "C"

import (
    "fmt"
    "unsafe"

    "github.com/mwright228/my/src/tbrutal/bridge"
)

//export Java_id_my_mub_service_NativeCoreBridge_startTunnel
func Java_id_my_mub_service_NativeCoreBridge_startTunnel(env *C.JNIEnv, clazz C.jclass, profileJSON *C.char) C.jint {
    _ = env
    _ = clazz
    _ = profileJSON
    return C.jint(bridge.StartTunnel(C.GoString(profileJSON)))
}

//export Java_id_my_mub_service_NativeCoreBridge_stopTunnel
func Java_id_my_mub_service_NativeCoreBridge_stopTunnel(env *C.JNIEnv, clazz C.jclass) {
    _ = env
    _ = clazz
    bridge.StopTunnel()
}

//export Java_id_my_mub_service_NativeCoreBridge_setSocketProtector
func Java_id_my_mub_service_NativeCoreBridge_setSocketProtector(env *C.JNIEnv, clazz C.jclass) {
    _ = env
    _ = clazz
    bridge.SetSocketProtector()
}

//export Java_id_my_mub_service_NativeCoreBridge_getLastError
func Java_id_my_mub_service_NativeCoreBridge_getLastError(env *C.JNIEnv, clazz C.jclass) *C.char {
    _ = env
    _ = clazz
    return C.CString(bridge.GetLastError())
}

//export Java_id_my_mub_service_NativeCoreBridge_probeBugHost
func Java_id_my_mub_service_NativeCoreBridge_probeBugHost(env *C.JNIEnv, clazz C.jclass, host *C.char) C.jint {
    _ = env
    _ = clazz
    return C.jint(bridge.ProbeBugHost(C.GoString(host)))
}

//export Java_id_my_mub_service_NativeCoreBridge_setSshFingerprint
func Java_id_my_mub_service_NativeCoreBridge_setSshFingerprint(env *C.JNIEnv, clazz C.jclass, fingerprint *C.char) {
    _ = env
    _ = clazz
    bridge.SetSSHFingerprint(C.GoString(fingerprint))
}

//export Java_id_my_mub_service_NativeCoreBridge_getSshFingerprint
func Java_id_my_mub_service_NativeCoreBridge_getSshFingerprint(env *C.JNIEnv, clazz C.jclass) *C.char {
    _ = env
    _ = clazz
    return C.CString(bridge.GetSSHFingerprint())
}

func protectSocket(fd int) bool {
    return C.callProtectSocket(C.int(fd)) != 0
}

func nativeLog(tag, msg string) {
    cTag := C.CString(tag)
    defer C.free(unsafe.Pointer(cTag))
    cMsg := C.CString(msg)
    defer C.free(unsafe.Pointer(cMsg))
    C.callNativeLog(cTag, cMsg)
}

func init() {
    C.initJavaVM(nil)
    _ = fmt.Sprintf
}
