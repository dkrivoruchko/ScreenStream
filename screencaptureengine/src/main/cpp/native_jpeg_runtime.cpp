#include "native_jpeg_runtime.h"

#include <climits>
#include <cstdlib>
#include <cstring>
#include <new>

namespace screenstream::jpeg {

    WriterCapsule::~WriterCapsule() noexcept {
        close();
    }

    bool WriterCapsule::write(void *context, const void *data, std::size_t size) noexcept {
        if (context == nullptr) return false;
        return static_cast<WriterCapsule *>(context)->append(data, size);
    }

    bool WriterCapsule::append(const void *data, std::size_t size) noexcept {
        try {
            std::lock_guard<std::mutex> lock(mutex_);
            if (!accepting_ || frozen_ || closed_) {
                recordInternalFailure();
                return false;
            }

            const WriterFault currentFault = fault();
            if (currentFault != WriterFault::None) {
                if (size > 0 && data == nullptr) recordInternalFailure();
                return false;
            }
            if (size == 0) return true;
            if (data == nullptr) {
                recordInternalFailure();
                return false;
            }
            if (size > static_cast<std::size_t>(INT_MAX) ||
                producedByteCount_ > static_cast<std::int64_t>(INT_MAX) - static_cast<std::int64_t>(size)) {
                recordOutOfMemory();
                return false;
            }

            auto *segment = static_cast<NativeSegment *>(std::malloc(sizeof(NativeSegment)));
            if (segment == nullptr) {
                recordOutOfMemory();
                return false;
            }
            segment->bytes = static_cast<std::uint8_t *>(std::malloc(size));
            if (segment->bytes == nullptr) {
                std::free(segment);
                recordOutOfMemory();
                return false;
            }
            segment->next = nullptr;
            segment->byteCount = static_cast<std::int32_t>(size);
            std::memcpy(segment->bytes, data, size);

            if (tail_ == nullptr) {
                head_ = segment;
            } else {
                tail_->next = segment;
            }
            tail_ = segment;
            producedByteCount_ += static_cast<std::int64_t>(size);
            return true;
        } catch (const std::bad_alloc &) {
            recordOutOfMemory();
            return false;
        } catch (...) {
            recordInternalFailure();
            return false;
        }
    }

    void WriterCapsule::recordOutOfMemory() noexcept {
        std::int32_t expected = static_cast<std::int32_t>(WriterFault::None);
        fault_.compare_exchange_strong(
                expected,
                static_cast<std::int32_t>(WriterFault::NativeOutOfMemory),
                std::memory_order_relaxed
        );
    }

    void WriterCapsule::recordInternalFailure() noexcept {
        fault_.store(static_cast<std::int32_t>(WriterFault::InternalFailure), std::memory_order_relaxed);
    }

    bool WriterCapsule::freezeAfterCompression() noexcept {
        try {
            std::lock_guard<std::mutex> lock(mutex_);
            if (!accepting_ || frozen_ || closed_) {
                recordInternalFailure();
                return false;
            }
            accepting_ = false;
            frozen_ = true;
            return true;
        } catch (...) {
            recordInternalFailure();
            return false;
        }
    }

    NativeSegment *WriterCapsule::firstSegment() const noexcept {
        return frozen_ && !closed_ ? head_ : nullptr;
    }

    bool WriterCapsule::releaseFront(NativeSegment *expected) noexcept {
        if (!frozen_ || closed_ || expected == nullptr || head_ != expected) {
            recordInternalFailure();
            return false;
        }
        head_ = expected->next;
        if (head_ == nullptr) tail_ = nullptr;
        std::free(expected->bytes);
        expected->bytes = nullptr;
        std::free(expected);
        return true;
    }

    void WriterCapsule::releaseAll() noexcept {
        NativeSegment *segment = head_;
        head_ = nullptr;
        tail_ = nullptr;
        while (segment != nullptr) {
            NativeSegment *next = segment->next;
            std::free(segment->bytes);
            segment->bytes = nullptr;
            std::free(segment);
            segment = next;
        }
    }

    bool WriterCapsule::close() noexcept {
        if (closed_) return head_ == nullptr && tail_ == nullptr;
        accepting_ = false;
        frozen_ = true;
        releaseAll();
        closed_ = true;
        return head_ == nullptr && tail_ == nullptr;
    }

    WriterFault WriterCapsule::fault() const noexcept {
        return static_cast<WriterFault>(fault_.load(std::memory_order_relaxed));
    }

    std::int64_t WriterCapsule::producedByteCount() const noexcept {
        return producedByteCount_;
    }

    bool WriterCapsule::closed() const noexcept {
        return closed_ && head_ == nullptr && tail_ == nullptr;
    }

    CompressionResult compressFrame(
            const NativeFrameDescriptor &descriptor,
            CompressorFunction compressor,
            WriterCapsule &capsule
    ) noexcept {
        CompressionResult result{};
        if (compressor == nullptr || descriptor.pixels == nullptr) {
            capsule.recordInternalFailure();
        } else {
            try {
                result.compressorResult = compressor(
                        &descriptor.bitmapInfo,
                        descriptor.dataspace,
                        descriptor.pixels,
                        descriptor.compressFormat,
                        descriptor.quality,
                        &capsule,
                        &WriterCapsule::write
                );
            } catch (const std::bad_alloc &) {
                capsule.recordInternalFailure();
            } catch (...) {
                capsule.recordInternalFailure();
            }
        }

        result.frozen = capsule.freezeAfterCompression();
        result.writerFault = capsule.fault();
        result.producedByteCount = capsule.producedByteCount();
        return result;
    }

}  // namespace screenstream::jpeg
