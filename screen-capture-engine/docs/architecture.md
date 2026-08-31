[README](../README.md) · [Usage](usage.md) · Architecture

# Architecture

ScreenStream Capture Engine is organized around one capture run that turns Android screen content into complete JPEG frames. This document explains the boundaries inside that run, why they exist, and how they shape the behavior visible to an app. [Usage](usage.md) provides the corresponding integration steps, setting choices, and frame-handling procedures.

## Contents

- [Capture run model](#capture-run-model)
  - [Host and engine boundaries](#host-and-engine-boundaries)
  - [One session, one run](#one-session-one-run)
- [Android capture and JPEG pipeline](#android-capture-and-jpeg-pipeline)
  - [From MediaProjection to complete JPEG](#from-mediaprojection-to-complete-jpeg)
  - [Capture size and density](#capture-size-and-density)
  - [JPEG backend seam](#jpeg-backend-seam)
- [Session lifecycle](#session-lifecycle)
  - [Public phases](#public-phases)
  - [Recoverable pauses and final outcomes](#recoverable-pauses-and-final-outcomes)
  - [Run outcome and resource release](#run-outcome-and-resource-release)
- [Requested and applied output](#requested-and-applied-output)
  - [Why output is resolved](#why-output-is-resolved)
  - [How changes reach delivered frames](#how-changes-reach-delivered-frames)
- [Frame ownership and bounded delivery](#frame-ownership-and-bounded-delivery)
  - [Borrowed frames and app-owned bytes](#borrowed-frames-and-app-owned-bytes)
  - [Backpressure and reusable JPEGs](#backpressure-and-reusable-jpegs)
- [Observation model](#observation-model)
  - [Three signals, three roles](#three-signals-three-roles)
  - [Independent timelines](#independent-timelines)
- [Performance and memory design](#performance-and-memory-design)
  - [Avoid expensive work early](#avoid-expensive-work-early)
  - [One final-size processing path](#one-final-size-processing-path)
  - [Reuse resources and encoded data](#reuse-resources-and-encoded-data)

## Capture run model

### Host and engine boundaries

The host application establishes Android screen-capture access. It obtains user consent and a [`MediaProjection`](https://developer.android.com/media/grow/media-projection), the Android object representing access to the display or app window selected by the user. It also maintains the required [`mediaProjection` foreground-service context](https://developer.android.com/develop/background-work/services/fgs/service-types#media-projection) while capture can run.

The host also decides who may receive copied JPEG data and how those copies are transported, stored, retained, and deleted.

The app requests image settings, and the engine combines them with the captured width, height, and density. The resulting `applied output` is the exact settings and resolved geometry used for a usable JPEG path. The engine owns the capture objects attached to the run, manages graphics and JPEG resources, and delivers complete frames. The host therefore controls platform access and downstream data policy while the engine produces consistent JPEG output.

### One session, one run

A `ScreenCaptureSession`, usually called a session, is the public object for one capture run. It joins capture, live image-setting changes, frame delivery, observations, and the final outcome so every frame and state belongs to the same run.

Each run uses a fresh session and a fresh `MediaProjection`, matching Android's [one-use projection consent model](https://developer.android.com/media/grow/media-projection#user-consent). Once the run begins, the engine manages that projection and its capture resources until the run ends.

Screen-capture access moves from host setup into the session, while JPEG data stays engine-owned until the app explicitly creates a retained copy:

```mermaid
flowchart TB
    Host["Host-managed capture access<br/>consent · MediaProjection"] --> Session
    Session["Session-managed capture run<br/>capture · processing · delivery"] --> Payload
    Payload["Engine-owned complete JPEG<br/>+ applied output"] --> Frame
    Frame["Borrowed EncodedImageFrame<br/>during one callback"] --> Use
    Use["Read metadata<br/>during callback"]
    Frame --> Copy["copyTo() or toByteArray()<br/>during callback"]
    Copy --> Owned["App-owned JPEG bytes"]
```

The app reads metadata directly from the borrowed frame during the callback. To copy JPEG bytes during that callback, `copyTo()` writes into caller-owned storage, while `toByteArray()` creates a new app-owned array. A byte copy is unnecessary when the app needs the metadata but not the JPEG payload.

## Android capture and JPEG pipeline

### From MediaProjection to complete JPEG

Android projects the selected display or app-window content by calling [`MediaProjection.createVirtualDisplay()`](https://developer.android.com/reference/kotlin/android/media/projection/MediaProjection#createvirtualdisplay). The engine creates that virtual display at the current source dimensions and directs it to an engine-provided [`Surface`](https://developer.android.com/reference/kotlin/android/view/Surface). That `Surface` is backed by a [`SurfaceTexture`](https://developer.android.com/reference/kotlin/android/graphics/SurfaceTexture), which exposes captured images as an OpenGL ES texture.

The engine keeps the newest waiting image, combines spatial and color operations in one [OpenGL ES draw](https://developer.android.com/reference/kotlin/android/opengl/GLES20#gldrawarrays), and [reads back pixels](https://developer.android.com/reference/kotlin/android/opengl/GLES20#glreadpixels) at the final JPEG dimensions. A JPEG backend encodes those pixels, and the complete image plus its applied output description becomes one immutable frame for bounded callback delivery.

```mermaid
flowchart TB
    App["App supplies capture access<br/>and image settings"] --> Session

    subgraph Engine["ScreenStream Capture Engine"]
        direction TB
        Session["ScreenCaptureSession<br/>lifecycle + output request"] --> Resolve["Resolve applied output"]
        Resolve --> Capture["MediaProjection<br/>source-sized VirtualDisplay → Surface"]
        Capture --> Source["Newest waiting<br/>screen image"]
        Source --> GPU["OpenGL ES<br/>transform + final-size readback"]
        GPU --> Encode["JPEG encoding<br/>Framework or optional Native"]
        Encode --> Deliver["Bounded delivery<br/>one complete frame at a time"]
    end

    Deliver --> Callback["App callback<br/>borrowed EncodedImageFrame"]
```

Each stage passes forward one defined result: source geometry, applied output, processed pixels, complete JPEG bytes, then the callback-scoped frame. Only the complete encoded result reaches public delivery.

### Capture size and density

`CaptureMetricsSource` supplies the width, height, and density used for capture geometry. It can follow the current display or another host-selected source; the user-approved `MediaProjection` supplies the screen content.

On API 24–33, the selected metrics source supplies all three geometry values. On API 34–37, those initial dimensions are provisional: startup and frame admission wait for Android's first valid authoritative [`onCapturedContentResize()`](https://developer.android.com/reference/android/media/projection/MediaProjection.Callback#onCapturedContentResize(int,%20int)) width and height, while the selected source continues to supply density. The selected content can therefore differ from the host display dimensions while density continues to come from the metrics source.

When dimensions or density change, the session resolves new output before fresh JPEG production continues.

### JPEG backend seam

JPEG encoding sits behind a stable frame boundary. The Framework path uses [`Bitmap.compress()`](https://developer.android.com/reference/kotlin/android/graphics/Bitmap#compress) with `Bitmap.CompressFormat.JPEG`. The optional Native path uses NDK [`AndroidBitmap_compress()`](https://developer.android.com/ndk/reference/group/bitmap#androidbitmap_compress), available from API 30. `JpegBackendPolicy.Auto` selects Native only after its library load and capability checks succeed. An exact `UnsatisfiedLinkError` or `SecurityException` from initial library loading, or an unsupported compressor, selects Framework. Other Native failures retain their ordinary containment or propagation semantics. If Native later rejects compression in the one form the engine can handle safely, that attempt produces no JPEG and is not retried with Framework; later frames use Framework. `JpegBackendPolicy.FrameworkOnly` uses Framework exclusively.

Both choices produce the same public kind of complete JPEG frame and preserve the same geometry, ownership, delivery, observation, and problem meanings.

## Session lifecycle

### Public phases

`NotStarted` is the initial state. `Starting` covers creation of the first usable capture path. `Active` means the session has applied output that can produce JPEGs. Setting or geometry changes can enter `Reconfiguring`; a recoverable problem can enter `Suspended`. `Stopped` and `Failed` are permanent outcomes.

The state names in this diagram are exact public names. The arrows summarize representative phase changes so the overall model remains readable:

```mermaid
stateDiagram-v2
    [*] --> NotStarted
    NotStarted --> Starting
    NotStarted --> Stopped
    Starting --> Active
    Starting --> Stopped
    Starting --> Failed
    Active --> Reconfiguring
    Reconfiguring --> Active
    Active --> Suspended
    Reconfiguring --> Suspended
    Suspended --> Active
    Active --> Stopped
    Active --> Failed
    Reconfiguring --> Stopped
    Reconfiguring --> Failed
    Suspended --> Stopped
    Suspended --> Failed
```

Lifecycle state describes the run; frames describe individual JPEG outputs. `Active` establishes usable output, while a delivered frame establishes one produced JPEG.

### Recoverable pauses and final outcomes

`Reconfiguring` is a planned pause while the engine prepares output for changed settings or geometry. `Suspended` represents a recoverable problem after the session has become active. A changed parameter request or restored or changed capture geometry can trigger another attempt. The session returns to `Active` only after it prepares usable output.

`Stopped` and `Failed` finish the session permanently. `Stopped` describes a normal app-requested end or Android ending the projection. Android reports that platform event through [`MediaProjection.Callback.onStop()`](https://developer.android.com/reference/kotlin/android/media/projection/MediaProjection.Callback#onstop), and the session exposes it as a stable stop reason. `Failed` carries the stable problem category when the engine cannot continue. Apps can respond to these outcomes independently of platform exception text or diagnostic messages.

### Run outcome and resource release

As the run ends, the session closes new capture and delivery work and manages release of its projection, virtual display, surface, graphics, and encoding resources. `state`, `stats`, and `diagnosticEvents` remain available for the lifetime of the session object.

`Stopped` or `Failed` gives the final public outcome of the capture run. A frame callback that has already begun may still return afterward, and Android and graphics resources may finish releasing in the background.

## Requested and applied output

### Why output is resolved

`ScreenCaptureParameters` describes the output requested by the app. The engine must combine that request with the actual capture width, height, and density before it knows the exact source area and final JPEG size. The resulting `ScreenCaptureEffectiveParameters` is the public description of that applied output.

A request can resolve to different concrete geometry after rotation, display changes, or app-window selection. A request that cannot currently produce output becomes a clear lifecycle problem while retaining its requested value.

### How changes reach delivered frames

`updateParameters()` records the newest request without waiting for reconfiguration or frame production. `ScreenCaptureState.Active.effectiveParameters` describes the applied session output, while `EncodedImageFrame.effectiveParameters` describes the exact output for one JPEG. The session resolves a live change against current capture geometry and reuses compatible resources or prepares replacements. `Reconfiguring` makes any required pause visible until new output is ready.

Work that has already started keeps the applied description with which it began. If a newer request or geometry change makes that result obsolete before delivery, the result is discarded. It is never relabeled with newer settings. The next fresh frame therefore describes one coherent combination of source geometry, requested settings, and JPEG size.

This permits asynchronous changes without mixing the meaning of adjacent frames.

## Frame ownership and bounded delivery

### Borrowed frames and app-owned bytes

The encoder finishes a complete JPEG before delivery. The engine retains it as an immutable payload with its effective output, sequence, and output timestamp. `EncodedImageFrame` is the temporary public view during one callback.

The frame is borrowed: its properties and byte-copy operations are available only inside the callback that receives it and on that callback's thread. The callback boundary defines when borrowed app access ends; the lifetime of engine storage remains behind that boundary. Bytes copied during the callback are app-owned and can outlive it.

Callbacks for one consumer are serialized on engine-provided worker execution rather than the caller or UI thread. The engine does not promise the same physical thread for later callbacks. UI or other longer-lived work therefore receives app-owned bytes copied before the callback returns, not the borrowed frame.

The app chooses how many copied JPEGs to retain, when to release them, and how they enter storage or transport.

### Backpressure and reusable JPEGs

Backpressure controls input that arrives faster than the engine and app can consume it. A newer source image replaces the older waiting image. For the current consumer, the session keeps at most one callback running or waiting to begin. A later opportunity while that callback is busy becomes an observable delivery drop instead of backlog.

The latest complete immutable JPEG can be reused for later delivery or configured repeats without another capture, readback, or encode.

Newest-image selection and one-at-a-time delivery bound waiting work at both ends of the pipeline and expose pressure through statistics.

## Observation model

### Three signals, three roles

Each session exposes three read-only signals because lifecycle, cumulative measurements, and troubleshooting context have different roles.

| Signal | Why it is separate |
| --- | --- |
| `session.state` | Describes the current lifecycle phase, applied output, recoverable pause, or final run outcome. It is the signal for app behavior. |
| `session.stats` | Accumulates frame, drop, processing-time, and JPEG-size measurements across the run without turning each measurement into a lifecycle change. |
| `session.diagnosticEvents` | Adds optional context about individual notable events for logs and support reports while lifecycle meaning remains in `state`. |

The running states can also carry Android's latest captured-content visibility observation when the platform supplies one through [`onCapturedContentVisibilityChanged()`](https://developer.android.com/reference/kotlin/android/media/projection/MediaProjection.Callback#oncapturedcontentvisibilitychanged). Visibility is informational context alongside the applied output and any session problem.

### Independent timelines

`state` and `stats` each retain an internally consistent latest value. `diagnosticEvents` publishes best-effort events to observers present when they occur. Their independence lets lifecycle progress while statistics or diagnostics move at a different pace.

Latest values from different signals do not form one synchronized snapshot: state can be newer than statistics, and diagnostic context can be absent while both continue. Each published value remains internally consistent.

## Performance and memory design

### Avoid expensive work early

The pipeline removes obsolete opportunities before expensive stages. While production is busy, the newest source image replaces the older waiting one. Frame-rate policy is evaluated before GPU readback and JPEG encoding, concentrating CPU and GPU work on images still eligible for output.

Only one fresh image proceeds through readback and encoding at a time. Parameter and geometry changes can discard an obsolete result before delivery, preventing outdated work from multiplying across the pipeline.

### One final-size processing path

One OpenGL ES draw combines source selection, crop, rotation, mirroring, output sizing, and color conversion. Rendering at final output dimensions means JPEG encoding reads only those pixels, avoiding full-size intermediate images for individual transforms.

On API 32–37, Android can [scale captured content into the supplied surface while preserving its aspect ratio](https://developer.android.com/media/grow/media-projection#surface). For a compatible full-source, same-aspect downscale, the engine uses that platform behavior and supplies smaller buffer dimensions through [`SurfaceTexture.setDefaultBufferSize()`](https://developer.android.com/reference/kotlin/android/graphics/SurfaceTexture#setdefaultbuffersize). The `VirtualDisplay` remains source-sized. This reduces the number of pixels entering the graphics path while preserving the source geometry in the output description.

### Reuse resources and encoded data

Compatible capture targets, graphics objects, pixel buffers, and JPEG resources remain reusable across frames and live changes, avoiding repeated setup and allocation.

JPEG output is retained as an immutable encoded payload that can be published and reused directly. `copyTo()` can fill an app-provided destination, while `toByteArray()` creates a contiguous app-owned array only when requested. Repeats reuse the latest payload, avoiding capture, GPU readback, JPEG encoding, and an engine-side payload copy.

The same design limits queued work and avoidable retention: the engine processes one fresh frame at a time, only the newest source image waits, and at most one callback is running or waiting for the current consumer. Less obsolete work also means fewer avoidable retained buffers and encoded results.
