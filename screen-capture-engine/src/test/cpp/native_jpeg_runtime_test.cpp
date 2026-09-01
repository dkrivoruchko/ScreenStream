#include "native_jpeg_runtime.h"

#include <array>
#include <climits>
#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <exception>
#include <iostream>
#include <new>
#include <stdexcept>
#include <string>
#include <type_traits>
#include <utility>
#include <vector>

namespace {

    using screenstream::jpeg::CompressionResult;
    using screenstream::jpeg::NativeFrameDescriptor;
    using screenstream::jpeg::NativeSegment;
    using screenstream::jpeg::NativeSegmentWriter;
    using screenstream::jpeg::NativeWireStatus;
    using screenstream::jpeg::SegmentFreeFunction;
    using screenstream::jpeg::WriterFault;
    using screenstream::jpeg::kNativeSegmentPayloadCapacity;

    static_assert(static_cast<std::int64_t>(NativeWireStatus::NativeTransferComplete) == 0);
    static_assert(static_cast<std::int64_t>(NativeWireStatus::SafeCompressorRejection) == 1);
    static_assert(static_cast<std::int64_t>(NativeWireStatus::NativeOutOfMemory) == 2);
    static_assert(static_cast<std::int64_t>(NativeWireStatus::InternalFailure) == 3);
    static_assert(static_cast<std::int64_t>(NativeWireStatus::JavaThrowable) == 4);
    static_assert(std::is_same_v<SegmentFreeFunction, void (*)(void *) noexcept>);

    enum class AllocationException {
        None,
        BadAlloc,
        Ordinary,
    };

    struct AllocationLedger final {
        std::size_t allocationCalls = 0;
        std::size_t successfulAllocationCalls = 0;
        std::size_t freeCalls = 0;
        std::size_t failAllocationCall = 0;
        std::size_t throwAllocationCall = 0;
        AllocationException allocationException = AllocationException::None;
        bool freeContractViolation = false;
        std::vector<std::size_t> allocationRequestSizes;
        std::vector<void *> liveAllocations;
    };

    AllocationLedger *activeLedger = nullptr;
    bool freeWithoutActiveLedger = false;
    bool freeContractViolationObserved = false;

    void require(bool condition, const std::string &message) {
        if (!condition) throw std::runtime_error(message);
    }

    void requireValidObservedAllocationRequests(
            const AllocationLedger &ledger,
            const std::string &message
    ) {
        require(ledger.allocationCalls == ledger.allocationRequestSizes.size(), message);
        for (const std::size_t requestSize: ledger.allocationRequestSizes) {
            require(requestSize > sizeof(NativeSegment), message);
            require(requestSize - sizeof(NativeSegment) <= static_cast<std::size_t>(INT_MAX), message);
        }
    }

    void requireEverySuccessfulAllocationFreedExactlyOnce(
            const AllocationLedger &ledger,
            const std::string &message
    ) {
        require(ledger.liveAllocations.empty() &&
                ledger.freeCalls == ledger.successfulAllocationCalls &&
                !ledger.freeContractViolation, message);
    }

    void *trackedAllocate(std::size_t size) {
        require(activeLedger != nullptr, "allocator called without an active ledger");
        ++activeLedger->allocationCalls;
        activeLedger->allocationRequestSizes.push_back(size);
        if (activeLedger->allocationCalls == activeLedger->throwAllocationCall) {
            switch (activeLedger->allocationException) {
                case AllocationException::None:
                    break;
                case AllocationException::BadAlloc:
                    throw std::bad_alloc();
                case AllocationException::Ordinary:
                    throw std::runtime_error("injected allocator failure");
            }
        }
        if (activeLedger->allocationCalls == activeLedger->failAllocationCall) return nullptr;
        void *allocation = std::malloc(size);
        if (allocation != nullptr) {
            try {
                activeLedger->liveAllocations.push_back(allocation);
                ++activeLedger->successfulAllocationCalls;
            } catch (...) {
                std::free(allocation);
                throw;
            }
        }
        return allocation;
    }

    void trackedFree(void *allocation) noexcept {
        if (activeLedger == nullptr) {
            freeWithoutActiveLedger = true;
            return;
        }
        ++activeLedger->freeCalls;
        auto &live = activeLedger->liveAllocations;
        std::size_t index = 0;
        while (index < live.size() && live[index] != allocation) ++index;
        if (index == live.size()) {
            activeLedger->freeContractViolation = true;
            return;
        }
        live.erase(live.begin() + static_cast<std::ptrdiff_t>(index));
        std::free(allocation);
    }

    static_assert(noexcept(trackedFree(nullptr)));

    class ActiveLedger final {
    public:
        explicit ActiveLedger(AllocationLedger &ledger) : ledger_(ledger) {
            require(activeLedger == nullptr, "nested allocator ledger");
            activeLedger = &ledger;
        }

        ~ActiveLedger() {
            freeContractViolationObserved =
                    freeContractViolationObserved || ledger_.freeContractViolation;
            activeLedger = nullptr;
        }

        ActiveLedger(const ActiveLedger &) = delete;

        ActiveLedger &operator=(const ActiveLedger &) = delete;

    private:
        AllocationLedger &ledger_;
    };

    std::vector<std::uint8_t> bytes(std::size_t size, std::uint8_t seed = 0) {
        std::vector<std::uint8_t> result(size);
        for (std::size_t index = 0; index < size; ++index) {
            result[index] = static_cast<std::uint8_t>(seed + index * 31U);
        }
        return result;
    }

