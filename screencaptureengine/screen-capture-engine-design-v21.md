# Screen Capture Engine Design v21

> Public working name: `ScreenCaptureEngine`. The name is product-facing: the engine captures user-approved screen or app-window content through Android `MediaProjection`, renders a selected view, encodes it through an `ImageEncoderProvider`, and publishes latest-only encoded image frames. `MediaProjectionEngine` is intentionally not used as the public root name because it sounds like a thin platform wrapper rather than the complete capture/render/readback/encode/publication pipeline. MJPEG is an integration/transport pattern built on top of frame callbacks, not the core engine identity.
>
> Revision v21 keeps the Kotlin-only public API and session-level callback dispatcher, but tightens delivery lifecycle semantics so `stop()`/`close()` remain fast-bounded without pretending the engine can observe the first user-lambda instruction. Public snapshot preparation is asynchronous session-owned coordinator work, producer-critical contexts only publish the internal latest frame and signal a conflated delivery sequence, and `AdmittedRunning` is the formal callback-start boundary.

## Table of Contents

- [Goal and Non-Goals](#goal-and-non-goals)
- [Public API](#public-api)
- [Encoder Provider API](#encoder-provider-api)
- [Lifecycle and Threading](#lifecycle-and-threading)
- [MediaProjection, Foreground Service, and VirtualDisplay](#mediaprojection-foreground-service-and-virtualdisplay)
- [Capture Geometry and Output Planning](#capture-geometry-and-output-planning)
- [Scheduling, Publication, and Backpressure](#scheduling-publication-and-backpressure)
- [Rendering, Readback, and Encoding](#rendering-readback-and-encoding)
- [Memory, Privacy, and Cleanup](#memory-privacy-and-cleanup)
- [Failure, Fallback, and Diagnostics](#failure-fallback-and-diagnostics)
- [Test Matrix](#test-matrix)
- [Implementation Tunables](#implementation-tunables)
- [Integration Notes](#integration-notes)
- [Official References Reviewed](#official-references-reviewed)

## Goal and Non-Goals

`ScreenCaptureEngine` is a Kotlin-only Android library that produces latest-only encoded still-image frames from a user-approved Android `MediaProjection` session.

The required built-in encoder is JPEG. JPEG is exposed through a public `ImageEncoderProvider`, so capture, geometry, rendering, scheduling, publication, and consumer contracts are not tied to MJPEG transport or one concrete JPEG implementation. The engine core treats provider output as opaque encoded image bytes. Built-in PNG, WebP, video encoders, GPU image encoders, third-party codec bundles, and network streaming are not required by this revision.

Supported platform range is Android API 24 through Android 17/API 37. API level values used by `minSdk`, `compileSdk`, and `targetSdk` are integer Android platform API levels. Minor SDK versions are handled only through runtime checks where needed.

The engine must:

- capture full-display or user-selected app-window content approved by the user;
- support full captured content when requested by product settings;
- make the default preview/transport case cheap by applying capture-target downscale as early as possible;
- avoid enlarging the Android projection target above logical captured content only to support final-output upscale;
- apply source selection, crop, rotation, mirror, output sizing, grayscale, frame pacing, and image encoding;
- publish only the latest encoded frame;
- avoid raw frame queues and consumer-driven producer blocking;
- keep lifecycle boundaries safe for sensitive captured data;
- expose enough state, stats, and diagnostics for integration without making events part of control flow;
- keep encoder details isolated behind a small synchronous provider contract.

Non-goals:

- HTTP/server transport, MJPEG multipart framing, authentication, storage, or access-control policy;
- bypassing Android secure-window, projection-consent, foreground-service, or privacy behavior;
- redaction-grade crop, masking, or pixel-exact exclusion guarantees for pixels outside selected boundaries;
- asynchronous app-provided encoders that retain raw input after `encode(...)` returns;
- Java interoperability, generated overloads, or compatibility shims as public API goals.

## Public API

The public API is Kotlin-only. It uses `StateFlow`, `SharedFlow`, and `CoroutineDispatcher` from `kotlinx-coroutines-core`; it does not expose `java.util.concurrent.Executor` for frame delivery.

Public snippets assume Kotlin imports for Android platform classes and kotlinx.coroutines types. The design does not require Java compatibility overloads or Java-specific helper APIs.

```kotlin
interface ScreenCaptureEngine {
    suspend fun startSession(
        config: ScreenCaptureConfig,
        mediaProjection: MediaProjection,
        initialParameters: ScreenCaptureParameters = ScreenCaptureParameters.defaults()
    ): ScreenCaptureSession
}

class ScreenCaptureConfig {
    val metricsProvider: CaptureMetricsProvider
    val publishedSnapshotSlotCount: Int
    val slowConsumerThreshold: Int
    val maxOutputPixels: Int
    val maxEncodedBytes: Int
    val frameCallbackDispatcher: CoroutineDispatcher?
}

interface CaptureMetricsProvider {
    val metrics: StateFlow<CaptureMetrics>
}

class CaptureMetrics {
    val widthPx: Int
    val heightPx: Int
    val densityDpi: Int
}

object CaptureMetricsProviders {
    fun fromActivity(activity: Activity): CaptureMetricsProvider
    fun fromUiContext(context: Context): CaptureMetricsProvider
    fun fromDisplay(baseContext: Context, display: Display): CaptureMetricsProvider
    fun bestEffort(context: Context): CaptureMetricsProvider
}

interface ScreenCaptureSession {
    suspend fun setParameters(parameters: ScreenCaptureParameters): ScreenCaptureParameterUpdateResult
    fun trimMemory(level: Int)
    fun stop()
    fun close()

    val state: StateFlow<ScreenCaptureSessionState>
    val stats: StateFlow<ScreenCaptureStats>
    val events: SharedFlow<ScreenCaptureEvent>

    fun onFrame(
        callback: (EncodedImageFrame) -> Unit
    ): FrameSubscription
}

interface FrameSubscription {
    fun cancel()
}

class ScreenCaptureStartException(
    val requiresFreshProjection: Boolean,
    val problem: ScreenCaptureProblem
) : Exception(problem.message, problem.cause)
```

`startSession(...)` and `setParameters(...)` are suspending because they can wait for platform callbacks, GL work, readback resource preparation, and encoder resource preparation. They are main-safe from the caller perspective and must not block the caller thread. Calls from engine-owned contexts fail fast with `IllegalStateException` to avoid deadlocks.

`onFrame(callback)` registers a frame callback using the session-level callback execution dispatcher selected by `config.frameCallbackDispatcher`; when that config value is null, the engine uses its own bounded callback dispatcher. All deliveries pass through the engine-owned frame-delivery coordinator first; the configured callback dispatcher is only the final callback execution hop. The callback itself is intentionally non-suspending. A borrowed `EncodedImageFrame` is valid only during the admitted synchronous callback invocation. Consumers that need a different execution context, retention, or suspending work must copy first, then launch their own coroutine work outside the callback.

`FrameSubscription.cancel()` is thread-safe and idempotent. It prevents future deliveries but does not interrupt a callback invocation that has already been admitted to run.

Configuration defaults:

| Config value | Default | Meaning |
| --- | ---: | --- |
| `publishedSnapshotSlotCount` | `4` | Maximum immutable public delivery snapshot slots retained for frame callbacks. Excludes the single internal latest encoded frame. |
| `slowConsumerThreshold` | `2` | Consecutive direct per-subscription delivery-problem threshold for slow/failing classification. |
| `maxOutputPixels` | `2_073_600` | Default maximum final output pixels. This is a published-output cap, not a logical captured-content cap. |
| `maxEncodedBytes` | `8 * 1024 * 1024` | Hard cap per internal latest encoded frame and per immutable public delivery snapshot slot. |
| `frameCallbackDispatcher` | `null` | Session-level dispatcher for public callback bodies. `null` uses the engine-owned bounded callback dispatcher. |

Configuration construction validation:

| Value | Accepted range |
| --- | --- |
| `publishedSnapshotSlotCount` | integer `1..16` |
| `slowConsumerThreshold` | integer `1..1024` |
| `maxOutputPixels` | integer `1..268_435_456` |
| `maxEncodedBytes` | integer `1_024..268_435_456` |

The configuration sanity limits are public construction limits, not allocation guarantees. Runtime planning still decides whether a specific output plan can run on a specific device.

### Public Models

```kotlin
class ScreenCaptureParameters {
    val sourceRegion: SourceRegion
    val crop: CropInsetsPx
    val outputSize: OutputSize
    val rotation: Rotation
    val mirror: Mirror
    val colorMode: ColorMode
    val frameRate: FrameRate
    val encoderProvider: ImageEncoderProvider
}

sealed interface OutputSize {
    class ScaleFactor(val factor: Double) : OutputSize
    class TargetSize(
        val width: Int,
        val height: Int,
        val contentMode: ContentMode
    ) : OutputSize
}

sealed interface FrameRate {
    class MaxFps(val fps: Int) : FrameRate
    class PeriodicRefresh(val intervalMillis: Long) : FrameRate
    object Auto : FrameRate
}

sealed interface ScreenCaptureParameterUpdateResult {
    object Applied : ScreenCaptureParameterUpdateResult
    class Rejected(val problem: ScreenCaptureProblem) : ScreenCaptureParameterUpdateResult
}

sealed interface ScreenCaptureSessionState {
    class Running(
        val output: ScreenCaptureOutputState,
        val capturedContentVisible: Boolean?
    ) : ScreenCaptureSessionState

    class Stopped(
        val reason: ScreenCaptureStopReason,
        val problem: ScreenCaptureProblem?
    ) : ScreenCaptureSessionState

    class Failed(
        val problem: ScreenCaptureProblem
    ) : ScreenCaptureSessionState
}

sealed interface ScreenCaptureOutputState {
    class Active(
        val effectiveParameters: ScreenCaptureEffectiveParameters
    ) : ScreenCaptureOutputState

    class Suspended(
        val problem: ScreenCaptureProblem,
        val previousEffectiveParameters: ScreenCaptureEffectiveParameters,
        val currentCaptureGeometry: CaptureGeometry
    ) : ScreenCaptureOutputState
}

class ScreenCaptureProblem {
    val sequence: Long
    val kind: ScreenCaptureProblemKind
    val message: String?
    val cause: Throwable?
}

enum class ScreenCaptureProblemKind {
    ProjectionInvalidOrStopped,
    ProjectionPermissionDenied,
    ProjectionSessionReused,
    VirtualDisplayCreateFailed,
    StartupGeometryUnavailable,

    MetricsUnavailableOrInvalid,
    OutputPlanInvalid,
    OutputLimitsExceeded,

    SurfaceCreateOrResizeFailed,
    GlInitializationFailed,
    GlResourceFailure,
    GlInvariantViolation,

    ReadbackUnavailable,
    ReadbackRepeatedFailure,
    PboValidationOrInvariantFailure,

    EncoderUnavailable,
    EncoderValidationFailed,
    EncodeRepeatedFailure,
    EncodedSizeLimitExceeded,

    AllocationFailed,
    MemoryPressure,

    FrameDeliveryFailed,
    FrameCallbackThrew,

    InternalInvariantViolation
}

enum class ScreenCaptureStopReason {
    OwnerStop,
    CaptureEnded
}
```

`ScreenCaptureProblemKind` is a stable technical classification. It does not define severity by itself. Severity and remediation are determined by context:

- `ScreenCaptureStartException`: session was not created;
- `ScreenCaptureParameterUpdateResult.Rejected`: requested parameters were not applied and the previous active plan remains in use;
- `ScreenCaptureOutputState.Suspended`: the session is alive but no new frames are published while the active plan is unavailable;
- `ScreenCaptureSessionState.Failed`: terminal engine/session failure;
- `events`: diagnostic only.

`ProjectionPermissionDenied` and `ProjectionSessionReused` remain projection-specific because they map to different owner remediation: fixing consent/FGS/permission flow versus obtaining a fresh projection session.

`StartupGeometryUnavailable` means the engine could not obtain trustworthy initial capture geometry required for startup. On API 34+ this normally means the first valid `onCapturedContentResize(width, height)` did not arrive within `3_000 ms` after the first `VirtualDisplay` was created, unless projection stop was observed first or in the same serialized control turn.

Runtime model values:

```text
SourceRegion = Full | LeftHalf | RightHalf
ContentMode = Stretch | AspectFit
Rotation = Degrees0 | Degrees90 | Degrees180 | Degrees270
Mirror = None | Horizontal | Vertical
ColorMode = Original | Grayscale
FrameRate = MaxFps(fps) | PeriodicRefresh(intervalMillis) | Auto
ReadbackMode = Es2 | Es3Pbo
CaptureGeometry.source = MetricsProvider | MetricsProviderProvisional | CapturedContentResize
```

### Frames, Effective Parameters, and Stats

```kotlin
interface EncodedImageFrame {
    val format: EncodedImageFormat
    val byteCount: Int
    val sequence: Long
    val timestampElapsedRealtimeNanos: Long

    fun copyTo(destination: ByteArray, destinationOffset: Int = 0): Int
    fun copyBytes(): ByteArray
}

class ScreenCaptureEffectiveParameters {
    val captureGeometry: CaptureGeometry
    val captureTarget: CaptureTarget
    val sourceRegion: SourceRegion
    val crop: CropInsetsPx
    val appliedSourceRect: ImageRect
    val orientedContentSize: Size
    val outputSize: OutputSize
    val finalImageSize: Size
    val rotation: Rotation
    val mirror: Mirror
    val colorMode: ColorMode
    val readbackMode: ReadbackMode
    val encoderInfo: ImageEncoderInfo
    val frameRate: FrameRate
}

class CaptureTarget {
    val width: Int
    val height: Int
    val scaleFromLogicalCapture: Double
    val isEarlyDownscaled: Boolean
}

class ScreenCaptureStats {
    val framesEncoded: Long
    val framesPublished: Long
    val droppedFrames: ScreenCaptureFrameDropStats
    val droppedDeliveries: ScreenCaptureDeliveryDropStats
    val publishedFps: Double
    val averageEncodeMs: Double
    val averageReadbackMs: Double
    val lastEncodedByteCount: Int
    val averageEncodedByteCount: Int
    val activeFrameSubscriptions: Int
    val slowConsumers: Int
}

class ScreenCaptureFrameDropStats {
    val total: Long
    val byFrameRatePolicy: Long
    val byReadbackBusy: Long
    val byEncoderBusy: Long
    val byOutputSuspended: Long
    val byStaleGeneration: Long
    val byEncodedSizeLimit: Long
    val byTransientFailure: Long
}

class ScreenCaptureDeliveryDropStats {
    val total: Long
    val bySubscriptionBusy: Long
    val byDispatchFailed: Long
    val byCallbackThrew: Long
    val bySnapshotSlotsExhausted: Long
    val byStaleSession: Long
}

class ScreenCaptureEvent {
    val sequence: Long
    val timestampElapsedRealtimeNanos: Long
    val type: ScreenCaptureEventType
    val problem: ScreenCaptureProblem?
    val message: String?
}

enum class ScreenCaptureEventType {
    SessionStarted,
    SessionStopped,
    CaptureGeometryChanged,
    CaptureTargetChanged,
    InvalidMetricsIgnored,
    OutputPlanApplied,
    OutputPlanRejected,
    OutputPlanSuspended,
    OutputPlanResumed,
    ReadbackModeChanged,
    EncoderChanged,
    EncodedFrameDropped,
    SlowConsumerPressure,
    FrameDeliveryFailure,
    MemoryTrimmed
}
```

Stats definitions:

- `framesEncoded` counts successful encoder outputs before stale-generation and size-cap publication checks.
- `framesPublished` counts successful replacements of the internal latest encoded frame.
- `droppedFrames` counts production opportunities or completed encoded results that did not become the internal latest frame.
- `droppedDeliveries` counts materialized per-subscription delivery/drop decisions that did not complete successfully, including direct per-subscription drop decisions, pre-admission skips, and callback-threw failures. Internal publication signals and public-snapshot preparation retired before any per-subscription decision exists are not delivery drops. `droppedDeliveries` never increments `droppedFrames`.
- Drop `total` fields are exactly the sum of all listed category fields.
- Each dropped production opportunity or counted dropped delivery is counted in exactly one public drop category.
- `publishedFps` is based on `framesPublished` over session lifetime using monotonic elapsed time.
- `timestampElapsedRealtimeNanos` is publication time and is the public ordering timestamp.
- `EncodedImageFrame.sequence` increments per published encoded image frame, not per visual content change. `FrameRate.PeriodicRefresh` may publish a frame equivalent to the previous published frame.
- `slowConsumers` counts active subscriptions currently classified as slow/failing by the public policy in [Delivery Drop Categories and Slow Consumer Classification](#delivery-drop-categories-and-slow-consumer-classification). It is not merely the instantaneous count of callbacks currently running.

`EncodedImageFrame` is a borrowed encoded byte view. It is not a raw frame, not an MJPEG multipart item, not a transport packet, and not valid after callback return.

## Encoder Provider API

Encoding is selected through `ScreenCaptureParameters.encoderProvider`. The provider SPI is public and synchronous. It is public because encoder choice and JPEG quality are product behavior; it is synchronous so that raw input lifetime is bounded and not leaked into arbitrary app-owned asynchronous work.

```kotlin
interface ImageEncoderProvider {
    val id: String
    val outputFormat: EncodedImageFormat

    fun createEncoder(request: ImageEncoderRequest): ImageEncoder
}

interface ImageEncoder {
    val info: ImageEncoderInfo

    fun encode(
        input: ImageEncoderInput,
        output: EncodedImageSink
    ): ImageEncodeResult

    fun close()
}

class ImageEncoderRequest {
    val width: Int
    val height: Int
    val rowStrideBytes: Int
    val maxEncodedBytes: Int
    val inputFormat: ImageEncoderInputFormat
}

interface ImageEncoderInput {
    val width: Int
    val height: Int
    val rowStrideBytes: Int
    val buffer: ByteBuffer
    val format: ImageEncoderInputFormat
}

interface EncodedImageSink {
    val byteCount: Int
    val maxByteCount: Int
    fun write(source: ByteArray, offset: Int, byteCount: Int): Boolean
    fun write(source: ByteBuffer, byteCount: Int): Boolean
}

sealed interface ImageEncodeResult {
    object Success : ImageEncodeResult
    class Failed(val message: String?, val cause: Throwable?) : ImageEncodeResult
}

class ImageEncoderInfo {
    val providerId: String
    val outputFormat: EncodedImageFormat
    val backendName: String?
}

class EncodedImageFormat {
    val name: String
    val mimeType: String
}

object EncodedImageFormats {
    val Jpeg: EncodedImageFormat
}

enum class ImageEncoderInputFormat {
    Rgba8888SrgbOpaque
}

class ImageEncoderUnavailableException(
    message: String?,
    cause: Throwable?
) : Exception(message, cause)
```

Provider rules:

- `ImageEncoderProvider` instances are lightweight, immutable, and thread-safe.
- The engine may call `createEncoder(...)` during startup and during `setParameters(...)` when output dimensions, input stride, max encoded size, or provider settings change.
- `ImageEncoder` instances are session/plan scoped and are used only on the engine encoder context.
- `encode(...)` is synchronous. It must fully consume the borrowed `ImageEncoderInput` before returning.
- Providers must not retain `ImageEncoderInput`, `ByteBuffer`, raw pointers, row data, or `EncodedImageSink` after `encode(...)` returns.
- Providers must not call engine public APIs from `encode(...)`.
- Providers must not require or touch the engine GL context.
- Providers must write only through `EncodedImageSink` and must respect `maxEncodedBytes`. A `false` return from sink write means the encoded output would exceed the configured cap; the provider should stop and return `Failed(...)`.
- Provider exceptions are caught by the engine and mapped to encoder problem kinds. Repeated failures are handled by fallback/terminal policy.
- A blocked or stuck provider must not block `stop()` or `close()` from returning. The engine may retire blocked encoder/PBO resources and mark the backend or provider unhealthy for the current session.

`ImageEncoderInput` is raw RGBA and is sensitive. It is exposed only to encoder providers, not to normal frame consumers. Normal consumers receive only borrowed encoded bytes through `EncodedImageFrame`.

### Default JPEG Provider

```kotlin
class JpegImageEncoderProvider(
    val quality: Int = 80,
    val backendPolicy: JpegEncoderBackendPolicy = JpegEncoderBackendPolicy.Auto
) : ImageEncoderProvider

enum class JpegEncoderBackendPolicy {
    Auto,
    FrameworkOnly
}

enum class JpegEncoderBackend {
    FrameworkBitmapCompress,
    NdkAndroidBitmapCompress
}
```

JPEG provider validation:

| Value | Accepted range |
| --- | --- |
| `quality` | integer `0..100` |
| `backendPolicy` | declared values only |

`JpegImageEncoderProvider.outputFormat` is `EncodedImageFormats.Jpeg`, with MIME type `image/jpeg`. It forces opaque alpha. Source alpha is not preserved. Output is treated as opaque 8-bit SDR/sRGB JPEG.

The required built-in implementation scope includes only `JpegImageEncoderProvider`. App-provided providers may output other single-image byte formats by declaring a stable `EncodedImageFormat`, but the engine does not parse, inspect, transcode, or guarantee interoperability for those bytes.

## Lifecycle and Threading

### Lifecycle Contract

- `startSession(...)` requires a fresh, active, not-stopped `MediaProjection` that has not already been used to create a `VirtualDisplay`.
- `startSession(...)` returns only after the projection session is owned, core projection/GL/encoder resources are initialized, the initial output plan is active, and initial `Running(output = Active(...))` state is published; otherwise it throws `ScreenCaptureStartException`.
- Initial `Running.output` is always `Active`; the engine does not start an initially suspended session.
- On API 34+, no Active output is planned from provisional width/height. Startup waits for the first valid `onCapturedContentResize(width, height)` and fails after the normative `3_000 ms` startup deadline if it does not arrive.
- A `ScreenCaptureSession` creates at most one `VirtualDisplay`. It may resize the virtual display and replace the target `Surface`, but it does not create a second virtual display for the same projection session.
- A stopped or failed session cannot be restarted. Fresh user consent/projection creates a new session.
- `setParameters(...)` is thread-safe, suspending, serialized, and atomic. It is implemented as a prepare/validate/commit/rollback transaction. It returns `Applied` only when the transaction commits before any terminal transition wins.
- `setParameters(...)` returns `Rejected(problem)` when requested parameters cannot produce a valid plan for current geometry or configured caps, or when a terminal transition wins before commit. The previous active plan remains in use unless the session has become terminal.
- If a later geometry change makes active parameters impossible, the session enters `Running(output = Suspended(...))`, stops new frame publication, retires old latest output for new deliveries, keeps projection alive, and resumes automatically when a later geometry or parameter update produces a valid plan.
- `stop()` and `close()` are fast, thread-safe, idempotent, and terminal. They close the session gate synchronously, invalidate subscriptions, logically retire reachable not-admitted delivery records and pre-delivery public-snapshot records, and schedule heavy resource cleanup on engine-owned contexts.
- User/system projection stop publishes `Stopped(CaptureEnded, problem)` and does not publish `Failed`.
- Fatal engine/platform errors publish `Failed(problem)`.
- `state`, `stats`, and `events` remain readable after terminal state.

### Coroutine Cancellation

- Cancellation of the caller coroutine before `startSession(...)` returns requests startup abandonment. If projection ownership or `createVirtualDisplay(...)` has already happened, the engine releases or retires resources and the owner must assume a fresh projection is required for retry.
- Cancellation of the caller coroutine while `setParameters(...)` is in flight does not leave a partially applied public plan. The engine either commits the transaction atomically, rolls it back, or reaches terminal state. `state.value` remains authoritative.
- Caller cancellation may cause the suspending `setParameters(...)` call itself to throw cancellation, but it does not create an intermediate public output state. A committed plan remains committed; an uncommitted transaction is rolled back or retired by terminal cleanup.
- `FrameSubscription.cancel()` is independent from coroutine cancellation and controls only future delivery for that subscription.
- The engine does not accept a caller `CoroutineScope` or caller `Job` for frame delivery lifetime. Session and subscription lifetime are explicit.

### Parameter Update Transactions

Runtime parameter changes are explicit transactions because later implementation phases create and reconfigure resources such as `VirtualDisplay` size, projection `Surface`, `SurfaceTexture`, GL framebuffers/PBOs, encoder instances, and snapshot buffers.

A parameter update has four conceptual phases:

```text
prepare   -> create candidate plan/resources in a transaction-owned resource bag
validate  -> check geometry, caps, generations, backend availability, and terminal gate
commit    -> atomically swap public effective plan/resources and publish state
rollback  -> retire candidate resources without changing the public effective plan
```

Normative transaction rules:

- Candidate resources created during `prepare` are not visible through `state`, `stats`, frame callbacks, or effective parameters until `commit` succeeds.
- Candidate resources never publish frames before commit.
- `commit` runs on the serialized control context and rechecks terminal state immediately before swapping the effective plan.
- If commit wins before `stop()`, `close()`, projection stop, or fatal failure, `setParameters(...)` returns `Applied`, even if a terminal transition happens immediately after commit.
- If a terminal transition wins before commit, `setParameters(...)` returns `Rejected(problem)` when the caller is still active. Owner `stop()`/`close()` maps to a stopped-session problem; projection stop maps to the projection-stop problem; fatal failure maps to the terminal failure problem.
- If validation fails before commit, `setParameters(...)` returns `Rejected(problem)` and the previous active plan remains in use.
- The transaction owns every resource it prepares until commit. After commit, the session owns the new active resources and retires old resources. If rollback or terminal transition wins before commit, the transaction resource bag is retired by rollback or terminal cleanup.
- `stop()` and `close()` may briefly synchronize with the control context to close the terminal gate and steal active transaction bags, but they do not wait for heavy GL/encoder/platform resource destruction.

This transaction model is normative even if an early implementation phase prepares only pure plans and no platform resources.

### Stop/Close Boundary and Asynchronous Cleanup

`stop()` and `close()` are immediate publication/frame-delivery boundaries, not synchronous heavy-resource destruction barriers.

The design distinguishes five boundaries that must not be collapsed:

1. a frame has become the internal latest encoded frame;
2. public delivery preparation has been signaled to the delivery coordinator;
3. an immutable public snapshot is being prepared or has become ready;
4. a per-subscription `DeliveryRecord` has been materialized and handed off; and
5. the callback invocation has passed the final callback-entry gate and transitioned to `AdmittedRunning`.

`AdmittedRunning` is the formal callback-start boundary for this design. A callback invocation is considered already started for lifecycle purposes when its `DeliveryRecord` atomically passes the final session/subscription/generation callback-entry gate and becomes `AdmittedRunning`. The implementation must invoke the user callback immediately after that transition, without additional dispatch, suspension, large copy, blocking allocation, or queueing. The first user-lambda instruction may be reached shortly after `stop()`/`close()` returns if the invocation was admitted before the terminal boundary. That invocation is not considered a new callback after stop.

After `stop()` or `close()` returns:

- no new render, encode, publication, public snapshot preparation, or per-subscription delivery work is accepted for the session;
- no not-yet-admitted frame callback invocation is admitted;
- no new `EncodedImageFrame` lease is issued to not-yet-admitted deliveries;
- the internal latest encoded frame is unavailable for new delivery;
- frame subscriptions are invalidated;
- pending public-snapshot-preparation records and not-admitted delivery records are stale;
- public state is terminal or already terminal.

Queued and prepared delivery cleanup rules:

- A callback invocation admitted before terminal transition may finish with its already selected immutable snapshot. Its borrowed frame remains valid only until that callback returns.
- The callback-entry gate is not held across user callback execution. Holding it across the callback would allow slow, stuck, or reentrant user code to block `stop()`/`close()`, which is outside the lifecycle contract.
- Any work that may allocate, reserve, copy, or retain bytes for public frame delivery must be represented by a session-owned delivery or pre-delivery record before that work starts.
- Pending public-snapshot preparation that has not started copying is invalidated and retired before `stop()`/`close()` returns.
- A public snapshot copy already in progress when terminal transition wins is logically retired before `stop()`/`close()` returns. The physical byte copy may finish after return as internal stale cleanup, but it must not publish, create public callback leases, materialize per-subscription delivery records, invoke callbacks, or become visible through latest delivery. The copy task releases or discards its bytes in `finally`.
- Delivery tasks queued in the engine-owned frame-delivery coordinator but not admitted are marked stale and their retained snapshot leases are released or logically retired before `stop()`/`close()` returns when those leases are reachable from the session delivery registry.
- Delivery tasks already handed off to the configured callback dispatcher but not admitted are controlled by an atomic delivery token. `stop()`/`close()` invalidates the token and releases or logically retires any retained snapshot lease before return when the lease is reachable from the session delivery registry. If the dispatcher later runs the task, the task observes the stale token and exits without admitting or invoking the callback.
- A handed-off task must not be the only owner of a not-admitted public snapshot lease. Not-admitted leases remain reachable from session-owned delivery bookkeeping until they are admitted or retired.
- Active admitted callback leases are not forcibly revoked or reused, because borrowed frame validity extends through the admitted synchronous callback invocation. They are released when the callback returns or throws.
- Internal stale cleanup may continue after `stop()`/`close()` if it only releases or retires resources, completes already-determined accounting, or drains engine-owned cleanup tasks. It must not invoke public frame callbacks, publish frames, accept new work, or emit lifecycle-control-significant events.

If a callback invocation was admitted before terminal transition and later throws after `stop()`/`close()` returns, `droppedDeliveries.byCallbackThrew` may still increment and the lease must be released. New post-terminal `FrameDeliveryFailure` events are suppressed by default unless the event was already enqueued before the terminal boundary. `stats` remains the authoritative accounting surface.

Heavy resources are released or retired asynchronously on engine-owned contexts, including `VirtualDisplay`, projection `Surface`, `SurfaceTexture`, EGL/GL objects, PBOs, reusable Bitmaps, encoder scratch storage, public snapshot copy scratch storage, and oversized snapshot slots. Retired resources must not be reused for new work. Resources blocked by active frame, encoder, or PBO/readback leases are released after the lease completes or after the resource can be safely abandoned without reuse. Exact zeroization of all platform/native allocations is not a public guarantee.

### Threading Contract

Internal contexts:

- control context: lifecycle, parameters, projection events, capture geometry, output planning, adapter commands;
- GL thread: EGL, `SurfaceTexture`, shaders, framebuffer, readback;
- encoder context: synchronous image encoding work and internal-latest publication;
- frame-delivery coordinator: pending-publication conflation, asynchronous public snapshot preparation, session-owned pre-delivery records, subscription bookkeeping, snapshot-slot leasing, delivery state, dispatcher handoff, watchdogs, and drop accounting;
- frame-delivery callback dispatcher: invokes public callbacks when `config.frameCallbackDispatcher` is null.

Rules:

- GL calls run only on the GL thread.
- Encoder provider code does not own a GL context and must not call GL APIs.
- Normal consumers never access raw RGBA buffers.
- `startSession(...)` and `setParameters(...)` are safe to call from the main/UI coroutine context because they suspend instead of blocking the caller thread.
- Public calls from engine-owned contexts fail fast with `IllegalStateException` where needed to avoid deadlocks.
- `stop()`, `close()`, `trimMemory(level)`, and `FrameSubscription.cancel()` are non-blocking or fast-bounded and safe from the main/UI thread.
- Public frame callbacks are never invoked on the control context, GL thread, encoder context, `MediaProjection` callback handler, or producer-critical publication path.
- Encoded-frame publication replaces only the internal latest frame and signals engine-owned delivery coordination through a conflated sequence. Public snapshot preparation and per-subscription materialization are coordinator work, not producer-critical work.
- All materialized delivery attempts first enter engine-owned delivery coordination. The optional `config.frameCallbackDispatcher` is a session-level final callback hop, not the owner of engine delivery accounting.
- All frame subscriptions in a session use the same callback execution dispatcher. Consumers that need per-consumer execution contexts must copy the frame inside the callback and hop to their own context outside the engine.
- If `config.frameCallbackDispatcher` can run tasks immediately, such as an immediate dispatcher, the callback may run without an extra thread hop. This remains outside producer-critical contexts, but the owner controls callback latency behavior for the whole session.
- Caller-owned bounded, closing, or executor-backed config dispatchers are allowed but not recommended for integrations that need precise dispatch-failure attribution. The engine provides strict admission accounting only for its own bounded callback dispatcher; caller-owned dispatcher failure classification is best-effort before `AdmittedRunning`.

### Diagnostic Event Contract

`events` is read-only best-effort diagnostics for logging, telemetry, and debug UI.

- `state` is authoritative for lifecycle and current output status.
- `stats` is authoritative for counters, rates, byte sizes, and consumer pressure.
- `setParameters(...)` return value is authoritative for parameter-update acknowledgement.
- `onFrame(...)` is authoritative for encoded byte delivery.
- `events` must not be required for correct lifecycle control, parameter-update acknowledgement, cleanup completion, or frame consumption.
- Event emission never blocks control, GL, readback, encoding, latest-frame replacement, or frame delivery.
- Uncollected events may be dropped. Repeated recoverable events may be coalesced or rate-limited according to the normative internal defaults in [Implementation Tunables](#implementation-tunables).
- New diagnostic events after terminal state are suppressed unless they were already enqueued before terminal transition. Final counters and state remain readable through `stats` and `state`.
- `message` is diagnostic text and not a stable parsing contract.

### Frame Delivery Contract

Frame callbacks are session-scoped in-process latest-only delivery.

- `onFrame(callback)` registers a callback for this session only and returns a `FrameSubscription`.
- Callback invocations use `config.frameCallbackDispatcher` when non-null; otherwise they use the engine-owned bounded frame-delivery callback dispatcher.
- There is no per-subscription dispatcher override. All subscriptions in the session share the same final callback execution dispatcher.
- Engine-owned delivery coordination controls public snapshot preparation, snapshot leasing, in-flight state, callback-entry admission, watchdogs, and accounting before any callback dispatcher is used.
- Frame subscriptions are automatically invalidated by `stop()`, projection stop, `Failed`, `close()`, or their own `FrameSubscription.cancel()`.
- No frame subscription survives into another `MediaProjection` session.
- Each subscription has at most one scheduled, handed-off, or `AdmittedRunning` delivery. If a newer frame is published while that subscription is busy, delivery for that newer publication is skipped for that subscription and `droppedDeliveries.bySubscriptionBusy` increments.
- The engine does not guarantee total ordering or fairness across different subscriptions.
- Callbacks for different subscriptions may run concurrently when the configured callback dispatcher allows concurrency.
- A slow subscription does not block GL, readback, encoding, internal latest-frame replacement, or frame delivery to other subscriptions except through bounded snapshot-slot pressure and shared callback-dispatcher capacity.
- If no immutable public delivery snapshot slot can be retained because all snapshot slots are leased, delivery attempts for affected eligible subscriptions are skipped and `droppedDeliveries.bySnapshotSlotsExhausted` increments. This is global public-snapshot-pool pressure; it does not by itself classify the affected subscription as slow.
- If the engine-owned bounded callback dispatcher cannot accept a task before callback admission, that delivery is skipped and `droppedDeliveries.byDispatchFailed` increments.
- If a configured caller-owned dispatcher appears to reject, cancel, or fail delivery before callback admission, the engine classifies the observed pre-admission failure as `byDispatchFailed` when the session is still non-terminal. This classification is best-effort because arbitrary `CoroutineDispatcher` implementations may transform rejection into cancellation or cleanup execution on another dispatcher.
- A non-throwing dispatch/handoff call is not a successful delivery. Delivery succeeds only when the callback invocation is admitted and the user callback returns normally.
- Every delivery task checks the current session/subscription/generation token at the final callback-entry gate. If the token is stale, the task exits without admission or callback invocation and increments `byStaleSession` only when a per-subscription delivery record has already been materialized.
- `AdmittedRunning` is the formal callback-start boundary. Once a delivery transitions to `AdmittedRunning`, `stop()`/`close()` treats it as already running, even if the first user-lambda instruction executes shortly after terminal return.
- After `AdmittedRunning`, the implementation must immediately invoke the user callback inside explicit `try/catch/finally`. No further dispatch, suspension, large copy, or blocking admission work is allowed between admission and the user callback call.
- If an admitted frame callback invokes user code and throws, the engine catches the failure inside the delivery task, releases the borrowed frame lease, records `droppedDeliveries.byCallbackThrew`, may emit a rate-limited `FrameDeliveryFailure` event with problem kind `FrameCallbackThrew` before terminal state, and keeps the subscription active unless the owner cancels it or the session becomes terminal.
- Callback exception handling is explicit engine control flow inside the delivery task. Diagnostic coroutine exception handlers are not the mechanism for delivery accounting, lease release, or subscription recovery.
- A callback may call `stop()`, `close()`, `trimMemory(level)`, or `FrameSubscription.cancel()` on its own subscription.
- A callback must not retain `EncodedImageFrame` after callback return. It must call `copyTo(...)` or `copyBytes()` if bytes are needed later.
- Consumers that need per-consumer dispatching, suspending work, or long retention must copy the frame inside the callback and hand the copied bytes to their own app-owned pipeline.
- The recommended transport bridge uses one engine frame subscription, quickly copies the latest frame into a bounded app-owned latest-frame holder, and lets transport clients apply their own backpressure/drop policy outside the engine.

## MediaProjection, Foreground Service, and VirtualDisplay

### Owner Startup Contract

The engine does not own Android consent UI or the foreground service. The integrating app must perform platform setup before calling `startSession(...)`.

Required sequence when a media-projection foreground service is used:

1. Request projection consent with `MediaProjectionManager.createScreenCaptureIntent(...)` or a supported platform configuration variant.
2. Start and promote a foreground service with foreground service type `mediaProjection` according to platform rules.
3. For target API 34+, declare `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PROJECTION`, and a service with `android:foregroundServiceType="mediaProjection"` where such a service is used.
4. Call `MediaProjectionManager.getMediaProjection(...)` only after the foreground-service prerequisite is satisfied.
5. Pass that fresh `MediaProjection` immediately to `ScreenCaptureEngine.startSession(...)`.

For target Android 15+, the owner must not launch a media-projection foreground service from a `BOOT_COMPLETED` broadcast receiver.

### Android 14+ Single-Use Rules

- A projection consent/session is single-use for `createVirtualDisplay(...)`.
- The same consent `Intent` must not be reused to obtain multiple `MediaProjection` instances on Android 14+.
- The same `MediaProjection` must not create more than one `VirtualDisplay` on Android 14+.
- The engine registers exactly one `MediaProjection.Callback` before `createVirtualDisplay(...)`.
- `MediaProjection.Callback.onStop()` is terminal for the session.
- A stopped or already-used `MediaProjection` is invalid for this design.

Startup failure mapping:

- Preflight failure before projection ownership and before any projection-consuming operation throws `ScreenCaptureStartException(requiresFreshProjection = false)`.
- Failure during `createVirtualDisplay(...)` or after ownership throws `ScreenCaptureStartException(requiresFreshProjection = true)` and releases or retires created resources.
- `requiresFreshProjection = false` is not a guarantee that the platform/caller can reuse the projection; it only says the engine has not performed an operation known to consume it.
- `requiresFreshProjection = true` means the caller must obtain fresh user consent and a fresh projection session before retrying.

### API 34+ Captured-Content Resize

On API 34+ the authoritative captured-content size is `MediaProjection.Callback.onCapturedContentResize(width, height)`. Metrics-provider width/height are provisional bootstrap inputs only; density still comes from the latest valid metrics-provider emission.

Normative startup deadline:

```text
API34_FIRST_CAPTURED_CONTENT_RESIZE_TIMEOUT_MS = 3_000
```

Startup behavior:

- no Active output and no frames are published from provisional width/height;
- startup waits for the first valid `onCapturedContentResize(width, height)`;
- the wait starts after `createVirtualDisplay(...)` returns non-null, unless a valid resize callback was already observed on the registered projection callback handler;
- the first valid resize callback produces initial `CaptureGeometry(source = CapturedContentResize)`;
- if the first valid resize does not arrive before the `3_000 ms` deadline, startup fails with `StartupGeometryUnavailable`;
- if projection stop is observed before the timeout failure is committed, or in the same serialized control turn as the timeout, the projection-stop problem wins because it is more specific;
- if `MediaProjection` ownership was taken or `createVirtualDisplay(...)` was attempted, `ScreenCaptureStartException.requiresFreshProjection` is `true` for this failure path.

Runtime behavior:

- every valid captured-content resize updates logical capture geometry;
- invalid sizes are ignored or fail according to lifecycle stage;
- captured-content visibility updates `Running.capturedContentVisible` when the platform callback is available;
- geometry and visibility events are conflated and sequenced.

### VirtualDisplay and Surface Ordering

The session creates at most one `VirtualDisplay`. Resizing/reconfiguration uses generation-controlled target changes, not second virtual displays.

Required target resize sequence is equivalent to:

1. validate target dimensions with checked `Long` arithmetic against config caps, GL limits, and `SurfaceTexture` constraints;
2. bump capture/surface generation and pause scheduling for the old generation;
3. prepare a new or resized `SurfaceTexture` with `setDefaultBufferSize(width, height)`;
4. create or reuse the corresponding projection `Surface`;
5. call `VirtualDisplay.resize(width, height, densityDpi)`;
6. call `VirtualDisplay.setSurface(newSurface)`;
7. retire the old `Surface` only after the virtual display no longer targets it and no GL/encoder/readback work references resources from the old generation;
8. resume scheduling only for the new current generation.

Every `SurfaceTexture`, projection `Surface`, `VirtualDisplay` target assignment, output plan, GL readback buffer/PBO lease, encoder result, and published frame belongs to a generation. Stale-generation frame-available signals, render ticks, readbacks, encode completions, and publications are discarded and counted as stale drops; they are not backend failures.

## Capture Geometry and Output Planning

### Metrics Provider Lifecycle and Geometry Ownership

`CaptureMetricsProvider` is mandatory even on API 34+, because density and bootstrap geometry need an owner-context source. The public provider object is owner-owned and reusable. A session owns only its attachment to the provider.

Provider ownership rules:

- `CaptureMetricsProvider` does not expose public `close()` in this revision.
- The engine must not close or permanently invalidate a provider object supplied through `ScreenCaptureConfig`.
- A provider may be created before a session, reused across sessions, and outlive a session.
- Built-in providers may use an internal attach/detach contract so a session can observe metrics without owning the provider object.
- Built-in factory calls should not register long-lived platform listeners at construction time. Long-lived listeners/callbacks are registered lazily when a session attaches and are unregistered when that session stops, closes, fails startup, or otherwise detaches.
- If one built-in provider is attached by multiple sessions, listener registration is implementation-owned and ref-counted or otherwise shared safely.
- Custom providers expose only `StateFlow<CaptureMetrics>` to the engine. Any lifecycle cleanup for custom providers belongs to the provider owner, not the session.

Provider factory intent:

- `fromActivity(activity)`: preferred for activity/window-owned capture. The provider is Activity/window-scoped; the owner must not treat it as an application-lifetime object.
- `fromUiContext(context)`: for UI-bound contexts where activity is not directly available. It has the same lifetime caution as other UI-context objects.
- `fromDisplay(baseContext, display)`: for explicit display-bound integrations. If the display disappears or becomes invalid, the provider may stop producing new valid metric changes; sessions use the last valid metrics until projection geometry or lifecycle says otherwise.
- `bestEffort(context)`: fallback for service/application contexts. It is the preferred built-in choice for long-lived foreground-service capture when no stable Activity/window owner is available, but it may be less accurate on old APIs, external displays, desktop/freeform modes, or non-standard launch flows.

Metric validity rules:

- width, height, and density must be positive;
- invalid startup metrics fail startup before projection ownership when possible;
- invalid running metrics are ignored, keep the last valid geometry, and emit `InvalidMetricsIgnored`;
- Activity destruction, display invalidation, or context lifetime end does not automatically stop a session. The owner remains responsible for app lifecycle policy;
- geometry events are conflated; no fixed debounce window is public contract;
- geometry sequence increments only when effective width, height, density, or source changes.

On API 34+, `onCapturedContentResize(width, height)` is authoritative for captured-content width/height after projection startup. The metrics provider still supplies density and bootstrap validity.

### Parameter Validation

| Value | Accepted range |
| --- | --- |
| `CropInsetsPx.*` | integer `0..32768` |
| `ScaleFactor.factor` | finite `0.10..2.00` |
| `TargetSize.width`, `TargetSize.height` | integer `1..32768` |
| `FrameRate.MaxFps.fps` | integer `1..120` |
| `FrameRate.PeriodicRefresh.intervalMillis` | integer `1_000..300_000` |
| `JpegImageEncoderProvider.quality` | integer `0..100` |
| `encoderProvider` | non-null provider with non-empty stable `id`, non-null `outputFormat`, and non-empty MIME type |

All pixel and byte products must use checked `Long` arithmetic before conversion to `Int` or allocation. Examples include `width * height`, `width * height * 4`, `rowStrideBytes * height`, PBO allocation sizes, bitmap memory, encoded snapshot slots, and aggregate retained-buffer estimates.

### Public Coordinate Semantics

All user-visible source-selection coordinates are expressed in logical captured-content pixels before any capture-target downscale.

Definitions:

```text
logicalContentRect = [0, 0, captureWidth, captureHeight)
sourceRect = sourceRegion(logicalContentRect)
croppedRect = sourceRect inset by CropInsetsPx
appliedSourceRect = croppedRect in logical captured-content coordinates
orientedContentSize = appliedSourceRect size after rotation
finalImageSize = resolved outputSize after rotation/contentMode rules
```

View-selection rules:

- `sourceRegion = Full` selects full logical captured content.
- `LeftHalf` and `RightHalf` split logical width at integer midpoint: left `[0, width / 2)`, right `[width / 2, width)`. The right half receives the extra pixel for odd widths.
- `crop` subtracts insets from the selected logical source region.
- If crop produces an empty rect, the plan is invalid: startup fails, running `setParameters(...)` returns `Rejected`, or a running session enters output suspension after a geometry change.
- `appliedSourceRect` always reports the selected/cropped logical rect before capture-target sampling, even when the internal capture target is downscaled.

Rounding:

- Positive finite sizes use `roundPositive(x) = floor(x + 0.5)`, then clamp each dimension to at least `1px` only after a non-empty source rect exists.
- `ScaleFactor(factor)` uses `roundPositive(inputDimension * factor)`.
- `TargetSize(width, height, Stretch)` produces exactly `width x height`.
- `TargetSize(width, height, AspectFit)` treats `width x height` as max bounds, preserves aspect ratio, produces no padding, and uses `scale = min(width / inputWidth, height / inputHeight)` before rounding.

### Performance-First Projection Sampling Model

Conceptual pipeline:

```text
MediaProjection
-> capture target Surface/SurfaceTexture, possibly downscaled from logical capture
-> external OES texture
-> GL source selection/crop/rotation/mirror/resize/grayscale
-> final output-sized RGBA framebuffer
-> readback
-> image encoder provider
-> latest-only publication
```

Public semantics:

- `sourceRegion` and `crop` are interpreted in logical captured-content coordinates.
- The implementation maps the logical `appliedSourceRect` into the actual capture target used by the current plan.
- `sourceRegion` and `crop` are geometric view-selection controls, not privacy/redaction boundaries.
- Early downscale/platform/GPU filtering may allow neighboring outside-region pixels to influence a small number of boundary samples.
- Pixel-exact exclusion at selection boundaries is not a public output guarantee.
- If product requires privacy/redaction semantics, it must enforce that at the source/window/content layer or add a separate redaction-specific pipeline.

Capture target planning:

- Capture target represents the whole logical captured content scaled uniformly to a target surface size.
- Capture target normally preserves logical captured-content aspect ratio. Aspect-changing output transforms are GL output work.
- Early downscale factor is computed from selected/cropped/oriented logical rect and final image size, but applied uniformly to whole logical captured content.
- A useful model is `requiredCaptureScale = min(1.0, max(finalImageWidth / orientedContentWidth, finalImageHeight / orientedContentHeight))`; implementations may use equivalent conservative heuristics.
- The engine must not enlarge capture target above logical capture only to support `ScaleFactor(>1.0)` or large `TargetSize`; upscale is final GL work.
- Capture target changes are generation changes and are reported through `CaptureTargetChanged` diagnostics and `ScreenCaptureEffectiveParameters.captureTarget`.

Runtime validation rejects or suspends plans when output dimensions, pixel counts, RGBA bytes, PBO bytes, bitmap bytes, encoded cap, GL texture/renderbuffer limits, or provider request values exceed supported limits.

## Scheduling, Publication, and Backpressure

### Frame-Rate Policy

- `MaxFps(fps)` renders at most `fps` from source frame availability.
- `PeriodicRefresh(intervalMillis)` ensures fresh publication for static sources at the requested interval, subject to backpressure and lifecycle. Sequence increments per published encoded image, not per visual change.
- `Auto` is implementation-defined but must be bounded and documented by the implementation.
- Frame pacing drops count as `droppedFrames.byFrameRatePolicy`.

### Latest-Only Publication

The engine maintains two distinct encoded-retention domains:

1. one replaceable internal latest encoded frame; and
2. a bounded pool of immutable public delivery snapshot slots.

`publishedSnapshotSlotCount` counts only the second domain. It does not include the internal latest encoded frame.

Publication is split from public delivery preparation. Producer-critical contexts publish the internal latest frame and signal the delivery coordinator through a conflated latest-only publication signal. They do not synchronously copy public snapshots, iterate subscriptions under heavy locks, wait for public snapshot slots, wait for callback dispatcher capacity, or emit blocking diagnostics.

Producer-side publication model:

```text
encoded result validated
-> internal latest encoded frame replaced with sequence N
-> framesPublished increments
-> conflated pending-delivery sequence becomes N
-> delivery coordinator is signaled if not already scheduled
-> producer path returns
```

Normative rules:

- The internal latest frame is owned by the engine and may be replaced by newer encoded output without waiting for public frame callbacks.
- Public callbacks never borrow the internal latest frame directly. They receive a borrowed `EncodedImageFrame` backed by an immutable public delivery snapshot lease.
- Immutable public delivery snapshots are prepared by the engine-owned delivery coordinator, not by producer-critical render/readback/encode contexts.
- One immutable public delivery snapshot may be shared by multiple callback deliveries through implementation-owned lease/reference counting. The public contract is immutable leased bytes, not one object allocation per callback.
- No public callback receives raw RGBA.
- No per-consumer encoded frame queue is allowed.
- If there are no active frame subscriptions, an implementation may skip public delivery-snapshot preparation for that publication.
- If a newer encoded frame is published before the delivery coordinator materializes delivery for an older pending sequence, the older pending sequence may be replaced by the newer sequence without per-subscription delivery accounting.
- If all public delivery snapshot slots are leased when a materialized delivery would need a public snapshot, delivery for affected subscriptions is skipped and producer-critical contexts do not block. Internal latest-frame replacement remains independent from public snapshot-slot exhaustion.
- Output suspension, `stop()`, `close()`, projection stop, and `Failed` make latest encoded data unavailable for new delivery.

Delivery drops are separate from production drops. A frame may be encoded and published successfully even if one or more subscriptions skip delivery.

### Public Snapshot Preparation

Public snapshot preparation is delivery-coordinator work. It converts a replaceable internal latest encoded frame into an immutable borrowed-frame source suitable for callback delivery.

Conceptual preparation flow:

```text
PendingDeliverySignal(seq)
-> PendingPublicSnapshotCopy(seq) registered in the session delivery registry
-> public snapshot slot reserved, if needed
-> CopyingPublicSnapshot(seq)
-> PublicSnapshotReady(seq)
-> per-subscription delivery/drop decisions materialized
```

Normative preparation rules:

- A pending or copying public snapshot is session-owned pre-delivery work, not a per-subscription delivery attempt.
- Any work that may allocate, reserve, copy, or retain bytes for public frame delivery must have a session-owned pre-delivery record before the work starts.
- A `PendingPublicSnapshotCopy` or `CopyingPublicSnapshot` record may be invalidated by stop, close, projection stop, generation change, output suspension, or a newer latest sequence.
- If invalidated before per-subscription delivery/drop decisions are materialized, the preparation is retired as internal stale cleanup and does not increment `droppedDeliveries`.
- A copy already in progress may finish after terminal return only to release/discard bytes. It must not create public leases, materialize subscriptions, publish frames, or invoke callbacks.
- If no public snapshot slot is available at the per-subscription decision point, affected eligible subscriptions receive materialized `bySnapshotSlotsExhausted` drop decisions. Implementations may choose an equivalent internal ordering that preserves the same public accounting.

### Delivery State Machine

Delivery state is split into internal publication/pre-delivery state and per-subscription accounting state.

Internal publication and pre-delivery work is session-owned, latest-only, and not yet a delivery drop surface:

```text
InternalLatestPublished(seq)
-> PendingDeliverySignal(seq)                    // conflated, not a frame queue
-> PendingPublicSnapshotCopy(seq)                // session-owned pre-delivery record
-> CopyingPublicSnapshot(seq)                    // may be retired as internal stale cleanup
-> PublicSnapshotReady(seq)                      // immutable public snapshot can back delivery
```

Per-subscription accounting begins only when the delivery coordinator reaches a concrete decision for a specific active subscription and sequence. That decision either creates a `DeliveryRecord` or records exactly one public delivery-drop category for that subscription.

```text
PerSubscriptionDecision(subscription, seq)
-> DirectDrop(bySubscriptionBusy | bySnapshotSlotsExhausted)

PerSubscriptionDecision(subscription, seq)
-> DeliveryRecordCreated(subscription, seq)
-> queued in engine-owned delivery coordinator
-> handed off to session-level callback dispatcher
-> final callback-entry gate
-> AdmittedRunning(subscription, seq)
-> user callback returns normally / user callback throws
-> lease released and in-flight delivery cleared
```

Terminal/stale cleanup boundaries:

```text
PendingDeliverySignal(seq), PendingPublicSnapshotCopy(seq), CopyingPublicSnapshot(seq),
or PublicSnapshotReady(seq) retired before any per-subscription decision exists
-> RetiredInternal(seq), no public delivery drop

DeliveryRecordCreated(subscription, seq) or HandedOff(subscription, seq)
retired before AdmittedRunning
-> RetiredStale(subscription, seq), count byStaleSession

AdmittedRunning(subscription, seq) races with terminal transition
-> callback remains admitted; no byStaleSession; lease released when callback returns or throws
```

Normative delivery-state rules:

- A per-subscription delivery/drop decision is the first point where `droppedDeliveries` may increment for that subscription and sequence.
- A `DeliveryRecord` is created only for an attempt that may be queued/handed off/admitted. Direct drop decisions such as `bySubscriptionBusy` or `bySnapshotSlotsExhausted` do not need to create a callback delivery record.
- A delivery is not successful until it reaches `AdmittedRunning`, invokes the user callback immediately afterward, and the callback returns normally.
- A non-throwing dispatcher handoff is not success; it only means the session-level callback handoff was attempted and not synchronously rejected in a way the engine observed.
- `AdmittedRunning` is the formal callback-start boundary. It means the delivery passed the final callback-entry gate and must immediately invoke the user callback.
- The first instruction of the user lambda is not the lifecycle boundary. It may execute shortly after `stop()`/`close()` returns if the delivery reached `AdmittedRunning` before the terminal transition.
- No additional dispatch, suspension, large copy, blocking allocation, public snapshot preparation, or queueing is allowed between `AdmittedRunning` and invoking the user callback.
- Each subscription has at most one queued, handed-off, or `AdmittedRunning` delivery. This single in-flight rule is what prevents per-consumer frame queues.
- The engine-owned delivery coordinator queue contains delivery tasks, not encoded frames. Its capacity matches the maximum active subscription count so one publication can attempt delivery to every active subscription without creating multi-frame backlog.
- A snapshot lease for a not-admitted per-subscription delivery remains reachable from session-owned delivery bookkeeping until the delivery is admitted or retired.
- An admitted callback owns its borrowed frame lease only for the admitted synchronous callback invocation. The lease is released when the callback returns or throws.
- Producer-critical contexts never wait for public snapshot preparation, callback completion, configured callback dispatcher availability, public snapshot-slot availability, or delivery event collection.
- The stop/close terminal gate is checked at the final callback-entry gate. A stale delivery exits without admission or callback invocation.

### Delivery Drop Categories and Slow Consumer Classification

Delivery accounting is per subscription per publication after a concrete per-subscription delivery/drop decision has been materialized. Every materialized decision that does not complete successfully is counted in exactly one public `ScreenCaptureDeliveryDropStats` category. Terminal/stale retirement before that per-subscription decision point is internal cleanup and does not increment `droppedDeliveries`.

| Delivery attempt result | Public drop category | Counts as direct subscription problem? |
| --- | --- | --- |
| Subscription already has a scheduled, handed-off, or admitted callback for a previous publication when the per-subscription decision is made. | `bySubscriptionBusy` | Yes. |
| Engine-owned callback dispatcher admission fails, or configured caller-owned dispatcher failure/cancellation is observed before callback admission while the session is non-terminal. | `byDispatchFailed` | Yes. |
| Delivery reaches `AdmittedRunning`, invokes user callback, and the callback throws. | `byCallbackThrew` | Yes. |
| No immutable public delivery snapshot slot is available for an otherwise eligible subscription at the per-subscription decision point. | `bySnapshotSlotsExhausted` | No. This is global snapshot-pool pressure for the affected subscription. |
| Session, generation, or subscription becomes stale after a `DeliveryRecord` exists but before callback admission. | `byStaleSession` | No. |
| Callback invocation is admitted and user callback returns normally. | No drop. | Resets direct problem counter. |
| Pending delivery signal or public snapshot preparation is retired before per-subscription decisions exist. | No public delivery drop. | No. |

Configured caller-owned dispatcher caveat:

- `byDispatchFailed` is strict for the engine-owned bounded callback dispatcher because the engine owns admission control.
- For arbitrary caller-owned `CoroutineDispatcher` supplied as `config.frameCallbackDispatcher`, `byDispatchFailed` is best-effort. Some dispatchers may transform rejection into coroutine cancellation or cleanup execution elsewhere. The engine records pre-admission failures it can observe, but it cannot make every dispatcher implementation synchronously attributable.
- Bounded, shutting-down, or executor-backed config dispatchers are allowed, but they are not recommended when precise delivery-failure classification is important.
- Because there is no per-subscription dispatcher override, a misconfigured session-level dispatcher affects all frame subscriptions in that session. Integrations that need isolation should use the engine-owned dispatcher and move copied bytes into app-owned pipelines.

Slow-consumer policy is public and deterministic:

- Each active frame subscription maintains `consecutiveDirectDeliveryProblems`.
- The counter increments only for direct per-subscription problems: `bySubscriptionBusy`, `byDispatchFailed`, and `byCallbackThrew`.
- The counter resets to zero only after that subscription's callback invocation is admitted and the user callback returns normally for a delivered frame.
- A subscription is classified as slow/failing when `consecutiveDirectDeliveryProblems >= slowConsumerThreshold`.
- `ScreenCaptureStats.slowConsumers` is the count of active subscriptions currently classified as slow/failing.
- Cancelling a subscription or reaching a terminal session state removes that subscription from `activeFrameSubscriptions` and from `slowConsumers`.
- `bySnapshotSlotsExhausted` may trigger/coalesce a `SlowConsumerPressure` diagnostic event because it indicates global public snapshot-pool pressure, but it does not increment the affected subscription's direct problem counter in this revision.
- `byStaleSession` does not increment direct problem counters because stale delivery is a lifecycle/generation race, not consumer behavior.
- Internal stale retirement before per-subscription records exist is not a subscription problem.
- An admitted callback stuck longer than the diagnostic watchdog threshold may trigger slow-consumer diagnostics, but its borrowed snapshot lease is not revoked or reused while the admitted callback invocation is still running.

Events remain diagnostic and best-effort. `stats` is authoritative for drop counters and slow-consumer state.

## Rendering, Readback, and Encoding

### Surface/OES Rules

- `SurfaceTexture` frame-available callbacks only enqueue work; they do not call GL.
- `SurfaceTexture.updateTexImage()` and `getTransformMatrix(...)` run only on the GL thread with the owning EGL context current.
- The OES transform matrix is applied for each consumed frame.
- `updateTexImage()` may skip intermediate producer frames; this is acceptable for latest-only semantics.
- Any `SurfaceTexture`, OES, size, or transform invariant failure maps to GL/surface problem kinds and may fail or suspend according to context.

### Readback

ES2 baseline:

- Required on API 24+.
- Render final output into RGBA framebuffer.
- `glReadPixels` copies into engine-owned CPU memory.
- CPU RGBA buffer lifetime is owned by the engine and never exposed to normal consumers.

ES3/PBO accelerated path:

- Optional runtime capability selected only after startup validation.
- PBO ring slots and fences are owned by the GL/readback subsystem.
- Fence waits/map attempts are bounded; timeout means the readback slot is busy and the production opportunity is dropped as `byReadbackBusy`. It is not a hard backend failure by itself.
- GL-thread fence handling is non-blocking; any optional non-GL wait is short-bounded by implementation defaults.
- A mapped PBO range leased to encoder is not reused, unmapped, or freed until the encode task completes or the resource is retired according to safe-retirement policy.
- Stop/resize/provider change retires active leases and prevents stale results from publishing.
- ES3/PBO validation or hard runtime invariant failure degrades one-way to ES2 for the session when safe fallback exists. If no safe fallback remains, startup fails or running output suspends/fails according to lifecycle context.

### Encoder Provider Execution

Provider `encode(...)` receives `ImageEncoderInputFormat.Rgba8888SrgbOpaque`, width, height, row stride, and a borrowed `ByteBuffer` valid only during the call. It writes encoded bytes only through `EncodedImageSink`. If sink write returns `false`, the provider should stop and return `Failed(...)`; the engine maps this to encoded-size limit handling.

A provider must not retain input, output sink, row pointers, or GL resources after `encode(...)` returns. A provider may be closed or retired when plan changes, session stops, provider changes, or backend degrades.

A blocked or stuck provider must not block `stop()` or `close()` from returning. The engine may mark the provider/backend unhealthy and retire resources, but raw/PBO/encoder resources still borrowed by an active synchronous `encode(...)` call are not reused until the call returns or the implementation can safely abandon the resource without reuse.

### Default JPEG Encoding

Default JPEG provider supports two backends:

- `FrameworkBitmapCompress`: required fallback on API 24+;
- `NdkAndroidBitmapCompress`: preferred on API 30+ when policy allows, native backend is loadable, symbols are safely guarded, and startup validation passes.

JPEG quality is `0..100`. JPEG output is opaque; alpha is not preserved. Native backend load failure, missing symbols, validation failure, or serious runtime invariant violation disables the native backend for the session and falls back to framework JPEG when possible. Repeated encode failures after fallback policy is exhausted suspend running output or fail startup according to lifecycle context.

## Memory, Privacy, and Cleanup

Retained memory is bounded by configured caps and active leases, not by an exact cross-device byte formula. Memory contributors include capture geometry, capture target, final image size, readback mode, encoder provider, snapshot slot count, active frame leases, active PBO/encoder leases, and backend/device allocation behavior.

Encoded retention has a public upper-bound model for the two encoded-retention domains:

```text
internal latest encoded frame <= maxEncodedBytes
public delivery snapshots    <= publishedSnapshotSlotCount * maxEncodedBytes
```

This model excludes implementation scratch buffers, encoder/backend allocations, readback/PBO resources, GL resources, platform/native overhead, and allocator fragmentation. It also does not promise that every slot is allocated eagerly.

The engine must:

- enforce `maxOutputPixels` and `maxEncodedBytes` before allocation/publication;
- use checked `Long` arithmetic for all size products;
- bound active frame subscriptions, snapshot slots, readback slots, encoder tasks, and PBO leases;
- avoid retaining old latest snapshots after suspension/stop/failure for new delivery;
- release or shrink optional buffers on `trimMemory(level)` without violating active leases;
- never expose raw RGBA to normal consumers;
- never promise exact zeroization of Android platform/native allocations.

`sourceRegion` and `crop` do not necessarily reduce platform-owned capture buffers because the capture target may contain whole captured content at a scaled size before GL view selection.

## Failure, Fallback, and Diagnostics

Failures are classified by lifecycle context, not by problem kind alone. The same low-level problem may reject startup, reject a parameter update, suspend running output, emit diagnostics, or terminate the session depending on when it happens and whether a safe fallback exists.

Recoverable examples:

- invalid running metrics ignored while last valid geometry remains;
- rejected parameter update keeps current active plan;
- ES3/PBO validation or runtime failure degrades to ES2;
- native JPEG unavailable/invalid degrades to framework JPEG;
- callback-dispatcher saturation drops delivery for affected subscriptions only;
- callback throw drops one delivery and keeps the subscription active;
- single transient readback/encode failure drops one production opportunity;
- repeated runtime readback/encode hard failures suspend output after fallback policy is exhausted when projection and session invariants remain valid.

Startup failure examples:

- projection invalid, stopped, reused, or permission flow invalid;
- `createVirtualDisplay(...)` failure;
- first API 34+ captured-content resize missing after the `3_000 ms` startup deadline;
- invalid startup metrics when they are required before projection ownership;
- initial output plan exceeds configured caps or device limits;
- required ES2/GL/readback setup failure;
- encoder provider unavailable with no valid fallback.

Running output suspension examples:

- active parameters become impossible after geometry change;
- repeated readback hard failures after fallback policy is exhausted while the projection remains valid;
- repeated encode hard failures after fallback policy is exhausted while the projection remains valid;
- recoverable allocation/memory pressure where keeping the projection alive is safe but new frames cannot be published.

Terminal examples:

- user/system projection stop;
- projection becomes invalid for platform/security reasons;
- unrecoverable GL context or surface invariant failure where safe rendering cannot continue;
- internal invariant violation;
- cleanup/resource ownership violation that prevents maintaining safe session boundaries.

Problem kind guidance:

- `ProjectionSessionReused`: fresh user consent/projection is required.
- `StartupGeometryUnavailable`: startup geometry was not available in time; retry requires fresh projection if ownership was taken.
- `OutputPlanInvalid` / `OutputLimitsExceeded`: change parameters or caps.
- `ReadbackRepeatedFailure`: startup fails if no initial readback path can run; running output suspends if projection remains alive and a later parameter/backend change may recover.
- `EncodeRepeatedFailure`: startup fails if no initial encoder can run; running output suspends if projection remains alive and a later parameter/provider change may recover.
- `FrameDeliveryFailed`: dispatcher/admission failure before callback admission; delivery problem only; session remains active unless another terminal problem occurs.
- `FrameCallbackThrew`: admitted callback invocation called user code and the callback threw; this is not dispatch failure; delivery problem only; session remains active unless another terminal problem occurs.
- `EncodedSizeLimitExceeded`: provider output exceeded `maxEncodedBytes`; reduce output size/quality or raise cap.

Diagnostic policy:

- `state` and `stats` are authoritative.
- `events` are best-effort, bounded, and rate-limited.
- Diagnostic event loss must not change lifecycle, parameter update results, frame publication, or delivery accounting.
- Repeated recoverable failures are coalesced so a broken device/backend/consumer does not flood the event flow.

## Test Matrix

Platform coverage:

| Area | Required tests |
| --- | --- |
| API 24-33 | metrics-provider geometry, ES2 readback, framework JPEG, lifecycle stop/close. |
| API 30+ | native JPEG backend availability, validation, fallback to framework. |
| API 34+ | fresh token/session, single `createVirtualDisplay`, mandatory callback before VD, first `onCapturedContentResize`, `3_000 ms` startup timeout, projection-stop tie-break, app-window/full-display resize. |
| API 35+ | foreground-service mediaProjection restrictions, no BOOT_COMPLETED startup integration. |
| API 37 target | native loading notes, local-network bridge note, adaptive/large-screen geometry behavior. |

Scenario coverage:

| Area | Required tests |
| --- | --- |
| Validation/overflow | `32768x32768`, large `ScaleFactor`, large `TargetSize`, byte-count overflow, invalid caps, slot count edge values. |
| Metrics provider ownership | built-in factory construction does not leak long-lived listeners; session attach/detach on success, stop, close, startup failure; provider reuse across sessions; Activity destroyed/display invalid/running invalid metrics ignored. |
| Startup | valid start; invalid metrics; missing API34 first resize timeout; projection stopped during startup; timeout and projection stop same control turn; reused token/session; `requiresFreshProjection` mapping. |
| Resize/generation | resize while rendering/encoding; stale frame-available; stale readback; stale encode completion; surface detach/release ordering. |
| Coordinates | Full/LeftHalf/RightHalf, odd widths, crop invalidation, AspectFit rounding, early downscale mapping. |
| Parameter transactions | prepare/validate/commit/rollback; commit-before-stop returns `Applied`; stop-before-commit returns `Rejected`; transaction resource bag cleanup; caller cancellation during update leaves no partial public plan. |
| GL/readback | ES2 baseline; ES3/PBO validation; fence timeout counts busy not hard failure; PBO fallback; PBO lease during stop/resize; repeated hard failure suspends running output after fallback exhaustion. |
| Encoder | framework JPEG; native JPEG; native validation failure disables native for session; provider unavailable; provider throws; provider exceeds sink cap; stuck provider; provider change via `setParameters`; repeated hard failure suspends running output after fallback exhaustion. |
| Delivery | engine-owned default callback dispatcher; configured caller-owned callback dispatcher; engine-owned dispatcher saturation; caller-owned dispatcher pre-admission cancellation/rejection best-effort classification; no per-subscription dispatcher override; dispatch return not success; `AdmittedRunning` is the formal callback-start boundary; callback throws and increments `byCallbackThrew`; subscription cancelled inside callback; snapshot-slot exhaustion; slow-consumer consecutive problem/reset policy; slow subscription isolation. |
| Stop/close delivery cleanup | pending public-snapshot copy record registered before copy; pending copy invalidated by stop; in-progress copy finishes only as stale cleanup; not-admitted delivery lease retired before return; handed-off task token invalidated; late task exits without admission; admitted callback may finish; admitted callback throw after stop updates stats but suppresses new post-terminal diagnostic event. |
| Lifecycle races | `setParameters`, `stop`, `close`, `trimMemory`, subscription cancel, projection stop, frame publication, public snapshot preparation, callback admission, and callback dispatcher handoff racing. |
| Diagnostics/tunables | event buffer overflow drops diagnostics without changing state/stats; event rate limiting; pre-admission delivery watchdog; admitted callback stuck diagnostic; max active subscriptions. |
| Privacy/memory | stop/suspension invalidates latest for new delivery; stale public snapshot preparation cannot create public leases; trim under active leases; no raw RGBA to normal consumers; admitted callback lease not forcibly revoked. |
| Transport bridge | one engine subscription copies quickly into app-owned latest holder; slow transport clients do not block engine. |

## Implementation Tunables

This revision makes selected runtime constants normative because they affect observable lifecycle, accounting, tests, or memory/backpressure boundaries. They are not public configuration values in this revision. Implementations may expose internal/test-only injection points, but product API must not depend on them.

The engine-owned delivery coordinator queue is deliberately tied to the maximum active subscription count. It is not a frame queue and must not create per-consumer frame backlog; it bounds not-admitted per-subscription delivery tasks after public snapshot preparation has materialized delivery records. Pending publication signals and public snapshot copy records are latest-only/conflated session-owned preparation work, not frame queues.

### Normative Internal Runtime Defaults

| Value | Default / rule |
| --- | --- |
| API 34+ first captured-content resize startup timeout | `3_000 ms` after `createVirtualDisplay(...)` returns non-null, unless a valid resize was already observed. |
| API 34+ timeout tie-break | Projection stop wins if observed before timeout commit or in the same serialized control turn. |
| Maximum active frame subscriptions | `16` per session. `onFrame(...)` over this limit throws `IllegalStateException`. Direct engine subscriptions are intended for a small number of in-process consumers; transport fan-out should be implemented outside the engine. |
| Engine-owned callback dispatcher workers | `2` fixed workers for public callback bodies. |
| Engine-owned delivery coordinator queue | Capacity `16`, matching max active subscriptions. This is not a frame queue; it holds at most one not-admitted delivery task per subscription. Each subscription still has at most one queued/handed-off/`AdmittedRunning` delivery. |
| Configured caller-owned dispatcher failure attribution | Best-effort only before callback admission; strict admission accounting applies only to the engine-owned callback dispatcher path. |
| Pre-admission delivery start watchdog | `3_000 ms`. If a materialized per-subscription delivery has not reached `AdmittedRunning` by then, retire/release its not-admitted lease and classify as `byDispatchFailed` when non-terminal, or `byStaleSession` when terminal/stale. |
| `AdmittedRunning` callback stuck diagnostic | First diagnostic after `5_000 ms`, then coalesce/repeat no more often than every `30_000 ms` while still running. Do not revoke or reuse the borrowed snapshot lease. |
| Diagnostic event buffer | `32` entries. Diagnostic overflow drops oldest diagnostic events without changing state or stats. |
| Diagnostic event rate limit | First diagnostic event for a key is immediate; repeated diagnostics are limited to at most `1/sec` per `(eventType, problemKind)` key. Lifecycle state transitions are not delayed by diagnostic rate limits. |
| Readback hard-failure threshold | `3` consecutive hard failures per active generation/backend before fallback or output suspension. Fence timeout/busy is not a hard failure. |
| Encode hard-failure threshold | `3` consecutive hard failures per active plan/provider/backend before fallback or output suspension. Encoded-size cap failures use the encoded-size drop path. |
| Native JPEG backend failure | Load failure, validation failure, missing guarded symbol, or serious runtime invariant disables native JPEG for the current session and falls back to framework JPEG when possible. |

### Implementation-Defined Backend Tunables

The following remain implementation-defined internal constants because they are backend/performance choices rather than public contract:

| Value | Recommended initial value / rule |
| --- | --- |
| ES3/PBO ring size | `3` slots. |
| GL-thread fence wait | Non-blocking poll, effectively `0 ns`. |
| Optional non-GL PBO map/wait | At most `1 ms`. |
| ES3/PBO validation details | Implementation-defined, but failures must degrade/fail/suspend according to public lifecycle rules. |
| Early-downscale heuristic | Implementation-defined, provided public coordinate semantics and the no-unnecessary-projection-upscale rule are preserved. |
| Thread names, diagnostic text, and backend-specific messages | Implementation-defined; not stable parsing contracts. |

The implementation may tune these internal values between library versions only when the public semantics above remain intact.

## Integration Notes

### MJPEG Transport Bridge

The engine is not an MJPEG streamer. Recommended transport pattern:

1. Register one engine frame subscription.
2. In the callback, copy bytes quickly to a bounded app-owned latest-frame holder.
3. Transport clients read from that holder and apply their own slow-client drop/backpressure policy.
4. HTTP multipart framing, authentication, access control, and local-network permissions remain app/transport responsibilities.

### Android 17/API 37 Local Network

The engine core opens no sockets and does not require local-network permissions. An HTTP/MJPEG bridge that serves LAN clients is outside the engine and must handle Android 17/API 37 local-network behavior itself. For broad persistent LAN communication on target SDK 37+, the bridge should expect `ACCESS_LOCAL_NETWORK` runtime-permission behavior unless it uses a system-mediated, privacy-preserving alternative that avoids broad LAN access.

### Native Loading

The default native JPEG backend should prefer packaged libraries loaded via `System.loadLibrary(...)`. If any native file is loaded via `System.load(...)` for target API 37+, it must be read-only before loading. Calls to newer native APIs must be guarded by API checks and weak API references or isolated in API-specific libraries so old devices can load the app safely.

## Official References Reviewed

- Android MediaProjection guide: https://developer.android.com/media/grow/media-projection
- MediaProjection API reference: https://developer.android.com/reference/android/media/projection/MediaProjection
- MediaProjection.Callback API reference: https://developer.android.com/reference/android/media/projection/MediaProjection.Callback
- Foreground service type `mediaProjection`: https://developer.android.com/develop/background-work/services/fgs/service-types
- Android 15 foreground service type changes: https://developer.android.com/about/versions/15/changes/foreground-service-types
- VirtualDisplay API reference: https://developer.android.com/reference/android/hardware/display/VirtualDisplay
- SurfaceTexture API reference: https://developer.android.com/reference/android/graphics/SurfaceTexture
- Bitmap and Bitmap.CompressFormat API references: https://developer.android.com/reference/android/graphics/Bitmap and https://developer.android.com/reference/android/graphics/Bitmap.CompressFormat
- Android NDK Bitmap API: https://developer.android.com/ndk/reference/group/bitmap
- NDK newer API usage / weak references: https://developer.android.com/ndk/guides/using-newer-apis
- Android 17 behavior changes: https://developer.android.com/about/versions/17/behavior-changes-17
- Android local network permission: https://developer.android.com/privacy-and-security/local-network-permission
- Android StateFlow and SharedFlow guide: https://developer.android.com/kotlin/flow/stateflow-and-sharedflow
- Kotlin CoroutineDispatcher API: https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/-coroutine-dispatcher/
- Kotlin CoroutineExceptionHandler API: https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/-coroutine-exception-handler/
- Kotlin asCoroutineDispatcher rejected-execution behavior: https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/as-coroutine-dispatcher.html
- Kotlin coroutine cancellation and `ensureActive`: https://kotlinlang.org/docs/cancellation-and-timeouts.html
- Android app screen sharing / MediaProjection resize callbacks: https://developer.android.com/about/versions/14/features/app-screen-sharing
