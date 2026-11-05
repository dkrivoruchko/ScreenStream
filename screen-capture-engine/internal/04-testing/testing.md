# Testing and verification

Use this guide to choose and run the smallest check that can observe a changed contract. Read the relevant public [Usage](../../docs/usage.md) and [Architecture](../../docs/architecture.md) sections first, then the internal owner page. [Verification contracts](verification-contracts.md) defines the complete ID catalogue and obligations; [Image test oracles](image-test-oracles.md) defines reproducible pixel fixtures, tolerances, and device-evidence limits.

## Choose the evidence

- JVM and Robolectric tests cover deterministic values, state transitions, ownership, injected schedules, and fault mapping at exercised seams. They do not prove real framework, graphics-driver, packaged-JNI, or target-ABI behavior. See Android's [local-test](https://developer.android.com/training/testing/local-tests) and [Robolectric](https://developer.android.com/training/testing/local-tests/robolectric) guidance.
- Host-C++ tests cover exercised native protocol, bounds, cleanup, and sanitizer behavior. They do not prove Android ABI packaging, registered-JNI loading, Android Bitmap behavior, or target-device execution.
- Instrumentation can exercise real Android, graphics, and packaged native paths on the device used. Its result is limited to the cases and branches that actually ran. Source inspection and AndroidTest assembly supply no device-runtime result.

The catalogue's **Executable** method requires a direct test oracle; **Inspection** requires a bounded source or build-configuration check. Neither method labels nor markers establish coverage or a passing result. Device, GPU, artifact, and external-consumer evidence must be established separately. In particular, shared Runtime tests cannot replace an owner's typed outcome, resource-settlement, and lost-wake evidence.

## Commands and prerequisites

Run commands from the repository root. The module [build configuration](../../build.gradle.kts) uses JVM toolchain 17 and explicitly launches unit tests on JDK 21; Gradle must be able to locate or provision that test runtime. The unit-test launch opens `java.base/jdk.internal.access` for Robolectric's API 37 FileDescriptor setup. Host-native checks use the configured Android SDK CMake and Ninja executables plus a host Clang compiler. Their [CMake workflow](../../src/test/cpp/CMakePresets.json) configures, builds, and runs the two C++ test executables with AddressSanitizer and UndefinedBehaviorSanitizer.

### Local and host-native checks

```shell
./gradlew :screen-capture-engine:testDebugUnitTest
./gradlew :screen-capture-engine:testHostNative
./gradlew :screen-capture-engine:check
```

`testDebugUnitTest` runs JVM and Robolectric tests. `testHostNative` runs `native_jpeg_runtime_test` and `screen_capture_engine_jni_test` through CTest. `check` includes the routine unit-test path and explicitly depends on `testHostNative`; it is broader than either focused command.

Filter a unit test when one contributor observes the change:

```shell
./gradlew :screen-capture-engine:testDebugUnitTest \
  --tests 'io.screenstream.capture.internal.capture.SessionCaptureOwnerReadbackTest.acceptedUnenteredReadReturnsCutoffOnceAndRetirementRejectsReuse'
```

### Instrumentation

Assembly checks compilation and packaging only:

```shell
./gradlew :screen-capture-engine:assembleDebugAndroidTest
```

For runtime evidence, use a connected physical device when available. Ask before launching an emulator if none is connected. With the intended device connected, run the debug instrumentation suite or restrict it to a relevant class:

```shell
./gradlew :screen-capture-engine:connectedDebugAndroidTest

./gradlew :screen-capture-engine:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=io.screenstream.capture.internal.capture.GLRendererRawPixelTest
```

The module configures `androidx.test.runner.AndroidJUnitRunner`. Instrumentation uses runner arguments, while local tests use `--tests`. Android's [command-line test guide](https://developer.android.com/studio/test/command-line) explains variant tasks and reports; the [AndroidX testing guide](https://androidx.github.io/androidx/testing.html) documents Gradle runner filtering. To inspect tasks for the current checkout, use `./gradlew :screen-capture-engine:tasks --all`.

### Android suite boundaries and selection

The suite has eight classes and 17 methods. Keep these four evidence layers separate; executing a lower layer does not qualify real projection.

| Evidence layer | Class (under `io.screenstream.capture`) | Methods / contract |
| --- | --- | --- |
| Real Canvas/OES/GLES/readback | `internal.capture.GLRendererRawPixelTest` | 2 methods, 29 raw cases; `IMG-01` |
| Framework JPEG owner | `internal.encoding.EncodingOwnerFrameworkJpegTest` | 1 method; `ENC-01` |
| Native Auto owner and direct registered JNI | `internal.encoding.NativeJpegRuntimeTest` | 3 methods; `ENC-02`, `ENC-04` |
| Real default Display Metrics | `internal.metrics.BuiltInCaptureMetricsRealDisplayTest` | 2 methods; `MET-02` |
| Public projection: output parameters | `device.ScreenCaptureProjectionSizingTest`, `device.ScreenCaptureProjectionReconfigurationTest` | 4 + 2 methods; `IMG-02`, recovery also `SES-03` |
| Public projection: actual phone rotation | `device.ScreenCaptureProjectionDisplayChangeTest` | 2 methods; `IMG-02` |
| Public projection: opted-in emulator source size | `device.ScreenCaptureProjectionSourceResizeLabTest` | 1 method; `IMG-02` |

