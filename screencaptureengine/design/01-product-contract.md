# Screen Capture Engine — Product Contract

This document is the sole authority for the V1 caller-visible API, values, validation, lifecycle outcomes,
frames, observations, and support boundary. Internal authority and execution belong to
[`02-internal-architecture.md`](02-internal-architecture.md); platform metrics to
[`03-metrics-platform.md`](03-metrics-platform.md); capture and image production to
[`04-capture-rendering.md`](04-capture-rendering.md); managed JPEG and encoded storage to
[`05-framework-encoding-storage.md`](05-framework-encoding-storage.md); Native JPEG to
[`06-native-jni-package.md`](06-native-jni-package.md); delivery and observation mechanics to
[`07-delivery-observation.md`](07-delivery-observation.md); and acceptance evidence to
[`08-verification.md`](08-verification.md). Those owners implement this contract and cannot narrow it.

## 1. Product and support boundary

Screen Capture Engine turns one Android `MediaProjection` authority into a sequence of JPEG images. Its public
package is `io.screenstream.engine`. V1 supports Android 7/API 24 through Android 17/API 37.

One application-created Session accepts fresh projection authority once, accepts parameter changes while
running, optionally exposes one current frame-consumer path, and publishes State, Stats, and diagnostics as hot
Flows. A terminal Session never restarts. Later capture requires a new Session, new consent, and a fresh
projection.

Framework JPEG is mandatory. Native JPEG and early downscaled capture are transparent optional accelerations;
they preserve the public dimensions, transforms, image semantics, ownership, State, Stats, and failure contract.
Selection uses only documented API, configuration, and capability facts. Device identity, allowlists,
benchmarks, image scores, memory predictions, and tests never select runtime behavior.

V1 emits best-effort SDR JPEG. It has no fixed public image-dimension or encoded-byte cap. Feasibility is decided
from checked representation, requested geometry, deterministic device/backend limits, and actual required
creation/allocation results. Capture and delivery are best effort, not realtime; absence of a source frame is not
itself a timeout.

The application owns consent UI, notification, permissions, a compliant media-projection foreground service,
copied-JPEG transport and retention, access control, and aggregate pressure across concurrent Sessions. On API
34+ it declares the media-projection foreground-service permission/type and starts that typed service from an
allowed foreground context before obtaining projection authority. An application targeting API 35+ does not
start that service from `BOOT_COMPLETED`.

## 2. Public API and construction

```kotlin
public object ScreenCaptureEngine {
    public fun create(
        context: Context,
        config: ScreenCaptureConfig = ScreenCaptureConfig(),
    ): ScreenCaptureSession
}

public class ScreenCaptureSession private constructor(/* engine-owned arguments */) {
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

public class FrameSubscription private constructor(/* engine-owned arguments */) {
    public suspend fun unsubscribe(): Unit
}
```

`create` returns a fresh, identity-based, one-shot Session. It normally retains `context.applicationContext`; an
explicit built-in metrics source may also retain its documented Display association. Accessing or collecting a
Flow does not start capture. At creation, State is `NotStarted`, every Stats and drop field is zero, and no
capture owner has started.

Only caller-input models and genuine extension points have public constructors. Engine-produced geometry,
output, State, Stats, diagnostic, Session, frame, subscription, and exception facades have private Kotlin
constructors. The engine constructs them through `internal @JvmSynthetic` Kotlin factories. External Kotlin
source cannot call either the constructors or factories. Factory names, private constructor arguments,
synthetic accessors, and representation are not API. Schematic constructor comments above do not mean a
no-argument constructor. The supported API is Kotlin-only; Java interoperability is best effort, and normal
Kotlin/JVM null checks apply.

## 3. Configuration and metrics extension API

```kotlin
public class ScreenCaptureConfig(
    public val captureMetricsSource: CaptureMetricsSource? = null,
    public val jpegBackendPolicy: JpegBackendPolicy = JpegBackendPolicy.Auto,
)

public enum class JpegBackendPolicy { Auto, FrameworkOnly }

public fun interface CaptureMetricsSource {
    public fun subscribe(observer: CaptureMetricsObserver): CaptureMetricsSubscription
}

public interface CaptureMetricsObserver {
    public fun onMetricsChanged(metrics: CaptureMetrics?): Unit
    public fun onComplete(): Unit
    public fun onFailure(cause: Throwable): Unit
}

public fun interface CaptureMetricsSubscription {
    public fun close(): Unit
}

public class CaptureMetrics(
    public val widthPx: Int,
    public val heightPx: Int,
    public val densityDpi: Int,
)

public object CaptureMetricsSources {
    public fun fromDisplay(context: Context, display: Display): CaptureMetricsSource
}
```

`CaptureMetrics` requires three positive values. A null metrics value means that the selected source's currently
associated geometry is unavailable. `CaptureMetricsSource` is externally implementable and must support
independent repeated subscriptions. Each `subscribe` establishes one observation and returns its exact close
handle. Observer calls may occur synchronously before `subscribe` returns and from arbitrary source-owned
threads.

