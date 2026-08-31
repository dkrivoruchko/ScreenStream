#include "native_jpeg_runtime.h"

#include <climits>
#include <cstdlib>
#include <cstring>
#include <limits>
#include <new>

namespace screenstream::jpeg {

    static_assert(
            kNativeSegmentPayloadCapacity <=
            std::numeric_limits<std::size_t>::max() - sizeof(NativeSegment)
    );

    void NativeSegmentWriter::defaultFree(void *allocation) noexcept {
        std::free(allocation);
    }

    NativeSegmentWriter::NativeSegmentWriter(
            SegmentAllocateFunction allocateFunction,
            SegmentFreeFunction freeFunction
    ) noexcept:
            allocateFunction_(allocateFunction == nullptr ? &std::malloc : allocateFunction),
            freeFunction_(freeFunction == nullptr ? &NativeSegmentWriter::defaultFree : freeFunction) {
        if (allocateFunction == nullptr || freeFunction == nullptr) recordInternalFailure();
    }

    NativeSegmentWriter::~NativeSegmentWriter() noexcept {
        close();
    }

    bool NativeSegmentWriter::write(void *context, const void *data, std::size_t size) noexcept {
        if (context == nullptr) return false;
        return static_cast<NativeSegmentWriter *>(context)->append(data, size);
    }

    bool NativeSegmentWriter::append(const void *data, std::size_t size) noexcept {
        NativeSegment *preparedHead = nullptr;
        NativeSegment *preparedTail = nullptr;
        std::size_t preparedCount = 0;
        const auto releasePrepared = [this, &preparedHead, &preparedTail, &preparedCount]() noexcept {
            while (preparedHead != nullptr) {
                NativeSegment *next = preparedHead->next_;
                freeFunction_(preparedHead);
                preparedHead = next;
            }
            preparedTail = nullptr;
            preparedCount = 0;
        };

        try {
            std::lock_guard<std::mutex> lock(mutex_);
            if (size == 0) return true;
            if (fault() != WriterFault::None) return false;
            if (lifecycle_ != Lifecycle::Open) {
                recordInternalFailure();
                return false;
            }
            if (data == nullptr) {
                recordInternalFailure();
                return false;
            }
            if (size > static_cast<std::size_t>(INT_MAX) ||
                producedByteCount_ > static_cast<std::int64_t>(INT_MAX) - static_cast<std::int64_t>(size)) {
                recordOutOfMemory();
                return false;
            }

            std::size_t tailAvailable = 0;
            if (tail_ != nullptr) {
                if (tail_->segmentByteCount <= 0 ||
                    tail_->segmentByteCount > static_cast<std::int32_t>(kNativeSegmentPayloadCapacity)) {
                    recordInternalFailure();
                    return false;
                }
                tailAvailable = kNativeSegmentPayloadCapacity -
                                static_cast<std::size_t>(tail_->segmentByteCount);
            }

            const std::size_t bytesAfterTail = size > tailAvailable ? size - tailAvailable : 0;
            const std::size_t requiredSegmentCount = bytesAfterTail == 0 ? 0 :
                                                     1 + ((bytesAfterTail - 1) / kNativeSegmentPayloadCapacity);
            for (std::size_t index = 0; index < requiredSegmentCount; ++index) {
                void *allocation = allocateFunction_(sizeof(NativeSegment) + kNativeSegmentPayloadCapacity);
                if (allocation == nullptr) {
                    recordOutOfMemory();
                    releasePrepared();
                    return false;
                }
                auto *segment = ::new(allocation) NativeSegment{};
                if (preparedTail == nullptr) {
                    preparedHead = segment;
                } else {
                    preparedTail->next_ = segment;
                }
                preparedTail = segment;
                ++preparedCount;
            }

            const auto *source = static_cast<const std::uint8_t *>(data);
            std::size_t remaining = size;
            if (tailAvailable > 0) {
                const std::size_t copied = remaining < tailAvailable ? remaining : tailAvailable;
                std::memcpy(
                        tail_->payload() + static_cast<std::size_t>(tail_->segmentByteCount),
                        source,
                        copied
                );
                tail_->segmentByteCount += static_cast<std::int32_t>(copied);
                source += copied;
                remaining -= copied;
            }
            for (NativeSegment *segment = preparedHead; segment != nullptr; segment = segment->next_) {
                const std::size_t copied = remaining < kNativeSegmentPayloadCapacity ?
                                           remaining : kNativeSegmentPayloadCapacity;
                std::memcpy(segment->payload(), source, copied);
                segment->segmentByteCount = static_cast<std::int32_t>(copied);
                source += copied;
                remaining -= copied;
            }

            if (preparedHead != nullptr) {
                if (tail_ == nullptr) {
                    head_ = preparedHead;
                } else {
                    tail_->next_ = preparedHead;
                }
                tail_ = preparedTail;
                segmentCount_ += preparedCount;
                preparedHead = nullptr;
                preparedTail = nullptr;
                preparedCount = 0;
            }
            producedByteCount_ += static_cast<std::int64_t>(size);
            return true;
        } catch (const std::bad_alloc &) {
            recordOutOfMemory();
            releasePrepared();
            return false;
        } catch (...) {
            recordInternalFailure();
            releasePrepared();
            return false;
        }
    }

