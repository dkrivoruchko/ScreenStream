#ifndef SCREEN_CAPTURE_ENGINE_TEST_JNI_H_
#define SCREEN_CAPTURE_ENGINE_TEST_JNI_H_

// Test-only JNI 1.6 subset. Types, function-table signatures, and C++ member
// wrappers match the NDK 29 jni.h surface used by screen_capture_engine_jni.cpp.

#include <stdarg.h>
#include <stdint.h>

typedef uint8_t jboolean;
typedef int8_t jbyte;
typedef uint16_t jchar;
typedef int16_t jshort;
typedef int32_t jint;
typedef int64_t jlong;
typedef float jfloat;
typedef double jdouble;
typedef jint jsize;

class _jobject {
};

class _jclass : public _jobject {
};

class _jthrowable : public _jobject {
};

typedef _jobject *jobject;
typedef _jclass *jclass;
typedef _jthrowable *jthrowable;

struct _jmethodID;
typedef _jmethodID *jmethodID;

typedef struct {
    const char *name;
    const char *signature;
    void *fnPtr;
} JNINativeMethod;

struct JNINativeInterface;
struct JNIInvokeInterface;
struct _JNIEnv;
struct _JavaVM;

typedef _JNIEnv JNIEnv;
typedef _JavaVM JavaVM;

struct JNINativeInterface {
    void *reserved0;
    void *reserved1;
    void *reserved2;
    void *reserved3;

    jclass (*FindClass)(JNIEnv *, const char *);

    jint (*ThrowNew)(JNIEnv *, jclass, const char *);

    void (*DeleteLocalRef)(JNIEnv *, jobject);

    jclass (*GetObjectClass)(JNIEnv *, jobject);

    jmethodID (*GetMethodID)(JNIEnv *, jclass, const char *, const char *);

    void (*CallVoidMethodV)(JNIEnv *, jobject, jmethodID, va_list);

    jint (*RegisterNatives)(JNIEnv *, jclass, const JNINativeMethod *, jint);

    jboolean (*ExceptionCheck)(JNIEnv *);

    jobject (*NewDirectByteBuffer)(JNIEnv *, void *, jlong);

    void *(*GetDirectBufferAddress)(JNIEnv *, jobject);

    jlong (*GetDirectBufferCapacity)(JNIEnv *, jobject);
};

struct _JNIEnv {
    const JNINativeInterface *functions;

    jclass FindClass(const char *name) {
        return functions->FindClass(this, name);
    }

    jint ThrowNew(jclass clazz, const char *message) {
        return functions->ThrowNew(this, clazz, message);
    }

    void DeleteLocalRef(jobject localRef) {
        functions->DeleteLocalRef(this, localRef);
    }

    jclass GetObjectClass(jobject object) {
        return functions->GetObjectClass(this, object);
    }

    jmethodID GetMethodID(jclass clazz, const char *name, const char *signature) {
        return functions->GetMethodID(this, clazz, name, signature);
    }

    void CallVoidMethod(jobject object, jmethodID methodID, ...) {
        va_list arguments;
        va_start(arguments, methodID);
        functions->CallVoidMethodV(this, object, methodID, arguments);
        va_end(arguments);
    }

    jint RegisterNatives(jclass clazz, const JNINativeMethod *methods, jint methodCount) {
        return functions->RegisterNatives(this, clazz, methods, methodCount);
    }

    jboolean ExceptionCheck() {
        return functions->ExceptionCheck(this);
    }

    jobject NewDirectByteBuffer(void *address, jlong capacity) {
        return functions->NewDirectByteBuffer(this, address, capacity);
    }

    void *GetDirectBufferAddress(jobject buffer) {
        return functions->GetDirectBufferAddress(this, buffer);
    }

    jlong GetDirectBufferCapacity(jobject buffer) {
        return functions->GetDirectBufferCapacity(this, buffer);
    }
};

struct JNIInvokeInterface {
    void *reserved0;
    void *reserved1;
    void *reserved2;

    jint (*GetEnv)(JavaVM *, void **, jint);
};

struct _JavaVM {
    const JNIInvokeInterface *functions;

    jint GetEnv(void **environment, jint version) {
        return functions->GetEnv(this, environment, version);
    }
};

#define JNI_FALSE 0
#define JNI_TRUE 1

#define JNI_VERSION_1_6 0x00010006

#define JNI_OK 0
#define JNI_ERR (-1)
#define JNI_EVERSION (-3)

#define JNIEXPORT __attribute__((visibility("default")))
#define JNICALL

#endif
