[Usage](usage.md) · Architecture · [Internals](../internal/README.md)

# MJPEG architecture

MJPEG connects one Android capture attempt to multiple HTTP viewers. Shared streaming infrastructure owns launch admission and Android coordination; the native controller owns its module's working state and cleanup. HTTP can remain open while capture is idle or stopped. This separation lets Stop end screen production without treating every browser response or address listener as a new capture session.

Read [Usage](usage.md) for controls and host integration, and the [internal pages](../internal/README.md) for exact algorithms and outcomes. This page explains how the components fit together; device/browser acceptance remains incomplete.

## Components and boundaries

```mermaid
flowchart TD
    App["App / Activity"] --> Manager["Streaming manager"]
    Manager --> Service["Original module Service"]
    Service --> Controller["MJPEG controller"]
    App --> ConsentHost["Activity consent host"]
    ConsentHost --> Access["Process capture Access"]
    Controller --> Capture["Capture attempt"]
    Capture --> Access
    Capture --> Engine["Capture engine"]
    Controller --> Monitor["Address monitor"]
    Controller --> Http["HTTP delivery"]
    Engine -->|"borrowed JPEG"| Capture
    Capture -->|"synchronous copy"| Http
    Http --> Viewer["HTTP viewers"]
```

The arrows show interaction. Settings, manager and `ScreenCaptureAccess` live at process scope; the consent host belongs to the Activity. Each controller owns its address monitor and HTTP delivery and retains the latest capture attempt for cleanup. Its original Android Service retains the controller reference and performs platform foreground calls, without a separate media worker.

| Component | Responsibility |
| --- | --- |
| `:app` | Application DI, Activity/bootstrap/Exit, consent hosting, shell permissions and notification presentation. |
| `:streaming-core` | Fixed module catalog/manager, instance/attempt API, Service base, selection/education settings, foreground Stop and capture Access. |
| `:common` | Shared UI, notification interfaces, preference storage and helpers. |
| `:mjpeg2` | Native descriptor/Service/controller, saved mode settings, address discovery, capture session and engine adapter, HTTP delivery and current native screen. |
| `:screen-capture-engine` | Projection-backed image production, transformations, JPEG encoding and bounded borrowed-frame delivery. |

