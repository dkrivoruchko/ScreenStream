#ifndef SCREEN_CAPTURE_ENGINE_TEST_ANDROID_DATA_SPACE_H_
#define SCREEN_CAPTURE_ENGINE_TEST_ANDROID_DATA_SPACE_H_

// Test-only subset matched to NDK 29.0.14206865 android/data_space.h.

#include <stdint.h>

enum ADataSpace : int32_t {
    ADATASPACE_UNKNOWN = 0,
    ADATASPACE_SRGB = 142671872,
};

#endif
