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
    void* env_ptr = NULL;
    int status = (*g_vm)->GetEnv(g_vm, &env_ptr, JNI_VERSION_1_6);
    if (status != JNI_OK) {
        status = (*g_vm)->AttachCurrentThreadAsDaemon(g_vm, &env_ptr, NULL);
        if (status != JNI_OK) return NULL;
    }
    return (JNIEnv*)env_ptr;
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
    "strings"
    "unsafe"

    "github.com/mwright228/my/src/tbrutal/bridge"
)

func init() {
    bridge.SetSocketProtector(func(fd int) bool {
        return C.callProtectSocket(C.int(fd)) != 0
    })
    bridge.SetLogger(func(tag, msg string) {
        cTag := C.CString(tag)
        cMsg := C.CString(msg)
        defer C.free(unsafe.Pointer(cTag))
        defer C.free(unsafe.Pointer(cMsg))
        C.callNativeLog(cTag, cMsg)
    })
}

func safeGoString(cStr *C.char) string {
    if cStr == nil {
        return ""
    }
    return C.GoString(cStr)
}

//export Java_id_my_mub_service_NativeCoreBridge_nativeSetSSHHostKeySHA256
func Java_id_my_mub_service_NativeCoreBridge_nativeSetSSHHostKeySHA256(
    env *C.JNIEnv,
    clazz C.jclass,
    jFingerprint C.jstring,
) {
    C.initJavaVM(env)
    cFingerprint := C.getStringUTFChars(env, jFingerprint)
    defer C.releaseStringUTFChars(env, jFingerprint, cFingerprint)
    bridge.SetSSHHostKeySHA256(safeGoString(cFingerprint))
}

//export Java_id_my_mub_service_NativeCoreBridge_nativeStartTunnel
func Java_id_my_mub_service_NativeCoreBridge_nativeStartTunnel(
    env *C.JNIEnv,
    clazz C.jclass,
    jProtocol C.jstring,
    jServerAddr C.jstring,
    jSNI C.jstring,
    jHostHeader C.jstring,
    jToken C.jstring,
    jPoolSize C.jint,
    jRateMbps C.jint,
    jUseTLS C.jboolean,
    jInsecureTLS C.jboolean,
    jRawMode C.jboolean,
    jObfsKey C.jstring,
    jPortHopRange C.jstring,
    jDNSServer C.jstring,
    jCustomPayload C.jstring,
) C.jint {
    C.initJavaVM(env)

    cProtocol := C.getStringUTFChars(env, jProtocol)
    defer C.releaseStringUTFChars(env, jProtocol, cProtocol)
    cServerAddr := C.getStringUTFChars(env, jServerAddr)
    defer C.releaseStringUTFChars(env, jServerAddr, cServerAddr)
    cSNI := C.getStringUTFChars(env, jSNI)
    defer C.releaseStringUTFChars(env, jSNI, cSNI)
    cHostHeader := C.getStringUTFChars(env, jHostHeader)
    defer C.releaseStringUTFChars(env, jHostHeader, cHostHeader)
    cToken := C.getStringUTFChars(env, jToken)
    defer C.releaseStringUTFChars(env, jToken, cToken)
    cObfsKey := C.getStringUTFChars(env, jObfsKey)
    defer C.releaseStringUTFChars(env, jObfsKey, cObfsKey)
    cPortHopRange := C.getStringUTFChars(env, jPortHopRange)
    defer C.releaseStringUTFChars(env, jPortHopRange, cPortHopRange)
    cDNSServer := C.getStringUTFChars(env, jDNSServer)
    defer C.releaseStringUTFChars(env, jDNSServer, cDNSServer)
    cCustomPayload := C.getStringUTFChars(env, jCustomPayload)
    defer C.releaseStringUTFChars(env, jCustomPayload, cCustomPayload)

    cfg := bridge.BridgeConfig{
        Protocol: safeGoString(cProtocol), ServerAddr: safeGoString(cServerAddr), SNI: safeGoString(cSNI), HostHeader: safeGoString(cHostHeader),
        PoolSize: int(jPoolSize), RateMbps: int(jRateMbps), UseTLS: jUseTLS != 0, InsecureTLS: jInsecureTLS != 0, RawMode: jRawMode != 0,
        ObfsKey: safeGoString(cObfsKey), PortHopRange: safeGoString(cPortHopRange), DNSServer: safeGoString(cDNSServer), CustomPayload: safeGoString(cCustomPayload),
        SocksListenAddr: "127.0.0.1:0",
    }
    port, err := bridge.StartTunnel(cfg)
    if err != nil { bridge.LogMsg("ERR", fmt.Sprintf("Tunnel start error: %v", err)); return C.jint(-1) }
    bridge.LogMsg("SUCCESS", fmt.Sprintf("Tunnel operational on SOCKS5 loopback port %d", port))
    return C.jint(port)
}

