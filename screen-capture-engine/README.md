# ScreenStream Capture Engine

## Overview

ScreenStream Capture Engine is an embeddable Kotlin Android library that turns Android screen capture into JPEG images through an integrated graphics and encoding pipeline. Give it a [`MediaProjection`](https://developer.android.com/reference/android/media/projection/MediaProjection), Android's user-approved connection for screen capture, choose the image size and processing options, and receive complete JPEG frames ready for your app to save, analyze, or transport.

### Capture Control

- **Receive complete, self-describing JPEG frames.** Each delivered image includes its final dimensions and the exact output details used to produce it.
- **Change output without restarting capture.** Adjust the captured region, crop, image size, rotation, mirroring, color, frame rate, repeat interval, and JPEG quality while the session is running.
- **Choose the capture measurements.** Use dimensions and density from the current default display, a fixed display, or measurements supplied by your app.

### Performance and Memory Efficiency

- **Avoid unnecessary CPU and GPU work.** The engine keeps only the newest screen image waiting for processing and applies frame-rate limits before GPU pixel readback and JPEG encoding.
- **Process each fresh output in one GPU pass.** One OpenGL ES draw applies crop, rotation, mirroring, output sizing, and color or grayscale processing, then reads only the final-size pixels needed by the JPEG encoder.
- **Capture fewer pixels when possible.** On [API 32–37](https://developer.android.com/media/grow/media-projection#surface), a compatible full-source downscale can feed fewer pixels into the graphics path; see the [one final-size processing path](docs/architecture.md#one-final-size-processing-path).
- **Reuse compatible resources.** Capture targets, GPU resources, and encoding storage are reused across frames and setting changes when their size and format remain compatible, reducing repeated setup and allocation.
- **Keep callback delivery bounded.** Each session runs or schedules at most one frame callback for its current consumer at a time; if the app is still handling it, later delivery opportunities are counted as drops instead of building a memory-consuming queue.
- **Copy JPEG bytes only when requested.** Receiving a frame does not allocate an app-owned JPEG copy. `copyTo()` writes into app-provided storage, while `toByteArray()` creates an independent contiguous copy.
- **Reuse encoded JPEGs for repeat output.** When repeats are enabled, the engine can deliver the latest JPEG again without another capture, GPU readback, encode, or engine-side JPEG-byte copy.

### Built for Integration

- **Use a focused session API.** One `ScreenCaptureSession` represents one capture run and accepts live setting changes without requiring another session.
- **Observe capture as it runs.** Read-only Kotlin Flows report lifecycle state, cumulative frame statistics, and best-effort troubleshooting events.

```mermaid
flowchart TB
    Start["Your app<br/>starts capture"] --> Capture

    subgraph Engine["ScreenStream Capture Engine"]
        direction TB
        Capture["Capture"] --> GPU["GPU processing<br/>one transform pass"]
        GPU --> Encode["JPEG encoding<br/>Android Framework · optional Native"]
        Encode --> Deliver["Controlled delivery<br/>complete JPEG · one at a time"]
    end

    Deliver --> Value["JPEG frames ready for your app<br/>save · analyze · stream"]
```

Continue with [Usage](docs/usage.md) for integration steps and operational behavior, or explore [Architecture](docs/architecture.md) for the complete pipeline, responsibility boundaries, and performance design.

## Requirements

- The supported public API is Kotlin.
- The library supports Android API 24 through 37 and is currently compiled against SDK 37.
- Complete the [Android host prerequisites](docs/usage.md#android-host-prerequisites) before starting capture.

## Quick start

Create a session, register a frame consumer (the function called for each delivered JPEG), and start it with a fresh `MediaProjection`. To capture again after stopping, create a new session and obtain a new `MediaProjection`.

```kotlin
val session = ScreenCaptureEngine.createSession(context)

session.registerFrameConsumer { frame: EncodedImageFrame ->
    // frame contains one complete JPEG and its output details.
    // Read or copy it only inside this callback.
}

session.start(mediaProjection)

// When capture is no longer needed:
session.stop()
```

```mermaid
sequenceDiagram
    participant App as Your app
    participant Engine as ScreenStream Capture Engine
    App->>Engine: Create a session
    App->>Engine: Register for JPEG frames
    App->>Engine: Start with MediaProjection
    loop During capture
        Engine-->>App: Deliver a complete JPEG frame
        opt Settings change
            App->>Engine: Update image settings
        end
    end
    App->>Engine: Stop capture
```

### Work with JPEG frames

Each callback receives an `EncodedImageFrame`, an object containing one complete JPEG plus read-only output and timing details. The object is borrowed: read or copy it only inside that callback and on the same thread. If your app needs the JPEG bytes after the callback returns, copy them inside the callback with `copyTo()` or `toByteArray()`.

See [Detailed frame handling](docs/usage.md#work-with-jpeg-frames) for metadata, lifetime rules, and both copy strategies.

## Capture parameters

The defaults provide a ready path to complete JPEG output. Pass parameters at start or request updates while the same session is running.

| Parameter | Default | Purpose |
| --- | --- | --- |
| `sourceRegion` | `SourceRegion.Full` | Selects the source area to capture. |
| `crop` | `CropInsetsPx.ZERO` | Removes edges from the selected content. |
| `rotation` | `Rotation.Degrees0` | Rotates the image clockwise. |
| `mirror` | `Mirror.None` | Reflects the image after rotation. |
| `outputSize` | `OutputSize.ScaleFactor(0.5)` | Sets the final JPEG dimensions. |
| `colorMode` | `ColorMode.Color` | Selects color or grayscale output. |
| `frameRate` | `FrameRate.Auto` | Controls how often new JPEGs may be produced. |
| `frameRepeatInterval` | `null` | Optionally redelivers the latest JPEG after an interval with no output. |
| `jpegQuality` | `80` | Sets the JPEG encoder quality hint. |

See [All capture parameters and live updates](docs/usage.md#choose-and-update-capture-parameters) for every setting's available values, their processing order, live updates, and how to verify the settings applied to an output.

## Session configuration

Session configuration contains session-level choices fixed when the session is created, separate from capture parameters that can be updated while it runs.

| Option | Default | Purpose |
| --- | --- | --- |
| `captureMetricsSource` | `null` | Chooses where capture dimensions and density come from. |
| `jpegBackendPolicy` | `JpegBackendPolicy.Auto` | Controls whether the optional native JPEG encoder may be used. |

See [Session configuration](docs/usage.md#configure-session-wide-behavior) for display-source choices and JPEG backend policy.

## Monitor capture

A session exposes three read-only Kotlin Flows, which your app can collect to receive updates:

| Signal | Use it for |
| --- | --- |
| `session.state` | Shows current capture status and any requested or applied output details available in that state. Use it for app decisions. |
| `session.stats` | Accumulates frame counts, processing time, JPEG size, and frame-production or delivery-drop counts. |
| `session.diagnosticEvents` | Best-effort context for app logs, support reports, and troubleshooting. |

Use `state` to show the current capture status and respond when capture stops or fails, `stats` to display or collect performance measurements, and `diagnosticEvents` to investigate individual notable events. These Flows update independently, so do not combine their latest values as if they formed one synchronized snapshot. See [Monitoring details](docs/usage.md#monitor-capture) for state values, fields, and collection behavior.

## Behavior and responsibilities

### What the engine provides

- Every delivered frame is one complete, fully opaque, top-down SDR/sRGB JPEG together with the settings and dimensions actually used to produce it.
- Out-of-range parameter values are rejected. Requests that cannot produce valid output are reported through startup failure or session state rather than silently changed.

### What your app owns

- Complete the Android host requirements, then use a fresh `MediaProjection` and a new `ScreenCaptureSession` for each capture run.
- Use an `EncodedImageFrame` only inside its callback. Copy its bytes before returning if your app must retain them or send them to another thread.
- Source selection and crop choose the intended image area. Your app decides who may access delivered JPEGs and how they are transported, stored, retained, and deleted.

### Practical limits

- Capture, frame pacing, and repeat delivery are best effort rather than realtime guarantees. Explicit frame-rate controls set limits or sampling policies, not promised delivery rates.
- A callback already running may continue after settings change or `stop()` is requested. If your app must wait for that callback, wait for the frame consumer's `unregister()` call to complete successfully before calling `stop()`.

See [Detailed usage](docs/usage.md) for integration steps, app responsibilities, and operational limits.