    std::vector<std::uint8_t> adoptFrozen(
            NativeSegmentWriter &writer,
            std::int64_t expectedProducedByteCount
    ) {
        require(expectedProducedByteCount >= 0 && expectedProducedByteCount <= INT_MAX,
                "expected frozen byte count was outside the wire range");
        std::size_t remaining = static_cast<std::size_t>(expectedProducedByteCount);
        std::vector<std::uint8_t> result;
        result.reserve(remaining);
        while (writer.firstSegment() != nullptr) {
            NativeSegment *segment = writer.firstSegment();
            require(segment->segmentByteCount > 0, "frozen segment was empty");
            const std::size_t segmentByteCount = static_cast<std::size_t>(segment->segmentByteCount);
            require(segmentByteCount <= remaining, "frozen segments exceeded the produced byte count");
            result.insert(
                    result.end(),
                    segment->payload(),
                    segment->payload() + segmentByteCount
            );
            remaining -= segmentByteCount;
            require(writer.freeFrontSegment(segment), "ordered frozen segment release failed");
        }
        require(remaining == 0, "frozen segments ended before the produced byte count");
        return result;
    }

    // Verification: ENC-04
    void testZeroNullIsInert() {
        AllocationLedger ledger;
        ActiveLedger active(ledger);
        NativeSegmentWriter writer(&trackedAllocate, &trackedFree);

        require(NativeSegmentWriter::write(&writer, nullptr, 0), "zero/null write failed");
        require(writer.producedByteCount() == 0, "zero write changed byte count");
        require(writer.fault() == WriterFault::None, "zero write faulted");
        require(ledger.allocationCalls == 0, "zero write allocated");
        require(writer.freezeAfterCompression(), "empty writer did not freeze");
        require(writer.close(), "empty writer did not close");
        require(ledger.freeCalls == 0, "empty writer freed an allocation");
    }

    // Verification: ENC-04
    void testOneCallbackPreservesBytesAcrossCapacityDerivedSizes() {
        const std::vector<std::size_t> sizes = {
                kNativeSegmentPayloadCapacity - 1,
                kNativeSegmentPayloadCapacity,
                kNativeSegmentPayloadCapacity + 1,
                3 * kNativeSegmentPayloadCapacity + 7,
        };
        for (const std::size_t size: sizes) {
            AllocationLedger ledger;
            ActiveLedger active(ledger);
            NativeSegmentWriter writer(&trackedAllocate, &trackedFree);
            const auto source = bytes(size, 5);
            require(NativeSegmentWriter::write(&writer, source.data(), source.size()), "boundary write failed");
            require(writer.producedByteCount() == static_cast<std::int64_t>(size), "boundary count mismatch");
            requireValidObservedAllocationRequests(
                    ledger,
                    "boundary allocation request was not a valid wire-segment shape"
            );
            require(writer.freezeAfterCompression(), "boundary writer did not freeze");
            require(adoptFrozen(writer, writer.producedByteCount()) == source,
                    "boundary bytes were not exact and ordered");
            require(writer.close(), "boundary writer did not close");
            requireEverySuccessfulAllocationFreedExactlyOnce(ledger,
                                                             "boundary allocations were not freed exactly once");
        }
    }

    // Verification: ENC-04
    void testMultipleCallbacksPreserveConcatenatedByteOrder() {
        AllocationLedger ledger;
        ActiveLedger active(ledger);
        NativeSegmentWriter writer(&trackedAllocate, &trackedFree);
        const auto first = bytes(100, 1);
        const auto second = bytes(kNativeSegmentPayloadCapacity - 100, 7);
        const auto third = bytes(23, 19);
        std::vector<std::uint8_t> expected = first;
        expected.insert(expected.end(), second.begin(), second.end());
        expected.insert(expected.end(), third.begin(), third.end());

        require(NativeSegmentWriter::write(&writer, first.data(), first.size()), "first callback failed");
        require(NativeSegmentWriter::write(&writer, second.data(), second.size()), "second callback failed");
        require(NativeSegmentWriter::write(&writer, third.data(), third.size()), "third callback failed");
        require(writer.producedByteCount() == static_cast<std::int64_t>(expected.size()),
                "multi-callback produced-byte count mismatch");
        requireValidObservedAllocationRequests(ledger,
                                               "multi-callback allocation request was not a valid wire-segment shape");
        require(writer.freezeAfterCompression(), "multi-callback writer did not freeze");
        require(adoptFrozen(writer, writer.producedByteCount()) == expected,
                "multi-callback bytes lost callback order");
        require(writer.close(), "multi-callback writer did not close");
        requireEverySuccessfulAllocationFreedExactlyOnce(ledger,
                                                         "multi-callback allocations were not freed exactly once");
    }

