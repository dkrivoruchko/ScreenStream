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
    using screenstream::jpeg::NativeSegmentWriter;
    using screenstream::jpeg::NativeWireStatus;
    using screenstream::jpeg::WriterFault;

    constexpr jlong kResultBlockByteCount = 16;
    constexpr std::size_t kProducedByteCountOffset = 0;
    constexpr std::size_t kWireStatusOffset = 8;
    constexpr const char *kFacadeClassName = "io/screenstream/capture/internal/encoding/NativeJpegProcess";
    constexpr const char *kSinkMethodName = "adoptNativeSegment";
    constexpr const char *kSinkMethodDescriptor = "(Ljava/nio/ByteBuffer;I)V";

    class ResultChannel final {
    public:
        ResultChannel(JNIEnv *env, jobject resultBlock) noexcept {
            if (env == nullptr || env->ExceptionCheck() || resultBlock == nullptr) return;
            void *address = env->GetDirectBufferAddress(resultBlock);
            if (env->ExceptionCheck() || address == nullptr) return;
            const jlong capacity = env->GetDirectBufferCapacity(resultBlock);
            if (env->ExceptionCheck() || capacity != kResultBlockByteCount) return;
            address_ = static_cast<std::uint8_t *>(address);
        }

        [[nodiscard]] bool armed() const noexcept { return address_ != nullptr; }

        void complete(NativeWireStatus wireStatus, std::int64_t producedByteCount) noexcept {
            if (address_ == nullptr) return;
            const auto wireStatusValue = static_cast<std::int64_t>(wireStatus);
            std::memcpy(address_ + kProducedByteCountOffset, &producedByteCount, sizeof(producedByteCount));
            std::memcpy(address_ + kWireStatusOffset, &wireStatusValue, sizeof(wireStatusValue));
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

    jobject nativeAllocateCarrier(JNIEnv *env, jobject, jlong carrierByteCount) noexcept {
        try {
            if (env == nullptr || env->ExceptionCheck()) return nullptr;
            if (carrierByteCount <= 0 ||
                static_cast<std::uint64_t>(carrierByteCount) > std::numeric_limits<std::size_t>::max()) {
                throwNew(env, "java/lang/IllegalArgumentException", "native carrier byte count is invalid");
                return nullptr;
            }
            const auto allocationSize = static_cast<std::size_t>(carrierByteCount);
            void *allocation = std::malloc(allocationSize);
            if (allocation == nullptr) {
                throwNew(env, "java/lang/OutOfMemoryError", "native carrier allocation failed");
                return nullptr;
            }
            jobject carrierBuffer = env->NewDirectByteBuffer(allocation, carrierByteCount);
            if (carrierBuffer == nullptr || env->ExceptionCheck()) {
                std::free(allocation);
                if (carrierBuffer != nullptr) env->DeleteLocalRef(carrierBuffer);
                if (!env->ExceptionCheck()) {
                    throwNew(env, "java/lang/IllegalStateException", "native carrier direct view creation failed");
                }
                return nullptr;
            }
            return carrierBuffer;
        } catch (const std::bad_alloc &) {
            throwNew(env, "java/lang/OutOfMemoryError", "native carrier allocation failed");
            return nullptr;
        } catch (...) {
            throwNew(env, "java/lang/IllegalStateException", "native carrier allocation failed internally");
            return nullptr;
        }
    }

    void nativeFreeCarrier(JNIEnv *env, jobject, jobject carrierBuffer) noexcept {
        try {
            if (env == nullptr || env->ExceptionCheck()) return;
            if (carrierBuffer == nullptr) {
                throwNew(env, "java/lang/IllegalArgumentException", "native carrier is missing");
                return;
            }
            void *address = env->GetDirectBufferAddress(carrierBuffer);
            if (env->ExceptionCheck()) return;
            if (address == nullptr) {
                throwNew(env, "java/lang/IllegalArgumentException", "native carrier is not a positive direct range");
                return;
            }
            const jlong capacity = env->GetDirectBufferCapacity(carrierBuffer);
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

    jboolean nativeHasWeakCompressor(JNIEnv *env, jobject) noexcept {
        try {
            if (env == nullptr || env->ExceptionCheck()) return JNI_FALSE;
            if (__builtin_available(android 30, *)) {
                auto compressor = &AndroidBitmap_compress;
                if (compressor != nullptr) return JNI_TRUE;
            }
            return JNI_FALSE;
        } catch (...) {
            throwNew(env, "java/lang/IllegalStateException", "native compressor capability failed internally");
            return JNI_FALSE;
        }
    }

    bool validateDescriptor(
            jobject carrierBuffer,
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
        if (carrierBuffer == nullptr || sink == nullptr || pixelByteCount <= 0 || width <= 0 || height <= 0 ||
            stride <= 0) {
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

    void compressAndTransferSegments(
            JNIEnv *env,
            jobject sink,
            jmethodID adoptMethod,
            NativeFrameDescriptor descriptor,
            CompressorFunction compressor,
            ResultChannel &result
    ) noexcept {
        std::int64_t producedByteCount = 0;
        try {
            NativeSegmentWriter writer;

            NativeWireStatus finalWireStatus = NativeWireStatus::InternalFailure;
            try {
                const CompressionResult compressionResult =
                        screenstream::jpeg::compressFrame(descriptor, compressor, writer);
                producedByteCount = compressionResult.producedByteCount;

                const bool compressionLeftPendingJavaThrowable = env->ExceptionCheck();
                finalWireStatus = screenstream::jpeg::classifyInitialWireStatus(
                        compressionResult,
                        compressionLeftPendingJavaThrowable
                );
                if (finalWireStatus == NativeWireStatus::NativeTransferComplete) {
                    while (NativeSegment *nativeSegment = writer.firstSegment()) {
                        const auto segmentByteCount = static_cast<jint>(nativeSegment->segmentByteCount);
                        jobject nativeSegmentView = env->NewDirectByteBuffer(nativeSegment->payload(), segmentByteCount);
                        if (nativeSegmentView == nullptr || env->ExceptionCheck()) {
                            const bool directViewLeftPendingJavaThrowable = env->ExceptionCheck();
                            if (nativeSegmentView != nullptr) env->DeleteLocalRef(nativeSegmentView);
                            if (!writer.freeFrontSegment(nativeSegment)) {
                                finalWireStatus = NativeWireStatus::InternalFailure;
                            } else {
                                finalWireStatus = directViewLeftPendingJavaThrowable ? NativeWireStatus::JavaThrowable : NativeWireStatus::InternalFailure;
                            }
                            break;
                        }

                        env->CallVoidMethod(sink, adoptMethod, nativeSegmentView, segmentByteCount);
                        const bool adoptionLeftPendingJavaThrowable = env->ExceptionCheck();
                        env->DeleteLocalRef(nativeSegmentView);
                        if (!writer.freeFrontSegment(nativeSegment)) {
                            finalWireStatus = NativeWireStatus::InternalFailure;
                            break;
                        }
                        if (adoptionLeftPendingJavaThrowable) {
                            finalWireStatus = NativeWireStatus::JavaThrowable;
                            break;
                        }
                    }
                    if (finalWireStatus == NativeWireStatus::NativeTransferComplete && writer.firstSegment() != nullptr) {
                        finalWireStatus = NativeWireStatus::InternalFailure;
                    }
                }

                if (env->ExceptionCheck() && finalWireStatus != NativeWireStatus::JavaThrowable &&
                    finalWireStatus != NativeWireStatus::InternalFailure) {
                    finalWireStatus = NativeWireStatus::JavaThrowable;
                }
            } catch (const std::bad_alloc &) {
                producedByteCount = writer.producedByteCount();
                finalWireStatus = NativeWireStatus::InternalFailure;
            } catch (...) {
                producedByteCount = writer.producedByteCount();
                finalWireStatus = NativeWireStatus::InternalFailure;
            }

            const bool closed = writer.close() && writer.closed();
            if (!closed || writer.fault() == WriterFault::InternalFailure ||
                (finalWireStatus == NativeWireStatus::SafeCompressorRejection &&
                 writer.fault() != WriterFault::None)) {
                finalWireStatus = NativeWireStatus::InternalFailure;
            }
            result.complete(finalWireStatus, producedByteCount);
        } catch (const std::bad_alloc &) {
            result.complete(NativeWireStatus::InternalFailure, producedByteCount);
        } catch (...) {
            result.complete(NativeWireStatus::InternalFailure, producedByteCount);
        }
    }

    void nativeCompress(
            JNIEnv *env,
            jobject,
            jobject carrierBuffer,
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

        try {
            if (!validateDescriptor(carrierBuffer, pixelByteCount, width, height, stride, format, flags, dataspace, compressFormat, quality, sink)) {
                result.complete(NativeWireStatus::InternalFailure, 0);
                return;
            }

            void *pixelAddress = env->GetDirectBufferAddress(carrierBuffer);
            if (env->ExceptionCheck() || pixelAddress == nullptr) {
                result.complete(NativeWireStatus::InternalFailure, 0);
                return;
            }
            const jlong pixelCapacity = env->GetDirectBufferCapacity(carrierBuffer);
            if (env->ExceptionCheck() || pixelCapacity != pixelByteCount) {
                result.complete(NativeWireStatus::InternalFailure, 0);
                return;
            }

            jclass sinkClass = env->GetObjectClass(sink);
            if (sinkClass == nullptr || env->ExceptionCheck()) {
                if (sinkClass != nullptr) env->DeleteLocalRef(sinkClass);
                result.complete(NativeWireStatus::InternalFailure, 0);
                return;
            }
            jmethodID adoptMethod = env->GetMethodID(sinkClass, kSinkMethodName, kSinkMethodDescriptor);
            env->DeleteLocalRef(sinkClass);
            if (adoptMethod == nullptr || env->ExceptionCheck()) {
                result.complete(NativeWireStatus::InternalFailure, 0);
                return;
            }

            NativeFrameDescriptor descriptor{};
            descriptor.bitmapInfo.width = static_cast<std::uint32_t>(width);
            descriptor.bitmapInfo.height = static_cast<std::uint32_t>(height);
            descriptor.bitmapInfo.stride = static_cast<std::uint32_t>(stride);
            descriptor.bitmapInfo.format = static_cast<std::int32_t>(format);
            descriptor.bitmapInfo.flags = static_cast<std::uint32_t>(flags);
            descriptor.dataspace = static_cast<std::int32_t>(dataspace);
            descriptor.compressFormat = static_cast<std::int32_t>(compressFormat);
            descriptor.quality = static_cast<std::int32_t>(quality);
            descriptor.pixels = pixelAddress;

            if (__builtin_available(android 30, *)) {
                auto compressor = &AndroidBitmap_compress;
                if (compressor != nullptr) {
                    compressAndTransferSegments(env, sink, adoptMethod, descriptor, compressor, result);
                    return;
                }
            }
            result.complete(NativeWireStatus::InternalFailure, 0);
        } catch (const std::bad_alloc &) {
            result.complete(NativeWireStatus::InternalFailure, 0);
        } catch (...) {
            result.complete(NativeWireStatus::InternalFailure, 0);
        }
    }

    const JNINativeMethod kRuntimeMethods[] = {
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
                            "(Ljava/nio/ByteBuffer;JIIIIJIIILio/screenstream/capture/internal/"
                            "encoding/NativeSegmentSink;Ljava/nio/ByteBuffer;)V"
                    ),
                    reinterpret_cast<void *>(nativeCompress),
            },
    };
    constexpr std::size_t kRuntimeMethodArraySize = sizeof(kRuntimeMethods) / sizeof(kRuntimeMethods[0]);
    static_assert(kRuntimeMethodArraySize == 4);
    static_assert(kRuntimeMethodArraySize <= static_cast<std::size_t>(std::numeric_limits<jint>::max()));
    constexpr jint kRuntimeMethodCount = static_cast<jint>(kRuntimeMethodArraySize);

}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
    try {
        JNIEnv *env = nullptr;
        if (vm == nullptr ||
            vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK ||
            env == nullptr) {
            return JNI_ERR;
        }

        jclass facadeClass = env->FindClass(kFacadeClassName);
        if (facadeClass == nullptr) return JNI_ERR;
        if (env->ExceptionCheck()) {
            env->DeleteLocalRef(facadeClass);
            return JNI_ERR;
        }

        const jint registration = env->RegisterNatives(facadeClass, kRuntimeMethods, kRuntimeMethodCount);
        env->DeleteLocalRef(facadeClass);
        if (registration != JNI_OK || env->ExceptionCheck()) return JNI_ERR;
        return JNI_VERSION_1_6;
    } catch (...) {
        return JNI_ERR;
    }
}
