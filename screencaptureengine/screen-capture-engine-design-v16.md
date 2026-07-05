# Screen Capture Engine Design v16

> Public working name: `ScreenCaptureEngine`. The name is product-facing: the engine captures user-approved screen or app-window content through Android `MediaProjection`, renders a selected view, encodes it through an `ImageEncoderProvider`, and publishes latest-only encoded image frames. `MediaProjectionEngine` is intentionally not used as the public root name because it sounds like a thin platform wrapper rather than the complete capture/render/readback/encode/publication pipeline. MJPEG is an integration/transport pattern built on top of frame callbacks, not the core engine identity.

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

`ScreenCaptureEngine` is a Kotlin-first Android library that produces latest-only encoded still-image frames from a user-approved Android `MediaProjection` session.

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
- Java interop as a compatibility target.

## Public API

The public API is Kotlin-first. It uses `StateFlow`, `SharedFlow`, and `CoroutineDispatcher` from `kotlinx-coroutines-core`; it does not expose `java.util.concurrent.Executor` for frame delivery.

Public snippets assume Kotlin imports for Android platform classes and kotlinx.coroutines types.

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
    val defaultFrameDeliveryDispatcher: CoroutineDispatcher?
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
        dispatcher: CoroutineDispatcher? = null,
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

`onFrame(dispatcher = null, callback)` selects `config.defaultFrameDeliveryDispatcher` when non-null; otherwise it selects an engine-owned bounded frame-delivery dispatcher. The callback itself is intentionally non-suspending. A borrowed `EncodedImageFrame` is valid only during the synchronous callback body. Consumers that need to retain bytes or do suspending work must copy first, then launch their own coroutine work outside the callback.

`FrameSubscription.cancel()` is thread-safe and idempotent. It prevents future deliveries but does not interrupt a callback invocation that has already started.

Configuration defaults:

| Config value | Default | Meaning |
| --- | ---: | --- |
| `publishedSnapshotSlotCount` | `4` | Maximum immutable encoded snapshots retained for frame delivery. |
| `slowConsumerThreshold` | `2` | Diagnostic threshold for busy frame subscriptions or delivery failures. |
| `maxOutputPixels` | `2_073_600` | Default maximum final output pixels. This is a published-output cap, not a logical captured-content cap. |
| `maxEncodedBytes` | `8 * 1024 * 1024` | Hard cap per encoded/published frame and retained snapshot slot. |
| `defaultFrameDeliveryDispatcher` | `null` | Uses engine-owned bounded frame-delivery dispatcher. |

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

`StartupGeometryUnavailable` means the engine could not obtain trustworthy initial capture geometry required for startup. On API 34+ this normally means the first valid `onCapturedContentResize(width, height)` did not arrive before the startup deadline or the projection stopped before it arrived.

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
- `droppedDeliveries` counts per-subscription delivery skips and never increments `droppedFrames`.
- Drop `total` fields are exactly the sum of all listed category fields.
- Each dropped production opportunity or dropped delivery is counted in exactly one public drop category.
- `publishedFps` is based on `framesPublished` over session lifetime using monotonic elapsed time.
- `timestampElapsedRealtimeNanos` is publication time and is the public ordering timestamp.
- `EncodedImageFrame.sequence` increments per published encoded image frame, not per visual content change. `FrameRate.PeriodicRefresh` may publish a frame equivalent to the previous published frame.

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
- On API 34+, no Active output is planned from provisional width/height. Startup waits for the first valid `onCapturedContentResize(width, height)`.
- A `ScreenCaptureSession` creates at most one `VirtualDisplay`. It may resize the virtual display and replace the target `Surface`, but it does not create a second virtual display for the same projection session.
- A stopped or failed session cannot be restarted. Fresh user consent/projection creates a new session.
- `setParameters(...)` is thread-safe, suspending, serialized, and atomic. It returns `Applied` only after the new runtime plan has been validated, required resources are prepared or reconfigured, and state has been updated.
- `setParameters(...)` returns `Rejected(problem)` when requested parameters cannot produce a valid plan for current geometry or configured caps; the current active plan remains in use.
- If a later geometry change makes active parameters impossible, the session enters `Running(output = Suspended(...))`, stops new frame publication, retires old latest output for new deliveries, keeps projection alive, and resumes automatically when a later geometry or parameter update produces a valid plan.
- `stop()` and `close()` are fast, thread-safe, idempotent, and terminal. They may be called from the main thread, lifecycle callbacks, public frame callbacks, or owner worker threads.
- User/system projection stop publishes `Stopped(CaptureEnded, problem)` and does not publish `Failed`.
- Fatal engine/platform errors publish `Failed(problem)`.
- `state`, `stats`, and `events` remain readable after terminal state.