    // Verification: ENC-04
    void testOverflowBeforeMutation() {
        const std::uint8_t byte = 9;
        {
            AllocationLedger ledger;
            ActiveLedger active(ledger);
            NativeSegmentWriter writer(&trackedAllocate, &trackedFree);
            require(!NativeSegmentWriter::write(
                    &writer,
                    reinterpret_cast<const void *>(static_cast<std::uintptr_t>(1)),
                    static_cast<std::size_t>(INT_MAX) + 1U
            ), "single callback above Int.MAX_VALUE succeeded");
            require(writer.fault() == WriterFault::NativeOutOfMemory, "size overflow did not record OOM");
            require(writer.producedByteCount() == 0 && writer.firstSegment() == nullptr,
                    "size overflow mutated accepted progress");
            require(ledger.allocationCalls == 0,
                    "size overflow reached allocation after the invalid data address");
            require(writer.freezeAfterCompression(), "size-overflow writer graph was inconsistent");
        }
        {
            NativeSegmentWriter writer;
            require(NativeSegmentWriter::write(&writer, &byte, 1), "overflow setup write failed");
            require(!NativeSegmentWriter::write(&writer, &byte, static_cast<std::size_t>(INT_MAX)),
                    "cumulative Int.MAX_VALUE overflow succeeded");
            require(writer.fault() == WriterFault::NativeOutOfMemory, "cumulative overflow did not record OOM");
            require(writer.producedByteCount() == 1, "cumulative overflow changed accepted count");
            require(writer.freezeAfterCompression(), "cumulative-overflow writer graph was inconsistent");
            require(adoptFrozen(writer, writer.producedByteCount()) == std::vector<std::uint8_t>({byte}),
                    "cumulative overflow changed accepted bytes");
        }
    }

    // Verification: ENC-04
    void testPreparationFailureIsCallbackAtomic() {
        AllocationLedger ledger;
        ActiveLedger active(ledger);
        NativeSegmentWriter writer(&trackedAllocate, &trackedFree);
        const auto accepted = bytes(100, 3);
        const auto rejected = bytes(2 * kNativeSegmentPayloadCapacity, 11);
        require(NativeSegmentWriter::write(&writer, accepted.data(), accepted.size()), "setup write failed");
        const std::size_t setupAllocationCalls = ledger.allocationCalls;
        const std::vector<void *> acceptedAllocations = ledger.liveAllocations;
        const std::size_t freeCallsBeforeRejection = ledger.freeCalls;
        require(!acceptedAllocations.empty(), "setup retained no accepted allocation");
        ledger.failAllocationCall = setupAllocationCalls + 2;

        require(!NativeSegmentWriter::write(&writer, rejected.data(), rejected.size()),
                "partially prepared callback succeeded");
        require(writer.fault() == WriterFault::NativeOutOfMemory, "preparation failure did not record OOM");
        require(writer.producedByteCount() == static_cast<std::int64_t>(accepted.size()),
                "preparation failure changed accepted count");
        requireValidObservedAllocationRequests(
                ledger,
                "preparation failure allocation request was not a valid wire-segment shape"
        );
        require(ledger.freeCalls > freeCallsBeforeRejection &&
                ledger.liveAllocations == acceptedAllocations &&
                !ledger.freeContractViolation,
                "temporary preparation was not rolled back without changing accepted identities");
        require(writer.freezeAfterCompression(), "writer did not freeze after preparation failure");
        require(adoptFrozen(writer, writer.producedByteCount()) == accepted,
                "preparation failure changed accepted bytes");
        require(writer.close(), "writer did not close after preparation failure");
        requireEverySuccessfulAllocationFreedExactlyOnce(ledger,
                                                         "preparation-failure allocations were not freed exactly once");
    }

    // Verification: ENC-04
    void testAllocatorExceptionsRollbackTemporaryPreparationAtomically() {
        struct AllocatorExceptionCase final {
            const char *name;
            AllocationException exception;
            WriterFault expectedFault;
        };
        const std::vector<AllocatorExceptionCase> cases = {
                {"bad_alloc",          AllocationException::BadAlloc, WriterFault::NativeOutOfMemory},
                {"ordinary exception", AllocationException::Ordinary, WriterFault::InternalFailure},
        };

        for (const AllocatorExceptionCase &exceptionCase: cases) {
            AllocationLedger ledger;
            ledger.allocationException = exceptionCase.exception;
            ActiveLedger active(ledger);
            const auto accepted = bytes(100, 23);
            const auto rejected = bytes(2 * kNativeSegmentPayloadCapacity, 41);
            const std::uint8_t laterByte = 73;

            {
                NativeSegmentWriter writer(&trackedAllocate, &trackedFree);
                require(NativeSegmentWriter::write(&writer, accepted.data(), accepted.size()),
                        std::string(exceptionCase.name) + ": setup write failed");
                const std::size_t setupAllocationCalls = ledger.allocationCalls;
                const std::vector<void *> acceptedAllocations = ledger.liveAllocations;
                const std::size_t freeCallsBeforeRejection = ledger.freeCalls;
                require(!acceptedAllocations.empty(),
                        std::string(exceptionCase.name) + ": setup retained no accepted allocation");
                ledger.throwAllocationCall = setupAllocationCalls + 2;

                require(!NativeSegmentWriter::write(&writer, rejected.data(), rejected.size()),
                        std::string(exceptionCase.name) + ": throwing preparation callback succeeded");
                require(writer.fault() == exceptionCase.expectedFault,
                        std::string(exceptionCase.name) + ": allocator exception classification mismatch");
                require(writer.producedByteCount() == static_cast<std::int64_t>(accepted.size()),
                        std::string(exceptionCase.name) + ": throwing preparation changed accepted count");
                requireValidObservedAllocationRequests(
                        ledger,
                        std::string(exceptionCase.name) + ": invalid observed allocation request"
                );
                require(ledger.freeCalls > freeCallsBeforeRejection &&
                        ledger.liveAllocations == acceptedAllocations &&
                        !ledger.freeContractViolation,
                        std::string(exceptionCase.name) +
                        ": temporary rollback changed accepted allocation identities");

                const std::size_t allocationCallsAfterFault = ledger.allocationCalls;
                const std::vector<void *> liveAfterFault = ledger.liveAllocations;
                require(!NativeSegmentWriter::write(&writer, &laterByte, 1),
                        std::string(exceptionCase.name) + ": faulted writer accepted a later write");
                require(writer.fault() == exceptionCase.expectedFault,
                        std::string(exceptionCase.name) + ": later rejection overwrote the first fault");
                require(writer.producedByteCount() == static_cast<std::int64_t>(accepted.size()) &&
                        ledger.allocationCalls == allocationCallsAfterFault &&
                        ledger.liveAllocations == liveAfterFault,
                        std::string(exceptionCase.name) + ": later rejection mutated writer progress");

                require(writer.freezeAfterCompression(),
                        std::string(exceptionCase.name) + ": callback-atomic accepted list did not freeze");
                NativeSegment *const acceptedHead = writer.firstSegment();
                require(acceptedHead == acceptedAllocations.front(),
                        std::string(exceptionCase.name) + ": accepted head identity changed");
                require(adoptFrozen(writer, writer.producedByteCount()) == accepted,
                        std::string(exceptionCase.name) + ": accepted bytes changed");

                require(writer.close() && writer.closed(),
                        std::string(exceptionCase.name) + ": writer did not close after rollback");
                requireEverySuccessfulAllocationFreedExactlyOnce(ledger,
                                                                 std::string(exceptionCase.name) + ": final close did not clean each segment exactly once");
            }
            requireEverySuccessfulAllocationFreedExactlyOnce(ledger,
                                                             std::string(exceptionCase.name) + ": destructor repeated or missed cleanup");
        }
    }

