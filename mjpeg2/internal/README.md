# MJPEG internals

Use the [module README](../README.md) and [Usage](../docs/usage.md) for viewing and host integration, and [Architecture](../docs/architecture.md) for the component and image-flow model. These internal pages describe exact algorithms, admission rules, resource ownership and failure boundaries.

Device/browser acceptance remains incomplete. The verification pages distinguish source-derived contracts, proposed checks and executed evidence.

## Find the rule for a change

### Lifecycle and coordination

| Maintainer task | Owning page |
| --- | --- |
| Change catalog/selection, Service-first launch, manager reports/deadlines, foreground Stop or consent handoff | [Streaming host](01-lifecycle/streaming-host.md) |
| Change capture startup/state/Stop, Suspended, first failures, consumer/projection cleanup or consent reuse conditions | [Module lifecycle](01-lifecycle/module-lifecycle.md) |
| Change controller event work, effective settings, readiness, network-loss timing, LAN request admission or native state projection | [Coordination](01-lifecycle/coordination.md) |

### Configuration

| Maintainer task | Owning page |
| --- | --- |
| Change saved settings/defaults/serialization, validation, edit policy or preference-write behavior | [Settings](02-configuration/settings.md) |
| Change address classification/filtering/identity, Android metadata/native scanning, uncertain evidence or discovery cleanup | [Network addresses](02-configuration/network-addresses.md) |

### Delivery

| Maintainer task | Owning page |
| --- | --- |
| Change listener/socket ownership, credential rotation/PIN limiter, request registration/retirement, sampled statistics or terminal cleanup | [HTTP delivery](03-delivery/http-delivery.md) |
| Change JPEG copy/publication, selected image, pools/leases, multipart writes/repeats or slow observations | [JPEG delivery](03-delivery/jpeg-delivery.md) |
| Change route/status/header/origin rules, cookie/token exchange, WebSocket wire or existing browser coordination/recovery/presentation | [Web client](03-delivery/web-client.md) |
| Change authored browser files, generated HTML or build/drift workflow | [Existing web build guide](../tools/web-client/README.md) |

### Verification

| Maintainer task | Owning page |
| --- | --- |
| Choose verification environment, evidence expectations and permitted test work | [Testing](04-testing/testing.md) |
| Find product scenarios, source contributors and current coverage gaps | [Verification contracts](04-testing/verification-contracts.md) |

The current business-logic inventory contains 23 non-UI Kotlin files; the six `ui/**` files remain outside that scope. The testing pages own detailed source coverage, environments and verification evidence.

## Source and evidence ownership

The main coordination entry point is [MjpegStreamingModuleController](../src/main/kotlin/io/screenstream/mjpeg/MjpegStreamingModuleController.kt). Its [capture attempt](../src/main/kotlin/io/screenstream/mjpeg/MjpegCaptureSession.kt), [HTTP delivery](../src/main/kotlin/io/screenstream/mjpeg/http/KtorHttpDelivery.kt) and [address monitor](../src/main/kotlin/io/screenstream/mjpeg/networkaddress/AndroidNetworkAddressMonitor.kt) own separate lifetimes. The capture attempt uses the internal [engine adapter](../src/main/kotlin/io/screenstream/mjpeg/MjpegCaptureEngineAdapter.kt); address discovery uses [native interface access](../src/main/kotlin/io/screenstream/mjpeg/networkaddress/NativeInterfaceAccess.kt). Follow the task links above for each owner's detailed contract.

Kotlin source, manifests and Gradle files define current declarations, identities, packaging and SDK/dependency configuration. The [capture engine Usage](../../screen-capture-engine/docs/usage.md) defines upstream borrowed-frame, lifecycle and image guarantees; MJPEG must not strengthen them.

Official [Android projection](https://developer.android.com/media/grow/media-projection), [foreground-service](https://developer.android.com/develop/background-work/services/fgs/service-types#media-projection) and [coroutine](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/-non-cancellable/) documentation own external platform/library rules. Tests provide evidence about product contracts, not new definitions of those contracts. Distinguish source inspection, proposed cases, executed results and unresolved acceptance when documenting a conclusion.

## Keep documentation aligned

Keep each exact algorithm, state/outcome table and protocol table on its owning page. Other pages summarize the dependency and link to that page. Coordination describes reconciliation; Module lifecycle describes capture outcomes; Streaming host describes launch/Android boundaries; delivery pages describe their resources and protocol.

When behavior or shared design changes, update the public guide and its internal extension. Preserve relative links for GitHub branches and clones. Keep Mermaid diagrams small, explain them in prose, and put detailed rules in text or tables. [GitHub diagram](https://docs.github.com/en/get-started/writing-on-github/working-with-advanced-formatting/creating-diagrams) and [link/heading guidance](https://docs.github.com/en/get-started/writing-on-github/getting-started-with-writing-and-formatting-on-github/basic-writing-and-formatting-syntax) describe the renderer's supported features.