### Coroutine Cancellation

- Cancellation of the caller coroutine before `startSession(...)` returns requests startup abandonment. If projection ownership or `createVirtualDisplay(...)` has already happened, the engine releases or retires resources and the owner must assume a fresh projection is required for retry.
- Cancellation of the caller coroutine while `setParameters(...)` is in flight does not leave a partially applied public plan. The engine either completes the plan transition atomically or keeps the previous plan; `state.value` remains authoritative.
- `FrameSubscription.cancel()` is independent from coroutine cancellation and controls only future delivery for that subscription.
- The engine does not accept a caller `CoroutineScope` or caller `Job` for frame delivery lifetime. Session and subscription lifetime are explicit.

### Stop/Close Boundary and Asynchronous Cleanup

`stop()` and `close()` are immediate publication/frame-delivery boundaries, not synchronous heavy-resource destruction barriers.

After `stop()` or `close()` returns:

- no new render, encode, publication, or frame delivery work is accepted for the session;
- no new frame callback invocation starts;
- no new `EncodedImageFrame` lease is issued;
- the internal latest encoded frame is unavailable for new delivery;
- frame subscriptions are invalidated;
- public state is terminal or already terminal.

A frame callback already running may finish with its already selected snapshot. Its borrowed frame remains valid only until that callback returns.

Heavy resources are released or retired asynchronously on engine-owned contexts, including `VirtualDisplay`, projection `Surface`, `SurfaceTexture`, EGL/GL objects, PBOs, reusable Bitmaps, encoder scratch storage, and oversized snapshot slots. Retired resources must not be reused for new work. Resources blocked by active frame, encoder, or PBO/readback leases are released after the lease completes. Exact zeroization of all platform/native allocations is not a public guarantee.

### Threading Contract

Internal contexts:

- control context: lifecycle, parameters, projection events, capture geometry, output planning, adapter commands;
- GL thread: EGL, `SurfaceTexture`, shaders, framebuffer, readback;
- encoder context: synchronous image encoding work;
- frame-delivery context: subscription bookkeeping, coroutine-dispatcher handoff, and delivery-drop accounting.

Rules:

- GL calls run only on the GL thread.
- Encoder provider code does not own a GL context and must not call GL APIs.
- Normal consumers never access raw RGBA buffers.
- `startSession(...)` and `setParameters(...)` are safe to call from the main/UI coroutine context because they suspend instead of blocking the caller thread.
- Public calls from engine-owned contexts fail fast with `IllegalStateException` where needed to avoid deadlocks.
- `stop()`, `close()`, `trimMemory(level)`, and `FrameSubscription.cancel()` are non-blocking or fast-bounded and safe from the main/UI thread.
- Public frame callbacks are never invoked on the control context, GL thread, encoder context, or `MediaProjection` callback handler.
- If a caller supplies a dispatcher that can run tasks immediately, such as an immediate dispatcher, the callback may run without an extra thread hop. This remains outside producer-critical contexts, but the caller owns latency behavior of that dispatcher.

### Diagnostic Event Contract