Configuration has these exact meanings:

| Value | Contract |
| --- | --- |
| `captureMetricsSource == null` | The Session creates one private built-in source that follows `Display.DEFAULT_DISPLAY` through the application `DisplayManager`; after removal it may select the new current default Display object. |
| `CaptureMetricsSources.fromDisplay(context, display)` | Retains normalized `context.applicationContext` and returns an immutable reusable private implementation fixed to that exact logical `Display` object and ID; another object is never substituted merely because it has the same ID. A provably missing or invalid association at construction throws `IllegalArgumentException`. Later loss emits null, and that same association may recover. |
| custom source | The Session preserves the exact source identity and the caller's Activity/window/dynamic-display/lifecycle policy. |
| `JpegBackendPolicy.Auto` | Native is used only when every documented capability check succeeds; otherwise Framework is used. A safely returned Native compressor failure disables Native monotonically for later frames. |
| `JpegBackendPolicy.FrameworkOnly` | The Native compressor is never attempted. |

Every engine attachment to a built-in source is independent, including repeated, concurrent, reused-source, and
cross-Session attachments. Each Session attachment invokes its selected source's `subscribe` exactly once. The
Display is metrics authority, not capture-source selection; the caller must match it to projection consent.

| API | Width/height authority | Density authority |
| --- | --- | --- |
| 24–33 | selected source | selected source |
| 34–37 before first valid captured resize | source dimensions are provisional and frame admission remains closed | selected source |
| 34–37 after first valid captured resize | latest projection resize | latest selected-source density |

API 34–37 startup cannot produce a frame from provisional dimensions. Missing the initial authoritative resize
is startup `CaptureUnavailable`. Visibility is null on API 24–33. On API 34–37 it is the latest captured-content
visibility observation and is informational only.

Metrics-source outcomes are closed:

| Outcome | Caller-visible result |
| --- | --- |
| normal completion before joint readiness, including after a timely valid value but before handle adoption | startup `Failed(CaptureUnavailable)` and matching start exception; no completion diagnostic |
| readiness expiry | startup `Failed(CaptureUnavailable)` and matching start exception |
| `subscribe` throws `Exception`, returns an invalid/null interop handle, or reports failure before joint readiness | startup `Failed(InternalFailure)` and matching start exception; while readiness is unresolved, a throw or invalid handle outranks staged callbacks |
| required metrics are lost after a valid value but before first `Active` | startup `Failed(CaptureUnavailable)` and matching start exception, even if a later staged value is valid |
| normal completion after joint readiness, including before first `Active` | close ingress, retain the last valid metrics, permit startup to continue, and make exactly one normal `MetricsSource`/`CapabilityCheck` diagnostic attempt with null cause for that observation lifetime |
| source failure after a valid value | terminal `Failed(InternalFailure)` |
| subscription close throws `Exception` while nonterminal | terminal `Failed(InternalFailure)` |
| null or unusable runtime value after first `Active` | `Suspended(CaptureUnavailable)` with historical last committed geometry retained |
| later valid runtime value | reconcile and potentially resume `Active` |

Joint readiness uses the earliest positive sample's elapsed `T < D`, but succeeds only when that tuple and the
same attachment's normally returned nonnull exact handle coexist before prior loss, terminal, or expiry. A
positive tuple incompatible with region/crop is `InvalidRequest`, not `CaptureUnavailable`. A late handle or
resize result cannot revive failed startup.

## 4. Capture parameters and validation

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
| `sourceRegion` | Full, left half, or right half before other transforms. A half requires authoritative width at least 2. On odd width, the right half owns the final column. |
| `crop` | Four nonnegative insets in the unrotated selected-region coordinate space; known geometry must leave nonempty content. Crop is selection, not security redaction. |
| `ScaleFactor(f)` | `f` is finite and positive and scales the positive oriented dimensions by §5. |
| `TargetSize(w,h,Stretch)` | `w` and `h` are positive and are used exactly. |
| `TargetSize(w,h,AspectFit)` | `w` and `h` are positive bounds; output aspect-fits without padding by §5. |
| `rotation` | Clockwise; 90° and 270° exchange oriented dimensions. |
| `mirror` | Horizontal or vertical in the image after rotation. |
| `colorMode` | Color or gamma-coded grayscale after source color handling and sizing. |
| `FrameRate.Auto` | Source/capacity pace. |
| `MaxFps(fps)` | `fps` is `1..120`; it caps fresh admission and all produced publication. |
| `SampleEvery(ms)` | `ms` is `1_001..3_600_000`; the first eligible current source is immediate, then fresh sampling is slower than one frame per second. |
| `frameRepeatIntervalMillis` | Null or `1_000..3_600_000`; repeats valid cached JPEG bytes without capture or encoding and remains subject to `MaxFps`. |
| `jpegQuality` | `0..100` hint. A change invalidates bytes encoded at the prior quality. |

Parameter graphs are deeply immutable. Public constructors reject local violations with
`IllegalArgumentException` and never clamp. Geometry-dependent invalidity is decided during startup or
reconciliation and maps to `InvalidRequest`.

