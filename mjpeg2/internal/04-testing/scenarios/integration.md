[Scenario index](../verification-contracts.md) · [Running tests](../testing.md)

# Integration inspections and real adapter smoke

Behavior: [maintained contract](../../01-lifecycle/streaming-host.md). Current configuration checks are source inspections; host tests read packaged assets and install the real Service/controller through a supplied test graph. Resolution through the actual module Koin graph remains unarranged. Actual projection, foreground and Android networking need L2; the platform smoke below has not run.

## Bounded configuration inspection

<a id="mjpeg-cfg-01"></a>

- **Inert module metadata and declared component.** Current module Kotlin and matching resources/manifest are inspected. Check bounded metadata and Service declaration. Id MJPEG, priority 25, localized metadata resources and MjpegStreamingModuleService are wired; metadata singleton does not construct controller/capture/listener resources. Service is not exported and declares mediaProjection foreground type.

<a id="mjpeg-cfg-02"></a>

- **Koin binding and inert controller installation.** Inspect ComponentScan/module annotations and factory/singleton bindings. MJPEG binds StreamingModule and registers HttpDelivery/NetworkAddressMonitor factories. The test graph installs the real controller with its exact runtime/Service without starting streaming resources. The actual module graph still needs a qualified resolution check; shared manager installation internals remain outside scope.

<a id="mjpeg-cfg-03"></a>

- **Manifest and runtime dependency configuration.** Current Kotlin integration depends on LAN/network/foreground access and actual patched CIO graph. Inspect MJPEG manifest, module/root build and version catalogue. Manifest declares INTERNET, ACCESS_LOCAL_NETWORK, ACCESS_NETWORK_STATE, foreground and media-projection foreground permissions; actual SDK/toolchain/runtime versions and patched network substitution are recorded. Inspection does not prove merged APK or device behavior.

## Packaged assets and real lifetime smoke

<a id="mjpeg-cfg-04"></a>

Host checks already read packaged HTML/logo and exercise parts of Service/controller and delivery lifetimes. The combined actual-graph/platform smoke is unarranged. On a packaged Android artifact, activate MJPEG, read root/placeholder, obtain consent, start capture, then Stop and shut down the module. The tested foreground/capture/media boundary should produce a recognizable JPEG and cleanly stop. Browser UI, all transforms/ABIs and shared-core internals remain outside this smoke.

Reopen a fresh lifetime using saved preferences. PIN policy governs preparation; the new runtime starts without prior capture, selected capture image, peer blocks, counters or endpoint history. Old owners cannot authorize successor work. Check applied current authority, not random PIN/token inequality.

Apply this outcome across the supported API 24–37 range, recording actual runtime, target, grants and device/build for L2 evidence. Distinct platform prerequisites include notification channels/background launch on 26+, foreground permission on 28+, projection service type on 29+, background FGS admission on 31+, notification permission/visibility on 33+, and per-session projection consent plus type permission on 34+. Notification denial does not itself prohibit starting a foreground service. Android 15 QPR1 and later can end projection on screen lock or user chip Stop; QPR/build is material, not just API 35. API 36 adds public tether metadata; API 37/target 37 requires LAN grant and an external client for incoming reachability. Android 16's optional local-network compatibility restriction needs separate recorded configuration.

Downstream Engine behavior also varies by SDK, but its implementation remains engine-owned. An MJPEG smoke establishes only the observed capture boundary on its recorded platform. Pure saved/wire/value histories inherit [the common supported-range contract](../testing.md#supported-android-versions) without an every-SDK execution claim.

[Foreground prerequisites](https://developer.android.com/develop/background-work/services/fgs/launch), [notification permission](https://developer.android.com/develop/ui/compose/notifications/notification-permission), [projection consent](https://developer.android.com/about/versions/14/behavior-changes-14#user-consent-required), [Android 15 QPR1 projection changes](https://developer.android.com/about/versions/15/behavior-changes-all) and [local-network enforcement](https://developer.android.com/privacy-and-security/local-network-permission) define these platform limits.
