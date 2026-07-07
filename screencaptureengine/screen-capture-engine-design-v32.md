# Screen Capture Engine Design v32

> Public working name: `ScreenCaptureEngine`. The name is product-facing: the engine captures user-approved screen or app-window content through Android `MediaProjection`, renders a selected view, encodes it through an `ImageEncoderProvider`, and publishes latest-only encoded image frames. `MediaProjectionEngine` is intentionally not used as the public root name because it sounds like a thin platform wrapper rather than the complete capture/render/readback/encode/publication pipeline. MJPEG is an integration/transport pattern built on top of frame callbacks, not the core engine identity.
>
> Revision v32 keeps the Kotlin-only API, atomic startup boundary, projection attachment/consumption split, generation-owned projection target model, startup arbiter, final commit-return handoff, provider-preparation isolation, delivery/admission semantics, first-plan render transform package, and resource/config-based `RenderingPipelineReady` from earlier revisions. It makes the encoder SPI byte-exact by defining `Rgba8888SrgbOpaque` as top-to-bottom RGBA byte order with opaque alpha and absolute zero-based buffer rows, clarifies transactional encoded-sink discard semantics, specifies correctness-first framework JPEG conversion through ARGB color ints, classifies built-in JPEG allocation failures, tightens `maxEncodedBytes` early-rejection rules, clarifies `RuntimeFrameLoopInstalled` and public `InitialActivePlanCommitted` boundaries, and adds the concrete public engine factory/default-provider readiness contract.

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
- `ImageReader`, `MediaRecorder`, or other non-GL `Surface` consumers as normative runtime capture targets in this revision;
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