The [raw and JPEG procedures](image-test-oracles.md) define precision reporting, API eligibility and independent image criteria. An API-gated skip is not an exercised branch. On API 37, the pre-API-30 Native method is not applicable; packaged ABIs and an unselected graphics-precision branch still require separate runtime evidence.

The real-consent histories below use the public factory. Each runner selector has package prefix `io.screenstream.capture.device.`. History and stage IDs remain stable when source methods move or are renamed, so archived evidence must be bound to its original APK, class/method and run UUID.

| Runner selector after the package prefix | Stable history ID |
| --- | --- |
| `ScreenCaptureProjectionSizingTest#defaultsPreserveWholeDisplayAndFreshContent` | `untouched-defaults` |
| `ScreenCaptureProjectionSizingTest#explicitHalfSizeStretchPreservesWholeDisplayAndFreshContent` | `equal-output-full-input-control` |
| `ScreenCaptureProjectionSizingTest#liveScaleRoundTripPreservesMapping` | `live-full-downscale-full` |
| `ScreenCaptureProjectionSizingTest#liveScaleSequencePreservesWholeDisplayAndFreshContent` | `shrink-regrowth-growth` |
| `ScreenCaptureProjectionReconfigurationTest#regionAndCropRoundTripsPreserveMapping` | `fixed-output-restriction-crop` |
| `ScreenCaptureProjectionReconfigurationTest#invalidCropSuspendsAndRecoveryProducesFreshContent` | `invalid-crop-recovery` |
| `ScreenCaptureProjectionDisplayChangeTest#rotationAtFullScalePreservesMapping` | `actual-rotation-full` |
| `ScreenCaptureProjectionDisplayChangeTest#rotationAtDefaultScalePreservesMapping` | `actual-rotation-default` |
| `ScreenCaptureProjectionSourceResizeLabTest#sourceAspectShrinkRoundTripPreservesMapping` | `source-aspect-shrink-lab` |

For example, select one ordinary projection history:

```shell
./gradlew :screen-capture-engine:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=io.screenstream.capture.device.ScreenCaptureProjectionSizingTest#defaultsPreserveWholeDisplayAndFreshContent
```

Select the source-size lab separately and opt in explicitly:

```shell
./gradlew :screen-capture-engine:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=io.screenstream.capture.device.ScreenCaptureProjectionSourceResizeLabTest#sourceAspectShrinkRoundTripPreservesMapping \
  -Pandroid.testInstrumentationRunnerArguments.engine.sourceResizeLab=true
```

All real-consent procedures require an already awake, unlocked device, qualified whole-display SystemUI selectors and a test APK targeting 37, checked in its packaged manifest and at runtime. Rotation additionally requires a natural-portrait phone window below 600 dp and an observed actual display change; an ignored request fails qualification. The isolated size lab requires the API 37 emulator at logical `1080×2400`, density 420 and rotation 0. Without its argument, JUnit reports not applicable before constructing the host; an invalid explicit argument or opted-in unqualified environment fails before changing any size override. It saves physical/effective override state, executes `480×960 → exact original` through test `UiAutomation`, restores a preexisting override by value, and resets only when none existed. It is not generic physical-device or app-window-sharing qualification.

