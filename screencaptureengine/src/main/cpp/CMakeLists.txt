cmake_minimum_required(VERSION 3.22.1)

project(screen_capture_engine LANGUAGES CXX)

if (NOT ANDROID_WEAK_API_DEFS)
    message(FATAL_ERROR "ANDROID_WEAK_API_DEFS=ON is required")
endif ()

add_library(screencaptureengine_runtime OBJECT
        native_jpeg_runtime.cpp
)

set_target_properties(screencaptureengine_runtime PROPERTIES
        POSITION_INDEPENDENT_CODE ON
        CXX_VISIBILITY_PRESET hidden
        VISIBILITY_INLINES_HIDDEN YES
)

target_compile_features(screencaptureengine_runtime PRIVATE cxx_std_17)
target_compile_options(screencaptureengine_runtime PRIVATE
        -Wall
        -Wextra
        -Wpedantic
        -Werror
        -Wunguarded-availability
)

add_library(screencaptureengine SHARED
        screen_capture_engine_jni.cpp
        $<TARGET_OBJECTS:screencaptureengine_runtime>
)

set_target_properties(screencaptureengine PROPERTIES
        CXX_VISIBILITY_PRESET hidden
        VISIBILITY_INLINES_HIDDEN YES
)

target_compile_features(screencaptureengine PRIVATE cxx_std_17)
target_compile_options(screencaptureengine PRIVATE
        -Wall
        -Wextra
        -Wpedantic
        -Werror
        -Wunguarded-availability
)

find_library(jnigraphics_library jnigraphics REQUIRED)

target_link_libraries(screencaptureengine PRIVATE
        ${jnigraphics_library}
)

target_link_options(screencaptureengine PRIVATE
        "-Wl,--no-undefined"
        "-Wl,-z,max-page-size=16384"
        "-Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/screencaptureengine.map.txt"
)
