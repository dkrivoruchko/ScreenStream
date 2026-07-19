#include "native_jpeg_runtime.h"

#include <android/bitmap.h>
#include <android/data_space.h>
#include <jni.h>

#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <limits>
#include <new>

namespace {

    using screenstream::jpeg::CompressionResult;
    using screenstream::jpeg::CompressorFunction;
    using screenstream::jpeg::NativeFrameDescriptor;
    using screenstream::jpeg::NativeSegment;
    using screenstream::jpeg::WriterCapsule;
    using screenstream::jpeg::WriterFault;

    enum class NativeStatus : std::int64_t {
        NativeTransferComplete = 0,
        SafeCompressorAllocationFailure = 1,
        NativeOutOfMemory = 2,
        InternalFailure = 3,
        JavaThrowable = 4,
    };

    constexpr std::int32_t kBootstrapSuccess = JNI_OK;
    constexpr std::int32_t kBootstrapInternalFailure = 3;
    constexpr jlong kResultBlockByteCount = 16;
    constexpr std::size_t kStatusOffset = 0;
    constexpr std::size_t kProducedByteCountOffset = 8;
    constexpr const char *kSinkMethodName = "adoptNativeSegment";
    constexpr const char *kSinkMethodDescriptor = "(Ljava/nio/ByteBuffer;I)V";

    class ResultChannel final {
    public:
        ResultChannel(JNIEnv *env, jobject resultBlock) noexcept {
            if (env == nullptr || resultBlock == nullptr) return;
            void *address = env->GetDirectBufferAddress(resultBlock);
            if (env->ExceptionCheck() || address == nullptr) return;
            const jlong capacity = env->GetDirectBufferCapacity(resultBlock);
            if (env->ExceptionCheck() || capacity != kResultBlockByteCount) return;
            address_ = static_cast<std::uint8_t *>(address);
        }

        [[nodiscard]] bool armed() const noexcept { return address_ != nullptr; }

        void complete(NativeStatus status, std::int64_t producedByteCount) noexcept {
            if (address_ == nullptr) return;
            const auto statusValue = static_cast<std::int64_t>(status);
            std::memcpy(address_ + kProducedByteCountOffset, &producedByteCount, sizeof(producedByteCount));
            std::memcpy(address_ + kStatusOffset, &statusValue, sizeof(statusValue));
        }

    private:
        std::uint8_t *address_ = nullptr;
    };

    void throwNew(JNIEnv *env, const char *className, const char *message) noexcept {
        if (env == nullptr || env->ExceptionCheck()) return;
        jclass exceptionClass = env->FindClass(className);
        if (exceptionClass == nullptr) return;
        if (env->ExceptionCheck()) {
            env->DeleteLocalRef(exceptionClass);
            return;
        }
        env->ThrowNew(exceptionClass, message);
        env->DeleteLocalRef(exceptionClass);
    }

    CompressorFunction resolveCompressor() noexcept {
        if (__builtin_available(android 30, *)) {
            auto compressor = &AndroidBitmap_compress;
            if (compressor != nullptr) return compressor;
        }
        return nullptr;
    }

    jobject nativeAllocateCarrier(JNIEnv *env, jobject, jlong byteCount) noexcept {
        try {
            if (byteCount <= 0 || static_cast<std::uint64_t>(byteCount) > std::numeric_limits<std::size_t>::max()) {
                throwNew(env, "java/lang/IllegalArgumentException", "native carrier byte count is invalid");
                return nullptr;
            }
            const auto allocationSize = static_cast<std::size_t>(byteCount);
            void *allocation = std::malloc(allocationSize);
            if (allocation == nullptr) {
                throwNew(env, "java/lang/OutOfMemoryError", "native carrier allocation failed");
                return nullptr;
            }
            jobject buffer = env->NewDirectByteBuffer(allocation, byteCount);
            if (buffer == nullptr || env->ExceptionCheck()) {
                std::free(allocation);
                if (buffer != nullptr) env->DeleteLocalRef(buffer);
                if (!env->ExceptionCheck()) {
                    throwNew(env, "java/lang/IllegalStateException", "native carrier direct view creation failed");
                }
                return nullptr;
            }
            return buffer;
        } catch (const std::bad_alloc &) {
            throwNew(env, "java/lang/OutOfMemoryError", "native carrier allocation failed");
            return nullptr;
        } catch (...) {
            throwNew(env, "java/lang/IllegalStateException", "native carrier allocation failed internally");
            return nullptr;
        }
    }

    void nativeFreeCarrier(JNIEnv *env, jobject, jobject pixels) noexcept {
        try {
            if (pixels == nullptr) {
                throwNew(env, "java/lang/IllegalArgumentException", "native carrier is missing");
                return;
            }
            void *address = env->GetDirectBufferAddress(pixels);
            if (env->ExceptionCheck()) return;
            if (address == nullptr) {
                throwNew(env, "java/lang/IllegalArgumentException", "native carrier is not a positive direct range");
                return;
            }
            const jlong capacity = env->GetDirectBufferCapacity(pixels);
            if (env->ExceptionCheck() || capacity <= 0 ||
                static_cast<std::uint64_t>(capacity) > std::numeric_limits<std::size_t>::max()) {
                if (!env->ExceptionCheck()) {
                    throwNew(env, "java/lang/IllegalArgumentException", "native carrier is not a positive direct range");
                }
                return;
            }
            std::free(address);
        } catch (...) {
            throwNew(env, "java/lang/IllegalStateException", "native carrier free failed internally");
        }
    }

