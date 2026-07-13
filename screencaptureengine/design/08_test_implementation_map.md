# Screen Capture Engine — Test Implementation Map

## 1. Scope and authority

This document binds the executable-test layout for the V1 contract in
[01_design.md](01_design.md), the internal design in [02_architecture.md](02_architecture.md), the
acceptance scenarios and traces in [03_verification.md](03_verification.md), and the Gate-B obligations in
[05_gate_b_inputs.md](05_gate_b_inputs.md). Production ownership and source decomposition remain those in
[06_implementation_design.md](06_implementation_design.md); private policy values remain those in
[07_private_constants.md](07_private_constants.md).

All paths are repository-relative through these roots: `MOD = screencaptureengine/`,
`PUB = screencaptureengine/src/main/kotlin/io/screenstream/engine/`,
`INT = screencaptureengine/src/main/kotlin/io/screenstream/engine/internal/`,
`CPP = screencaptureengine/src/main/cpp/`,
`HOST = screencaptureengine/src/test/kotlin/io/screenstream/engine/internal/`,
`AIT = screencaptureengine/src/androidTest/kotlin/io/screenstream/engine/`,
`AI = screencaptureengine/src/androidTest/kotlin/io/screenstream/engine/internal/`, and
`NTCPP = screencaptureengine/src/nativeTest/cpp/`. A `ROOT:file` entry means that exact file relative to the
declared root; a root by itself means that exact directory. No workstation-specific path or other expansion
is implied.

The tests are not a second lifecycle, fallback, resource, or timing design. They observe the existing owners,
exact receipts, public values, and documented outcomes. Programmatic image vectors are test source, not
generated assets. No test result selects a runtime
path, and no benchmark, device allowlist, aggregate telemetry threshold, or repeated-race heuristic is a gate.

## 2. Source sets, runners, and tasks

| Source set or artifact | Runner and exact task | Scope |
| --- | --- | --- |
| `HOST` | Gradle/JUnit host runner through `testDebugUnitTest`; aggregate task `verifyScreenCaptureEngineHost` | Pure values, controller decisions, deterministic race/fault tests, arithmetic, storage, Stats, diagnostics, and owner ledgers. `BuildPackagingContractTest` is explicitly excluded. No Android opaque call is claimed verified by a host fake. |
| `AIT` and `AI` | `androidx.test.runner.AndroidJUnitRunner` through the single `connectedNativeTestAndroidTest` execution; `verifyScreenCaptureEngineDevice` and `verifyScreenCaptureEngineNative` both depend on that same task | All instrumentation classes. The library binds `testBuildType = "nativeTest"`; the nonpublished `nativeTest` build type contains the production library plus the test-only native fault library from `NTCPP`. Public API/lifecycle/frame tests, Android platform adapters, real EGL/GLES2 image/readback, Bitmap/Framework JPEG, real native registration/writer/compressor/receipts, cleanup, and image vectors therefore run once. Managed Android adapter branches absent on the connected device use their production adapter seams and exact injected SDK facts; this is not a device matrix. Those seams do not execute absent production native weak-resolution/API branches, whose evidence is allocated explicitly in Sections 3.2 and 4.4. |
| debug/release/nativeTest artifacts, merged manifests, consumer rules, and packaged ELF files | Separate filtered Gradle/JUnit `Test` task `verifyScreenCaptureEnginePackage`, depending on `assembleDebug`, `assembleRelease`, `assembleNativeTest`, and `lintRelease` | Runs only `HOST:BuildPackagingContractTest.kt`. Module artifact providers supply the selected artifacts without hard-coded paths. Standard Kotlin ABI validation treats the module artifact and Document-01 declaration inventory as public visibility authority; it does not inspect or assert root-project plugins, wrapper, classpath, or repository build configuration. `MOD:build.gradle.kts` evidence verifies that every production/nativeTest native configuration passes the exact CMake configure argument `-DANDROID_WEAK_API_DEFS=ON`; `CPP:CMakeLists.txt` evidence separately verifies `jnigraphics` linkage, target composition, and unguarded-availability diagnostics. Artifact/source checks cover every configured ABI and production-bearing variant as applicable: debug/release contain only the production DSO, nativeTest contains both production and test DSOs, production has exactly its two exports, carries the direct `DT_NEEDED` dependency and weak undefined `AndroidBitmap_compress`, and every ELF has the required alignment. Source evidence also verifies the same-function nested availability guard and absence of dynamic compressor loading or cleanup. No generated verifier, parser, or script is created. |

`verifyScreenCaptureEngine` is the explicit aggregate over the host, instrumentation, and package tasks above.
The two instrumentation aggregate aliases share one Gradle dependency, so the connected task executes once.
The aggregate is not wired to ordinary compilation in a way that launches an emulator. Device tasks run only
when a connected physical device is available or an emulator has been separately authorized. There is no
generated vector or generated
expected-result task.

## 3. Deterministic support

### 3.1 Host support

`HOST:HostTestHarness.kt` owns the shared host-only support:

- `ManualEngineClock`, with independently assigned elapsed-realtime and wall-clock samples;
- `ManualScheduler`, which records each precreated `DeadlineWakeLink`, its primary fact, `Submitting`, accepted
  future, `Fired`, cancellation, dequeue, current/stale rejection, successor authority, and scheduler shutdown
  receipt without sleeping;
- reusable barriers immediately before outward entry, before `settlementGate`, after the settlement sample,
  after complete cell publication but before controller signal, before controller application, and before and
  after active-to-cleanup transfer;
- `GateProbe`, which records `sessionGate -> settlementGate`, rejects the reverse order, and confirms that
  outward calls, diagnostics, publication, scheduling, and release happen outside gates;
- `AllocationProbe`, enabled around prepared return-cell publication, gate arbitration, and consumption of the
  precreated fixed outside-gate action slots, to prove those regions allocate no result object, wrapper, string,
  diagnostic payload, or action node;
- typed fake owner boundaries and an `OwnershipLedger` recording acquisition, transfer, lease, receipt,
  logical retirement, physical release, and exact quarantine child identity.

Every timing test assigns clock samples directly and invokes the recorded wake. It never uses real sleep,
scheduler luck, wall time, producer timestamps, or retries until a race appears. A test that represents deep
sleep advances elapsed realtime while withholding scheduler execution, then delivers one current wake.

Every deliberately blocked or nonreturning test owns a release control. Its `finally` releases every held
barrier, drains the corresponding worker/scheduler callback, and awaits the late mechanical receipt before the
test returns. Assertions may first observe the unresolved state, but no test leaves a blocked engine lane,
dispatcher, metrics upstream, native callback, or cleanup occurrence contaminating the instrumentation process.

### 3.2 Android and native support

`AI:AndroidTestHarness.kt` owns platform call recorders,
test-owned callback adapters, Surface/SurfaceTexture fixtures, one private EGL/GLES2 fixture, Bitmap/JPEG decode
helpers, and the programmatic vectors in Section 5. The harness records real Android return/throw receipts; it
does not substitute a successful mock call for a platform receipt.

`AI:NativeTestHarness.kt` and `NTCPP:screen_capture_engine_native_test_jni.cpp` expose only test-DSO fault
controls and receipt probes. The test JNI bridge includes `CPP:native_jpeg_runtime.h` and calls the unchanged
JNI-free runtime object with a fake non-owning compressor function, test-local writer services, adopter, writer
capsules, and barriers. It owns no compressor state and never reaches production
DSO state. Production Native coverage remains black-box through the real production bootstrap and public-private
engine path. The test DSO is absent from main, debug, and release native contents.

Black-box production-DSO runtime evidence is deliberately narrow: it proves the normal eligible API 30+
nonnull path only on the connected executing ABI, plus the ordinary observable `FrameworkOnly` absence of a
weak-capability query and compressor invocation. Managed seams deterministically prove managed decisions, while
source/artifact inspection proves native structure and containment; neither is labeled execution of an absent
production native branch.

## 4. Test files and owner coverage

The identifiers in this table are used by the traceability sections. A test file contains concise table-driven
checks; an identifier does not imply one end-to-end test.

Every test and production entry uses one of the declared repository-relative roots.

