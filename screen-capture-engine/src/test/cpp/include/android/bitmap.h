#ifndef SCREEN_CAPTURE_ENGINE_TEST_ANDROID_BITMAP_H_
#define SCREEN_CAPTURE_ENGINE_TEST_ANDROID_BITMAP_H_

// Test-only subset matched to NDK 29.0.14206865 android/bitmap.h.

#include <stdint.h>
#include <stddef.h>

enum {
    ANDROID_BITMAP_RESULT_SUCCESS = 0,
    ANDROID_BITMAP_RESULT_BAD_PARAMETER = -1,
    ANDROID_BITMAP_RESULT_JNI_EXCEPTION = -2,
    ANDROID_BITMAP_RESULT_ALLOCATION_FAILED = -3,
};

enum AndroidBitmapCompressFormat {
    ANDROID_BITMAP_COMPRESS_FORMAT_JPEG = 0,
};

enum AndroidBitmapFormat {
    ANDROID_BITMAP_FORMAT_RGBA_8888 = 1,
};

enum {
    ANDROID_BITMAP_FLAGS_ALPHA_OPAQUE = 1,
};

typedef struct {
    uint32_t width;
    uint32_t height;
    uint32_t stride;
    int32_t format;
    uint32_t flags;
} AndroidBitmapInfo;

typedef bool (*AndroidBitmap_CompressWriteFunc)(
        void *userContext,
        const void *data,
        size_t size
);

#ifdef __cplusplus
extern "C" {
#endif

int AndroidBitmap_compress(
        const AndroidBitmapInfo *info,
        int32_t dataspace,
        const void *pixels,
        int32_t format,
        int32_t quality,
        void *userContext,
        AndroidBitmap_CompressWriteFunc function
);

#ifdef __cplusplus
}
#endif

#endif
