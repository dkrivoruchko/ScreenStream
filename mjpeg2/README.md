# ScreenStream MJPEG

MJPEG is ScreenStream's native Android screen-streaming module. It captures screen content as JPEG images and serves them over HTTP to browsers and other viewers, without audio. A reachable local network is enough; viewing does not require an internet service.

The module combines the [capture engine](../screen-capture-engine/README.md) with device-address discovery, an HTTP listener for each selected address, optional PIN protection, and a browser page. All listeners and viewers share the captured JPEG.

This is an Android library module in this repository, integrated with `:streaming-core`, `:common`, and `:app`. It is not a separately published capture or streaming SDK. Its stable selection ID is `MJPEG`.

Read the guides:

- [Usage](docs/usage.md) — start viewing, use browser controls, understand configuration and integrate the Android host.
- [Architecture](docs/architecture.md) — components, image flow, lifetimes and resource ownership.
- [Internals](internal/README.md) — lifecycle, configuration and delivery contracts for maintainers.

## Start viewing

1. In ScreenStream, select native MJPEG and allow local-network access when requested.
2. Wait for a server to show Listening, then press **Start stream** and complete Android's screen-capture consent.
3. Use **Copy URL** on that server and open the link on a device that can reach its address. Loopback addresses work only on the streaming device.

Protected copied links authorize viewing automatically. The browser also supports PIN entry when protection is enabled.

Selection prepares the module and its HTTP servers; it does not start screen capture. **Stop stream** ends the current capture attempt while leaving the selected module and HTTP delivery open. Depending on the saved stopped-image setting, viewers then receive the logo, the last available image, or no image. [Usage](docs/usage.md#start-view-and-stop) explains readiness, permissions and failures.

## Capabilities

- **Viewing.** Use the bundled browser page, a multipart MJPEG response, or a single JPEG response. The page offers image sizing, connection retry and an alternate viewing method; fullscreen and picture in picture depend on browser support.
- **Shared delivery.** Slow viewers skip intermediate images instead of building an unbounded frame queue. An ordinary bind failure does not stop the other listeners or capture.
- **Saved configuration.** Image settings support crop, scale or target dimensions, rotation, mirroring, color, JPEG quality and frame timing. The current native screen edits address filters; the full settings editor remains unfinished.
- **Live changes.** Saved image parameters, address filters and page appearance can apply during capture. The HTTP port, access configuration and JPEG backend remain fixed until successful capture cleanup.
- **Access protection.** A six-digit PIN authorizes viewing; copied protected links carry a runtime access token. Credentials rotate when protection or the PIN changes. Native PIN controls remain unfinished.

The current [build configuration](../build.gradle.kts) uses Android API 24 as its minimum, target/compile SDK 37 and JVM toolchain 17. See the [module build](build.gradle.kts) and [version catalog](../gradle/libs.versions.toml) for dependencies.

## Current status and limits

MJPEG is under development and has not completed device/browser acceptance. The current native screen provides Start/Stop, address filters, server status, server Retry, Copy URL and local-network permission handling. Full settings/PIN controls, preview, viewer/statistics presentation and capture Retry remain unfinished. The [testing guide](internal/04-testing/testing.md) records verification scope and evidence.

HTTP does not encrypt screen images or viewing credentials. Selected addresses and a successful bind do not guarantee viewer reachability. Frame rate is best effort; browser rendering and actual network delivery are not proved by server counters. Capture may pause in `Suspended`; there is no automatic timed capture retry. Protected content and color/output limits follow the [capture engine contract](../screen-capture-engine/docs/usage.md).

Saved keep-screen-awake, screen-off Stop, hide-PIN and slow-viewer notification preferences are not fully connected to runtime behavior. HTTPS and mDNS are not implemented. Legacy settings receive a one-time import attempt that deletes the old file even on failure; see [settings migration](internal/02-configuration/settings.md#legacy-import).