`events` is read-only best-effort diagnostics for logging, telemetry, and debug UI.

- `state` is authoritative for lifecycle and current output status.
- `stats` is authoritative for counters, rates, byte sizes, and consumer pressure.
- `setParameters(...)` return value is authoritative for parameter-update acknowledgement.
- `onFrame(...)` is authoritative for encoded byte delivery.
- `events` must not be required for correct lifecycle control, parameter-update acknowledgement, cleanup completion, or frame consumption.
- Event emission never blocks control, GL, readback, encoding, latest-frame replacement, or frame delivery.
- Uncollected events may be dropped. Repeated recoverable events may be coalesced or rate-limited.
- `message` is diagnostic text and not a stable parsing contract.

### Frame Delivery Contract

Frame callbacks are session-scoped in-process latest-only delivery.

- `onFrame(dispatcher, callback)` registers a callback for this session only and returns a `FrameSubscription`.
- Passing `null` dispatcher uses `config.defaultFrameDeliveryDispatcher` when non-null; otherwise it uses the engine-owned bounded frame-delivery dispatcher.
- Passing a non-null dispatcher uses that dispatcher for this subscription only.
- Frame subscriptions are automatically invalidated by `stop()`, projection stop, `Failed`, `close()`, or their own `FrameSubscription.cancel()`.
- No frame subscription survives into another `MediaProjection` session.
- Each subscription has at most one scheduled or running delivery. If a newer frame is published while that subscription is busy, delivery for that newer publication is skipped for that subscription and `droppedDeliveries.bySubscriptionBusy` increments.
- The engine does not guarantee total ordering or fairness across different subscriptions.
- Callbacks for different subscriptions may run concurrently.
- A slow subscription does not block GL, readback, encoding, internal latest-frame replacement, or frame delivery to other subscriptions except through bounded snapshot-slot pressure.
- If dispatching to the selected dispatcher fails or the engine-owned bounded dispatcher is saturated, that delivery is skipped, `droppedDeliveries.byDispatchFailed` increments, and a rate-limited `FrameDeliveryFailure` event with problem kind `FrameDeliveryFailed` may be emitted. The subscription remains active until the owner cancels it or the session becomes terminal.
- If a frame callback throws, the engine catches it, skips that delivery, increments/diagnoses frame callback failure, and keeps the subscription active unless the owner cancels it or the session becomes terminal.
- A callback may call `stop()`, `close()`, `trimMemory(level)`, or `FrameSubscription.cancel()` on its own subscription.
- A callback must not retain `EncodedImageFrame` after callback return. It must call `copyTo(...)` or `copyBytes()` if bytes are needed later.
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

On API 34+ the authoritative captured-content size is `MediaProjection.Callback.onCapturedContentResize(width, height)`.

Startup behavior:

- metrics provider width/height are provisional only;
- no Active output and no frames are published before the first valid resize callback;
- first valid resize callback produces initial `CaptureGeometry(source = CapturedContentResize)`;
- missing first resize callback before a deterministic startup deadline fails startup with `StartupGeometryUnavailable` and `requiresFreshProjection = true` unless a more specific projection-stop problem is available.

Runtime behavior:

- every valid captured-content resize updates logical capture geometry;
- invalid sizes are ignored or fail according to lifecycle stage;
- density continues to come from the latest valid metrics-provider emission;
- geometry events are conflated and sequenced.

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

### Metrics Provider

`CaptureMetricsProvider` is mandatory even on API 34+, because density and bootstrap geometry need an owner-context source.

Provider intent:

- `fromActivity(activity)`: preferred for activity/window-owned capture;
- `fromUiContext(context)`: for UI-bound contexts where activity is not directly available;
- `fromDisplay(baseContext, display)`: for explicit display-bound integrations;
- `bestEffort(context)`: fallback for service/application contexts and may be less accurate on old APIs, external displays, desktop/freeform modes, or non-standard launch flows.