//export Java_id_my_mub_service_NativeCoreBridge_nativeStopTunnel
func Java_id_my_mub_service_NativeCoreBridge_nativeStopTunnel(env *C.JNIEnv, clazz C.jclass) {
    C.initJavaVM(env)
    bridge.StopTunnel()
}

//export Java_id_my_mub_service_NativeCoreBridge_nativeStartTunRouter
func Java_id_my_mub_service_NativeCoreBridge_nativeStartTunRouter(env *C.JNIEnv, clazz C.jclass, jTunFd C.jint, jSocksPort C.jint, jDNSServer C.jstring) C.jboolean {
    C.initJavaVM(env)
    cDNSServer := C.getStringUTFChars(env, jDNSServer)
    defer C.releaseStringUTFChars(env, jDNSServer, cDNSServer)
    err := bridge.StartTunRouterWithDNS(int(jTunFd), int(jSocksPort), safeGoString(cDNSServer))
    if err != nil { bridge.LogMsg("ERR", fmt.Sprintf("TunRouter failed to start: %v", err)); return C.JNI_FALSE }
    bridge.LogMsg("SUCCESS", "Layer 3 TunRouter connected and active")
    return C.JNI_TRUE
}

//export Java_id_my_mub_service_NativeCoreBridge_nativeStopTunRouter
func Java_id_my_mub_service_NativeCoreBridge_nativeStopTunRouter(env *C.JNIEnv, clazz C.jclass) {
    C.initJavaVM(env)
    bridge.StopTunRouter()
}

//export Java_id_my_mub_service_NativeCoreBridge_nativeGetTelemetry
func Java_id_my_mub_service_NativeCoreBridge_nativeGetTelemetry(env *C.JNIEnv, clazz C.jclass) C.jstring {
    C.initJavaVM(env)
    rx, tx, conns := bridge.GetTelemetry()
    formatted := fmt.Sprintf("%d|%d|%d", rx, tx, conns)
    cFormatted := C.CString(formatted)
    defer C.free(unsafe.Pointer(cFormatted))
    return C.newStringUTF(env, cFormatted)
}

//export Java_id_my_mub_service_NativeCoreBridge_nativeProbeBugHost
func Java_id_my_mub_service_NativeCoreBridge_nativeProbeBugHost(env *C.JNIEnv, clazz C.jclass, jURL C.jstring, jSNI C.jstring, jTimeoutMs C.jint) C.jstring {
    C.initJavaVM(env)
    cURL := C.getStringUTFChars(env, jURL)
    defer C.releaseStringUTFChars(env, jURL, cURL)
    cSNI := C.getStringUTFChars(env, jSNI)
    defer C.releaseStringUTFChars(env, jSNI, cSNI)
    res := bridge.ProbeBugHost(safeGoString(cURL), safeGoString(cSNI), int(jTimeoutMs))
    sansStr := strings.Join(res.CertSANs, ",")
    formatted := fmt.Sprintf("%d|%d|%s|%s|%s", res.StatusCode, res.LatencyMs, res.CertCN, sansStr, res.ErrorMsg)
    cFormatted := C.CString(formatted)
    defer C.free(unsafe.Pointer(cFormatted))
    return C.newStringUTF(env, cFormatted)
}

func main() {}