object ScreenCaptureEngines {
    fun create(context: Context): ScreenCaptureEngine
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

`ScreenCaptureEngines.create(context)` is the public concrete construction entrypoint for the default Android implementation. The factory stores only application-safe context state internally and does not start capture, attach metrics-provider listeners, request projection consent, create a `MediaProjection`, or allocate GL/encoder resources. Advanced dependency-injection wrappers may be built by the integrating app around the `ScreenCaptureEngine` interface, but this revision exposes one stable platform-module factory rather than a public builder.

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

`StartupGeometryUnavailable` means the engine could not obtain trustworthy initial capture geometry required for startup. On API 34+ this normally means the first valid `onCapturedContentResize(width, height)` did not arrive within `3_000 ms` after `createVirtualDisplay(...)` returned non-null. Before `AuthoritativeStartupGeometryReady`, startup arbitration priority is projection stop first, caller cancellation second, then first valid resize, timeout, and ordinary startup resource failure.

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

`FrameRate.Auto` is a requested-parameter convenience. Planning resolves it to a concrete bounded effective policy. In this revision `Auto` resolves to `FrameRate.MaxFps(30)`, and `ScreenCaptureEffectiveParameters.frameRate` never exposes `Auto`.

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
- `createEncoder(...)` is synchronous provider code from the engine perspective. It may execute app-provided code and is therefore treated as untrusted for scheduling, cancellation, and latency.
- `createEncoder(...)` must run only on an isolated engine-owned provider-preparation context. It must not run on the caller thread, main thread, control context, GL thread, `MediaProjection` callback handler, frame-delivery coordinator, callback dispatcher, or active runtime encoder lane.
- `ImageEncoder` instances are session/plan scoped and are used only on the engine encoder context after they have been created and validated.
- `encode(...)` is synchronous. It must fully consume the borrowed `ImageEncoderInput` before returning.
- Providers must treat `ImageEncoderInput.buffer` as read-only borrowed input even if the concrete `ByteBuffer` instance is technically mutable.
- Providers must not retain `ImageEncoderInput`, `ByteBuffer`, raw pointers, row data, or `EncodedImageSink` after `encode(...)` returns.
- Providers must not call engine public APIs from `createEncoder(...)`, `encode(...)`, or `close()`.
- Providers must not require or touch the engine GL context.
- Providers must write only through `EncodedImageSink` and must respect `maxEncodedBytes`. A `false` return from sink write means the encoded output would exceed the configured cap; the provider should stop and return `Failed(...)`. A failed encode attempt is transactionally discarded by the engine and is never publishable, even if the sink accepted earlier bytes from the same attempt.
- Provider exceptions are caught by the engine and mapped to encoder problem kinds. Repeated failures are handled by fallback/terminal policy.
- A blocked or stuck provider must not block startup rollback, `setParameters(...)` rollback, `stop()`, or `close()` from returning. The engine may retire blocked encoder/PBO resources, quarantine stuck provider-preparation workers, and mark the backend or provider unhealthy for the current session.

### Raw Encoder Input Contract

`ImageEncoderInputFormat.Rgba8888SrgbOpaque` is a byte-exact provider contract, not a backend-specific or Android `Bitmap` memory-layout alias. Every provider receives top-to-bottom logical image rows with this layout:

```text
row 0                  = top logical output row
row y base offset      = y * rowStrideBytes
pixel x in row y       = rowBase + x * 4
byte 0 at pixel offset = R, unsigned 8-bit sRGB
byte 1 at pixel offset = G, unsigned 8-bit sRGB
byte 2 at pixel offset = B, unsigned 8-bit sRGB
byte 3 at pixel offset = A, unsigned 8-bit, always 255 for engine-produced input
columns                = left-to-right within each row
padding                = bytes [width * 4, rowStrideBytes) after each row are unspecified
```

The `Opaque` suffix is normative. The engine must produce alpha byte `255` for every logical pixel. JPEG providers may ignore or overwrite alpha because JPEG output is opaque, but app-provided providers may assume alpha is opaque. If engine-produced input contains non-opaque alpha for this format, that is an engine bug.

`ImageEncoderInput.buffer` is canonicalized by the engine before `encode(...)` is called:

```text
buffer.position() == 0
buffer.limit() >= requiredBytes
requiredBytes = (height - 1) * rowStrideBytes + width * 4
row offsets are absolute zero-based byte indexes from the start of this buffer view
```

Providers should read by absolute index or by using their own duplicate/slice. The provider must not rely on the caller-visible mutable `position`/`limit` state as part of the image layout and must not write into the buffer. The engine may pass a duplicate or read-only view so provider position changes, if any, cannot affect engine-owned readback storage.

The engine must render, transform, or CPU-normalize readback output so every provider receives `ImageEncoderInputFormat.Rgba8888SrgbOpaque` in the specified top-to-bottom logical order. Bottom-to-top framebuffer order is an internal GL/readback concern and is not exposed in the provider contract. This revision does not add row-order metadata to `ImageEncoderRequest` or `ImageEncoderInput`.

### Encoded Sink Transaction Contract

`EncodedImageSink` is scoped to one encode attempt. Bytes accepted by the sink are tentative until the provider returns `ImageEncodeResult.Success` and the engine passes stale-generation and size-cap publication checks.

Rules:

- `write(...) == true` means the bytes were accepted into the current attempt buffer, not that they are publishable.
- `write(...) == false` means the attempt cannot remain within `maxEncodedBytes`; the provider should stop writing and return `ImageEncodeResult.Failed(...)`.
- If a provider returns `Failed(...)`, throws, returns success after a prior sink rejection, or otherwise violates the sink protocol, the engine discards the entire attempt. Partial bytes are never converted into `EncodedImageFrame`, never replace the internal latest frame, never create public snapshots, and never trigger frame delivery.
- The publication layer is the transactional boundary. The sink implementation may physically contain partial bytes until cleanup, but no partial or failed attempt is public.
- For streaming encoders such as the framework JPEG path, the provider may adapt `EncodedImageSink` to an `OutputStream` that throws or records a private cap-exceeded condition when the sink rejects a write; the provider then returns `Failed(...)` and the engine records the encoded-size drop path.

### Provider Preparation, Timeout, and Quarantine

`ImageEncoderProvider.createEncoder(request)` participates in startup and parameter-update transactions, but it is not trusted to be fast or cooperatively cancellable. The engine must isolate provider construction from lifecycle-critical contexts and fence all results with the owning plan-preparation token.

Provider preparation rules:

- Provider preparation uses an engine-owned `ProviderPreparationContext` with bounded admission and generation-bound plan-preparation-token fencing.
- Startup waits for `createEncoder(request)` only up to the normative internal timeout `ENCODER_CREATE_TIMEOUT_MS = 3_000`.
- A startup timeout maps to `ScreenCaptureProblemKind.EncoderUnavailable` with diagnostic text such as `ImageEncoderProvider.createEncoder timed out after 3000 ms`.
- In the normal startup order, encoder preparation happens after `createVirtualDisplay(...)` entry, so encoder-preparation timeout, failure, or stale rollback before `InitialActivePlanCommitted` reports `ScreenCaptureStartException.requiresFreshProjection = true`.
- Caller cancellation, projection stop, or startup rollback while provider preparation is running closes the startup gate and marks the provider-preparation record stale immediately. Rollback attempts best-effort cancellation or thread interruption, but it does not wait for provider code to return.
- If a stale provider call later returns an `ImageEncoder`, that result must not validate startup, commit a plan, or replace the primary startup outcome. The engine closes or discards the encoder asynchronously on an isolated provider-cleanup path.
- If a stale provider call later throws, that exception is internal diagnostic information only and must not replace the primary startup problem.
- If provider code ignores interruption and remains stuck indefinitely, the engine may abandon that worker for the session. Abandoned workers must not be reused for engine work and must remain fenced so late return cannot affect public state.
- The implementation must bound abandoned provider-preparation workers per `ScreenCaptureEngine` instance. Recommended default is `MAX_QUARANTINED_PROVIDER_WORKERS = 2` per engine instance. When the guardrail is exhausted, future provider-preparation admission for that engine fails fast with `EncoderUnavailable` until capacity is recovered or the engine instance is discarded.
- Provider cleanup, including late `ImageEncoder.close()`, is provider code too. It must not run on control, GL, main, MediaProjection callback, frame-delivery, or active runtime encoder lanes, and must not block public lifecycle calls.

The same provider-preparation policy applies to `setParameters(...)`. If encoder creation times out or fails during a parameter update, the update is rejected with `EncoderUnavailable` or `EncoderValidationFailed`, the previous committed plan remains in use, and any late encoder result is closed or discarded asynchronously.

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

`JpegImageEncoderProvider.outputFormat` is `EncodedImageFormats.Jpeg`, with MIME type `image/jpeg`. It forces opaque alpha. Source alpha is not preserved. Output is treated as opaque 8-bit SDR/sRGB JPEG. Built-in JPEG reads only the R, G, and B bytes from the `Rgba8888SrgbOpaque` input contract and constructs opaque output; it may validate or overwrite alpha internally, but it does not preserve alpha.

Before public `startSession(...)` is exposed as usable, `ScreenCaptureParameters.defaults()` must work with the built-in `JpegImageEncoderProvider`. Internal readiness tests may use fake or app-provided providers, but public startup must not require callers to replace the default JPEG provider merely to start a valid session.

The required first built-in JPEG backend is `FrameworkBitmapCompress` on API 24+. `JpegEncoderBackendPolicy.Auto` resolves to `FrameworkBitmapCompress` until a native backend is implemented and validated. `NdkAndroidBitmapCompress` remains a planned optional backend; it must not be advertised as active in `ImageEncoderInfo.backendName` until implemented and selected.

The built-in framework JPEG backend must accept top-to-bottom `Rgba8888SrgbOpaque` input with `rowStrideBytes >= width * 4`. The correctness-first baseline is explicit CPU normalization/conversion:

```text
for each top-to-bottom logical input row:
    read R, G, B, A bytes from the zero-based RGBA contract
    construct opaque non-premultiplied sRGB ARGB color int: 0xff000000 | (R << 16) | (G << 8) | B
    write those ARGB color ints into a mutable Bitmap through setPixels(...)
compress the Bitmap using Bitmap.CompressFormat.JPEG and the configured quality
```

This baseline intentionally avoids relying on `Bitmap.copyPixelsFromBuffer(...)` or native `Bitmap.Config.ARGB_8888` byte layout for the first implementation. Direct-copy/native-layout optimizations are allowed only as later validated optimizations that preserve the public RGBA byte contract and do not mutate provider-visible buffer semantics.

If the backing `Bitmap`/compression path requires tight rows or a different memory layout, the built-in provider normalizes padded rows internally before compression. App-provided providers receive the same row-stride contract and may reject unsupported requests during `createEncoder(request)` validation.

Built-in framework JPEG may allocate plan-scoped resources such as a mutable `Bitmap`, ARGB row/int scratch storage, and encoded-output scratch state during `createEncoder(request)` because startup readiness requires the selected encoder to be prepared before `InitialActivePlanCommitted`. Allocation or configuration failure for these built-in plan-scoped resources maps to `AllocationFailed` when the failure is memory/resource exhaustion, or to `EncoderUnavailable` when the selected backend cannot be prepared for a non-allocation reason. Request/info mismatch remains `EncoderValidationFailed`.

For `maxEncodedBytes`, built-in JPEG must accept structurally valid requests unless impossibility is deterministic. JPEG size is content-dependent, so the provider must not reject legal tiny caps using quality/entropy heuristics. Actual over-cap output is handled at encode time through `EncodedImageSink` rejection and the `byEncodedSizeLimit` production-drop path. Startup failure for size cap is allowed only when validation can prove no legal output attempt could satisfy the cap.

The required built-in implementation scope includes only JPEG. App-provided providers may output other single-image byte formats by declaring a stable `EncodedImageFormat`, but the engine does not parse, inspect, transcode, or guarantee interoperability for those bytes.

## Lifecycle and Threading

### Lifecycle Contract

- `startSession(...)` requires a fresh, active, not-stopped `MediaProjection` that has not already been used to create a `VirtualDisplay` and has not already delivered `MediaProjection.Callback.onStop()`.
- `startSession(...)` is an atomic public startup boundary. The caller sees either a returned `ScreenCaptureSession` whose initial public state is `Running(output = Active(...))`, or a thrown startup failure or caller cancellation before final commit.
- No public session identity exists before `InitialActivePlanCommitted`. Internal startup objects, transaction tokens, resource bags, future-session identifiers, and diagnostics identifiers may exist earlier only if they are invisible through public state, stats, events, frame callbacks, and owner lifecycle APIs.
- There is no externally visible session state where the projection has been attached or consumed, or where a `VirtualDisplay` already exists, but the projection target, GL/readback, encoder, initial output resources, or runtime frame scheduling loop are not ready for the initial Active plan.
- Internally, startup uses a `StartupTransaction` that may receive a projection reference, attach a `MediaProjection.Callback`, consume the projection by entering the single `createVirtualDisplay(...)` call, and own the generation-owned projection target `Surface`, minimal EGL/OES target resources, rendering/readback resources, encoder resources, runtime frame loop wiring, and `VirtualDisplay` if created. If startup fails before final commit, the transaction commits logical rollback and no public session is returned.
- Startup readiness uses internal milestones: `ValidatedInputs`, `ProjectionTargetReady`, `ProjectionCallbackAttached`, `VirtualDisplayAttempted`, `VirtualDisplayOwned`, `AuthoritativeStartupGeometryReady`, `RenderingPipelineReady`, `RuntimeFrameLoopInstalled`, and `InitialActivePlanCommitted`, followed immediately by the non-suspending return handoff. The final commit-return handoff begins only after the last cancellable startup checkpoint has passed.
- `ProjectionCallbackAttached` is platform notification/cleanup attachment. It is required before `createVirtualDisplay(...)`, but it is not projection consumption for `requiresFreshProjection` purposes.
- Projection consumption starts when `createVirtualDisplay(...)` is entered, `MediaProjection.stop()` is invoked by the engine, or `MediaProjection.Callback.onStop()` is observed.
- `AuthoritativeStartupGeometryReady` freezes an immutable `StartupGeometrySnapshot`. The first public `Running(Active)` effective parameters, initial output plan, and authoritative pre-active target rebind are derived from that snapshot. Later pre-active metrics, density, resize, or geometry inputs are retained as pending runtime signals and are not folded into the initial Active plan.
- `RenderingPipelineReady` means GL/render/readback/encoder resources for the first Active plan are prepared and validated by mandatory resource/config checks under the owning rendering-preparation token. It also means the engine has built and retained a first-plan render transform package derived from the frozen startup geometry snapshot and verified that the static crop, rotation, mirror, scale, color, and top-to-bottom encoder-input normalization strategy is representable by the selected GL/readback path. Encoder readiness includes successful provider preparation through the isolated provider-preparation context, within the bounded startup timeout, followed by `ImageEncoderInfo`/request/cap validation. It does not require a first real projected frame, `SurfaceTexture.updateTexImage()`, a concrete runtime OES transform matrix value, real render, `glReadPixels`, encode, publication, or public delivery probe.
- `RuntimeFrameLoopInstalled` means the returned session has a runtime frame scheduling/publication loop that can make progress after commit, observe stop/projection-stop gates, drain detached pending geometry/density before the first normal render scheduling tick, accept real frame-available signals, apply frame-rate policy, schedule render/readback/encode/publication attempts for the committed resources, and report zero produced frames honestly through stats. Public `startSession(...)` must not be exposed for a release path that returns `Running(Active)` while only a zero-frame shell exists and no real frame scheduling/publication path can run. The first actual frame may still be produced later.
- `InitialActivePlanCommitted` is the first public success boundary. It publishes initial `Running(output = Active(...))`, creates public session identity, transfers pending runtime signals exactly once, converts startup-owned resources into returned-session resources, and makes state/stats/events/subscription surfaces visible.
- After `InitialActivePlanCommitted` begins, caller cancellation no longer rolls back startup. The implementation must return the committed `ScreenCaptureSession` without any cancellable suspension, dispatcher hop, blocking platform call, GL/readback/encoder work, or event emission that can suspend.
- If projection stop is observed after `InitialActivePlanCommitted` begins but before `startSession(...)` physically returns, the committed session still returns. The terminal signal is queued for immediate runtime processing after the commit-return handoff; it is not converted back into startup failure and the engine must not intentionally return a session whose first public state skipped the initial `Running(Active)` commit.
- Initial `Running.output` is always `Active`; the engine does not start an initially suspended session. It is acceptable for pending runtime geometry/density to cause zero frames to be produced from the frozen first Active plan before the runtime transitions to an updated `Active` or `Suspended` state.
- On API 34+, no Active output is planned from provisional metrics width/height. Startup uses validated metrics only to create a positive bootstrap projection target, waits for the first valid `onCapturedContentResize(width, height)`, freezes that first valid resize plus valid density into `StartupGeometrySnapshot`, and fails after the normative `3_000 ms` startup deadline only when the startup arbiter commits timeout without a same-startup-arbiter-turn projection stop, caller cancellation, or valid resize.
- A `ScreenCaptureSession` creates at most one `VirtualDisplay`. It may resize the virtual display and replace the generation-owned target `Surface`, but it does not create a second virtual display for the same projection session.
- The startup `VirtualDisplay` is not created with a null `Surface`. Creation is delayed until `ProjectionTargetReady` has been reached.
- The first complete runtime implementation uses a GL-backed `SurfaceTexture` as the projection target. On API 24-25, reaching `ProjectionTargetReady` requires creating a real minimal EGL context and OES texture object before constructing the `SurfaceTexture`.
- After `AuthoritativeStartupGeometryReady`, authoritative target creation or `VirtualDisplay` rebind may perform bounded internal retry/fallback before startup failure. No initially suspended public session escapes. Any failure after `createVirtualDisplay(...)` entry still reports `requiresFreshProjection = true`.
- A returned active session owns the projection lifecycle for this engine. Owner `stop()` or `close()` commits `Stopped(OwnerStop, ...)`, closes the session gate, invalidates plan-preparation tokens and runtime work, and invokes `MediaProjection.stop()` best-effort. A later `MediaProjection.Callback.onStop()` caused by that call is a cleanup echo, not a second public terminal reason.
- External/system/user `MediaProjection.Callback.onStop()` that wins before owner stop commits `Stopped(CaptureEnded, ProjectionInvalidOrStopped)` and triggers cleanup. The engine does not classify this as `Failed`.
- A stopped or failed session cannot be restarted. Fresh user consent/projection creates a new session.
- `setParameters(...)` is thread-safe, suspending, serialized, and atomic. It is implemented as a prepare/validate/commit/rollback transaction and uses the same plan-preparation token model as startup. It returns `Applied` only when the transaction commits before any terminal transition wins.
- `setParameters(...)` returns `Rejected(problem)` when requested parameters cannot produce a valid plan for current geometry or configured caps, or when a terminal transition wins before commit. The previous active plan remains in use unless the session has become terminal.
- If a later geometry change makes active parameters impossible, the session enters `Running(output = Suspended(...))`, stops new frame publication, retires old latest output for new deliveries, keeps the single `VirtualDisplay` alive when safe, and resumes automatically when a later geometry or parameter update produces a valid plan.
- `onCapturedContentVisibilityChanged(false)` does not suspend output by itself. It updates `Running.capturedContentVisible` while frame production remains governed by lifecycle, geometry, output plan validity, frame pacing, GL/readback, and encoder health.
- `stop()` and `close()` are fast, thread-safe, idempotent, and terminal. They close the session gate synchronously, invalidate subscriptions, logically retire reachable not-admitted delivery records and pre-delivery public-snapshot records, commit owner-stop state if they win the terminal gate, request projection stop for a returned active session, and schedule heavy resource cleanup on engine-owned contexts.
- Fatal engine/platform errors publish `Failed(problem)` only when the session cannot safely continue and the projection stop path is not the more specific terminal cause.
- `state`, `stats`, and `events` remain readable after terminal state.

### Coroutine Cancellation

- Cancellation of the caller coroutine before the final startup commit-return handoff requests startup abandonment. Caller-visible cancellation may throw `CancellationException`, but the startup transaction still commits logical rollback.
- The last cancellable startup checkpoint occurs immediately before the commit-return handoff. After that checkpoint, the engine must not perform any cancellable suspension before returning the committed session.
- If caller cancellation wins before projection consumption, the engine unregisters its callback if attached, retires prepared non-projection resources, does not call `MediaProjection.stop()`, and does not mark the projection as fresh-required merely because callback attachment happened.
- If caller cancellation wins after projection consumption but before the final commit-return handoff, or if `MediaProjection.Callback.onStop()` was observed, the transaction closes the startup gate, invalidates plan-preparation tokens, retires projection resources, invokes or observes projection stop according to the projection stop policy, marks in-flight provider-preparation records stale, attempts best-effort interruption where possible, and the owner must assume fresh projection is required for retry.
- A stuck app-provided `createEncoder(...)` call must not delay caller-cancellation, projection-stop, startup rollback, parameter rollback, `stop()`, or `close()`. Its worker may be quarantined and any late result is discarded under the provider-preparation policy.
- If `InitialActivePlanCommitted` has begun, caller cancellation no longer abandons startup. The committed session has entered normal public lifecycle and `startSession(...)` must return it without a cancellable or suspending boundary.
- If startup would otherwise fail with `ScreenCaptureStartException` and caller cancellation has already cancelled a pre-commit suspension boundary, the caller may observe cancellation instead. Internal cleanup, projection consumption flags, token invalidation, and projection-stop behavior remain authoritative for resource handling.
- Cancellation of the caller coroutine while `setParameters(...)` is in flight does not leave a partially applied public plan. The engine either commits the transaction atomically, rolls it back, or reaches terminal state. `state.value` remains authoritative.
- Caller cancellation may cause the suspending `setParameters(...)` call itself to throw cancellation, but it does not create an intermediate public output state. A committed plan remains committed; an uncommitted transaction is rolled back or retired by terminal cleanup.
- `FrameSubscription.cancel()` is independent from coroutine cancellation and controls only future delivery for that subscription.
- The engine does not accept a caller `CoroutineScope` or caller `Job` for frame delivery lifetime. Session and subscription lifetime are explicit.

### Plan Preparation Tokens and Resource Handoff

Startup and parameter changes use explicit generation-bound preparation tokens. The token is a lifecycle fence, not merely a coroutine cancellation flag. It is required because provider construction, GL/readback preparation, and cleanup can be suspended, blocking, or delayed while the session lifecycle has already moved on.

Conceptual token model:

```text
PlanPreparationToken {
    transactionId
    sessionGeneration
    targetGeneration
    outputPlanGeneration
    lifecycleState = Valid | Stale | Committed | RolledBack
}
```

Token scope:

- provider-preparation admission, timeout, late success, late failure, and late cleanup;
- GL context, shader/program, framebuffer, render target, readback buffer, and PBO validation;
- partial GL/readback/provider allocation cleanup bags;
- selected readback backend ownership;
- prepared encoder ownership;
- transfer into the initial runtime resource owner at `InitialActivePlanCommitted`;
- transfer into a replacement runtime resource owner during `setParameters(...)`;
- stale-result fencing for superseded parameter transactions.

Rules:

- Every resource prepared for `RenderingPipelineReady` or `setParameters(...)` belongs to the preparing transaction until a token-checked commit transfers ownership to the runtime owner.
- Prepared resources may move into runtime ownership only when the token is valid, the transaction is ready to commit, and the session/target/output generations are still current.
- Projection stop, owner stop/close, rollback, caller cancellation before final commit, fatal startup failure, or a superseding parameter transaction invalidates the token before heavy cleanup starts.
- A stale token means the result cannot commit public state, cannot publish frames, cannot install runtime resources, and cannot replace the primary startup/update outcome. Resources associated with a stale token move to a stale cleanup bag.
- If a preparation step sees a stale token at its first pre-allocation/pre-admission checkpoint, it returns a lifecycle-stale outcome and performs no GL allocation, readback allocation, or provider work. This is not a GL/readback/provider resource failure. The owning transaction arbiter maps the already-known stale cause to projection stop, caller cancellation, rollback, or superseding transaction semantics.
- Invoking a preparer with a stale token is not itself a public `InternalInvariantViolation` when the token was invalidated by an already-committed lifecycle transition. It becomes `InternalInvariantViolation` only if no legitimate stale cause exists or the stale result is later allowed to commit.
- Lifecycle arbitration at the point of failure uses this priority when no earlier result has already committed: projection stop first, then caller cancellation, then resource validation failure. If a resource failure committed in an earlier arbiter turn, a later projection stop is cleanup/echo and does not rewrite the primary failure.
- Token invalidation must happen before engine-initiated `MediaProjection.stop()` during startup rollback or returned-session owner stop. This prevents late provider/GL/readback results from claiming runtime ownership while platform stop callbacks are being delivered.
- GL preparation is engine-owned but can still hang in platform/driver code. Startup GL/readback preparation has a bounded watchdog. On timeout, the token becomes stale, startup rolls back with `GlInitializationFailed` or `GlResourceFailure`, and the GL lane/resource bag is abandoned for this engine instance. An abandoned GL lane is never reused for session work; a new engine instance may create a fresh GL lane.

### Parameter Update Transactions

Runtime parameter changes are explicit transactions because later implementation phases create and reconfigure resources such as `VirtualDisplay` size, generation-owned projection target `Surface` assignments, GL-backed `SurfaceTexture` generations, GL framebuffers/PBOs, encoder instances, and snapshot buffers.

A parameter update has four conceptual phases:

```text
prepare   -> create candidate plan/resources in a transaction-owned resource bag
validate  -> check geometry, caps, generations, backend availability, and terminal gate
commit    -> atomically swap public effective plan/resources and publish state
rollback  -> retire candidate resources without changing the public effective plan
```

Normative transaction rules:

- Candidate resources created during `prepare` are owned by the transaction plan-preparation token and are not visible through `state`, `stats`, frame callbacks, or effective parameters until token-checked `commit` succeeds.
- Candidate resources never publish frames before commit.
- `commit` runs on the serialized control context and rechecks terminal state immediately before swapping the effective plan.
- If commit wins before `stop()`, `close()`, projection stop, or fatal failure, `setParameters(...)` returns `Applied`, even if a terminal transition happens immediately after commit.
- If a terminal transition wins before commit, `setParameters(...)` returns `Rejected(problem)` when the caller is still active. Owner `stop()`/`close()` maps to a stopped-session problem; projection stop maps to the projection-stop problem; fatal failure maps to the terminal failure problem.
- If validation fails before commit, `setParameters(...)` returns `Rejected(problem)` and the previous active plan remains in use.
- The transaction owns every resource it prepares until commit. After commit, the session owns the new active resources and retires old resources. If rollback or terminal transition wins before commit, the transaction resource bag is retired by rollback or terminal cleanup.
- Encoder creation during `prepare` uses the isolated provider-preparation context with the same timeout, plan-preparation-token fencing, late-result discard, and quarantine policy used by startup. GL/readback preparation uses the same token model and the startup/runtime GL preparation watchdog policy.
- `stop()` and `close()` may briefly synchronize with the control context to close the terminal gate and steal active transaction bags, but they do not wait for heavy GL/encoder/provider/platform resource destruction.

This transaction model is normative even if an early implementation phase prepares only pure plans and no platform resources.

### Stop/Close Boundary and Asynchronous Cleanup

`stop()` and `close()` are immediate publication/frame-delivery boundaries, not synchronous heavy-resource destruction barriers. For a returned active session they are also authoritative owner-stop transitions: the engine closes the session gate, invalidates runtime work, and invokes `MediaProjection.stop()` best-effort. If the platform later delivers `MediaProjection.Callback.onStop()` because of that engine stop, the callback is treated as an idempotent cleanup echo rather than a second public terminal reason.

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

Heavy resources are released or retired asynchronously on engine-owned contexts, including `VirtualDisplay`, generation-owned projection target `Surface` objects, GL-backed `SurfaceTexture` objects, minimal EGL/OES target resources, EGL/GL rendering objects, PBOs, reusable Bitmaps, encoder instances, encoder scratch storage, provider-preparation or provider-cleanup tasks, public snapshot copy scratch storage, and oversized snapshot slots. Retired resources must not be reused for new work. Resources blocked by active frame, encoder, or PBO/readback leases are released after the lease completes or after the resource can be safely abandoned without reuse. Provider-preparation workers that remain stuck after best-effort cancellation may be quarantined and abandoned according to the bounded per-engine provider-worker guardrail. A GL lane abandoned after startup GL preparation timeout is also retired from use for that engine instance. Exact zeroization of all platform/native allocations is not a public guarantee.

### Threading Contract

Internal contexts:

- control context: lifecycle, parameters, projection events, capture geometry, output planning, adapter commands;
- GL thread: EGL, OES texture ownership, GL-backed `SurfaceTexture`, shaders, framebuffer, readback;
- provider-preparation context: isolated provider construction and provider cleanup for `createEncoder(...)` and late/stale encoder close/discard;
- encoder context: synchronous image encoding work and internal-latest publication for already-created, validated encoders;
- frame-delivery coordinator: pending-publication conflation, asynchronous public snapshot preparation, session-owned pre-delivery records, subscription bookkeeping, snapshot-slot leasing, delivery state, dispatcher handoff, watchdogs, and drop accounting;
- frame-delivery callback dispatcher: invokes public callbacks when `config.frameCallbackDispatcher` is null.

Rules:

- GL calls run only on the GL thread.
- Encoder provider code does not own a GL context and must not call GL APIs.
- App/provider `createEncoder(...)` and late `ImageEncoder.close()` cleanup run on the isolated provider-preparation/cleanup context, not on caller, main, control, GL, MediaProjection callback, frame-delivery, callback, or active runtime encoder lanes.
- The active encoder context runs validated `encode(...)` calls for the current committed plan; it is not used for potentially blocking provider construction.
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

### Android 14+ Single-Use and Platform Stop Rules

- A projection consent/session is single-use for `createVirtualDisplay(...)`.
- The same consent `Intent` must not be reused to obtain multiple `MediaProjection` instances on Android 14+.
- The same `MediaProjection` must not create more than one `VirtualDisplay` on Android 14+.
- The engine registers exactly one `MediaProjection.Callback` before `createVirtualDisplay(...)`.
- `MediaProjection.Callback.onStop()` is terminal for a startup transaction or returned session.
- After `onStop()` is observed, `createVirtualDisplay(...)` is invalid for this projection even if no `VirtualDisplay` had been created yet.
- A stopped or already-used `MediaProjection` is invalid for this design.

### Projection Reference, Attachment, Consumption, and Freshness

This design distinguishes projection reference receipt, callback attachment, projection consumption, and projection invalidation. These boundaries are intentionally separate because `MediaProjection.Callback` registration is required before `createVirtualDisplay(...)`, but callback registration is not itself the capture-consuming operation for `requiresFreshProjection` purposes.

Internal projection lifecycle concepts:

```text
ProjectionReferenceReceived
  The engine has received the caller-supplied MediaProjection reference.
  No platform callback is attached yet.
  Startup failures here report requiresFreshProjection = false.

ProjectionCallbackAttached
  The engine has registered its MediaProjection.Callback.
  This is notification/cleanup attachment, not projection consumption.
  Startup failures here still report requiresFreshProjection = false unless onStop is observed.

VirtualDisplayAttempting
  The engine has atomically won the pre-VD arbitration and is entering
  createVirtualDisplay(...). The projection is considered consumed from this point,
  even if the call throws or returns null.

ProjectionConsumed
  The engine has entered createVirtualDisplay(...), or invoked MediaProjection.stop(),
  or observed MediaProjection.Callback.onStop(). Startup failures, rollback, or caller
  cancellation after this point report requiresFreshProjection = true.
```

The `requiresFreshProjection` boundary is the first projection-consuming or projection-invalidating event:

```text
createVirtualDisplay(...) attempt entered
OR engine MediaProjection.stop() invoked
OR MediaProjection.Callback.onStop() observed
```

The following are not projection consumption by themselves:

```text
startSession(...) entry
ValidatedInputs
ProjectionTargetReady
MediaProjection.Callback registration
```

`requiresFreshProjection = false` is not a guarantee that the platform or owner can successfully reuse the projection. It says only that the engine has not performed or observed an operation known by this design to consume or invalidate it. `requiresFreshProjection = true` means the caller must obtain fresh user consent and a fresh projection session before retrying.

Raw `MediaProjection.Callback.onStop()` delivery and `createVirtualDisplay(...)` entry are not required to be physically serialized on the same Android callback thread. They must, however, be resolved through an atomic pre-VD arbitration state machine so the engine has one authoritative classification:

```text
PreVirtualDisplay
  Callback is attached and the engine has not entered createVirtualDisplay(...).

StopObservedBeforeVirtualDisplay
  Raw onStop won before createVirtualDisplay entry. The engine must not enter
  createVirtualDisplay(...). Startup fails with ProjectionInvalidOrStopped and
  requiresFreshProjection = true.

VirtualDisplayAttempting
  createVirtualDisplay entry won. Projection is consumed; any later onStop is
  post-consumption startup rollback or returned-session termination.

ProjectionConsumed
  createVirtualDisplay was entered, engine stop was invoked, or onStop was observed.
```

A plain final `alreadyStopped` check immediately before `createVirtualDisplay(...)` is not sufficient because it has a time-of-check/time-of-use race. The transition into `VirtualDisplayAttempting` must be atomic relative to raw `onStop()` marking. The raw callback path should do minimal work: atomically mark stop, enqueue a startup signal, and let the serialized startup arbiter commit the public failure or cleanup outcome.

Milestone-level startup freshness:

| Startup point | Failure or cancellation after this point | `requiresFreshProjection` | Required rollback behavior |
| --- | --- | ---: | --- |
| Before `ValidatedInputs` | invalid config, parameters, or provider declaration | `false` | no projection cleanup |
| After `ValidatedInputs` | metrics invalid or pure planning preflight fails | `false` | no projection stop |
| After `ProjectionTargetReady` before callback attachment | target prepared but later pre-VD validation fails | `false` | retire target resources |
| After `ProjectionCallbackAttached`, before VD attempt | startup failure or caller cancellation | `false` | unregister callback, retire target resources, do not call `MediaProjection.stop()` |
| After `ProjectionCallbackAttached`, before VD attempt, but `onStop()` wins arbitration | projection externally stopped before VD entry | `true` | do not enter `createVirtualDisplay(...)`; cleanup; callback may already be inert or unregistered |
| `createVirtualDisplay(...)` attempt entered | call throws, returns null, caller cancellation, or later startup failure before final commit | `true` | logical rollback, release/retire VD if any, invoke `MediaProjection.stop()` best-effort unless already stopped |
| After `VirtualDisplayOwned` | any later startup failure before final commit | `true` | release/retire VD, stop projection, schedule heavy cleanup |
| After `AuthoritativeStartupGeometryReady` | authoritative target rebind, planning, GL, readback, encoder, or activation failure after bounded retry/fallback | `true` | release/retire VD/resources, stop projection |
| After `RenderingPipelineReady`, before the last cancellable startup checkpoint | resource failure, terminal race, or caller cancellation | `true` | release/retire VD/resources, stop projection |
| After the last cancellable startup checkpoint has passed and `InitialActivePlanCommitted` begins | no longer cancellable startup; commit invariant breach only | not applicable unless invariant breach prevents safe commit | returned session owns lifecycle, or invariant breach performs logical rollback if commit cannot safely complete |
| After `InitialActivePlanCommitted` | no longer startup failure; returned session owns lifecycle | not applicable | session `stop()`/`close()` owns terminal cleanup |

If platform `onStop()` is observed during startup before `InitialActivePlanCommitted` and the caller's suspension has not already been cancelled, `startSession(...)` throws:

```kotlin
ScreenCaptureStartException(
    requiresFreshProjection = true,
    problem = ScreenCaptureProblem(
        kind = ScreenCaptureProblemKind.ProjectionInvalidOrStopped,
        message = "MediaProjection stopped before startup completed",
        cause = null
    )
)
```

Before `AuthoritativeStartupGeometryReady`, startup arbiter priority is projection stop, caller cancellation, first valid resize, startup geometry timeout, then ordinary resource-startup failure. After the geometry snapshot has committed, later resize/metrics/visibility signals are pending runtime inputs and do not participate in startup success arbitration. Caller coroutine cancellation may still be the exception observed by the caller if it wins a pre-commit suspension boundary; once `InitialActivePlanCommitted` begins, cancellation no longer rolls back startup and the committed session must be returned.

### Startup Rollback and Projection Stop Ownership

Startup is a transaction, not a partially visible session. The transaction may own platform resources internally before `startSession(...)` returns, but the public result is binary: either the initial Active plan is committed and the session is returned, or startup fails/cancels before final commit and no session object escapes.

Internal startup milestones:

```text
ValidatedInputs
-> ProjectionTargetReady
-> ProjectionCallbackAttached
-> VirtualDisplayAttempted
-> VirtualDisplayOwned
-> AuthoritativeStartupGeometryReady
-> RenderingPipelineReady
-> RuntimeFrameLoopInstalled
-> last cancellable startup checkpoint
-> InitialActivePlanCommitted
-> non-suspending return of ScreenCaptureSession
```

`ProjectionTargetReady` means a non-null generation-owned projection target `Surface` exists with validated positive width, height, and density. It is safe to pass this `Surface` to `createVirtualDisplay(...)`. This milestone may require a minimal EGL context and OES texture even before the full render/readback/encoder pipeline is ready.

`ProjectionCallbackAttached` means the engine has successfully registered its single `MediaProjection.Callback` on the configured callback handler. Attachment is required before `createVirtualDisplay(...)`, but it does not create a public session and does not by itself consume the projection.

`VirtualDisplayAttempted` starts at the moment the engine atomically enters `createVirtualDisplay(...)`. The projection is considered consumed from this point even if the call throws or returns null.

`VirtualDisplayOwned` means `createVirtualDisplay(...)` returned a non-null `VirtualDisplay` and the startup transaction owns it.

`AuthoritativeStartupGeometryReady` means startup has committed an immutable `StartupGeometrySnapshot` for the initial Active plan. For API 34+, captured width and height come from the first valid `onCapturedContentResize(width, height)` that won the startup arbiter. Density comes from the latest valid metrics snapshot observed on the control context at the moment this milestone commits. For API 24-33, width, height, and density come from the latest valid metrics snapshot used to commit the milestone.

`RenderingPipelineReady` means the current generation has the GL rendering path, selected readback path, encoder instance, output plan, first-plan render transform package, and internal buffers prepared and validated for `Running(output = Active(...))`, all derived from the frozen startup geometry snapshot and fenced by the startup `PlanPreparationToken`. This milestone is reached through resource/config validation and transform representability validation, not by consuming a real projected frame. It does not require `SurfaceTexture.updateTexImage()` on projection content, a concrete runtime OES transform matrix value, first real render, first `glReadPixels`, first encoder `encode(...)`, first publication, or public frame-delivery probe.

Mandatory `RenderingPipelineReady` validation includes:

- EGL context exists and is current on the GL thread;
- generation-owned projection target `SurfaceTexture` and OES texture ownership are valid;
- shaders compile, programs link, and required uniforms/attributes are resolved for the configured initial plan;
- dynamic OES transform handling is configured for the render path, including a per-frame runtime slot for the matrix obtained after `updateTexImage()` and a validated composition path with the static first-plan transform package;
- output textures/renderbuffers/framebuffers match the frozen first Active plan and framebuffer completeness checks pass;
- a render/readback path that can produce top-to-bottom `Rgba8888SrgbOpaque` encoder input is validated;
- checked pixel/byte arithmetic and GL size limits have been applied before allocation;
- selected readback resources are allocated and shaped for the final output size;
- if ES2 readback is selected, CPU readback storage and row/stride assumptions are valid;
- if ES3/PBO readback is selected, PBO ring/fence/map resources have passed the implementation's validation;
- encoder request is built from the first Active plan, provider preparation returns a usable encoder within the bounded preparation timeout, and `ImageEncoderInfo`/request/cap compatibility is validated.

Synthetic GL self-tests are not part of the public startup contract. Debug/internal diagnostics may clear or render test content into engine-owned GL targets, but pre-public startup must not consume MediaProjection content, call `SurfaceTexture.updateTexImage()`, call `glReadPixels`, invoke app-provided `ImageEncoder.encode(...)`, publish frames, or turn an optional diagnostic self-test into a hidden startup dependency.

`RuntimeFrameLoopInstalled` means the runtime scheduler/publication loop for the committed session has been installed and can make progress after commit. It includes the runtime mailbox/control integration, frame-available admission, frame-rate scheduling, generation/stale gates, pending geometry/density drain before the first normal render tick, render/readback/encode/publication scheduling, stop/close handling, projection-stop handling, and honest zero-frame stats. Startup does not wait for a first produced frame. Public release behavior must not expose `Running(Active)` before this loop can attempt real frame production when frames arrive; tests may validate the zero-frame state before device/instrumentation coverage exercises the first real frame.

`InitialActivePlanCommitted` is the only public startup success boundary. It publishes the initial `Running(output = Active(...))`, creates public session identity, makes state/stats/events/subscription surfaces visible, transfers startup-owned runtime resources to the returned session, and detaches pending runtime signals from the startup transaction exactly once. If `InitialActivePlanCommitted` happened, `SessionStarted` is eligible for best-effort diagnostic emission even if a terminal transition is processed immediately afterward. Internal implementation milestones may prepare a non-public runtime owner earlier, but they must not call that public milestone committed, emit public state/events, accept subscriptions, or require owner cleanup until the non-suspending public return handoff can immediately follow.

The final commit-return handoff is non-cancellable after it begins:

```text
last cancellable startup checkpoint
-> InitialActivePlanCommitted
-> non-suspending return of ScreenCaptureSession
```

After `InitialActivePlanCommitted` begins, there must be no cancellable suspension, dispatcher hop, blocking platform call, GL/readback/encoder work, or event emission that can suspend before returning `ScreenCaptureSession`. Allowed post-commit work is limited to bounded non-suspending bookkeeping such as storing the initial state, finalizing the public session wrapper, marking the transaction committed, best-effort non-blocking diagnostic emission, and returning the session. Projection stop observed in this small window is queued as a runtime terminal signal and processed after the session has been returned.

Public identity rules:

- No `ScreenCaptureSession` object, public state flow, stats flow, event flow, frame subscription surface, `SessionStarted` diagnostic, or frame publication is externally visible before `InitialActivePlanCommitted`.
- Internal pre-session objects may exist earlier only if they cannot be used by owner code and cannot emit public state/events/frames. If implementation tests need a pre-public internal runtime owner, it is a separate internal milestone such as `InternalRuntimeOwnerPrepared`, not the design's public `InitialActivePlanCommitted`.
- `SessionStarted`, when emitted, is diagnostic and belongs after `InitialActivePlanCommitted`; it must not be a suspending part of commit-return correctness.

Startup geometry freeze rules:

- Once `AuthoritativeStartupGeometryReady` commits, the first public Active plan must use the frozen `StartupGeometrySnapshot`.
- Later metrics updates, additional captured-content resize callbacks, or other geometry inputs that arrive before `InitialActivePlanCommitted` must not mutate the startup transaction in place.
- Such later inputs are recorded as pending runtime signals and processed only after initial Active commit, unless projection stop or caller cancellation aborts startup before the final commit-return handoff.
- Captured-content visibility is not output geometry. The initial public `capturedContentVisible` must use the latest known visibility value at `InitialActivePlanCommitted`, or `null` only if no visibility callback was observed.
- A density-only metrics change after the snapshot freezes is a pending runtime geometry signal even when captured width and height are unchanged.

Pending runtime signal handoff rules:

- Pending pre-active resize, metrics, density, and visibility inputs are handed from startup to runtime exactly once by effective state, not by every raw callback.
- Raw duplicate or intermediate signals may be conflated to the latest valid effective state.
- Geometry and density pending signals are not applied to the frozen first Active plan. They are detached from the startup transaction at `InitialActivePlanCommitted` and must be applied or intentionally absorbed by runtime before the first normal render scheduling tick after commit, unless startup is immediately overtaken by terminal projection/session cleanup.
- The latest known pre-commit visibility value initializes `Running.capturedContentVisible` at commit and is not replayed again as a runtime visibility update or standalone diagnostic event.
- If a signal races with commit, sequence/generation ownership decides whether it belongs to the startup pending handoff or to normal post-commit runtime processing. It must not be lost and must not be applied twice.
- A pending geometry/density signal that would make the frozen first plan obsolete or impossible does not prevent `InitialActivePlanCommitted`. The engine commits the frozen `Running(Active)` first, then runtime applies the pending signal and transitions to updated `Running(Active)` or `Running(Suspended)` if needed. It is explicitly valid for zero frames to be produced from the frozen first plan.

Pre-active authoritative target rebind rules:

- After `AuthoritativeStartupGeometryReady`, the engine should prepare an authoritative generation-owned target and resize/rebind the single `VirtualDisplay` to match the frozen startup geometry before `RenderingPipelineReady`.
- If authoritative target preparation or `VirtualDisplay.resize(...)` / `setSurface(...)` fails in a clearly recoverable way, startup may retry once with a freshly created generation-owned target.
- Fallback to the provisional/bootstrap target is allowed only when that target already satisfies the authoritative startup geometry semantics, including size/aspect/generation safety and GL target invariants.
- Fallback must never allow API 34+ provisional metrics dimensions to become Active output.
- If no safe authoritative target exists after bounded retry/fallback, startup fails with `SurfaceCreateOrResizeFailed`, `OutputPlanInvalid`, `OutputLimitsExceeded`, or a more specific projection/GL problem. Because this occurs after `createVirtualDisplay(...)` entry in the normal path, `requiresFreshProjection = true`.
- Startup must not return a `Running(Suspended)` session as a recovery mechanism for pre-active target or initial output-plan failure.

Rollback rules:

- Invalid config, parameters, encoder provider declarations, and startup metrics fail before callback attachment and projection consumption whenever possible.
- Invalid startup metrics fail before callback attachment and projection consumption whenever possible, including on API 34+, because density and positive bootstrap dimensions are still mandatory.
- If startup fails after callback attachment but before projection consumption, the engine unregisters the callback, retires prepared non-projection resources, does not call `MediaProjection.stop()`, and reports `requiresFreshProjection = false` unless `onStop()` was observed.
- If startup fails or caller cancellation wins after projection consumption but before the final commit-return handoff, the engine closes the startup gate, invalidates all startup plan-preparation tokens, retires or releases the `VirtualDisplay` if one exists, invokes `MediaProjection.stop()` best-effort unless platform `onStop()` was already observed or the engine already invoked stop, and reports `requiresFreshProjection = true` for `ScreenCaptureStartException` paths.
- If `createVirtualDisplay(...)` returns null or throws, startup fails with `VirtualDisplayCreateFailed`, `ProjectionInvalidOrStopped`, `ProjectionSessionReused`, or another more specific projection problem and reports `requiresFreshProjection = true`.
- If `createVirtualDisplay(...)` succeeds but a later required startup resource fails before `InitialActivePlanCommitted`, startup fails and reports `requiresFreshProjection = true`. This includes captured-content resize timeout, final target resize or replacement after bounded retry/fallback, required GL rendering/readback setup or watchdog timeout, encoder provider-preparation timeout/failure, encoder readiness validation failure, and initial output-plan activation. ES3/PBO validation failure is not startup failure when ES2 readback is valid and can be selected for the session.
- Projection stop before `InitialActivePlanCommitted` aborts startup. Caller cancellation before the last cancellable startup checkpoint aborts startup. Pending resize, metrics, density, or visibility signals do not abort startup and are runtime-only if startup commits. If projection stop, caller cancellation, and resource validation failure are arbitrated in the same not-yet-committed startup turn, projection stop wins first, caller cancellation second, and resource validation failure third.
- Startup rollback before throwing or returning cancellation must complete a fast logical barrier. It must close the startup/session gate, invalidate preparation tokens, prevent any new render/readback/encode/publication/delivery work, make callbacks inert or unregistered, detach resources from public/runtime paths, logically retire owned `VirtualDisplay`/target resources, and invoke projection stop when required by the rules above.
- Startup rollback before throwing does not need to physically finish all heavy cleanup. EGL/GL object destruction, `SurfaceTexture` release, `Surface` release after safe detach, PBO/readback cleanup, encoder close, scratch-buffer release, and oversized snapshot storage release may continue asynchronously on engine-owned cleanup contexts. A GL lane abandoned after watchdog timeout is never reused for new work.

Cleanup failure surfacing:

- The primary `ScreenCaptureStartException.problem` is the startup failure that prevented the initial Active session, not a later cleanup failure.
- Heavy cleanup failures observed before throwing may be logged internally and may be attached as suppressed causes in debug or test builds, but they must not replace the public problem kind.
- Heavy cleanup failures observed after the exception is delivered are internal logging/test diagnostics only because no public `events` flow exists for a session that never escaped.
- Heavy cleanup failures must not convert ordinary startup failure or caller cancellation into `InternalInvariantViolation`.
- Failure to establish the fast logical rollback barrier is different from heavy cleanup failure. If the engine cannot guarantee that no public/session work can start or become visible after startup failure, the startup problem must be `InternalInvariantViolation`.

### Returned Session Stop Semantics

After `InitialActivePlanCommitted`, the returned session owns the projection lifecycle for this engine. The engine does not support handing the projection back to the caller for reuse or another capture pipeline.

Owner-requested `stop()` or `close()` of a returned active session:

```text
commit terminal reason OwnerStop
close session gate and invalidate delivery
retire runtime resources from public paths
invoke MediaProjection.stop() best-effort
schedule asynchronous heavy cleanup
```

External, system, or user `MediaProjection.Callback.onStop()` that wins first:

```text
commit terminal reason CaptureEnded
mark projection consumed/invalid
close session gate and invalidate delivery
release/retire VirtualDisplay and target resources
schedule asynchronous heavy cleanup
```

If owner stop commits first, a later `MediaProjection.Callback.onStop()` caused by `MediaProjection.stop()` is a cleanup echo. It must be handled idempotently, suppress duplicate public terminal events, and must not change the already committed `Stopped(OwnerStop, ...)` reason. If external `onStop()` commits first, a later owner `stop()`/`close()` is idempotent cleanup and must not rewrite the reason to `OwnerStop`.

### Projection Target Surface Contract

The platform virtual display renders into an application-provided `Surface`. This design therefore defines startup in terms of a non-null generation-owned projection target `Surface`, not a particular backing consumer object used to create that `Surface`.

Normative target rules:

- The startup `VirtualDisplay` is never created with a null `Surface`, even though the platform API allows a null initial surface.
- `createVirtualDisplay(...)` is delayed until `ProjectionTargetReady` has been reached and the pre-VD arbitration has allowed entry.
- The first complete runtime implementation uses a GL-backed `SurfaceTexture` as the projection target.
- On API 24-25, `ProjectionTargetReady` requires a real minimal EGL context and a real OpenGL/OES texture object name before constructing `SurfaceTexture(texName)`. A zero or fake texture name is not a valid runtime path.
- On API 26+, a detached `SurfaceTexture` constructor may be used as an internal optimization, but the implementation may also use the same minimal EGL/OES path as API 24-25.
- `ImageReader`, `MediaRecorder`, or another non-GL `Surface` consumer is not part of the normative runtime path for this revision. Such consumers may be used only as internal bring-up or test shims and must not be exposed as design-complete behavior.
- If a non-GL shim is used internally, it is generation-owned startup-only state; it must not publish frames, must be drained or closed promptly, must be detached or replaced before `Running(Active)`, and any failure after `createVirtualDisplay(...)` still reports `requiresFreshProjection = true`.
- `ProjectionTargetHandle` exposed outside the GL runtime owner is surface-only. It does not allow `SurfaceTexture.updateTexImage()`, OES binding/sampling, or transform-matrix reads.
- Pre-public readiness uses a `ReadinessValidationToken` to validate ownership, generation, and GL wiring without consuming frames. Runtime frame consumption uses a separate `RuntimeFrameConsumptionToken`, created only after `InitialActivePlanCommitted`, checked against the current generation, and used only on the GL thread with the owning EGL context current.

Recommended startup target order:

```text
validate config, parameters, metrics, and caps
resolve bootstrap width/height/density from metrics
create StartupTransaction
prepare ProjectionTargetReady:
  API 24-25: minimal EGL + OES texture + SurfaceTexture(realTexName) + Surface
  API 26+: same path, or optional detached SurfaceTexture optimization
register MediaProjection.Callback
atomically arbitrate createVirtualDisplay entry against raw onStop
enter createVirtualDisplay(..., nonNullProjectionTargetSurface)
API 34+: wait for startup arbiter to accept first valid onCapturedContentResize
commit immutable StartupGeometrySnapshot
prepare or rebind authoritative generation-owned target:
  retry once with a fresh target if recoverable
  fallback to bootstrap target only if it already satisfies authoritative geometry semantics
prepare full GL/render/readback resources from the frozen snapshot
prepare encoder through the isolated provider-preparation context with timeout and stale-token fencing
validate encoder request/info/cap compatibility
perform final cancellable/terminal gate check
begin InitialActivePlanCommitted:
  publish Running(Active) from the frozen snapshot
  initialize capturedContentVisible from latest known startup visibility or null
  detach pending geometry/density runtime signals exactly once
return ScreenCaptureSession without further suspension or dispatcher hop
```

The design separates `ProjectionTargetReady` from `RenderingPipelineReady`. A target `Surface` can be ready for `createVirtualDisplay(...)` before shaders, FBOs, optional PBOs, readback buffers, encoders, and final output resources are ready. `RenderingPipelineReady` validates the resources required for the first Active plan by deterministic resource/config checks; it does not require a first real frame probe, first readback, first encode, publication, or delivery. This separation is internal only; no public session escapes until the selected rendering pipeline and initial Active output plan are committed and the non-cancellable return path begins.

### API 34+ Captured-Content Resize and Visibility

On API 34+ the authoritative captured-content width and height come from `MediaProjection.Callback.onCapturedContentResize(width, height)`. Metrics-provider width and height are bootstrap inputs only. Density for the initial Active plan is the valid metrics-provider density frozen into `StartupGeometrySnapshot` at `AuthoritativeStartupGeometryReady`.

Startup callbacks, timer deadlines, resource failures, caller cancellation, and raw projection-stop observations are converted into engine-internal startup signals. They do not directly commit startup success or failure from Android callback threads or timer threads.

Before `AuthoritativeStartupGeometryReady`, startup priority inside the startup arbiter is:

```text
projection stop > caller cancellation > first valid resize > startup geometry timeout > ordinary resource failure
```

A same startup arbiter turn means one invocation of the startup arbiter on the engine control context after it has drained or snapshotted all currently available startup signals from the engine-internal signal mailbox without suspension. It does not include Android callback or timer messages that have not yet entered the engine mailbox. Timeout only enqueues a `StartupGeometryTimeoutDue` signal; it must not directly commit `StartupGeometryUnavailable` outside the arbiter.

Startup behavior:

- validated metrics-provider width, height, and density are required before callback attachment and before `createVirtualDisplay(...)`;
- metrics width/height are used only to create a positive bootstrap projection target;
- no Active output and no frames are published from provisional metrics dimensions;
- the first valid resize signal that wins the startup arbiter supplies captured-content width and height for the immutable `StartupGeometrySnapshot`;
- `densityDpi` in the `StartupGeometrySnapshot` is the latest valid metrics density observed on the control context when `AuthoritativeStartupGeometryReady` commits;
- if valid resize and timeout are present in the same arbiter turn, valid resize wins unless projection stop or caller cancellation also wins that turn;
- if projection stop and valid resize are present in the same arbiter turn, projection stop wins;
- if projection stop and timeout are present in the same arbiter turn, projection stop wins;
- if caller cancellation and resize/timeout are present in the same arbiter turn, cancellation wins unless projection stop also wins;
- if the first valid resize does not arrive before the `3_000 ms` deadline and no projection stop, caller cancellation, or valid resize wins the same arbiter turn, startup fails with `StartupGeometryUnavailable`;
- if `createVirtualDisplay(...)` was entered before timeout, `ScreenCaptureStartException.requiresFreshProjection` is `true` for this failure path;
- additional valid resize, metrics, or density signals that arrive after `AuthoritativeStartupGeometryReady` but before `InitialActivePlanCommitted` are held as pending runtime updates, not folded into the initial Active plan;
- a density-only metrics change after the snapshot freezes is a pending runtime geometry signal even when captured width and height are unchanged.

After `AuthoritativeStartupGeometryReady`, additional resize/metrics/density signals are no longer startup-success signals. They cannot prevent initial commit by themselves. If startup reaches `InitialActivePlanCommitted`, runtime receives those pending effective signals exactly once and must drain them before the first normal render scheduling tick. This may immediately replan to a newer `Running(Active)` state or suspend output if the newer geometry makes the current parameters impossible; it may also result in zero frames being produced from the frozen first Active plan.

Runtime behavior:

- every valid captured-content resize updates logical capture geometry after the initial Active commit;
- invalid sizes are ignored or fail according to lifecycle stage;
- density continues to come from the latest valid metrics-provider emission;
- geometry events are conflated and sequenced;
- if current parameters can no longer produce a valid output plan after resize or density change, the session enters `Running(output = Suspended(...))`, retires old latest output for new deliveries, keeps the same `VirtualDisplay` alive when safe, and attempts to keep the generation-owned projection target aligned with the new captured geometry;
- target resize failure suspends output when the projection and `VirtualDisplay` can safely remain alive; it fails terminally only when no safe target/rendering invariant remains.

`onCapturedContentVisibilityChanged(isVisible)` updates `Running.capturedContentVisible`. It does not suspend output, retire latest output, or create a frame/drop category by itself. During startup, the latest known visibility value at `InitialActivePlanCommitted` initializes `Running.capturedContentVisible`; `null` is used only if no visibility callback was observed. The visibility value used for initial state is not replayed again as a runtime visibility update, and no separate diagnostic event is emitted merely to restate the initial visibility value.

### VirtualDisplay and Generation-Owned Surface Ordering

The session creates at most one `VirtualDisplay`. Resizing/reconfiguration uses generation-controlled target changes, not second virtual displays. The virtual display is projection-level state; `Running(output = Active | Suspended)` is output-plan state. Suspending output does not by itself release or replace the virtual display.

Runtime target resize sequence is equivalent to:

1. validate target dimensions with checked `Long` arithmetic against config caps, GL limits, and `SurfaceTexture` constraints;
2. bump capture/surface generation and pause scheduling for the old generation;
3. prepare a new or resized generation-owned projection target `Surface`, normally backed by a GL `SurfaceTexture` whose default buffer size matches the target;
4. call `VirtualDisplay.resize(width, height, densityDpi)`;
5. call `VirtualDisplay.setSurface(newSurface)`;
6. retire the old `Surface` only after the virtual display no longer targets it and no GL/encoder/readback work references resources from the old generation;
7. resume scheduling only for the new current generation.

Pre-active authoritative target rebind uses the same ordering but with startup-specific rules:

- it is performed inside the startup transaction after `StartupGeometrySnapshot` has been frozen;
- it may retry once with a freshly created generation-owned target if a recoverable target creation or rebind failure occurs;
- it may continue using the bootstrap/provisional target only if that target already satisfies the frozen authoritative geometry and generation invariants;
- it must not publish frames, expose `Running(Suspended)`, or expose API 34+ provisional metrics dimensions as Active output;
- if it fails after bounded retry/fallback, startup rolls back with `requiresFreshProjection = true` because `createVirtualDisplay(...)` has already been entered.

Every generation-owned projection target, `SurfaceTexture`, projection `Surface`, `VirtualDisplay` target assignment, output plan, GL readback buffer/PBO lease, encoder result, public snapshot, delivery record, and published frame belongs to a generation. Stale-generation frame-available signals, render ticks, readbacks, encode completions, public snapshot preparations, and publications are discarded and counted as stale drops only after their relevant public/accounted attempt has been materialized; they are not backend failures.

## Capture Geometry and Output Planning

### Metrics Provider Lifecycle and Geometry Ownership

`CaptureMetricsProvider` is mandatory even on API 34+, because density and bootstrap geometry need an owner-context source. The public provider object is owner-owned and reusable. A session owns only its attachment to the provider.

Provider ownership rules:

- `CaptureMetricsProvider` does not expose public `close()` in this revision.
- The engine must not close or permanently invalidate a provider object supplied through `ScreenCaptureConfig`.
- A provider may be created before a session, reused across sessions, and outlive a session.
- Built-in providers may use an internal attach/detach contract so a session can observe metrics without owning the provider object.
- Built-in factory calls must be usable before `startSession(...)` and should not register long-lived platform listeners at construction time. Long-lived listeners/callbacks are registered lazily when a session attaches and are unregistered when that session stops, closes, fails startup, or otherwise detaches.
- If one built-in provider is attached by multiple sessions, listener registration is implementation-owned and ref-counted or otherwise shared safely.
- Custom providers expose only `StateFlow<CaptureMetrics>` to the engine. Any lifecycle cleanup for custom providers belongs to the provider owner, not the session.

Built-in provider factories are part of the design-complete runtime API. An internal bring-up milestone may implement caller-supplied providers first, but a shippable design-complete implementation must not expose built-in factory stubs that throw `NotImplementedError`, `UnsupportedOperationException`, or equivalent placeholder failures.

Provider factory intent:

- `fromActivity(activity)`: preferred for activity/window-owned capture. The provider is Activity/window-scoped; the owner must not treat it as an application-lifetime object.
- `fromUiContext(context)`: for UI-bound contexts where activity is not directly available. It has the same lifetime caution as other UI-context objects.
- `fromDisplay(baseContext, display)`: for explicit display-bound integrations. If the display disappears or becomes invalid, the provider may stop producing new valid metric changes; sessions use the last valid metrics until projection geometry or lifecycle says otherwise.
- `bestEffort(context)`: fallback for service/application contexts. It is the preferred built-in choice for long-lived foreground-service capture when no stable Activity/window owner is available, but it may be less accurate on old APIs, external displays, desktop/freeform modes, or non-standard launch flows.

Metric validity rules:

- width, height, and density must be positive;
- invalid startup metrics fail startup before callback attachment and projection consumption when possible;
- invalid running metrics are ignored, keep the last valid geometry, and emit `InvalidMetricsIgnored`;
- Activity destruction, display invalidation, or context lifetime end does not automatically stop a session. The owner remains responsible for app lifecycle policy;
- geometry events are conflated; no fixed debounce window is public contract;
- built-in provider buffering is latest-value only and bounded. A `StateFlow`-style latest metrics snapshot is sufficient; no unbounded listener/event buffer is allowed or required;
- geometry sequence increments only when effective width, height, density, or source changes.

On API 34+, `onCapturedContentResize(width, height)` is authoritative for captured-content width/height after projection startup. The metrics provider still supplies density and bootstrap validity. Invalid startup metrics fail before callback attachment and projection consumption whenever possible; the engine must not consume the projection while hoping that a later captured-content resize will repair missing or invalid bootstrap metrics.

### Startup Geometry Snapshot and Pending Runtime Signals

`StartupGeometrySnapshot` is an internal immutable value committed exactly once per successful startup, at `AuthoritativeStartupGeometryReady`.

Conceptual fields:

```text
StartupGeometrySnapshot {
    widthPx: Int
    heightPx: Int
    densityDpi: Int
    source: MetricsProvider | CapturedContentResize
    metricsSequence: Long
    capturedResizeSequence: Long? // API 34+ only
}
```

Snapshot rules:

- For API 34+, `widthPx` and `heightPx` come from the first valid captured-content resize that wins the startup arbiter. `densityDpi` comes from the latest valid metrics snapshot observed on the control context when the snapshot commits.
- For API 24-33, width, height, and density come from the latest valid metrics snapshot used to commit authoritative startup geometry.
- The first public `ScreenCaptureEffectiveParameters.captureGeometry`, capture target, output plan, and authoritative pre-active target rebind are derived from this frozen snapshot.
- Metrics, density, or resize inputs observed after the snapshot commits but before `InitialActivePlanCommitted` are not folded into the initial Active plan. They are detached at commit and processed as runtime geometry signals before the first normal render scheduling tick after startup success, unless terminal cleanup overtakes the session before runtime scheduling begins.
- Projection stop and caller cancellation can still abort startup before the final commit-return handoff.
- Captured-content visibility is separate from geometry. The initial public state uses the latest known visibility value at commit, or `null` only if no visibility callback was observed.

`PendingRuntimeSignals` is an internal startup bag for effective state observed after the frozen geometry snapshot but before public commit. Geometry and density portions are handed to runtime; visibility in the bag initializes the first public state and is not replayed as a runtime update.

Conceptual fields:

```text
PendingRuntimeSignals {
    latestGeometryOrResize: GeometrySignal?
    latestDensity: DensitySignal?
    latestVisibility: Boolean?
    maxObservedSequence: Long
}
```

Pending signal rules:

- The bag stores latest effective state, not every raw callback.
- Duplicate/intermediate resize, metrics, density, and visibility inputs may be conflated.
- Geometry and density signals in the bag are not applied to the frozen first Active plan.
- At `InitialActivePlanCommitted`, geometry and density pending signals are detached from the startup transaction and handed to the runtime controller exactly once.
- Runtime must drain the detached geometry/density signals before the first normal render scheduling tick unless a terminal transition preempts runtime, but this drain is not part of the non-cancellable commit-return path. The drain may update the Active plan or suspend output before any frame is produced from the frozen first Active plan.
- Latest pre-commit visibility initializes `capturedContentVisible` at commit and is not replayed as a second runtime update or standalone diagnostic event.
- A signal racing with commit is owned by either startup pending handoff or normal post-commit runtime delivery according to sequence/generation, never both.
- Pending geometry/density can make the frozen first plan obsolete immediately after commit, but it cannot mutate that first plan or prevent the initial Active commit by itself. Zero frames from the frozen first Active plan is a valid outcome when pending runtime signals are drained first.

### Parameter Validation

| Value | Accepted range |
| --- | --- |
| `CropInsetsPx.*` | integer `0..32768` |
| `ScaleFactor.factor` | finite `0.10..2.00` |
| `TargetSize.width`, `TargetSize.height` | integer `1..32768` |
| `FrameRate.MaxFps.fps` | integer `1..120` |
| `FrameRate.PeriodicRefresh.intervalMillis` | integer `1_000..300_000` |
| `FrameRate.Auto` | accepted as requested input and resolved to effective `FrameRate.MaxFps(30)` |
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
-> generation-owned projection target Surface, normally GL-backed SurfaceTexture, possibly downscaled from logical capture
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
- Capture target normally preserves logical captured-content aspect ratio. Aspect-changing output transforms are GL output work and are represented in the plan's render transform package rather than by changing logical coordinate semantics.
- Early downscale factor is computed from selected/cropped/oriented logical rect and final image size, but applied uniformly to whole logical captured content.
- A useful model is `requiredCaptureScale = min(1.0, max(finalImageWidth / orientedContentWidth, finalImageHeight / orientedContentHeight))`; implementations may use equivalent conservative heuristics.
- The engine must not enlarge capture target above logical capture only to support `ScaleFactor(>1.0)` or large `TargetSize`; upscale is final GL work.
- Capture target changes are generation changes and are reported through `CaptureTargetChanged` diagnostics and `ScreenCaptureEffectiveParameters.captureTarget`.

Runtime validation rejects or suspends plans when output dimensions, pixel counts, RGBA bytes, PBO bytes, bitmap bytes, encoded cap, GL texture/renderbuffer limits, or provider request values exceed supported limits.

## Scheduling, Publication, and Backpressure

### Frame-Rate Policy

- `MaxFps(fps)` renders at most `fps` from source frame availability.
- `PeriodicRefresh(intervalMillis)` ensures fresh publication for static sources at the requested interval, subject to backpressure and lifecycle. Sequence increments per published encoded image, not per visual change.
- `Auto` is accepted only as requested-parameter sugar. It resolves during planning to `MaxFps(30)` in this revision.
- `ScreenCaptureEffectiveParameters.frameRate` exposes the resolved concrete policy and never exposes `Auto`.
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
- When output suspension commits, the internal latest encoded frame is retired immediately for new delivery. The engine does not keep serving a frozen old frame while suspended, even if no replacement frame exists.

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

### Projection Target, SurfaceTexture, and OES Rules

The first complete runtime implementation uses a GL-backed `SurfaceTexture` as the projection target consumer. The `VirtualDisplay` renders into the Android `Surface` created from that `SurfaceTexture`; the GL pipeline samples the corresponding external OES texture.

Startup target rules:

- API 24-25 cannot rely on the detached `SurfaceTexture(singleBufferMode)` constructor. The engine must create a real minimal EGL context and a real OES/OpenGL texture object before constructing `SurfaceTexture(texName)`.
- The texture name passed to `SurfaceTexture(texName)` or `SurfaceTexture(texName, singleBufferMode)` must be a real texture object name generated for the current GL context. Passing `0`, a fake value, or an unrelated texture name is outside the design contract.
- API 26+ may use detached `SurfaceTexture(singleBufferMode)` and later `attachToGLContext(...)`, but this is an optional optimization. The implementation should prefer the same minimal EGL/OES target path across API 24+ unless there is a measured reason to split.
- `ImageReader`, `MediaRecorder`, or any other non-GL `Surface` consumer is not a production bootstrap path for this revision. Such consumers may be used only as temporary internal bring-up/test shims under the restrictions in [Projection Target Surface Contract](#projection-target-surface-contract).

Runtime GL rules:

- `SurfaceTexture` frame-available callbacks only enqueue work; they do not call GL.
- `SurfaceTexture.updateTexImage()` and `getTransformMatrix(...)` run only on the GL thread with the owning EGL context current.
- The OES transform matrix is applied for each consumed frame.
- `updateTexImage()` may skip intermediate producer frames; this is acceptable for latest-only semantics.
- `SurfaceTexture.setDefaultBufferSize(width, height)` is part of generation-owned target preparation and must use dimensions validated against engine caps and GL limits.
- Any projection target `Surface`, `SurfaceTexture`, OES, size, transform, or EGL ownership invariant failure maps to GL/surface problem kinds and may fail or suspend according to context. Startup rendering preparation distinguishes expected lifecycle-stale outcomes from real GL/device/resource failures and from GL/OES ownership invariants.

### RenderingPipelineReady Validation Boundary

`RenderingPipelineReady` is reached by deterministic resource/config validation for the frozen first Active plan under the owning `PlanPreparationToken`. The engine must not wait for MediaProjection to produce the first frame and must not require first real render/readback/encode/publication before returning the public session.

Required startup validation:

- EGL context creation and current-context ownership on the GL thread;
- GL-backed projection target ownership, OES texture validity, `SurfaceTexture` generation consistency, and separation between readiness validation and runtime frame-consumption authority;
- startup GL access boundary validation: the GL lane accepts preparation work, the expected target generation is current, the target is open, and the current-context/OES owner matches the preparing transaction;
- shader compile status, program link status, and required uniform/attribute lookup for only the configured initial plan;
- OES transform and source-sampling path configured for the render plan;
- output framebuffer/renderbuffer/texture allocation for the final image size;
- framebuffer completeness check for the output render target;
- a render/readback path that can produce top-to-bottom `Rgba8888SrgbOpaque` encoder input, including either a render-space inversion strategy or CPU row-normalization strategy;
- checked row stride, pixel count, RGBA byte count, and configured cap validation;
- selected readback resource allocation and shape checks;
- encoder instance creation on the isolated provider-preparation context within the bounded timeout, followed by request/info/cap compatibility validation.

The first ES2 implementation may require a validated 8-bit RGBA render target/FBO path. If no safe path can produce the required `Rgba8888SrgbOpaque` encoder input, startup fails with `GlResourceFailure`, `GlInitializationFailed`, or `ReadbackUnavailable` according to the failing layer. A future implementation may use another internal ES2-compatible render target format only if it converts to the unchanged top-to-bottom `Rgba8888SrgbOpaque` encoder-input contract before encoding.

#### Startup GL Access and Target Ownership Classification

Startup GL access is the boundary where the preparing transaction enters the GL lane and obtains current EGL/GL access to the generation-owned projection target for `RenderingPipelineReady`. This boundary is fenced by the same `PlanPreparationToken` as the rest of rendering preparation.

The engine must classify failures by what failed, not by the fact that they occurred near GL access:

| Failure | Classification |
| --- | --- |
| Token already stale at the first pre-allocation/pre-admission check | Lifecycle-stale outcome. No allocation/provider work starts. The owner arbiter maps the stale cause; this is not a GL resource failure. |
| EGL display/context creation fails, EGL context cannot be made current, or no usable EGL/current-context can be established | `GlInitializationFailed`. |
| GL lane is already marked abandoned/unhealthy before the preparation is admitted | `GlResourceFailure`. The lane is a known unavailable rendering resource. |
| GL lane unexpectedly rejects work while the token is current and no abandoned/unhealthy state was committed | `GlInvariantViolation` if the rejection violates GL-lane ownership/lifecycle invariants; otherwise `GlResourceFailure` for a known resource-unavailable state. |
| Projection target handle has the wrong owner while the token is current | `GlInvariantViolation`. |
| Projection target generation does not match the transaction/frozen plan while the token is current | `GlInvariantViolation`, unless a superseding lifecycle transition has already made the token stale. |
| Projection target is closed while the current token still expects it to be open | `GlInvariantViolation`, unless rollback/terminal invalidation already made the token stale. |
| Current `SurfaceTexture`/OES texture ownership or current-context binding does not match the expected generation/owner | `GlInvariantViolation`. |
| Shader compile/link, uniform/attribute lookup, FBO/render target allocation, or GL object preparation fails after a current context exists | `GlResourceFailure`, unless the failure is specifically an initial EGL/current-context establishment failure. |
| Readback allocation or row-normalization storage cannot be prepared for the selected path | `ReadbackUnavailable` or `GlResourceFailure` according to whether the failed layer is readback contract or GL storage. |
| Unexpected untyped exception during the GL access block | Best-known in-flight stage classification. Use `GlInitializationFailed` during EGL/current-context acquisition, `GlResourceFailure` during ordinary GL resource preparation, `GlInvariantViolation` for target/OES ownership assertions, and `InternalInvariantViolation` only for non-GL engine contract bugs such as missing transaction state or impossible ownership transfer. |

`GlInvariantViolation` is valid during startup `RenderingPipelineReady`. It is reserved for impossible GL/OES/current-context/target ownership or generation violations where the logical plan and requested parameters may be valid but the engine cannot safely trust the current GL target state. It is not limited to post-commit runtime failures. `InternalInvariantViolation` remains reserved for broader non-GL engine contract failures, rollback-barrier failure, double commit, missing required package after successful validation, or allowing a stale result to become public.

`STARTUP_GL_PREPARE_TIMEOUT_MS` uses best-known in-flight stage classification. Timeout during EGL/current-context/target-access acquisition maps to `GlInitializationFailed`. Timeout after the context is current and ES2 resource/FBO/shader/readback preparation has begun maps to `GlResourceFailure`, unless a more specific already-committed projection stop or caller cancellation wins arbitration. Generic unknown-stage timeout falls back to `GlResourceFailure`.

#### First-Plan Render Transform Package

`RenderingPipelineReady` must build and retain a first-plan render transform package for the frozen first Active plan. This package is immutable for that plan generation and is transferred to the runtime owner only through the same token-checked handoff as the GL/readback/encoder resources.

The package includes at least:

- the frozen logical content size and `CaptureTarget` size;
- `appliedSourceRect` in logical captured-content coordinates;
- the logical-to-capture-target mapping used for early-downscaled projection sampling;
- the static plan transform for source crop, output content-mode scale, rotation, mirror, final viewport/output size, and color mode;
- required uniform values, uniform locations, attribute bindings, and shader/program variant identity for the configured first plan;
- a dynamic OES transform slot and composition rule for the matrix obtained at runtime after each `SurfaceTexture.updateTexImage()`;
- the selected top-to-bottom normalization strategy for encoder input, such as render-space vertical inversion or CPU row flip/normalization;
- readback row stride, logical row ordering, and buffer-shape metadata needed to construct `ImageEncoderInput`.

The dynamic OES transform matrix value itself is not frozen at `RenderingPipelineReady`. It may change each time the texture image is updated, so runtime frame consumption obtains it after `updateTexImage()` and composes it with the retained static plan transform before drawing. `RenderingPipelineReady` validates that this dynamic matrix can be represented and composed by the selected program, not that a concrete matrix value is already available.

Before expensive ES2 allocation, readback-buffer allocation, or provider preparation, the transaction must run a cheap static-transform preflight subset against the frozen `StartupGeometrySnapshot` and requested parameters. This preflight rejects obviously impossible cases such as empty source/crop, non-finite scale or matrix components, invalid output dimensions, checked arithmetic overflow, and impossible row-stride or cap calculations. Full shader/program-specific transform validation may still complete later after ES2 metadata, program identity, and uniform/attribute locations exist. Performing ES2 readiness first and then discovering an impossible logical transform is allowed only for failures that could not be known without GL/program metadata; it must still roll back without public state.

Transform representability validation must check that the static first-plan transform package can be represented without executing the pipeline:

- source/crop/output geometry is non-empty and already accepted by output planning;
- all coordinates, scales, matrix entries, viewport values, and row-stride calculations are finite and within checked `Int`/`Float`/GL limits used by the implementation;
- required uniforms/attributes exist and can carry the selected transform values;
- the chosen row-normalization strategy can produce the provider contract: top-to-bottom `Rgba8888SrgbOpaque` rows;
- no runtime-only frame data is required to complete the static package.

Failure mapping:

- if user parameters or captured geometry cannot produce a valid logical transform, use `OutputPlanInvalid` or `OutputLimitsExceeded` according to the planning failure;
- if the logical plan is valid but the selected GL program, uniforms, framebuffer, or transform representation cannot support it, use `GlResourceFailure` or `GlInitializationFailed` according to the failing layer;
- if target/OES/current-context ownership or generation invariants fail while the token is current, use `GlInvariantViolation`;
- if the selected readback/normalization path cannot produce top-to-bottom `Rgba8888SrgbOpaque`, use `ReadbackUnavailable`;
- use `InternalInvariantViolation` only when a previously validated plan becomes internally inconsistent in a non-GL contract sense, such as a planner/renderer package missing after successful validation, double ownership transfer, stale result committed, rollback-barrier failure, or another impossible state outside the GL/OES target-invariant domain.

Synthetic GL self-tests are not required for startup readiness. Debug/internal diagnostics may perform bounded clear/render checks that do not become a public dependency. Pre-public startup must not consume MediaProjection content, call `SurfaceTexture.updateTexImage()`, call `glReadPixels`, invoke app-provided `ImageEncoder.encode(...)`, publish frames, or expose public state.

Only the shader/program variant required by the configured initial plan must be compiled and validated during startup. Initial `ColorMode.Original` validates the original-color path. Initial `ColorMode.Grayscale` is a supported public color mode in this revision and must validate a grayscale-capable ES2 shader/program path before `RenderingPipelineReady`; it must not be treated as `OutputPlanInvalid` merely because the implementation has not added the shader. If a design-complete implementation cannot compile/link/validate the grayscale path on a device, that is a GL resource failure for that startup. During an internal milestone that has not yet implemented grayscale, the public grayscale scenario must remain gated as incomplete rather than shipped as a misleading public rejection. Crop, rotation, mirror, and scale normally remain uniforms/math in the selected program rather than separate mandatory startup variants, but their concrete first-plan values must still be computed, validated for finite representability, and retained in the first-plan render transform package before `RenderingPipelineReady`. Future `setParameters(...)` prepares additional variants and transform packages transactionally when needed.

Startup GL/readback preparation is fenced by the plan-preparation token and guarded by `STARTUP_GL_PREPARE_TIMEOUT_MS`. If GL preparation times out before public commit, rollback invalidates the token, reports the best-known in-flight GL startup problem, abandons the GL lane/resource bag for this engine instance, and proceeds without waiting for the stuck GL lane.

### Readback

Readback selection is part of output planning and is reflected in `ScreenCaptureEffectiveParameters.readbackMode`. All readback modes must present encoder input in top-to-bottom logical row order with `ImageEncoderInputFormat.Rgba8888SrgbOpaque`. Any GL framebuffer bottom-row-first behavior is internal; the engine must render with an appropriate transform or CPU-flip/normalize rows before invoking the encoder.

ES2 baseline:

- Required on API 24+.
- A complete implementation may choose ES2 as the initial and only readback mode for the first runtime slice.
- When ES2 is selected, `RenderingPipelineReady` requires a validated path that can produce RGBA output, checked output buffer sizing, CPU readback storage allocation, and row/stride assumptions needed by the future real `glReadPixels` call.
- Startup may fail if no safe ES2 render/readback path can produce the required top-to-bottom `Rgba8888SrgbOpaque` encoder input.
- The first actual `glReadPixels` call may occur only after `InitialActivePlanCommitted`, pending runtime geometry/density have been drained as required, and a real frame/render opportunity exists.
- CPU RGBA buffer lifetime is owned by the engine and never exposed to normal consumers.

ES3/PBO accelerated path:

- Optional optimization/hardening path, not required for the first complete ES2 runtime slice.
- PBO selection is allowed only after implementation validation of PBO ring slots, fences, mapping, and safe lease retirement.
- Fence waits/map attempts are bounded; timeout means the readback slot is busy and the production opportunity is dropped as `byReadbackBusy`. It is not a hard backend failure by itself.
- GL-thread fence handling is non-blocking; any optional non-GL wait is short-bounded by implementation defaults.
- A mapped PBO range leased to encoder is not reused, unmapped, or freed until the encode task completes or the resource is retired according to safe-retirement policy.
- Stop/resize/provider change retires active leases and prevents stale results from publishing.
- If ES3/PBO validation fails before public commit but ES2 is valid, startup degrades one-way to ES2 and continues. The initial public effective parameters show `readbackMode = Es2`; no public diagnostic event is emitted for the pre-public fallback.
- If ES3/PBO was already active in a returned session and later hits a hard runtime invariant failure, the session degrades one-way to ES2 when safe fallback exists and publishes the normal runtime state/diagnostic update. If no safe fallback remains, running output suspends or fails according to lifecycle context.

### Encoder Provider Execution

Startup encoder readiness means the engine constructs `ImageEncoderRequest`, prepares an encoder through the isolated provider-preparation context, and validates the returned encoder before `InitialActivePlanCommitted`.

Mandatory readiness validation:

- request width, height, row stride, input format, and `maxEncodedBytes` match the frozen first Active plan;
- provider identity and output format are stable and compatible with requested parameters;
- `createEncoder(request)` returns an encoder instance before `ENCODER_CREATE_TIMEOUT_MS` expires;
- `encoder.info.providerId`, `encoder.info.outputFormat`, and diagnostic backend information are consistent with the selected provider and public output format;
- provider/request/cap declarations do not prove that producing any valid output under `maxEncodedBytes` is impossible.

`createEncoder(request)` is app/provider code and may block, throw, ignore interruption, or return late. The engine must not execute it on lifecycle-critical or producer-critical lanes. Startup and parameter-update preparation use this model:

```text
create ProviderPrepareRecord(transactionToken, request)
submit createEncoder(request) to ProviderPreparationContext
wait up to ENCODER_CREATE_TIMEOUT_MS without blocking the control, GL, callback, or encoder lanes
on success: validate returned encoder against the still-current transaction token
on timeout/failure/stale token: reject or rollback transaction and fence late results
```

Provider preparation classification:

- `ImageEncoderUnavailableException`, timeout, admission failure, or no returned encoder maps to `EncoderUnavailable`.
- Invalid or mismatched `ImageEncoderInfo`, incompatible output format, incompatible request/cap declarations, or another returned-unusable encoder state maps to `EncoderValidationFailed`.
- In startup after `createVirtualDisplay(...)` entry, both categories report `ScreenCaptureStartException.requiresFreshProjection = true`.
- During `setParameters(...)`, both categories reject the update and keep the previous committed plan active when the session is still running.

Timeout and rollback behavior:

- Startup timeout is `ENCODER_CREATE_TIMEOUT_MS = 3_000`.
- Timeout attempts best-effort cancellation or interruption of the provider-preparation work, but the engine does not rely on provider cooperation for lifecycle correctness.
- Caller cancellation, projection stop, owner stop, parameter rollback, or terminal failure marks in-flight provider-preparation records stale and proceeds through the normal logical rollback barrier without waiting for provider code to finish.
- A late encoder returned after timeout, rollback, projection stop, or terminal state is stale. It must be closed or discarded asynchronously through isolated provider cleanup and must not commit startup, replace an active plan, publish state, or change the primary failure/cancellation outcome.
- A late exception from stale provider work is internal diagnostic information only.
- If provider code remains permanently stuck, the worker may be quarantined and abandoned for the session. Abandoned workers must be bounded, never reused for engine work, and fenced so a late return cannot affect public state.

The engine must not call app-provided `ImageEncoder.encode(...)` with synthetic input before `InitialActivePlanCommitted`. Built-in encoders may perform bounded backend-specific validation such as native library/symbol availability, framework allocation compatibility, and request sanity checks, but synthetic encoding is not a required startup step.

Provider `encode(...)` receives `ImageEncoderInputFormat.Rgba8888SrgbOpaque`, width, height, top-to-bottom logical row order, row stride, and a borrowed `ByteBuffer` valid only during the call. It writes encoded bytes only through `EncodedImageSink`. If sink write returns `false`, the provider should stop and return `Failed(...)`; the engine maps this to encoded-size limit handling.

A provider must not retain input, output sink, row pointers, or GL resources after `encode(...)` returns. A provider may be closed or retired when plan changes, session stops, provider changes, or backend degrades. Provider `close()` can also execute app/provider code and must not block control, GL, main, MediaProjection callback, delivery, or active runtime encoder lanes.

A blocked or stuck active `encode(...)` call must not block `stop()` or `close()` from returning. The engine may mark the provider/backend unhealthy and retire resources, but raw/PBO/encoder resources still borrowed by an active synchronous `encode(...)` call are not reused until the call returns or the implementation can safely abandon the resource without reuse.

### Encoded Size Cap

`maxEncodedBytes` is enforced by `EncodedImageSink` and by a pre-publication encoded-attempt drop path. A frame that exceeds the cap must never become a publishable `EncodedImageFrame`.

Runtime behavior:

- If a real encode attempt would exceed `maxEncodedBytes`, the encoded result is not published and no delivery attempt is materialized for that production opportunity.
- The core records the result through a narrow pre-publication entrypoint equivalent to `recordEncodedAttemptDropped(generation, EncodedSizeLimitExceeded, attemptedByteCount)`. This updates counters and diagnostics atomically without constructing a public frame.
- The drop increments `droppedFrames.byEncodedSizeLimit` exactly once and may emit/coalesce an `EncodedFrameDropped` diagnostic with `EncodedSizeLimitExceeded`.
- `framesPublished` does not increment, the internal latest frame is not replaced, no public snapshot is prepared, and no frame delivery attempt is created.
- If the sink cap is hit before `ImageEncodeResult.Success`, `framesEncoded` does not increment.
- If encode succeeds but the generation is stale, `framesEncoded` may increment and the result is dropped as `byStaleGeneration`, not as encoded-size limit.
- If a provider returns `Success` after the sink rejected writes, the engine treats the attempt as `EncodedSizeLimitExceeded` and a provider protocol violation for diagnostics, but it still does not publish.
- The session remains `Running(output = Active(...))` by default. Repeated encoded-size-cap drops do not automatically suspend output in this revision because encoded size is content-dependent and a later frame may fit the cap without parameter changes.
- Integrators should reduce output size, lower encoder quality, or raise `maxEncodedBytes` when diagnostics show persistent cap drops.

Startup behavior:

- Startup may fail before first real encode only when request/provider/cap validation deterministically proves that no valid output can be produced under the configured cap.
- The engine must not perform synthetic app-provider encode merely to discover whether typical content fits `maxEncodedBytes`.

### Default JPEG Encoding

Default JPEG has a required framework baseline and an optional native future path.

- `FrameworkBitmapCompress` is the required built-in JPEG backend on API 24+.
- `JpegEncoderBackendPolicy.Auto` resolves to framework compression until a native backend is implemented, guarded, validated, and selected.
- `NdkAndroidBitmapCompress` may be added later as an optimization. Native backend load failure, missing symbols, validation failure, or serious runtime invariant violation must not break framework JPEG fallback when framework is available.
- `ImageEncoderInfo.backendName` must honestly report the selected backend, for example `FrameworkBitmapCompress` for the first framework-only implementation.
- Built-in framework JPEG accepts top-to-bottom `Rgba8888SrgbOpaque` input and padded rows where `rowStrideBytes >= width * 4`. It uses correctness-first RGBA-to-opaque-ARGB CPU normalization with `Bitmap.setPixels(...)` as the required baseline; direct `Bitmap.copyPixelsFromBuffer(...)` or native-layout copying is only a later validated optimization.
- JPEG quality is `0..100`. JPEG output is opaque; alpha is not preserved.
- Built-in JPEG validation before startup commit may check request compatibility, bitmap/config allocation compatibility, backend object creation, and native library/symbol availability when native exists. Built-in plan-scoped allocation failure maps to `AllocationFailed` for memory/resource exhaustion or `EncoderUnavailable` for backend preparation failure. Synthetic app-provider encode is never required for startup.
- Built-in JPEG must not reject legal tiny `maxEncodedBytes` values based on conservative encoded-size heuristics. Runtime over-cap output uses sink rejection and `byEncodedSizeLimit`; startup rejection is allowed only for deterministic cap impossibility.
- Repeated real encode failures after fallback policy is exhausted suspend running output or fail startup according to lifecycle context. Encoded-size cap failures use the encoded-size drop path rather than the repeated hard encode failure path.

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
- ES3/PBO validation failure before public commit or hard runtime invariant failure degrades to ES2 when ES2 is valid;
- native JPEG unavailable/invalid degrades to framework JPEG;
- stale or late provider-preparation result is closed/discarded without changing public outcome;
- callback-dispatcher saturation drops delivery for affected subscriptions only;
- callback throw drops one delivery and keeps the subscription active;
- first real and single transient readback/encode failure after public commit drops one production opportunity and remains runtime behavior;
- runtime encoded-size-cap exceed drops one production opportunity and keeps output Active by default;
- repeated runtime readback/encode hard failures suspend output after fallback policy is exhausted when projection and session invariants remain valid.

Startup failure examples:

- projection invalid, stopped, reused, or permission flow invalid;
- `createVirtualDisplay(...)` failure or null return;
- first API 34+ captured-content resize missing after the `3_000 ms` startup deadline;
- invalid startup metrics before projection callback attachment and projection consumption;
- projection target `Surface` preparation failure before startup commit, including API 24-25 minimal EGL/OES texture target creation failure;
- authoritative pre-active target creation or `VirtualDisplay` rebind failure after bounded retry/fallback;
- initial output plan exceeds configured caps or device limits;
- required ES2/GL/readback resource/config validation failure;
- encoder provider unavailable, validation failed, or timed out with no valid fallback;
- provider/request/cap validation deterministically proves before first real encode that no valid output can satisfy `maxEncodedBytes`.

Any startup failure after projection consumption sets `ScreenCaptureStartException.requiresFreshProjection = true`. Projection consumption starts when `createVirtualDisplay(...)` is entered, engine `MediaProjection.stop()` is invoked, or platform `onStop()` is observed. Callback registration alone is not projection consumption.

Running output suspension examples:

- active parameters become impossible after captured-content geometry change;
- projection target `Surface` resize/replacement cannot currently support output but the existing projection/`VirtualDisplay` can remain safely owned;
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
- `StartupGeometryUnavailable`: startup geometry was not available in time. On API 34+ first-resize timeout is post-`VirtualDisplay` creation and therefore requires fresh projection. Pre-geometry arbiter priority is projection stop, caller cancellation, first valid resize, timeout, then ordinary resource failure.
- `SurfaceCreateOrResizeFailed`: startup fails when no safe pre-active authoritative target exists after bounded retry/fallback; running output suspends only when the projection, `VirtualDisplay`, and existing generation can remain safely owned.
- `OutputPlanInvalid` / `OutputLimitsExceeded`: change parameters or caps. Use these for invalid logical geometry, crop, final size, output planning, or cap violations before GL representation is considered.
- `GlInitializationFailed`: use this when startup cannot establish a usable EGL/current-context or projection-target GL access environment.
- `GlResourceFailure`: use this when a logically valid first-plan transform cannot be represented by the selected GL resources, shader program, uniforms, framebuffer, or static transform package, or when a known-unhealthy GL lane/resource bag is unavailable.
- `GlInvariantViolation`: use this for impossible GL/OES/current-context/projection-target ownership or generation violations while the plan-preparation token is current. Do not use it for ordinary lifecycle-stale outcomes after rollback/cancellation/terminal invalidation.
- `ReadbackUnavailable`: use this when a logically valid render path cannot produce the required top-to-bottom `Rgba8888SrgbOpaque` encoder input or row-normalization contract.
- `ReadbackRepeatedFailure`: startup fails only if no required initial readback path can run. The first complete implementation may select ES2 and skip PBO entirely. Optional PBO validation failure degrades to ES2 when ES2 is valid. A first real readback failure after public commit is runtime behavior; running output suspends if projection remains alive and a later parameter/backend change may recover after fallback exhaustion.
- `EncodeRepeatedFailure`: startup fails if no initial encoder can be created and validated. First real encode failure after public commit is runtime behavior; running output suspends if projection remains alive and a later parameter/provider change may recover after fallback exhaustion.
- `FrameDeliveryFailed`: dispatcher/admission failure before callback admission; delivery problem only; session remains active unless another terminal problem occurs.
- `FrameCallbackThrew`: admitted callback invocation called user code and the callback threw; this is not dispatch failure; delivery problem only; session remains active unless another terminal problem occurs.
- `EncodedSizeLimitExceeded`: during runtime, provider output exceeded `maxEncodedBytes` for a real frame and the production opportunity is dropped without publication or automatic suspension. Reduce output size/quality or raise cap if this repeats. During startup, this problem kind is used only when cap impossibility is deterministic from request/provider validation before real encode.
- `EncoderUnavailable`: encoder provider could not produce a usable encoder, including creation timeout, provider-preparation admission failure, declared unavailability, or no returned encoder.
- `EncoderValidationFailed`: encoder was returned but request, info, output-format, or cap compatibility checks failed.
- `InternalInvariantViolation`: includes failure to establish the fast logical rollback barrier before throwing a startup exception, allowing a stale preparation result to commit, double commit/rollback breach, or an impossible non-GL inconsistency such as a missing first-plan transform package after successful `RenderingPipelineReady`. Heavy cleanup failure after the barrier is not enough by itself to rewrite the primary startup problem.

Diagnostic policy:

- `state` and `stats` are authoritative.
- `events` are best-effort, bounded, and rate-limited.
- Diagnostic event loss must not change lifecycle, parameter update results, frame publication, or delivery accounting.
- Repeated recoverable failures are coalesced so a broken device/backend/consumer does not flood the event flow.
- For failed startup, there is no public session event stream. Heavy cleanup failures are internal logging/test diagnostics; if they are observed before the exception is thrown, implementations may attach them as suppressed causes in debug/test builds, but the public problem kind remains the original startup failure unless the logical rollback barrier itself failed.

## Test Matrix

Platform coverage:

| Area | Required tests |
| --- | --- |
| API 24-25 | metrics-provider geometry, startup minimal EGL/OES projection target before `createVirtualDisplay(...)`, GL-backed `SurfaceTexture(texName)` with real generated texture name, ES2 readback, framework JPEG, lifecycle stop/close. |
| API 26-33 | metrics-provider geometry, optional detached `SurfaceTexture` bootstrap not required, ES2 readback, framework JPEG, lifecycle stop/close. |
| API 30+ | native JPEG backend availability, validation, fallback to framework. |
| API 34+ | fresh token/session, single `createVirtualDisplay`, mandatory callback before `VirtualDisplay` creation, non-null generation-owned startup `Surface`, bootstrap projection target from metrics, first `onCapturedContentResize`, immutable `StartupGeometrySnapshot`, `3_000 ms` startup timeout, startup arbiter priority `projection stop > caller cancellation > valid resize > timeout > ordinary resource failure`, captured-content visibility updates state without suspending output, app-window/full-display resize. |
| API 35+ | foreground-service mediaProjection restrictions, no BOOT_COMPLETED startup integration. |
| API 37 target | native loading notes, local-network bridge note, adaptive/large-screen geometry behavior. |

Scenario coverage:

| Area | Required tests |
| --- | --- |
| Validation/overflow | `32768x32768`, large `ScaleFactor`, large `TargetSize`, byte-count overflow, invalid caps, slot count edge values. |
| Metrics provider ownership | built-in factory construction does not leak long-lived listeners; session attach/detach on success, stop, close, startup failure; provider reuse across sessions; Activity destroyed/display invalid/running invalid metrics ignored; shippable design-complete runtime does not expose unimplemented built-in factory stubs. |
| Startup | valid start; no public partially started session; no public session identity before `InitialActivePlanCommitted`; last cancellable checkpoint before commit-return handoff; cancellation before commit rolls back; cancellation after `InitialActivePlanCommitted` does not roll back and the session is returned; no suspend/dispatcher hop between commit and return; invalid metrics fail before projection callback attachment/consumption; `ProjectionTargetReady` before `createVirtualDisplay(...)`; API 24-25 no fake/zero `SurfaceTexture` texture name; optional API 26+ detached `SurfaceTexture` path; ImageReader/non-GL target not shipped as normative runtime path; missing API34 first resize timeout; timeout and valid resize same startup arbiter turn; timeout and projection stop same startup arbiter turn; caller cancellation priority in startup arbiter; additional pre-active resize/metrics/density signals after `AuthoritativeStartupGeometryReady` become exactly-once pending runtime updates; latest pre-commit visibility initializes initial state without a standalone diagnostic event; `createVirtualDisplay(...)` returns null or throws; callback registration is not projection consumption; atomic pre-VD arbitration between raw `onStop()` and createVD entry; pre-VD failure after callback registration unregisters callback and reports `requiresFreshProjection = false`; platform `onStop()` before VD reports `ProjectionInvalidOrStopped` and `requiresFreshProjection = true`; post-`createVirtualDisplay(...)` GL/readback/encoder/target failure maps `requiresFreshProjection = true`; startup rollback invokes `MediaProjection.stop()` only after projection consumption; reused token/session. |
| Resize/generation | startup `StartupGeometrySnapshot` freeze; pending pre-active geometry/density does not mutate first Active plan; pending effective-state handoff not lost and not applied twice; runtime drains pending geometry/density before the first normal render scheduling tick, so zero frames may be produced from the frozen first plan; pre-active authoritative target rebind retry once with fresh generation target; provisional target fallback only when authoritative semantics are satisfied; resize while rendering/encoding; stale frame-available; stale readback; stale encode completion; projection target detach/release ordering; captured-content resize or density change that invalidates current output enters `Running(Suspended)` while keeping/re-targeting the same `VirtualDisplay` when safe; old latest retired on suspension. |
| Coordinates | Full/LeftHalf/RightHalf, odd widths, crop invalidation, AspectFit rounding, early downscale mapping, frozen logical `appliedSourceRect` to capture-target mapping used by the first-plan render transform package. |
| Frame rate | `MaxFps`; `PeriodicRefresh`; requested `Auto` resolves to effective `MaxFps(30)` and effective parameters never expose `Auto`. |
| Parameter transactions | prepare/validate/commit/rollback; commit-before-stop returns `Applied`; stop-before-commit returns `Rejected`; transaction resource bag cleanup; caller cancellation during update leaves no partial public plan. |
| GL/readback | minimal EGL/OES projection target; GL-backed `SurfaceTexture` ownership; startup GL access classification; `GlInvariantViolation` for current target/OES ownership or generation violations; stale token first-check returns lifecycle-stale; plan-preparation token invalidation; GL preparation watchdog timeout and GL-lane abandonment; `RenderingPipelineReady` resource/config validation without first real frame/readback probe; cheap static-transform preflight before expensive ES2/provider work; first-plan render transform package built and retained before runtime; dynamic OES matrix value deferred until per-frame `updateTexImage`; initial grayscale shader validation; no mandatory synthetic GL self-test; no pre-public `updateTexImage`/`glReadPixels`; ES2-only initial implementation path; 8-bit RGBA render/FBO validation or compatible conversion to top-to-bottom `Rgba8888SrgbOpaque`; ES3/PBO optional validation; PBO validation failure before public commit degrades to ES2 when valid; no public event for pre-public PBO fallback; fence timeout counts busy not hard failure; PBO fallback; PBO lease during stop/resize; repeated hard failure suspends running output after fallback exhaustion. |
| Encoder | built-in framework JPEG works for `ScreenCaptureParameters.defaults()`; framework JPEG supports padded rows; native backend may be deferred and `Auto` resolves to framework until implemented; startup encoder readiness uses isolated `createEncoder(request)` plus `ImageEncoderInfo`/request/cap validation; no synthetic encode for app-provided providers before public commit; startup create timeout; caller cancellation/projection stop while provider creation is running; late encoder return after rollback is closed/discarded; per-engine quarantined stuck provider-preparation worker limit; provider unavailable; provider throws; provider exceeds sink cap; pre-publication encoded-attempt drop entrypoint; encoded-size cap drops keep output Active by default; stuck active `encode(...)` does not block stop/close; provider change via `setParameters`; repeated hard failure suspends running output after fallback exhaustion. |
| Delivery | engine-owned default callback dispatcher; configured caller-owned callback dispatcher; engine-owned dispatcher saturation; caller-owned dispatcher pre-admission cancellation/rejection best-effort classification; no per-subscription dispatcher override; dispatch return not success; `AdmittedRunning` is the formal callback-start boundary; callback throws and increments `byCallbackThrew`; subscription cancelled inside callback; snapshot-slot exhaustion; slow-consumer consecutive problem/reset policy; slow subscription isolation. |
| Stop/close delivery cleanup | pending public-snapshot copy record registered before copy; pending copy invalidated by stop; in-progress copy finishes only as stale cleanup; not-admitted delivery lease retired before return; handed-off task token invalidated; late task exits without admission; admitted callback may finish; admitted callback throw after stop updates stats but suppresses new post-terminal diagnostic event. |
| Lifecycle races | `setParameters`, `stop`, `close`, `trimMemory`, subscription cancel, owner `MediaProjection.stop()`, external/system `onStop()`, platform `onStop()` echo after owner stop, raw `onStop()` racing `createVirtualDisplay(...)` entry, startup arbiter signal ordering, token invalidation before projection stop, projection stop vs caller cancellation vs resource failure priority, final commit-return handoff, post-commit stop before return becomes runtime terminal, exactly-once pending runtime signal transfer, startup rollback, frame publication, public snapshot preparation, callback admission, and callback dispatcher handoff racing. |
| Diagnostics/tunables | startup cleanup failure does not rewrite primary startup problem after logical rollback barrier; event buffer overflow drops diagnostics without changing state/stats; event rate limiting; pre-admission delivery watchdog; admitted callback stuck diagnostic; max active subscriptions. |
| Privacy/memory | stop/suspension invalidates latest for new delivery; stale public snapshot preparation cannot create public leases; trim under active leases; no raw RGBA to normal consumers; admitted callback lease not forcibly revoked. |
| Transport bridge | one engine subscription copies quickly into app-owned latest holder; slow transport clients do not block engine. |

## Implementation Tunables

This revision makes selected runtime constants normative because they affect observable lifecycle, accounting, tests, or memory/backpressure boundaries. They are not public configuration values in this revision. Implementations may expose internal/test-only injection points, but product API must not depend on them.

The engine-owned delivery coordinator queue is deliberately tied to the maximum active subscription count. It is not a frame queue and must not create per-consumer frame backlog; it bounds not-admitted per-subscription delivery tasks after public snapshot preparation has materialized delivery records. Pending publication signals and public snapshot copy records are latest-only/conflated session-owned preparation work, not frame queues.

### Normative Internal Runtime Defaults

| Value | Default / rule |
| --- | --- |
| API 34+ first captured-content resize startup timeout | `3_000 ms` after `createVirtualDisplay(...)` returns non-null, unless a valid resize was already observed. |
| API 34+ startup arbiter and tie-break | Before `AuthoritativeStartupGeometryReady`, projection stop wins over caller cancellation, valid resize, timeout, and ordinary resource failure. First valid resize wins over timeout if both are present in the same startup arbiter turn and no projection stop or caller cancellation wins. Timeout only enqueues a signal and commits only when neither projection stop, caller cancellation, nor valid resize has won the arbiter turn. |
| Startup projection target Surface | `createVirtualDisplay(...)` receives a non-null generation-owned projection target `Surface`. The first complete runtime target is GL-backed `SurfaceTexture`; API 24-25 require minimal EGL/OES texture creation before `SurfaceTexture(texName)`. Pre-active authoritative target rebind may retry once and may use provisional fallback only if authoritative semantics are satisfied. |
| Projection consumption boundary | Atomic `createVirtualDisplay(...)` entry, engine `MediaProjection.stop()` invocation, or observed `MediaProjection.Callback.onStop()`. Callback registration alone is attachment, not consumption; raw `onStop()` racing createVD entry is resolved by pre-VD atomic arbitration. |
| Startup rollback barrier | Before throwing or returning pre-commit cancellation, rollback must logically close gates, make callbacks inert or unregistered, detach resources from public paths, and invoke projection stop if projection was consumed. Heavy GL/native/platform cleanup may continue asynchronously; cleanup failures do not rewrite the primary startup problem unless the logical barrier itself fails. |
| Commit-return handoff | The last cancellable startup checkpoint precedes `InitialActivePlanCommitted`. After commit begins, no cancellable suspension, dispatcher hop, or blocking startup work is allowed before returning `ScreenCaptureSession`; later caller cancellation is normal session lifecycle, not startup rollback. |
| Runtime frame loop boundary | Public `startSession(...)` requires `RuntimeFrameLoopInstalled` before `InitialActivePlanCommitted`: the loop can accept real frame signals, drain pending geometry/density, apply frame-rate policy, schedule render/readback/encode/publication attempts, and report zero frames honestly. No first frame is required. Projection stop after commit begins is queued as runtime terminal work, not converted to startup failure. |
| API 34+ bootstrap, geometry snapshot, and pending handoff | Valid metrics-provider width/height/density create only a positive bootstrap projection target. No Active output is planned from provisional dimensions. `AuthoritativeStartupGeometryReady` freezes `StartupGeometrySnapshot`; later pre-active geometry/density inputs become exactly-once pending runtime signals and must be drained before the first normal render scheduling tick after commit. Latest pre-commit visibility initializes the first public state. |
| RenderingPipelineReady validation | Mandatory readiness is resource/config validation under a valid plan-preparation token: EGL/current GL context, projection target/OES ownership, startup GL access boundary checks, shader/program validity for the initial plan, initial grayscale shader path when requested, cheap static-transform preflight, first-plan transform package representability, output framebuffer completeness, top-to-bottom RGBA encoder-input capability, readback resource shape/allocation, and encoder request/info compatibility. No first real frame, concrete OES matrix value, `updateTexImage`, `glReadPixels`, encode, or publication is required. Optional synthetic GL diagnostics must not become startup dependency and must not consume projection content, call `glReadPixels`, or invoke app-provided `encode(...)`. |
| Initial readback implementation | ES2-only is a valid first implementation. Effective parameters show `readbackMode = Es2`. ES2 must validate a path that can provide top-to-bottom `Rgba8888SrgbOpaque` input. ES3/PBO is optional and may be added later without changing public API. |
| Pre-public PBO fallback | If ES3/PBO validation is attempted and fails before public commit while ES2 is valid, startup degrades one-way to ES2 and continues. No public event is emitted for that pre-public fallback; the initial effective parameters expose ES2. |
| Encoder readiness | Startup readiness calls `createEncoder(request)` on the isolated provider-preparation context and validates returned encoder info/request/cap compatibility. App-provided `encode(...)` is first called only after public Active commit and first real readback. |
| Encoder create timeout | `ENCODER_CREATE_TIMEOUT_MS = 3_000`. Timeout maps to `EncoderUnavailable`. Startup after projection consumption reports `requiresFreshProjection = true`; `setParameters(...)` rejects and keeps the previous committed plan. |
| Startup GL preparation timeout | `STARTUP_GL_PREPARE_TIMEOUT_MS = 5_000`. Timeout uses best-known in-flight stage classification: `GlInitializationFailed` during EGL/current-context/target-access acquisition, `GlResourceFailure` after ES2 resource/FBO/shader/readback preparation has begun, and `GlResourceFailure` as generic fallback. Startup after projection consumption reports `requiresFreshProjection = true`; the GL lane/resource bag is abandoned for the engine instance and not reused. |
| Plan-preparation token priority | At an uncommitted arbitration point, projection stop wins over caller cancellation, and caller cancellation wins over resource validation failure. A token already stale at the first pre-allocation check returns lifecycle-stale rather than a GL/resource problem. Token invalidation occurs before engine-initiated `MediaProjection.stop()` during rollback/owner stop. |
| Quarantined provider-preparation workers | `MAX_QUARANTINED_PROVIDER_WORKERS = 2` per `ScreenCaptureEngine` instance. Stuck provider creation workers may be abandoned and never reused; late results are fenced and discarded. Admission fails fast with `EncoderUnavailable` for that engine if the guardrail is exhausted. |
| Encoded-size cap lifecycle | Runtime real-encode cap exceed uses the pre-publication encoded-attempt drop entrypoint, increments `byEncodedSizeLimit`, leaves `framesPublished` unchanged, creates no public frame/snapshot/delivery, and keeps output Active by default. Partial sink bytes from failed attempts are transactionally discarded. Startup fails only when validation deterministically proves the cap impossible before first real encode; built-in JPEG must not reject legal caps by heuristic. |
| FrameRate.Auto resolution | Requested `Auto` resolves to effective `MaxFps(30)` in this revision. Effective parameters never expose `Auto`. |
| Maximum active frame subscriptions | `16` per session. `onFrame(...)` over this limit throws `IllegalStateException`. Direct engine subscriptions are intended for a small number of in-process consumers; transport fan-out should be implemented outside the engine. |
| Engine-owned callback dispatcher workers | `2` fixed workers for public callback bodies. |
| Engine-owned delivery coordinator queue | Capacity `16`, matching max active subscriptions. This is not a frame queue; it holds at most one not-admitted delivery task per subscription. Each subscription still has at most one queued/handed-off/`AdmittedRunning` delivery. |
| Configured caller-owned dispatcher failure attribution | Best-effort only before callback admission; strict admission accounting applies only to the engine-owned callback dispatcher path. |
| Pre-admission delivery start watchdog | `3_000 ms`. If a materialized per-subscription delivery has not reached `AdmittedRunning` by then, retire/release its not-admitted lease and classify as `byDispatchFailed` when non-terminal, or `byStaleSession` when terminal/stale. |
| `AdmittedRunning` callback stuck diagnostic | First diagnostic after `5_000 ms`, then coalesce/repeat no more often than every `30_000 ms` while still running. Do not revoke or reuse the borrowed snapshot lease. |
| Diagnostic event buffer | `32` entries. Diagnostic overflow drops oldest diagnostic events without changing state or stats. |
| Diagnostic event rate limit | First diagnostic event for a key is immediate; repeated diagnostics are limited to at most `1/sec` per `(eventType, problemKind)` key. Lifecycle state transitions are not delayed by diagnostic rate limits. |
| Readback hard-failure threshold | `3` consecutive hard failures per active generation/backend before fallback or output suspension. Fence timeout/busy is not a hard failure. |
| Encode hard-failure threshold | `3` consecutive hard failures per active plan/provider/backend before fallback or output suspension. Encoded-size cap failures use the encoded-size drop path and do not automatically suspend output. |
| Built-in JPEG baseline | Before public `startSession(...)` is usable, `ScreenCaptureParameters.defaults()` must work with framework JPEG. `JpegEncoderBackendPolicy.Auto` resolves to `FrameworkBitmapCompress` until native is implemented and validated. Built-in framework JPEG supports padded rows and uses correctness-first RGBA-to-opaque-ARGB `Bitmap.setPixels(...)` conversion as the required baseline. |
| Native JPEG backend failure | Load failure, validation failure, missing guarded symbol, or serious runtime invariant disables native JPEG for the current session and falls back to framework JPEG when possible. |

### Implementation-Defined Backend Tunables

The following remain implementation-defined internal constants because they are backend/performance choices rather than public contract:

| Value | Recommended initial value / rule |
| --- | --- |
| ES3/PBO ring size | `3` slots. |
| GL-thread fence wait | Non-blocking poll, effectively `0 ns`. |
| Optional non-GL PBO map/wait | At most `1 ms`. |
| ES3/PBO validation details | Implementation-defined when the PBO backend is implemented. A first ES2-only runtime may omit PBO validation entirely. When attempted, failures degrade/fail/suspend according to public lifecycle rules. |
| Early-downscale heuristic | Implementation-defined, provided public coordinate semantics and the no-unnecessary-projection-upscale rule are preserved. |
| Provider-preparation executor implementation | Implementation-defined, but must be isolated from control, main, GL, MediaProjection callback, delivery, callback, and active encoder lanes; must support bounded admission, timeout, stale-token fencing, and best-effort interruption/cancellation. |
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
- VirtualDisplay.Callback API reference: https://developer.android.com/reference/android/hardware/display/VirtualDisplay.Callback
- SurfaceTexture API reference: https://developer.android.com/reference/android/graphics/SurfaceTexture
- ImageReader API reference: https://developer.android.com/reference/android/media/ImageReader
- Bitmap and Bitmap.CompressFormat API references: https://developer.android.com/reference/android/graphics/Bitmap and https://developer.android.com/reference/android/graphics/Bitmap.CompressFormat
- Java/Android ByteBuffer API reference: https://developer.android.com/reference/java/nio/ByteBuffer
- Android NDK Bitmap API: https://developer.android.com/ndk/reference/group/bitmap
- NDK newer API usage / weak references: https://developer.android.com/ndk/guides/using-newer-apis
- Android 17 behavior changes: https://developer.android.com/about/versions/17/behavior-changes-17
- Android local network permission: https://developer.android.com/privacy-and-security/local-network-permission
- Android StateFlow and SharedFlow guide: https://developer.android.com/kotlin/flow/stateflow-and-sharedflow
- Kotlin CoroutineDispatcher API: https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/-coroutine-dispatcher/
- Kotlin CoroutineExceptionHandler API: https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/-coroutine-exception-handler/
- Kotlin asCoroutineDispatcher rejected-execution behavior: https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/as-coroutine-dispatcher.html
- Kotlin coroutine cancellation and `ensureActive`: https://kotlinlang.org/docs/cancellation-and-timeouts.html
- Kotlin `withTimeout` API: https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/with-timeout.html
- Kotlin `suspendCancellableCoroutine` API and resource-return cancellation behavior: https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/suspend-cancellable-coroutine.html
- Java `Future.cancel` API: https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/Future.html
- Android app screen sharing / MediaProjection resize callbacks: https://developer.android.com/about/versions/14/features/app-screen-sharing
- Khronos OpenGL ES `glCheckFramebufferStatus`: https://registry.khronos.org/OpenGL-Refpages/es3/html/glCheckFramebufferStatus.xhtml
- Khronos OpenGL ES `glLinkProgram`: https://registry.khronos.org/OpenGL-Refpages/es3/html/glLinkProgram.xhtml
- Khronos OpenGL ES `glReadPixels`: https://registry.khronos.org/OpenGL-Refpages/es3/html/glReadPixels.xhtml
- Khronos OpenGL ES `glMapBufferRange`: https://registry.khronos.org/OpenGL-Refpages/es3/html/glMapBufferRange.xhtml
- Khronos OpenGL ES sync objects: https://registry.khronos.org/OpenGL-Refpages/es3/html/glFenceSync.xhtml