    void NativeSegmentWriter::recordOutOfMemory() noexcept {
        auto expected = static_cast<std::int32_t>(WriterFault::None);
        fault_.compare_exchange_strong(
                expected,
                static_cast<std::int32_t>(WriterFault::NativeOutOfMemory),
                std::memory_order_relaxed
        );
    }

    void NativeSegmentWriter::recordInternalFailure() noexcept {
        auto expected = static_cast<std::int32_t>(WriterFault::None);
        fault_.compare_exchange_strong(
                expected,
                static_cast<std::int32_t>(WriterFault::InternalFailure),
                std::memory_order_relaxed
        );
    }

    bool NativeSegmentWriter::validateChainLocked() const noexcept {
        if (segmentCount_ == 0) {
            return head_ == nullptr && tail_ == nullptr;
        }
        if (head_ == nullptr || tail_ == nullptr) return false;

        NativeSegment *segment = head_;
        for (std::size_t index = 0; index < segmentCount_; ++index) {
            if (segment == nullptr || segment->segmentByteCount <= 0 ||
                segment->segmentByteCount > static_cast<std::int32_t>(kNativeSegmentPayloadCapacity)) {
                return false;
            }
            if (index + 1 < segmentCount_ &&
                segment->segmentByteCount != static_cast<std::int32_t>(kNativeSegmentPayloadCapacity)) {
                return false;
            }
            if (index + 1 == segmentCount_) {
                return segment == tail_ && segment->next_ == nullptr;
            }
            segment = segment->next_;
        }
        return false;
    }

    bool NativeSegmentWriter::validateListLocked() const noexcept {
        if (producedByteCount_ == 0) return segmentCount_ == 0 && validateChainLocked();
        if (producedByteCount_ < 0 || producedByteCount_ > INT_MAX) return false;

        const auto produced = static_cast<std::size_t>(producedByteCount_);
        const std::size_t expectedSegmentCount =
                1 + ((produced - 1) / kNativeSegmentPayloadCapacity);
        if (segmentCount_ != expectedSegmentCount || !validateChainLocked()) return false;

        std::size_t remaining = produced;
        NativeSegment *segment = head_;
        NativeSegment *lastSegment = nullptr;
        for (std::size_t index = 0; index < expectedSegmentCount; ++index) {
            if (segment == nullptr) return false;
            const std::size_t expectedByteCount = remaining < kNativeSegmentPayloadCapacity ?
                                                  remaining : kNativeSegmentPayloadCapacity;
            if (segment->segmentByteCount != static_cast<std::int32_t>(expectedByteCount)) return false;
            remaining -= expectedByteCount;
            lastSegment = segment;
            segment = segment->next_;
        }
        return remaining == 0 && segment == nullptr && lastSegment == tail_;
    }

    bool NativeSegmentWriter::freezeAfterCompression() noexcept {
        try {
            std::lock_guard<std::mutex> lock(mutex_);
            if (lifecycle_ != Lifecycle::Open) {
                recordInternalFailure();
                return false;
            }
            lifecycle_ = Lifecycle::Frozen;
            if (!validateListLocked()) {
                recordInternalFailure();
                return false;
            }
            return true;
        } catch (...) {
            recordInternalFailure();
            return false;
        }
    }

    NativeSegment *NativeSegmentWriter::firstSegment() const noexcept {
        try {
            std::lock_guard<std::mutex> lock(mutex_);
            return lifecycle_ == Lifecycle::Frozen ? head_ : nullptr;
        } catch (...) {
            const_cast<NativeSegmentWriter *>(this)->recordInternalFailure();
            return nullptr;
        }
    }

    bool NativeSegmentWriter::freeFrontSegment(NativeSegment *expected) noexcept {
        try {
            std::lock_guard<std::mutex> lock(mutex_);
            if (lifecycle_ != Lifecycle::Frozen || expected == nullptr || head_ != expected ||
                segmentCount_ == 0 ||
                expected->segmentByteCount <= 0 ||
                expected->segmentByteCount > static_cast<std::int32_t>(kNativeSegmentPayloadCapacity) ||
                ((expected->next_ == nullptr) != (tail_ == expected))) {
                recordInternalFailure();
                return false;
            }
            head_ = expected->next_;
            --segmentCount_;
            if (head_ == nullptr) {
                tail_ = nullptr;
            }
            freeFunction_(expected);
            return true;
        } catch (...) {
            recordInternalFailure();
            return false;
        }
    }