    // Verification: ENC-04
    void testFirstFaultStickinessAndLaterWrites() {
        const std::uint8_t byte = 1;
        {
            AllocationLedger ledger;
            ledger.failAllocationCall = 1;
            ActiveLedger active(ledger);
            NativeSegmentWriter writer(&trackedAllocate, &trackedFree);
            require(!NativeSegmentWriter::write(&writer, &byte, 1), "injected OOM write succeeded");
            writer.recordInternalFailure();
            require(writer.fault() == WriterFault::NativeOutOfMemory, "internal fault overwrote first OOM");
            require(!NativeSegmentWriter::write(&writer, nullptr, 1), "faulted writer accepted positive write");
            require(ledger.allocationCalls == 1, "faulted writer invoked allocator again");
        }
        {
            AllocationLedger ledger;
            ledger.failAllocationCall = 1;
            ActiveLedger active(ledger);
            NativeSegmentWriter writer(&trackedAllocate, &trackedFree);
            require(!NativeSegmentWriter::write(&writer, nullptr, 1), "null positive write succeeded");
            require(!NativeSegmentWriter::write(&writer, &byte, static_cast<std::size_t>(INT_MAX) + 1U),
                    "faulted writer accepted overflow write");
            require(writer.fault() == WriterFault::InternalFailure, "later OOM condition overwrote first internal fault");
            require(ledger.allocationCalls == 0, "faulted writer inspected allocation path");
        }
        {
            NativeSegmentWriter writer;
            require(NativeSegmentWriter::write(&writer, &byte, 1), "freeze setup write failed");
            require(writer.freezeAfterCompression(), "freeze setup failed");
            require(!NativeSegmentWriter::write(&writer, &byte, 1), "frozen writer accepted positive write");
            require(writer.producedByteCount() == 1, "frozen write changed count");
        }
    }

