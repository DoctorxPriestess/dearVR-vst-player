/* jni_bridge.c - thin JNI wrapper around nd_decode_to_wav.
 *
 * The Java side (com.vstplayer.core.NativeDecode) exposes the same error
 * codes; the last error text is kept in a small process wide buffer that
 * lastError()/getLastError0() hands back as a Java string.
 */
#include <jni.h>
#include <stdio.h>
#include <string.h>

#include "nd_decode.h"
#include "nd_flac_end.h"

#define ND_JNI_ERR_MAX 1024

#if defined(_WIN32)
/* The JNI bridge is also compiled for the desktop smoke test. */
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <windows.h>
static SRWLOCK nd_jni_lock = SRWLOCK_INIT;
#define ND_JNI_LOCK()   AcquireSRWLockExclusive(&nd_jni_lock)
#define ND_JNI_UNLOCK() ReleaseSRWLockExclusive(&nd_jni_lock)
#else
#include <pthread.h>
static pthread_mutex_t nd_jni_lock = PTHREAD_MUTEX_INITIALIZER;
#define ND_JNI_LOCK()   pthread_mutex_lock(&nd_jni_lock)
#define ND_JNI_UNLOCK() pthread_mutex_unlock(&nd_jni_lock)
#endif

static char nd_jni_last_error[ND_JNI_ERR_MAX];

static void nd_jni_store_error(const char *msg)
{
    ND_JNI_LOCK();
    if (msg)
        snprintf(nd_jni_last_error, sizeof(nd_jni_last_error), "%s", msg);
    else
        nd_jni_last_error[0] = 0;
    ND_JNI_UNLOCK();
}

static jint nd_jni_decode(JNIEnv *env, jclass cls, jstring in, jstring out,
                          jint max_seconds)
{
    const char *cin = NULL;
    const char *cout = NULL;
    char        err[ND_JNI_ERR_MAX];
    int         rc;

    (void)cls;
    memset(err, 0, sizeof(err));

    if (!in || !out) {
        nd_jni_store_error("missing input or output path");
        return (jint)ND_ERR_OPEN;
    }

    /* audit #8: when GetStringUTFChars fails the JVM has already raised an
     * OutOfMemoryError.  Without clearing it the Java caller never sees the
     * documented negative error code. */
    cin = (*env)->GetStringUTFChars(env, in, NULL);
    if (!cin) {
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        nd_jni_store_error("out of memory converting input path");
        return (jint)ND_ERR_OPEN;
    }
    cout = (*env)->GetStringUTFChars(env, out, NULL);
    if (!cout) {
        (*env)->ReleaseStringUTFChars(env, in, cin);
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        nd_jni_store_error("out of memory converting output path");
        return (jint)ND_ERR_OPEN;
    }

    rc = nd_decode_to_wav(cin, cout, (int)max_seconds, err, (int)sizeof(err));

    (*env)->ReleaseStringUTFChars(env, out, cout);
    (*env)->ReleaseStringUTFChars(env, in, cin);

    nd_jni_store_error(err);
    return (jint)rc;
}

JNIEXPORT jint JNICALL
Java_com_vstplayer_core_NativeDecode_decodeToWav(JNIEnv *env, jclass cls,
                                                 jstring in, jstring out,
                                                 jint max_seconds)
{
    return nd_jni_decode(env, cls, in, out, max_seconds);
}

/* Long form of the same export, for VMs that resolve overloaded natives
 * through the mangled name. */
JNIEXPORT jint JNICALL
Java_com_vstplayer_core_NativeDecode_decodeToWav__Ljava_lang_String_2Ljava_lang_String_2I(
    JNIEnv *env, jclass cls, jstring in, jstring out, jint max_seconds)
{
    return nd_jni_decode(env, cls, in, out, max_seconds);
}

JNIEXPORT jstring JNICALL
Java_com_vstplayer_core_NativeDecode_getLastError0(JNIEnv *env, jclass cls)
{
    jstring ret;

    (void)cls;
    ND_JNI_LOCK();
    ret = (*env)->NewStringUTF(env, nd_jni_last_error);
    ND_JNI_UNLOCK();
    return ret;
}

/* WAV -> FLAC for the export step. Same error buffer as the decoder, so
 * NativeDecode.lastError() reports whichever path failed last. */
static jint nd_jni_encode_flac(JNIEnv *env, jclass cls, jstring in, jstring out, jint level)
{
    const char *cin = NULL;
    const char *cout = NULL;
    char        err[ND_JNI_ERR_MAX];
    int         rc;

    (void)cls;
    memset(err, 0, sizeof(err));

    if (!in || !out) {
        nd_jni_store_error("missing input or output path");
        return (jint)ND_ENC_ERR_OPEN;
    }
    cin = (*env)->GetStringUTFChars(env, in, NULL);
    if (!cin) {
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        nd_jni_store_error("out of memory converting input path");
        return (jint)ND_ENC_ERR_OPEN;
    }
    cout = (*env)->GetStringUTFChars(env, out, NULL);
    if (!cout) {
        (*env)->ReleaseStringUTFChars(env, in, cin);
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        nd_jni_store_error("out of memory converting output path");
        return (jint)ND_ENC_ERR_OPEN;
    }

    rc = nd_encode_flac(cin, cout, (int)level, err, (int)sizeof(err));

    (*env)->ReleaseStringUTFChars(env, out, cout);
    (*env)->ReleaseStringUTFChars(env, in, cin);

    nd_jni_store_error(err);
    return (jint)rc;
}

JNIEXPORT jint JNICALL
Java_com_vstplayer_core_NativeDecode_encodeFlac(JNIEnv *env, jclass cls,
                                                jstring in, jstring out, jint level)
{
    return nd_jni_encode_flac(env, cls, in, out, level);
}

JNIEXPORT jint JNICALL
Java_com_vstplayer_core_NativeDecode_encodeFlac__Ljava_lang_String_2Ljava_lang_String_2I(
    JNIEnv *env, jclass cls, jstring in, jstring out, jint level)
{
    return nd_jni_encode_flac(env, cls, in, out, level);
}
