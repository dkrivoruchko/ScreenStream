# Testing MJPEG

[Internals](../README.md) · [Scenario index](02-verification-contracts.md)

Use the [scenario index](02-verification-contracts.md) to find the histories affected by a change. Behavior belongs to the linked public and internal owners; tests check those contracts.

<a id="from-required-behavior-to-evidence"></a>

## Current state of the test sources

The local suite is in `src/test/kotlin` and uses JUnit4 runners and rules with Kotlin test assertions. A scenario or `// Cases:` marker identifies intended behavior, not a passed check. Record commands, environments, results and scenario IDs with the change or release.

Fixtures supply a no-op Foundation `Logger` in their test-owned Koin container. Start it before constructing affected owners and stop it after owner cleanup; fixtures with an existing graph add the binding there.

Keep markers such as `// Cases: MJPEG-CAP-13`. Use existing constructor seams, module-internal access and observable outputs. Do not reflect on private members or widen production APIs for tests. Testability changes are separate design changes requiring explicit approval.

## What the local suite covers

The suite exercises settings and edit policy, controller admission and reconciliation, the capture adapter, discovery, HTTP/access, JPEG storage and wire output, the web protocol and focused Compose behavior. Analytics and log output are excluded. App assembly and web-client checks do not compile or run the Kotlin suite.

Local [UI tests](../../src/test/kotlin/io/screenstream/mjpeg/ui) cover capture commands, address actions (exact open/share/copy payloads, plain-URL fallback, retry and permission callbacks), PIN-row masking, reveal reset and sensitive copying, and the settings editors: port validation, setup locks and queued port saves rechecked when they execute, PIN drafts and regeneration, Size mode drafts, Frame-rate mode drafts and decimal-comma intervals, Background HEX/slider/palette synchronization, Placement merges into newer saved layouts and search. [Frame-rate tests](../../src/test/kotlin/io/screenstream/mjpeg/ui/FrameRateSettingsUiTest.kt) also check that an invalid selected Interval preserves the saved rate while an invalid dormant draft permits an Auto save. The port, Size, Frame-rate, Background and Placement tests run against real settings groups and an isolated production store; the PIN editor uses supplied state and recorded transforms. The [save-failure test](../../src/test/kotlin/io/screenstream/mjpeg/ui/SettingsSaveFailureUiTest.kt) drives a real write fault through `MjpegContent` and checks the rendered snackbar and unchanged stored data. Callbacks, intents, clipboard and permission objects are supplied, so these tests do not establish actual grants, browser/share-sheet handling, system clipboard presentation or QR rendering. [Address-row mapping tests](../../src/test/kotlin/io/screenstream/mjpeg/ui/MjpegAddressServerMappingTest.kt) check ready-link mapping from supplied server, permission and token inputs, not the controller's token source.