[ProjectionCaptureFixture](../../src/androidTest/kotlin/io/screenstream/capture/device/ProjectionCaptureFixture.kt) owns session/checkpoint progression, copied callback frames, current-scene and source-layout qualification, verdicts and failure accounting. [ProjectionEvidence](../../src/androidTest/kotlin/io/screenstream/capture/device/ProjectionEvidence.kt) stores/exports snapshots. [ProjectionRotationControl](../../src/androidTest/kotlin/io/screenstream/capture/device/ProjectionRotationControl.kt) owns platform rotation state and commands; [ProjectionSourceResizeLab](../../src/androidTest/kotlin/io/screenstream/capture/device/ProjectionSourceResizeLab.kt) exclusively owns opted-in size mutation/restoration. The [test host](../../src/androidTest/kotlin/io/screenstream/capture/device/ProjectionTestHost.kt) owns the real Activity, foreground service and measured source View; [ProjectionImageOracle](../../src/androidTest/kotlin/io/screenstream/capture/device/ProjectionImageOracle.kt) independently judges source/JPEG content. Test source, selection, successful platform commands and assembly do not establish an image pass. See the [projection procedure](image-test-oracles.md#real-projection-procedures) for stage qualification, failure and evidence rules.

Optional runner argument `engine.captureGeometryDiagnostics=true` records SurfaceFlinger `--frontend` then `--comp-displays` once after the first source-qualified, recorded image failure; all-pass histories capture nothing. Each stdout dump is capped at 1 MiB and paired with command `System.nanoTime` brackets and exact run/history/stage/last-matched JPEG metadata. Snapshots are non-atomic; exit status and separate stderr are unavailable. Denied, missing, truncated or failed output is inconclusive. Diagnostic errors remain secondary evidence without changing image verdicts or cleanup. Acceptance runs leave this flag absent or false.

### Inspect results and artifacts

After the relevant command runs, inspect its result rather than inferring success from a file's presence. Gradle writes local HTML/XML results under `screen-capture-engine/build/reports/tests/` and `screen-capture-engine/build/test-results/`; connected instrumentation uses `build/reports/androidTests/connected/` and `build/outputs/androidTest-results/connected/` within the module. Variant and device subdirectories depend on the task. The host workflow uses `screen-capture-engine/build/host-native-test/`, with CTest logs under `Testing/Temporary/`.

Find the files actually produced in the checkout:

```shell
rg --files --no-ignore screen-capture-engine/build | \
  rg '/(reports/|test-results/|outputs/|host-native-test/Testing/)'
```

Open the matching HTML report in a browser, or inspect the matching XML/CTest log. Build directories can contain older results; record the command, source revision or patch, result location, actual pass/failure/skip outcome, and any environment prerequisite that prevented execution. For device checks, also record the device, Android API, ABI, and relevant naturally selected graphics branch. Keep attempted, assembled, executed, skipped, and not-applicable outcomes distinct. An emitted APK/AAR or successful assembly does not prove runtime loading, image correctness, R8 behavior, or external-consumer compatibility; [Native ABI](../03-output/native-abi.md) describes those separate obligations.

## Arrange deterministic execution

Reuse the existing [test harnesses](../../src/test/kotlin/io/screenstream/capture/testutil/) where they faithfully expose the boundary being changed. `ControlledNonInlineDispatcher` separates dispatch acceptance from explicit outer-task entry. Its task-handle completion records the outer Runnable's outcome, including failure; it does not by itself prove nested callback/codec entry or an engine slot's release. `ManualDelayedEntryScheduler` records delayed work and exposes explicit entry without advancing the clock or automatically running tasks. `MutableElapsedRealtimeClock` supplies a separate set/advance control.

These controls arrange a chosen history. The verdict must remain the product outcome described by the rules below: exact typed results, immutable bytes and metadata, ownership, or settlement evidence. The infrastructure's own behavior is checked separately by `TST-01`.

`ProjectionOwnerLifecycleTest` checks configured width/height/density and deduplication while preserving callback source dimensions. `SessionCaptureOwnerTargetReplacementTest` checks the selected consumer at display-change notification, retained capacity, replacement ownership and failure/retirement proofs; [Target contracts](verification-contracts.md#target-replacement-and-reuse) define the exact obligations. `ScreenCaptureSessionTopologyTest` checks source/output separation and resize authority. Injected or Robolectric API branches, including API 32/33, are host evidence, not device composition, producer allocation or performance evidence. The strict `IMG-02` histories remain the independent real-projection image gate.

## Traceability markers

Executable evidence uses a language-neutral source comment with exactly one verification ID per line:

```kotlin
// Verification: API-03
// Verification: SES-01
@Test
fun concurrentStartLoserIsRejected() {
    // ...
}
```

- Put a marker immediately above the narrowest contributing test or C++ test function. Use a class-level marker only when every test in that class contributes to the same contract.
- Repeat an ID at every contributing scope and stack separate marker lines when one test contributes to multiple contracts. Do not add suffixes.
- Mark direct oracles, not fixtures, mocks, helpers, production code, registration tables, or `main()`.

Find contributors with `rg -n -F '// Verification: <ID>' screen-capture-engine/src/test screen-capture-engine/src/androidTest`. Every marker must name a listed [verification contract](verification-contracts.md).

## Contract-test rules

- Document non-obvious scheduling controls, injected faults, and forbidden observations close to the affected tests. Assert product values, outcomes, immutable data, ownership, resource settlement, and documented ordering.
- Fakes, mocks, schedulers, and harnesses may arrange input, timing, or failure, but their incidental calls and structure are not the verdict. Prefer an existing faithful seam and do not add production seams solely for observation.
- For competing actions, assert the permitted winner and the invariant preserved by either winner; do not promise every interleaving.
- Use coroutine-test dispatchers and virtual time only to arrange execution; assert product effects rather than dispatcher or `Job` internals. Follow Android's [coroutine-test guide](https://developer.android.com/kotlin/coroutines/test) and the current [`kotlinx-coroutines-test` API](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-test/). Do not use sleeps or scheduler-step counts as proof.
- Task acceptance, timeout, terminal state, diagnostic emission, reference release, or garbage collection does not prove callback return or resource settlement. Do not observe private structure unless it is itself a maintained contract.
- Prefer the smallest representative case set that distinguishes the contract. Avoid exhaustive race or input matrices and implementation-specific assertions that add no independent product evidence.