| ID and exact test path | Type and production paths | Deterministic setup and expected result |
| --- | --- | --- |
| `H-LC` — `HOST:ControllerLifecycleTest.kt` | JVM/race/fault; `PUB:ScreenCaptureEngine.kt`, `INT:SessionController.kt` | Command barriers select start, update, stop, terminal-priority, drainer, and cross-Session orders. Observed Running publications after concurrent `updateParameters` calls are coherent immutable snapshots; the synchronous setter return is not an observation oracle. Unequal desires conflate to the latest committed value/revision without mixed fields. Precreated fixed outside-gate action slots preserve bounded allocation-free gate work. A saturated action batch with several already-complete cells self-marks dirty and rescans to consume every fact exactly once even when no producer signals again. `SessionController` is the sole lifecycle, desire/revision, topology/plan, reconciliation-occurrence, pacing/repeat-policy, counter, public-observation-value, and result authority; helper calculations cannot commit or retain any of that authority. Exactly one command winner and one terminal result commit; losing projection/desire/session authority is untouched; final Stats precedes terminal State. |
| `H-RC` — `HOST:ReconciliationAndAllocationTest.kt` | JVM/race/fault; `INT:ReconciliationOwner.kt`, `INT:TargetOwner.kt`, `INT:JpegRuntimeOwner.kt`, `INT:FrameworkJpegOwner.kt`, `INT:EncodedStorageOwner.kt`, `INT:CleanupOwner.kt` | Latest desired/geometry cells, topology identities, allocator results, and retirement barriers cover A-to-B-to-A, safe stale, ambiguous stale, pre-retirement denial, post-retirement failure, exact-compatible reuse, and smallest-scope replacement. `ReconciliationOwner` receives one immutable controller snapshot and returns only a synchronous pure calculation; it retains no desired/revision cell, topology, occurrence, admission, or commit authority. Managed capability false during preparation selects Framework before a Native attempt; managed per-call unavailability after previously Enabled health selects pre-invocation `InternalFailure` with no Framework allocation, fallback, or same-frame retry. While Native health is Enabled, the ledger contains no Framework Bitmap or row scratch creation, use, or recycle. Those resources first become eligible only after Framework is selected by `FrameworkOnly`, clean Native ineligibility, or a completed safe Native disable for a later frame; the disabling frame performs no Framework allocation/work or same-frame retry. Active appears only with a complete healthy current topology; no second healthy topology exists. These host outcomes do not claim production weak-symbol resolution. |
| `H-OS` — `HOST:OperationSettlementRaceTest.kt` | JVM/race/fault; `INT:OperationSettlement.kt`, `INT:SessionController.kt`, `INT:JpegRuntimeOwner.kt`, `INT:CleanupOwner.kt` | The common barrier matrix covers the precreated `DeadlineWakeLink`, primary-fact-first publication, schedule outside gates, `Fired` before accepted-future publication, successor eligibility only after both prior sides settle, current/stale submission rejection, cancellation during `Submitting` and after dequeue, scheduler shutdown/nonprogress, allocation probes, unentered rejection, entry, `D - 1`, `D`, `D + 1`, empty-at-`D`, gate pauses, commit-before-signal, terminal deadline retirement versus retained cleanup state, and late receipt. It explicitly includes the complete Native-preparation occurrence, each preterminal target-listener install/removal occurrence, and preterminal free of an incompatible `NativeMallocCarrier` under `jpegEnteredOperationSafetyNanos`. The carrier-free rows cover timely normal return, rejection, throw, nonreturn, expiry, and late return; prove at most one `nativeFreeCarrier` call and exactly one after entry, with no retry after rejection/throw; and admit replacement allocation only after a timely normal free receipt. Terminal-before-entry converts that same occurrence to no-watchdog cleanup, terminal-after-entry retires its deadline, and a terminal-origin free starts without a watchdog. The exact typed outcome applies once; later evidence changes only matching cleanup/quarantine residue. |
| `H-PS` — `HOST:PacingStorageStatsTest.kt` | JVM/race/fault; `INT:PacingOwner.kt`, `INT:TargetOwner.kt`, `INT:EncodedStorageOwner.kt`, `INT:ObservationOwner.kt`, `INT:SessionController.kt` | The actual generation-fenced `AtomicBoolean` latest-pending seam owned by `TargetOwner`, production-slot state, cache roles, clock, and wake identities cover Auto/MaxFps/SampleEvery, repeat, cached-first, one-wake churn, output pacing, storage displacement, production cutoff, and the exact `1_000 ms` Stats cadence. A current listener performs only `set(true)` and signals; only `SessionController` may consume with `getAndSet(false)`, materialize work, and commit pacing, grant, wake, attempt, and outcome actions. Around an admissible exchange, a set before it yields one current materialization; a set after it remains true and is materialized by the next drainer/self-rescan without another source callback. Cadence or non-admissible deferral does not exchange or clear the bit, repeated sets coalesce, and retirement drops the old generation's atomic so a late old-generation set/fact is ignored and cannot affect the current target. `PacingOwner` synchronously calculates from an immutable snapshot. The storage matrix additionally covers the derived Framework tail formula, `write(int)`, bulk writes, close/commit/abort, exact trim/copy, malformed ranges, allocation faults, and persistent exact-length segments. No pending deferral invents a drop or a second attempt. |
| `H-DL` — `HOST:DeliveryOwnerRaceTest.kt` | JVM/race/fault; `INT:DeliveryOwner.kt`, `INT:EncodedStorageOwner.kt`, `INT:OperationSettlement.kt`, `INT:SessionController.kt` | Independent dispatch-return, trampoline-entry, callback-return, and deadline-wake barriers cover rejection/entry ordering, inline callback return, unsubscribe, the exact `5_000 ms` accepted-task deadline at `D - 1`/`D`/`D + 1`, `Fired`-before-future, current/stale rejection, cancellation/dequeue, terminal cutoff, and scheduler shutdown/nonprogress. The delivery owner submits one non-suspending Runnable at a time through its own per-Session `Dispatchers.IO.limitedParallelism(1)` view, distinct from the JPEG and metrics views; barriers preserve the exact submission, entry, serial occurrence, and mechanical return facts without asserting physical thread identity. Handoff convergence publishes one identity-fenced mechanical fact; the controller commits one shared `Success` or terminal result and completes its shared signal after unlocking, so all current/later callers observe the same result without per-waiter gate arbitration. Waiter cancellation is local, same-callback unsubscribe rejects before `Closing`, and replacement exclusion clears only on shared `Success`. If terminal wins while caller dispatch is still in call, the transferred callback-entry link remains `Unarmed`; only a later normal accepted dispatch return arms it, after which scheduler submission and Future publication may occur. Throw/rejection never fabricates an accepted-task deadline. Late cleanup releases only its exact owners and cannot revise the committed shared result. |
| `H-GM` — `HOST:GeometryColorAndBoundsTest.kt` | JVM/unit/image; `PUB:ScreenCaptureParameters.kt`, `INT:ReconciliationOwner.kt`, `INT:GlPipelineOwner.kt` pure resolver seams | Checked arithmetic and the raw vector verify region/crop/rotation/mirror/size mapping, Downscaled planning, P3 binary64 oracle, grayscale oracle, overflow, narrowing, and deterministic capacity denial. Injected positive asymmetric GL capability facts compare target `(Tw,Th)` and output `(Ow,Oh)` independently per axis against `min(GL_MAX_TEXTURE_SIZE, corresponding GL_MAX_VIEWPORT_DIMS component)`. For each target/output axis, swapped width/height fixtures cover the exact limiting minimum and that minimum plus one, including cases where texture size is smaller and where the corresponding viewport component is smaller. Startup denial and Running denial known before retirement allocate no target/output resource, retire nothing, preserve the old topology parked without output where one exists, cannot trigger a Downscaled-to-Full fallback, and cannot defer discovery to `updateTexImage`. Geometry and raw mapping expectations are exact. |
| `H-OB` — `HOST:ObservationAndDiagnosticsTest.kt` | JVM/unit/fault; `PUB:ScreenCaptureObservations.kt`, `INT:ObservationOwner.kt`, `INT:SessionController.kt` | Direct clock/wall-clock values and controlled Flow collectors cover immutable snapshots, saturation, finite protection, cadence, terminal order, sequence attempts, buffer overflow, semantic messages, raw cause identity, and absence of diagnostic control authority. |
| `H-NL` — `HOST:NativeLoaderPolicyTest.kt` | JVM/fault; `INT:JpegRuntimeOwner.kt` loader seam | Deterministic host seams verify high-level clean-unavailability, bootstrap poison, and result classification without claiming native mechanics or weak-symbol resolution. A preparation capability result of false selects Framework before a Native attempt; after Enabled publication, a per-call unavailable result selects pre-invocation terminal `InternalFailure`, does not invoke a compressor, and performs no fallback or same-frame retry. Only direct `System.loadLibrary` `OutOfMemoryError` takes the load-time `ResourceExhausted` branch; after JNI-operation entry, bootstrap `std::bad_alloc`, typed/unknown C++ exceptions, partial registration, and every other failure are distinct terminal `InternalFailure` poison cases with no fallback or retry. Concurrent publication is one-shot, and no carrier allocation precedes successful bootstrap. The complete Native preparation is one Document-06/07 JPEG-deadline occurrence; timely, exact-boundary, nonreturn, terminal-transfer, and late-completion cases assert that only a timely result may publish carrier/backend owners. Timeout or late preparation starts no fresh attempt, occupies no production slot, creates no frame, and increments no frame-drop counter. |
| `A-API` — `AIT:PublicApiInstrumentationTest.kt` | instrumentation/API; `PUB:ScreenCaptureEngine.kt`, `PUB:ScreenCaptureConfig.kt`, `PUB:ScreenCaptureParameters.kt`, `PUB:ScreenCaptureOutput.kt`, `PUB:ScreenCaptureObservations.kt` | Reflection plus direct construction verifies the complete public inventory, visibility, defaults, ordinary-class semantics, internal engine-produced constructors, validation, exception types, and absence of implementation types or data-class helpers. |
| `A-SES` — `AIT:SessionContractInstrumentationTest.kt` | instrumentation/race; `PUB:ScreenCaptureEngine.kt`, `PUB:ScreenCaptureOutput.kt`, `INT:DeliveryOwner.kt`, `INT:ObservationOwner.kt`, `INT:SessionController.kt` | Installed facade tests use test-owned owner seams to verify main-safe start/unsubscribe, wrong-state calls, frame thread/lifetime/range checks, cached-first metadata, consumer replacement, and public terminal mappings. Unsubscribe races verify one controller-committed shared result/signal for every waiter, local waiter cancellation, pre-`Closing` same-callback rejection, `Success` idempotence, and replacement release only after shared `Success`. |
| `A-CAP` — `AI:AndroidCaptureInstrumentationTest.kt` | instrumentation/race/fault; `PUB:ScreenCaptureConfig.kt`, `INT:AndroidCaptureOwner.kt`, `INT:CaptureMetricsOwner.kt`, `INT:TargetOwner.kt`, `INT:OperationSettlement.kt`, `INT:CleanupOwner.kt` | Android call recorders verify the exact API-band context chains and registration receipts; one per-Session `Dispatchers.IO.limitedParallelism(1)` view invokes custom `observe()`/`collect` and performs every built-in register/read/unregister without asserting physical thread identity. Built-in tests cover registration-before-seed, callback-during-read, a structurally bounded conflated storm, DisplayListener signal-only callbacks on the capture Handler, arbitrary-thread ComponentCallbacks signal-only callbacks, no metrics Handler post/sentinel, and retained-Context reads. Barriers before and after the first tuple prove second-Session/controller progress, cancellation intent versus the standalone collector Job's sole `finally`/return receipt, nonreturn ownership, and late getter domain fencing. On nonreturn, the metrics owner record retains the provider, returned Flow, standalone collector Job, and transitively required adapters/Context references until mechanical return; the IO view, upstream execution threads, caller `flowOn`, and transient Context views remain non-owned and are never closed or independently quarantined. The retained Flow is a dependency reference, not an independently releasable engine-owned service. The suite also verifies exact MediaProjection/VirtualDisplay/SurfaceTexture calls, arguments, lanes, return partitions, resize/visibility, generations, selection, detach, deadline races including preterminal target-listener install/removal, fallback, and cleanup order. Target creation is attempted only after the current positive GL-cap facts admit each target axis against the corresponding texture/viewport minimum; an injected `Surface.OutOfResourcesException` from either SurfaceTexture or Surface construction follows the existing startup or Running-after-retirement terminal `ResourceExhausted` mapping, while every other unexpected construction failure is `InternalFailure`. Pre-retirement remains the separate deterministic cap denial with no target allocation or retirement; no path fabricates a Downscaled fallback or reaches `updateTexImage` to discover a known cap violation. |
| `A-GL` — `AI:GlPipelineImageInstrumentationTest.kt` | instrumentation/GL/image/fault; `INT:TargetOwner.kt`, `INT:GlPipelineOwner.kt`, `INT:JpegRuntimeOwner.kt` carrier seam | Real EGL/GLES2 verifies image/readback, the exact initial private-lane bind arguments, Session-owned current-context identity before every GL occurrence, and `EGL_NO_CONTEXT` after exact successful unbind. Immediately after the valid bind and a clean error boundary, one coherent one-time group captures positive `GL_MAX_TEXTURE_SIZE`, both `GL_MAX_VIEWPORT_DIMS` components, and `glGetShaderPrecisionFormat(GL_FRAGMENT_SHADER, GL_HIGH_FLOAT, ...)`; injected positive, nonpositive/corrupt, query-error, and inconsistent evidence follows the exact capability classification, and no later frame/reconciliation re-queries the group. Positive precision and both range components select highp; the documented all-zero result selects mediump; a GL error or mixed/otherwise-invalid precision evidence is `InternalFailure`. Per-axis asymmetric/swapped exact-minimum and minimum-plus-one target/output plans agree with `H-GM` and deny doomed allocation or retirement before any target construction or `updateTexImage`. Exactly two direct native-order client `FloatBuffer`s are constructed once per `GlPipelineOwner`; identity is stable across frames and plan reconciliations, position is reset before each matching vertex-pointer call, and no per-frame construction, pool, or VBO appears. A representative Direct readback publishes its real exact-range carrier to the assigned Framework and eligible Native encode slices. Injected seams cover owned-context/no-context mismatches with one exact error observation and no repair, EGL false/sentinel partitions, and construction OOM confirmation. The private GL lane is the sole `eglMakeCurrent` caller; no current-display/current-surface probes are asserted. The approved old/final capped drains remain unchanged. Exact unbind/destruction receipts, config/context/pbuffer, OES sampling, canonical state, transforms/colors, Session isolation, and absence of `eglTerminate` remain covered. |
| `A-FJ` — `AI:FrameworkJpegInstrumentationTest.kt` | instrumentation/Bitmap/JPEG/image/fault; `INT:FrameworkJpegOwner.kt`, `INT:EncodedStorageOwner.kt`, `INT:JpegRuntimeOwner.kt` carriers | Both carrier kinds feed the same exact-range adapter. Bitmap/row-scratch allocation, use, and recycle counters remain exactly zero while Native health is Enabled. Framework resources first appear only after selection by `FrameworkOnly`, clean Native ineligibility, or a completed safe Native disable for a later frame; the disabling frame has zero Framework work/allocation and no retry. After selection, normal allocation/failure classification, exact-shape reuse, transfer choice, and one-shot recycle are exercised unchanged. In the representative real path, the actual Direct readback carrier enters the Framework transfer/compress transaction and its committed JPEG is decoded against Section 5. The sink records whichever `write(int)`, bulk-write, and close calls the real `Bitmap.compress` implementation actually makes; `H-PS` separately forces every sink branch. Fast/portable guards, row conversion, derived tail growth and trim, commit/abort, copy integrity, allocation faults, reuse, and one-shot recycle remain focused checks. |
| `A-CL` — `AI:CleanupIsolationInstrumentationTest.kt` | instrumentation/race/fault; `INT:AndroidCaptureOwner.kt`, `INT:CaptureMetricsOwner.kt`, `INT:TargetOwner.kt`, `INT:GlPipelineOwner.kt`, `INT:JpegRuntimeOwner.kt`, `INT:FrameworkJpegOwner.kt`, `INT:DeliveryOwner.kt`, `INT:CleanupOwner.kt` | One metrics getter/collector, built-in register/read/unregister, owned execution-resource termination, non-owned JPEG/delivery Runnable, or unrelated cleanup branch is held while the controller, projection cleanup, a second Session, and unrelated roots progress. Cancellation remains intent until the standalone collector Job's sole `finally`/return receipt. Nonreturn retains one exact metrics owner record with provider, returned Flow, standalone collector Job, adapters, and transitively needed Context references until mechanical return; unresolved DisplayListener unregister additionally retains its listener, DisplayManager, Handler reference, and exact occurrence, but no HandlerThread dependency lease. After terminal fencing, signal close, and Android-owned work settlement, HandlerThread quit/return proceeds independently and cannot fabricate unregister receipt. Late or failed listener delivery has no authority. ComponentCallbacks and retained Contexts remain metrics-owned. GL-executor and Android HandlerThread shutdown tests distinguish request, normal termination receipt, nontermination quarantine, and cleanup-only late completion. JPEG and delivery instead use two distinct non-owned per-Session IO views: tests never request or expect view shutdown/termination, cancellation, close, or quarantine; a nonreturn retains only the exact Session-owned operation/Runnable residue, and a late return reduces only that residue. Weak-compressor capability creates no cleanup resource or receipt. Every barrier is released in `finally`, and late receipts are awaited. Exact roots prove dependency-local blocking, logical progress on another view and in another Session, and no fabricated release. |
| `N-JPEG` — `AI:NativeJpegInstrumentationTest.kt` | native/instrumentation/race/fault/image; `INT:JpegRuntimeOwner.kt`, `INT:EncodedStorageOwner.kt`, `CPP:screen_capture_engine_jni.cpp`, `CPP:native_jpeg_runtime.h`, `CPP:native_jpeg_runtime.cpp`, `NTCPP:screen_capture_engine_native_test_jni.cpp` | The JPEG owner submits one non-suspending Runnable at a time through its own per-Session `Dispatchers.IO.limitedParallelism(1)` view, distinct from delivery and metrics; barriers preserve serial submission, entry, operation, settlement, and mechanical return without asserting physical thread identity or a view-termination receipt. On an eligible API 30+ connected target, the production DSO is exercised only black-box: a real Direct carrier enters real bootstrap, a nonnull `nativeHasWeakCompressor`, same-helper nonnull re-resolution in `nativeCompress`, the real non-owning weak-function call, writer/adoption, committed storage, and decode on that executing ABI. The ordinary `FrameworkOnly` case observes no weak-capability query or compressor invocation. The API 24–29 guarded non-entry and API 30+ null-address production paths remain unexecuted unless a suitable connected target actually supplies those conditions; managed seams, the test DSO, and static/package evidence are not reported as their production execution. While Native remains Enabled, Framework Bitmap/row-scratch create/use/recycle counts are zero. A safe Native disable drops the switching frame with no Framework allocation/work or same-frame retry; Framework becomes eligible only for a later frame. The same real registered production glue is called with managed sink OOM/non-OOM throws and feasible malformed direct blocks/ranges through existing internal seams; it proves throwable precedence and clearing, fixed result-block evidence, local-reference cleanup, exact one-managed-segment-per-nonempty-callback transfer/free order, zero-size no-op, copy integrity, and exact residue release without a production hook or forced native-service fault. Separately, the test DSO dynamically proves bootstrap-specific C++ poison containment and drives the same unchanged JNI-free runtime object with a test-local non-owning compressor function, writer services, adopter, writer capsules, and barriers for applicable runtime/adoption C++ exception partitions, reachable writer status precedence, residue, callback/adoption/copy faults, every non-success compressor result after partial callbacks, and late-health mechanics; it never reads or mutates production state. Impossible defensive writer exceptions remain source-inspection evidence only. Fixed writer/result owner blocks, no raw ownership `Long` or function pointer crossing JNI, no compressor cleanup, no same-frame retry, and precedence remain covered. |
| `N-PKG` — `AI:NativePackagingInstrumentationTest.kt` | native/instrumentation/package; `CPP:CMakeLists.txt`, `CPP:native_jpeg_runtime.h`, `CPP:native_jpeg_runtime.cpp`, `NTCPP:CMakeLists.txt`, `NTCPP:screen_capture_engine_native_test_jni.cpp` | On the connected ABI only, nativeTest loads both DSOs, proves the production black-box bootstrap and guarded weak capability independently, and proves the test bridge reaches source-identical runtime mechanics only through a test-local non-owning compressor function and test-local dependencies. It makes no runtime claim for other ABIs and cannot satisfy the production Direct+Native image slice. |
| `P-PKG` — `HOST:BuildPackagingContractTest.kt` | build/package; `MOD:build.gradle.kts`, `CPP:CMakeLists.txt`, `CPP:screen_capture_engine_jni.cpp`, `CPP:native_jpeg_runtime.h`, `CPP:native_jpeg_runtime.cpp`, `NTCPP:CMakeLists.txt`, `NTCPP:screen_capture_engine_native_test_jni.cpp`, `MOD:src/main/AndroidManifest.xml`, `MOD:consumer-rules.pro` | Excluded from `testDebugUnitTest` and selected alone by `verifyScreenCaptureEnginePackage`. Module artifact evidence validates the standard Kotlin public ABI against Document 01, empty manifests, and narrow consumer rules. `MOD:build.gradle.kts` inspection requires every production/nativeTest native configuration to pass the exact CMake configure argument `-DANDROID_WEAK_API_DEFS=ON`; no root-project wiring is asserted. `CPP:CMakeLists.txt` inspection separately requires system `jnigraphics` linkage, the one hidden PIC `native_jpeg_runtime.cpp` OBJECT target linked unchanged into production and test DSOs, both JNI bridges including `native_jpeg_runtime.h`, correct target composition, and unguarded-availability diagnostics as errors; it is not treated as the sole activator of the option. Source inspection further requires a JNI-free runtime with one explicit non-owning compressor function plus writer services/adopter parameters, complete outer C++ exception containment at JNI/adoption/writer boundaries, the bootstrap-specific rule that every post-entry C++ exception including `std::bad_alloc` becomes terminal `InternalFailure` poison, writer `noexcept`/catch-all defensive classification with no production injection hook, and production-adapter deletion of every created segment-view local reference on all exits. Every production ELF in all configured ABIs and production-bearing variants as applicable must expose weak undefined `AndroidBitmap_compress` and the direct `DT_NEEDED` dependency, whose possible mapping on API 24–29 or under `FrameworkOnly` is disclosed without predictive accounting. Source inspection requires both same-function address-taking and the null check to be structurally nested inside `__builtin_available(android 30, *)` in `nativeHasWeakCompressor` and `nativeCompress`, with null-before-invocation control flow, no pointer crossing or writable storage, and no `dlopen`/`dlsym`/`dlclose`, compressor owner/capsule/block, or compressor cleanup path. These source/artifact checks prove structure and containment, not actual runtime null resolution or non-entry on an absent API branch. Debug/release exclude the test DSO; nativeTest contains both; production has exactly two exports and hidden registered operations; every ELF has 16-KiB alignment. |

