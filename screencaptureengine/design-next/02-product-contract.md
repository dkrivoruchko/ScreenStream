# Screen Capture Engine — Product Contract

This document owns the V1 caller-visible product, public API, values, outcomes, observations, and support
boundaries. Internal ownership, arbitration, settlement, cleanup, and concurrency mechanics belong to
[03-shared-runtime.md](03-shared-runtime.md) and the domain contracts. A referenced internal mechanism cannot
narrow or alter the behavior stated here.

## Navigation

- [PROD-001](#prod-001--product-and-support-boundary)
- [PROD-010](#prod-010--public-session-api)
- [PROD-011](#prod-011--start-cancellation-and-wrong-state-outcomes)
- [PROD-020](#prod-020--configuration-and-metrics)
- [PROD-030](#prod-030--capture-parameters)
- [PROD-031](#prod-031--geometry-and-visible-transform)
- [PROD-040](#prod-040--effective-output-and-borrowed-frame)
- [PROD-050](#prod-050--frame-delivery-caching-and-replacement)
- [PROD-060](#prod-060--state-and-errors)
- [PROD-070](#prod-070--stats)
- [PROD-080](#prod-080--diagnostics)
- [PROD-090](#prod-090--values-observation-and-threading)
- [PROD-100](#prod-100--explicit-v1-boundaries)

## PROD-001 — Product and support boundary

Screen Capture Engine turns one Android `MediaProjection` authority into a sequence of JPEG images. Its public
package is `io.screenstream.engine`; V1 supports Android 7/API 24 through Android 17/API 37.

One application-created Session accepts fresh projection authority once, may receive parameter changes while
running, optionally exposes one current frame-consumer path, and publishes State, Stats, and diagnostics as hot
Flows. A terminal Session never restarts; later capture uses a new Session, new consent, and a fresh projection.

Framework JPEG is mandatory. Native JPEG and early downscaled capture are transparent optional accelerations.
They preserve the same public dimensions, transform, image, ownership, State, and error contract. Runtime
selection uses only documented API/configuration/capability facts. Device allowlists, soak outcomes, performance
measurements, image scores, and memory predictions never select behavior.

V1 emits best-effort SDR JPEG. It has no fixed public image-size or encoded-byte cap. Feasibility uses checked
representation, requested geometry, deterministic device/backend limits, and actual creation/allocation results.
Capture and delivery are best effort rather than realtime; absence of a source frame is not itself a timeout.

The application owns consent UI, notification, permissions, a compliant media-projection foreground service,
copied-JPEG transport and retention, access control, and aggregate pressure across concurrent Sessions. On API
34+ it declares the media-projection foreground-service permission/type and starts that typed service from an
allowed foreground context before obtaining projection authority. An application targeting API 35+ does not
start it from `BOOT_COMPLETED`.

## PROD-010 — Public Session API

```kotlin
public object ScreenCaptureEngine {
    public fun create(
        context: Context,
        config: ScreenCaptureConfig = ScreenCaptureConfig(),
    ): ScreenCaptureSession
}

public interface ScreenCaptureSession {
    public suspend fun start(
        mediaProjection: MediaProjection,
        initialParameters: ScreenCaptureParameters = ScreenCaptureParameters(),
    ): Unit
    public fun updateParameters(parameters: ScreenCaptureParameters): Unit
    public fun onFrame(callback: (EncodedImageFrame) -> Unit): FrameSubscription
    public fun stop(): Unit
    public val state: StateFlow<ScreenCaptureState>
    public val stats: StateFlow<ScreenCaptureStats>
    public val diagnosticEvents: SharedFlow<ScreenCaptureDiagnosticEvent>
}

public interface FrameSubscription {
    public suspend fun unsubscribe(): Unit
}
```

`create` returns a fresh identity-based one-shot Session. It normally retains `context.applicationContext`; an
explicit built-in metrics provider may retain the documented display association. Merely accessing or collecting
the three Flows does not start capture.

At creation, State is `NotStarted`, every Stats/drop field is zero, and no platform/capture owner has started.

| Operation | Caller-visible contract |
| --- | --- |
| `start` | Starts exactly once with fresh authority. Success returns only after `Running(Active)` is visible; it does not wait for the first JPEG. |
| `updateParameters` | Synchronously validates and accepts the latest unequal desire in a nonterminal Running state. Return acknowledges desire, not output convergence. Equal desire is a no-op. |
| `onFrame` | Registers the one current consumer path and returns its identity-based subscription. It performs no capture work by itself. |
| `unsubscribe` | Immediately closes new delivery for that registration, then waits for its entire outstanding handoff to settle. It never stops capture. |
| `stop` | Idempotently fixes owner stop and closes all new public work before returning. Terminal observation and physical cleanup may follow asynchronously. |

`start` and `unsubscribe` are main-safe suspending operations. `updateParameters`, `onFrame`, and `stop` are
thread-safe, synchronous, and nonblocking relative to capture, encode, callback, and cleanup work. Heavy work is
not performed on a caller thread.

## PROD-011 — Start, cancellation, and wrong-state outcomes

The one winning `start` transfers only its supplied projection. A concurrent or repeated loser throws
`IllegalStateException` and does not read, register, stop, retain, or otherwise touch its supplied projection.

Cancellation before start acceptance leaves the Session and projection untouched. Cancellation after acceptance
remains `CancellationException`, requests `Stopped(OwnerStop)`, detaches that waiter, and lets already-entered
mechanics converge independently. If `Stopped(OwnerStop)` or `Stopped(CaptureEnded)` wins after acceptance but
before Active, terminal State is assigned and `start` throws `ScreenCaptureException(CaptureUnavailable)`. If
`Failed(problem)` wins, State is assigned and `start` throws `ScreenCaptureException(problem)` with the selected
cause. Permanent terminal arbitration is owned by
[CTRL-030](05-domain-controller-reconciliation.md#ctrl-030--lifecycle-and-terminal-application).

Local parameter validation always precedes the state decision. Therefore a locally invalid update throws
`IllegalArgumentException` even when the Session state would reject a valid update.

| Call and condition at linearization | Exact result |
| --- | --- |
| winning `start` in `NotStarted` | Accept the projection and begin startup. |
| `start` in Starting, any Running, or terminal | Throw `IllegalStateException`; allocate and touch nothing for the losing projection. |
| `updateParameters` in NotStarted, Starting, or terminal, after local validation | Throw `IllegalStateException`; publish no desire/revision and perform no resource work. |
| legal update structurally equal to current requested parameters | Return `Unit`; requested value and revision remain unchanged. |
| legal unequal update | Replace the latest desired value/revision and return `Unit`; intermediate accepted desires may be conflated in observation. Revision exhaustion fails terminally with `InternalFailure` before reuse and throws its `ScreenCaptureException`. |
| `onFrame` in NotStarted, Starting, or nonterminal Running with no unresolved registration | Register one consumer. A Suspended Session waits for later valid output. |
| `onFrame` while a prior registration has not successfully unsubscribed, or after terminal | Throw `IllegalStateException`; create no generation, handoff, lease, or worker. |
| unresolved `unsubscribe` when `Failed(problem)` wins/is visible | Throw `ScreenCaptureException(problem)`; unresolved residue remains retained and replacement remains forbidden. |
| unresolved `unsubscribe` when `Stopped(...)` wins/is visible | Throw `CancellationException`; unresolved residue remains retained and replacement remains forbidden. |
| already successful `unsubscribe`, before or after terminal | Return `Unit` idempotently. |

Calling `unsubscribe` from its own entered callback fails fast with `IllegalStateException`. Cancelling an
unsubscribe waiter neither reopens delivery nor fabricates settlement; a later call on the same handle may await
the same real result. Only successful unsubscribe return permits replacement registration.

## PROD-020 — Configuration and metrics

```kotlin
public class ScreenCaptureConfig(
    public val captureMetricsProvider: CaptureMetricsProvider? = null,
    public val frameCallbackDispatcher: CoroutineDispatcher = Dispatchers.Default,
    public val jpegBackendPolicy: JpegBackendPolicy = JpegBackendPolicy.Auto,
)

public enum class JpegBackendPolicy { Auto, FrameworkOnly }

public fun interface CaptureMetricsProvider {
    public fun observe(): Flow<CaptureMetrics?>
}

public class CaptureMetrics(
    public val widthPx: Int,
    public val heightPx: Int,
    public val densityDpi: Int,
)

public object CaptureMetricsProviders {
    public fun fromDisplay(context: Context, display: Display): CaptureMetricsProvider
}
```

`CaptureMetrics` requires three positive values. A custom provider returns a cold Flow; null means that its
currently associated geometry is unavailable. A throw from `observe()` is a getter failure. A nonnull returned
Flow is collected; a throw during collection is a collection failure. A null Flow observed through Java/platform
interop is unusable. A provider-origin `CancellationException` while collection remains active is a provider
failure; cancellation caused by Session termination is mechanics cancellation. The engine performs no separate
coldness probe.

| Configuration | Contract |
| --- | --- |
| `captureMetricsProvider == null` | Create one Session-private built-in definition that follows `Display.DEFAULT_DISPLAY` through application `DisplayManager`. |
| `fromDisplay(context, display)` | Return an immutable reusable built-in definition fixed to that exact logical `Display` object. A provably missing/invalid association at construction throws `IllegalArgumentException`; later loss emits null and the same exact association may recover. |
| custom provider | Preserve that exact provider object and its Activity/window/dynamic-display/caller-lifecycle policy. |
| `frameCallbackDispatcher` | Use it only to enter the application frame callback. It is caller-owned and never closed by the engine. Deliberately Unconfined/undispatched callback execution is unsupported. |
| `jpegBackendPolicy == Auto` | Use Native when every documented capability check succeeds; otherwise Framework. Safely returned Native failure disables Native monotonically for later frames. |
| `jpegBackendPolicy == FrameworkOnly` | Never attempt the platform Native compressor. |

Every built-in `observe()` collection is independent, including direct, repeated, concurrent, and cross-Session
collection of the same reusable definition. The `Display` is metrics authority, not capture-source selection;
the caller is responsible for matching it to projection consent.

| API | Width/height authority | Density authority |
| --- | --- | --- |
| 24–33 | selected provider | selected provider |
| 34–37 before first valid projection resize | provider values are provisional; frame admission remains closed | selected provider |
| 34–37 after first valid projection resize | latest projection resize | latest selected-provider density |

On API 34–37, startup cannot produce a frame from provisional dimensions. Missing the initial authoritative
resize becomes startup `CaptureUnavailable`. Visibility remains null on API 24–33; on API 34–37 it becomes the
latest projection `onCapturedContentVisibilityChanged` value and is informational only.

Provider outcomes are closed:

| Outcome | Visible result |
| --- | --- |
| completion before a valid value, or expiry of first-value readiness | startup `Failed(CaptureUnavailable)` and matching start exception |
| getter/unusable-Flow/collection failure before first valid value | startup `Failed(InternalFailure)` and matching start exception |
| loss of required metrics after a valid value but before first Active | startup `Failed(CaptureUnavailable)` and matching start exception |
| normal completion after a valid value | retain the last valid metrics and attempt the `MetricsProvider`/`CapabilityCheck` completion diagnostic with null cause defined by [AND-MET-021](06-domain-android-capture-metrics.md#and-met-021--provider-mechanical-outcome-partition) |
| collection failure after a valid value | terminal `Failed(InternalFailure)` |
| null/unusable runtime value after first Active | `Running(Suspended(CaptureUnavailable))`, retaining last committed geometry as historical observation |
| later valid runtime value | reconcile and potentially resume Active |

A positive tuple incompatible with the requested region/crop is `InvalidRequest`, not `CaptureUnavailable`.

## PROD-030 — Capture parameters

```kotlin
public class ScreenCaptureParameters(
    public val sourceRegion: SourceRegion = SourceRegion.Full,
    public val crop: CropInsetsPx = CropInsetsPx.Zero,
    public val outputSize: OutputSize = OutputSize.ScaleFactor(0.5),
    public val rotation: Rotation = Rotation.Degrees0,
    public val mirror: Mirror = Mirror.None,
    public val colorMode: ColorMode = ColorMode.Color,
    public val frameRate: FrameRate = FrameRate.Auto,
    public val frameRepeatIntervalMillis: Long? = null,
    public val jpegQuality: Int = 80,
)

public enum class SourceRegion { Full, LeftHalf, RightHalf }

public class CropInsetsPx(
    public val left: Int,
    public val top: Int,
    public val right: Int,
    public val bottom: Int,
) {
    public companion object { public val Zero: CropInsetsPx }
}

public sealed interface OutputSize {
    public class ScaleFactor(public val factor: Double) : OutputSize
    public class TargetSize(
        public val width: Int,
        public val height: Int,
        public val contentMode: ContentMode = ContentMode.AspectFit,
    ) : OutputSize
}

public enum class ContentMode { Stretch, AspectFit }
public enum class Rotation { Degrees0, Degrees90, Degrees180, Degrees270 }
public enum class Mirror { None, Horizontal, Vertical }
public enum class ColorMode { Color, Grayscale }

public sealed interface FrameRate {
    public object Auto : FrameRate
    public class MaxFps(public val fps: Int) : FrameRate
    public class SampleEvery(public val intervalMillis: Long) : FrameRate
}
```

| Parameter | Domain and exact meaning |
| --- | --- |
| `sourceRegion` | Full, left half, or right half before other transforms. A half requires authoritative width at least 2; on odd width the right half owns the final column. |
| `crop` | Four nonnegative unrotated selected-region insets that must leave nonempty content when geometry is known. Crop is selection, not security redaction. |
| `ScaleFactor(f)` | `f` is finite and positive. It scales the positive oriented dimensions by the exact rule below. |
| `TargetSize(w,h,Stretch)` | Positive requested dimensions used exactly. |
| `TargetSize(w,h,AspectFit)` | Positive bounds, aspect fit without padding, using the exact integer rule below. |
| `rotation` | Clockwise; 90/270 exchange oriented dimensions. |
| `mirror` | Horizontal/vertical in the image after rotation. |
| `colorMode` | Color or gamma-coded grayscale after source color handling and sizing. |
| `FrameRate.Auto` | Source/capacity pace. |
| `MaxFps(fps)` | `fps` is `1..120`; caps fresh admission and produced publication. |
| `SampleEvery(ms)` | `ms` is `1_001..3_600_000`; first eligible current source is immediate, then fresh sampling is slower than one frame/second. |
| `frameRepeatIntervalMillis` | Null or `1_000..3_600_000`; republishes valid cached JPEG bytes without capture/encode and remains subject to MaxFps. |
| `jpegQuality` | `0..100` hint. Change invalidates cached/repeat bytes encoded with the prior quality. |

Parameter graphs are deeply immutable. Constructors reject local violations with `IllegalArgumentException` and
never clamp invalid input. Geometry-dependent invalidity is decided during startup/reconciliation.

## PROD-031 — Geometry and visible transform

The transform order is fixed:

```text
source region -> unrotated crop -> clockwise rotation -> oriented mirror
-> output sizing -> source-to-SDR/sRGB handling -> color mode -> top-down JPEG rows
```

For positive oriented dimensions `(Rw,Rh)`, `ScaleFactor(f)` uses binary64 in this exact order:

```text
scaledW = binary64(Rw) * f; roundedW = floor(scaledW + 0.5)
scaledH = binary64(Rh) * f; roundedH = floor(scaledH + 0.5)
Ow = max(1, checkedNonNegativeInt(roundedW))
Oh = max(1, checkedNonNegativeInt(roundedH))
```

`checkedNonNegativeInt` accepts exactly `0..Int.MAX_VALUE`. Nonfinite or out-of-range products/sums/results are
`InvalidRequest`; the one-pixel minimum above is the only size clamp.

For `TargetSize(A,B,AspectFit)`, checked positive `Long` arithmetic uses no padding:

```text
if A*Rh <= B*Rw:
    Ow = A
    Oh = min(B, max(1, (A*Rh + Rw/2) / Rw))
else:
    Oh = B
    Ow = min(A, max(1, (B*Rw + Rh/2) / Rh))
```

Output is opaque top-down RGBA before JPEG. V1 grayscale uses the inexpensive gamma-coded rule
`Y=(77R+150G+29B+128)>>8`, assigns `R=G=B=Y`, and forces alpha 255. It is not linear-light Rec.709.
Exact sRGB is nominal sRGB; exact Display-P3 uses the documented P3-to-sRGB path; scRGB, ST2084, HLG, and unknown
evidence use the documented best-effort SDR actions. Tests never become runtime selectors.

## PROD-040 — Effective output and borrowed frame

```kotlin
public class ImageRect internal constructor(
    public val left: Int,
    public val top: Int,
    public val right: Int,
    public val bottom: Int,
)

public class CaptureGeometry internal constructor(
    public val widthPx: Int,
    public val heightPx: Int,
    public val densityDpi: Int,
)

public class ImageSize internal constructor(
    public val widthPx: Int,
    public val heightPx: Int,
)

public class ScreenCaptureEffectiveParameters internal constructor(
    public val captureGeometry: CaptureGeometry,
    public val sourceRegion: SourceRegion,
    public val crop: CropInsetsPx,
    public val appliedSourceRect: ImageRect,
    public val outputSize: OutputSize,
    public val finalImageSize: ImageSize,
    public val rotation: Rotation,
    public val mirror: Mirror,
    public val colorMode: ColorMode,
    public val frameRate: FrameRate,
    public val frameRepeatIntervalMillis: Long?,
    public val jpegQuality: Int,
)

public interface EncodedImageFrame {
    public val byteCount: Int
    public val imageSize: ImageSize
    public val sequence: Long
    public val timestampElapsedRealtimeNanos: Long
    public fun copyTo(destination: ByteArray, destinationOffset: Int = 0): Int
    public fun copyBytes(): ByteArray
}
```

`CaptureGeometry` is the authoritative source geometry of the last committed startup/reconciliation. `ImageRect`
is a left/top-inclusive, right/bottom-exclusive rectangle in that source. `ScreenCaptureEffectiveParameters`
reports that committed startup/reconciliation and is observation, not reusable configuration.

Every produced frame has exact JPEG byte count and positive image size. Sequence is Session-local, starts at one,
and fails terminally before reuse. Timestamp is the elapsed-realtime sample at output commit; equality is allowed.
`copyTo` validates the complete destination range, throws `IndexOutOfBoundsException` without copying on invalid
input, and otherwise copies exactly `byteCount`. `copyBytes` returns one exact caller-owned array.

The frame object is borrowed. Every property/copy operation is legal only on its callback thread and until that
callback returns; wrong-thread or post-return access throws `IllegalStateException`.

## PROD-050 — Frame delivery, caching, and replacement

At most one callback invocation is outstanding. Callback execution uses the configured dispatcher. A supported
dispatcher eventually returns or throws from each `dispatch` invocation and eventually executes every normally
accepted task. A normally accepted task that has not entered within 5,000 ms terminally fails the Session; an
entered callback has no execution timeout. A dispatcher-call nonreturn is not timed: it can permanently occupy
the one handoff and prevent replacement.

One delivery has separate dispatcher-call, trampoline-entry, and callback-return sides. Entry wins delivery
classification. A later dispatcher return/throw cannot reclassify an entered callback or increment
`byDispatchFailure`. Callback return immediately releases borrowed-frame authority and its encoded lease, but if
the dispatch call is still unresolved the handoff remains occupied; a new opportunity records
`byConsumerBusy`. Only settlement of every required side permits reuse or successful unsubscribe.

A dispatcher throw/rejection that commits before entry drops only that opportunity, increments
`byDispatchFailure`, leaves registration active, and creates no retry queue. An entered callback throw that
commits before terminal transfer increments `byCallbackFailure`, resolves its callback side, and leaves the
registration active for later output. Results committed after terminal transfer change only cleanup residue and
no public counter, diagnostic problem, or State.

After successful registration, the current valid cached JPEG is offered immediately when available. It preserves
the original bytes, sequence, timestamp, and image size; it performs no capture/encode/copy, does not increment
`framesProduced`, and is not delayed by MaxFps or repeat pacing. It uses the same handoff and unsubscribe rules.

Repeat republishes the latest valid immutable JPEG with new sequence/timestamp, performs no capture/GL/JPEG/copy,
and increments `framesProduced` but not `framesEncoded`. Fresh output wins a tie. Suspension, untrusted source,
image-affecting change, fallback, target/output rebuild, or terminal invalidates cache/repeat. A healthy retained
target is not replaced solely for freshness, and the first buffer after resume may predate the resume; V1 makes no
post-resume freshness guarantee.

## PROD-060 — State and errors

```kotlin
public sealed interface ScreenCaptureState {
    public object NotStarted : ScreenCaptureState
    public object Starting : ScreenCaptureState
    public class Running internal constructor(
        public val requestedParameters: ScreenCaptureParameters,
        public val runningState: ScreenCaptureRunningState,
        public val capturedContentVisible: Boolean?,
    ) : ScreenCaptureState
    public class Stopped internal constructor(
        public val reason: ScreenCaptureStopReason,
        public val requestedParameters: ScreenCaptureParameters,
        public val lastEffectiveParameters: ScreenCaptureEffectiveParameters?,
    ) : ScreenCaptureState
    public class Failed internal constructor(
        public val problem: ScreenCaptureProblem,
        public val requestedParameters: ScreenCaptureParameters,
        public val lastEffectiveParameters: ScreenCaptureEffectiveParameters?,
    ) : ScreenCaptureState
}

public sealed interface ScreenCaptureRunningState {
    public class Active internal constructor(
        public val effectiveParameters: ScreenCaptureEffectiveParameters,
    ) : ScreenCaptureRunningState
    public class Suspended internal constructor(
        public val problem: ScreenCaptureProblem,
        public val lastEffectiveParameters: ScreenCaptureEffectiveParameters,
        public val lastKnownCaptureGeometry: CaptureGeometry?,
    ) : ScreenCaptureRunningState
}

public enum class ScreenCaptureStopReason { OwnerStop, CaptureEnded }

public enum class ScreenCaptureProblem {
    InvalidRequest,
    CaptureUnavailable,
    Reconfiguring,
    ResourceExhausted,
    InternalFailure,
}

public class ScreenCaptureException internal constructor(
    public val problem: ScreenCaptureProblem,
    cause: Throwable? = null,
) : Exception(problem.name, cause)
```

Active is valid only while a healthy compatible live output topology is actually owned. Historical effective
parameters cannot make retired output Active. Every Running value is one immutable requested/running/visibility
snapshot. Terminal values freeze final requested and last committed effective parameters (`null` if none).
`lastKnownCaptureGeometry` is historical observation, never current availability proof.

| Problem | Meaning |
| --- | --- |
| `InvalidRequest` | Current geometry and parameters cannot produce the requested result. |
| `CaptureUnavailable` | Projection, source, or required metrics are unavailable. |
| `Reconfiguring` | Output is unavailable after irreversible retirement and before a current replacement/result, including retirement of a poisoned private GL context. It admits no output, fallback, or retry. Startup remains Starting rather than publishing this state. |
| `ResourceExhausted` | A clean deterministic capacity or required allocation/creation boundary denied the request. |
| `InternalFailure` | Platform, GL/JPEG, ownership, or engine evidence became unsafe or inconsistent. |

Startup has no Suspended outcome. During Running, invalid geometry and missing capture authority suspend and may
recover. A deterministic mandatory denial before irreversible retirement suspends with `ResourceExhausted` and
retains the desire. A required current allocation/creation failure after retirement is terminal
`ResourceExhausted`. Startup deterministic denial or required allocation/creation failure is likewise terminal
`ResourceExhausted`. Required pixel-carrier or encoded-storage allocation failure during Running is terminal
`ResourceExhausted`, not optional fallback. A safely returned optional-path failure disables only that
acceleration for later work. Any
ownership-ambiguous or unsafe result is terminal `InternalFailure`, including when stale.

During the sole `createVirtualDisplay` attempt, null or `SecurityException` is `CaptureUnavailable`; a directly
thrown `OutOfMemoryError` is `ResourceExhausted`; `IllegalStateException` or any other unexpected throwable is
`InternalFailure`.

Terminal State ends capture authority and all new work, but is not a physical-cleanup receipt. Cleanup may
continue or retain unresolved resources until process death; the public API exposes no cleanup-completion handle.

## PROD-070 — Stats

```kotlin
public class ScreenCaptureStats internal constructor(
    public val framesEncoded: Long,
    public val framesProduced: Long,
    public val droppedFrames: ScreenCaptureFrameDropStats,
    public val droppedDeliveries: ScreenCaptureDeliveryDropStats,
    public val averageProducedFps: Double,
    public val averageEncodeMs: Double,
    public val averageReadbackMs: Double,
    public val lastEncodedByteCount: Int,
    public val averageEncodedByteCount: Int,
)

public class ScreenCaptureFrameDropStats internal constructor(
    public val byRateLimit: Long,
    public val byPipelineBusy: Long,
    public val byStaleWork: Long,
    public val byFailure: Long,
) {
    public val total: Long
        get() = saturatingSum(byRateLimit, byPipelineBusy, byStaleWork, byFailure)
}

public class ScreenCaptureDeliveryDropStats internal constructor(
    public val byConsumerBusy: Long,
    public val byDispatchFailure: Long,
    public val byCallbackFailure: Long,
) {
    public val total: Long
        get() = saturatingSum(byConsumerBusy, byDispatchFailure, byCallbackFailure)
}
```

All fields start at zero. Counters and totals saturate at `Long.MAX_VALUE`. `droppedFrames.total` is the
saturating sum of `byRateLimit + byPipelineBusy + byStaleWork + byFailure`;
`droppedDeliveries.total` is the saturating sum of
`byConsumerBusy + byDispatchFailure + byCallbackFailure`. Each total is derived from its components, never an
independently updated counter. `framesEncoded` counts mechanically successful fresh JPEG encodes, including a
result later suppressed as stale. `framesProduced` counts fresh and repeat output commits whether or not a
consumer exists; cached-first delivery counts neither.

An early paced source remains one unmaterialized pending source, so V1 `byRateLimit` remains zero. A materialized
attempt dropped because the production slot changed is `byPipelineBusy`. A mechanically returned production
failure is `byFailure` even if its work identity became stale; `byStaleWork` is reserved for otherwise successful
work suppressed solely by stale identity. Terminal retirement of unclassified/unpublished/transferred work adds
no frame-drop count.

`byConsumerBusy`, `byDispatchFailure`, and `byCallbackFailure` retain the exact delivery meanings in `PROD-050`.
Engine scheduling rejection is an internal Session failure, not a dispatch drop.
Producing output while no consumer is registered is not a delivery drop.

Encode/readback means include only successful real operations; repeat contributes no sample. Each eligible
duration sample is the nonnegative `endNanos - startNanos` from nondecreasing `EngineClock` nanosecond samples.
Every duration or encoded-byte series applies samples in commit order to its binary64 accumulator:
`mean = mean + (sample - mean) / sampleCount`; duration series use `sampleNanos` and `meanNanos`.
`averageEncodeMs` and `averageReadbackMs` are their respective `meanNanos / 1_000_000.0`, evaluated with
IEEE-754 round-to-nearest. A mean with zero samples is `0.0`. `lastEncodedByteCount` is zero before any successful
fresh-encode sample; afterwards it is the exact `Int` byte size of the latest accepted sample from the same
mechanically successful fresh-encode set and controller cutoff as `framesEncoded` and the encoded-byte mean,
including success later suppressed as stale. Failure, repeat, cached-first delivery, and cleanup-only late return
contribute no sample. `averageEncodedByteCount` is zero with no samples, otherwise
`min(Int.MAX_VALUE,floor(meanBytes+0.5))`. Produced FPS is zero with fewer than two output commits or a
nonpositive first-to-last elapsed interval; otherwise it is
`(framesProduced-1)*1e9/(lastProduction-firstProduction)`, including repeats and elapsed deep sleep/suspension
inside that interval. Public Doubles are always finite: invalid mean updates retain the last finite mean,
nonfinite FPS clamps to the greatest finite Double, and saturation freezes the affected derived value.

Ordinary dirty Stats publish no more often than once per 1,000 ms of engine elapsed time, without catch-up.
Lifecycle, suspension/resume, rebuild/fallback, and terminal changes publish immediately. Final Stats is assigned
immediately before terminal State. State and Stats remain separate Flows, not an atomic combined value.
Controller eligibility, accounting, and cutoff commits are owned by
[CTRL-200](05-domain-controller-reconciliation.md#ctrl-200--policy-attempt-counter-and-observation-authority);
unlocked Stats construction and publication are owned by
[DEL-OBS-010](12-domain-delivery-observation.md#del-obs-010--stats-accounting-and-cadence).

## PROD-080 — Diagnostics

```kotlin
public class ScreenCaptureDiagnosticEvent internal constructor(
    public val sequence: Long,
    public val timestampEpochMillis: Long,
    public val source: String,
    public val label: String,
    public val message: String,
    public val cause: Throwable? = null,
)
```

The Flow has `replay=0`, `extraBufferCapacity=128`, and `BufferOverflow.DROP_OLDEST`. Each required observation
makes one best-effort attempt. Sequence starts at one and advances per attempt; overflow may expose gaps.
`timestampEpochMillis` samples wall clock for correlation and is never ordering, deadline, or control authority.
Message wording is noncontractual but short and semantically identifies the decision/action. Throwable text is
not copied into the message; raw cause remains a separate reference whose retention policy belongs to the app.

Allowed sources are exactly `Session`, `MetricsProvider`, `MediaProjection`, `VirtualDisplay`, `SurfaceTarget`,
`GlPipeline`, `FrameworkJpeg`, `NativeJpeg`, `FrameConsumer`, `Controller`, `ColorPipeline`, and `Cleanup`.

| Label | Allowed source and required observation |
| --- | --- |
| `CapabilityCheck` | Applicable boundary source for each top-level capability selection or failure. |
| `RuntimeProfile` | `Session`, once for initial Running modes. |
| `RuntimeModeChanged` | `SurfaceTarget`, `FrameworkJpeg`, or `NativeJpeg` for an actual mode change/fallback. |
| `DeliveryProblem` | `FrameConsumer` for committed dispatcher/callback failure. |
| `StatsProblem` | `Controller` for finite-value protection. Cause is null. |
| `ColorAction` | `ColorPipeline` for initial/changed color classification and action. |
| `QuarantineChanged` | `Cleanup` only when exact quarantine ownership actually changes. |
| `SessionTerminal` | `Session` for the winning stop reason/problem and last active modes. |

Diagnostics are observation only: collection, loss, delay, overflow, or failed emission changes no lifecycle,
fallback, Stats, ownership, cleanup, or result. Routine geometry, visibility, rebuild, consumer lifecycle, and
frame production require no event.

## PROD-090 — Values, observation, and threading

Public API uses ordinary classes, never data classes. Parameters, parameter values, metrics, geometry,
`ImageSize`, State, effective parameters, Stats, and drop vectors have manual structural equality/hash and a
bounded `toString()` containing the class name and documented scalar/enum/value fields. It excludes buffers,
bytes, callbacks, providers, dispatchers, and Throwable graphs. Config, provider, dispatcher, Session,
subscription, frame lease, diagnostic event, and exception retain identity semantics unless stated otherwise.

Only caller-input models have public constructors. Engine-produced geometry, output, State, Stats, diagnostics,
frame, subscription, and exception values cannot be constructed by callers. Kotlin/JVM null checks retain normal
behavior.

Caller-input public constructors, `ScreenCaptureEngine.create`, and the State, Stats, and diagnostic Flow getters
are callable from any thread. They perform only bounded validation or snapshot access: no Handler/Looper or
platform capture call and no capture startup.

State and Stats are latest-value StateFlows. With supported nonblocking, non-reentrant collectors, observation
does not participate in engine progress. A collector must not block the publishing thread or synchronously
reenter an operation that waits on publication. `Main.immediate` is supported for nonblocking/non-reentrant
collection; Unconfined collection is unsupported where it creates reentry/liveness risk.

Private readiness and entered-operation boundaries are positive finite implementation policy, not public SLAs.
They cannot change product classification, ownership, lifecycle priority, support, or runtime selection. The only
fixed public timing values are the 5,000 ms accepted-callback entry deadline and the 1,000 ms ordinary Stats
cadence.

## PROD-100 — Explicit V1 boundaries

- Concurrent Sessions are supported, but the engine provides no process-wide resource or progress guarantee.
- Early Downscale may differ from Full by ordinary platform filtering/rounding while preserving semantic output.
- A safe Native compressor allocation failure may lose that one frame, disable Native, and use Framework only
  for later frames; it never encodes the same frame twice.
- Framework `Bitmap.compress` false drops that frame as `byFailure` and retains Framework for later frames.
  Entered required-storage OOM is terminal `ResourceExhausted`; malformed/unexpected behavior is terminal
  `InternalFailure`; partial bytes never publish.
- Managed reclamation, opaque allocator/driver bytes, and physical release after logical reference drop are
  outside product observation.
- Crop/region selection is not privacy redaction because sampling can mix edge texels.
- The engine provides no post-resume source-freshness guarantee and no cleanup-completion API.
- Future HDR mapping, linear-light grayscale, general early-target planning, video, and audio are outside V1.