    jboolean nativeHasWeakCompressor(JNIEnv *, jobject) noexcept {
        try {
            return resolveCompressor() != nullptr ? JNI_TRUE : JNI_FALSE;
        } catch (...) {
            return JNI_FALSE;
        }
    }

    bool validateDescriptor(
            jobject pixels,
            jlong pixelByteCount,
            jint width,
            jint height,
            jint stride,
            jint format,
            jlong flags,
            jint dataspace,
            jint compressFormat,
            jint quality,
            jobject sink
    ) noexcept {
        if (pixels == nullptr || sink == nullptr || pixelByteCount <= 0 || width <= 0 || height <= 0 || stride <= 0) {
            return false;
        }
        const std::int64_t expectedStride = static_cast<std::int64_t>(width) * 4;
        if (expectedStride != stride || expectedStride > std::numeric_limits<std::int64_t>::max() / height ||
            expectedStride * height != pixelByteCount) {
            return false;
        }
        return format == ANDROID_BITMAP_FORMAT_RGBA_8888 &&
               flags == ANDROID_BITMAP_FLAGS_ALPHA_OPAQUE &&
               dataspace == ADATASPACE_SRGB &&
               compressFormat == ANDROID_BITMAP_COMPRESS_FORMAT_JPEG &&
               quality >= 0 && quality <= 100 &&
               pixelByteCount <= std::numeric_limits<std::int32_t>::max();
    }

    void nativeCompress(
            JNIEnv *env,
            jobject,
            jobject pixels,
            jlong pixelByteCount,
            jint width,
            jint height,
            jint stride,
            jint format,
            jlong flags,
            jint dataspace,
            jint compressFormat,
            jint quality,
            jobject sink,
            jobject resultBlock
    ) noexcept {
        ResultChannel result(env, resultBlock);
        if (!result.armed()) return;

        std::int64_t producedByteCount = 0;
        try {
            if (!validateDescriptor(
                    pixels,
                    pixelByteCount,
                    width,
                    height,
                    stride,
                    format,
                    flags,
                    dataspace,
                    compressFormat,
                    quality,
                    sink
            )) {
                result.complete(NativeStatus::InternalFailure, 0);
                return;
            }

            void *pixelAddress = env->GetDirectBufferAddress(pixels);
            if (env->ExceptionCheck() || pixelAddress == nullptr) {
                result.complete(NativeStatus::InternalFailure, 0);
                return;
            }
            const jlong pixelCapacity = env->GetDirectBufferCapacity(pixels);
            if (env->ExceptionCheck() || pixelCapacity != pixelByteCount) {
                result.complete(NativeStatus::InternalFailure, 0);
                return;
            }

            jclass sinkClass = env->GetObjectClass(sink);
            if (sinkClass == nullptr || env->ExceptionCheck()) {
                if (sinkClass != nullptr) env->DeleteLocalRef(sinkClass);
                result.complete(NativeStatus::InternalFailure, 0);
                return;
            }
            jmethodID adoptMethod = env->GetMethodID(sinkClass, kSinkMethodName, kSinkMethodDescriptor);
            env->DeleteLocalRef(sinkClass);
            if (adoptMethod == nullptr || env->ExceptionCheck()) {
                result.complete(NativeStatus::InternalFailure, 0);
                return;
            }

            CompressorFunction compressor = resolveCompressor();
            if (compressor == nullptr) {
                result.complete(NativeStatus::InternalFailure, 0);
                return;
            }

            WriterCapsule capsule;
            NativeFrameDescriptor descriptor{};
            descriptor.bitmapInfo.width = static_cast<std::uint32_t>(width);
            descriptor.bitmapInfo.height = static_cast<std::uint32_t>(height);
            descriptor.bitmapInfo.stride = static_cast<std::uint32_t>(stride);
            descriptor.bitmapInfo.format = static_cast<std::int32_t>(format);
            descriptor.bitmapInfo.flags = static_cast<std::uint32_t>(flags);
            descriptor.dataspace = dataspace;
            descriptor.compressFormat = compressFormat;
            descriptor.quality = quality;
            descriptor.pixels = pixelAddress;
            descriptor.writerFunction = &WriterCapsule::write;
            descriptor.writerContext = &capsule;

            NativeStatus finalStatus = NativeStatus::InternalFailure;
            try {
                const CompressionResult compression = screenstream::jpeg::compressFrame(descriptor, compressor, capsule);
                producedByteCount = compression.producedByteCount;

                if (!compression.frozen || compression.writerFault == WriterFault::InternalFailure) {
                    finalStatus = NativeStatus::InternalFailure;
                } else if (compression.writerFault == WriterFault::NativeOutOfMemory) {
                    finalStatus = NativeStatus::NativeOutOfMemory;
                } else if (compression.compressorResult == ANDROID_BITMAP_RESULT_ALLOCATION_FAILED) {
                    finalStatus = NativeStatus::SafeCompressorAllocationFailure;
                } else if (compression.compressorResult != ANDROID_BITMAP_RESULT_SUCCESS || producedByteCount <= 0) {
                    finalStatus = NativeStatus::InternalFailure;
                } else {
                    finalStatus = NativeStatus::NativeTransferComplete;
                    while (NativeSegment *segment = capsule.firstSegment()) {
                        jobject view = env->NewDirectByteBuffer(segment->bytes, segment->byteCount);
                        if (view == nullptr || env->ExceptionCheck()) {
                            const bool pending = env->ExceptionCheck();
                            if (view != nullptr) env->DeleteLocalRef(view);
                            if (!capsule.releaseFront(segment)) finalStatus = NativeStatus::InternalFailure;
                            else finalStatus = pending ? NativeStatus::JavaThrowable : NativeStatus::InternalFailure;
                            break;
                        }

                        env->CallVoidMethod(sink, adoptMethod, view, static_cast<jint>(segment->byteCount));
                        const bool pending = env->ExceptionCheck();
                        env->DeleteLocalRef(view);
                        if (!capsule.releaseFront(segment)) {
                            finalStatus = NativeStatus::InternalFailure;
                            break;
                        }
                        if (pending) {
                            finalStatus = NativeStatus::JavaThrowable;
                            break;
                        }
                    }
                    if (finalStatus == NativeStatus::NativeTransferComplete && capsule.firstSegment() != nullptr) {
                        finalStatus = NativeStatus::InternalFailure;
                    }
                }

                if (env->ExceptionCheck() && finalStatus != NativeStatus::JavaThrowable) {
                    finalStatus = NativeStatus::InternalFailure;
                }
            } catch (const std::bad_alloc &) {
                producedByteCount = capsule.producedByteCount();
                finalStatus = NativeStatus::InternalFailure;
            } catch (...) {
                producedByteCount = capsule.producedByteCount();
                finalStatus = NativeStatus::InternalFailure;
            }

            const bool closed = capsule.close() && capsule.closed();
            if (!closed) finalStatus = NativeStatus::InternalFailure;
            result.complete(finalStatus, producedByteCount);
        } catch (const std::bad_alloc &) {
            result.complete(NativeStatus::InternalFailure, producedByteCount);
        } catch (...) {
            result.complete(NativeStatus::InternalFailure, producedByteCount);
        }
    }