Legacy MJPEG, RTSP and PlayStore WebRTC use a separate `:streaming-legacy` adapter and retain their own media, settings and permissions. Core has no dependency on that adapter. Native `MJPEG` has the lifecycle described here; [Streaming host](../internal/01-lifecycle/streaming-host.md#activity-lifetime-and-legacy-boundary) explains the legacy boundary.

## Lifetimes and identities

An immutable module descriptor identifies a selectable mode without opening resources. The manager reserves a launch before dispatching the descriptor's Service. Only an accepted delivery invokes the Service factory and installs an inert controller; ordinary work starts after installation.

| Identity | Lifetime and purpose |
| --- | --- |
| Module `Id` | Stable catalog selection, `MJPEG` for native MJPEG. |
| `InstanceId` | One module launch; replacement/restart gets a new UUID. |
| `CaptureAttemptId` | One accepted Start in that instance; retained through attempt cleanup. |
| Discovery address ID | One observed IP plus IPv6 scope, retained across filters/metadata until confirmed disappearance. |
| HTTP server UUID | One planned address/port incarnation; physical retries preserve it. |
| Physical listener identity | One bind attempt/CIO engine; request admission addresses this exact listener. |
| Capture source identity | One accepted HTTP production epoch; suspension/recovery can replace it within the same attempt. |
| Credential generation | One runtime viewing-authorization epoch; independent of capture. |

These identities solve different races. Exact attempt Stop cannot end replacement capture. A stale server Retry cannot reset a replacement server. Late JPEG copies cannot restore an image after their production source is revoked. Old WebSocket state cannot authorize a later credential generation. [Coordination](../internal/01-lifecycle/coordination.md) and [HTTP delivery](../internal/03-delivery/http-delivery.md) describe where each comparison occurs.

## Control and observation

The manager handles process transitions on Main, Android's main thread. Its Runtime binds admission and reports to the original launch. The native controller serializes commands, settings, address changes and capture observations in one event loop on the background Default dispatcher. Each owner checks whether new work is still allowed when admitting it; shutdown closes admission independently of that loop and begins cleanup even if the loop is blocked.

The capture attempt runs in controller scope and continues entered cleanup in `NonCancellable`. HTTP has its own runtime on the IO dispatcher and control monitor; discovery has independent scan/close work. These owners can begin cleanup independently, although one stuck obligation can leave total cleanup pending. Exact scheduling and admission rules are in [Coordination](../internal/01-lifecycle/coordination.md).

StateFlows provide latest snapshots, not a guaranteed history of every transition. Manager state and current instance ID are not an atomic pair. Capture state, HTTP Info, permission and saved settings also come from separate observations. Each owner reads the facts it needs at its own admission boundary rather than assuming a global snapshot.

### Saved, accepted and applied values

```mermaid
flowchart TD
    Stored["Saved preferences"] --> Observed["Controller observation"]
    Observed --> Effective["Effective settings / edit policy"]
    Effective --> Engine["Desired image parameters"]
    Effective --> Selection["Address filter / permission"]
    Selection --> Plan["HTTP server plan"]
    Effective --> Access["HTTP access / page configuration"]
```

Saved values become effective only after controller observation and edit-policy checks. Backend, HTTP port and access settings stay fixed through consent, capture and cleanup; live fields can change. Successful cleanup allows deferred setup values to apply. Copy URL uses applied HTTP credentials, and engine convergence follows its own frame-metadata contract. [Settings](../internal/02-configuration/settings.md) and [Coordination](../internal/01-lifecycle/coordination.md#effective-settings-and-saves) describe these boundaries.

## Capture and image delivery

The capture attempt obtains consent, promotes its original Service to foreground, then creates the projection. Successful engine construction transfers projection ownership to the engine. [MjpegCaptureSession](../src/main/kotlin/io/screenstream/mjpeg/MjpegCaptureSession.kt) retains capture/cleanup outcomes, receives borrowed JPEGs from the [engine adapter](../src/main/kotlin/io/screenstream/mjpeg/MjpegCaptureEngineAdapter.kt), and forwards their byte count and synchronous copier to HTTP. The engine owns geometry, metrics, configuration/output metadata, timing and encoding; [Module lifecycle](../internal/01-lifecycle/module-lifecycle.md) describes startup and cleanup.

The copier is borrowed authority: it is usable only on the frame callback's thread before the callback returns. HTTP can decline an offer or copy it once into owned storage outside its monitors, then checks source identity before publishing. The selected immutable byte prefix can be leased by multiple responses. Old frames remain valid for existing writers until their leases end; selection changes do not overwrite them.

Each media response pins its initial JPEG before sending headers. A single JPEG response sends that pin; MJPEG advances to the newest selected frame and skips intermediate publications. If there is no newer publication, MJPEG can repeat the current JPEG after one second without another capture/encode. This bounds ordinary frame queuing, not total byte memory: JPEG sizes, active response leases and copies outside the module still matter. [JPEG delivery](../internal/03-delivery/jpeg-delivery.md) owns the pool, lease and write rules.

Capture phase and selected image availability are separate. Starting can retain an older selected image. Suspended/Failed clear it. Ordinary Stop selects according to stopped-image policy. An existing MJPEG reader can wait for image availability; a new request without a pin receives 503. A capture failure remains Failed through successful cleanup until a new accepted attempt.

## HTTP and networks

Discovery selects one matching interface per supported IP/scope and retains candidates whose absence a scan cannot confirm. The controller passes permitted addresses to HTTP; Start additionally requires a fresh matching discovery observation. See [Network addresses](../internal/02-configuration/network-addresses.md) and [Start readiness](../internal/01-lifecycle/coordination.md#discovery-plan-and-start-readiness).

Each scoped IP/port has a socket owner serializing bind and cleanup. A bind failure affects that server, while uncertain listener cleanup stops delivery to prevent a successor from assuming the socket is free. Retry targets the exact failed server UUID. [HTTP delivery](../internal/03-delivery/http-delivery.md) defines retry and cleanup rules, including the [pinned CIO network patch](../../ktor-network-patched/build.gradle.kts) packaged through [root dependency substitution](../../build.gradle.kts).

Listeners share access, routes and selected JPEG. Credential rotation prevents old readers from acquiring another frame and revokes old WebSockets; an acquired JPEG lease may still finish. Sampled endpoint history rows are separate from physical response jobs. Counters include completed payload writes from active and finished responses, without proving network receipt or display. See [HTTP accounting](../internal/03-delivery/http-delivery.md#client-history-and-statistics).

The browser is an independent viewer: Connection, Media, Presentation and Page coordinate socket state, image replacement, display and page lifecycle. It does not issue capture commands. [Web client](../internal/03-delivery/web-client.md) owns protocol and current browser behavior; the existing [build guide](../tools/web-client/README.md) owns authored/generated assets.

## Shutdown and completion evidence

There are three completion boundaries:

1. **Capture Stop** requests cancellation of the exact attempt; the module remains open.
2. **Capture session cleanup** settles available media, consumer, foreground and projection-guard obligations. Successful engine stop confirms logical shutdown and projection-stop return, not every internal engine resource release.
3. **Module cleanup** joins admitted controller work, HTTP and monitor close, capture cleanup, final foreground removal and original Service destruction.

Manager deadlines bound waiting and allow UI progress while cleanup is unconfirmed; owners continue their cleanup independently. A deadline does not prove physical resource release or successful cleanup. Failed manager outcomes are retained; Unresponsive can recover with a fresh report. See [Streaming host](../internal/01-lifecycle/streaming-host.md#reports-clocks-and-deadlines) and [Module lifecycle](../internal/01-lifecycle/module-lifecycle.md#controller-shutdown-and-complete-module-cleanup).

Device/OEM lifecycle, browser compatibility, memory/FD behavior and rendering still require acceptance evidence. The [testing guide](../internal/04-testing/testing.md) records verification scope and evidence.
