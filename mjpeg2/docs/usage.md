Usage · [Architecture](architecture.md) · [Internals](../internal/README.md)

# Using ScreenStream MJPEG

MJPEG serves Android screen images through a local HTTP server, without audio. Use the current ScreenStream controls to start capture and open the browser viewer. The host integration sections describe this repository's APIs; MJPEG is not a standalone published SDK.

In this guide:

- [Start, view and stop](#start-view-and-stop)
- [Change configuration](#change-configuration)
- [Observe state and failures](#observe-state-and-failures)
- [Close and reopen](#close-and-reopen)
- [Integrate the Android host](#integrate-the-android-host)
- [Select and control the module](#select-and-control-the-module)

## Start, view and stop

1. Select native MJPEG in ScreenStream and grant local-network access when requested.
2. Wait for a server to show Listening. Press **Start stream** and complete Android's screen-capture consent.
3. Use **Copy URL** on a listening server and open the link on a device that can reach that address. Loopback addresses work only on the streaming device.
4. Press **Stop stream** on the streaming device to end capture. The selected module and its HTTP servers remain open.

### Readiness and permission

Start becomes available after settings and HTTP configuration are applied, selected addresses are observed, and a matching permitted server is listening. A previous capture must also have cleaned up successfully. An older listening row during discovery may therefore be insufficient for Start; [Coordination](../internal/01-lifecycle/coordination.md#discovery-plan-and-start-readiness) explains the checks.

Defaults select private IPv4 addresses on Wi-Fi/Ethernet, using port 8080. Filters can select other supported address families, interfaces and categories. A selected address or successful bind does not guarantee that another device can reach it. See [Network addresses](../internal/02-configuration/network-addresses.md) for supported selections.

On Android 17 (API 37) and later, this target-37 app requests local-network permission. Native MJPEG checks it for non-loopback Wi-Fi, Ethernet and Other addresses; its Mobile/VPN rows do not require that check. Without permission, affected rows show PermissionDenied and offer neither server Retry nor Copy URL. Granting permission reconfigures listeners; it does not start capture. See [Android local-network permission](https://developer.android.com/privacy-and-security/local-network-permission) and the module's [permission rules](../internal/01-lifecycle/coordination.md#lan-permission-request-identity).

### Capture controls

Each accepted Start creates one capture attempt. Declining consent, an unavailable consent host or busy acquisition ends it without another automatic dialog. The screen shows Busy during consent, startup and cleanup. Stop appears after confirmed startup or while Suspended. An exact external Stop request can also cancel consent/startup while the screen shows Busy.

Successful startup does not guarantee that the first JPEG is available yet. `Suspended` pauses image production and clears the served image while keeping the attempt and foreground lifetime. Changed image parameters or capture conditions may recover it; there is no automatic timed retry. The current screen offers Stop but does not display the suspension reason or offer capture Retry. See [Module lifecycle](../internal/01-lifecycle/module-lifecycle.md#stop-and-suspension).

**Stop stream** ends the current attempt. A new Start waits for successful capture cleanup. A capture error can clean up successfully and permit another Start; cleanup failure blocks Start until module shutdown. Android can stop projection independently of viewers and saved behavior preferences.

### URLs and viewing

Copy URL is available only for a listening, permitted address that can form a portable link, after access configuration is applied. The click rechecks the current server and uses its applied viewing credentials. IPv6 URLs use brackets without a scope suffix; unsupported portable IPv6 links are not offered.

The root page provides viewing and PIN entry. `/jpeg` gives one JPEG; `/mjpeg` gives multipart JPEG images; `/ws` supplies read-only browser state. Media requests accept open access, a current viewing cookie or the current access token. The WebSocket uses open access or a cookie. A protected copied link exchanges its token for a cookie and redirects to `/`. Ordinary HTTP exposes credentials and content to the network. [Web client](../internal/03-delivery/web-client.md) owns the exact routes, status codes and wire schema.

A six-digit PIN and optional wrong-PIN limiter are shared across listeners. With limiting enabled, the fifth wrong entry from a direct peer IP starts a block; even a correct PIN remains blocked until expiry. Existing cookie/token authorization continues, and requests do not extend the block. Changing protection or the enabled PIN invalidates old viewing credentials. A JPEG already acquired by a response can finish. See [HTTP delivery](../internal/03-delivery/http-delivery.md#access-generations-and-pin-blocks).

After ordinary Stop, the configured image policy selects no image, the cached logo, or the last currently available image. It cannot restore a frame already cleared by suspension/failure. A new media request with no image gets 503; an already admitted MJPEG response can wait for another image. Slow viewers skip intermediate images. See [JPEG delivery](../internal/03-delivery/jpeg-delivery.md).

### Browser controls

Choose **Fit**, **Original size** or **Fit width** to size the image. Open **More** for connection information, **Retry connection** and **Other viewing method**. MJPEG is the default; the alternate method polls single JPEG images. When JPEG is selected, the switch back is labeled **Use MJPEG**. Connection and image recovery use finite retry budgets, so a failure can require a manual retry or method change.

Fullscreen and picture in picture appear when the browser exposes the required APIs; actual support and permission depend on the browser. Image sizing, viewing method and information choices stay in page memory and reset when the page reloads. The viewer requires JavaScript and WebSocket support and cannot start or stop capture on the Android device.

## Change configuration

The current native screen edits address filters; the full settings editor and PIN controls remain unfinished. The saved model supports the settings below. Preferences use `mjpeg.preferences_pb`. A present legacy `MJPEG_settings.preferences_pb` receives one import attempt before access and is deleted even when import fails. Existing new settings take priority. [Migration details](../internal/02-configuration/settings.md#legacy-import) describe conversion and failure limits. Saving a value does not confirm that the controller or capture engine has applied it.

| When editable | Settings |
| --- | --- |
| Live, including consent/startup/cleanup | Image geometry/color/quality/timing, address filters, page appearance and behavior preferences. |
| Only fully idle after successful capture cleanup | JPEG backend, HTTP port and access settings. |

Restricted changes saved while capture is busy are deferred until successful cleanup. Failed cleanup keeps them restricted. Live image updates target the same engine session; a frame from an earlier configuration may still arrive under the [capture engine contract](../../screen-capture-engine/docs/usage.md).

Network-loss Stop applies when the selected-address list stays empty during an attempt, including Suspended. Its timeout is 10–1800 seconds, default 60. Returning addresses cancel the episode; editing the timeout retains its original start, while discovery Loading or a filter mismatch resets it. Missing LAN permission or a bind failure with a selected address does not count as this loss. Recovery does not restart capture automatically. [Coordination](../internal/01-lifecycle/coordination.md#network-loss-deadline) defines the exact timer.

Saved screen wakefulness, additional screen-off Stop, PIN hiding and slow-viewer notifications are not fully wired. HTTP statistics collection does not depend on the slow-viewer notification preference. Defaults, serialization and exact policy live in [Settings](../internal/02-configuration/settings.md).

## Observe state and failures

Manager `Running` confirms reports from the control loop; it does not confirm capture or HTTP readiness. `Status.isStreaming`, `hasConsumer` and `captureAttempt` are independent facts. A successfully started Suspended capture still counts as streaming. Sampled consumer presence can include a short idle grace after media requests end; it does not prove that a viewer is displaying images.

`NoModule`, `Switching` and `Exiting` hide old module content. `Unresponsive` means the control loop has missed heartbeats; capture is not automatically stopped, and a fresh heartbeat can restore Running. `Failed` retains the first terminal launch/module failure. The manager can restart the exact failed/unresponsive launch, but the shell's error/restart presentation remains unfinished.

Capture/cleanup errors are retained separately inside MJPEG and can produce a capture error notification. Recoverable capture failure is not automatically a terminal manager failure. Bind failures remain per-server and offer Retry for the current failed incarnation. A fatal settings/discovery/HTTP/controller failure closes the module. The lifecycle details are in [Streaming host](../internal/01-lifecycle/streaming-host.md) and [Module lifecycle](../internal/01-lifecycle/module-lifecycle.md).

HTTP totals count completed JPEG payload writes, including repeats, excluding framing and partial payloads. They do not confirm network delivery or browser decode. Servers publish immediately; viewer/counter snapshots are normally sampled once per second. These statistics are collected but not yet fully exposed by native UI.

## Close and reopen

Module shutdown closes new work and starts independent cleanup. The manager bounds its scheduled startup and shutdown waits to three seconds each; switching or restarting can wait separately for old shutdown and new startup. Blocked Android calls or Main scheduling can delay API return, and `exit()` can return while cleanup remains pending or unconfirmed. See [Streaming host](../internal/01-lifecycle/streaming-host.md#reports-clocks-and-deadlines) and [Module lifecycle](../internal/01-lifecycle/module-lifecycle.md#controller-shutdown-and-complete-module-cleanup).

The Activity-scoped [SingleActivityViewModel](../../app/src/main/java/info/dvkr/screenstream/app/SingleActivityViewModel.kt) retains bootstrap and Exit across configuration changes. After the bounded wait, the current Activity removes the task without forcibly killing the process.

Home/rotation do not themselves repeat bootstrap or Stop capture. Removing Recents is not a capture Stop command. Process death leaves saved preferences but does not restore capture, consent or cleanup callbacks. A later opening after completed Exit may select a module again.

Device/browser acceptance remains incomplete; see the [testing guide](../internal/04-testing/testing.md) for verification scope and evidence.

## Integrate the Android host

Public host entry points belong to `streaming-core`; MJPEG's controller, settings, network and HTTP types are internal. The current host is the repository's `:app`. Its [Gradle dependencies](../../app/build.gradle.kts) include MJPEG; the module in turn depends on `:common`, `:streaming-core` and `:screen-capture-engine`. [MjpegKoinModule](../src/main/kotlin/io/screenstream/mjpeg/MjpegKoinModule.kt) and [StreamingKoinModule](../../streaming-core/src/main/kotlin/io/screenstream/streaming/StreamingKoinModule.kt) participate in the annotation/compiler-based DI configuration started by [BaseApp](../../app/src/main/java/info/dvkr/screenstream/BaseApp.kt). This wiring also needs the app's implementations of common notification and UI services. Adding a dependency alone is not a complete replacement-host setup.

The [MJPEG manifest](../src/main/AndroidManifest.xml) declares network permissions, foreground-service permissions and the non-exported `mediaProjection` Service. The [core manifest](../../streaming-core/src/main/AndroidManifest.xml) declares the exact capture Stop receiver. Preserve these components and their DI dependencies when changing integration.

The app hosts [ScreenCaptureConsentHost](../../streaming-core/src/main/kotlin/io/screenstream/streaming/capture/ScreenCaptureConsentHost.kt) outside stream tabs in [ScreenStreamContent](../../app/src/main/java/info/dvkr/screenstream/ui/ScreenStreamContent.kt). A new dialog needs a resumed Activity. The host presents the education gate and correlates Android results with the original request. It does not own the projection or capture session.

MJPEG acquires consent, promotes its original Service to the `mediaProjection` foreground type, then creates the projection and capture-engine session. Android's [media-projection](https://developer.android.com/media/grow/media-projection) and [foreground-service](https://developer.android.com/develop/background-work/services/fgs/service-types#media-projection) requirements remain applicable. For a new session on API 34+, fresh consent is required. The pre-34 process-only reuse path and exact ownership rules are in [Streaming host](../internal/01-lifecycle/streaming-host.md#consent-and-projection-handoff).

Ordinary permissions remain separate. The app requests notification permission on API 33+. Native MJPEG owns its local-network request path; the app's separate LAN UI serves legacy modes. Denied notification permission can hide a notification without becoming capture authorization or disabling native UI Stop. Returning from Android Settings triggers a grant recheck; it does not automatically start capture.

## Select and control the module

[StreamingModuleManager](../../streaming-core/src/main/kotlin/io/screenstream/streaming/StreamingModuleManager.kt) owns the fixed catalog, selection and process state. Its catalog is ordered by descending priority. A listed module is metadata, not an active launch.

The following example uses existing public APIs. Run selection, restart, exact Stop and Exit on Main as required by their declarations. The caller supplies an already initialized/injected manager; this snippet does not replace app bootstrap or consent hosting.

```kotlin
// Select the native module; selection does not start capture.
manager.selectModule(StreamingModule.Id("MJPEG"))

// On an explicit user Start action, use the presented launch identity.
val running = manager.state.value as? StreamingModuleManager.State.Running
running?.let { manager.requestStreamStart(it.instanceId) }

// On a later explicit Stop action, read and route the exact attempt.
val stopState = manager.state.value as? StreamingModuleManager.State.Running
stopState?.status?.captureAttempt?.let(manager::requestStreamStop)
```

Commands are requests. Their return does not prove admission, startup, JPEG availability or resource release. The module rechecks readiness when it handles Start, and compares the exact attempt when it handles Stop. A stale Stop cannot stop a later capture. Current native UI performs these requests and should normally be used by the existing app.

| Entry point | Caller-visible behavior |
| --- | --- |
| `selectModule(id?)` | Opens a known module; the first null selection uses saved choice/fallback. Same current module ID is a no-op, including after failure. Concurrent transitions reject selection without queueing it. |
| `restartModule(instanceId)` | Replaces only the exact current Failed/Unresponsive launch while the manager is free. Does not change saved selection or automatically start capture. |
| `requestStreamStart(instanceId)` | Requests capture from the original admitted launch; stale/unready requests can do nothing. |
| `requestStreamStop(attempt)` | Requests Stop for the original exact capture attempt; does not close the module. |
| `InstanceContent(...)` | Renders only the exact installed/admitted controller; otherwise calls the supplied fallback. UI collection does not own streaming resources. |
| `exit()` | Closes admission and joins the shared bounded shutdown wait. Caller cancellation ends its wait, not cleanup. |

Use [manager state](#observe-state-and-failures) to understand the result. `currentInstanceId` and `state` are independently collected flows; use one `state` value when identity and phase must agree.