## 5. Dimensions, transform, color, and JPEG semantics

The visible transform order is fixed:

```text
source region -> unrotated crop -> clockwise rotation -> oriented mirror
-> output sizing -> source-to-SDR/sRGB handling -> color mode -> top-down JPEG rows
```

For authoritative source dimensions `(W,H)`, the selected source rectangles are Full `(0,0,W,H)`, LeftHalf
`(0,0,W/2,H)`, and RightHalf `(W/2,0,W,H)`, using integer division. Crop offsets that selected rectangle in
source coordinates. Rotation is then applied clockwise, and mirror axes refer to the already-oriented image.

For positive oriented dimensions `(Rw,Rh)`, `ScaleFactor(f)` uses binary64 in this exact order:

```text
scaledW = binary64(Rw) * f; roundedW = floor(scaledW + 0.5)
scaledH = binary64(Rh) * f; roundedH = floor(scaledH + 0.5)
Ow = max(1, checkedNonNegativeInt(roundedW))
Oh = max(1, checkedNonNegativeInt(roundedH))
```

`checkedNonNegativeInt` accepts exactly `0..Int.MAX_VALUE`. A nonfinite or out-of-range product, sum, or result
is `InvalidRequest`. The one-pixel minimum above is the only output-size clamp.

For `TargetSize(A,B,AspectFit)`, checked positive `Long` arithmetic uses no padding:

```text
if A*Rh <= B*Rw:
    Ow = A
    Oh = min(B, max(1, (A*Rh + Rw/2) / Rw))
else:
    Oh = B
    Ow = min(A, max(1, (B*Rw + Rh/2) / Rh))
```

Output before JPEG is opaque, top-down RGBA. Exact sRGB evidence is nominal sRGB. On API 24–32, source color is
nominal SDR. On API 33+, color evidence is classified in this order: exact sRGB is nominal sRGB; exact
Display-P3 is converted to sRGB; exact scRGB/scRGB-linear and ST2084/HLG transfer evidence use system-composited
8-bit best effort; every other value uses nominal-SDR best effort. Display capability is not source-buffer
evidence, and V1 exposes neither an HDR flag nor an HDR colorimetric guarantee.

The Display-P3 reference conversion decodes each gamma-coded channel, applies this D65 linear transform in
written order, clamps each linear result to `[0,1]`, and encodes sRGB:

```text
decode(c) = c/12.92                         if c <= 0.04045
            ((c+0.055)/1.055)^2.4          otherwise
R' =  1.2247452668R - 0.2249043652G
G' = -0.0420579309R + 1.0420810013G
B' = -0.0196422806R - 0.0786549180G + 1.0985371988B
encode(c) = 12.92c                          if c <= 0.0031308
            1.055*c^(1/2.4)-0.055           otherwise
```

The selected fragment shader applies this reference once before readback. It prefers fragment `highp` and uses
`mediump` as a best-effort fallback when `highp` is unavailable. The written formula, matrix order, clamp and
final `min(255,max(0,floor(255*c+0.5)))` describe the semantic result, but shader evaluation is not required to
use binary64 and V1 makes no cross-GPU bit-exact color guarantee. The exact precision-specific visual bounds
belong to Verification's GL image oracle. After source color handling, grayscale uses the gamma-coded reference
rule `Y=(77R+150G+29B+128)>>8`, assigns `R=G=B=Y`, and forces alpha 255. It is not linear-light Rec.709.

Committed bytes are a complete immutable JPEG payload; partial, failed, stale, or tentative bytes never
publish. `jpegQuality` is a quality hint rather than a byte-for-byte encoder guarantee. Early Downscale may
differ from Full by ordinary platform filtering and rounding while preserving the semantic output above.
Capture/rendering owns the exact sampling and target mechanics; Framework/storage and Native own their encoder
mechanics and binary boundaries.

## 6. Effective output and borrowed frame

```kotlin
public class ImageRect private constructor(
    public val left: Int,
    public val top: Int,
    public val right: Int,
    public val bottom: Int,
)

public class CaptureGeometry private constructor(
    public val widthPx: Int,
    public val heightPx: Int,
    public val densityDpi: Int,
)

public class ImageSize private constructor(
    public val widthPx: Int,
    public val heightPx: Int,
)

public class ScreenCaptureEffectiveParameters private constructor(
    public val appliedParameters: ScreenCaptureParameters,
    public val captureGeometry: CaptureGeometry,
    public val appliedSourceRect: ImageRect,
    public val finalImageSize: ImageSize,
)

public class EncodedImageFrame private constructor(/* engine-owned arguments */) {
    public val byteCount: Int
    public val effectiveParameters: ScreenCaptureEffectiveParameters
    public val sequence: Long
    public val timestampElapsedRealtimeNanos: Long
    public fun copyTo(destination: ByteArray, destinationOffset: Int = 0): Int
    public fun copyBytes(): ByteArray
}
```