### 4.1 Deadline-wake and controller matrices

`H-OS` table-drives every finite operation wake; `H-DL` applies the same cases to the accepted callback-entry
deadline. Primary fact publication is always tested before the outside-gate schedule attempt. If the callback
sets `Fired` before the accepted future is published, no successor becomes eligible until both sides of that
same link are settled. Cancellation while `Submitting`, cancellation after dequeue, early/stale callback,
current rejection, stale rejection, and shutdown with an unresolved scheduler receipt each preserve the one
current identity and exact owner residue. Current active rejection follows its documented failure partition;
stale/retired rejection is cleanup-only. Scheduler nonprogress has no watchdog. Allocation probes surround link
publication, gate arbitration, prepared action-slot selection, and successor authorization. Every applicable
boundary also runs at `D - 1`, `D`, and `D + 1`.

Terminal conversion has two explicit rows. A finite entered operation retires its active deadline and transfers
its intact unresolved record to no-watchdog cleanup. If terminal wins while caller dispatch is still in call,
the callback-entry link transfers as `Unarmed`. Only a later normal dispatch return proves acceptance and arms
that public deadline; only after that arm may scheduler submission and Future publication occur. A dispatch
throw/rejection does not arm it. Any later expiry can only root the exact delivery residue and cannot replace the
terminal winner.

