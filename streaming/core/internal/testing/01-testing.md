# Testing Core

[Core internals](../README.md) · [Verification scenarios](02-scenarios.md)

This guide explains how to verify Core with local tests: what the scope covers, which environment to use, how to drive Core through observable inputs, and what counts as evidence. The [contract owners](../README.md#contract-owners) table names the guide or KDoc that defines each behavior, and the [scenario catalogue](02-scenarios.md) lists the histories that verification must cover.

## Scope

Local tests exercise production Core with controlled collaborators. The catalogue owns verification histories; public guides and KDoc own behavior.

| Area / permanent IDs | Test source | Boundary |
| --- | --- | --- |
| Manager · `CORE-MGR-01–15` | [StreamingModuleManagerTest](../../src/test/kotlin/io/screenstream/core/manager/StreamingModuleManagerTest.kt) | Catalog, selection, startup, admission, commands, health, cleanup waits and problem episodes |
| Consent · `CORE-CAP-01–10,12–13` | [ScreenCaptureAccessTest](../../src/test/kotlin/io/screenstream/core/capture/ScreenCaptureAccessTest.kt) | Correlation, cancellation, grants, acquisition, reuse and observer fault isolation; `CAP-11` stays retired |
| Base Service · `CORE-SVC-01–06` | [StreamingModuleServiceTest](../../src/test/kotlin/io/screenstream/core/module/StreamingModuleServiceTest.kt) | Foreground ownership/presentation, supplied failures, refresh, stop/destruction and defensive timeout input |
| Notification actions · `CORE-ACT-01–03` | [StreamingModuleActionReceiverTest](../../src/test/kotlin/io/screenstream/core/manager/StreamingModuleActionReceiverTest.kt) | Factory/receiver routing, PendingIntent target identity and malformed-input rejection |
| Permission predicates · `CORE-PERM-01–03` | [RuntimePermissionTest](../../src/test/kotlin/io/screenstream/core/permission/RuntimePermissionTest.kt) | OS/target/grant policy, rationale hints and settings destinations |
| Settings · `CORE-SET-01–04` | [StreamingSettingsStoreTest](../../src/test/kotlin/io/screenstream/core/settings/StreamingSettingsStoreTest.kt) | Shared loading, independent durable fields, admitted cancelled edits and failure recovery |
| Consent host UI · `CORE-UI-01–10` | [ScreenCaptureConsentHostTest](../../src/test/kotlin/io/screenstream/core/compose/ScreenCaptureConsentHostTest.kt) | Education, Activity lifecycle, supplied launcher/results and finishing hook |
| Instance content UI · `CORE-UI-11–12` | [StreamingModuleInstanceContentTest](../../src/test/kotlin/io/screenstream/core/compose/StreamingModuleInstanceContentTest.kt) | Rendering decisions at explicit recomposition checkpoints |

App-owned permission request UI and notification-provider rendering/delivery are outside Core. Observer telemetry, log output, screenshots and trivial getters/defaults are not assertions. Observer failures are supplied inputs: tests check that consent remains correct, not what analytics observed. Core has no `src/androidTest` suite. These sources establish local behavior under Robolectric, not device qualification.

## Required behavior versus evidence

A scenario states required behavior; it doesn't claim that a test exists or passed. Keep four claims separate:

1. **Scenario.** The catalogue entry and its owner define what must hold.
2. **Test source.** A test that names a scenario ID shows intent. It counts only when its fixtures and assertions match current production and the owning contract.
3. **Fresh run.** A run with inspected results is evidence for the environment it ran in.
4. **Platform qualification.** Behavior that depends on the real platform, such as foreground admission, Android's consent dialog or a real `MediaProjection`, needs a recorded Android build and device. The local suite does not establish those platform outcomes.

Mark the scenarios a test targets with a comment such as `// Cases: CORE-MGR-05`, so that the catalogue and the tests can be cross-checked. A marker states intent; it doesn't mean the test covers every variant of that scenario. Keep run reports with the change or release that produced them, listing commands, SDK levels, results and scenario IDs. This guide is not a coverage dashboard.

## Choosing an environment

Core tests use JUnit4 runners and rules with Kotlin test assertions. They run as **local tests on the host JVM under Robolectric**, in `streaming/core/src/test/kotlin`, for Android services, clocks, lifecycle and UI. Android's [local tests guide](https://developer.android.com/training/testing/local-tests) describes this source set.

- **Test application.** The source set's `robolectric.properties` selects `SilentCoreTestApplication`, which replaces Core's log writer with one that discards output, so tests need no XLog setup. Non-UI fixtures can use `@Config(manifest = Config.NONE)`; Compose fixtures use an empty rule and explicitly register their own test Activity.
- **Use public lifecycle drivers.** Start a test `StreamingModuleService` subclass through Robolectric's Service controller with the Intent that the manager actually started, rather than calling internal hooks. The instance-content UI suite is the exception: it checks rendering, not Service lifecycle, so it admits Controllers through the manager's internal Service-start callback. Robolectric's [best practices](https://robolectric.org/best-practices/) recommend public lifecycle APIs over test-only entry points and advise against mocking `Context`.
- **Fidelity is limited.** Robolectric doesn't replicate the complete Android environment ([best practices](https://robolectric.org/best-practices/)). Foreground-service start restrictions, foreground-type enforcement, Android's consent UI and real projection creation need platform qualification.
- **Ordinary Service dispatch.** A test-owned `Context` wrapper can record `startService`, reject unexpected `startForegroundService` calls and supply startup exceptions. This verifies the chosen call and the manager's response, not Android's decision. [Background start limits](https://developer.android.com/about/versions/oreo/background) and [foreground-promotion restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start), including transitions between dispatch and delivery, need a recorded device and build. The local assertions cover supplied acceptance or rejection, not background eligibility on a device.
- **Platform results.** Where a history needs a platform result that Robolectric doesn't produce, such as a `MediaProjectionManager` acquisition that returns or throws, define a Robolectric shadow in the test source. It is a supplied input, not platform evidence.
- **Koin.** Construct the manager and the consent coordinator directly through their internal constructors, with a test notification provider and an inert observer except in protective fault histories. The Service, the notification-action receiver and `ScreenCaptureConsentHost` resolve their collaborators from Koin, so tests that run them start Koin with the exact test instances and stop it afterward. Koin's [testing guide](https://insert-koin.io/docs/reference/koin-test/testing) notes that this global context must be stopped between tests and rules out parallel tests in the same JVM.
- **SDK levels.** Behavior that changes across Android versions needs runs on both sides of the boundary, chosen with Robolectric's `@Config(sdk = …)`. Legacy consent reuse applies on SDK 24–33 and fresh consent from SDK 34; the projection-acquisition retry applies only below SDK 34. Manager tests use SDK 28, consent defaults to 34 with legacy cases on 24/33. Permission tests bracket 25/26, 32/33 and device/target 36/37; Service tests bracket their foreground-type and watcher boundaries. Use one representative SDK for invariant behavior; record which levels actually ran.

## Observable inputs and results

Drive Core through the inputs its real collaborators use, and assert what those collaborators can observe.

| Inputs a test controls | Results a test asserts |
| --- | --- |
| Manager commands: `selectModule`, `restartModule`, `exit`, `requestStreamStart`, `requestStreamStop`, `requestRecovery` | Snapshots of `StreamingModuleManager.state` |
| Module catalog, including invalid catalogs | Catalog order and construction failures |
| Service start delivery, through the Robolectric Service controller | Services the manager asked Android to start, and the Service's foreground state |
| A test Controller factory: return, throw or return a mismatched identity | Calls the fake Controller receives: `startModule`, commands, `requestModuleShutdown` and `awaitCleanup` |
| Reports through the `ManagerHandle`: `reportRunning` and `reportFailed` | `ManagerHandle.isCurrent()` at explicit checkpoints |
| Controller cleanup outcomes: true, false, throwing or held | Calls the fake notification provider receives |
| Consent requests with a controlled attempt `Job` and foreground callback | `ConsentResult` values, thrown exceptions and their causes |
| The calls `ScreenCaptureConsentHost` makes: resume, inactive, submit, education cancel, abandonment, result delivery and launch failure | Whether a request is waiting for a host, and which host can submit it |
| Projection acquisition, finish and save-for-reuse calls | Returned projections, exceptions and later admission outcomes |
| Permission grants, app enablement, target SDK and rationale | Availability, hints and launched settings Intent |
| Seeded settings, queued edits, caller cancellation and supplied storage faults | Loaded snapshots and durable values after writes settle |
| Compose actions, Activity lifecycle and supplied Activity results | Education, one launch/result association and controller content or fallback |

Avoid assertions on private fields, log output, reflection or internal Intent extras. Coordinator tests call internal host methods directly; only the separate Compose suite exercises the composable's wiring. Neither qualifies Android's external consent UI. Settings fixtures seed before construction and inspect durable bytes after edits settle; never create a second DataStore on an active path.

## Clocks and coroutine scheduling

Core reads two Android clocks, and neither is a coroutine clock.

- **Android clocks.** The manager measures startup and shutdown deadlines with `SystemClock.elapsedRealtime()`, which includes deep sleep, and heartbeat age with `SystemClock.uptimeMillis()`, which excludes it. Projection-acquisition retry timing also uses elapsed realtime. Under Robolectric, [ShadowSystemClock](https://robolectric.org/javadoc/latest/org/robolectric/shadows/ShadowSystemClock.html) controls both: advancing it moves both clocks, and simulated deep sleep moves only elapsed realtime.
- **Coroutine scheduler.** Virtual time in [kotlinx-coroutines-test](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-test/) skips delays only on test dispatchers. It doesn't move `SystemClock`, and advancing `SystemClock` doesn't resume a coroutine delay.
- **Fixed dispatchers.** The manager takes a dispatcher for its transitions and reports, but waits for Controller cleanup on `Dispatchers.Default`. Consent admission and the Service's capture-notification, foreground-release and stop calls switch to `Dispatchers.Main.immediate`. The settings store loads and writes on its own dispatcher, `Dispatchers.IO` unless a test supplies one. Work on dispatchers that a test doesn't replace runs in real time on real threads.

A deadline history therefore advances both: move the Android clock past the deadline, then run the scheduler or main looper that observes it. Moving only one can leave a deadline unobserved, or observed early. Choose one strategy for coroutine Main work per test class, either Robolectric's main looper or `Dispatchers.setMain` with a test dispatcher, and reset it afterward. With a replaced Main dispatcher, consent admission can stay queued until its scheduler runs. Compose rules own their scheduler/Main setup; do not install a competing test dispatcher.

## Checkpoints and cleanup

- **Use explicit checkpoints.** Hold fakes with `CompletableDeferred` gates or latches, for example a Controller whose `awaitCleanup` waits until the test releases it. Assert state only after a checkpoint you can name. Delays and incidental call counts are not checkpoints.
- **Keep waits bounded.** Every wait on real-thread work needs a timeout that fails the test with a message. `runTest` has its own default timeout; don't rely on it as the assertion.
- **Remember `StateFlow` conflation.** The manager's state keeps only the latest snapshot, and collectors may skip intermediate states. Assert the snapshot at a checkpoint, or record a sequence only where the contract guarantees each value is published.
- **State which outcomes are allowed.** A race history needs entry and completion barriers and a statement of every permitted outcome.
- **Clean up.** The manager lives for the process and has no close operation, so confine its dispatcher to the test. After each test, release held fakes, stop Robolectric Services the test started, stop Koin and reset the Main dispatcher. Give each settings store its own preferences directory; a store left running keeps its file active.

## Running local tests

The [module build](../../build.gradle.kts) owns Core's test dependencies and test JVM settings. Run from the repository root:

```shell
./gradlew :streaming:core:testDebugUnitTest
```

- To select one class, add `--tests 'fully.qualified.TestClass'`.
- To force a fresh run, add `--rerun-tasks`. An up-to-date or skipped task produced no fresh results.
- Inspect `streaming/core/build/reports/tests/testDebugUnitTest/` and `streaming/core/build/test-results/testDebugUnitTest/`.

A successful build without fresh test results isn't execution evidence.

In Android Studio, run a test class from its gutter icon, or run `:streaming:core:testDebugUnitTest` from the Gradle tool window. Both use the module build as it is: no custom test runner, init script, separate Gradle home or cache, and no JVM flags beyond those the module build declares. Production uses JVM toolchain 17; the unit-test launcher is Java 21.

## Evidence rules

1. **Use independent expectations.** Compare against identities and values the test created or observed, not against values produced by the code under test. Compare identities by equality, never by format.
2. **Label supplied inputs.** A fake Controller or provider, a test shadow or a `Context` wrapper shows how Core responds to that input. It doesn't show how a real module, provider or the platform behaves. Inputs a conforming collaborator never produces, such as a Controller with a mismatched identity, are defensive checks; label them so. A fake `awaitCleanup` result is likewise supplied: platform cleanup, restart and Service-ownership qualification requires a Controller that actually stops and awaits its original Service, or a real module run.
3. **Keep lifecycle facts apart.** Closing admission, requesting shutdown, the end of the manager's bounded wait, the end of Controller cleanup and physical release are separate facts. So are consent, foreground preparation, grant delivery and projection acquisition.
4. **State evidence limits.** Shadows establish responses to supplied platform outcomes. State which real-platform behavior the local result does not establish.
5. **Prefer existing boundaries.** Reach behavior through the entry points real collaborators use, Kotlin `internal` access from the module's own tests, test-defined shadows and observable outputs. Don't use reflection on private members, and don't widen production APIs for a test. A production change made for testability, even a read-only accessor or a forwarding parameter, is a design change of its own: explain it separately and get explicit approval before making it.