`CaptureGeometry` is the authoritative source geometry of the committed startup/reconciliation. `ImageRect` is
left/top inclusive and right/bottom exclusive in that source. Effective parameters contain the exact immutable
applied parameter snapshot and resolved output geometry; they are observation, not reusable configuration. All
geometry and final-image dimensions are positive where the schema requires them.

Each fresh or repeat output commit receives a positive Session-local frame sequence. It starts at one and fails
the Session terminally before reuse. The timestamp is the elapsed-realtime sample at output commit; equal
timestamps are valid. Cached-first delivery retains the cached output's original sequence and timestamp.

The frame object is borrowed. Every property and copy operation is legal only on the callback thread and until
that callback returns or throws; wrong-thread or post-callback access throws `IllegalStateException`. `copyTo`
validates the complete destination range before copying. Invalid input throws `IndexOutOfBoundsException` and
modifies no destination byte; valid input copies exactly `byteCount` bytes and returns `byteCount`. `copyBytes`
returns one exact caller-owned array. A caller-demand allocation failure propagates normally and does not
corrupt, replace, or retire the engine payload. Only caller-owned copies may outlive the callback.

## 7. Lifecycle, operations, and cancellation

| Operation | Caller-visible contract |
| --- | --- |
| `start` | Starts exactly once with fresh authority. Success returns only after `Active` is visible and does not wait for a source frame, JPEG, or consumer. |
| `updateParameters` | Synchronously validates and durably accepts the newest unequal desired record and revision in nonterminal Running, together with the pause-before-effect obligation under `sessionGate`. The caller path does not construct, assign, or publish `ScreenCaptureState`; Control alone constructs and assigns public `Reconfiguring` before the first outward effect for that accepted change. Return acknowledges desire, not State assignment, Flow delivery, or output convergence. Equality with the current requested value is an absolute no-op. |
| `onFrame` | Registers the one current consumer path and returns its identity-based subscription. Registration is legal before start and while nonterminal Running, creates no capture work, and creates no cached-first offer or handoff until `Active`. |
| `unsubscribe` | Immediately closes new delivery for that registration, then waits for its complete outstanding handoff unless terminal resolution wins. It never stops capture. |
| `stop` | Idempotently fixes owner stop and closes all new public work before returning. Terminal observation and physical cleanup may follow asynchronously. |

`start` and `unsubscribe` are main-safe suspending operations. `updateParameters`, `onFrame`, and `stop` are
thread-safe, synchronous, and nonblocking relative to capture, encoding, callback, and cleanup work. Heavy work
does not run on a caller thread.

The one winning `start` transfers only its supplied projection. A concurrent or repeated loser throws
`IllegalStateException` and does not read, register, stop, retain, or otherwise touch its projection.
Cancellation before start acceptance leaves both Session and projection untouched. Cancellation after
acceptance remains `CancellationException`, requests `Stopped(OwnerStop)`, detaches only that waiter, and lets
already-entered work converge independently.

If `Stopped(OwnerStop)` or `Stopped(CaptureEnded)` wins after acceptance but before `Active`, terminal State is
assigned and `start` throws `ScreenCaptureException(CaptureUnavailable)`. If `Failed(problem)` wins, State is
assigned and `start` throws `ScreenCaptureException(problem)` with the selected cause.

Local parameter validation always precedes update state arbitration. Thus a locally invalid update throws
`IllegalArgumentException` even when the Session would reject a valid update.

| Call and state at linearization | Exact outcome |
| --- | --- |
| winning `start` in `NotStarted` | Accept projection and initial parameters; transition through `Starting`. |
| `start` in `Starting`, any Running state, or terminal | Throw `IllegalStateException`; allocate nothing and touch no losing projection. |
| `stop` in `NotStarted` | Fix `OwnerStop`; terminal requested parameters are ordinary default `ScreenCaptureParameters()` and `lastEffectiveParameters == null`. Start no runtime work. Every later `start` loses without touching its projection. |
| valid `updateParameters` in `NotStarted`, `Starting`, or terminal | Throw `IllegalStateException`; publish no desire and perform no resource work. |
| legal update structurally equal to current requested parameters | Return `Unit`; requested value, revision, Running value, admission, and work remain unchanged, including while reconfiguring. |
| legal unequal update | Under `sessionGate`, replace the newest desired record, reserve its next nonreused revision, and durably accept the pause-before-effect obligation during the call. The caller constructs, assigns, and publishes no State. Control alone constructs and assigns coherent `Reconfiguring(requested,lastEffective,lastKnownGeometry,visibility)` before the first outward effect for that accepted change. Setter return guarantees desire acceptance but not State assignment or collector delivery; intermediate desires may conflate. Revision exhaustion fails terminally with `InternalFailure` before reuse and throws its `ScreenCaptureException`. |
| `onFrame` in `NotStarted`, `Starting`, or nonterminal Running with no unresolved registration | Register one consumer; a paused registration waits for later `Active`. |
| `onFrame` with an unresolved prior registration, or after terminal | Throw `IllegalStateException`; create no handoff or callback work. |
| unresolved `unsubscribe` when `Failed(problem)` wins or is visible | Throw `ScreenCaptureException(problem)`; unresolved residue remains retained and replacement remains forbidden. |
| unresolved `unsubscribe` when `Stopped(...)` wins or is visible | Throw `CancellationException`; unresolved residue remains retained and replacement remains forbidden. |
| already successful `unsubscribe`, before or after terminal | Return `Unit` idempotently. |