Provider rules:

- width, height, and density must be positive;
- invalid startup metrics fail startup before projection ownership when possible;
- invalid running metrics are ignored, keep the last valid geometry, and emit `InvalidMetricsIgnored`;
- geometry events are conflated; no fixed debounce window is public contract;
- geometry sequence increments only when effective width, height, density, or source changes.

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
- `Auto` is implementation-defined but must be bounded and documented.
- Frame pacing drops count as `droppedFrames.byFrameRatePolicy`.

### Latest-Only Publication

The engine maintains one internal latest encoded frame plus bounded immutable published snapshots.

- Encoded bytes are copied into engine-owned immutable snapshot slots before public delivery.
- No public callback receives raw RGBA.
- No per-consumer encoded frame queue is allowed.
- If all snapshot slots are leased when a new frame would be delivered, delivery for affected subscriptions is skipped; producer-critical contexts do not block.
- Output suspension, `stop()`, `close()`, projection stop, and `Failed` make latest encoded data unavailable for new delivery.

Delivery drops are separate from production drops. A frame may be encoded and published successfully even if one or more subscriptions skip delivery.

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
- Fence waits/map attempts are bounded; timeout means slot busy/skipped frame, not unbounded GL-thread blocking.
- A mapped PBO range leased to encoder is not reused, unmapped, or freed until the encode task completes or the resource is retired according to stuck-lease policy.
- Stop/resize/provider change retires active leases and prevents stale results from publishing.
- ES3/PBO failure degrades one-way to ES2 for the session or fails terminally if no safe fallback remains.

### Encoder Provider Execution

Provider `encode(...)` receives `ImageEncoderInputFormat.Rgba8888SrgbOpaque`, width, height, row stride, and a borrowed `ByteBuffer` valid only during the call. It writes encoded bytes only through `EncodedImageSink`. If sink write returns `false`, the provider should stop and return `Failed(...)`; the engine maps this to encoded-size limit handling.

A provider must not retain input, output sink, row pointers, or GL resources after `encode(...)` returns. A provider may be closed or retired when plan changes, session stops, provider changes, or backend degrades.

### Default JPEG Encoding

Default JPEG provider supports two backends:

- `FrameworkBitmapCompress`: required fallback on API 24+;
- `NdkAndroidBitmapCompress`: preferred on API 30+ when policy allows, native backend is loadable, symbols are safely guarded, and startup validation passes.

JPEG quality is `0..100`. JPEG output is opaque; alpha is not preserved. Native backend failure, missing symbols, load failures, or validation failures recover by selecting framework backend when possible. Repeated encode failures or invariant violations map to encoder problem kinds and may degrade or fail the session according to context.

## Memory, Privacy, and Cleanup

Retained memory is bounded by configured caps and active leases, not by an exact cross-device byte formula. Memory contributors include capture geometry, capture target, final image size, readback mode, encoder provider, snapshot slot count, active frame leases, active PBO/encoder leases, and backend/device allocation behavior.

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

Recoverable examples:

- invalid running metrics ignored while last valid geometry remains;
- rejected parameter update keeps current active plan;
- ES3/PBO validation or runtime failure degrades to ES2;
- native JPEG unavailable/invalid degrades to framework JPEG;
- dispatcher saturation drops delivery for affected subscriptions only;
- single transient readback/encode failure drops one production opportunity.

Terminal examples:

- projection invalid/stopped;
- `createVirtualDisplay(...)` failure;
- required ES2/GL/readback setup failure;
- output plan exceeds configured caps or device limits;
- encoder provider unavailable with no valid fallback;
- repeated readback/encode failures after fallback policy is exhausted;
- internal invariant violation.

Problem kind guidance:

