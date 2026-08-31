#ifndef SCREEN_CAPTURE_ENGINE_NATIVE_JPEG_RUNTIME_H_
#define SCREEN_CAPTURE_ENGINE_NATIVE_JPEG_RUNTIME_H_

#include <android/bitmap.h>
#include <android/data_space.h>

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <mutex>

namespace screenstream::jpeg {

    using CompressWriteFunction = bool (*)(void *, const void *, std::size_t);
    using SegmentAllocateFunction = void *(*)(std::size_t);
    using SegmentFreeFunction = void (*)(void *) noexcept;

    inline constexpr std::size_t kNativeSegmentPayloadCapacity = 65'536;

    enum class WriterFault : std::int32_t {
        None = 0,
        NativeOutOfMemory = 1,
        InternalFailure = 2,
    };

    enum class NativeWireStatus : std::int64_t {
        NativeTransferComplete = 0,
        SafeCompressorRejection = 1,
        NativeOutOfMemory = 2,
        InternalFailure = 3,
        JavaThrowable = 4,
    };

    struct NativeFrameDescriptor final {
        AndroidBitmapInfo bitmapInfo{};
        std::int32_t dataspace = ADATASPACE_UNKNOWN;
        std::int32_t compressFormat = ANDROID_BITMAP_COMPRESS_FORMAT_JPEG;
        std::int32_t quality = 0;
        const void *pixels = nullptr;
    };

    struct NativeSegment final {
        std::int32_t segmentByteCount = 0;

        std::uint8_t *payload() noexcept {
            return reinterpret_cast<std::uint8_t *>(this) + sizeof(NativeSegment);
        }

        [[nodiscard]] const std::uint8_t *payload() const noexcept {
            return reinterpret_cast<const std::uint8_t *>(this) + sizeof(NativeSegment);
        }

    private:
        NativeSegment *next_ = nullptr;

        friend class NativeSegmentWriter;
    };

    class NativeSegmentWriter final {
    public:
        NativeSegmentWriter() noexcept = default;

        NativeSegmentWriter(
                SegmentAllocateFunction allocateFunction,
                SegmentFreeFunction freeFunction
        ) noexcept;

        ~NativeSegmentWriter() noexcept;

        NativeSegmentWriter(const NativeSegmentWriter &) = delete;

        NativeSegmentWriter &operator=(const NativeSegmentWriter &) = delete;

        static bool write(void *context, const void *data, std::size_t size) noexcept;

        void recordInternalFailure() noexcept;

        bool freezeAfterCompression() noexcept;

        NativeSegment *firstSegment() const noexcept;

        bool freeFrontSegment(NativeSegment *expected) noexcept;

        bool close() noexcept;

        WriterFault fault() const noexcept;

        std::int64_t producedByteCount() const noexcept;

        bool closed() const noexcept;

    private:
        enum class Lifecycle {
            Open,
            Frozen,
            Closed,
        };

        bool append(const void *data, std::size_t size) noexcept;

        void recordOutOfMemory() noexcept;

        [[nodiscard]] bool validateChainLocked() const noexcept;

        [[nodiscard]] bool validateListLocked() const noexcept;

        [[nodiscard]] bool freeChainLocked() noexcept;

        static void defaultFree(void *allocation) noexcept;

        mutable std::mutex mutex_;
        std::atomic<std::int32_t> fault_{static_cast<std::int32_t>(WriterFault::None)};
        NativeSegment *head_ = nullptr;
        NativeSegment *tail_ = nullptr;
        std::size_t segmentCount_ = 0;
        std::int64_t producedByteCount_ = 0;
        Lifecycle lifecycle_ = Lifecycle::Open;
        SegmentAllocateFunction allocateFunction_ = &std::malloc;
        SegmentFreeFunction freeFunction_ = &NativeSegmentWriter::defaultFree;
    };

    using CompressorFunction = int32_t (*)(
            const AndroidBitmapInfo *,
            int32_t,
            const void *,
            int32_t,
            int32_t,
            void *,
            CompressWriteFunction
    );

    struct CompressionResult final {
        std::int32_t androidBitmapResult = ANDROID_BITMAP_RESULT_BAD_PARAMETER;
        WriterFault writerFault = WriterFault::InternalFailure;
        std::int64_t producedByteCount = 0;
        bool writerFrozen = false;
    };

    [[nodiscard]] NativeWireStatus classifyInitialWireStatus(
            const CompressionResult &compressionResult,
            bool compressionLeftPendingJavaThrowable
    ) noexcept;

    CompressionResult compressFrame(
            const NativeFrameDescriptor &descriptor,
            CompressorFunction compressor,
            NativeSegmentWriter &writer
    ) noexcept;

}

#endif
