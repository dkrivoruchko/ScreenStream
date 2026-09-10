# Encoding

Encoding turns a completed Capture read into one immutable JPEG payload. It owns the reusable carrier, backend selection, codec resources, mutable assembly, and physical settlement. Session owns currentness and publication. See the public [JPEG backend seam](../../docs/architecture.md#jpeg-backend-seam) and [immutable payload contract](#immutable-segmented-storage).

## Contents

- [Responsibility boundary and owned state](#responsibility-boundary-and-owned-state)
- [Carrier loan and the Capture boundary](#carrier-loan-and-the-capture-boundary)
- [Backend selection and fallback](#backend-selection-and-fallback)
- [Framework and Native production](#framework-and-native-production)
- [Transactional segmented output](#transactional-segmented-output)
- [Immutable segmented storage](#immutable-segmented-storage)
- [Pre-submission construction failures](#pre-submission-construction-failures)
- [Reconciliation and operation results](#reconciliation-and-operation-results)
- [Failure containment and retirement](#failure-containment-and-retirement)
- [Implementation and verification](#implementation-and-verification)

## Responsibility boundary and owned state

One `EncodingOwner` exists per session. It exclusively owns:

- the installed checked RGBA layout and one compatible direct carrier;
- the carrier's exact loan/use state and active `EncodingInput` capability;
- Framework/Native backend state and session-lifetime native backend health;
- reusable Framework Bitmap and optional row scratch;
- one queue-less reconcile or production operation;
- one mutable transaction per encode and every tentative segment; and
- retirement obligations and late or nonreturning codec roots.

Encoding does not own Capture, production revision, pacing, cache, sequence/timestamp assignment, Stats, delivery, or terminal policy. Its results describe physical settlement; Session correlates them to the exact production and checks currentness.

## Carrier loan and the Capture boundary

The carrier is one direct writable range whose capacity is exactly the checked RGBA layout byte count, `4 * outputWidth * outputHeight`, within `Int.MAX_VALUE`. Lending and codec entry reset position to zero and limit to capacity; exact input validation requires a direct, writable, zero-position view with capacity and limit equal to that count. Its ownership is linear:

```text
idle -> loaned to Capture -> ready for Encoding -> entered codec -> idle
                       \-> discarded ------------------------> idle
```

`EncodingInput` is both the loan and the only capability allowed to settle it. It retains the exact carrier, direct view, byte count, owner, and production return port. Stale, duplicate, or substituted inputs cannot affect a later loan. Capture receives the writable view through the session's [read bridge](../01-session/production.md#read-bridge-and-production-progression), but cannot call carrier settlement itself.

Queue acceptance, cancellation, elapsed time, terminal state, or reference loss never returns a carrier to idle. After the loan request is installed, an exact real Capture return may select encode or discard; definite proof that Capture submission was rejected before entry may instead claim the matching bridge and discard that exact input. No timeout or synthetic inference may settle the loan. A returned backing buffer is adopted before shape validation, so a malformed returned range remains an owned cleanup obligation. A managed carrier retires by dropping engine roots; a native-malloc carrier retires only after its exact native free call returns normally. Native retirement marks `RetainedAfterFreeEntry` before the call, preventing another free attempt even if an uncontained throwable escapes. A Java reference does not keep a freed native allocation valid.

## Backend selection and fallback

Framework JPEG uses Android's managed [`Bitmap.compress`](https://developer.android.com/reference/android/graphics/Bitmap#compress(android.graphics.Bitmap.CompressFormat,%20int,java.io.OutputStream)) path over the RGBA carrier; Native JPEG uses the optional registered JNI path and the platform's weak Bitmap compressor. `FrameworkOnly` selects Framework exclusively and makes no Native lookup, load, capability, allocation, free, or compression call.

A newly selected Framework runtime uses a managed direct carrier. A native-malloc carrier is retained only during an in-place safe fallback of an already-owned Native runtime.

`Auto` uses only these inputs: the requested backend policy; sticky process-wide DSO availability; platform compressor capability; the current compatible output plan, RGBA layout, and runtime; and the per-session monotone Native health cell (`NativeHealthCell`). Device identity or allowlists, benchmarks, image scoring, diagnostics, memory prediction, and test results are excluded. This component owns the exhaustive selection policy; summaries elsewhere cannot add inputs.

`Auto` distinguishes library-load unavailability from later failure. The exact base `UnsatisfiedLinkError` or any caught `SecurityException` from the narrow `System.loadLibrary` boundary is sticky clean unavailability and selects Framework. An ordinary `Exception` from that boundary poisons availability and fails selection. Other `Error` types, including `UnsatisfiedLinkError` subclasses, propagate without publication; capability and JNI failures occur after the load boundary and are never reclassified as clean unavailability. A normally returned unsupported compressor selects Framework. An available and supported compressor selects Native with a native-malloc carrier, and native backend health is lifetime-monotone: once disabled, it never becomes enabled again for that session.

A coherent `SafeCompressorRejection` is the only runtime fallback signal. Encoding requires the exact transaction to be aborted and codec/carrier resources to be settled as reusable before disabling native backend health and returning `ReadinessChanged`. The rejected frame is not retried. Session reconciliation prepares the Framework owner, and only a later admitted frame uses it. Generic JNI, wire, ownership, transaction, or cleanup failures never enable fallback.

DSO availability is sticky after the first classified load result; backend choice and Native health remain per session. Capability is probed when that session first needs a health cell, not on every frame or compatible reuse. A disabled cell stays disabled across runtime replacement; an enabled cell contradicting sticky clean unavailability is internal failure. An uncontained load throwable publishes no result, so a later call retries.

Native-health disable is owner-wide evidence even if the rejected production became obsolete. [Session's stale-result policy](../01-session/session.md#stale-results-and-owner-health) and [Production accounting](../01-session/production.md#accounting-before-terminal-freeze) determine how that settled result affects readiness, cache, and counters before freeze.

## Framework and Native production

### Framework Bitmap adoption invariant

Framework accepts an adopted Bitmap for use only when both API bands prove exact dimensions, a mutable non-recycled software `ARGB_8888` Bitmap, and valid row/storage shape.

On API 24–25, the common invariant applies and the owner must not access API-26 color-management symbols; the [API-26 Bitmap API diff](https://developer.android.com/sdk/api_diff/26/changes/android.graphics.Bitmap) identifies `Bitmap.getColorSpace()` and the `ColorSpace` additions that begin at API 26.

On API 26 and later, use additionally requires a non-`HARDWARE` configuration and exact sRGB (`ColorSpace.get(ColorSpace.Named.SRGB)`). In both API bands, the row/storage checks require `rowBytes >= 4W`, nonnegative byte counts, checked `rowBytes * H <= Int.MAX_VALUE`, `byteCount == rowBytes * H`, and `allocationByteCount >= byteCount`.

The owner adopts the returned Bitmap before checking these properties. Validation or row-scratch allocation failure therefore returns an owner residue for retirement. Neither failure loses the acquired Bitmap or authorizes a second allocation over its unresolved owner.

Framework production reuses one mutable software `ARGB_8888` Bitmap. Tight RGBA rows use `copyPixelsFromBuffer`; padded Bitmap rows use one reusable width-sized `IntArray` and `setPixels`. Row conversion reads R, G, and B by byte and constructs opaque ARGB integers, independent of the carrier buffer's byte order. A frame performs one RGBA transfer and one `Bitmap.compress(JPEG, quality, stream)` call. A `false` compression result becomes `FrameFailed` only after the Bitmap use, carrier, and transaction are safely settled.

Native production passes tight, top-down, opaque RGBA with sRGB dataspace to the weak [NDK Bitmap compressor](https://developer.android.com/ndk/reference/group/bitmap) on API 30 and later. API 24–29 do not invoke that compressor and use Framework production. The JNI call synchronously streams native segments into `NativeSegmentSink`; each temporary direct view is copied once into transaction-owned managed storage and never escapes the call. Managed code classifies the result only after normal or explicitly contained invocation exit, coherent result evidence, and carrier and transaction settlement.

Both backends interpret the carrier as sRGB; neither descriptor proves upstream conversion. The [image contract](../02-capture/image-pipeline.md#color-and-readback) defines that shared interpretation and its fidelity limits.

The [Native ABI contract](native-abi.md) owns registration, wire format, statuses, exports, and lookup names; build files and native sources own exact packaging facts.

## Transactional segmented output

Each encode owns one `ManagedEncodedTransaction`. Producer close ends write access but does not publish bytes. Commit requires a successful codec outcome, a closed producer, positive checked byte count, exact segment normalization, and successful construction of `ImmutableEncodedPayload`. Commit transfers exclusive segment ownership to Storage and removes all mutable producer references. Other returned paths attempt abort and expose no tentative bytes; an unproved abort is an unsafe settlement failure.

Encoding may commit a mechanically complete payload whose production has become stale. It neither decides currentness nor aborts a valid transaction by inferring it; Session separately admits only current results for publication.

Framework writes grow positive `ByteArray` segments and normalize only a partially used final segment. Earlier full segments are never recopied or flattened. Native copying creates one managed segment per frozen native writer segment. Commit transfers segmented storage without flattening; only an explicit consumer copy API flattens it.

Transactions retain a sticky first fault and use checked cumulative `Int` length. Byte-count overflow records `ResourceExhausted`; a negative write length, malformed range, or ownership mismatch records internal failure. Named carrier, Bitmap, scratch, segment, tail-normalization, and payload-construction allocation denials may become `ResourceExhausted` only after safe settlement is proved. Partial bytes never accompany a failure result.

### Transaction state and transfer

`ManagedEncodedTransaction` progresses from `Open` to `ProducerClosed` and then `Committed`. Abort is allowed directly from `Open`, `ProducerClosed`, or `Faulted`; clean cutoff and codec rejection do not require a fault first. Producer close is idempotent while closed and preserves an existing fault. Commit while still open records an internal fault; empty closed output cannot commit. A committed transaction cannot abort, and a second abort returns false. Payload detach succeeds only for the exact committed payload identity and does not copy bytes.

The first recorded failure kind and cause survive later producer attempts. Only an `OutOfMemoryError` recorded by the exact named transaction allocation site may be contained when it propagates through a codec callback; a foreign OOM is not recognized from its class alone. Allocation of segments, segment-list capacity, the outer frozen array, a normalized tail, and the payload wrapper have explicit containment. Output is never exposed merely because some writes succeeded.

## Immutable segmented storage

`ImmutableEncodedPayload` adopts the supplied outer `Array<ByteArray>` and every inner array without copying. Constructor checks require a positive `byteCount`, a nonempty outer array, nonempty segments, a cumulative length within `Int.MAX_VALUE`, and exact equality of that sum to `byteCount`. These checks validate storage shape; they do not parse or validate JPEG syntax. The codec-success and transaction-commit seams establish complete encoded output.

Immutability depends on exclusive ownership transfer: the producer must relinquish every mutable alias to both array levels. Storage does not clone, flatten, pool, mutate, expose an iterator, or promise reclamation timing. Framework normalizes only its partially used final segment before transfer; Native segments are already exact managed copies. [Delivery](delivery.md#borrowed-frame-lifetime) is the only public borrowed access path and owns the copy checks and callback lifetime.

`PublishedFrame` retains a payload alongside immutable output metadata. [Production](../01-session/production.md#output-identity-and-cache-compatibility) owns its construction and fresh/cached-first identity. Retaining a published frame for cache or handoff does not grant mutable access to its bytes.

## Pre-submission construction failures

Framework and Native production each construct their producer transaction before a `ProductionOperation` can be published or submitted. A contained transaction-constructor `OutOfMemoryError` becomes `ResourceExhausted` only after the exact ready input is settled through its own return path. An ordinary `Exception` from adjacent backend-production construction instead settles the input as an internal failure. An `Error` or other non-`Exception` from that adjacent boundary is not contained or reclassified: the identical throwable propagates and the owner retains the failed, unproved loan. None of these paths creates a production task, exposes tentative bytes, or invokes a production callback.

Focused constructor-injection and near-miss evidence is mapped to `ENC-03` in the [verification contracts](../04-testing/verification-contracts.md).

## Reconciliation and operation results

Reconciliation installs a runtime compatible with one output layout and backend policy. An already compatible runtime is reused. A shape change retires the old Bitmap/carrier dependencies before installing replacements; a transition from Native to Framework after native backend health is disabled may retain the native carrier while adding the Framework Bitmap owner.

Accepted reconcile and production operations retain their exact return ports until private work occupancy is released. A definitive submission rejection is callback-free; accepted cutoff before entry returns `CutoffInert` after clean local settlement. Production may return:

- `Encoded`, with one immutable payload and encode duration;
- `FrameFailed`, for a safely settled Framework codec rejection;
- `ReadinessChanged`, for the safely committed native backend health disable transition;
- `Failed`, with the applicable stable problem and optional cause; or
- `CutoffInert`, when accepted work is cleanup-only at retirement.

`EncodingInputSettlement.Accepted` transfers settlement to asynchronous production; it does not promise that the callback arrives. `Settled` proves synchronous return of the exact input with no remaining asynchronous production. Failed input settlement preserves the applicable problem and cause. Reconcile returns `Ready`, `Failed`, or `CutoffInert` through its own exact port.

Successful duration evidence requires nonnegative start time, a finish no earlier than start, and checked subtraction. Framework measures its RGBA transfer and compression through the post-compression sample; Native samples after carrier entry and again after returned invocation, producer close, and carrier settlement. Transaction commit follows those samples. Timing failure cannot be converted into a successful encode.

No result exposes carrier identity, backend name, transaction state, or tentative bytes.

## Failure containment and retirement

Setup, codec use, and retirement never overlap within one owner. Ordinary failures attempt the smallest complete settlement: close or abort producer access, finish Bitmap/native use, return the exact carrier when proved, and release operation occupancy before invoking the result port. An ambiguous or nonreturning operation keeps only the roots whose settlement is unproved and prevents successor reuse.

Retirement closes new loans and work immediately, then retires an idle Framework owner before its carrier. Each physical release is attempted at most once. An entered codec or outstanding Capture loan remains strongly rooted; retirement is retried only when a genuine later settlement makes resources eligible. A real late return may settle its exact producer, transaction, carrier, and callback, but frozen session ingress makes that callback cleanup-only.

There is intentionally no second carrier, per-frame Bitmap, same-frame backend retry, persistent native JPEG payload, background native continuation, cleanup-completion API, or zeroization promise. These exclusions preserve bounded memory and make ownership ambiguity containable. The common accepted/nonreturning operation model is in [Coordination](../01-session/coordination.md), and stable failure/terminal interpretation is in [Session failure policy](../01-session/session.md#stable-problem-mapping).

## Implementation and verification

- [EncodingOwner](../../src/main/kotlin/io/screenstream/capture/internal/encoding/EncodingOwner.kt), [EncoderRuntime](../../src/main/kotlin/io/screenstream/capture/internal/encoding/EncoderRuntime.kt), and [RgbaCarrier](../../src/main/kotlin/io/screenstream/capture/internal/encoding/RgbaCarrier.kt) own reconcile, loan identity, health, and retirement. [EncodingContract](../../src/main/kotlin/io/screenstream/capture/internal/encoding/EncodingContract.kt) owns the returned types.
- [FrameworkBitmapOwner](../../src/main/kotlin/io/screenstream/capture/internal/encoding/FrameworkBitmapOwner.kt), [FrameworkJpegProduction](../../src/main/kotlin/io/screenstream/capture/internal/encoding/FrameworkJpegProduction.kt), and [NativeJpegProduction](../../src/main/kotlin/io/screenstream/capture/internal/encoding/NativeJpegProduction.kt) own acquisition, codec entry, and settlement. The [Native ABI](native-abi.md) extends the Native call boundary.
- [ManagedEncodedTransaction](../../src/main/kotlin/io/screenstream/capture/internal/encoding/ManagedEncodedTransaction.kt), its [Framework](../../src/main/kotlin/io/screenstream/capture/internal/encoding/FrameworkEncodedTransaction.kt) and [Native](../../src/main/kotlin/io/screenstream/capture/internal/encoding/NativeEncodedTransaction.kt) implementations, and [ImmutableEncodedPayload](../../src/main/kotlin/io/screenstream/capture/internal/storage/ImmutableEncodedPayload.kt) own commit and storage.
- [Verification contracts](../04-testing/verification-contracts.md) `ENC-01`–`ENC-09` and `STO-01` map the relevant evidence. Focused [transaction lifecycle tests](../../src/test/kotlin/io/screenstream/capture/internal/encoding/ManagedEncodedTransactionLifecycleTest.kt), [pre-submission failure tests](../../src/test/kotlin/io/screenstream/capture/internal/encoding/EncodingOwnerPreSubmissionFailureTest.kt), and [storage tests](../../src/test/kotlin/io/screenstream/capture/internal/storage/ImmutableEncodedPayloadStorageTest.kt) exercise those seams. Named allocation catches also require inspection; their presence is not evidence of a dynamically exercised allocation denial.
