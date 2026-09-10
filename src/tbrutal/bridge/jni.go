//go:build android && cgo

package bridge

/*
#include <jni.h>
#include <stdlib.h>

static char* getStringUTFChars(JNIEnv* env, jstring jstr) {
    if (!jstr) return NULL;
    return (char*)(*env)->GetStringUTFChars(env, jstr, NULL);
}

static void releaseStringUTFChars(JNIEnv* env, jstring jstr, char* str) {
    if (jstr && str) {
        (*env)->ReleaseStringUTFChars(env, jstr, str);
    }
}
*/
import "C"
import (
	"fmt"
	"strings"
	"unsafe"
)

//export Java_id_my_mub_service_NativeCoreBridge_nativeStartTunnel
func Java_id_my_mub_service_NativeCoreBridge_nativeStartTunnel(
	env *C.JNIEnv,
	clazz C.jclass,
	jServerAddr C.jstring,
	jSNI C.jstring,
	jHostHeader C.jstring,
	jToken C.jstring,
	jPoolSize C.jint,
	jRateMbps C.jint,
	jUseTLS C.jboolean,
	jInsecureTLS C.jboolean,
	jRawMode C.jboolean,
) C.jint {
	cServerAddr := C.getStringUTFChars(env, jServerAddr)
	defer C.releaseStringUTFChars(env, jServerAddr, cServerAddr)

	cSNI := C.getStringUTFChars(env, jSNI)
	defer C.releaseStringUTFChars(env, jSNI, cSNI)

	cHostHeader := C.getStringUTFChars(env, jHostHeader)
	defer C.releaseStringUTFChars(env, jHostHeader, cHostHeader)

	cToken := C.getStringUTFChars(env, jToken)
	defer C.releaseStringUTFChars(env, jToken, cToken)

	cfg := BridgeConfig{
		ServerAddr:      C.GoString(cServerAddr),
		SNI:             C.GoString(cSNI),
		HostHeader:      C.GoString(cHostHeader),
		Token:           C.GoString(cToken),
		PoolSize:        int(jPoolSize),
		RateMbps:        int(jRateMbps),
		UseTLS:          jUseTLS != 0,
		InsecureTLS:     jInsecureTLS != 0,
		RawMode:         jRawMode != 0,
		SocksListenAddr: "127.0.0.1:0",
	}

	port, err := StartTunnel(cfg)
	if err != nil {
		return C.jint(-1)
	}
	return C.jint(port)
}

//export Java_id_my_mub_service_NativeCoreBridge_nativeStopTunnel
func Java_id_my_mub_service_NativeCoreBridge_nativeStopTunnel(env *C.JNIEnv, clazz C.jclass) {
	StopTunnel()
}

//export Java_id_my_mub_service_NativeCoreBridge_nativeProbeBugHost
func Java_id_my_mub_service_NativeCoreBridge_nativeProbeBugHost(
	env *C.JNIEnv,
	clazz C.jclass,
	jURL C.jstring,
	jSNI C.jstring,
	jTimeoutMs C.jint,
) C.jstring {
	cURL := C.getStringUTFChars(env, jURL)
	defer C.releaseStringUTFChars(env, jURL, cURL)

	cSNI := C.getStringUTFChars(env, jSNI)
	defer C.releaseStringUTFChars(env, jSNI, cSNI)

	urlStr := C.GoString(cURL)
	sniStr := C.GoString(cSNI)

	res := ProbeBugHost(urlStr, sniStr, int(jTimeoutMs))

	// Format: "STATUS|LATENCY|CN|SANS|ERR"
	sansStr := strings.Join(res.CertSANs, ",")
	formatted := fmt.Sprintf("%d|%d|%s|%s|%s", res.StatusCode, res.LatencyMs, res.CertCN, sansStr, res.ErrorMsg)

	cFormatted := C.CString(formatted)
	defer C.free(unsafe.Pointer(cFormatted))

	return (*(*env)).NewStringUTF(env, cFormatted)
}
