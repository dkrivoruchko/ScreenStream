# Encoding

Encoding turns a completed Capture read into one immutable JPEG payload. It owns the reusable carrier, backend selection, codec resources, mutable assembly, and physical settlement. Session owns currentness and publication. See the public [JPEG backend seam](../../docs/architecture.md#jpeg-backend-seam) and [immutable payload contract](../contracts/frame-ownership-and-delivery.md#immutable-segmented-storage).

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

The carrier is one direct writable range whose capacity is exactly `4 * outputWidth * outputHeight`. Its ownership is linear:

```text
idle -> loaned to Capture -> ready for Encoding -> entered codec -> idle
                       \-> discarded ------------------------> idle
```

`EncodingInput` is both the loan and the only capability allowed to settle it. It retains the exact carrier, direct view, byte count, owner, and production return port. Stale, duplicate, or substituted inputs cannot affect a later loan. Capture receives the writable view through the session's [read bridge](capture.md#read-bridge-to-encoding), but cannot call carrier settlement itself.

Queue acceptance, cancellation, elapsed time, terminal state, or reference loss never returns a carrier to idle. After the loan request is installed, an exact real Capture return may select encode or discard; definite proof that Capture submission was rejected before entry may instead claim the matching bridge and discard that exact input. No timeout or synthetic inference may settle the loan. A managed carrier retires by dropping engine roots; a native-malloc carrier retires only after its exact native free call returns normally.

## Backend selection and fallback

Framework JPEG uses Android's managed [`Bitmap.compress`](https://developer.android.com/reference/android/graphics/Bitmap#compress(android.graphics.Bitmap.CompressFormat,%20int,java.io.OutputStream)) path over the RGBA carrier; Native JPEG uses the optional registered JNI path and the platform's weak Bitmap compressor. `FrameworkOnly` selects Framework exclusively and makes no Native lookup, load, capability, allocation, free, or compression call.

A newly selected Framework runtime uses a managed direct carrier. A native-malloc carrier is retained only during an in-place safe fallback of an already-owned Native runtime.

`Auto` uses only these inputs: the requested backend policy; sticky process-wide DSO availability; platform compressor capability; the current compatible output plan, RGBA layout, and runtime; and the per-session monotone Native health cell (`NativeHealthCell`). Device identity or allowlists, benchmarks, image scoring, diagnostics, memory prediction, and test results are excluded. This component owns the exhaustive selection policy; summaries elsewhere cannot add inputs.

`Auto` distinguishes library-load unavailability from later failure. An exact `UnsatisfiedLinkError` or `SecurityException` from the narrow `System.loadLibrary` boundary is sticky clean unavailability and selects Framework. An ordinary `Exception` from that boundary poisons availability and fails selection. Other `Error` types, including `UnsatisfiedLinkError` subclasses, propagate without publication; capability and JNI failures occur after the load boundary and are never reclassified as clean unavailability. A normally returned unsupported compressor selects Framework. An available and supported compressor selects Native with a native-malloc carrier, and native backend health is lifetime-monotone: once disabled, it never becomes enabled again for that session.

A coherent `SafeCompressorRejection` is the only runtime fallback signal. Encoding first aborts the exact transaction, settles codec/carrier resources as reusable, disables native backend health, and returns `ReadinessChanged`. The rejected frame is not retried. Session reconciliation prepares the Framework owner, and only a later admitted frame uses it. Generic JNI, wire, ownership, transaction, or cleanup failures never enable fallback.

DSO availability is sticky after the first classified load result; backend choice and Native health remain per session. An uncontained load throwable publishes no result, so a later call retries.

## Framework and Native production

### Framework Bitmap adoption invariant

Framework adopts a returned Bitmap only when both API bands prove exact dimensions, a mutable non-recycled software `ARGB_8888` Bitmap, and valid row/storage shape.

On API 24–25, the common invariant applies and the owner must not access API-26 color-management symbols; the [API-26 Bitmap API diff](https://developer.android.com/sdk/api_diff/26/changes/android.graphics.Bitmap) identifies `Bitmap.getColorSpace()` and the `ColorSpace` additions that begin at API 26.

On API 26 and later, adoption additionally requires a non-`HARDWARE` configuration and exact sRGB (`ColorSpace.get(ColorSpace.Named.SRGB)`). Mechanical row-byte, byte-count, and allocation-count checks remain source-owned evidence for the valid row/storage shape.

Framework production reuses one mutable software `ARGB_8888` Bitmap. Tight RGBA rows use `copyPixelsFromBuffer`; padded Bitmap rows use one reusable width-sized `IntArray` and `setPixels`. A frame performs one RGBA transfer and one `Bitmap.compress(JPEG, quality, stream)` call. A `false` compression result becomes `FrameFailed` only after the Bitmap use, carrier, and transaction are safely settled.

Native production passes tight, top-down, opaque RGBA with sRGB dataspace to the weak [NDK Bitmap compressor](https://developer.android.com/ndk/reference/group/bitmap) on API 30 and later. API 24–29 do not invoke that compressor and use Framework production. The JNI call synchronously streams native segments into `NativeSegmentSink`; each temporary direct view is copied once into transaction-owned managed storage and never escapes the call. Managed code classifies the result only after normal or explicitly contained invocation exit, coherent result evidence, and carrier and transaction settlement.

Both backends interpret the carrier as sRGB; neither descriptor proves upstream conversion. The [image contract](../contracts/image-pipeline.md#color-and-readback) defines that shared interpretation and its fidelity limits.

The [Native ABI contract](../contracts/native-abi.md) owns registration, wire format, statuses, exports, and lookup names; build files and native sources own exact packaging facts.

## Transactional segmented output

Each encode owns one `ManagedEncodedTransaction`. Producer close ends write access but does not publish bytes. Commit requires a successful codec outcome, a closed producer, positive checked byte count, exact segment normalization, and successful construction of `ImmutableEncodedPayload`. Commit transfers exclusive segment ownership to Storage and removes all mutable producer references. Every other returned path aborts and exposes no tentative bytes.

Encoding may commit a mechanically complete payload whose production has become stale. It neither decides currentness nor aborts a valid transaction by inferring it; Session separately admits only current results for publication.

Framework writes grow positive `ByteArray` segments and normalize only a partially used final segment. Earlier full segments are never recopied or flattened. Native adoption creates one managed segment per frozen native writer segment. Commit transfers segmented storage without flattening; only an explicit consumer copy API flattens it.

Transactions retain a sticky first fault and use checked cumulative `Int` length. A malformed write, contradictory range, or ownership mismatch is internal failure. Named carrier, Bitmap, scratch, segment, tail-normalization, and payload-construction allocation denials may become `ResourceExhausted` only after safe settlement is proved. Partial bytes never accompany a failure result.

## Pre-submission construction failures

Framework and Native production each construct their producer transaction before a `ProductionOperation` can be published or submitted. A contained transaction-constructor `OutOfMemoryError` becomes `ResourceExhausted` only after the exact ready input is settled through its own return path. An ordinary `Exception` from adjacent backend-production construction instead settles the input as an internal failure. An `Error` or other non-`Exception` from that adjacent boundary is not contained or reclassified: the identical throwable propagates and the owner retains the failed, unproved loan. None of these paths creates a production task, exposes tentative bytes, or invokes a production callback.

Focused constructor-injection and near-miss evidence is mapped to `ENC-03` in the [verification contracts](../testing.md#verification-contracts).

## Reconciliation and operation results

Reconciliation installs a runtime compatible with one output layout and backend policy. An already compatible runtime is reused. A shape change retires the old Bitmap/carrier dependencies before installing replacements; a transition from Native to Framework after native backend health is disabled may retain the native carrier while adding the Framework Bitmap owner.

Accepted reconcile and production operations retain their exact return ports until private work occupancy is released. A definitive submission rejection is callback-free; accepted cutoff before entry returns `CutoffInert` after clean local settlement. Production may return:

- `Encoded`, with one immutable payload and encode duration;
- `FrameFailed`, for a safely settled Framework codec rejection;
- `ReadinessChanged`, for the safely committed native backend health disable transition;
- `Failed`, with the applicable stable problem and optional cause; or
- `CutoffInert`, when accepted work is cleanup-only at retirement.

No result exposes carrier identity, backend name, transaction state, or tentative bytes.

## Failure containment and retirement

Setup, codec use, and retirement never overlap within one owner. Ordinary failures attempt the smallest complete settlement: close or abort producer access, finish Bitmap/native use, return the exact carrier when proved, and release operation occupancy before invoking the result port. An ambiguous or nonreturning operation keeps only the roots whose settlement is unproved and prevents successor reuse.

Retirement closes new loans and work immediately, then retires an idle Framework owner before its carrier. Each physical release is attempted at most once. An entered codec or outstanding Capture loan remains strongly rooted; retirement is retried only when a genuine later settlement makes resources eligible. A real late return may settle its exact producer, transaction, carrier, and callback, but frozen session ingress makes that callback cleanup-only.

There is intentionally no second carrier, per-frame Bitmap, same-frame backend retry, persistent native JPEG payload, background native continuation, cleanup-completion API, or zeroization promise. These exclusions preserve bounded memory and make ownership ambiguity containable. The common accepted/nonreturning operation model is in [Concurrency and liveness](../contracts/concurrency-and-liveness.md), and stable failure/terminal interpretation is in [Failures and terminal semantics](../contracts/failures-and-terminal-semantics.md).