Self-unsubscribe from the subscription's entered callback fails fast with `IllegalStateException` without closing
the registration. Cancelling an unsubscribe waiter neither reopens delivery nor fabricates completion; a later
call on the same handle may await the same result. Only successful unsubscribe permits replacement. A terminal
or unsubscribe cutoff that wins before callback entry prevents user-code entry; an entry that won first may
finish under the rules below.

## 8. Delivery, cache, repeat, and callback behavior

At most one callback invocation or unresolved submission for it is outstanding. Callback execution is serial
on an engine-selected thread and thread selection is not configurable. There is no callback-entry or callback-
execution deadline. A callback that has returned releases borrowed-frame authority immediately, but an
unresolved handoff can remain occupied; a new opportunity then increments `byConsumerBusy`. Successful
unsubscribe waits for all applicable sides of that handoff.

An entered callback `Exception` increments `byCallbackFailure`, makes one `FrameConsumer`/`DeliveryProblem`
diagnostic attempt with that exact exception, and leaves the registration active. A callback `Error` or other
direct non-`Exception` throwable is not a delivery drop or fallback; the identical object follows the fatal
boundary and no State, Stats, or diagnostic publication is guaranteed. Internal delivery submission failure is
a Session `InternalFailure`, not a delivery drop.

Pause closes new production, repeat, cached-first, and handoff admission. It does not revoke a handoff whose
admission won first. Such a grandfathered handoff may enter while State is `Reconfiguring` and completes as
draining delivery of its immutable old output and registration generation. It keeps its old effective descriptor
even if callback entry follows a newer `Active`. Reconfiguration neither cancels nor waits for that callback,
and no later callback overlaps it.

After successful registration while `Active`, the current valid cached JPEG is offered immediately when one is
available. Cached-first preserves original bytes, sequence, timestamp, and effective descriptor; performs no
capture, encode, or payload copy; increments neither `framesProduced` nor `framesEncoded`; and is delayed by
neither `MaxFps` nor repeat pacing. A registration created while `NotStarted`, `Starting`, `Reconfiguring`, or
`Suspended` persists but creates no cached-first offer or handoff until `Active`.

Repeat republishes the latest valid immutable JPEG with a new sequence and timestamp and the current committed
effective descriptor. It performs no capture, readback, JPEG, or payload copy; increments `framesProduced` but
not `framesEncoded`; and contributes no encode/readback sample. Fresh output wins a tie. Repeat is a best-effort
maximum-silence target rather than a deadline; `MaxFps` may delay it because `MaxFps` caps every output.

The cache is unavailable during every pause or suspension. Image-, backend-, or topology-affecting change,
untrusted source, fallback, target/output rebuild, or terminal invalidates it. An exactly compatible frame-rate
or repeat-policy-only change may preserve bytes, subject to currentness when `Active` resumes. A healthy capture
target is not replaced solely for freshness. The first source buffer after resume may predate resume; there is no
post-resume freshness guarantee.

## 9. State, terminal priority, and public failures

```kotlin
public sealed interface ScreenCaptureState {
    public object NotStarted : ScreenCaptureState
    public object Starting : ScreenCaptureState
    public sealed interface Running : ScreenCaptureState {
        public val requestedParameters: ScreenCaptureParameters
        public val capturedContentVisible: Boolean?
    }
    public class Active private constructor(
        public val effectiveParameters: ScreenCaptureEffectiveParameters,
        override public val capturedContentVisible: Boolean?,
    ) : Running {
        override public val requestedParameters: ScreenCaptureParameters
            get() = effectiveParameters.appliedParameters
    }
    public class Reconfiguring private constructor(
        override public val requestedParameters: ScreenCaptureParameters,
        public val lastEffectiveParameters: ScreenCaptureEffectiveParameters,
        public val lastKnownCaptureGeometry: CaptureGeometry?,
        override public val capturedContentVisible: Boolean?,
    ) : Running
    public class Suspended private constructor(
        override public val requestedParameters: ScreenCaptureParameters,
        public val problem: ScreenCaptureProblem,
        public val lastEffectiveParameters: ScreenCaptureEffectiveParameters,
        public val lastKnownCaptureGeometry: CaptureGeometry?,
        override public val capturedContentVisible: Boolean?,
    ) : Running
    public class Stopped private constructor(
        public val reason: ScreenCaptureStopReason,
        public val requestedParameters: ScreenCaptureParameters,
        public val lastEffectiveParameters: ScreenCaptureEffectiveParameters?,
    ) : ScreenCaptureState
    public class Failed private constructor(
        public val problem: ScreenCaptureProblem,
        public val requestedParameters: ScreenCaptureParameters,
        public val lastEffectiveParameters: ScreenCaptureEffectiveParameters?,
    ) : ScreenCaptureState
}

public enum class ScreenCaptureStopReason { OwnerStop, CaptureEnded }

public enum class ScreenCaptureProblem {
    InvalidRequest,
    CaptureUnavailable,
    ResourceExhausted,
    InternalFailure,
}

public class ScreenCaptureException private constructor(
    public val problem: ScreenCaptureProblem,
    cause: Throwable? = null,
) : Exception(problem.name, cause)
```