    // Verification: ENC-04
    void testWrongFrontPreservesFirstFaultAndExactCleanup() {
        struct WrongFrontCase final {
            const char *name;
            bool startsWithOutOfMemory;
            WriterFault expectedFault;
        };
        const std::array<WrongFrontCase, 2> cases = {{
                                                             {"no prior fault", false, WriterFault::InternalFailure},
                                                             {"native OOM", true, WriterFault::NativeOutOfMemory},
                                                     }};

        for (const WrongFrontCase &wrongFrontCase: cases) {
            AllocationLedger ledger;
            ActiveLedger active(ledger);
            {
                NativeSegmentWriter writer(&trackedAllocate, &trackedFree);
                NativeSegmentWriter otherWriter(&trackedAllocate, &trackedFree);
                const auto accepted = bytes(kNativeSegmentPayloadCapacity + 1, 13);
                const auto rejected = bytes(kNativeSegmentPayloadCapacity, 17);
                const std::uint8_t otherByte = 29;
                const std::string context = wrongFrontCase.name;
                require(NativeSegmentWriter::write(&writer, accepted.data(), accepted.size()),
                        context + ": setup write failed");
                const std::size_t setupAllocationCalls = ledger.allocationCalls;
                const std::vector<void *> acceptedAllocations = ledger.liveAllocations;
                require(!acceptedAllocations.empty(), context + ": setup retained no allocation");

                if (wrongFrontCase.startsWithOutOfMemory) {
                    ledger.failAllocationCall = setupAllocationCalls + 1;
                    require(!NativeSegmentWriter::write(&writer, rejected.data(), rejected.size()),
                            context + ": allocation-failure write succeeded");
                    require(writer.fault() == WriterFault::NativeOutOfMemory,
                            context + ": allocator OOM was not recorded");
                    ledger.failAllocationCall = 0;
                }
                require(writer.firstSegment() == nullptr,
                        context + ": writer exposed its head before freeze");
                require(writer.producedByteCount() == static_cast<std::int64_t>(accepted.size()) &&
                        ledger.liveAllocations == acceptedAllocations &&
                        !ledger.freeContractViolation,
                        context + ": setup changed accepted progress or identities");
                requireValidObservedAllocationRequests(
                        ledger,
                        context + ": allocation request was not a valid wire-segment shape"
                );

                require(NativeSegmentWriter::write(&otherWriter, &otherByte, 1),
                        context + ": other-writer setup failed");
                require(otherWriter.firstSegment() == nullptr,
                        context + ": other writer exposed its head before freeze");
                require(writer.freezeAfterCompression(), context + ": writer did not freeze coherently");
                require(otherWriter.freezeAfterCompression(), context + ": other writer did not freeze");
                NativeSegment *const first = writer.firstSegment();
                NativeSegment *const otherFirst = otherWriter.firstSegment();
                require(first == acceptedAllocations.front() && otherFirst != nullptr,
                        context + ": frozen heads did not preserve their identities");

                const std::size_t freeCallsBeforeWrongRelease = ledger.freeCalls;
                const std::vector<void *> liveBeforeWrongRelease = ledger.liveAllocations;
                require(!writer.freeFrontSegment(otherFirst), context + ": wrong head was accepted");
                require(writer.firstSegment() == first &&
                        ledger.freeCalls == freeCallsBeforeWrongRelease &&
                        ledger.liveAllocations == liveBeforeWrongRelease,
                        context + ": wrong head mutated or freed accepted storage");
                require(writer.fault() == wrongFrontCase.expectedFault,
                        context + ": wrong-front rejection changed first-fault precedence");
                require(adoptFrozen(writer, writer.producedByteCount()) == accepted,
                        context + ": wrong head changed accepted FIFO bytes");
                require(adoptFrozen(otherWriter, otherWriter.producedByteCount()) ==
                        std::vector<std::uint8_t>({otherByte}),
                        context + ": wrong head changed the other writer");
                if (wrongFrontCase.startsWithOutOfMemory) {
                    require(writer.close() && writer.closed(), context + ": writer did not close cleanly");
                    require(otherWriter.close() && otherWriter.closed(),
                            context + ": other writer did not close cleanly");
                } else {
                    require(writer.close(), context + ": writer did not close cleanly");
                    require(otherWriter.close(), context + ": other writer did not close cleanly");
                }
                requireEverySuccessfulAllocationFreedExactlyOnce(
                        ledger,
                        context + ": cleanup did not free each node exactly once"
                );
            }
            requireEverySuccessfulAllocationFreedExactlyOnce(
                    ledger,
                    std::string(wrongFrontCase.name) + ": destructor repeated or missed cleanup"
            );
        }
    }

    // Verification: ENC-04
    void testCloseDestructorAndFrontFreeAreExactAndIdempotent() {
        AllocationLedger ledger;
        ActiveLedger active(ledger);
        {
            NativeSegmentWriter writer(&trackedAllocate, &trackedFree);
            const auto source = bytes(kNativeSegmentPayloadCapacity + 2, 8);
            require(NativeSegmentWriter::write(&writer, source.data(), source.size()), "front-free setup write failed");
            require(writer.freezeAfterCompression(), "front-free setup freeze failed");
            NativeSegment *first = writer.firstSegment();
            require(first != nullptr, "front-free setup exposed no current head");
            const std::size_t freeCallsBeforeFrontRelease = ledger.freeCalls;
            require(writer.freeFrontSegment(first), "front segment free failed");
            require(ledger.freeCalls == freeCallsBeforeFrontRelease + 1 &&
                    !ledger.freeContractViolation,
                    "front release did not free the exact current head once");
            require(writer.close() && writer.closed(), "writer did not close after front free");
            const std::size_t freeCallsAfterClose = ledger.freeCalls;
            require(writer.close(), "second close was not idempotent");
            require(ledger.freeCalls == freeCallsAfterClose,
                    "repeated close freed an allocation again");
            requireEverySuccessfulAllocationFreedExactlyOnce(ledger,
                                                             "close did not free remaining allocations exactly once");
        }
        requireEverySuccessfulAllocationFreedExactlyOnce(ledger,
                                                         "destructor repeated close frees");

        {
            NativeSegmentWriter writer(&trackedAllocate, &trackedFree);
            const std::uint8_t byte = 2;
            require(NativeSegmentWriter::write(&writer, &byte, 1), "destructor setup write failed");
        }
        requireEverySuccessfulAllocationFreedExactlyOnce(ledger,
                                                         "destructor did not free open-writer allocations exactly once");
    }

    int successfulCompressor(
            const AndroidBitmapInfo *,
            std::int32_t,
            const void *,
            std::int32_t,
            std::int32_t,
            void *context,
            screenstream::jpeg::CompressWriteFunction writeFunction
    ) {
        const std::uint8_t first[] = {1, 2};
        const std::uint8_t second[] = {3, 4, 5};
        require(writeFunction(context, first, sizeof(first)), "compressor first callback failed");
        require(writeFunction(context, second, sizeof(second)), "compressor second callback failed");
        return ANDROID_BITMAP_RESULT_SUCCESS;
    }