    JNINativeMethod kRuntimeMethods[] = {
            {
                    const_cast<char *>("nativeAllocateCarrier"),
                    const_cast<char *>("(J)Ljava/nio/ByteBuffer;"),
                    reinterpret_cast<void *>(nativeAllocateCarrier),
            },
            {
                    const_cast<char *>("nativeFreeCarrier"),
                    const_cast<char *>("(Ljava/nio/ByteBuffer;)V"),
                    reinterpret_cast<void *>(nativeFreeCarrier),
            },
            {
                    const_cast<char *>("nativeHasWeakCompressor"),
                    const_cast<char *>("()Z"),
                    reinterpret_cast<void *>(nativeHasWeakCompressor),
            },
            {
                    const_cast<char *>("nativeCompress"),
                    const_cast<char *>(
                            "(Ljava/nio/ByteBuffer;JIIIIJIIILio/screenstream/engine/internal/"
                            "EncodedStorageOwner$NativeSegmentSink;Ljava/nio/ByteBuffer;)V"
                    ),
                    reinterpret_cast<void *>(nativeCompress),
            },
    };

}  // namespace

extern "C" JNIEXPORT jint JNICALL
screenCaptureEngineJniOnLoad(JavaVM *, void *) noexcept __asm__("JNI_OnLoad");

extern "C" JNIEXPORT jint JNICALL screenCaptureEngineJniOnLoad(JavaVM *, void *) noexcept {
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jint JNICALL
Java_io_screenstream_engine_internal_jpeg_NativeJpegProcess_nativeBootstrap(
        JNIEnv *env,
        jobject receiver
) noexcept {
    try {
        if (env == nullptr || receiver == nullptr || env->ExceptionCheck()) return kBootstrapInternalFailure;
        jclass receiverClass = env->GetObjectClass(receiver);
        if (receiverClass == nullptr) return kBootstrapInternalFailure;
        if (env->ExceptionCheck()) {
            env->DeleteLocalRef(receiverClass);
            return kBootstrapInternalFailure;
        }
        const jint registration = env->RegisterNatives(
                receiverClass,
                kRuntimeMethods,
                static_cast<jint>(sizeof(kRuntimeMethods) / sizeof(kRuntimeMethods[0]))
        );
        env->DeleteLocalRef(receiverClass);
        if (registration != JNI_OK || env->ExceptionCheck()) return kBootstrapInternalFailure;
        return kBootstrapSuccess;
    } catch (const std::bad_alloc &) {
        return kBootstrapInternalFailure;
    } catch (...) {
        return kBootstrapInternalFailure;
    }
}