`Active` means a healthy compatible live output is owned; its requested parameters are its effective applied
snapshot. `Reconfiguring` truthfully reports an accepted serialized mutation. `Suspended` reports recoverable
unavailability while retaining the latest desire. Every Running value is one immutable requested/visibility
snapshot. `capturedContentVisible` is only the latest nullable observation.

`lastEffectiveParameters` in `Reconfiguring` and `Suspended` is historical last committed `Active` output, not a
claim of current availability. `lastKnownCaptureGeometry` is likewise historical. Requested parameters may
differ from the historical applied parameters. Terminal values freeze the final requested parameters and last
committed effective parameters, or null when no output was committed.

Terminal contenders are selected once in this exact priority: `CaptureEnded`, then `OwnerStop`, then
`Failed(problem)`. A later or lower-priority contender cannot rewrite the winner. `CaptureEnded` comes only from
the projection's stop callback; owner cleanup cannot fabricate it. Terminal cutoff closes all new public work,
folds already-authoritative accounting, and publishes in §11–12 order. A result that becomes durable only after
terminal transfer is cleanup-only and cannot revise State, Stats, cache, fallback, health, admission, cause, or
diagnostic selection.

| Problem | Meaning |
| --- | --- |
| `InvalidRequest` | Current geometry and parameters cannot produce the requested result. |
| `CaptureUnavailable` | Projection, source, or required metrics are unavailable. |
| `ResourceExhausted` | A clean deterministic capacity or required creation/allocation boundary denied the request. |
| `InternalFailure` | Platform, render/JPEG, ownership, or engine evidence became unsafe or inconsistent. |

`Suspended.problem` is exactly recoverable `InvalidRequest`, `CaptureUnavailable`, or a deterministic
pre-retirement `ResourceExhausted`; startup has no Suspended outcome. During Running, invalid geometry or missing
capture authority may suspend and recover. A deterministic mandatory denial before irreversible retirement
suspends with `ResourceExhausted`. A required current creation/allocation failure after retirement is terminal
`ResourceExhausted`. Startup deterministic denial or required creation/allocation failure is terminal
`ResourceExhausted`. Required raw-pixel-carrier or encoded-storage failure during Running is terminal
`ResourceExhausted`, not optional fallback.

V1 has no automatic Target-mode downgrade or Downscaled-to-Full fallback. A deterministic Target denial or
required Target creation/allocation failure follows the `Suspended` or terminal rules above and does not disable
Downscaled for a later reconciliation. A later revision selects Full or Downscaled only from its current API,
geometry and parameter eligibility.

At the sole virtual-display creation boundary, a null or security denial is `CaptureUnavailable`, a direct
`OutOfMemoryError` is `ResourceExhausted`, and `IllegalStateException` or another unexpected `Exception` is
`InternalFailure`. At documented Framework resource, compression, and entered encoded-storage boundaries,
`OutOfMemoryError` is `ResourceExhausted`; malformed or unexpected non-OOM evidence is `InternalFailure`.
Framework encoder false drops that frame as `byFailure` and keeps Framework available. Partial bytes never
publish.

A safely returned optional Native compressor-allocation failure drops that frame as `byFailure`, disables Native
for later frames, and changes to Framework only after later reconciliation; the same frame is never encoded
twice. A documented required Native OOM result is `ResourceExhausted`. Safely returned optional-path failure
does not change Target or rendering health. Ownership ambiguity, malformed evidence, unsafe nonreturn, or
inconsistent results are `InternalFailure`, even when stale. Ambiguity outranks OOM evidence, and OOM evidence
outranks an ordinary returned result.

A direct fatal throwable is never normalized into an ordinary drop, fallback, or recoverable failure except at
the explicitly documented OOM boundaries above. The identical fatal object reaches the owning fatal boundary;
recovery, physical cleanup, and public observation are best effort only.

Terminal State ends capture authority and all new work but is not a physical-cleanup receipt. Cleanup may retain
unresolved resources until process death. The public API exposes no cleanup-completion handle, and terminal State
has no freshness guarantee. State, Stats, and diagnostic Flow facades remain open for the Session lifetime.

## 10. Stats schema and formulas

```kotlin
public class ScreenCaptureStats private constructor(
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

public class ScreenCaptureFrameDropStats private constructor(
    public val byPipelineBusy: Long,
    public val byStaleWork: Long,
    public val byFailure: Long,
) {
    public val total: Long
        get() = saturatingSum(byPipelineBusy, byStaleWork, byFailure)
}

public class ScreenCaptureDeliveryDropStats private constructor(
    public val byConsumerBusy: Long,
    public val byCallbackFailure: Long,
) {
    public val total: Long
        get() = saturatingSum(byConsumerBusy, byCallbackFailure)
}
```

