cmake_minimum_required(VERSION 4.1.2)

project(native_jpeg_runtime_host_test LANGUAGES CXX)

option(SCE_ENABLE_SANITIZERS "Enable AddressSanitizer and UndefinedBehaviorSanitizer" OFF)

enable_testing()

function(configure_host_native_test target)
    target_compile_features(${target} PRIVATE cxx_std_17)
    target_include_directories(${target} PRIVATE
            include
            ../../main/cpp
    )
    target_compile_options(${target} PRIVATE
            -Wall
            -Wextra
            -Wpedantic
            -Werror
    )

    if (SCE_ENABLE_SANITIZERS)
        if (NOT CMAKE_CXX_COMPILER_ID MATCHES "Clang|GNU")
            message(FATAL_ERROR "SCE_ENABLE_SANITIZERS requires a Clang- or GNU-compatible compiler")
        endif ()
        target_compile_options(${target} PRIVATE
                -fsanitize=address,undefined
                -fno-omit-frame-pointer
                -fno-sanitize-recover=undefined
        )
        target_link_options(${target} PRIVATE -fsanitize=address,undefined -fno-omit-frame-pointer)
    endif ()
endfunction()

add_executable(native_jpeg_runtime_test
        native_jpeg_runtime_test.cpp
        ../../main/cpp/native_jpeg_runtime.cpp
)
configure_host_native_test(native_jpeg_runtime_test)

add_test(NAME native_jpeg_runtime_test COMMAND native_jpeg_runtime_test)

if (NOT CMAKE_CXX_COMPILER_ID MATCHES "Clang")
    message(FATAL_ERROR "screen_capture_engine_jni_test requires the Clang availability builtin used by the NDK source")
endif ()

add_executable(screen_capture_engine_jni_test
        screen_capture_engine_jni_test.cpp
        ../../main/cpp/native_jpeg_runtime.cpp
        ../../main/cpp/screen_capture_engine_jni.cpp
)
configure_host_native_test(screen_capture_engine_jni_test)

add_test(NAME screen_capture_engine_jni_test COMMAND screen_capture_engine_jni_test)
