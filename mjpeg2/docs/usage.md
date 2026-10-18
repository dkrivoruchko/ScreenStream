Usage · [Architecture](architecture.md) · [Internals](../internal/README.md)

# Using ScreenStream MJPEG

MJPEG serves Android screen images through a local HTTP server, without audio. Use the ScreenStream controls to start capture and open the browser viewer. The host integration sections describe this repository's APIs; MJPEG is not a standalone published SDK.

In this guide:

- [Start, view and stop](#start-view-and-stop)
- [Change configuration](#change-configuration)
- [HTTP viewing API](http-viewing-api.md)
- [Observe state and failures](#observe-state-and-failures)
- [Troubleshoot viewing and capture](#troubleshoot-viewing-and-capture)
- [Close and reopen](#close-and-reopen)
- [Integrate the Android host](#integrate-the-android-host)
- [Select and control the module](#select-and-control-the-module)

## Start, view and stop

1. Select native MJPEG in ScreenStream. If an address requires local-network access, press its **Request permission** or **Open settings** action and complete the app prompt.
2. Wait until **Start stream** becomes available, then press it and complete Android's screen-capture consent.
3. Use **Copy URL** on a listening server and open the link on a device that can reach that address. Loopback addresses work only on the streaming device.
4. Press **Stop stream** on the streaming device to end capture. The selected module and its HTTP servers remain open.

### Readiness and permission

Start becomes available after settings and HTTP configuration are applied, selected addresses are observed, and a matching permitted server is listening. A previous capture must also have cleaned up successfully. An older listening row during discovery may therefore be insufficient for Start; [Coordination](../internal/01-lifecycle/coordination.md#discovery-plan-and-start-readiness) explains the checks.

Defaults select private IPv4 addresses on Wi-Fi/Ethernet, using port 8080. Filters can select other supported address families, interfaces and categories. A selected address or successful bind does not guarantee that another device can reach it. See [Network addresses](../internal/02-configuration/network-addresses.md) for supported selections.

On Android 17 (API 37) and later, this target-37 app requests local-network permission. Native MJPEG checks it for non-loopback Wi-Fi, Ethernet and Other addresses; its Mobile/VPN rows do not require that check. Without permission, affected rows show PermissionDenied without server Retry or an applied viewing link; their address actions still use the plain HTTP endpoint. Granting permission reconfigures listeners; it does not start capture. See [Android local-network permission](https://developer.android.com/privacy-and-security/local-network-permission) and the module's [permission rules](../internal/01-lifecycle/coordination.md#lan-permission-request-identity).

Local-network requests use the app's shared ordinary-permission host. Its explanation describes access and Android's Nearby devices wording. **Continue** submits the Android request; **Not now**, Back or outside dismissal closes the local-network explanation without granting access or automatically reopening it. **Request permission** on an affected row offers an explicit attempt. When the shared state suggests Settings, the row shows **Open settings**; this opens a confirmation before navigating to app settings. The suggestion combines previously observed denied-permission rationale with Android's current rationale signal; it is not proof of permanent denial. Returning to the app rechecks the grant.

A genuinely fresh app-root UI offers notification access once when app notifications are unavailable, including when they are disabled in Android Settings. The explanation is optional: **Not now**, Back or outside dismissal closes it. Rotation, restored process state, background return and returning from Settings do not create another offer. **Continue** opens Android's permission request when applicable. **Open settings** is selected when notification delivery is disabled despite an available runtime grant, when no notification runtime request exists on API 24–32, or when observed rationale history suggests Settings. The latter remains a heuristic, as for LAN. **Settings → Device notification settings** also provides explicit recovery: normal public APIs cannot reliably distinguish a first-seen permanent denial from a fresh request with no observed history. Notification denial does not gate LAN or audio admission. The shared host still serializes permission UI, so an occupied notification slot blocks a new ungranted request until that slot clears. An admitted prompt belongs to the app and remains independent of the originating screen's exit. Granting LAN access prepares eligible listeners; the user still presses Start. A platform-denied bind with a server Retry action is a separate listener failure from a missing-grant row. The [internal permission contract](../internal/01-lifecycle/coordination.md#lan-permission-request-identity) describes address-row presentation, app-owned request lifetime and fresh OS checks.

Native MJPEG requests LAN access only after the user presses an address-row permission action. Opening the screen, changing the filter, granting notification permission or returning to the Activity does not automatically offer a native LAN prompt. If admission is blocked while the app is paused or another permission owns the UI, use the row action again when available; the request is not queued. This native policy does not change other modules' request behavior.

### Capture controls

Each accepted Start creates one capture attempt. Declining consent, an unavailable consent host or busy acquisition ends it without another automatic dialog. The screen shows Busy during consent, startup and cleanup. Stop appears after confirmed startup or while Suspended. An exact external Stop request can also cancel consent/startup while the screen shows Busy.

Successful startup does not guarantee that the first JPEG is available yet. `Suspended` pauses image production and applies the configured image fallback while keeping the attempt and foreground lifetime. Changed image parameters or capture conditions may recover it; there is no automatic timed retry. The screen displays the suspension reason and offers Stop; it has no capture Retry action. See [Module lifecycle](../internal/01-lifecycle/module-lifecycle.md#stop-and-suspension).

**Stop stream** ends the current attempt. A new Start waits for successful capture cleanup. A capture error can clean up successfully and permit another Start; cleanup failure blocks Start until module shutdown. Android can stop projection independently of viewers and saved behavior preferences.

### URLs and viewing

Copy/Open/Share/QR use the address row's supplied snapshot. A listening, permitted address with applied access configuration and a portable host supplies a viewing link, including the current token when protected. Otherwise the actions use the plain HTTP endpoint; this fallback does not establish reachability or automatic protected access. Portable IPv6 viewing links use brackets without a scope suffix and exclude link/site-local addresses. The action does not recheck the current server or credentials at click time.

The root page provides viewing and PIN entry. `/jpeg` gives one JPEG; `/mjpeg` gives multipart JPEG images; `/ws` supplies read-only browser state. Media requests accept open access, a current viewing cookie or the current access token. The WebSocket uses open access or a cookie. A protected copied link exchanges its token for a cookie and redirects to `/`. Ordinary HTTP exposes credentials and content to the network. The [HTTP viewing API](http-viewing-api.md) defines the route/status table, direct Host and Origin requirements, cookies and WebSocket schema. Use the supplied numeric device address: arbitrary DNS aliases and reverse-proxy authority are not accepted.

A six-digit PIN and optional wrong-PIN limiter are shared across listeners. With limiting enabled, the fifth wrong entry from a direct peer IP starts a block; even a correct PIN remains blocked until expiry. Existing cookie/token authorization continues, and requests do not extend the block. Changing protection or the enabled PIN invalidates old viewing credentials. A JPEG already acquired by a response can finish. See [HTTP delivery](../internal/03-delivery/http-delivery.md#access-generations-and-pin-blocks).

With PIN protection on, expand Stream addresses to find the hidden PIN below the addresses. Use **Show/Hide** to read it or **Copy PIN** to copy it without showing it on screen. Both actions remain available during capture. Enter this PIN when connecting by typing an address. Shared access links and their QR codes open without PIN entry. Edit or generate a PIN in Access settings.

Whenever capture has no current image, the configured fallback selects no image, the cached logo, or the latest real frame. Last frame uses the logo until a real frame exists. That one real frame survives suspension, failure, capture attempts and fallback changes until it is replaced or HTTP delivery is disposed. A new media request with no image gets 503; an already admitted MJPEG response can wait for another image. Slow viewers skip intermediate images. See [JPEG delivery](../internal/03-delivery/jpeg-delivery.md).

### Browser controls

Use the three image-size buttons for **Fit**, **1:1 (Original size)** or **Fit to width**; the selected button stays highlighted. Open **More** for the **Connection** section: **MJPEG** is the default; **JPEG** polls single JPEG images. The selected method has a check mark. Status, Image, conditional Notice and Dimensions appear below the choices, followed by the Privacy Policy and Terms & Conditions links. Close More by pressing it again, clicking or tapping outside it, or pressing Escape. Connection and image recovery use finite retry budgets; an exhausted failure offers **Retry connection** and an explicit switch to the other method when an image is available.

Fullscreen and picture in picture appear when the browser exposes the required APIs; actual support and permission depend on the browser. Image sizing, viewing method and information choices stay in page memory and reset when the page reloads. The viewer requires JavaScript and WebSocket support and cannot start or stop capture on the Android device.

Access, recovery and states without a displayed image follow the browser's light or dark preference; older browsers use the light fallback. Overlay controls, title and More use dark neutral player surfaces. While an image is displayed, the configured RGB fills the viewing canvas and separate bars; bar text and icons use black or white for contrast. A displayed retained frame has a persistent state badge above the bottom controls without changing image sizing; capture failure uses red text. With no displayed real frame, the page uses its own centered logo and message. Pending screen-sharing permission always uses that centered state, even when direct media selects a retained frame.

## Change configuration

The native screen provides five collapsible, searchable groups: Image, Automation, Connection, Web page and Access. Search matches localized setting descriptions and options without indexing user values or PINs. Forms keep changes as drafts and save a changed valid result when closed; keyboard Done only clears focus. Switches in the main list and exclusive menu choices save immediately. Generate PIN saves immediately; manual PIN edits save on closing the form and require Permanent PIN while capture is idle.

Preferences use `mjpeg.preferences_pb`. A present legacy `MJPEG_settings.preferences_pb` receives one import attempt before access and is deleted even when import fails. Existing new settings take priority. [Migration details](../internal/02-configuration/settings.md#legacy-import) describe conversion and failure limits. Saving a value does not confirm that the controller or capture engine has applied it.

| When editable | Settings |
| --- | --- |
| Live, including consent/startup/cleanup | Image geometry/color/quality/timing, address filters, page appearance, behavior preferences and Hide PIN when capture starts. |
| Only fully idle after successful capture cleanup | JPEG backend, HTTP port and access settings other than Hide PIN when capture starts. |

Restricted changes saved while capture is busy are deferred until successful cleanup. Failed cleanup keeps them restricted. Live image updates target the same engine session; a frame from an earlier configuration may still arrive under the [capture engine contract](../../screen-capture-engine/docs/usage.md).

Network-loss Stop applies when the selected-address list stays empty during an attempt, including Suspended. Its timeout is 10–1800 seconds, default 60. Returning addresses cancel the episode; editing the timeout retains its original start. Discovery Loading clears the episode, and changing the effective filter starts a new interval if its selection is empty. Missing LAN permission or a bind failure with a selected address does not count as this loss. Recovery does not restart capture automatically. [Coordination](../internal/01-lifecycle/coordination.md#network-loss-deadline) defines the exact timer.

**Keep screen awake** is a live preference, off by default. When enabled, it requests that the screen stay on during confirmed capture, including while other apps are visible, capture is paused or image settings are changing. It allows dimming and applies independently of connected viewers. Waiting for permission, unconfirmed startup and HTTP-only operation do not keep the screen on. Turning it off removes the request; normal device behavior then applies. This can increase battery use. Android may suppress the request, and failures only log without interrupting capture or showing a warning.

**Stop when screen turns off** is a live preference: when enabled, device sleep stops the current capture attempt, including consent or startup, without automatically restarting on wake. It leaves the module and HTTP servers open. Keep screen awake does not prevent manual power-button sleep, wake or unlock the device. Both preferences can be enabled together. Android may independently stop projection even when screen-off Stop is off; Android 15 QPR1 and later stop projection when the device locks.

**Notify about slow viewers** is a live switch, enabled by default. During active capture, an MJPEG response that skips five accumulated capture publications can trigger a brief device Toast. Simultaneous slow viewers share one warning; a fresh sample with none slow rearms it. Warnings are at least 30 seconds apart across reconnects, Stop/Start and switch changes within the same module lifetime. Continued slowness produces no reminders. JPEG polling is excluded, and the signal does not measure bandwidth or browser rendering or detect every stalled connection. Turning the switch off or leaving active capture prevents further warnings; an already requested Toast finishes normally. Android controls Toast visibility and duration. HTTP statistics collection remains independent of this switch. Defaults and serialization live in [Settings](../internal/02-configuration/settings.md); exact sampling, cooldown and lifecycle rules live in [Coordination](../internal/01-lifecycle/coordination.md#slow-viewer-warnings).

### Image controls

The image pipeline uses the Android-granted screen or app area. **Capture area** then selects its full, left-half or right-half region; **Crop** removes pixel insets from that region before rotation. Rotation is clockwise in 90° steps, mirror applies after rotation, and output size describes the transformed image. Retaining inactive crop or target-size values does not make them active.

| Control | Choices and behavior | Default |
| --- | --- | --- |
| Capture area / Crop | Full/left/right region; optional nonnegative left/top/right/bottom pixel insets. Crop must leave a nonempty area at runtime. | Full; crop off, zero insets |
| Image size | Scale greater than 0 through 200% in the native editor, or width/height 1–8192 pixels per side. Size preserves aspect ratio within the bounds unless Stretch is enabled. | Scale 50% |
| Rotation / Mirror / Grayscale | 0°/90°/180°/270° clockwise; no/horizontal/vertical mirror; color or grayscale. | 0°; no mirror; color |
| JPEG quality | Integer 0–100; higher quality generally uses more data. | 80 |
| Frame rate | Auto, FPS limit 1–120, or minimum capture interval 1–3600 seconds, including decimal seconds. A limit is not a promised production or delivery rate. | Auto |
| JPEG encoder | Auto permits optimized encoding when available; Standard uses the framework-only backend. Change it while capture is idle. | Auto |

The size editor's source/result values are screen-based estimates; app capture and actual geometry can differ. Enlarging adds no source detail and may increase work. A syntactically valid crop or size can still be unsuitable for the current capture area or resources and suspend capture. The saved model accepts some values outside native editor bounds; [Settings](../internal/02-configuration/settings.md#validation-and-image-interpretation) distinguishes model validity from editor limits. [Size](../src/main/kotlin/io/screenstream/mjpeg/ui/settings/images/Size.kt) and [Frame rate](../src/main/kotlin/io/screenstream/mjpeg/ui/settings/images/FrameRate.kt) implement the native editors.

### Connection and automation

The network filter has three required groups: IP version, network type and address type. An address must match **each** group; multiple choices within one group are alternatives. The native editor keeps at least one choice in every group. Selecting a network type does not establish internet or viewer reachability. Defaults are IPv4, Wi-Fi/Ethernet and Private; the port defaults to 8080 and the native saved range is 1024–65535.

Private means RFC 1918 IPv4 or IPv6 unique-local addresses. Non-private includes other supported unicast and does not mean internet-reachable. Loopback supports only this device. IPv4 link-local can be selected, while IPv6 link-local is excluded. The exact classification and uncertain-discovery rules remain in [Network addresses](../internal/02-configuration/network-addresses.md).

Automation's network-loss timeout defaults to 60 seconds, with a 10–1800 second range and no Never value. **Inactive image** chooses no image, logo or the last real frame; the default is logo. Last frame falls back to logo when no real frame has been captured. The fallback applies whenever there is no current capture image, including startup, consent, pause, Stop and failure. Changing it does not erase the retained real frame. The empty-address timer and behavior preferences are described above.

### Access and web appearance

Protection defaults to off. When enabled without a saved PIN, a six-digit PIN is generated. **New PIN on open** prepares a PIN when the protected module opens; it does not regenerate on each capture Start/Stop or ordinary background return. **Permanent PIN** retains it until a manual Set or Generate action. Manual Set requires Permanent policy and idle capture; Generate works with either policy while idle. Enabling protection after an initially open activation generates only a missing PIN, without repeating the module-open regeneration. Show/Hide is an explicit local reveal action.

**Hide PIN when capture starts** is live-editable and defaults to on. It hides the PIN below stream addresses when starting capture. Turn it off before Start to keep a PIN you showed visible when starting. During consent, startup, capture or pending cleanup, switching this setting either way resets a manually revealed PIN to hidden when the screen observes the applied change. Neither turning it off nor stopping capture automatically shows the PIN; Show remains available. The preference also remains editable after failed cleanup, which still blocks another Start. The PIN starts hidden when the screen is recreated or the PIN changes. Access settings have separate Show/Hide controls. Hiding is a display convenience; it does not guarantee that the PIN cannot appear in a captured frame.

Wrong-PIN limiting defaults to on with a one-minute block. The native duration editor accepts 1–60 minutes; the saved model permits longer positive durations without silently rewriting them. Changing protection or an enabled PIN rotates viewing credentials; copy/share a fresh protected link for viewers that need new access.

Web page settings apply to all viewers: RGB background (default `#101418`), stream name and visibility, separate title/control placement. The default name comes from the Android device manufacturer/model. An explicitly empty name remains empty; the native name editor accepts up to 64 UTF-16 code units. Each title/control block can be Top/Bottom and Overlay/Bar. Auto-hide applies only to overlays; bars stay visible on the selected background. Defaults are a visible top-overlay title, bottom-overlay controls and auto-hide for both. At a shared edge controls sit outermost and the title sits inward. Bars reserve space; overlays cover the image, except an outer controls overlay followed by an inner title bar reserves the complete stack to preserve their order without overlap.

Fit mode, JPEG/MJPEG viewing choice and connection-information visibility belong only to the current browser page and reset on reload.

Forms and their errors describe saving, not capture convergence. Invalid or unchanged drafts do not write. A failed group save, including a PIN edit or regeneration, displays a Snackbar and does not confirm persistence; inspect the saved value before trying again. Save errors are not replayed when reopening the screen. [Settings internals](../internal/02-configuration/settings.md#native-settings-controls) extend these controls with latest-snapshot transactions, retention and serialization behavior.

## Observe state and failures

Manager `Running` confirms reports from the control loop; it does not confirm capture or HTTP readiness. `Status.isStreaming`, `hasConsumer` and `captureAttempt` are independent facts. A successfully started Suspended capture still counts as streaming. Sampled consumer presence can include a short idle grace after media requests end; it does not prove that a viewer is displaying images.

`NoModule`, `Switching` and `Exiting` hide old module content. `Unresponsive` means the control loop has missed heartbeats; capture is not automatically stopped, and a fresh heartbeat can restore Running. `Failed` retains the first terminal launch/module failure. The manager can restart the exact failed/unresponsive launch; the shell has no dedicated error/restart presentation.

Capture/cleanup errors are retained separately inside MJPEG and can produce a capture error notification. Recoverable capture failure is not automatically a terminal manager failure. Bind failures remain per-server and offer Retry for the current failed incarnation. A fatal settings/discovery/HTTP/controller failure closes the module. The lifecycle details are in [Streaming host](../internal/01-lifecycle/streaming-host.md) and [Module lifecycle](../internal/01-lifecycle/module-lifecycle.md).

HTTP totals count completed JPEG payload writes, including repeats, excluding framing and partial payloads. They do not confirm network delivery or browser decode. Servers publish immediately; viewer/counter snapshots are normally sampled once per second. The native screen does not present viewer or aggregate statistics.

## Troubleshoot viewing and capture

| Observation | Meaning and available action |
| --- | --- |
| No selected addresses | Check the device network and the filter's IP version, network type and address type. Discovery does not create or probe a network. |
| Pending address | Listener setup or retirement is pending. Address actions can still supply a plain endpoint; they do not prove readiness. Wait for a current permitted listener. |
| Request permission / Open settings | Use the native permission action, then allow the grant recheck. The copied fallback endpoint cannot bypass Android's grant. |
| Address in use / Address unavailable / Could not open server / Server failed | The row's listener failed. Retry targets that failed server; another listening row can remain usable. While capture is idle, a port conflict can also be addressed by choosing a different permitted port. Failure labels reflect caught transport exceptions, not a complete network diagnosis. |
| Permission denied with Retry | This is a platform-denied listener with an existing server, rather than the missing-grant action. Retry does not grant permission. |
| Start disabled or Busy | Configuration, discovery, permission or a matching listener may still be pending; consent/startup/cleanup also show Busy. A failed cleanup blocks another Start until module shutdown. A visible older listening row alone is insufficient. |
| Capture paused: image settings do not fit | Adjust region/crop/size for the current capture area. Capture remains in the same attempt; changing suitable live parameters can recover it. |
| Capture paused: source unavailable / insufficient resources | The attempt remains open and offers Stop. Capture conditions may recover; no timed retry or capture Retry action exists. |
| Page opens but viewing is denied | The HTML page is public. Use a current protected link or PIN on that address; wait out an active PIN block. A custom client must also satisfy the [Host/Origin contract](http-viewing-api.md#request-authority-origin-and-credentials). |
| HTTP 503 or a waiting multipart image | A new 503 response means viewing authorization passed but image or runtime admission was unavailable; an existing multipart reader can wait while image selection is empty. Check current delivery, Android capture, suspension/failure and image fallback; reconnecting the viewer does not start capture. |
| Browser connection or image recovery stops | Use the displayed Retry connection action, its explicit alternate-method action, or More → Connection → MJPEG/JPEG. A successful state connection does not prove fresh screen pixels or browser repaint. |

Copy/Open/Share/QR use the row's supplied snapshot, so an older link can become stale after server or credential changes. Copy it again from the current row when needed. Browser and network behavior must be qualified on the actual device/environment; the [verification guide](../internal/04-testing/testing.md) defines what each test environment can establish.

## Close and reopen

Module shutdown closes new work and starts independent cleanup. The manager bounds its scheduled startup and shutdown waits to three seconds each; switching or restarting can wait separately for old shutdown and new startup. Blocked Android calls or Main scheduling can delay API return, and `exit()` can return while cleanup remains pending or unconfirmed. See [Streaming host](../internal/01-lifecycle/streaming-host.md#reports-clocks-and-deadlines) and [Module lifecycle](../internal/01-lifecycle/module-lifecycle.md#controller-shutdown-and-complete-module-cleanup).

The Activity-scoped [SingleActivityViewModel](../../app/src/main/java/info/dvkr/screenstream/app/SingleActivityViewModel.kt) retains bootstrap and Exit across configuration changes. After the bounded wait, the current Activity removes the task without forcibly killing the process.

Home/rotation do not themselves repeat bootstrap or Stop capture. Removing Recents is not a capture Stop command. Process death leaves saved preferences but does not restore capture, consent or cleanup callbacks. A later opening after completed Exit may select a module again.

Device/browser behavior requires qualification in the relevant environment; the [testing guide](../internal/04-testing/testing.md) defines verification scope and evidence requirements.

## Integrate the Android host

Public host entry points belong to `streaming-core`; MJPEG's controller, settings, network and HTTP types are internal. The current host is the repository's `:app`. Its [Gradle dependencies](../../app/build.gradle.kts) include MJPEG; the module in turn depends on `:common`, `:streaming-core` and `:screen-capture-engine`. [MjpegKoinModule](../src/main/kotlin/io/screenstream/mjpeg/MjpegKoinModule.kt) and [StreamingKoinModule](../../streaming-core/src/main/kotlin/io/screenstream/streaming/StreamingKoinModule.kt) participate in the annotation/compiler-based DI configuration started by [BaseApp](../../app/src/main/java/info/dvkr/screenstream/BaseApp.kt). This wiring also needs the app's implementations of common notification and UI services. Adding a dependency alone is not a complete replacement-host setup.

The [MJPEG manifest](../src/main/AndroidManifest.xml) declares network permissions, foreground-service permissions and the non-exported `mediaProjection` Service. The [core manifest](../../streaming-core/src/main/AndroidManifest.xml) declares the exact capture Stop receiver. Preserve these components and their DI dependencies when changing integration.

The app hosts [ScreenCaptureConsentHost](../../streaming-core/src/main/kotlin/io/screenstream/streaming/capture/ScreenCaptureConsentHost.kt) outside stream tabs in [ScreenStreamContent](../../app/src/main/java/info/dvkr/screenstream/ui/ScreenStreamContent.kt). A new dialog needs a resumed Activity. The host presents the education gate and correlates Android results with the original request. It does not own the projection or capture session.

MJPEG acquires consent, promotes its original Service to the `mediaProjection` foreground type, then creates the projection and capture-engine session. Android's [media-projection](https://developer.android.com/media/grow/media-projection) and [foreground-service](https://developer.android.com/develop/background-work/services/fgs/service-types#media-projection) requirements remain applicable. For a new session on API 34+, fresh consent is required. The pre-34 process-only reuse path and exact ownership rules are in [Streaming host](../internal/01-lifecycle/streaming-host.md#consent-and-projection-handoff).

Ordinary permissions use the public [RuntimePermissions](../../streaming-core/src/main/kotlin/io/screenstream/streaming/permission/RuntimePermissions.kt) capability supplied through `LocalRuntimePermissions` at the Activity root. One `RuntimePermission` enum owns the Android names, applicability rules and explanation choices. Its `isGranted(Context)` member performs a fresh grant check with applicability handling; for Notifications it also requires Android's app-wide notifications to be enabled. `shouldShowRequestPermissionRationale(Activity)` delegates Android's rationale signal:

| `RuntimePermission` | Android permission | Explanation |
| --- | --- | --- |
| Notifications | `POST_NOTIFICATIONS` | Notifications |
| LocalNetwork | `ACCESS_LOCAL_NETWORK` | LAN viewing |
| RecordAudio | `RECORD_AUDIO` | Microphone and device audio |
| Microphone | `RECORD_AUDIO` | Microphone |
| DeviceAudio | `RECORD_AUDIO` | Device audio |

Entries sharing the Android permission share grants and state; an audio explanation does not create a separate Android grant. MJPEG uses LocalNetwork and provides no audio stream. `state(permission)` exposes Granted/Requestable/SettingsSuggested/Requesting as a last-known request/UI hint, initialized by the first `state(permission)` call and updated for that permission's request. It does not monitor unrelated OS changes; even a cached Granted value is not authorization. `isGranted(permission)` reads the fresh OS grant and, for Notifications, app-wide notification availability. Call nonsuspending `tryRequest(permission)` on the UI thread: true means already granted or admitted to the app's explanation while resumed, **not** that permission was granted. False means no admission: the Activity is not resumed, or an ungranted request is blocked by the pending app slot. Requests are not queued; retry from a later valid action and recheck the OS before protected work.

The repository's [RuntimePermissionRoot](../../app/src/main/java/info/dvkr/screenstream/permissions/RuntimePermissionHost.kt) provides one app-owned UI slot above native MJPEG and other module UIs. Each module decides when to request and whether a later grant still matters to its action; leaving that screen does not cancel an admitted explanation, system dialog or Settings visit. A replacement host must provide `LocalRuntimePermissions` above module content so the address permission button can use it when displayed; a missing provider fails at that boundary. Supply lifecycle/launcher handling as well as the separate projection-consent host. Notification denial can hide foreground notifications; it neither authorizes capture nor prevents other ordinary-permission requests once the slot is free. The app persists whether it observed Android's rationale signal while notification access was denied. This is evidence for a Settings suggestion, not a grant or a recorded user decision; callback contents alone do not establish refusal or distinguish cancellation. A later false rationale signal does not erase valid earlier evidence. Without such evidence, an existing denial can remain Requestable; the device notification settings action provides recovery. Returning from a host-owned Settings visit updates that request's hint without scanning every permission or automatically starting native MJPEG capture. Consumers check fresh grants when needed. Projection consent retains its separate Access/attempt lifecycle.

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