    int rejectingCompressor(
            const AndroidBitmapInfo *,
            std::int32_t,
            const void *,
            std::int32_t,
            std::int32_t,
            void *context,
            screenstream::jpeg::CompressWriteFunction writeFunction
    ) {
        const std::uint8_t byte = 6;
        require(!writeFunction(context, &byte, 1), "injected allocation failure callback succeeded");
        return ANDROID_BITMAP_RESULT_ALLOCATION_FAILED;
    }

    int partialReturningCompressor(
            std::int32_t androidBitmapResult,
            void *context,
            screenstream::jpeg::CompressWriteFunction writeFunction
    ) {
        const std::uint8_t partial[] = {31, 47, 59, 83};
        require(writeFunction(context, partial, sizeof(partial)),
                "partial rejecting compressor write failed");
        return androidBitmapResult;
    }

    int partialJniExceptionCompressor(
            const AndroidBitmapInfo *,
            std::int32_t,
            const void *,
            std::int32_t,
            std::int32_t,
            void *context,
            screenstream::jpeg::CompressWriteFunction writeFunction
    ) {
        return partialReturningCompressor(
                ANDROID_BITMAP_RESULT_JNI_EXCEPTION,
                context,
                writeFunction
        );
    }

    int partialAllocationFailedCompressor(
            const AndroidBitmapInfo *,
            std::int32_t,
            const void *,
            std::int32_t,
            std::int32_t,
            void *context,
            screenstream::jpeg::CompressWriteFunction writeFunction
    ) {
        return partialReturningCompressor(
                ANDROID_BITMAP_RESULT_ALLOCATION_FAILED,
                context,
                writeFunction
        );
    }

    int badAllocBeforeWriteCompressor(
            const AndroidBitmapInfo *,
            std::int32_t,
            const void *,
            std::int32_t,
            std::int32_t,
            void *,
            screenstream::jpeg::CompressWriteFunction
    ) {
        throw std::bad_alloc();
    }

    int ordinaryAfterPartialWriteCompressor(
            const AndroidBitmapInfo *,
            std::int32_t,
            const void *,
            std::int32_t,
            std::int32_t,
            void *context,
            screenstream::jpeg::CompressWriteFunction writeFunction
    ) {
        const std::uint8_t partial[] = {9, 8, 7};
        require(writeFunction(context, partial, sizeof(partial)), "ordinary-exception partial write failed");
        throw std::runtime_error("compressor failed after writing");
    }

    // Verification: ENC-04
    void testCompressFramePropagatesResultFreezeBytesAndFault() {
        const std::uint8_t pixel = 0;
        NativeFrameDescriptor descriptor{};
        descriptor.pixels = &pixel;
        {
            NativeSegmentWriter writer;
            const CompressionResult result =
                    screenstream::jpeg::compressFrame(descriptor, &successfulCompressor, writer);
            require(result.androidBitmapResult == ANDROID_BITMAP_RESULT_SUCCESS, "compress result code mismatch");
            require(result.writerFrozen, "compress result did not report frozen writer");
            require(result.writerFault == WriterFault::None, "compress result reported unexpected fault");
            require(result.producedByteCount == 5, "compress result byte count mismatch");
            require(adoptFrozen(writer, result.producedByteCount) == std::vector<std::uint8_t>({1, 2, 3, 4, 5}),
                    "compress result bytes mismatch");
        }
        {
            AllocationLedger ledger;
            ledger.failAllocationCall = 1;
            ActiveLedger active(ledger);
            NativeSegmentWriter writer(&trackedAllocate, &trackedFree);
            const CompressionResult result =
                    screenstream::jpeg::compressFrame(descriptor, &rejectingCompressor, writer);
            require(result.androidBitmapResult == ANDROID_BITMAP_RESULT_ALLOCATION_FAILED,
                    "rejecting compressor result code mismatch");
            require(result.writerFrozen, "faulted compress writer did not freeze consistently");
            require(result.writerFault == WriterFault::NativeOutOfMemory, "compress OOM fault mismatch");
            require(result.producedByteCount == 0, "failed callback contributed bytes");
            require(writer.firstSegment() == nullptr, "failed callback linked a segment");
        }
    }

