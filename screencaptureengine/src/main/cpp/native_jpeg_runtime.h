#ifndef SCREEN_CAPTURE_ENGINE_NATIVE_JPEG_RUNTIME_H_
#define SCREEN_CAPTURE_ENGINE_NATIVE_JPEG_RUNTIME_H_

#include <android/bitmap.h>
#include <android/data_space.h>

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <mutex>

namespace screenstream::jpeg {

    using CompressWriteFunction = bool (*)(void *, const void *, std::size_t);

    enum class WriterFault : std::int32_t {
        None = 0,
        NativeOutOfMemory = 1,
        InternalFailure = 2,
    };

    struct NativeFrameDescriptor final {
        AndroidBitmapInfo bitmapInfo{};
        std::int32_t dataspace = ADATASPACE_UNKNOWN;
        std::int32_t compressFormat = ANDROID_BITMAP_COMPRESS_FORMAT_JPEG;
        std::int32_t quality = 0;
        const void *pixels = nullptr;
        CompressWriteFunction writerFunction = nullptr;
        void *writerContext = nullptr;
    };

    struct NativeSegment final {
        NativeSegment *next = nullptr;
        std::uint8_t *bytes = nullptr;
        std::int32_t byteCount = 0;
    };

    class WriterCapsule final {
    public:
        WriterCapsule() noexcept = default;

        ~WriterCapsule() noexcept;

        WriterCapsule(const WriterCapsule &) = delete;

        WriterCapsule &operator=(const WriterCapsule &) = delete;

        static bool write(void *context, const void *data, std::size_t size) noexcept;

        void recordInternalFailure() noexcept;

        bool freezeAfterCompression() noexcept;

        NativeSegment *firstSegment() const noexcept;

        bool releaseFront(NativeSegment *expected) noexcept;

        void releaseAll() noexcept;

        bool close() noexcept;

        WriterFault fault() const noexcept;

        std::int64_t producedByteCount() const noexcept;

        bool closed() const noexcept;

    private:
        bool append(const void *data, std::size_t size) noexcept;

        void recordOutOfMemory() noexcept;

        mutable std::mutex mutex_;
        std::atomic<std::int32_t> fault_{static_cast<std::int32_t>(WriterFault::None)};
        NativeSegment *head_ = nullptr;
        NativeSegment *tail_ = nullptr;
        std::int64_t producedByteCount_ = 0;
        bool accepting_ = true;
        bool frozen_ = false;
        bool closed_ = false;
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
        std::int32_t compressorResult = ANDROID_BITMAP_RESULT_BAD_PARAMETER;
        WriterFault writerFault = WriterFault::InternalFailure;
        std::int64_t producedByteCount = 0;
        bool frozen = false;
    };

    CompressionResult compressFrame(
            const NativeFrameDescriptor &descriptor,
            CompressorFunction compressor,
            WriterCapsule &capsule
    ) noexcept;

}  // namespace screenstream::jpeg

#endif  // SCREEN_CAPTURE_ENGINE_NATIVE_JPEG_RUNTIME_H_