Preterminal replacement of an incompatible `NativeMallocCarrier` adds no new matrix or value. `H-OS` applies the
same finite-operation rows with `jpegEnteredOperationSafetyNanos` to the one free occurrence: normal return at
`D - 1` is the sole receipt that permits replacement allocation; return at `D` or `D + 1`, empty-at-`D`,
scheduler rejection, throw, or nonreturn selects the existing `InternalFailure` and admits no replacement. A
late normal return can reduce only the exact carrier cleanup residue, and the free call remains exactly once.
Terminal-before-entry converts that same occurrence to no-watchdog cleanup, terminal-after-entry retires its
active deadline, and a free created by terminal retirement starts without a watchdog.

`H-LC` also prepublishes more complete fact cells than the fixed action batch can claim, delivers exactly one
producer signal, and then withholds all further producer signalling. After the first saturated scan, the harness
asserts the [Document 06 Section 4.1](06_implementation_design.md#41-session-gate-and-lossless-drainer)
self-rescan sequence: the drainer remains/marks dirty, executes each bounded batch unlocked, and returns to idle
only after one full empty scan. Every fact and outside-gate action is consumed exactly once; none is stranded or
requires a synthetic producer wake.

The same host matrices enforce the authority boundary, not only the calculated value. `ReconciliationOwner`,
`PacingOwner`, and `ObservationOwner` are controller-confined synchronous helpers: each receives an immutable
snapshot and returns or publishes only its calculation. They own no gate, lifecycle, policy, asynchronous work,
topology/occurrence, counter, public value, or result decision. `TargetOwner` alone stores the generation-fenced
latest-pending `AtomicBoolean`; an admitted listener may only `set(true)` and signal, while only
`SessionController` may consume it with `getAndSet(false)` and commit the resulting pacing or work action.
`H-PS` places a set on each side of the exchange, proves that the post-exchange set survives for the next
drainer/self-rescan without another callback, and separately proves deferral-without-clear, repeated-set
coalescing, and that retirement drops the old atomic so any late old-generation set/fact cannot touch the current
generation's atomic.

### 4.2 Metrics and Android API matrices

The sole production authority for factory associations, API-band reads, invalidation mechanics, lanes, retained
references, and cleanup ownership is [Document 06 Section 7.2](06_implementation_design.md#72-metrics).
`A-CAP` injects exact SDK facts at API 24, 29, 30, 33, 34, and 37; these are adapter-branch cases, not a device
matrix. The test matrix preserves these controlled cases without restating the production chains:

| Factory cases | Controlled inputs and required observations |
| --- | --- |
| null config | Default Display present/missing, changing platform metrics between reads, and API-band recorders. Assert the exact selection/read calls, fresh-helper behavior, tuple source, provisional API-34/37 dimensions, live density, and missing-association partition specified by Document 06 Section 7.2. |
| `fromActivityDisplay` | Present/missing Activity association plus an Activity display move after factory return. Assert the factory Display snapshot; one retained application-safe display Context and, on API 30–37, one retained window Context; the dropped Activity reference; fixed-association rereads through those retained helpers; exact API-band calls; and the failure partition from Document 06 Section 7.2. |
| `fromDisplay` | One explicit Display plus unrelated default-display changes. Assert exact-display identity; one retained application-safe display Context and, on API 30–37, one retained window Context; rereads through those fixed-association helpers; and no association switch. |
| `fromUiContext` | Activity/non-Activity inputs on API 24/29; UI/non-UI Context, present/missing associated Display, configuration change, and application-derived substitute traps on API 30/33/34/37. Assert the exact retained-or-dropped authority, API-band validity checks, own-Context reads, and failure partitions from Document 06 Section 7.2. |

For built-in invalidation and cleanup, `A-CAP` and `A-CL` control register, seed/read, callback arrival during a
read, callback storms, signal close, unregister, and collector-`finally` boundaries independently. The recorders
must show the Document-06 registration-before-seed and conflation behavior; signal-only DisplayListener and
ComponentCallbacks paths; the exact metrics-IO/capture-Handler lane split; retained fixed-association and
UI-Context authority; exact unregister residue without a HandlerThread lease; and independent Android-lane
return after terminal fencing, signal close, and Android-owned settlement. Barriers are released in `finally`, late receipts are awaited, and
the held metrics path must not block projection cleanup, another Session, or unrelated roots. The tests assert
lane isolation and progress, never physical IO thread identity or ownership of the borrowed Flow, IO view,
upstream threads, caller `flowOn`, Display, or transient read Contexts.

The Android call recorder verifies these exact boundaries and lanes:

| Existing test | Calls and required result |
| --- | --- |
| `A-CAP`, `A-CL` built-in metrics | On the metrics IO view: register with the capture Handler as callback-delivery reference, seed/read, drain the conflated token, and unregister. A DisplayListener callback runs on the capture Handler and a ComponentCallbacks callback may run on an arbitrary test thread; each only signals. Registration-before-seed, callback-during-read, storm conflation, retained fixed-association display/window Contexts, retained-UI-context authority, and unregister normal/throw/nonreturn are asserted without a metrics Handler post, sentinel, or Handler dependency lease. Unresolved unregister retains the exact occurrence, listener, DisplayManager, and Handler reference in metrics residue; it never fabricates receipt or blocks HandlerThread quit after terminal fencing, signal close, and Android-owned work settlement. Late or failed callback delivery has no authority, while projection cleanup, Handler return, unrelated roots, and another Session progress independently. |
| `A-CAP` projection/display | On the Android Handler: register the projection callback with that Handler; call the sole `createVirtualDisplay` with the fixed private name, positive selected width/height/density, automatic-mirroring flag, nonnull target Surface, and null display callback/Handler; call exact-identity `resize`, `setSurface`, callback unregister, VirtualDisplay release, and projection stop. Null creation and the documented Security/OOM/illegal-state/unexpected throw partitions remain distinct; void calls record normal, throw, or unresolved return. |
| `A-CAP` target | Only after the one-time current positive GL-cap facts admit `Tw` and `Th` against their corresponding texture/viewport minima, the exact-context GL lane creates OES, calls `SurfaceTexture(oes, false)`, `setDefaultBufferSize(Tw, Th)`, and `Surface(surfaceTexture)`. Inject `Surface.OutOfResourcesException` independently at SurfaceTexture and Surface construction and assert the existing startup or Running-after-retirement terminal `ResourceExhausted` result; every other unexpected throwable is `InternalFailure`. Pre-retirement deterministic denial performs none of these target calls, does not retire the old topology, and never invents Downscaled-to-Full fallback. The Android Handler calls `setOnFrameAvailableListener(listener, androidHandler)` and later `setOnFrameAvailableListener(null, androidHandler)` followed by the same-Handler sentinel. The GL lane calls `updateTexImage()`, `getTransformMatrix(precreatedFloat16)` with the derived `FloatArray(16)` API shape, and `getDataSpace()` only on API 33+. |
| `A-CAP`, `A-CL`, `H-OS` target-listener occurrences | Installation and removal are separate preterminal Android-operation occurrences governed by the Document-07 Android constant. For each, drive normal/throw at `D - 1`, `D`, and `D + 1`, empty-at-`D`, nonreturn, terminal-after-entry, and late completion. Terminal-before-entry proves that no bounded occurrence starts; any required cleanup removal then uses the Document-06 no-watchdog path. Only timely normal installation permits producer attach; only timely preterminal or no-watchdog cleanup removal plus its sentinel advances retirement. After removal timeout, a late normal removal is its exact cleanup receipt and may advance only that cleanup chain. Listener timeout/late settlement fabricates no frame, production attempt, or frame-drop count. A winning timeout attempts source-`SurfaceTarget` `CapabilityCheck` and the matching `SessionTerminal` with the same cause; a higher-priority terminal leaves only cleanup visibility. |
| `A-CAP`, `A-CL`, `A-GL` release chain | The Android Handler owns listener install/removal, callbacks, sentinel, and display mutations. After Android detach/release receipt, listener sentinel, and zero target leases, the Session GL lane calls `Surface.release()`; only its normal receipt permits GL-lane `SurfaceTexture.release()`, and only that normal receipt permits GL-lane OES unbind/delete and receipt. Each throw/nonreturn preserves the order `Surface -> SurfaceTexture -> OES`; late normal receipts advance cleanup only. |

### 4.3 EGL/GL classification matrix

The sole EGL/GLES2 call, ownership, receipt, and classifier authority is
[Document 06 Section 8](06_implementation_design.md#8-egl-gles2-direct-readback-and-color). `A-GL` uses injected
return/error seams to cover every classifier input without reproducing that classifier in this document:

| Controlled seam cases | Required observations |
| --- | --- |
| owned current-context identity | After the exact successful initial `eglMakeCurrent(display, pbuffer, pbuffer, context)` and before each GL construction, frame, or destruction occurrence, require only `eglGetCurrentContext()` identity with the Session-owned context. Drive match and mismatch with the one exact immediate error observation; assert no current-display/current-surface query, repair bind, or other `eglMakeCurrent` caller. |
| one-time GL feasibility caps | Immediately after the valid initial bind and a clean error boundary, call `glGetIntegerv` exactly once for `GL_MAX_TEXTURE_SIZE` and exactly once for the two-component `GL_MAX_VIEWPORT_DIMS`; preserve both viewport components independently. Drive positive asymmetric values, a nonpositive/corrupt component, GL error, and inconsistent evidence. Only a clean positive complete result becomes the immutable Session capability fact; corrupt/error evidence follows the existing mandatory-GL `InternalFailure`. Reconciliation and frame work never re-query. For both target `(Tw,Th)` and output `(Ow,Oh)`, `H-GM` compares each axis with `min(GL_MAX_TEXTURE_SIZE, corresponding viewport component)` and uses swapped/asymmetric fixtures at the exact limiting minimum and that minimum plus one. The startup and Running pre-retirement denial rows prove zero doomed target/output allocation, zero retirement, retained parked topology where applicable, no false Downscaled fallback, and no late discovery by `updateTexImage`. |
| one-time fragment precision | In that same capability-query group, call `glGetShaderPrecisionFormat(GL_FRAGMENT_SHADER, GL_HIGH_FLOAT, range, 0, precision, 0)` exactly once. A clean result with positive precision and both positive range components selects highp; the documented clean all-zero unsupported result selects mediump; a GL error, missing result, or mixed/otherwise-invalid evidence is `InternalFailure`. No shader path performs a second precision query, and the selection remains observation-only capability input rather than a new runtime axis. |
| reusable client buffers | Construct exactly two direct native-order `FloatBuffer`s once per `GlPipelineOwner`. Across multiple frames and plan reconciliations, assert stable object identity, reset position before each corresponding `glVertexAttribPointer`, and correct four-vertex input. No per-frame client-buffer construction, buffer pool, or VBO allocation/bind appears. |
| successful unbind and no-context state | Call only `eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT)` with the exact arguments, record its normal/false/error receipt, and only after normal return require `eglGetCurrentContext() == EGL_NO_CONTEXT`. Drive match/mismatch and exact error evidence; mismatch supplies no completed currentness/unbind receipt and cannot authorize dependent destruction. Assert the prescribed destruction/`eglReleaseThread` receipts and absence of rebind, `eglTerminate`, current-display, or current-surface probes. |
| required EGL returns and sentinels | Cross true/false or handle/sentinel evidence with clean, allocation, other, and inconsistent immediate errors at safe and ambiguous construction ownership. Assert the exact Document-06 classification and residue; no case may invent fallback. |
| GL construction probes | Drive clean, allocation, other, and mixed/uncleared probe evidence after a proven clean boundary. Assert the exact Document-06 classification, typed construction receipt or residue, and that these probes do not alter the approved old/final frame-drain call counts. |
| destruction probes | Drive clean, returned failure, nonreturn, late, and stale destruction evidence for each owned group. Assert only the exact Document-06 receipt advances dependent cleanup or reuse. |

### 4.4 Native bootstrap, typed owners, and signatures

`P-PKG` requires `CPP:native_jpeg_runtime.cpp` to compile once as a hidden position-independent CMake OBJECT
target and to link unchanged into both DSOs. The runtime includes `CPP:native_jpeg_runtime.h`, contains no JNI,
and receives one non-owning compressor function, immutable writer services, and the segment adopter explicitly. Both
`CPP:screen_capture_engine_jni.cpp` and `NTCPP:screen_capture_engine_native_test_jni.cpp` include that header.
The production bridge re-resolves and supplies the guarded weak function plus production services and is
exercised only black-box; the test bridge supplies a fake non-owning function, test-local services, adopter,
writer capsules, and barriers and cannot access production bridge state. This is test
composition around source-identical mechanics, not a production fault hook.

`H-NL` proves the clean zero-owner boundary before bridge publication and poisons every failure after load or
bootstrap entry. `N-JPEG` then requires a real normal bootstrap before any carrier or writer owner exists.
The production bootstrap symbol, registration mechanism, argument order, binary class/method names, and exact
JNI descriptors are owned solely by [Document 06 Section 10.2](06_implementation_design.md#102-own-jni-library-loading-and-registration)
and [Section 10.5](06_implementation_design.md#105-native-descriptor-native-only-callback-writer-and-managed-adoption).
`N-JPEG`, `N-PKG`, and `P-PKG` retain this production callable inventory by name and assert that its Document-06
descriptors are registered unchanged: `nativeBootstrap`, `nativeAllocateCarrier`, `nativeFreeCarrier`,
`nativeHasWeakCompressor`, `nativeCompress`, and `nativeReleaseWriterResidue`.

`N-JPEG` executes one valid real registered `nativeAllocateCarrier`/`nativeFreeCarrier` pair and observes the
normal free return receipt, plus the feasible identity/range rejection controls before outward free entry.
`H-OS` uses the owner boundary seam—not a production native fault hook—to drive scheduler rejection, throw,
nonreturn, deadline, terminal conversion, and late-return ordering around that call. Together they verify the
real normal JNI boundary and the deterministic owner protocol without requiring an impossible production
native-free nonreturn injection.

The sole fixed direct owner block contains only the opaque writer token; native `WriterCapsule` remains a
typed native allocation and cannot be inferred from freed backing memory. A nonzero writer token in the same
`NativeWriterOwnerBlock` is the residue; there is no raw handle result. Host seams cover managed classification;
test-local runtime rows drive a fake non-owning compressor function through the unchanged runtime mechanics;
production runtime remains black-box. Neither side owns compressor state or cleanup. Runtime assertions cover
only the connected ABI. `P-PKG` supplies static weak-symbol/dependency/guard plus artifact/export/alignment
evidence for every configured ABI in debug, release, and nativeTest.

Weak-capability evidence keeps managed decisions, production execution, and structural proof distinct.
`N-JPEG` black-box execution covers the eligible API 30+ nonnull capability and same-helper nonnull first real
call on the connected ABI; it also observes the ordinary `FrameworkOnly` path making no capability query or
compressor invocation. `H-NL` and `H-RC` deterministically cover the managed outcomes: preparation false selects
Framework before a Native attempt, while a previously Enabled path whose per-call result is unavailable selects
pre-invocation `InternalFailure` without compressor invocation, fallback, or same-frame retry. Those seams do not
claim native weak resolution. `P-PKG` proves the weak undefined symbol and direct dependency, same-function
address-taking/null-check containment inside `__builtin_available(android 30, *)`, null-before-invocation flow,
and absence of loader, hook, pointer-storage, or cleanup state. That static evidence proves structure rather than
actual runtime null resolution or guarded non-entry. The test DSO supplies its fake non-owning function directly
to the unchanged runtime and tests writer/adoption mechanics only.

On an eligible executing ABI, `N-JPEG` applies this production-DSO bridge matrix through the existing private
managed/internal test seams. Every row invokes the real registered `NativeBridge` entry and real production
services; none selects a native fault, calls the test DSO, or reads test-DSO state.

| Production-bridge scenario | Entry and controlled managed fact | Required evidence |
| --- | --- | --- |
| adoption allocation OOM | A real compressor/writer result reaches `NativeSegmentSink`; the existing managed transaction allocator seam throws its exact `OutOfMemoryError` during synchronous adoption. | Kotlin catches that same throwable, the fixed five-field result block reports writer status, total bytes, adopted bytes, remaining bytes, and remaining segments, and terminal `ResourceExhausted` wins ahead of the compressor result. Only exact ownership cleanup may proceed: no Native disable, Framework fallback, same-frame retry, payload publication, or recovery is safe. The writer block either clears or identifies the exact releasable residue, which receives its one real production `nativeReleaseWriterResidue` receipt. |
| adoption non-OOM throwable | The existing managed sink seam throws one identity-bearing non-OOM throwable from `adoptNativeSegment` after real compressor return. | Kotlin catches the identical throwable; the non-OOM JNI/adoption fault wins ahead of OOM/compressor outcomes. The result block and writer block agree on adopted versus native residue, and only proven-unborrowed segments are released. |
| pending exception and temporary local reference | Each throwing row returns through the real production adopter while its exception remains pending inside native code. | The bridge records safe runtime/result-block facts without making another exception-sensitive JNI call, deletes the temporary segment-view local reference on every created-reference exit, and returns the original pending throwable for Kotlin to catch. A following registered production JNI call on the same instrumentation thread succeeds, proving that Kotlin received and cleared the pending exception; the sink retained no temporary view. `P-PKG` source inspection separately verifies the unconditional local-reference deletion path because local-reference-table deletion is not directly observable black-box. |
| direct block and range rejection | Existing internal seams call the registered production entries with constructible zero-token non-direct or incorrect-capacity owner/result blocks, a mismatched block instance where the managed owner knows exact identity, a non-direct pixel buffer, an insufficient direct pixel capacity, or an invalid byte-count/capacity combination; no test forges an arbitrary nonzero token or claims JNI can observe Java `ByteBuffer.order()`. | Rejection occurs before compressor/adopter entry, preserves every real owner, supplies no false release, and fabricates no result evidence. A valid control using the exact owner blocks and Direct carrier succeeds immediately beside each rejection class. |

The two throwing rows release every barrier in `finally`, await the production JNI return and any residue-release
receipt, and abort tentative managed storage. There is no compressor close or other compressor cleanup. They
assert cleanup facts, not GC timing or repeated local-reference pressure.

Exception containment follows only
[Document 06 Section 10.2](06_implementation_design.md#102-own-jni-library-loading-and-registration). The native
harness selects bootstrap, allocation-service, and adopter C++ exceptions only through the typed test-local
behaviors below; these are fault cases, not production modes:

| Containment boundary | Required evidence |
| --- | --- |
| production and native-test bootstrap after JNI-operation entry | Native-test bootstrap injects `std::bad_alloc`, a typed unexpected exception, and an unknown exception as separate cases. Every case returns contained evidence, becomes terminal `InternalFailure` poison, publishes no bridge/carrier/backend owner, permits no managed fallback or retry, and never escapes C++ through JNI. Production bootstrap is exercised normally at runtime; `P-PKG` source inspection proves the identical catch-to-poison rule for all three exception classes. This partition is separate from direct `System.loadLibrary` `OutOfMemoryError`, which remains the sole load-time `ResourceExhausted` case. |
| registered runtime/compressor JNI boundaries | At each applicable allocation or supplied-service boundary, `std::bad_alloc` maps to that boundary's existing exact OOM outcome; typed and unknown C++ exceptions map to `InternalFailure`. No exception crosses JNI, and result/owner blocks retain exact cleanup evidence. `P-PKG` separately inspects outer containment for every registered entry rather than assigning an OOM result to entries that cannot allocate. |
| production/test adoption adapter | Allocation and unexpected C++ exceptions are contained before adapter return. `std::bad_alloc` uses the existing exact adoption-OOM outcome; typed/unknown exceptions use `InternalFailure`. Any Java exception already pending is preserved rather than cleared or replaced, every created local reference is deleted, and only proven-unborrowed segments are freed. |
| native writer callback | Runtime tests drive reachable nonthrowing cases: null data for nonzero size, invalid capsule/current state, and lock/link/status-publication failure publish `InternalFailure` and false; null `malloc` and an otherwise-valid segment size or cumulative addition that overflows or is not managed-`Int` representable publish exact OOM and false. Status is monotone `Safe -> OOM -> InternalFailure`, with `InternalFailure` absorbing. After OOM, a callback performs only contract/state checks: malformed evidence upgrades to `InternalFailure`, otherwise OOM remains, with no allocate/copy/link/accepted-byte mutation. After `InternalFailure`, it returns false immediately. Because the production callback deliberately has no throwing service or injection hook, `P-PKG` source inspection—not a fabricated runtime fault—proves `noexcept`, the catch-all, defensive `std::bad_alloc`-to-OOM/other-exception-to-`InternalFailure` classification, and no escape to the compressor. |

The test DSO has the following complete test-only callable inventory. `AI:NativeTestHarness.kt` owns the named
Kotlin wrappers and receipt types; `NTCPP:screen_capture_engine_native_test_jni.cpp` owns the matching `native*`
entries and all referenced fixture state. Arguments are typed roles, not ordinals: there is no generic numeric
scenario code, raw ownership `Long`, new source file, production selector, or production hook.

| `NativeTestHarness` call / matching `NTCPP` entry | Argument roles | Result or receipt shape |
| --- | --- | --- |
| `bootstrapLocal` / `nativeBootstrapLocal` | Typed test-only `BootstrapBehavior` (`Success`, `BadAlloc`, `TypedUnexpected`, or `Unknown`); it enters the native-test bootstrap containment boundary without accessing production state. | `BootstrapReceipt(status, bridgePublished, poisonPublished, ownerPublished)`; every throwing behavior reports `InternalFailure`, no published owner, and sticky poison. This is dynamic containment evidence for native-test glue, not a production fault hook. |
| `compressLocal` / `nativeCompressLocal` | Exact writer/result blocks, exact Direct pixel range and descriptor fields, a test-local non-owning compressor function with typed result/callback behavior, typed `WriterBehavior`, typed `AdopterBehavior`, and `CallbackThreadMode` (`Caller` or one joined test helper). The function is passed directly into the unchanged runtime and is never stored as an owner. | `CompressReceipt(compressorResult, writerStatus, totalBytes, adoptedBytes, remainingBytes, remainingSegments, writerTokenPresent, callbackThreadMode, writerReceipts: List<WriterReceipt>, adoptionReceipts: List<AdoptionReceipt>, segmentReleaseReceipts: List<SegmentReleaseReceipt>)`. Each list is ordered and semantically typed; this is the test-DSO projection of the same runtime evidence, not a production weak-resolution or result substitute. |
| `armCompressorReturnBarrier` / `nativeArmCompressorReturnBarrier`; `armWriterCallbackBarrier` / `nativeArmWriterCallbackBarrier`; `armAdopterCallBarrier` / `nativeArmAdopterCallBarrier` | The current fixture; each named call arms only its corresponding test-local runtime boundary. | `BarrierArmReceipt(armed)`; no call starts or releases runtime work. |
| `awaitCompressorReturnBarrier` / `nativeAwaitCompressorReturnBarrier`; `awaitWriterCallbackBarrier` / `nativeAwaitWriterCallbackBarrier`; `awaitAdopterCallBarrier` / `nativeAwaitAdopterCallBarrier` | The already-armed named barrier. | `BarrierEntryReceipt(entered)` after that exact boundary arrives; the test uses a bounded instrumentation await, not a production deadline. |
| `releaseCompressorReturnBarrier` / `nativeReleaseCompressorReturnBarrier`; `releaseWriterCallbackBarrier` / `nativeReleaseWriterCallbackBarrier`; `releaseAdopterCallBarrier` / `nativeReleaseAdopterCallBarrier` | The already-armed named barrier. | `BarrierReleaseReceipt(released)`; the Kotlin worker running `compressLocal` supplies the distinct eventual return receipt. Test `finally` releases all armed barriers and awaits that worker. |
| `releaseLocalWriterResidue` / `nativeReleaseLocalWriterResidue` | The exact writer-owner block returned by `compressLocal`; no token is copied out. | `ResidueReceipt(status, tokenCleared, releasedBytes, releasedSegments)` with the ordered segment-free receipts. |

`WriterBehavior` names only runtime-reachable nonthrowing selections: normal callback; null-nonempty, invalid
capsule/current-state, and lock/link/status-publication `InternalFailure` cases; and null `malloc` or
otherwise-valid segment-size/cumulative-addition nonrepresentability or overflow OOM cases. It neither fabricates
a throwing writer service nor applies one generic range-to-Internal mapping. Ordered behavior lists express the
monotone `Safe -> OOM -> InternalFailure` transitions without adding a runtime policy axis. Defensive C++ exception branches
remain `P-PKG` source-inspection obligations.
`AdopterBehavior` names success, managed OOM/non-OOM failure, and test-local adopter
`std::bad_alloc`/typed-unexpected/unknown throws.
These are the semantic fault selections used by the containment rows above. Holding is controlled only by the separately named
barriers. These are test-only semantic types, not runtime policy axes. The helper-thread mode joins before
`compressLocal` returns unless a named barrier is deliberately held; late-return tests then release it in
`finally` and await the same receipt shapes.

### 4.5 Managed segmented storage matrix

`H-PS` owns the pure transaction state and allocation ledger; `A-FJ` repeats the applicable rows through real
`Bitmap.compress`; `N-JPEG` owns native callback-to-managed adoption. This is one storage implementation, not a
backend policy or tunable chunk-size axis.

The sole authority for transaction states, Framework growth/trim/copy mechanics, Native callback transfer,
adoption ordering/signature, classifications, and ownership equations is
[Document 06 Section 10.5](06_implementation_design.md#105-native-descriptor-native-only-callback-writer-and-managed-adoption)
and [Section 10.6](06_implementation_design.md#106-managed-segmented-storage). The suites consume those rules as
oracles through the following controlled cases rather than redefining the algorithms:

| Suite and controlled cases | Required test evidence |
| --- | --- |
| `H-PS` transaction state | Drive every legal and illegal `write`/`flush`/`close`/`commit`/`abort` sequence, first-fault stickiness, zero-byte encoder success, and abort after each failed phase. Assert the exact Document-06 transition, payload visibility, owner detachment, and invariant result. |
| `H-PS` Framework growth | Use symbolic input lengths that enter every first-tail, old-tail-fill, new-tail, full-tail, partial-tail, and checked-limit branch; include `write(int)`, valid/invalid bulk ranges, zero count, source mutation, and mixed calls. The allocation/copy ledger must match the Document-06 derived formula without introducing a chunk/tuning literal, and each accepted source byte appears once in order. |
| `H-PS`, `A-FJ` Framework faults and commit | Inject tail, copy, trim, container/freeze, and caller-demand flattening failures at named boundaries; exercise real `Bitmap.compress` call shapes separately. Assert exact abortability, no partial publication, ordered `copyTo`, persistent exact logical length, the Document-06 symbolic transient bound, and exact release after displacement. |
| `N-JPEG` callback mapping | Supply ordered zero and nonempty callbacks with symbolically derived sizes, plus pointer/size/cumulative and native allocation/copy failures. Receipt lists must match the Document-06 zero/no-op rule, one-native-to-one-managed segment mapping, byte order, local-reference deletion, native release order, residue, and unchanged production descriptor. |
| `N-JPEG` writer status and precedence | At runtime, drive null data with nonzero size, invalid capsule/current state, and lock/link/status-publication failure to `InternalFailure`; drive null `malloc` and otherwise-valid segment-size/cumulative-addition managed-`Int` nonrepresentability or overflow to exact OOM. Assert monotone `Safe -> OOM -> InternalFailure`, with `InternalFailure` absorbing. OOM-then-malformed upgrades to `InternalFailure` after contract/state checks only and with unchanged accepted bytes, cumulative length, segment list, allocation/copy/link receipts; malformed-then-OOM stays `InternalFailure`, and the later callback returns false immediately with no status or ledger mutation. Both orders prove `InternalFailure` before OOM before compressor result. A generic range-to-Internal classifier is explicitly rejected; impossible defensive exception branches are left to `P-PKG` source inspection. |
| `N-JPEG` non-success transfer gate | After one and after several valid nonempty callbacks, return every documented non-success compressor result plus an unknown result while writer evidence remains otherwise safe. Every row has zero adopter calls/receipts, zero managed segments and payload publication, exact disposal receipts for all provably owned native segments, and only exact ambiguous residue retained; ordinary result classification occurs only after that cleanup evidence. |
| `N-JPEG` adoption faults | Inject managed allocation/copy OOM and identity-bearing non-OOM throws while holding the named adopter/return barriers. Managed-adoption allocation OOM is terminal `ResourceExhausted` ahead of any compressor result; only ownership cleanup is safe and no fallback, retry, disable, publication, or recovery occurs. Non-OOM/pending-exception rows assert their Document-06 precedence, identical throwable, absent/non-authoritative compressor result, proven frees, and ambiguous residue retention. |
| `N-JPEG` transfer ledger | At each adoption and release receipt, assert the exact symbolic Document-06 native/managed/current-segment equation and its persistent final state. No callback grouping, coalescing, native-backed payload, predictive accounting, or new runtime axis is permitted. |

### 4.6 Execution-resource shutdown matrix

`A-CL` treats [Document 06 Section 3](06_implementation_design.md#3-runtime-topology-lanes-and-dependency-direction)
and [Section 13](06_implementation_design.md#13-cleanup-release-and-quarantine) as the sole shutdown/ownership
authority. Shutdown completion has no new deadline or watchdog:

| Execution resource | Controlled cases and required evidence |
| --- | --- |
| GL one-worker executor | After the exact dependent occurrences/leases transfer or settle, record the normal shutdown request and distinct `terminated()` receipt. Hold termination to prove that only the GL executor/thread and exact queued or attached GL/Surface residue enter quarantine; then publish late termination and prove cleanup-only root reduction. No forceful cancellation or fabricated owner receipt is allowed. |
| JPEG and delivery IO views | Construct two distinct non-owned per-Session `Dispatchers.IO.limitedParallelism(1)` views, also separate from the existing metrics view. `N-JPEG`, `H-DL`, and `A-CL` submit only non-suspending owner Runnables and prove one-at-a-time serial occurrence/submission/entry/return behavior on each view without asserting physical worker identity. Terminal closes new admission but never shuts down, cancels, closes, quarantines, or awaits termination of a view. A nonreturn retains only the exact Session-owned operation/Runnable and its real carrier/writer/Bitmap or dispatch residue; the IO view and runtime worker remain non-owned. Late return is cleanup-only. A held JPEG or delivery occurrence does not prevent logical progress on the other view or in another Session. There is no executor-termination receipt, private executor cleanup branch, or fabricated return. |
| scheduled one-worker executor | `H-OS`/`H-DL` disable successor authority, cancel every current Future outside gates, and settle caller-dispatch/`Submitting`/retained accepted-callback wake dependencies before normal `shutdown`; `terminated()` is the distinct scheduler receipt. Held/nonreturning termination retains only the scheduler/thread and exact queued/dequeued wake, `Submitting` record, retained callback-deadline link, or deadline record. Late termination reduces only that cleanup root; there is no scheduler watchdog or fabricated operation receipt. |
| Android HandlerThread/Handler | After terminal fences metrics authority and closes its signal, satisfy projection/target and every other Android-owned cleanup chain, then call `quitSafely` without waiting for DisplayListener unregister. Normal thread-`finally` return is the lane receipt. Held/nonreturning lane termination retains the thread, Handler, and queued Android facts under the Android root; it neither resolves nor fabricates the separate metrics unregister receipt. Late or failed listener delivery after fencing has no authority. |
| standalone metrics collector `Job` | `A-CAP`/`A-CL` request cancellation, close the built-in signal, and attempt exact unregister on the IO view; cancellation intent is not receipt. The collector's `finally`/completion commits the sole mechanical collector-return receipt. Held observe/read/unregister/collector return retains the exact collector Job, provider/returned Flow, and transitively required adapters/Context references. Unresolved DisplayListener unregister additionally retains its exact occurrence, listener, DisplayManager, and Handler reference in this metrics root, without retaining a HandlerThread lease or blocking Android return. Its late return reduces only that metrics root and changes no terminal result; there is no parent-scope, supervisor, second completion receipt, or fabricated unregister receipt. |
| other non-owned Kotlin dispatch service | `H-LC`/`A-CL` prove that `Dispatchers.Default` is never shut down, cancelled, closed, or quarantined. Tests retain only Session-owned controller submission/drainer cells; the global dispatcher remains non-owned even when work is held. |
| cross-Session and root isolation | For every held owned resource or Session-owned operation on a non-owned view above, a second Session's corresponding logical work and all independent roots of the affected Session progress to their applicable real receipts. In particular, unresolved metrics unregister cannot delay the affected Session's otherwise-ready Android Handler return, unresolved Android termination cannot fabricate or consume metrics unregister evidence, and a held JPEG/delivery Runnable retains no dispatch-view ownership. Releasing the held barrier later changes no terminal State, fallback, Stats, or other Session. |

## 5. Fixed image oracle

### 5.1 Raw `5 x 3` vector and exact geometry

`AI:AndroidTestHarness.kt` and `HOST:GeometryColorAndBoundsTest.kt` construct this top-down RGBA vector. Every
alpha is exactly `255`.

```text
#FF0000  #B34D26  #000000  #00FFFF  #00FF00
#FF00FF  #404040  #808080  #C0C0C0  #FFFFFF
#0000FF  #7030B0  #26994D  #008080  #FFFF00
```

The exact landmarks are the four corners `(0,0)`, `(4,0)`, `(0,2)`, `(4,2)`, the one-sided marker `(0,1)`,
and center `(2,1)`. All fifteen pixels, not only the landmarks, are checked for raw row/channel order. The
derived tight row is `R = 5 * 4 = 20` bytes and the derived full raw range is `B = R * 3 = 60` bytes. Framework
fast transfer requires an exact carrier range with `rowBytes = R`, zero start position, and limit, remaining,
and byte count equal to `B`. Any malformed full-range predicate uses the existing unsafe-input classification.
The portable choice is selected through a deterministic Bitmap-metadata seam while retaining the exact carrier
range; the test introduces no numeric padded stride and requires no full-frame temporary array.

The exact geometry table uses:

- LeftHalf columns `0,1` and RightHalf columns `2,3,4`;
- crop `(left=1, top=0, right=1, bottom=1)`, leaving `3 x 2`;
- every rotation and horizontal/vertical mirror using the same asymmetric vector;
- `ScaleFactor(2.0)`, resolving `5 x 3` to `10 x 6`;
- `TargetSize(8,8)`, resolving Stretch to `8 x 8` and AspectFit to `8 x 5`;
- one eligible Early Downscale fixture made by expanding each raw pixel to a `2 x 2` logical block, producing
  `10 x 6` source content and `5 x 3` target/output, plus the closed ineligible Full cases.

CPU geometry, dimensions, bounds, mapped landmarks, top-down orientation, raw alpha, and transfer layout are
exact. GL sampled RGB is compared channel-wise to the frozen CPU sampling oracle with these finite tolerances:

| Path | Maximum absolute eight-bit RGB error |
| --- | ---: |
| nominal sRGB highp | `2` |
| nominal sRGB mediump | `6` |
| Display-P3 highp | `3` |
| Display-P3 mediump | `8` |
| grayscale highp versus frozen integer-Y | `2` |
| grayscale mediump versus frozen integer-Y | `6` |
| Early Downscale at each exact landmark versus the CPU/source oracle | `12` |

Raw grayscale additionally requires exact `R == G == B`; raw and decoded alpha remains exact `255`. Exact
P3 classification and the corresponding `ColorAction` are required independently of pixel tolerance. The
unsupported wide/HDR classification checks its documented best-effort action and diagnostic rather than a new
colorimetric claim.

### 5.2 JPEG `64 x 48` vector

The JPEG source is a `4 x 3` array of exact `16 x 16` opaque tiles at semantic quality `80`:

```text
#E02020  #B34D26  #20B0C0  #20C040
#C020C0  #404040  #808080  #D0D0D0
#2040E0  #7030B0  #26994D  #E0C020
```

For every tile `(c,r)`, the exact half-open sample region is
`[16*c + 4, 16*c + 12) x [16*r + 4, 16*r + 12)`. All twelve regions are checked. Decode success, dimensions
`64 x 48`, placement/orientation, and alpha `255` are exact. For each channel in each `8 x 8` interior, mean
absolute error is at most `24`; for each of its eight rows, channel mean absolute error is at most `36`.
Decoded grayscale has channel-mean spread at most `8`. The `#404040`, `#808080`, `#D0D0D0` means are strictly
increasing with adjacent separation at least `32`.

The representative Framework instrumentation path uses the real Direct readback carrier, the real Framework
transfer/encoder/transaction, and a real decode against this oracle. On an eligible API 30+ connected target,
the representative Native path independently uses the real Direct carrier, production bootstrap/compressor/
writer/adoption/transaction, and real decode. On an ineligible target that Native execution remains unexecuted;
test-DSO loading and static/package evidence do not count as image execution. Framework and Native JPEG bytes,
decoded pixels, and tile values are never compared with each other. Downscaled output is checked against the
source/landmark oracle rather than for Full-path pixel identity. Quality forwarding is exact, but encoded size,
quality monotonicity, performance, and image score are not assertions.

## 6. Deadline diagnostics and noncontrol behavior

`H-OS` first asserts every exact private constant literal and its checked-addition or error-bound guard directly
against Document 07, which remains the sole numeric authority. It accepts the exact maximum representable
deadline and rejects the next start sample without wrap. `H-OS`, `H-OB`, `A-CAP`, `A-GL`, `A-FJ`, and `N-JPEG` then apply
the same table-driven `D - 1`, `D`, and `D + 1` boundary to each of the five private deadline families. Each
winning timeout attempts one `CapabilityCheck`
with the exact boundary source below, a semantic nonliteral timeout message, and the raw family cause. If that
timeout wins the terminal arbitration, the following `SessionTerminal` carries the same cause object by
reference. A higher-priority `CaptureEnded` or `OwnerStop` winner remains authoritative; the losing timeout may
settle its exact occurrence and cleanup evidence but cannot replace the terminal result.

| Timed boundary | `CapabilityCheck` source |
| --- | --- |
| first metrics readiness | `MetricsProvider` |
| initial captured resize readiness | `MediaProjection` |
| projection callback registration or `createVirtualDisplay` | `MediaProjection` |
| `VirtualDisplay.resize` or `VirtualDisplay.setSurface` | `VirtualDisplay` |
| preterminal target-listener installation or removal | `SurfaceTarget` |
| preterminal Surface release | `SurfaceTarget` |
| EGL/GL/readback occurrence | `GlPipeline` |
| Framework encode or preterminal Bitmap recycle | `FrameworkJpeg` |
| complete preterminal Native preparation, Native compression/JNI occurrence, or incompatible-carrier free | `NativeJpeg` |

A timeout produces no `RuntimeModeChanged`, optional fallback, retry, auto-tuning, public timeout code, stable
message text, or message parser. `QuarantineChanged` is attempted only if timeout/late settlement actually
changes the child set of `SessionQuarantineRoot`. Flow overflow, no collector, collector delay, wall-clock
movement, and failed `tryEmit` never change timeout, terminal, cleanup, fallback, Stats, or ownership results.
Host/application logging is outside the engine test surface, and the suite makes no reliable aggregate timeout
telemetry claim.

## 7. Document-05 required-slice coverage

| Slice | Covering tests and required result |
| --- | --- |
| B1 lifecycle, one-shot start, latest wins, A-to-B-to-A | `H-LC`, `H-RC`, `A-SES`: exact winner, coherent immutable Running publications after updates, saturated action-batch self-rescan without a later producer signal, no setter-return oracle, no losing projection access, and no Active restoration from historical equality without live topology. |
| B2 metrics/API geometry/visibility and pre-Active invalidation | `A-CAP`, `A-CL`, `H-OB`: exact API authority/context chains including retained fixed-association display/window Contexts and the retained UI Context where required, metrics-IO register/read/unregister, signal-only callback conflation, the standalone collector Job's sole `finally`/return receipt, unresolved-unregister metrics residue without a HandlerThread lease, independent Handler quit/return after fencing and Android-owned settlement, valid-then-invalid terminal mapping and diagnostics, and visibility noncontrol. |
| B3 transforms, color/grayscale, JPEG structure and corruption | `H-GM`, `A-GL`, `A-FJ`, `N-JPEG`: Section 5 exact/tolerant oracle plus representative real Direct-to-Framework and eligible Direct-to-Native encode/decode paths. |
| B4 pacing, repeat, cache, production slot, wake churn, Stats | `H-PS`: one generation-fenced `AtomicBoolean` pending source bit owned by `TargetOwner`; admitted-listener `set(true)` and controller-only `getAndSet(false)` consume/commit authority; set-before/set-after-exchange races with post-exchange preservation and next self-rescan without another callback; no clear on cadence/non-admissible deferral; repeated-set coalescing; retired-generation atomic drop and isolation from the current target; one production slot, one current queued wake plus only a dequeued stale callback, no burst/stranding, and exact counters. |
| B5 production-terminal cutoff | `H-PS`, `H-OS`: committed return/disposition folded once; unclassified, unpublished, transferred, and late-cleanup cases add no drop. |
| B6 Full/Downscaled, Direct readback, independent JPEG fallback | `H-GM`, `A-CAP`, `A-GL`, `A-FJ`, `N-JPEG`: closed selection, fit/center semantics, one safe Full fallback, independent backend health. |
| B7 registration, unsubscribe, callback/dispatcher failure, entry deadline | `H-DL`, `A-SES`: one generation/record/lease; one shared `Success` or terminal unsubscribe result committed by the controller under the gate and one shared signal completed after unlocking; local waiter cancellation and pre-`Closing` self-rejection; exact public exception mapping. |
| B8 dispatcher result versus trampoline entry | `H-DL`: recorded rejection before entry is `byDispatchFailure`; entry first prevents reclassification. |
| B9 inline callback return while dispatch is unresolved | `H-DL`: lease released, record/worker retained, intervening busy drop, no second worker; handoff convergence publishes one mechanical fact, and every unsubscribe caller awaits the same controller-committed result without per-waiter arbitration. |
| B10 callback gate ordering and terminal cutoff | `H-DL`, `H-OS`, `A-CL`: gates released before user code; pre-transfer callback return enters final Stats; late return changes cleanup only. |
| B11 active delivery-worker scheduling rejection | `H-DL`, `H-OS`: active unentered rejection is terminal internal failure without dispatch drop; prior disposition makes it cleanup-only. |
| B12 sole VirtualDisplay creation and callback authority | `A-CAP`: null callback/Handler, projection `onStop` only, explicit release receipt in cleanup. |
| B13 complete Surface-release slice | `A-CAP`, `A-GL`, `H-OS`, `A-CL`: every prerequisite, listener installation/removal finite-occurrence boundaries and terminal visibility, listener sentinel, identity fence, deadline/terminal/stale/rejection order, Surface-to-SurfaceTexture-to-OES receipts, and cross-Session GL-lane isolation. |
| B14 checked bounds, real OOM, stale fencing, quarantine | `H-GM`, `H-RC`, `H-PS`, `A-CAP`, `A-GL`, `A-FJ`, `N-JPEG`, `A-CL`: one-time clean positive `GL_MAX_TEXTURE_SIZE`/two-axis `GL_MAX_VIEWPORT_DIMS` facts with corrupt/error cases; swapped/asymmetric exact-minimum and minimum-plus-one checks comparing every target/output axis with its corresponding texture/viewport minimum; deterministic startup and Running pre-retirement denial with zero doomed allocation/retirement, no `updateTexImage` discovery, and no false Downscaled fallback; SurfaceTexture/Surface `OutOfResourcesException` as the existing startup or Running-after-retirement terminal `ResourceExhausted` mapping and unexpected failure as `InternalFailure`; exactly two once-owned/reused direct GL client buffers; exact arithmetic, derived Framework tail/trim ledger, exact native callback-segment ledger, and actual boundary outcomes; zero Framework Bitmap/scratch creation while Native is Enabled; no predictive memory fact or tuning axis. |
| B15 exact-compatible reuse and smallest replacement | `H-RC`: owner identity retained exactly and no registry, alternate plan, rollback, or second topology appears. |
| B16 target replacement with compatible output owners | `H-RC`, `A-CAP`: target dependents change while FBO/texture, carrier, Bitmap/scratch, and JPEG owners retain identity. |
| B17 Bitmap reuse and one-shot recycle | `A-FJ`, `H-OS`: uses/leases settle first; timely normal receipt permits replacement; rejection/throw/expiry/nonreturn retains owner without retry; terminal has no watchdog. |
| B18 generic settlement, deadline, rejection, transfer, late receipt | `H-LC`, `H-OS`, `H-DL`, `A-CAP`, `A-GL`, `A-FJ`, `N-JPEG`, `A-CL`: complete wake-link/common matrix including Native preparation, target-listener occurrences, and preterminal incompatible-`NativeMallocCarrier` free at `D - 1`/`D`/`D + 1`, equality expiry, rejection, throw, nonreturn, and late return; at-most-one free call, exactly one after entry, no retry, and replacement gating on timely normal receipt; terminal-before-entry conversion, terminal-after-entry deadline retirement, and terminal-origin no-watchdog free; a winning expiry's raw-cause `NativeJpeg` `CapabilityCheck`/`SessionTerminal` visibility; Unarmed callback-dispatch transfer/late arm causality, allocation-free cell/action publication, owned GL/scheduled/Android shutdown and standalone metrics collector cancellation with its sole `finally`/return receipt, unresolved unregister isolated from Android return, nontermination and cleanup-only late completion, two distinct non-owned per-Session JPEG/delivery IO views with serial non-suspending Runnables and no view shutdown/cancel/close/quarantine/termination receipt, exact nonreturn residue and cleanup-only late return, exclusion of all non-owned Kotlin dispatch services with exact Session-owned submission/drainer/operation receipts, absence of weak-compressor cleanup state, lock order, named no-watchdog rules, exact residue, cross-view/Session independence, and finally-released barriers with awaited late receipts. |
| B19 carrier/native-health combinations and fault precedence | `H-RC`, `H-NL`, `A-FJ`, `N-JPEG`, `P-PKG`: exactly three combinations, one Framework adapter, zero Framework Bitmap/row-scratch creation/use/recycle while Native is Enabled, Framework resource creation only after `FrameworkOnly`, managed preparation-false clean ineligibility, or a completed safe disable for a later frame, and zero Framework allocation/work or retry on the switching frame; complete Native-preparation boundary whose timeout/late result creates no fresh attempt, production slot, frame, or drop, direct-load OOME as the sole load-time `ResourceExhausted` case, and every post-entry bootstrap C++ exception including `std::bad_alloc` as terminal `InternalFailure` poison. `N-JPEG` supplies connected-ABI black-box evidence only for ordinary `FrameworkOnly` no-query/no-invocation and the eligible API 30+ nonnull capability/re-resolution/first real call; unsuitable-device API 24–29 guarded non-entry and API 30+ null resolution remain unexecuted. `H-NL`/`H-RC` supply deterministic managed preparation-false-to-Framework and previously-Enabled/per-call-unavailable-to-pre-invocation-`InternalFailure` outcomes without claiming weak resolution. `P-PKG` supplies weak-symbol/dependency artifacts and source containment of same-function address-taking/null checks, null-before-invocation flow, and no loader/hook/pointer storage; static evidence is not production execution. The test DSO supplies only source-identical writer/adoption mechanics through a fake non-owning function and test-local services/writer capsules. SUCCESS-gated adoption, non-success no-adopter cleanup, no compressor cleanup, no test-DSO access to production state, no same-frame retry, and no late health change remain required. |
| B20 diagnostics ordering, fields, and wall-clock semantics | `H-OB` plus Section 6 boundary suites: sequence attempts, fixed fields, semantic message, raw cause, overflow gaps, and noncontrol behavior. |
| B21 JNI layout, writer, ABI, page size, and isolation | `N-JPEG`, `N-PKG`, `P-PKG`: exact bootstrap/registered signatures including Boolean `nativeHasWeakCompressor`, JNI-free hidden PIC runtime OBJECT linked unchanged into both DSOs, explicit non-owning compressor-function/services/adopter inputs, bootstrap-specific poison versus runtime/adoption OOM exception containment, same-function nested weak guard and nativeCompress re-resolution, module-local `MOD:build.gradle.kts` passage of exact `-DANDROID_WEAK_API_DEFS=ON` for every production/nativeTest native configuration, separate `CPP:CMakeLists.txt` evidence for `jnigraphics` linkage/target composition/diagnostics, weak undefined `AndroidBitmap_compress` and direct `DT_NEEDED`/mapping disclosure across all configured ABIs and production-bearing variants as applicable, and absence of dynamic compressor loader/owner/close paths; exact monotone writer `Safe -> OOM -> InternalFailure` state and `InternalFailure`-before-OOM-before-compressor precedence in both fault orders without a generic range classifier; SUCCESS-gated adoption with zero adopter calls for every non-success result; one exact managed segment per adopted nonempty callback with ordered adopt/local-reference-delete/native-free receipts; writer/result blocks and typed writer capsule/residue; connected-ABI runtime evidence; exactly two production exports, hidden operations, header inclusion, 16-KiB alignment, and DSO/state isolation. |

## 8. Acceptance-scenario traceability

| Scenario | Tests | Scenario | Tests | Scenario | Tests |
| ---: | --- | ---: | --- | ---: | --- |
| 1 | `A-API`, `P-PKG`, `N-PKG` | 10 | `A-CAP` | 19 | `H-DL`, `A-SES` |
| 2 | `A-API`, `H-GM` | 11 | `H-GM`, `A-GL`, `A-FJ`, `N-JPEG` | 20 | `H-PS`, `H-DL`, `A-SES` |
| 3 | `H-LC`, `A-SES` | 12 | `H-GM`, `A-GL` | 21 | `H-DL`, `H-OS`, `A-CL` |
| 4 | `H-LC`, `A-SES`, `H-OB` | 13 | `H-PS` | 22 | `H-DL`, `A-SES` |
| 5 | `H-LC`, `A-SES`, `A-CL` | 14 | `H-PS` | 23 | `H-DL`, `H-OS`, `A-SES` |
| 6 | `H-LC`, `H-RC` | 15 | `H-PS`, `H-OB`, `H-OS` | 24 | `H-GM`, `H-RC`, `H-PS`, `H-OS`, `A-GL`, `A-FJ`, `N-JPEG`, `P-PKG` |
| 7 | `H-RC`, `H-OS`, `A-CAP` | 16 | `A-GL`, `A-FJ`, `N-JPEG`, `P-PKG` | 25 | `H-OS`, `H-RC`, `H-DL`, `A-CAP`, `A-FJ`, `N-JPEG`, `A-CL` |
| 8 | `A-CAP`, `H-OB` | 17 | `H-RC`, `H-NL`, `A-CAP`, `A-GL`, `N-JPEG`, `N-PKG` | 26 | `H-OB`, `A-CAP`, `A-GL`, `A-FJ`, `N-JPEG` |
| 9 | `A-CAP`, `A-CL`, `H-OB`, `H-RC` | 18 | `H-OS`, `H-RC`, `H-NL`, `A-CAP`, `A-FJ`, `N-JPEG`, `A-CL` | 27 | `H-LC`, `H-RC`, `A-CAP`, `A-GL`, `A-CL` |

## 9. High-risk trace reproduction

| Paper trace | Executable reproduction |
| --- | --- |
| 10.1 concurrent start | `H-LC` and `A-SES` run both gate orders and verify zero access to the losing projection. |
| 10.2 latest desire versus stale rebuild | `H-RC` runs safe stale, ambiguous stale, and destructive A-to-B-to-A with topology-identity barriers. |
| 10.3 geometry change during startup | `A-CAP` and `H-OS` race initial resize at `D - 1`, `D`, and after expiry against provisional-target retention/replacement. |
| 10.4 safe Native failure with healthy target | `N-JPEG` proves the connected-ABI eligible nonnull production call and safe returned failure; `H-RC` proves the resulting one failed frame, monotone Native disable, later Framework use, unchanged target/Direct health, and no compressor cleanup, while `P-PKG` proves guarded same-helper structure. Unsafe writer/carrier ownership remains terminal, and no managed or static evidence is labeled production weak-resolution execution. |
| 10.5 unsubscribe versus queued callback | `H-DL` runs entry-first, unsubscribe-first, in-call dispatch detach, deadline-first, terminal-before/after-convergence, waiter cancellation, and pre-`Closing` self-rejection; all non-rejected callers observe the one controller-committed shared result, and replacement exclusion clears only on `Success`. |
| 10.6 stop versus callback entry | `H-DL` and `H-LC` run both `sessionGate -> settlementGate` orders and the callback-return terminal cutoff. |
| 10.7 resource limit/allocation around retirement | `H-RC`, `H-OS`, and `A-CAP` prove no allocation before deterministic denial, suspension before retirement, one allocation after retirement, terminal real failure, and no rollback topology. |

## 10. Completion and post-implementation smoke

An executable slice is complete only when its expected public result and every involved owner end in an active,
released, transferred, or exactly quarantined state recorded by the applicable ledger. Passing image tests do
not certify performance or select acceleration. Passing host fakes do not replace the real platform, Bitmap,
GL, native, or packaging suites assigned above.

An eligible API 30+ connected target is required to mark the representative Direct-to-Native execution slice
complete. On another target the slice is reported unexecuted, not passed or replaced by static ABI inspection;
runtime observations apply only to the connected ABI, while package checks remain authoritative for all four
shipped ABI artifacts.

After implementation, the short manual smoke check in Document 03 runs on an available connected physical
device. It is informal and is not part of `verifyScreenCaptureEngine`; no emulator is launched without separate
authorization, and no device matrix or runtime allowlist is created.
