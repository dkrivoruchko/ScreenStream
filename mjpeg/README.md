# ScreenStream MJPEG

MJPEG is ScreenStream's native Android screen-streaming module. It captures screen content as JPEG images and serves them over HTTP to browsers and other viewers, without audio. A reachable local network is enough; viewing does not require an internet service.

The module combines the [capture engine](../screen-capture-engine/README.md) with device-address discovery, an HTTP listener for each selected address, optional PIN protection, and a browser page. All listeners and viewers share the captured JPEG.

This is an Android library module in this repository, integrated with `:streaming-core`, `:common`, and `:app`. It is not a separately published capture or streaming SDK. Its stable selection ID is `MJPEG`.

Read the guides:

- [Usage](docs/usage.md) — start viewing, configure image/network/access behavior, recover from failures and integrate the Android host.
- [Architecture](docs/architecture.md) — components, image flow, readiness boundaries, lifetimes and resource ownership.
- [HTTP viewing API](docs/http-viewing-api.md) — direct address/Origin requirements, routes, authorization and browser-state schema.
- [Internals](internal/README.md) — lifecycle, configuration and delivery contracts for maintainers.

## Start viewing

1. In ScreenStream, select native MJPEG. If an address needs local-network access, use its Request permission/Open settings action and complete the app prompt.
2. Wait until **Start stream** becomes available, then press it and complete Android's screen-capture consent.
3. Use **Copy URL** on that server and open the link on a device that can reach its address. Loopback addresses work only on the streaming device.

Copied links containing the current access token authorize protected viewing automatically. Address actions fall back to a plain HTTP endpoint when no applied viewing link is available. The browser also supports PIN entry when protection is enabled.

Selection prepares the module and its HTTP servers; it does not start screen capture. **Stop stream** ends the current capture attempt while leaving the selected module and HTTP delivery open. Whenever capture has no current image, the saved fallback selects the app logo or the last real frame (falling back to the logo if none exists). The latest real frame survives capture attempts and fallback changes until HTTP delivery is disposed. [Usage](docs/usage.md#start-view-and-stop) explains readiness, permissions and failures.

## Capabilities

- **Viewing.** Use the bundled browser page, a multipart MJPEG response, or a single JPEG response. The page offers image sizing, connection retry and an alternate viewing method; fullscreen and picture in picture depend on browser support.
- **Shared delivery.** Slow viewers skip intermediate images instead of building an unbounded frame queue. An ordinary bind failure does not stop the other listeners or capture.
- **Saved configuration.** Five searchable settings groups provide image controls, automation, connection, web appearance and access settings. Keep screen awake and slow-viewer warnings use live switches.
- **Live changes.** Saved image parameters, address filters and page appearance can apply during capture. The HTTP port, access configuration and JPEG backend remain fixed until successful capture cleanup.
- **Access protection.** A six-digit PIN authorizes viewing; copied protected links carry a runtime access token. Native controls provide protection policy, PIN reveal/edit/generation and wrong-PIN limiting. Credentials rotate when protection or the PIN changes.

The current [build configuration](../build.gradle.kts) uses Android API 24 as its minimum, target/compile SDK 37 and JVM toolchain 17. See the [module build](build.gradle.kts) and [version catalog](../gradle/libs.versions.toml) for dependencies.

## Limits

The native screen provides Start/Stop, settings search and editors, server status, server Retry, Copy/Open/Share/QR actions and local-network permission handling. It has no capture preview, viewer/statistics presentation or capture Retry action. The [testing guide](internal/04-testing/testing.md) defines verification scope and evidence requirements; browser compatibility and device behavior require qualification in their environment.

HTTP does not encrypt screen images or viewing credentials. Selected addresses and a successful bind do not guarantee viewer reachability. Frame rate is best effort; browser rendering and actual network delivery are not proved by server counters. Capture may pause in `Suspended`; there is no automatic timed capture retry. Protected content and color/output limits follow the [capture engine contract](../screen-capture-engine/docs/usage.md).

HTTPS and mDNS are not implemented. Keep screen awake makes a best-effort request during confirmed capture, including while other apps are visible; manual sleep and Android projection restrictions still apply. Slow-viewer warnings use the existing MJPEG skipped-publication signal and do not measure viewer bandwidth or rendering; see [warning policy](internal/01-lifecycle/coordination.md#slow-viewer-warnings). Legacy settings receive a one-time import attempt that deletes the old file even on failure; see [settings migration](internal/02-configuration/settings.md#legacy-import).