- `ProjectionSessionReused`: fresh user consent/projection is required.
- `StartupGeometryUnavailable`: startup geometry was not available in time; retry requires fresh projection if ownership was taken.
- `OutputPlanInvalid` / `OutputLimitsExceeded`: change parameters or caps.
- `FrameDeliveryFailed` / `FrameCallbackThrew`: delivery problem only; session remains active unless another terminal problem occurs.
- `EncodedSizeLimitExceeded`: provider output exceeded `maxEncodedBytes`; reduce output size/quality or raise cap.

## Test Matrix

Platform coverage:

| Area | Required tests |
| --- | --- |
| API 24-33 | metrics-provider geometry, ES2 readback, framework JPEG, lifecycle stop/close. |
| API 30+ | native JPEG backend availability, validation, fallback to framework. |
| API 34+ | fresh token/session, single `createVirtualDisplay`, mandatory callback before VD, first `onCapturedContentResize`, app-window/full-display resize. |
| API 35+ | foreground-service mediaProjection restrictions, no BOOT_COMPLETED startup integration. |
| API 37 target | native loading notes, local-network bridge note, adaptive/large-screen geometry behavior. |

Scenario coverage:

| Area | Required tests |
| --- | --- |
| Validation/overflow | `32768x32768`, large `ScaleFactor`, large `TargetSize`, byte-count overflow, invalid caps, slot count edge values. |
| Startup | valid start; invalid metrics; missing API34 first resize timeout; projection stopped during startup; reused token/session. |
| Resize/generation | resize while rendering/encoding; stale frame-available; stale readback; stale encode completion; surface detach/release ordering. |
| Coordinates | Full/LeftHalf/RightHalf, odd widths, crop invalidation, AspectFit rounding, early downscale mapping. |
| GL/readback | ES2 baseline; ES3/PBO validation; fence timeout; PBO fallback; PBO lease during stop/resize. |
| Encoder | framework JPEG; native JPEG; provider unavailable; provider throws; provider exceeds sink cap; stuck provider; provider change via `setParameters`. |
| Delivery | default dispatcher; custom dispatcher; dispatcher saturation; callback throws; subscription cancelled inside callback; slow subscription isolation. |
| Lifecycle races | `setParameters`, `stop`, `close`, `trimMemory`, subscription cancel, projection stop, and frame publication racing. |
| Privacy/memory | stop/suspension invalidates latest for new delivery; trim under active leases; no raw RGBA to normal consumers. |
| Transport bridge | one engine subscription copies quickly into app-owned latest holder; slow transport clients do not block engine. |

## Implementation Tunables

The following are implementation constants, not public API:

1. API34 startup geometry timeout.
2. GL/PBO ring size and fence timeout.
3. ES3/PBO validation strategy.
4. Repeated-failure thresholds for readback, encoder, provider, native JPEG, framework JPEG, and delivery dispatch.
5. PBO/encoder stuck-lease watchdog and unhealthy-provider policy.
6. Engine-owned frame-delivery dispatcher backing implementation, queue size, and thread count.
7. Event coalescing/rate limits.
8. Exact early-downscale heuristic, provided public coordinate semantics and no-unnecessary-upscale rule are preserved.

## Integration Notes

### MJPEG Transport Bridge

The engine is not an MJPEG streamer. Recommended transport pattern:

1. Register one engine frame subscription.
2. In the callback, copy bytes quickly to a bounded app-owned latest-frame holder.
3. Transport clients read from that holder and apply their own slow-client drop/backpressure policy.
4. HTTP multipart framing, authentication, access control, and local-network permissions remain app/transport responsibilities.

### Android 17/API 37 Local Network

The engine core opens no sockets and does not require local-network permissions. An HTTP/MJPEG bridge that serves LAN clients may require Android 17 local-network runtime permission or a system-mediated alternative, depending on target SDK and product behavior.

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
- Android StateFlow and SharedFlow guide: https://developer.android.com/kotlin/flow/stateflow-and-sharedflow
- Kotlin CoroutineDispatcher API: https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/-coroutine-dispatcher/