All fields start at zero. Counters and totals saturate at `Long.MAX_VALUE`. Each total is derived from its listed
components and is never independently updated.

| Field | Exact membership |
| --- | --- |
| `framesEncoded` | Mechanically successful fresh JPEG encodes, including success later suppressed as stale. |
| `framesProduced` | Fresh and repeat output commits, whether or not a consumer exists. Cached-first counts neither. |
| `byPipelineBusy` | A materialized attempt rejected because the sole production capacity changed. Retained unmaterialized pending source is not a drop. |
| `byFailure` | A mechanically returned production failure, even if its identity became stale. |
| `byStaleWork` | Otherwise successful work suppressed solely by stale identity. |
| `byConsumerBusy` | A new delivery opportunity while a prior handoff remains occupied. |
| `byCallbackFailure` | An entered callback `Exception`. |

Terminal retirement of unclassified, unpublished, or transferred work adds no frame drop. Delivery scheduling
failure is a Session failure, not a delivery drop. Producing with no consumer is not a delivery drop.

Encode and readback means include only successful real operations, including success later suppressed as stale.
Repeat and cached-first add no sample. Each eligible duration is nonnegative `endNanos - startNanos` from
nondecreasing elapsed-time samples. Duration and encoded-byte series apply samples in authoritative commit order
to binary64 accumulators:

```text
mean = mean + (sample - mean) / sampleCount
averageEncodeMs = meanEncodeNanos / 1_000_000.0
averageReadbackMs = meanReadbackNanos / 1_000_000.0
```

The division uses IEEE-754 round-to-nearest. A series with zero samples reports `0.0`. Before a successful fresh
encode, `lastEncodedByteCount == 0`; afterwards it is the exact latest accepted byte-size sample from the same
mechanically successful set and cutoff as `framesEncoded`, including success later suppressed as stale. Failure,
repeat, cached-first, and cleanup-only late return add no byte sample.

`averageEncodedByteCount` is zero with no samples; otherwise it is
`min(Int.MAX_VALUE,floor(meanBytes+0.5))`. `averageProducedFps` is zero with fewer than two output commits or a
nonpositive first-to-last interval; otherwise:

```text
(framesProduced - 1) * 1e9 / (lastProductionNanos - firstProductionNanos)
```

The FPS interval includes repeats and elapsed deep sleep or suspension. Public Doubles are always finite:
invalid mean updates retain the last finite mean, nonfinite FPS clamps to the greatest finite `Double`, and
counter saturation freezes the affected derived value. Each such visible protection makes the applicable
`StatsProblem` diagnostic attempt.

Session creation publishes the all-zero Stats snapshot. Accepted start preserves that same value and performs no
Stats assignment or Stats wake. Stats become dirty only when an authoritative public field changes; equal Stats
are not assigned for reconfiguration alone. Ordinary dirty Stats publish no more often than once per 1,000 ms
of engine elapsed time, without catch-up. Scheduling or collector delay creates no delivery-time promise. Final
Stats is assigned immediately before the terminal diagnostic and terminal State. State and Stats are separate
Flows, not one atomic value.

## 11. Diagnostics schema, vocabulary, causes, and order

```kotlin
public class ScreenCaptureDiagnosticEvent private constructor(
    public val sequence: Long,
    public val timestampEpochMillis: Long,
    public val source: String,
    public val label: String,
    public val message: String,
    public val cause: Throwable? = null,
)
```

The diagnostic Flow has `replay=0`, `extraBufferCapacity=128`, and
`BufferOverflow.DROP_OLDEST`. Each required observation makes exactly one best-effort emission attempt.
Diagnostic sequence is Session-local, starts at one, advances per attempt, and fails terminally before reuse.
Overflow, allocation failure, or failed emission may expose gaps. Attempts are sequence-ordered; post-terminal
cleanup attempts continue the same sequence. `timestampEpochMillis` samples wall clock for correlation only;
it may repeat or move and is never ordering, deadline, or control authority.

Message wording is noncontractual but short and semantically identifies the decision or action. Throwable text
is not copied into `message`; the raw cause is a separate reference whose retention is the application's choice.
Sources and labels are extensible strings, not enums; callers must tolerate unknown future values.

V1 engine sources are `Session`, `MetricsSource`, `MediaProjection`, `VirtualDisplay`, `SurfaceTarget`,
`GlPipeline`, `FrameworkJpeg`, `NativeJpeg`, `FrameConsumer`, `Controller`, `ColorPipeline`, and `Cleanup`.

