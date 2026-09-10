# Native JPEG ABI

This page owns the exact Kotlin/JNI/C++ packet and the evidence needed to interpret one invocation. [Encoding](encoding.md#backend-selection-and-fallback) owns backend selection, carrier loans, and transaction settlement. The [image pipeline](../02-capture/image-pipeline.md#color-and-readback) defines the RGBA input; [immutable Storage](encoding.md#immutable-segmented-storage) holds only committed managed bytes.

## Contents

- [Facade and registered methods](#facade-and-registered-methods)
- [Input descriptor and validation](#input-descriptor-and-validation)
- [Result block and wire statuses](#result-block-and-wire-statuses)
- [Native writer and synchronous transfer](#native-writer-and-synchronous-transfer)
- [Managed invocation evidence](#managed-invocation-evidence)
- [Error boundaries](#error-boundaries)
- [Exports, shrinking, and packaging](#exports-shrinking-and-packaging)
- [Implementation and verification](#implementation-and-verification)

## Facade and registered methods

The sole managed facade is `io.screenstream.capture.internal.encoding.NativeJpegProcess`; its logical library name is `screen_capture_engine`, packaged as `libscreen_capture_engine.so`. That library and the direct weak `AndroidBitmap_compress` platform function are the sole Native route. `System.loadLibrary` is the authoritative load operation. Direct facade wrappers require already-published `Available` status and never initiate loading themselves.

The process-lifetime receiver retains no Session or payload state. There is no alternate DSO name, reflective loader, unload path, `dlopen`, `dlsym`, retained compressor function pointer, second facade, or compatibility packet. A call obtains the weak compressor address locally under the API-30 availability guard; API 24–29 return unsupported capability and do not invoke the compressor. Selection and sticky load classification belong to [Encoding](encoding.md#backend-selection-and-fallback).

JNI registers four private instance natives in this order. These are JVM method descriptors, including their return types:

```text
nativeAllocateCarrier
(J)Ljava/nio/ByteBuffer;

nativeFreeCarrier
(Ljava/nio/ByteBuffer;)V

nativeHasWeakCompressor
()Z

nativeCompress
(Ljava/nio/ByteBuffer;JIIIIJIIILio/screenstream/capture/internal/encoding/NativeSegmentSink;Ljava/nio/ByteBuffer;)V
```

The sink's private method is `copyNativeSegment(Ljava/nio/ByteBuffer;I)V`. JNI resolves it on the supplied sink object and calls it synchronously for each frozen native segment. Kotlin's helper delegates to the exact `NativeEncodedTransaction`; it does not retain the native view.

## Input descriptor and validation

`nativeCompress` receives these arguments in order:

1. Direct carrier `ByteBuffer` and signed 64-bit pixel byte count `B`.
2. Signed 32-bit width `W`, height `H`, stride, and Bitmap format.
3. Signed 64-bit Bitmap flags.
4. Signed 32-bit dataspace, compression format, and JPEG quality.
5. `NativeSegmentSink` and the direct result block.

For the current packet, `stride = 4W`, `B = stride * H`, and `0 < B <= Int.MAX_VALUE`. The Kotlin facade supplies Bitmap format `1` (`RGBA_8888`), flags `1L` (opaque alpha), dataspace `142671872` (sRGB), compression format `0` (JPEG), and quality in `0..100`. Pixels are tight, top-down RGBA. These constants describe nominal sRGB interpretation; they do not establish upstream color conversion.

Managed carrier validation requires a direct writable zero-position view with capacity and limit exactly `B`. JNI separately rejects null carrier/sink, nonpositive dimensions/count/stride, wrong constants or quality, inconsistent checked size arithmetic, a missing direct address, or direct capacity unequal to `B`. It resolves the sink method before compressor entry and narrows descriptor fields only after validation. Native does not inspect Java position, limit, or read-only flags: those are managed-side obligations.

`nativeAllocateCarrier(B)` checks a positive size representable by `size_t`, mallocs that range, then creates its direct view. Failure to create the Java view frees the acquired allocation before returning. Encoding's checked layout and returned-view adoption add the managed `Int` bound and exact shape requirements.

`nativeFreeCarrier(buffer)` validates a non-null positive direct range and a capacity representable by `size_t`, then frees its address. Those shape checks do not prove malloc provenance, base-address identity, or that the pointer has not already been freed. The exact `NativeMallocCarrier` owner supplies that proof and permits one free attempt only after its loan is settled. The allocation view intentionally outlives allocation entry; retaining a Java direct-buffer reference does not extend the lifetime of freed native memory. Encoded-segment views have the shorter call scope described below.

## Result block and wire statuses

Kotlin allocates a direct writable native-order buffer with capacity and limit exactly 16 bytes. Its absolute reads use two signed 64-bit words:

| Offset | Word |
| --- | --- |
| `0` | Produced byte count |
| `8` | Wire status |

Both begin at `-1` (`Pending`). Managed shape validation checks directness, writability, capacity, limit, and native byte order; it does not require position zero. Native's `ResultChannel` arms only when `JNIEnv` is healthy, the result object is non-null, its direct address exists, and capacity is exactly 16. Native does not validate its Java limit, order, or writability.

An unarmed channel returns without writing; the original Pending words can remain even though JNI returned. For an armed completion, native writes produced count first and status last using field-wise `memcpy`, including when a Java throwable became pending after the address was captured. This is neither a C++ struct cast nor a cross-thread fence. Status-last is a same-task marker and does not establish invocation success on its own.

| Value | Meaning |
| --- | --- |
| `0` | `NativeTransferComplete` |
| `1` | `SafeCompressorRejection` |
| `2` | `NativeOutOfMemory` |
| `3` | `InternalFailure` |
| `4` | `JavaThrowable` |

Any other status, including Pending, decodes as `Unknown`. The count records native compressor output, not the managed prefix successfully copied. Rejection can report produced bytes with zero managed bytes; an interrupted transfer can report the complete native count and a shorter managed prefix. Neither creates a partial payload result.

## Native writer and synchronous transfer

One call-scoped `NativeSegmentWriter` owns a private singly linked chain of nodes with 65,536-byte payload capacity. Each node records its used byte count; every non-tail node is full. The writer maintains a checked total bounded by `INT_MAX`, a node count, an `Open → Frozen → Closed` lifecycle, and a sticky first fault. Its mutex protects chain changes; the fault cell uses relaxed atomic compare/exchange. These internal mechanisms do not permit a background compressor continuation after the call. The mutex serializes append, freeze, and free operations, but a pointer returned by `firstSegment()` remains valid only while its caller excludes close and other draining work. The JNI loop provides that exclusive drain scope; arbitrary concurrent drain/close is unsupported.

For a nonempty append, the writer checks lifecycle, data, and cumulative length, computes space left in its tail, and allocates every required new node before changing accepted bytes or links. Only after preparation succeeds does it copy into the old tail and new nodes, link the prepared chain, and advance the total. An allocation failure frees the temporary chain and preserves the previously accepted prefix. Named node-allocation failure or length overflow records native OOM; descriptor or ownership contradictions record internal failure. A zero-length append returns true without adding bytes.

After the compressor returns, freeze validates exact chain shape and total byte count. Initial classification considers compressor return, frozen state, writer fault, and pending Java throwable. Normal `JNI_EXCEPTION` or `ALLOCATION_FAILED` compressor results can become safe rejection only with no writer fault or Java throwable. `BAD_PARAMETER`, unknown compressor results, empty success, or a thrown C++ compressor exception are internal failure. Writer OOM has its own status.

Only an initially complete transfer enters the sink loop. JNI exposes the exact current node as a temporary direct view, calls the sink synchronously, deletes that local reference, and frees that exact front node. The sink requires a positive length and a direct view with position zero and capacity, limit, and remaining count exactly equal to that length. It allocates one exact managed array, copies once, appends it to the transaction, and only then advances the managed byte count.

A pending throwable or view/copy failure stops transfer. Closing releases the remaining native tail; it does not reduce the produced count to the copied prefix. Final status also requires coherent writer close, and internal close/chain contradictions override a previously safe outcome. All encoded-segment views expire before their nodes are freed and must never escape their synchronous sink call. No native encoded payload persists after a clean call; only managed segments can later commit.

## Managed invocation evidence

`NativeJpegProduction` catches an ordinary invocation `Exception`, or the exact transaction-recorded allocation `OutOfMemoryError`, and records it independently of the wire. Other OOMs and other uncontained throwables propagate without an ordinary returned result. After a normal or explicitly contained exit, it closes producer access, settles the exact carrier, and records duration before classification and transaction commit/abort.

`NativeInvocationEvidenceCell` first requires all of the following:

- One recorded invocation exit and readable, correctly shaped result block.
- Proven exact carrier settlement.
- `0 <= produced <= Int.MAX_VALUE` and `0 <= copied <= produced`.
- A coherent producer: `ProducerClosed` with no fault, or `Faulted` with a recorded failure kind. Open, committed, or aborted state is not valid at this decision point.

With those prerequisites, the wire has these additional conditions:

- **Complete transfer:** no throwable or transaction fault, positive produced count, and `copied == produced`.
- **Safe rejection:** no throwable or transaction fault, and `copied == 0`. Produced count may be positive. Clean abort and owner settlement are still required before native health can be disabled.
- **Native OOM:** no throwable or transaction fault, and `copied == 0`; this is required resource exhaustion only after clean abort.
- **Java throwable:** an actual thrown value must exist, the transaction must have recorded `ResourceExhausted`, and the value must be an ordinary `Exception` or the identical `OutOfMemoryError` recorded at its allocation boundary. This also covers the transaction's checked capacity failure. A Java throwable status alone is insufficient.
- **Internal or unknown:** unsafe internal failure, regardless of otherwise plausible counts.

Missing, malformed, or contradictory evidence is unsafe internal failure. Producer-close, carrier-settlement, timing, or abort failure also prevents a safe result. A coherent complete transfer still needs successful transaction commit; a named commit allocation denial becomes resource exhaustion after abort. Only that final successful commit exposes an immutable payload. Wire values cannot turn a thrown invocation into a normal return, authorize fallback on generic JNI failure, or release an unproved carrier loan.

## Error boundaries

Every registered native entry is `noexcept` and contains C++ exceptions before they cross JNI. Only `nativeCompress` uses the wire. `nativeAllocateCarrier` returns a direct buffer or uses Java `IllegalArgumentException`, `OutOfMemoryError`, or `IllegalStateException` paths. `nativeFreeCarrier` returns `void` with Java argument/internal exception paths; `nativeHasWeakCompressor` returns a boolean or a Java internal exception. These entries do not invent wire statuses.

Compression's named writer-allocation failures can produce native OOM. A generic C++ `bad_alloc` or other exception outside that named writer boundary is internal failure, not a fallback signal. Allocation entry translates its contained `bad_alloc` to Java OOM; free/capability use internal Java exception paths for their contained native failures.

An already-pending Java throwable is never cleared, described, or replaced. Native performs only nonthrowing cleanup of provably owned call-local references and segments while that throwable remains pending. Raw result writes use the address captured while JNI was healthy; they preserve Java propagation. Managed code applies its own narrow containment and evidence rules afterward.

The safely settled rejection is the sole runtime fallback signal. [Encoding](encoding.md#backend-selection-and-fallback) aborts that frame, monotonically disables Native for the session, and allows Framework only after later reconciliation. Generic wire, Java, ownership, or cleanup failure cannot trigger the same-frame retry or fallback.

## Exports, shrinking, and packaging

`JNI_OnLoad` is the sole exported ELF symbol. It obtains `JNIEnv` using `JNI_VERSION_1_6`, finds the exact facade class, registers the four methods above, and returns `JNI_VERSION_1_6`. Lookup, pending exception, or registration failure returns `JNI_ERR`. Its body catches every C++ exception and returns `JNI_ERR`; unlike the registered entries, its declaration is intentionally not `noexcept`.

[CMake](../../src/main/cpp/CMakeLists.txt) requires `ANDROID_WEAK_API_DEFS=ON`, uses C++17 and hidden visibility, links `jnigraphics`, and applies `--no-undefined` plus the [version map](../../src/main/cpp/screen_capture_engine.map.txt). [Gradle](../../build.gradle.kts) supplies the weak-API setting and currently packages `armeabi-v7a`, `arm64-v8a`, `x86`, and `x86_64`. [Consumer rules](../../consumer-rules.pro) preserve facade and sink binary names, private native members, and the sink callback name while allowing member optimization.

A packet change must keep Kotlin declarations, constants, registration order and descriptors, sink lookup, C++ writer/descriptor types, visibility/version map, and consumer rules aligned. Exact build versions remain owned by the build files.

## Implementation and verification

- [NativeJpegProcess](../../src/main/kotlin/io/screenstream/capture/internal/encoding/NativeJpegProcess.kt), [NativeSegmentSink](../../src/main/kotlin/io/screenstream/capture/internal/encoding/NativeSegmentSink.kt), and [NativeInvocationEvidenceCell](../../src/main/kotlin/io/screenstream/capture/internal/encoding/NativeInvocationEvidenceCell.kt) own the managed packet and classification.
- [JNI entry points](../../src/main/cpp/screen_capture_engine_jni.cpp), [native declarations](../../src/main/cpp/native_jpeg_runtime.h), and [native runtime](../../src/main/cpp/native_jpeg_runtime.cpp) own native validation, chain operations, and exception containment.
- [Verification contracts](../04-testing/verification-contracts.md) `ENC-02`, `ENC-04`, and `ENC-09` identify the managed/native boundary checks; [`ABI-01`](../04-testing/verification-contracts.md#abi-01) specifies exports, shrinking, and packaging inspection. [Reflection tests](../../src/test/kotlin/io/screenstream/capture/internal/encoding/NativeJpegRegistrationReflectionTest.kt), [wire tests](../../src/test/kotlin/io/screenstream/capture/internal/encoding/NativeJpegProcessWireProtocolTest.kt), and [evidence tests](../../src/test/kotlin/io/screenstream/capture/internal/encoding/NativeInvocationEvidenceCellContractTest.kt) exercise the managed boundary. [Host JNI tests](../../src/test/cpp/screen_capture_engine_jni_test.cpp) and [writer tests](../../src/test/cpp/native_jpeg_runtime_test.cpp) exercise native failure, transfer, and cleanup paths; those sources do not by themselves prove a device's compressor capability or packaged ELF exports.