    // Verification: ENC-04
    void testPartialCompressorRejectionsRetainSegmentsUntilAbortCloseAndCleanExactlyOnce() {
        struct PartialRejectionCase final {
            const char *name;
            screenstream::jpeg::CompressorFunction compressor;
            std::int32_t expectedAndroidBitmapResult;
        };
        const std::vector<PartialRejectionCase> cases = {
                {
                        "JNI exception",
                        &partialJniExceptionCompressor,
                        ANDROID_BITMAP_RESULT_JNI_EXCEPTION,
                },
                {
                        "allocation failed",
                        &partialAllocationFailedCompressor,
                        ANDROID_BITMAP_RESULT_ALLOCATION_FAILED,
                },
        };
        const std::uint8_t pixel = 0;
        NativeFrameDescriptor descriptor{};
        descriptor.pixels = &pixel;

        for (const PartialRejectionCase &rejectionCase: cases) {
            AllocationLedger ledger;
            ActiveLedger active(ledger);
            {
                NativeSegmentWriter writer(&trackedAllocate, &trackedFree);
                const std::size_t freeCallsBeforeCompression = ledger.freeCalls;
                const CompressionResult result = screenstream::jpeg::compressFrame(
                        descriptor,
                        rejectionCase.compressor,
                        writer
                );

                require(result.androidBitmapResult == rejectionCase.expectedAndroidBitmapResult,
                        std::string(rejectionCase.name) + ": compressor result mismatch");
                require(result.writerFrozen,
                        std::string(rejectionCase.name) + ": clean partial writer was not frozen");
                require(result.writerFault == WriterFault::None && writer.fault() == WriterFault::None,
                        std::string(rejectionCase.name) + ": clean partial writer recorded a fault");
                require(result.producedByteCount == 4 && writer.producedByteCount() == 4,
                        std::string(rejectionCase.name) + ": partial produced-byte count mismatch");
                requireValidObservedAllocationRequests(
                        ledger,
                        std::string(rejectionCase.name) + ": invalid partial allocation request"
                );
                const std::vector<void *> retainedAllocations = ledger.liveAllocations;
                require(ledger.freeCalls == freeCallsBeforeCompression &&
                        !retainedAllocations.empty(),
                        std::string(rejectionCase.name) +
                        ": writer did not retain partial bytes before abort close");
                NativeSegment *const retainedHead = writer.firstSegment();
                require(retainedHead != nullptr,
                        std::string(rejectionCase.name) + ": partial bytes were not retained");

                require(writer.close() && writer.closed(),
                        std::string(rejectionCase.name) + ": partial writer abort close failed");
                requireEverySuccessfulAllocationFreedExactlyOnce(ledger,
                                                                 std::string(rejectionCase.name) +
                                                                 ": abort close did not free the retained partial segment exactly once");
                require(screenstream::jpeg::classifyInitialWireStatus(result, false) ==
                        NativeWireStatus::SafeCompressorRejection,
                        std::string(rejectionCase.name) +
                        ": positive partial bytes outranked safe compressor rejection after clean close");
            }
            requireEverySuccessfulAllocationFreedExactlyOnce(ledger,
                                                             std::string(rejectionCase.name) + ": destructor repeated or missed abort cleanup");
        }
    }

    // Verification: ENC-04
    void testInitialWireStatusPrecedence() {
        struct WireCase final {
            const char *name;
            WriterFault writerFault;
            std::int32_t androidBitmapResult;
            std::int64_t producedByteCount;
            bool writerFrozen;
            bool pendingJavaThrowable;
            NativeWireStatus expected;
        };
        const std::vector<WireCase> cases = {
                {
                        "clean positive success",
                        WriterFault::None,
                        ANDROID_BITMAP_RESULT_SUCCESS,
                        1,
                        true,
                        false,
                        NativeWireStatus::NativeTransferComplete,
                },
                {
                        "zero-byte success",
                        WriterFault::None,
                        ANDROID_BITMAP_RESULT_SUCCESS,
                        0,
                        true,
                        false,
                        NativeWireStatus::InternalFailure,
                },
                {
                        "writer OOM outranks JNI rejection",
                        WriterFault::NativeOutOfMemory,
                        ANDROID_BITMAP_RESULT_JNI_EXCEPTION,
                        0,
                        true,
                        false,
                        NativeWireStatus::NativeOutOfMemory,
                },
                {
                        "clean JNI rejection with partial native bytes",
                        WriterFault::None,
                        ANDROID_BITMAP_RESULT_JNI_EXCEPTION,
                        17,
                        true,
                        false,
                        NativeWireStatus::SafeCompressorRejection,
                },
                {
                        "clean allocation rejection",
                        WriterFault::None,
                        ANDROID_BITMAP_RESULT_ALLOCATION_FAILED,
                        0,
                        true,
                        false,
                        NativeWireStatus::SafeCompressorRejection,
                },
                {
                        "pending throwable outranks clean rejection",
                        WriterFault::None,
                        ANDROID_BITMAP_RESULT_JNI_EXCEPTION,
                        0,
                        true,
                        true,
                        NativeWireStatus::JavaThrowable,
                },
                {
                        "pending throwable outranks writer OOM",
                        WriterFault::NativeOutOfMemory,
                        ANDROID_BITMAP_RESULT_JNI_EXCEPTION,
                        0,
                        true,
                        true,
                        NativeWireStatus::JavaThrowable,
                },
                {
                        "internal writer fault outranks pending throwable",
                        WriterFault::InternalFailure,
                        ANDROID_BITMAP_RESULT_JNI_EXCEPTION,
                        0,
                        true,
                        true,
                        NativeWireStatus::InternalFailure,
                },
                {
                        "unfrozen writer",
                        WriterFault::None,
                        ANDROID_BITMAP_RESULT_JNI_EXCEPTION,
                        0,
                        false,
                        false,
                        NativeWireStatus::InternalFailure,
                },
                {
                        "bad parameter result",
                        WriterFault::None,
                        ANDROID_BITMAP_RESULT_BAD_PARAMETER,
                        0,
                        true,
                        false,
                        NativeWireStatus::InternalFailure,
                },
        };

        for (const WireCase &wireCase: cases) {
            CompressionResult result{};
            result.writerFault = wireCase.writerFault;
            result.androidBitmapResult = wireCase.androidBitmapResult;
            result.producedByteCount = wireCase.producedByteCount;
            result.writerFrozen = wireCase.writerFrozen;
            require(
                    screenstream::jpeg::classifyInitialWireStatus(result, wireCase.pendingJavaThrowable) ==
                    wireCase.expected,
                    std::string(wireCase.name) + ": wire status mismatch"
            );
        }
    }