| Label | Required observation and cause |
| --- | --- |
| `CapabilityCheck` | One attempt at each applicable top-level capability selection or failure. Source names the boundary; message names boundary, decision, and action; cause is the exact raw cause when present. Additionally, exactly one normal post-readiness `MetricsSource` completion attempt per observation lifetime has null cause. Pre-readiness completion, source failure, and duplicate terminal delivery add no completion event. |
| `RuntimeProfile` | `Session`, once for initial Running modes; null cause. |
| `RuntimeModeChanged` | `SurfaceTarget`, `FrameworkJpeg`, or `NativeJpeg` only for an actual mode change or fallback; retains a safely returned cause when defined. A merely considered, stale-cleanup, or timeout-only change adds none. |
| `DeliveryProblem` | `FrameConsumer`, once for a callback `Exception` committed before terminal transfer, with that identical throwable. |
| `StatsProblem` | `Controller`, for a finite-value retention, clamp, or freeze; null cause. |
| `ColorAction` | `ColorPipeline`, once for initial classification/action and on each actual classification/action change; null cause. |
| `QuarantineChanged` | `Cleanup`, only when exact unresolved ownership actually attaches, reduces, or is removed; raw cleanup cause when present. |
| `SessionTerminal` | `Session`, once for the winning stop reason/problem and last active modes, after final Stats and before terminal State. Owner/capture stop and pre-Active valid-then-invalid metrics have null cause. Other failure uses the selected cause. A winning timeout reuses the identical cause from its `CapabilityCheck`. |

Timeout capability sources and public classification are fixed: first-positive metrics readiness uses
`MetricsSource` and `CaptureUnavailable`; API-34+ initial-resize readiness uses `MediaProjection` and
`CaptureUnavailable`; active capture/rendering boundaries use their applicable source and `InternalFailure`;
Framework or Native JPEG uses that backend source and `InternalFailure`. A prior higher-priority terminal makes a
later timeout cleanup-only and selects no timeout event. Timeout selects no fallback, retry, mode change,
duration sample, or other public field.

Terminal public observation order is contiguous:

```text
final Stats assignment
-> SessionTerminal sequence reservation and one emission attempt
-> terminal State assignment
-> any later cleanup diagnostic attempts
```

After terminal State assignment, required `Attach` attempts for unresolved Metrics, Capture, Encoder, and
Delivery identities occur in that fixed order. Each identity that actually becomes process-lifetime residue makes exactly one
`Cleanup`/`QuarantineChanged` `Attach` attempt. An ownership result already durable before attachment makes no
event. Each later real matching reduction or removal may make only one `Reduce` or `Remove` attempt; duplicate,
stale-identity, empty, and losing updates make none. These words identify the semantic action in the
noncontractual message; the public label remains `QuarantineChanged`.

Diagnostics are observation only. Collection, loss, delay, overflow, allocation/emission failure, or raw-cause
retention changes no lifecycle, fallback, Stats, ownership, cleanup, or result. Routine geometry, visibility,
rebuild, consumer lifecycle, and frame production require no event.

## 12. Equality, identity, observation, and caller threading

Public API uses ordinary classes, never data classes. Parameters and their value types, metrics, geometry and
rectangles, `ImageSize`, State values, effective parameters, Stats, and drop vectors have manual structural
`equals`/`hashCode` and bounded `toString()` containing the class name and documented scalar, enum, and value
fields. Their representations exclude buffers, bytes, callbacks, sources, observers, and Throwable graphs.

Config, metrics source, metrics observer, metrics subscription, Session, frame subscription, frame, diagnostic
event, and exception retain identity semantics. Function callbacks likewise retain their ordinary identity.

Caller-input constructors, `ScreenCaptureEngine.create`, and State/Stats/diagnostic Flow getters are callable
from any thread and perform only bounded validation or snapshot access. They make no platform capture call and
do not start capture.

State and Stats are latest-value StateFlows. With supported nonblocking, non-reentrant collectors, observation
does not participate in engine progress. A collector must not block the publishing thread or synchronously
reenter an operation that waits on publication. `Main.immediate` is supported for nonblocking, non-reentrant
collection. Unconfined collection is unsupported where it creates reentry or liveness risk. Publication may
resume `N` collectors and therefore is not a constant-time caller oracle.

Private readiness and entered-operation intervals are positive finite policy, not public SLAs. They cannot
change public classification, terminal priority, ownership, support, or runtime selection. The 1,000 ms ordinary
Stats cadence is the only unconditional engine publication cadence; `SampleEvery`, `MaxFps`, and repeat timing
are requested capture/output policies, not unconditional publication schedules.

No synchronous setter-return or collector-resumption oracle exists. An accepted update has durably committed its
newest desired record, revision, and pause-before-effect obligation even if Control has not yet assigned public
`Reconfiguring` or a caller has not observed it. Control alone constructs and assigns that State value.

## 13. Explicit V1 exclusions

- Concurrent Sessions are supported, but the engine gives no process-wide resource or progress guarantee.
- Crop and region selection are not privacy redaction because sampling may mix edge texels.
- There is no same-frame Native-to-Framework retry.
- There is no post-resume source-freshness guarantee and no cleanup-completion API.
- Managed reclamation, allocator/driver bytes, and physical release after logical reference drop are outside
  product observation.
- Future HDR mapping, linear-light grayscale, general early-target planning, video, and audio are outside V1.
