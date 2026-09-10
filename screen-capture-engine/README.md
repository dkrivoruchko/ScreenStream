# ScreenStream Capture Engine

ScreenStream Capture Engine is a Kotlin SDK for Android screen capture and image processing. It turns screen content into complete JPEG images, ready to save, analyze, or send to another device.

It handles capture and image processing while giving your app control over the result:

- **Ready-to-use JPEGs.** Complete images arrive with matching settings and dimensions for storage, analysis, or transport.
- **Flexible image output.** Choose the framing, orientation, size, color, and JPEG quality your application needs.
- **Live control.** Change image and timing settings without restarting capture.
- **Efficient image processing.** Image transforms share one GPU pass. Only the final-size pixels are then copied back for JPEG encoding.
- **Less wasted work.** The SDK checks whether another frame is due and selects the newest waiting image, reducing unnecessary processing.
- **Reuse across frames.** Graphics and encoding resources are reused when settings allow. A newly registered consumer can receive the latest compatible JPEG without a new capture or encode.
- **Bounded frame delivery.** Slow frame callbacks do not build an SDK delivery backlog. JPEGs can be copied into new or reusable app storage.
- **Resource management and monitoring.** The SDK coordinates capture resources and exposes lifecycle state, statistics, and diagnostics so your app can respond.

Read the guides:

- [Usage](docs/usage.md) — how to integrate and use the SDK, with examples and the complete public API contract.
- [Architecture](docs/architecture.md) — how the SDK works and why it is designed this way.

## Quick start

```kotlin
suspend fun captureScreen(
    context: Context,
    mediaProjection: MediaProjection,
    onJpeg: (ByteArray) -> Unit,
) {
    val session = ScreenCaptureEngine.createSession(context, mediaProjection)
    try {
        session.registerFrameConsumer { frame ->
            // Runs on an engine worker; keep onJpeg nonblocking.
            onJpeg(frame.toByteArray()) // An owned copy the app can keep.
        }
        session.start()
        // Wait for capture to end; cancel this coroutine to request stop.
        session.state.first {
            it is ScreenCaptureState.Stopped || it is ScreenCaptureState.Failed
        }
    } finally {
        withContext(NonCancellable) {
            session.stop()
        }
    }
}
```

See the [complete integration example](docs/usage.md#complete-lifecycle-integration) for error handling and registration cleanup.

## What your app owns

Your app obtains Android capture authority and a `MediaProjection` for the selected screen or, where supported, app window. Before creating a session, meet Android's permission and foreground-service requirements. The SDK does not provide that host setup; follow the [Android host requirements](docs/usage.md#prepare-the-android-host).

After `createSession()` succeeds, the SDK owns the projection. Your app controls the run through its session: register a JPEG consumer, start with the desired image settings, update those settings, and observe state and statistics. Each session supports one run. When it is no longer needed, await `session.stop()`; the [lifecycle guide](docs/usage.md#run-a-capture-session) covers startup, cancellation, consumer cleanup, and stopping from a context that cannot suspend.

Your app decides how to use, buffer, protect, and retain the JPEG copies. Audio capture, video encoding, storage, and transport are outside the SDK's scope.

Supports Android API 24 and later. The current SDK build uses Kotlin 2.4 and `compileSdk` 37.

## Capture capabilities

### Image control

**Composition.** Select the full source, its left half, or its right half, then crop, rotate, and mirror the image. Choose color or grayscale output and set JPEG quality. [Image sizing](docs/usage.md#image-and-geometry) can scale the result by a factor, fit it inside bounds without padding, or stretch it to exact dimensions.

**Live changes.** [Update settings](docs/usage.md#change-capture-parameters) without restarting the session. Each frame includes its own settings, captured area, and final dimensions, so the bytes stay paired with the right metadata as capture changes.

### Processing and delivery

**Work per image.** The [GPU pipeline](docs/architecture.md#one-final-size-processing-path) combines cropping, sizing, rotation, mirroring, and color processing in one pass. It then copies only those final-size pixels from the GPU for JPEG encoding. Graphics and encoding resources are reused while they remain suitable for the current settings. [JPEG encoding](docs/usage.md#session-configuration) supports the Android framework encoder and an optional native compressor.

**Output timing.** [Timing controls](docs/usage.md#frame-timing) let you process images as they become available, cap the output rate, or sample less often. The engine checks whether a frame is due before copying pixels from the GPU and encoding it, and keeps the newest waiting image.

**Frame handoff.** A session supports at most one current frame consumer, registered with `registerFrameConsumer()`. The frame object passed to its callback is a temporary SDK-owned view: read its metadata or [copy its JPEG bytes](docs/usage.md#handle-jpeg-frames) only while that callback is running and on the same thread. The copy can use a new array or your own reusable buffer.

For that registration, at most one callback can be running or waiting to run. Later delivery opportunities are skipped while it is busy, rather than queued. Your app controls any queue it creates with the copied bytes.

### Monitoring and recovery

**Run state.** Follow capture through active operation, settings changes, pauses, and completion with a Kotlin Flow. When a recoverable problem pauses new output, the session enters `Suspended` and remains available for recovery. It may resume after settings or capture conditions change. Resubmitting the current desired settings with `updateParameters()` can also [request reevaluation](docs/usage.md#retry-suspended-capture). This does not guarantee that another capture attempt will start or succeed, and the SDK does not retry continuously.

**Output health.** [Statistics](docs/usage.md#monitor-capture) report session totals for produced frames and production or delivery drops. They also show average output rate, processing times, and JPEG size, plus the latest JPEG size. Diagnostics add troubleshooting context; state and the SDK's stable problem categories guide [error handling and recovery](docs/usage.md#handle-errors-and-recovery).

## Practical limits

- Capture timing is best effort; frames may be delayed or skipped.
- JPEG output assumes standard dynamic range (SDR) and sRGB colors. Faithful color reproduction and conversion from high dynamic range (HDR) are not guaranteed. Captured frames explicitly identified as Display P3 are rejected; see the [color limits](docs/usage.md#color-assumptions-and-limits).
- Android may omit protected content, including windows marked `FLAG_SECURE`.
- A callback already scheduled before a settings change can still deliver its older image. Use that frame's own metadata.
- A terminal state does not prove that a running callback has returned or that all Android and graphics resources have finished releasing.
