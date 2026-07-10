# Screen Capture Engine — Gate-B Inputs

This file is the checklist for implementation planning. It does not define product behavior. Product and public contracts live in
[01_design.md](01_design.md); internal algorithms and ownership rules live in [02_architecture.md](02_architecture.md); required checks live in
[03_verification.md](03_verification.md).

Every item below is a required Gate-B output or an explicit placeholder awaiting that output. This file does not assert that any binding is complete. These
inputs have no authority to narrow release support, change the product contract, add a runtime-selection axis, or block/reopen Gate A. An unsatisfied input
blocks Gate B and implementation authorization. Any support-impacting obstacle is reported to the user/Gate-A authority for an explicit new product decision
outside this file.

## 1. Required implementation manifest

The implementation manifest contains one row for each architecture component/owner boundary. A row may list
multiple planned production and test paths owned by that boundary:

| Field | Required content |
| --- | --- |
| path | Exact repository-relative production and test path or paths. |
| role | Controller, Android lane, target, GL, PBO, JPEG, delivery, metrics, diagnostics, memory, deadline, cleanup, JNI, or test support. |
| owner | The single owner defined in [02_architecture.md](02_architecture.md#61-components-and-owner-model). |
| declarations | Key private types, cells, occurrence records, carriers, receipts, and entry points. |
| dependencies | Allowed inbound and outbound dependencies, including the lane used for every opaque call. |
| contract links | Design and architecture sections implemented by the file. |
| test slices | Unit, race, fault-injection, instrumentation, native, or image-vector checks covering the file. |

The manifest covers the material Kotlin, C/C++, build, packaging, test-support, and vector boundaries. Each
component row identifies its owner, material contract links, and assigned test slices.

The build/package rows bind module placement, package `io.screenstream.engine`, compile/target API 37, Kotlin 2.4.0, kotlinx-coroutines 1.11.0, JVM 17,
explicit-API enforcement, ABI/page-size packaging, source sets, generated inputs, and the exact verification tasks that inspect those outputs.

## 2. Private constants

Each name below requires an exact positive finite value unless the architecture fixes the value already:

- `firstMetricsReadinessNanos`
- `initialCapturedResizeReadinessNanos`
- `androidEnteredOperationSafetyNanos`
- `glEnteredOperationSafetyNanos`
- `jpegEnteredOperationSafetyNanos`
- `pboCompletionSafetyNanos`
- `dispatcherCallSafetyNanos`
- `pboPollDelayNanos`
- `maxOldGlErrorsPerFrame`
- `maxFinalGlErrorsPerFrame`
- `memoryHeadroomReserveBytes`
- `grallocRowAlignmentBytes`
- `grallocBufferCountEstimate`
- `grallocFixedOverheadBytes`
- `bitmapRowAlignmentBytes`
- `bitmapFixedOverheadBytes`
- `encodedSegmentOverheadBytes`
- `contextPbufferEstimateBytes`
- `glObjectProgramEstimateBytes`
- `sessionRecordEstimateBytes`
- `allocatorFixedOverheadBytes`

Every readiness and entered-operation deadline named above is positive, finite, private, and non-public. Gate B
presents the exact values for explicit user agreement before implementation authorization. They define no SLA
or additional product timing semantics. The public callback pre-entry deadline and Stats cadence are consumed
unchanged from [01 Product and Public Design](01_design.md) and are not private bindings. Each private constant
row records its unit, numeric type, exact value, arithmetic guard, use sites, and tests. Runtime benchmarks,
soak results, device allowlists, and image scores are not inputs.

## 3. Controller and delivery bindings

The component/owner manifest binds:

- the concrete atomics and transitions for `IDLE`, `RUNNING`, and `RUNNING_DIRTY`;
- the short synchronous public-command gate and immutable Running/terminal snapshot construction;
- desired/revision total ordering, revision exhaustion, the one-inflight/latest desired cell, and the exact reconciliation key;
- active-occurrence fencing, stale-clean versus unsafe result handling, and nonrejecting drainer scheduling;
- distinct `OperationReturnCell`, `DeadlineOccurrence`, entered latch, typed fact, carrier, and receipt representations;
- the fixed one-active delivery worker, one registration, one handoff record, and one encoded lease;
- exact implementations of `Prepared`, `Dispatching`, `AcceptedQueued`, `DetachedPreEntry`, `Entered`, `Resolved`, and `Quarantined`;
- dispatcher-return, callback-entry, callback-return, timeout, unsubscribe, terminal, and late-cleanup race slices, including unsubscribe/stop detachment while
  `dispatcher.dispatch` is in-call. A later normal dispatch return arms the same existing five-second entry/self-rejection deadline; no second timer is created.
  If trampoline entry wins and `dispatch` later throws, entry owns settlement and the throw records no `byDispatchFailure`.

## 4. Platform, runtime-service, GL, and JPEG call bindings

### 4.1 Android lane

Bind the concrete Handler/executor and adapters for every MediaProjection callback and target listener. The sole Android lane owns the exact calls for:

- `MediaProjection.registerCallback` and `unregisterCallback`;
- `MediaProjection.createVirtualDisplay` with null `VirtualDisplay.Callback` and null callback Handler, and `MediaProjection.stop`;
- `VirtualDisplay.resize`, `setSurface`, and `release`;
- projection resize, visibility, and `MediaProjection.Callback.onStop` fact conversion; `onStop` is the sole platform callback authority for `CaptureEnded`;
- target listener installation/removal, non-reused `targetGeneration` capture/check, stale-callback cleanup-only disposition, exhaustion, and same-Handler
  sentinel ordering;
- `Surface.release` after the required detach and lease receipts.

The manifest preserves the cleanup dependency `unregisterCallback -> VirtualDisplay.release -> MediaProjection.stop` and the target dependency
`listener sentinel + detach receipt + zero target leases -> Surface.release -> SurfaceTexture/GL destruction`.
It binds `Surface.release` normal return as the Surface-return receipt, a returned throw as unresolved Surface
ownership that does not stop unrelated cleanup roots, and nonreturn as a rooted call that blocks only physical
dependents.

### 4.2 Metrics, SurfaceTexture, clock, scheduling, memory callbacks, and observations

Bind exact owners, lanes/dispatchers, arguments, returned facts, cancellation, and receipts for:

- `CaptureMetricsProvider.observe()` invocation and Flow collection, collector cancellation request, mechanical collector-return receipt, and the pre-Active
  valid-then-invalid geometry/density disposition `Failed(CaptureUnavailable)` with matching `start` exception;
- built-in metrics access through `DisplayManager.getDisplay(Display.DEFAULT_DISPLAY)`, `Display.getRealSize`, display `Configuration`,
  `WindowManager.getMaximumWindowMetrics`, `Context.createDisplayContext`, UI/window Context association, and API-specific callback adapters;
- `SurfaceTexture(textureName, false)`, `setDefaultBufferSize`, `setOnFrameAvailableListener`, listener removal/sentinel, `updateTexImage`,
  `getTransformMatrix`, `getDataSpace`, and `release`;
- `SystemClock.elapsedRealtimeNanos()`, checked duration conversions, `Handler.post`/`postDelayed`, selected coroutine dispatch/delay boundaries, delayed wake
  identities, and scheduler-rejection behavior;
- `Context.registerComponentCallbacks`/`unregisterComponentCallbacks`, `ComponentCallbacks2.onTrimMemory`/`onLowMemory`, exact pressure mapping, selected
  Java/system memory sampling calls, and memory-trigger fact publication;
- `MutableStateFlow`/`MutableSharedFlow` construction, immutable State/Stats assignment, diagnostic `tryEmit`, replay/capacity/overflow configuration, collector-facing
  publication sites, and terminal observation order.

### 4.3 EGL, render target, and PBO

Bind exact formats, arguments, error checks, and destruction calls for:

- EGL display/config/context/pbuffer creation, binding, unbinding, and destruction without `eglTerminate`;
- OES texture, transform program, canonical state, output RGBA8 RenderTarget allocation, framebuffer attachment, and completeness checks;
- Direct `glReadPixels` and the full PBO inventory: `glGenBuffers`, `glBindBuffer`, `glBufferData`, offset `glReadPixels`, `glFenceSync`, `glFlush`, zero-timeout
  `glClientWaitSync`, `glMapBufferRange`, `glUnmapBuffer`, `glDeleteSync`, and `glDeleteBuffers`;
- exact GL error partitions, poll scheduling, timeout cells, return receipts, fallback disable, and destruction receipts.

### 4.4 Framework JPEG

Bind exact `rowBytes`, `byteCount`, and ByteBuffer guards; `copyPixelsFromBuffer`; row `setPixels`; `Bitmap.compress`; `BM + R` planning; checked
`allocationByteCount` charging; transaction calls; and small channel, row, alpha, odd-width, and guard vectors. Define practical decoded-image tolerances;
lossy JPEG pixels and encoded bytes are never required to be equal.
Bind the final Framework outcome partition: `Bitmap.compress` false is one-frame `byFailure` with later frames
continuing; Bitmap/sink memory exhaustion is terminal `ResourceExhausted`; unexpected exception or malformed
sink result is terminal `InternalFailure`; ambiguous ownership is terminal and quarantined. No outcome publishes
partial bytes or retries the same frame.

### 4.5 Native JPEG

Bind the Kotlin/JNI/C layout and narrowing for every `NativeFrameDescriptor` and `AndroidBitmapInfo` field; dynamic library and
`AndroidBitmap_compress` resolution; ABI and 16-KiB-page packaging; writer callback validation; probe/real-call parity; transactional sink ownership; and native
return, fallback, timeout, and late-return slices.

## 5. Memory and owner bindings

Bind numeric values and allocator attribution for every charge in the architecture ledger: target/gralloc `C`, RenderTarget `B`, CPU `B`, PBO `B`, planned
Bitmap `BM`, actual Bitmap `allocationByteCount`, row `R`, encoded roles `Et/Eu/El/Eo`, segment overhead, fixed context/GL/record/allocator charges, reservations,
and known quarantine.

Every allocation site records reservation consumption, actual-charge conversion, delta admission, refund, transfer, lease, and release edges. Overflow and
denial tests cover every addition, multiplication, alignment, narrowing, segment acceptance, and actual-allocation delta.

The owner/dependency manifest includes these roots:

| Root | Required binding |
| --- | --- |
| Android capture | Serial callback unregister, VirtualDisplay release, and projection stop receipts. |
| Current target | Target-generation fence, stale listener facts, listener sentinel, detach, Surface return, target leases, and GL destruction dependencies. |
| JPEG/storage | Encoder occurrence, transactional sink, CPU/raw carrier, encoded roles, and displaced leases. |
| Frame consumer | Prepared resolution, detached pre-entry resolution, entered return, and exact quarantine residue. |
| Metrics | Cancellation request plus the distinct mechanical collector-return receipt. |
| Deadlines | Identity-fenced wake/deadline retirement and late occurrence cleanup. |
| Memory/diagnostics | Callback detach, diagnostic-reference retirement, reservation refund, and proven carrier release. |
| EGL session | Target-dependent GL destruction followed by context and pbuffer teardown. |

## 6. Diagnostics and observation bindings

Bind the creation sites for the required diagnostic events in [01_design.md](01_design.md#5-diagnostics-contract). Each event assigns the next Session-local
`sequence`, samples wall-clock `timestampEpochMillis`, and supplies `source`, `label`, `message`, and optional raw `cause`. Also bind replay 0, capacity 128,
drop-oldest behavior, direct `tryEmit`, State/Stats assignments, visibility-change conflation, Stats finite-value protection, and the prohibition on routine
per-frame diagnostics. Tests check sequence ordering and field presence; timestamp is correlation data, not an ordering or control clock.

The pre-Active valid-then-invalid metrics case binds source `MetricsProvider`, label `CapabilityCheck`, message
`Required capture metrics became unavailable during startup`, and the raw nullable boundary cause; the
cause-free injected invalid-value case expects null `cause`. Its terminal commit also binds source
`Session`, label `SessionTerminal`, message `Session failed: CaptureUnavailable; last active modes: none`, and
null `cause`.

Diagnostics tests bind the first attempted event to sequence 1 and advance sequence before every `tryEmit`, so
drop-oldest overflow can appear as collector-visible gaps. They bind `timestampEpochMillis` to the wall-clock
sample taken at event creation and verify that repeated, forward, or backward wall-clock values never provide
ordering or control authority.

## 7. Required test slices

Gate B assigns a source path and runner to concise checks for:

- one-shot start, lifecycle, latest-wins parameter updates, and terminal behavior;
- metrics and API-specific geometry/visibility handling, including valid startup metrics followed by invalid geometry/density before initial Active;
- transforms, grayscale/color behavior, JPEG structure, decoded dimensions/orientation, and obvious channel/row corruption using small vectors and declared
  lossy tolerances;
- pacing, static repeat, cache reuse, and Stats accounting;
- Full/Downscaled eligibility, smaller-Surface fit/center behavior, its one safe Full fallback, and the independent Direct/PBO and Framework/Native fallback
  axes;
- single-consumer registration, unsubscribe/re-register, dispatcher/callback failures, and the five-second pre-entry deadline;
- dispatcher entry winning before a later `dispatch` throw, with exact settlement and no `byDispatchFailure`;
- the sole VirtualDisplay creation call's null callback/Handler arguments, `MediaProjection.Callback.onStop` as sole platform `CaptureEnded` callback authority,
  and explicit VirtualDisplay release during cleanup;
- memory denial, arithmetic overflow, stale-work fencing, cleanup, and quarantined late returns;
- diagnostics sequence start/attempt ordering, creation-time wall-clock sampling, and required fields, without comparing event objects or requiring every
  best-effort event to survive buffer overflow;
- JNI layout, native writer validation, ABI packaging, and 16-KiB page compatibility.

The checks use unit, race/fault-injection, instrumentation, native, and small image-vector tests where each is useful. JPEG verification is semantic and
structural; it never compares independently encoded lossy output pixel-for-pixel. Exact vectors and practical tolerances are selected during Gate B.

## 8. Gate-B completion

Gate B is complete when every component owner, private constant, material platform/native boundary, and required test slice above has a concrete binding and the
result remains consistent with [01_design.md](01_design.md), [02_architecture.md](02_architecture.md), and [03_verification.md](03_verification.md). A short manual
smoke run on an available physical device happens after implementation as an informal developer check. Runtime selection continues to use the static rules in
the design and architecture.