    bool NativeSegmentWriter::freeChainLocked() noexcept {
        const bool coherent = validateChainLocked();
        if (!coherent) recordInternalFailure();

        NativeSegment *nativeSegment = head_;
        head_ = nullptr;
        tail_ = nullptr;
        segmentCount_ = 0;
        while (nativeSegment != nullptr) {
            NativeSegment *nextNativeSegment = nativeSegment->next_;
            freeFunction_(nativeSegment);
            nativeSegment = nextNativeSegment;
        }
        return coherent;
    }

    bool NativeSegmentWriter::close() noexcept {
        try {
            std::lock_guard<std::mutex> lock(mutex_);
            if (lifecycle_ == Lifecycle::Closed) {
                const bool empty = head_ == nullptr && tail_ == nullptr && segmentCount_ == 0;
                if (!empty) recordInternalFailure();
                return empty;
            }
            lifecycle_ = Lifecycle::Frozen;
            const bool cleanupCoherent = freeChainLocked();
            lifecycle_ = Lifecycle::Closed;
            const bool empty = head_ == nullptr && tail_ == nullptr && segmentCount_ == 0;
            return cleanupCoherent && empty;
        } catch (...) {
            recordInternalFailure();
            return false;
        }
    }

    WriterFault NativeSegmentWriter::fault() const noexcept {
        switch (static_cast<WriterFault>(fault_.load(std::memory_order_relaxed))) {
            case WriterFault::None:
                return WriterFault::None;
            case WriterFault::NativeOutOfMemory:
                return WriterFault::NativeOutOfMemory;
            case WriterFault::InternalFailure:
                return WriterFault::InternalFailure;
        }
        return WriterFault::InternalFailure;
    }

    std::int64_t NativeSegmentWriter::producedByteCount() const noexcept {
        try {
            std::lock_guard<std::mutex> lock(mutex_);
            return producedByteCount_;
        } catch (...) {
            const_cast<NativeSegmentWriter *>(this)->recordInternalFailure();
            return producedByteCount_;
        }
    }

    bool NativeSegmentWriter::closed() const noexcept {
        try {
            std::lock_guard<std::mutex> lock(mutex_);
            return lifecycle_ == Lifecycle::Closed &&
                   head_ == nullptr && tail_ == nullptr && segmentCount_ == 0;
        } catch (...) {
            const_cast<NativeSegmentWriter *>(this)->recordInternalFailure();
            return false;
        }
    }

    NativeWireStatus classifyInitialWireStatus(
            const CompressionResult &compressionResult,
            bool compressionLeftPendingJavaThrowable
    ) noexcept {
        if (compressionLeftPendingJavaThrowable && compressionResult.writerFrozen &&
            compressionResult.writerFault != WriterFault::InternalFailure) {
            return NativeWireStatus::JavaThrowable;
        }
        if (compressionResult.writerFrozen && compressionResult.writerFault != WriterFault::InternalFailure) {
            if (compressionResult.writerFault == WriterFault::NativeOutOfMemory) {
                return NativeWireStatus::NativeOutOfMemory;
            }
            if (compressionResult.androidBitmapResult == ANDROID_BITMAP_RESULT_JNI_EXCEPTION ||
                compressionResult.androidBitmapResult == ANDROID_BITMAP_RESULT_ALLOCATION_FAILED) {
                return NativeWireStatus::SafeCompressorRejection;
            }
            if (compressionResult.androidBitmapResult == ANDROID_BITMAP_RESULT_SUCCESS &&
                compressionResult.producedByteCount > 0) {
                return NativeWireStatus::NativeTransferComplete;
            }
        }
        return NativeWireStatus::InternalFailure;
    }

    CompressionResult compressFrame(
            const NativeFrameDescriptor &descriptor,
            CompressorFunction compressor,
            NativeSegmentWriter &writer
    ) noexcept {
        CompressionResult result{};
        if (compressor == nullptr || descriptor.pixels == nullptr) {
            writer.recordInternalFailure();
        } else {
            try {
                result.androidBitmapResult = compressor(
                        &descriptor.bitmapInfo,
                        descriptor.dataspace,
                        descriptor.pixels,
                        descriptor.compressFormat,
                        descriptor.quality,
                        &writer,
                        &NativeSegmentWriter::write
                );
            } catch (const std::bad_alloc &) {
                writer.recordInternalFailure();
            } catch (...) {
                writer.recordInternalFailure();
            }
        }

        result.writerFrozen = writer.freezeAfterCompression();
        result.writerFault = writer.fault();
        result.producedByteCount = writer.producedByteCount();
        return result;
    }

}