    // Verification: ENC-04
    void testCompressorExceptionsAreContainedFrozenAndCleanedExactlyOnce() {
        struct ExceptionCase final {
            const char *name;
            screenstream::jpeg::CompressorFunction compressor;
            bool writesPartialBytes;
        };
        const std::vector<ExceptionCase> exceptionCases = {
                {"bad_alloc before write",                 &badAllocBeforeWriteCompressor,       false},
                {"ordinary exception after partial write", &ordinaryAfterPartialWriteCompressor, true},
        };
        const std::uint8_t pixel = 0;
        const std::uint8_t laterByte = 1;
        NativeFrameDescriptor descriptor{};
        descriptor.pixels = &pixel;

        for (const ExceptionCase &exceptionCase: exceptionCases) {
            AllocationLedger ledger;
            ActiveLedger active(ledger);
            const std::int64_t expectedByteCount = exceptionCase.writesPartialBytes ? 3 : 0;
            {
                NativeSegmentWriter writer(&trackedAllocate, &trackedFree);
                const CompressionResult result = screenstream::jpeg::compressFrame(
                        descriptor,
                        exceptionCase.compressor,
                        writer
                );

                require(result.androidBitmapResult == ANDROID_BITMAP_RESULT_BAD_PARAMETER,
                        std::string(exceptionCase.name) + ": exception escaped into a result code");
                require(result.writerFrozen, std::string(exceptionCase.name) + ": writer was not frozen");
                require(result.writerFault == WriterFault::InternalFailure,
                        std::string(exceptionCase.name) + ": exception was not an internal failure");
                require(result.producedByteCount == expectedByteCount,
                        std::string(exceptionCase.name) + ": accepted byte count changed");
                require(writer.fault() == WriterFault::InternalFailure,
                        std::string(exceptionCase.name) + ": internal failure was not sticky");
                require(writer.producedByteCount() == expectedByteCount,
                        std::string(exceptionCase.name) + ": writer count disagreed with result");
                if (exceptionCase.writesPartialBytes) {
                    requireValidObservedAllocationRequests(
                            ledger,
                            std::string(exceptionCase.name) + ": invalid partial allocation request"
                    );
                    require(adoptFrozen(writer, result.producedByteCount) == std::vector<std::uint8_t>({9, 8, 7}),
                            std::string(exceptionCase.name) + ": accepted partial bytes were not frozen exactly");
                } else {
                    require(writer.firstSegment() == nullptr,
                            std::string(exceptionCase.name) + ": pre-write exception published a segment");
                    require(ledger.allocationCalls == 0,
                            std::string(exceptionCase.name) + ": pre-write exception allocated");
                }

                require(!NativeSegmentWriter::write(&writer, &laterByte, 1),
                        std::string(exceptionCase.name) + ": frozen writer accepted a later write");
                require(writer.fault() == WriterFault::InternalFailure,
                        std::string(exceptionCase.name) + ": later rejection overwrote internal failure");
                require(writer.producedByteCount() == expectedByteCount,
                        std::string(exceptionCase.name) + ": later rejection changed accepted count");
                require(writer.close() && writer.closed(),
                        std::string(exceptionCase.name) + ": writer did not close");
                requireEverySuccessfulAllocationFreedExactlyOnce(ledger,
                                                                 std::string(exceptionCase.name) + ": close did not free allocations exactly once");
            }
            requireEverySuccessfulAllocationFreedExactlyOnce(ledger,
                                                             std::string(exceptionCase.name) + ": destructor repeated or missed cleanup");
        }
    }

    using TestFunction = void (*)();

    const std::vector<std::pair<const char *, TestFunction>> tests = {
            {"zero/null",                           &testZeroNullIsInert},
            {"one-callback byte preservation",      &testOneCallbackPreservesBytesAcrossCapacityDerivedSizes},
            {"multi-callback byte order",           &testMultipleCallbacksPreserveConcatenatedByteOrder},
            {"overflow before mutation",            &testOverflowBeforeMutation},
            {"callback-atomic preparation failure", &testPreparationFailureIsCallbackAtomic},
            {"allocator exception rollback",        &testAllocatorExceptionsRollbackTemporaryPreparationAtomically},
            {"first-fault stickiness",              &testFirstFaultStickinessAndLaterWrites},
            {"wrong-front rejection",               &testWrongFrontPreservesFirstFaultAndExactCleanup},
            {"close/destructor",                    &testCloseDestructorAndFrontFreeAreExactAndIdempotent},
            {"compressFrame byte propagation",      &testCompressFramePropagatesResultFreezeBytesAndFault},
            {"partial rejection retained cleanup",  &testPartialCompressorRejectionsRetainSegmentsUntilAbortCloseAndCleanExactlyOnce},
            {"initial wire-status precedence",      &testInitialWireStatusPrecedence},
            {"compressor exception containment",    &testCompressorExceptionsAreContainedFrozenAndCleanedExactlyOnce},
    };

}

int main() {
    std::size_t passed = 0;
    for (const auto &[name, test]: tests) {
        try {
            test();
            require(!freeWithoutActiveLedger && !freeContractViolationObserved,
                    "noexcept free seam observed an ownership violation");
            ++passed;
        } catch (const std::exception &failure) {
            std::cerr << "FAIL " << name << ": " << failure.what() << '\n';
            return 1;
        } catch (...) {
            std::cerr << "FAIL " << name << ": unknown failure\n";
            return 1;
        }
    }
    std::cout << "PASS " << passed << " native JPEG runtime cases\n";
    return 0;
}