The Compose tests use the v2 rule and native Robolectric graphics. The [shared fixture](../../src/test/kotlin/io/screenstream/mjpeg/ui/MjpegUiTestFixtures.kt) joins admitted saves through the store owner with an asynchronous identity transaction and a bounded `waitUntil`: a blocking join can stop Compose from finishing the save, and Compose idleness alone does not establish disk completion ([synchronization guidance](https://developer.android.com/develop/ui/compose/testing/synchronization)).

[Integration scenarios](scenarios/09-integration.md) describe module declarations, supplied graphs and existing device-test sources with their evidence limits.

The [Service adapter tests](../../src/test/kotlin/io/screenstream/mjpeg/MjpegStreamingModuleServiceTest.kt) check MJPEG metadata and real controller cleanup after promotion failure, waiting restoration failure and unexpected destruction ([CAP-13](scenarios/03-capture.md#mjpeg-cap-13)).

## Running local tests

From the repository root:

```shell
./gradlew :streaming:mjpeg:testDebugUnitTest
```

Select a class with `--tests 'fully.qualified.TestClass'`. Inspect `streaming/mjpeg/build/reports/tests/testDebugUnitTest/` and `streaming/mjpeg/build/test-results/testDebugUnitTest/`. `UP-TO-DATE`, `FROM-CACHE` and `SKIPPED` mean no new execution in that invocation; add `--rerun-tasks` only when a fresh run is deliberately required.

The [module build](../../build.gradle.kts) owns dependencies, Android resources, the production toolchain and the separate unit-test Java launcher, heap limit and JVM arguments; these apply to host test workers only, not to the Gradle daemon. Local tests need no other runner, init script, Gradle home or flags. CIO tests that bind IPv4 and IPv6 independently require both host loopback families.

## Running device tests

The [module build](../../build.gradle.kts) configures `AndroidJUnitRunner` for the library's own test APK. With a connected physical device, select the class to run:

```shell
./gradlew :streaming:mjpeg:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.screenstream.mjpeg.device.MjpegCapturePipelineTest
```

To select one method, use the runner's `Class#method` filter and quote the whole Gradle argument:

```shell
./gradlew :streaming:mjpeg:connectedDebugAndroidTest \
  '-Pandroid.testInstrumentationRunnerArguments.class=io.screenstream.mjpeg.device.MjpegLifecycleDeviceTest#systemProjectionChipStopRestoresIdleHttpAndRestartRequiresFreshConsent'
```

Quote selector values containing spaces the same way, for example `'-Pandroid.testInstrumentationRunnerArguments.mjpeg.systemStopChipDescription=<observed SystemUI description>'` ([AndroidJUnitRunner arguments](https://developer.android.com/studio/test/command-line#am-instrument-options)). Inspect `streaming/mjpeg/build/reports/androidTests/` and `streaming/mjpeg/build/outputs/androidTest-results/`. Don't launch an emulator without approval. Record the device/build, target SDK, grants and results with the change.

| Instrumentation source | Assertions / qualification needs |
| --- | --- |
| [Capture pipeline](../../src/androidTest/kotlin/io/screenstream/mjpeg/device/MjpegCapturePipelineTest.kt) | Changing decoded JPEG/multipart output, two increasing live Produced counts with nonzero Encoded counts in the native panel, available current FPS in the expanded chart, retained totals and unavailable current FPS after Stop, restart, fresh capture attempts and Exit releasing Service/viewer/listener resources over device loopback. Statistics checks keep producing fresh source changes; they assert no exact counts, rates or sampling cadence. |
| [Lifecycle](../../src/androidTest/kotlin/io/screenstream/mjpeg/device/MjpegLifecycleDeviceTest.kt) | Real declined consent, late Android approval received after Exit without resurrection, and SystemUI projection-chip stop followed by fresh consent. The chip-stop history uses 100% scale and is skipped below API 35. The chip needs an [Android 15 QPR1+ runtime](https://developer.android.com/media/grow/media-projection#status_bar_chip_auto_stop). On API 35 only, without a nonblank chip selector argument, a missing default SystemUI chip label skips the history through a visible JUnit assumption before the host or capture starts, marking that runtime unqualified; the missing private resource does not show that the device lacks the chip, and a skip establishes no chip action, post-stop or restart result. Otherwise (an explicit selector, a present default label, or API 36+), the test fails before capture stops when its selector is unavailable or finds no chip, so such a failure does not distinguish an ineligible runtime from unqualified selectors. Late approval requires the old dialog/result to remain deliverable. |
| [Reconfiguration](../../src/androidTest/kotlin/io/screenstream/mjpeg/device/MjpegReconfigurationDeviceTest.kt) | A legal saved crop rejected by real Engine geometry selects a placeholder; valid target settings recover changing decoded output on the same attempt/viewer. Rotation checks Activity recreation, the same capture attempt and multipart connection, and fresh output after restoration. It uses 100% scale, independently read platform display dimensions and literal asymmetric colour checks. A rotation the platform does not apply fails the test. |
| [LAN permission](../../src/androidTest/kotlin/io/screenstream/mjpeg/device/MjpegLanPermissionTest.kt) | API/target 37 grant gating of a selected native IPv4 endpoint, external placeholder JPEG/multipart receipts, and continuing device loopback. Requires the external peer runner below; without its run arguments this history is skipped. It does not start live capture or independently test kernel blocking. |
| [Wire-reader fixtures](../../src/androidTest/kotlin/io/screenstream/mjpeg/device/MjpegMultipartReaderTest.kt) | Literal in-memory parts check the [independent bounded reader](../../src/androidTest/kotlin/io/screenstream/mjpeg/device/MjpegMultipartReader.kt): framing, duplicate/malformed headers, lengths, truncation and short reads. These parser checks do not start capture or establish transport behaviour. |
| [Multipart connection fixtures](../../src/androidTest/kotlin/io/screenstream/mjpeg/device/MjpegMultipartConnectionTest.kt) | Real Android loopback peers check aggregate read deadlines under stalled/progressing input, late-match/EOF rejection, interruption by owner close, successive budgets and independent HTTP chunk/fixed-length framing. These fixtures do not start the module, capture or production CIO. |

The [device support](../../src/androidTest/kotlin/io/screenstream/mjpeg/device/MjpegDeviceTestSupport.kt) drives module controls through Compose, real system consent through UIAutomator and output through multipart/decoded-image assertions. Whole-display consent uses resource-based selectors for current and legacy SystemUI and verifies the selected mode; unrecognized modern controls fail qualification. A qualified `mjpeg.systemStopChipResource` or `mjpeg.systemStopChipDescription` runner argument replaces the lifecycle test's default chip selector. The [test host](../../src/androidTest/kotlin/io/screenstream/mjpeg/device/MjpegCaptureTestHost.kt) records consent launch/result identities without retaining consent Intent contents. Cleanup after a failed assertion is containment, not a successful shutdown verdict.

The multipart viewer owns a raw socket through [MjpegMultipartConnection](../../src/androidTest/kotlin/io/screenstream/mjpeg/device/MjpegMultipartConnection.kt). Each complete frame wait or close drain has one 15-second elapsed-time read budget, including HTTP chunk framing. Remaining socket read timeouts interrupt blocked IO, and completion is checked again before accepting a match or EOF. Time spent matching/decoding counts toward the verdict, so a late returned match fails; the budget cannot preempt matcher/decoder execution that does not return. Timeout closes that viewer's socket; no delayed watchdog can close a later read. A completed HTTP response (including its zero chunk/trailers), transport EOF or a socket error before local close remains a close receipt, without classifying the error as a reset; the existing listener/Service checks establish their separate Exit obligations. These read budgets do not bound request writes or unrelated cleanup.

The pipeline uses `ImageSettings()` defaults. These sources exercise MJPEG settings, lifecycle and HTTP integration; they do not establish a full image-transform or backend matrix.

### Device evidence

Each run writes evidence under the test APK's `cache/mjpeg-device-evidence/<UUID>/`: matching JPEG samples and, on a JPEG-wait failure, the latest received bytes, decoded dimensions/sample colours, state/result receipts and native window XML/PNG; rotation also records Activity recreation and expected/actual platform geometry. Network-frame assertions decide the media result; screenshots only diagnose, and diagnostic failures are attached to the original failure. Files exist only for observations the test reached.

Completed artifacts are registered with UIAutomator's [ResultsReporter](https://developer.android.com/reference/androidx/test/uiautomator/ResultsReporter) as `additionalTestOutputFile_*` instrumentation entries; verify that the host actually retained them. Private cache does not survive uninstall, so for a direct run archive the originals first into a new host directory, separately from Gradle's XML results:

```shell
mjpeg_evidence_dir="$(mktemp -d /private/tmp/mjpeg-evidence.XXXXXX)"
adb -s emulator-5554 exec-out run-as io.screenstream.mjpeg.test tar -cf - cache/mjpeg-device-evidence > "$mjpeg_evidence_dir/evidence.tar"
```

### LAN peer runner

[lan_peer.py](../../tools/android-tests/lan_peer.py) runs on the host computer as the external HTTP client against the production Android CIO listener. It uses only Python's standard library and `adb` and isn't packaged in the APK. It starts the selected instrumentation test and exchanges nonce/phase/endpoint-bound receipts with [MjpegLanPeerProtocol](../../src/androidTest/kotlin/io/screenstream/mjpeg/device/MjpegLanPeerProtocol.kt).

Build and install the library's own test APK, then deny `ACCESS_LOCAL_NETWORK` before instrumentation. For an already running, approved API 37+ emulator:

```shell
./gradlew :streaming:mjpeg:assembleDebugAndroidTest
adb -s emulator-5554 install -r -t streaming/mjpeg/build/outputs/apk/androidTest/debug/mjpeg-debug-androidTest.apk
adb -s emulator-5554 shell pm revoke io.screenstream.mjpeg.test android.permission.ACCESS_LOCAL_NETWORK
python3 -B streaming/mjpeg/tools/android-tests/lan_peer.py --serial emulator-5554 --component io.screenstream.mjpeg.test/androidx.test.runner.AndroidJUnitRunner --topology emulator-console --interface eth0 --console-port 5554 --output /private/tmp/mjpeg-lan-evidence
```

Use the selected device's serial and the installed test APK's instrumentation component; `install -t` [allows test packages](https://developer.android.com/tools/adb). The helper restores denial after instrumentation ends, because revoking during the run can kill its process. It neither builds/installs APKs nor launches an emulator. `adb` must be on PATH or supplied with `--adb`; an authenticated emulator console uses `~/.emulator_console_auth_token`.

- `emulator-console` uses console TCP redirection to the guest Ethernet endpoint `10.0.2.15` on `eth0`; serial and console port must match. It establishes an emulated external path, not physical LAN/Wi-Fi or independent kernel enforcement ([emulator networking](https://developer.android.com/studio/run/emulator-networking-interconnect)).
- `direct` targets the selected native address from the peer machine, without a console port; direct routing alone does not prove a physical LAN.

The helper writes `instrumentation.log`, per-phase `*-request.json` and `*-peer.json`, and `cleanup.json` under a unique run directory inside `--output`. `summary.json` is written only after both peer phases, instrumentation and cleanup succeed; a failed run may leave only partial evidence.

[test_lan_peer.py](../../tools/android-tests/test_lan_peer.py) checks the helper's HTTP and console handling against local sockets and mocks, without `adb`, a device or an emulator: `python3 -B -m unittest discover -s streaming/mjpeg/tools/android-tests`.

## Choosing an environment

L0/L1/L2 are environments, not test sizes. Use the lowest level that exercises the required boundary.

| Level | Environment | Evidence |
| --- | --- | --- |
| L0 | Host JVM, no Android execution | Value/access policies, JPEG storage/readers, independent saved-data and wire fixtures |
| L1 | Robolectric, with Compose, Ktor test engine or CIO loopback where needed | Simulated Android adapters and UI behavior, preferences/assets, in-process routes or real host sockets |
| L2 | Recorded Android build on a device | Real foreground/projection admission, native discovery, local-network enforcement and incoming traffic |

L0/L1 sources are in `src/test/kotlin`; instrumentation is in `src/androidTest/kotlin`. The capture and LAN histories above use a [library test host](../../src/androidTest/kotlin/io/screenstream/mjpeg/device/MjpegCaptureTestHost.kt) with production Core/MJPEG modules, system consent, Service, Engine and CIO, plus supplied notification, analytics and permission-request collaborators. They do not qualify the real app graph, app-owned permission request UI, physical LAN/kernel enforcement or the full packaged-app lifetime.

[Ktor's test engine](https://ktor.io/docs/server-testing.html) binds no sockets: listener, bind and transport histories need real CIO. [Robolectric](https://robolectric.org/best-practices/) simulates Android; host success does not establish device/OEM behavior, browser rendering or reachability from another device.

Available production seams:

1. The controller forwards its `MjpegEngineFactory` to each capture session and accepts `ScreenCaptureAccess`. Test one attempt's phases at `MjpegCaptureSession`, which accepts the same factory. The controller's internal `state` (`@VisibleForTesting(otherwise = NONE)`) exposes published screen state without Compose.
2. The real `MjpegStreamingModuleService` hosts the controller integration ([CAP-13](scenarios/03-capture.md#mjpeg-cap-13)). `HttpDelivery` and `NetworkAddressMonitor` can be fake or real.
3. `KtorHttpDelivery` accepts an `HttpListener` factory, defaulting to `CioHttpListener`. Controlled listeners can hold or fail start, termination and cleanup; bind, transport and socket-release evidence still needs CIO.
4. [`calculateMjpegCurrentFps`](../../src/main/kotlin/io/screenstream/mjpeg/MjpegCaptureSession.kt) (`@VisibleForTesting(otherwise = PRIVATE)`) has literal numeric tests. Session/controller fixtures can construct only the Engine's lawful initial zero statistics, so they do not establish rates from nonzero Engine emissions.
5. Discovery accepts an `observationDispatcher` for its owned observation scope only (default `Dispatchers.Default`). Native scans and close still use `Dispatchers.IO`; there is no injectable clock. See [evidence rule 3](#evidence-rules).

## Android versions

The [root build](../../../../build.gradle.kts) owns SDK/build-tool baselines. The library takes minimum/compile SDK and build tools from it; the [app build](../../../../app/build.gradle.kts) sets the app target SDK, while the module build sets the library test APK target through `testOptions.targetSdk`. Compile/target SDK do not cap supported runtime versions. Use one representative SDK for platform-independent behavior and qualify relevant transitions explicitly:

| Boundary | Transition |
| --- | --- |
| [Discovery](scenarios/04-network.md#mjpeg-net-09) | Getter fallback before API 26; callback metadata from 26; request capabilities from 30; public tethering events from 36 |
| [Local network](scenarios/04-network.md#mjpeg-lan-01) | Android 17 enforces `ACCESS_LOCAL_NETWORK` for target 37+; lower targets have implicit access. Android 16's separate opt-in restriction is deprecated. |
| [Capture foreground](scenarios/03-capture.md#mjpeg-cap-13) | Foreground types from API 29; stricter rules from 34 |
| [Consent/first Start](scenarios/03-capture.md#mjpeg-cap-12) | Core's legacy consent reuse versus fresh consent from API 34; single-app sharing and projection-callback dimensions on modern Android |
| [Device integration](scenarios/09-integration.md#mjpeg-cfg-04) | Real consent, projection and Service behavior in the library test APK; loopback and external-peer traffic |

[Local-network enforcement](https://developer.android.com/privacy-and-security/local-network-permission) includes incoming TCP, so qualify it with a client on another device ([LAN-05](scenarios/04-network.md#mjpeg-lan-05)). Record device/build/QPR where projection behavior depends on them.

## Doubles, shadows and fault adapters

1. Fake MJPEG interfaces through the seams above; do not shadow MJPEG constructors or methods.
2. Prefer supported framework shadows. [`ShadowPowerManager.turnScreenOn(boolean)`](https://robolectric.org/javadoc/latest/org/robolectric/shadows/ShadowPowerManager.html) changes interactivity, and the [4.17 implementation](https://github.com/robolectric/robolectric/blob/robolectric-4.17/shadows/framework/src/main/java/org/robolectric/shadows/ShadowPowerManager.java) also broadcasts screen on/off when the state changes; under `PAUSED`, run the main looper to deliver it. Use a custom framework shadow only where supported APIs cannot supply the required behavior or failure.
3. For writer output/faults, intercept public `ApplicationSendPipeline.After` and write the response's own `WriteChannelContent` to a controlled channel, scoped to that response, without replacing transport classes. This establishes writer behavior, not CIO transport.
4. The [storage-failure tests](../../src/test/kotlin/io/screenstream/mjpeg/settings/MjpegSettingsPersistenceTest.kt) seed a valid preferences file and block its `.tmp` path with a nonempty directory before constructing the single store owner. The UI save-failure test reuses that real write fault rather than an injected error callback.

## Harness fidelity traps

These are library implementation observations, including the screen-broadcast behavior above and [WebSocket queues](../02-delivery/03-web-client.md#limits-and-settlement). Recheck the pinned sources when the [catalog](../../../../gradle/libs.versions.toml) changes the library.

1. **Repeated headers.** The test client merges repeated header values before server delivery: comma-joined normally, `; ` for `Cookie`, separate only for date headers ([test engine](https://github.com/ktorio/ktor/blob/3.6.1/ktor-server/ktor-server-test-host/common/src/io/ktor/server/testing/client/TestHttpClientEngine.kt), [`mergeHeaders`](https://github.com/ktorio/ktor/blob/3.6.1/ktor-client/ktor-client-core/common/src/io/ktor/client/engine/Utils.kt)). Assert server-side `getAll` values. For genuinely separate fields, use raw sockets with CIO as in [WebRoutesDuplicateRequestHeadersCioTest](../../src/test/kotlin/io/screenstream/mjpeg/http/web/WebRoutesDuplicateRequestHeadersCioTest.kt).
2. **Response/upgrade lifetimes.** Test-engine producer and WebSocket-upgrade Jobs differ from CIO's. Controlled writers at the send boundary and routing-call Jobs observed through `RoutingCallStarted` establish MJPEG's modeled ownership. Call completion and socket closure need real CIO.
3. **One scheduler domain.** `testApplication` runs its block through `runTest` on a real dispatcher, carrying a hidden `TestCoroutineScheduler` ([`runTestWithRealTime`](https://github.com/ktorio/ktor/blob/3.6.1/ktor-test-dispatcher/common/src/TestCommon.kt)). Give a manually clocked writer its `StandardTestDispatcher` and matching scheduler in one context; do not create a competing dispatcher inside `testApplication`. Use `runTestApplication` from a context without a test scheduler, as `withWriterOnTestScheduler` in [JpegResponseWriterTest](../../src/test/kotlin/io/screenstream/mjpeg/http/media/JpegResponseWriterTest.kt) does. Keep jobs structured and cancel/join them in bounded cleanup; a sleep or longer timeout cannot replace a checkpoint.

## Evidence rules

1. **Independent expectations.** Use literal values and recognizable bytes; parse saved JSON/multipart output independently of production serializers/writers. Check redaction and actual authorization. Random credentials can coincide: never require newly generated values to differ.
2. **Checkpoints.** `StateFlow` can conflate transitions. Races need entry/completion barriers and allowed outcomes, not delays or incidental counts. Reader-model interpositions establish real-writer behavior only where a writer test interposes at the same point.
3. **Clocks and scheduling.**
   - Controller heartbeat reads `SystemClock.uptimeMillis()`. Address-loss deadlines, stop-delay warnings, slow-viewer cooldown and statistics history read `elapsedRealtime()`, as do capture FPS receipts and HTTP PIN blocks, bind retries and request history. Coroutine virtual time advances neither clock.
   - Except discovery's observation scope, fixed `Default`, `IO` and `Main.immediate` dispatchers are not replaced by a test dispatcher; sockets use real threads.
   - In the [CIO bind-recovery test](../../src/test/kotlin/io/screenstream/mjpeg/http/HttpDeliveryCioIntegrationTest.kt), real IO delays do not advance paused Robolectric elapsed time. Failed is published before physical listener cleanup; keep driving `ShadowSystemClock` while yielding to real IO until public Listening and an actual HTTP request establish recovery.
   - Discovery deadlines use `System.nanoTime()`. Cadence tests instrument `networkaddress` under Robolectric `PAUSED` so those reads follow its clock, and pass a `StandardTestDispatcher` using the same scheduler they advance. Advance the Robolectric clock and scheduler together.
   - Native IO reads need individual entry/completion receipts. Check each read's unique inventory witness after `runCurrent()` returns: publication precedes arming the next deadline. Bound host waits with host monotonic deadlines, independent of Robolectric; instrumented `System.nanoTime()` may be shadowed.
   - With recurring timers, advance to explicit times and run current work; do not use `advanceUntilIdle()`. See [coroutine test scheduling](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-test/).
   - [JpegResponseCioTest](../../src/test/kotlin/io/screenstream/mjpeg/http/media/JpegResponseCioTest.kt) bounds its raw response reader with one host monotonic deadline across status, headers and body, and a history-specific body cap. Reading one byte beyond that cap preserves physical EOF evidence, including the revoked response that advertises four bytes but sends none. The budget covers reads, not request writes or complete test cleanup.
4. **Ownership.** Saving, observation and application are separate facts, as are Stop acceptance, publication cutoff, cancellation, exact consumer completion and physical cleanup. Retained images/statistics do not prove active capture/requests; a shutdown request does not prove transport settlement.
5. **Payload accounting.** Nonzero counters need written payloads. Inject partial-payload, multipart-framing and flush failures separately. Counters measure writes, not peer receipt/display.
6. **Supplied inputs.** Engine/native fixtures do not establish projection revocation, native discovery, real app-graph resolution or upstream behavior. Label nonconforming inputs defensive: short frame copies, throwing Engine state flows or undocumented problems. Derive a failing flow from a real `StateFlow` with `onSubscription`; do not implement the unsupported [interface](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/-state-flow/) yourself.
7. **Statistics.** Check documented baselines/lifetimes, preserving their approximate nature; do not add exact render-time, memory or performance guarantees.
