cmake_minimum_required(VERSION 4.1.2)

project(screen_capture_engine LANGUAGES CXX)

if (NOT ANDROID_WEAK_API_DEFS)
    message(FATAL_ERROR "ANDROID_WEAK_API_DEFS=ON is required")
endif ()

add_library(screen_capture_engine SHARED
        native_jpeg_runtime.cpp
        screen_capture_engine_jni.cpp
)

set_target_properties(screen_capture_engine PROPERTIES
        CXX_VISIBILITY_PRESET hidden
        VISIBILITY_INLINES_HIDDEN YES
)

target_compile_features(screen_capture_engine PRIVATE cxx_std_17)
target_compile_options(screen_capture_engine PRIVATE
        -Wall
        -Wextra
        -Wpedantic
        -Werror
)

find_library(jnigraphics_library jnigraphics REQUIRED)

target_link_libraries(screen_capture_engine PRIVATE
        ${jnigraphics_library}
)

target_link_options(screen_capture_engine PRIVATE
        "-Wl,--no-undefined"
        "-Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/screen_capture_engine.map.txt"
)
