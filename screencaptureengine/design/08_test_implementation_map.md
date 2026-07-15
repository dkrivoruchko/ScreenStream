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
`SETTLEMENT = screencaptureengine/src/main/kotlin/io/screenstream/engine/internal/settlement/`,
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

Source-file creation and behavioral acceptance are independent. Final passive contracts may be compiled before
their owners' behavior is complete, while one behavioral verification slice may cover several production files.
The approved source split creates no test solely for file boundaries and does not weaken any existing outcome,
ownership, race, or receipt assertion.

## 2. Source sets, runners, and tasks

| Source set or artifact | Runner and exact task | Scope |
| --- | --- | --- |
| `HOST` | Gradle/JUnit host runner through `testDebugUnitTest`; aggregate task `verifyScreenCaptureEngineHost` | Pure values, controller decisions, deterministic race/fault tests, arithmetic, storage, Stats, diagnostics, and owner ledgers. `BuildPackagingContractTest` is explicitly excluded. No Android opaque call is claimed verified by a host fake. |
| `AIT` and `AI` | `androidx.test.runner.AndroidJUnitRunner` through the single `connectedNativeTestAndroidTest` execution; `verifyScreenCaptureEngineDevice` and `verifyScreenCaptureEngineNative` both depend on that same task | All instrumentation classes. The library binds `testBuildType = "nativeTest"`; the nonpublished `nativeTest` build type contains the production library plus the test-only native fault library from `NTCPP`. Public API/lifecycle/frame tests, Android platform adapters, real EGL/GLES2 image/readback, Bitmap/Framework JPEG, real native registration/writer/compressor/receipts, cleanup, and image vectors therefore run once. Managed Android adapter branches absent on the connected device use their production adapter seams and exact injected SDK facts; this is not a device matrix. Those seams do not execute absent production native weak-resolution/API branches, whose evidence is allocated explicitly in Sections 3.2 and 4.4. |
| debug/release/nativeTest artifacts, merged manifests, consumer rules, and packaged ELF files | Separate filtered Gradle/JUnit `Test` task `verifyScreenCaptureEnginePackage`, depending on `assembleDebug`, `assembleRelease`, `assembleNativeTest`, and `lintRelease` | Runs only `HOST:BuildPackagingContractTest.kt`. Module artifact providers supply the selected artifacts without hard-coded paths. Standard Kotlin ABI validation treats the module artifact and Document-01 declaration inventory as public visibility authority; it does not inspect or assert root-project plugins, wrapper, classpath, or repository build configuration. `MOD:build.gradle.kts` evidence verifies that every production/nativeTest native configuration passes the exact CMake configure argument `-DANDROID_WEAK_API_DEFS=ON`; `CPP:CMakeLists.txt` evidence separately verifies `jnigraphics` linkage, target composition, and unguarded-availability diagnostics. Artifact/source checks prove the exact production DSO for each of `armeabi-v7a`, `arm64-v8a`, `x86`, and `x86_64` in every applicable production-bearing variant: debug/release contain only the production DSO, nativeTest contains both production and test DSOs, production has exactly its two exports, carries the direct `DT_NEEDED` dependency and weak undefined `AndroidBitmap_compress`, and every ELF has the required alignment. Source evidence also verifies the same-function nested availability guard and absence of dynamic compressor loading or cleanup. No generated verifier, parser, or script is created. |

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

Deterministic seams drive forced OOM and nonreturn; instrumentation supplies ordinary real allocation and API
receipts. Tests neither require platform OOM nor infer managed reclamation from GC timing.

## 4. Test files and owner coverage

The identifiers in this table are used by the traceability sections. A test file contains concise table-driven
checks; an identifier does not imply one end-to-end test.

Every test and production entry uses one of the declared repository-relative roots.

The implementation sequence relevant to these mappings is: final passive Target/GL contracts, complete Target
behavior, the typed Android integration, complete GL behavior with its real live render-owner identity, and only
then Reconciliation. Earlier contract compilation is not behavioral acceptance; each owner closes only through
the existing multi-file tests mapped below. Settlement and JPEG likewise close as behavioral slices across their
approved physical files rather than by treating each file as an independently complete subsystem.

| ID and exact test path | Type and production paths | Deterministic setup and expected result |
| --- | --- | --- |
| `H-LC` — `HOST:ControllerLifecycleTest.kt` | JVM/race/fault; `PUB:ScreenCaptureEngine.kt`, `INT:SessionController.kt` | Command barriers select start, update, stop, terminal-priority, drainer, and cross-Session orders. Observed Running publications after concurrent `updateParameters` calls are coherent immutable snapshots; the synchronous setter return is not an observation oracle. Unequal desires conflate to the latest committed value/revision without mixed fields. Precreated fixed outside-gate action slots preserve bounded allocation-free gate work. A saturated action batch with several already-complete cells self-marks dirty and rescans to consume every fact exactly once even when no producer signals again. `SessionController` is the sole lifecycle, desire/revision, topology/plan, reconciliation-occurrence, pacing/repeat-policy, counter, public-observation-value, and result authority; helper calculations cannot commit or retain any of that authority. Exactly one command winner and one terminal result commit; losing projection/desire/session authority is untouched; final Stats precedes terminal State. |
| `H-RC` — `HOST:ReconciliationAndAllocationTest.kt` | JVM/race/fault; `INT:ReconciliationOwner.kt`, `INT:TargetContracts.kt`, `INT:TargetOwner.kt`, `INT:GlPipelineContracts.kt`, `INT:GlPipelineOwner.kt`, `INT:JpegRuntimeCore.kt`, `INT:JpegRuntimeOperations.kt`, `INT:JpegRuntimeOwner.kt`, `INT:FrameworkJpegOwner.kt`, `INT:EncodedStorageOwner.kt`, `INT:CleanupOwner.kt` | Owns eligibility, reuse, and topology transition. It covers A-to-B-to-A, `PreparedTarget` return across key/generation/currentness changes with exactly one `Installed` or `CleanupClaimed` consumption, deterministic pre-retirement denial, smallest-scope replacement, exact-shape identity reuse, and recycle/free-receipt or managed-logical-detach then fresh current/nonterminal recheck. Reconciliation consumes the exact installed Target and GL owner identities; passive declarations alone never satisfy live-topology reuse. Carrier replacement first drains every lease and entered operation; Native free, Native allocation, Managed logical detachment, and Managed allocation remain distinct. Only the matching typed replacement-allocation occurrence may then start, without rerunning preparation. Framework creation requires one current nonreused key, selected Framework backend, Native `Disabled`, checked fixed shape, no compatible owner, and no extant occurrence; a timely safe Native transition holds `Running(Suspended(Reconfiguring))` until complete Framework install and performs no same-frame allocation or retry. No registry, alternate planner, rollback topology, predictive accounting, or production weak-symbol claim is introduced. |
| `H-OS` — `HOST:OperationSettlementRaceTest.kt` | JVM/race/fault; `SETTLEMENT:OperationDeadline.kt`, `SETTLEMENT:OperationSettlement.kt`, `INT:SessionController.kt`, `INT:TargetContracts.kt`, `INT:TargetOwner.kt`, `INT:GlPipelineContracts.kt`, `INT:JpegRuntimeCore.kt`, `INT:JpegRuntimeOperations.kt`, `INT:JpegRuntimeOwner.kt`, `INT:FrameworkJpegOwner.kt`, `INT:CleanupOwner.kt` | Owns the combined settlement/deadline behavioral slice and fault partitions. Its barrier table covers primary-fact-first allocation-free publication, outside-gate scheduling, `Fired` before Future publication, current/stale rejection, inline entry/return, `Submitting` and dequeued cancellation, scheduler shutdown/nonprogress, settlement pauses, commit-before-signal, and exact cleanup. Native preparation, target listeners, `PreparedTarget` publication/install/cleanup arbitration, Native free, Section 4.1 creation, `NativeCarrierReplacementAllocationOccurrence`, and `ManagedDirectCarrierReplacementAllocationOccurrence` use their documented boundaries. Each allocation independently covers `D - 1`/`D`/`D + 1`, empty-at-`D`, rejection, direct `OutOfMemoryError`, throw, nonreturn, current/stale/late/terminal result, transfer, and one-shot cleanup; Managed detachment is not a free occurrence. Sequential occurrences receive separate deadlines and create no compound deadline. |
| `H-PS` — `HOST:PacingStorageStatsTest.kt` | JVM/race/fault; `INT:PacingOwner.kt`, `INT:TargetContracts.kt`, `INT:TargetOwner.kt`, `INT:EncodedStorageOwner.kt`, `INT:ObservationOwner.kt`, `INT:SessionController.kt` | Owns pacing, storage, Stats, and the new-admission cutoff. A generation-fenced `AtomicBoolean` latest-pending seam, production slot, cache roles, clock, and wake identities cover Auto/MaxFps/SampleEvery, repeat, cached-first, one-wake churn, output pacing, displacement, and exact `1_000 ms` Stats cadence. Only a current listener sets/signals; only `SessionController` exchanges the bit and commits work. Before/after-exchange, deferral, coalescing, self-rescan, and retired-generation rows prevent loss or cross-generation effect. At Native disable, no new fresh, repeat, cached-first, encode, or delivery admission occurs until complete Framework installation; delivery lifecycle ordering belongs to `H-DL`. Storage covers the derived tail formula, writes, close/commit/abort, trim/copy, malformed ranges, allocation faults, and persistent exact-length segments. |
| `H-DL` — `HOST:DeliveryOwnerRaceTest.kt` | JVM/race/fault; `INT:DeliveryOwner.kt`, `INT:EncodedStorageOwner.kt`, `SETTLEMENT:OperationDeadline.kt`, `SETTLEMENT:OperationSettlement.kt`, `INT:SessionController.kt` | Owns delivery admission, record/lease lifecycle, and exact dispatch/trampoline/callback races. Independent barriers cover rejection versus entry, inline return, unsubscribe, accepted-task deadline boundaries, `Fired`-before-future, cancellation/dequeue, terminal cutoff, and scheduler shutdown/nonprogress on the distinct serial delivery IO view. One identity-fenced handoff fact converges to one controller-committed shared result and after-unlock signal; waiter cancellation is local, self-unsubscribe rejects before `Closing`, and replacement exclusion clears only on shared `Success`. For Native-disable versus trampoline/callback entry, entry-first remains authoritative and settles the existing record and lease; a cutoff-first fixture that begins before any delivery opportunity is admitted creates no later opportunity, record, worker, dispatch, or entry until complete Framework install. No assertion is made for an already-created unentered record. Terminal-during-dispatch keeps the transferred link `Unarmed` until a later normal accepted return; throw/rejection never fabricates a deadline, and late cleanup cannot revise the shared result. |
| `H-GM` — `HOST:GeometryColorAndBoundsTest.kt` | JVM/unit/image; `PUB:ScreenCaptureParameters.kt`, `INT:ReconciliationOwner.kt`, `INT:GlPipelineContracts.kt`, `INT:GlPipelineOwner.kt` pure resolver seams | Checked arithmetic and the raw vector verify region/crop/rotation/mirror/size mapping, Downscaled planning, P3 binary64 oracle, grayscale oracle, overflow, narrowing, and deterministic capacity denial. Injected positive asymmetric GL capability facts compare target `(Tw,Th)` and output `(Ow,Oh)` independently per axis against `min(GL_MAX_TEXTURE_SIZE, corresponding GL_MAX_VIEWPORT_DIMS component)`. For each target/output axis, swapped width/height fixtures cover the exact limiting minimum and that minimum plus one, including cases where texture size is smaller and where the corresponding viewport component is smaller. Startup denial and Running denial known before retirement allocate no target/output resource, retire nothing, preserve the old topology parked without output where one exists, cannot trigger a Downscaled-to-Full fallback, and cannot defer discovery to `updateTexImage`. Geometry and raw mapping expectations are exact. |
| `H-OB` — `HOST:ObservationAndDiagnosticsTest.kt` | JVM/unit/fault; `PUB:ScreenCaptureObservations.kt`, `INT:ObservationOwner.kt`, `INT:SessionController.kt` | Owns observed lifecycle and diagnostics. Direct clock/wall-clock values and controlled Flow collectors cover immutable snapshots, saturation, finite protection, cadence, terminal order, sequence attempts, overflow, semantic messages, raw cause identity, and no diagnostic control authority. Managed- and Native-allocation timeouts retain their existing `FrameworkJpeg` and `NativeJpeg` source/cause rules respectively. At Native disable, observers see `Running(Suspended(Reconfiguring))` until complete Framework installation, never a historical or premature `Active`; a higher-priority terminal winner remains final. |
| `H-NL` — `HOST:NativeLoaderPolicyTest.kt` | JVM/fault; `INT:JpegRuntimeCore.kt`, `INT:JpegRuntimeOwner.kt` loader seam | Deterministic seams have no ABI input and distinguish clean load unavailability, sticky first-cause `LoadOome`, bootstrap poison, and managed weak-capability decisions without claiming native mechanics. Concurrent callers cause one load attempt. A direct `System.loadLibrary` OOM fixes process-lifetime `LoadOome`: a timely contender and all future Sessions receive `ResourceExhausted`, with no retry, bootstrap, carrier allocation, or Framework fallback. A late OOM cannot revise the Session outcome that already won but governs future Sessions. Post-entry bootstrap/registration failure is distinct sticky `InternalFailure` poison. Capability false selects Framework before a Native attempt; after Enabled publication, per-call unavailability is pre-invocation `InternalFailure` without fallback or retry. Preparation deadline/terminal/late rows prove only timely success publishes carrier/backend owners and a late result starts no fresh attempt, frame, production slot, or drop. |
| `A-API` — `AIT:PublicApiInstrumentationTest.kt` | instrumentation/API; `PUB:ScreenCaptureEngine.kt`, `PUB:ScreenCaptureConfig.kt`, `PUB:ScreenCaptureParameters.kt`, `PUB:ScreenCaptureOutput.kt`, `PUB:ScreenCaptureObservations.kt` | Direct construction and the standard Kotlin ABI/Kotlin-metadata surface verify the complete Document-01 public inventory, visibility, defaults, ordinary-class semantics, internal engine-produced constructors, validation, and exception types. `CaptureMetricsProviders` has the one public factory `fromDisplay(Context, Display)` returning the immutable reusable built-in provider; the default-display factory remains internal. This is Kotlin API evidence; Gate B adds no separate Java-hardening surface. |
| `A-SES` — `AIT:SessionContractInstrumentationTest.kt` | instrumentation/race; `PUB:ScreenCaptureEngine.kt`, `PUB:ScreenCaptureOutput.kt`, `INT:DeliveryOwner.kt`, `INT:ObservationOwner.kt`, `INT:SessionController.kt` | Installed facade tests use test-owned owner seams to verify main-safe start/unsubscribe, wrong-state calls, frame thread/lifetime/range checks, cached-first metadata, consumer replacement, and public terminal mappings. Unsubscribe races verify one controller-committed shared result/signal for every waiter, local waiter cancellation, pre-`Closing` same-callback rejection, `Success` idempotence, and replacement release only after shared `Success`. |
| `A-CAP` — `AI:AndroidCaptureInstrumentationTest.kt` | instrumentation/race/fault; `PUB:ScreenCaptureConfig.kt`, `INT:AndroidCaptureOwner.kt`, `INT:CaptureMetricsOwner.kt`, `INT:TargetContracts.kt`, `INT:TargetOwner.kt`, `INT:GlPipelineContracts.kt`, `SETTLEMENT:OperationDeadline.kt`, `SETTLEMENT:OperationSettlement.kt`, `INT:CleanupOwner.kt` | Section 4.2 is the canonical metrics matrix. The same suite verifies MediaProjection, VirtualDisplay, SurfaceTexture, resize/visibility, generation fencing, target-listener deadlines, and cleanup order. Only typed `CurrentTarget` supplies the Surface to `createVirtualDisplay`; an uninstalled `PreparedTarget` has zero display/listener/producer calls, and an installed pre-producer path accepts only typed safe no-producer evidence. Current positive GL-cap facts gate construction per axis; ordinary real allocation receipts preserve the documented classifications, while deterministic pre-retirement denial allocates and retires nothing. |
| `A-GL` — `AI:GlPipelineImageInstrumentationTest.kt` | instrumentation/GL/image/fault; `INT:TargetContracts.kt`, `INT:TargetOwner.kt`, `INT:GlPipelineContracts.kt`, `INT:GlPipelineOwner.kt`, `INT:JpegRuntimeCore.kt` carrier seam | Real EGL/GLES2 verifies image/readback, exact bind/currentness/unbind, capability and precision classification, stable reusable client buffers, OES sampling, canonical state, transforms/colors, Session isolation, and absence of `eglTerminate`. Per-axis minimum fixtures agree with `H-GM` and deny doomed work before construction or `updateTexImage`. Construction receipts prove partial adoption into `PreparedTarget`, exclusive install-or-cleanup, and applicable `Surface -> SurfaceTexture -> OES` cleanup order; an uninstalled result publishes no current target, carrier lease, or frame. The complete GL slice establishes the actual installed render-owner identity consumed later by Reconciliation; its passive contract declarations are not a substitute. Representative Direct readback uses the real exact range of each ordinary installed carrier path for the assigned Framework and eligible Native slices. Injected seams cover context, EGL/GL, construction, and destruction faults without platform-internal proof; the private GL lane remains the sole `eglMakeCurrent` caller and the approved capped drains remain unchanged. |
| `A-FJ` — `AI:FrameworkJpegInstrumentationTest.kt` | instrumentation/Bitmap/JPEG/image/fault; `INT:FrameworkJpegOwner.kt`, `INT:EncodedStorageOwner.kt`, `INT:JpegRuntimeCore.kt`, `INT:JpegRuntimeOwner.kt` carriers | Owns real Bitmap/API and Framework JPEG behavior. Exact injected SDK cases 24, 25, 26, and 37 adopt a real `Bitmap.createBitmap(Ow, Oh, ARGB_8888)` result and assert actual width, height, `rowBytes`, `byteCount`, config, and mutability. API 24–25 additionally prove zero invocation of the API-26 metadata helper; API 26+ checks the guarded branch against actual color space and `Bitmap.Config.HARDWARE`. Actual metadata selects direct copy or one width-sized scratch. Ordinary installed Native and Managed carrier leases exercise the one private synchronous real exact-range transfer, `Bitmap.compress`, encode/decode, transaction, and copy integrity; deterministic seams cover forced faults without a transfer-adapter lifecycle or device matrix. |
| `A-CL` — `AI:CleanupIsolationInstrumentationTest.kt` | instrumentation/race/fault; `INT:AndroidCaptureOwner.kt`, `INT:CaptureMetricsOwner.kt`, `INT:TargetContracts.kt`, `INT:TargetOwner.kt`, `INT:GlPipelineContracts.kt`, `INT:GlPipelineOwner.kt`, `INT:JpegRuntimeCore.kt`, `INT:JpegRuntimeOperations.kt`, `INT:JpegRuntimeOwner.kt`, `INT:FrameworkJpegOwner.kt`, `INT:DeliveryOwner.kt`, `INT:CleanupOwner.kt` | Owns cleanup isolation. Sections 4.2 and 4.6 cover separate Android-Surface and GL-destruction deadlines, terminal/nonreturn residue, PreparedTarget quarantine, and execution-resource shutdown; creation holds cover partial target/Bitmap ownership, result publication, active-to-cleanup transfer, and recycle entry. Managed-carrier retirement proves post-drain logical reference drop without a physical-free or GC-timing receipt. Each exact occurrence/evidence/owner bag stays rooted while unrelated roots and another Session progress. Finite preterminal work, no-watchdog terminal conversion, ordered late receipts, and `finally` barrier release remain required. |
| `N-JPEG` — `AI:NativeJpegInstrumentationTest.kt` | native/instrumentation/race/fault/image; `INT:JpegRuntimeCore.kt`, `INT:JpegRuntimeOperations.kt`, `INT:JpegRuntimeOwner.kt`, `INT:EncodedStorageOwner.kt`, `CPP:screen_capture_engine_jni.cpp`, `CPP:native_jpeg_runtime.h`, `CPP:native_jpeg_runtime.cpp`, `NTCPP:screen_capture_engine_native_test_jni.cpp` | The JPEG runtime closes as one behavioral slice across its three Kotlin files. Its owner submits one non-suspending Runnable at a time through its own per-Session `Dispatchers.IO.limitedParallelism(1)` view, distinct from delivery and metrics; barriers preserve serial submission, entry, operation, settlement, and mechanical return without asserting physical thread identity or a view-termination receipt. On an eligible API 30+ connected target, the production DSO is exercised only black-box: a real Direct carrier enters real bootstrap, a nonnull `nativeHasWeakCompressor`, same-helper nonnull re-resolution in `nativeCompress`, the real non-owning weak-function call, writer/adoption, committed storage, and decode on that executing ABI. The ordinary `FrameworkOnly` case observes no weak-capability query or compressor invocation. The API 24–29 guarded non-entry and API 30+ null-address production paths remain unexecuted unless a suitable connected target actually supplies those conditions; managed seams, the test DSO, and static/package evidence are not reported as their production execution. While Native remains Enabled, Framework Bitmap/row-scratch create/use/recycle counts are zero. A safe Native disable drops the switching frame with no Framework allocation/work or same-frame retry; Framework becomes eligible only for a later frame. The same real registered production glue is called with managed sink OOM/non-OOM throws and feasible malformed direct blocks/ranges through existing internal seams; it proves throwable precedence and clearing, fixed result-block evidence, local-reference cleanup, exact one-managed-segment-per-nonempty-callback transfer/free order, zero-size no-op, copy integrity, and exact residue release without a production hook or forced native-service fault. Separately, the test DSO dynamically proves bootstrap-specific C++ poison containment and drives the same unchanged JNI-free runtime object with a test-local non-owning compressor function, writer services, adopter, writer capsules, and barriers for applicable runtime/adoption C++ exception partitions, reachable writer status precedence, residue, callback/adoption/copy faults, every non-success compressor result after partial callbacks, and late-health mechanics; it never reads or mutates production state. Impossible defensive writer exceptions remain source-inspection evidence only. Fixed writer/result owner blocks, no raw ownership `Long` or function pointer crossing JNI, no compressor cleanup, no same-frame retry, and precedence remain covered. |
| `N-PKG` — `AI:NativePackagingInstrumentationTest.kt` | native/instrumentation/package; `CPP:CMakeLists.txt`, `CPP:native_jpeg_runtime.h`, `CPP:native_jpeg_runtime.cpp`, `NTCPP:CMakeLists.txt`, `NTCPP:screen_capture_engine_native_test_jni.cpp` | In the installed configuration, nativeTest successfully loads the engine's own production DSO, completes its bootstrap/registration, and proves guarded weak capability independently; this is sufficient runtime structural proof for that process and makes no ABI-string or Kotlin ABI-predicate assertion. The test bridge reaches source-identical runtime mechanics only through test-local inputs. It makes no runtime claim for other ABIs and cannot satisfy the production Direct+Native image slice. |
| `P-PKG` — `HOST:BuildPackagingContractTest.kt` | build/package; `MOD:build.gradle.kts`, `CPP:CMakeLists.txt`, `CPP:screen_capture_engine_jni.cpp`, `CPP:native_jpeg_runtime.h`, `CPP:native_jpeg_runtime.cpp`, `NTCPP:CMakeLists.txt`, `NTCPP:screen_capture_engine_native_test_jni.cpp`, `MOD:src/main/AndroidManifest.xml`, `MOD:consumer-rules.pro` | Excluded from `testDebugUnitTest` and selected alone by `verifyScreenCaptureEnginePackage`. Module artifact evidence validates the standard Kotlin public ABI against Document 01, empty manifests, and narrow consumer rules. Existing `lintRelease` must report no `NewApi` finding for `FrameworkJpegOwner.kt`, with no local `NewApi` suppression and no lint baseline; this is guarded-API evidence only. `MOD:build.gradle.kts` inspection requires every production/nativeTest native configuration to pass the exact CMake configure argument `-DANDROID_WEAK_API_DEFS=ON`; no root-project wiring is asserted. `CPP:CMakeLists.txt` inspection separately requires system `jnigraphics` linkage, the one hidden PIC `native_jpeg_runtime.cpp` OBJECT target linked unchanged into production and test DSOs, both JNI bridges including `native_jpeg_runtime.h`, correct target composition, and unguarded-availability diagnostics as errors; it is not treated as the sole activator of the option. Source inspection further requires a JNI-free runtime with one explicit non-owning compressor function plus writer services/adopter parameters, complete outer C++ exception containment at JNI/adoption/writer boundaries, the bootstrap-specific rule that every post-entry C++ exception including `std::bad_alloc` becomes terminal `InternalFailure` poison, writer `noexcept`/catch-all defensive classification with no production injection hook, and production-adapter deletion of every created segment-view local reference on all exits. Every production ELF in all configured ABIs and production-bearing variants as applicable must expose weak undefined `AndroidBitmap_compress` and the direct `DT_NEEDED` dependency, whose possible mapping on API 24–29 or under `FrameworkOnly` is disclosed without predictive accounting. Source inspection requires both same-function address-taking and the null check to be structurally nested inside `__builtin_available(android 30, *)` in `nativeHasWeakCompressor` and `nativeCompress`, with null-before-invocation control flow, no pointer crossing or writable storage, and no `dlopen`/`dlsym`/`dlclose`, compressor owner/capsule/block, or compressor cleanup path. These source/artifact checks prove structure and containment, not actual runtime null resolution or non-entry on an absent API branch. Debug/release exclude the test DSO; nativeTest contains both; production has exactly two exports and hidden registered operations; every ELF has 16-KiB alignment. |

### 4.1 Framework creation-race matrix

`H-OS` owns the single `FrameworkResourceCreationOccurrence` matrix; `H-RC`, `A-FJ`, and `A-CL` supply eligibility, real-API, and cleanup-isolation assertions.
One `jpegEnteredOperationSafetyNanos` deadline spans entry, Bitmap return and immediate adoption, actual-metadata inspection, optional scratch construction, and allocation-free result publication.
Rows cover rejection, inline entry/return, queued cancellation, `D - 1`/`D`/`D + 1`, empty-at-`D`, OOM/throw/nonreturn, settlement-gate pauses, commit-before-signal, and current/stale/late/terminal arbitration.
Terminal-before-entry invokes no allocator; terminal-after-entry transfers the intact occurrence, worker, and partial-owner bag to no-watchdog cleanup.
Bitmap success followed by incomplete scratch work installs neither resource and recycles the Bitmap exactly once; non-install scratch final reference drops only after both creation work and occurrence settlement.
Only timely current complete success installs before `Active`; safe stale results clean without lifecycle failure, while ambiguous ownership remains terminal.
Each distinct preparation, creation, encode, recycle, carrier-free, or carrier-allocation occurrence receives its own documented deadline; consecutive occurrences create no compound deadline or SLA.

### 4.2 Metrics and Android API matrices

The sole production authority for factory associations, API-band reads, invalidation mechanics, lanes, retained
references, and cleanup ownership is [Document 06 Section 7.2](06_implementation_design.md#72-metrics).
`A-CAP` injects exact SDK facts at API 24, 29, 30, 33, 34, and 37; these are API-branch cases, not a device
matrix. The factory matrix is intentionally small:

| Factory cases | Controlled inputs and required observations |
| --- | --- |
| null config, internal only | Default Display present/missing, selected-object removal, same-ID replacement, later add, changing platform metrics, and API-band recorders. Assert one Session-private default-display provider, application-Context/DisplayManager ownership, resolution of `Display.DEFAULT_DISPLAY`, unavailable publication while absent, and a new continuous-validity epoch that may select a different current default object. No internal factory is public. |
| public `fromDisplay` | One application-normalizable Context, one exact explicit Display object/ID, later invalidity and recovery of that same object, a different object with the same ID, and unrelated default/other-display changes. Assert one immutable reusable provider, normalized application Context, exact-object stickiness, no association switch, documented constructor rejection, and the fact that this Display is metrics authority only and neither selects nor verifies the MediaProjection capture source. A Session invokes `observe()` once on that exact configured object. |
| custom provider | Supply one identity-bearing provider and verify that Session attachment, the sole `observe()` call, collection, cancellation, and completion retain that exact object identity. Activity/window-following, dynamic-display, or caller-specific invalidation remains caller policy; the engine adds no built-in callback routing to it. |

The built-in provider/collection matrix uses identity-bearing Context, DisplayManager, listener, Handler,
channel, epoch, WindowContext/WindowManager, and cleanup recorders:

| Collection case | Required observation |
| --- | --- |
| one or concurrent engine Sessions | The engine calls `observe()` exactly once on each Session's exact configured provider, or on its private default provider for null configuration. Reusing one built-in provider yields independent listener, channel, epoch/cache state, and unregister ownership per collection. Every built-in callback on API 24–37 is explicitly routed to the process Main Looper; register/read/emission/unregister use that Session's metrics-IO view. One Session cannot affect another. |
| standalone direct, repeated, or parallel collection | `observe()` is cold and performs no work before collection. Every sequential or overlapping collection creates independent listener, channel, epoch/cache state, and unregister ownership, uses explicit process Main-Looper callback routing, and performs register/read/emission/unregister in its controlled upstream context. A new collection inherits no fact or completion, and cancelling one cannot affect another. |

Barriers surround collection start, listener registration, seed, callback delivery, cancellation, signal close,
unregister, and late callback entry. Positive provider/listener identity, Looper, call-context, and isolated-state
evidence establishes independent runtime state without requiring a particular Handler object identity.

For each built-in epoch, `A-CAP` table-drives these exact reads:

- API 24–29: one `Display.getRealSize(Point)` per complete read and density from one fresh transient
  `applicationContext.createDisplayContext(epochDisplay)` for that same read;
- API 30–37: one cached WindowContext/WindowManager pair per continuous valid-display epoch, maximum-window
  bounds from that cached pair, and density from one fresh transient display Context per complete read;
- API 30 obtains the epoch WindowContext through that epoch's display Context; API 31–37 uses the direct
  display-specific WindowContext overload required by Document 06;
- an ordinary selected-ID change preserves cached WindowContext/WindowManager identity and requires one complete
  reread; frame production across unchanged metrics performs zero provider platform reads.

Selected-ID add/removal sets the sticky `epochInvalidated` bit before signalling. Every reader token begins with
the one consuming exchange `boundary = epochInvalidated.getAndSet(false)`. The deterministic epoch matrix is:

| Controlled boundary position | Required observation |
| --- | --- |
| Set before the token's opening exchange | The exchange returns true. This token is a boundary-only pass: retire the prior epoch and cached window pair, publish unavailable, enqueue one conflated recovery token, and perform no epoch resolution or installation. |
| Set after the opening exchange, during any platform read, or before final candidate installation | The final nonconsuming sticky-bit/selection/validity validation suppresses the candidate. Retirement and unavailable publication are idempotent, and one recovery token remains. |
| Set immediately after successful final candidate validation | The current complete candidate may publish. The bit remains set and the callback wake drives a later boundary-only pass, so installation never erases the boundary. |
| Recovery after a boundary-only pass | Only a later token may resolve and install an epoch. Explicit `fromDisplay` requires the same exact Display object to be valid; the internal default policy may resolve a different current default object. A null/invalid selection stays unavailable until a matching add/change wake. |

Barriers at both sides of the opening exchange, between every platform read, at final validation, and immediately
before installation cover removal/add and validity races. Every suppressed candidate keeps width, height, and
density from the retired epoch out of the latest cell. A remove/add storm remains conflated and creates at most
one new WindowContext/WindowManager pair for the next continuous-validity epoch. Epoch installation contains no
write that clears `epochInvalidated`.

An ordinary selected-ID `onDisplayChanged` is not an epoch boundary. When it races a valid read, that read's
complete candidate may publish, and the callback's pending token must then perform a later complete reread.
Each tuple comes from one read; field values from separate reads are never mixed. A callback during a read leaves
one later token, and a bounded callback storm leaves at most one pending token.

For built-in invalidation and cleanup, `A-CAP` and `A-CL` independently control registration-before-seed,
seed/read, callback arrival, storms, terminal fencing, signal close, unregister, and parent/child completion.
All API 24–37 built-in listeners use explicit process Main-Looper callback routing; registration, reads,
emission, signal close, and unregister remain on the controlled upstream context or Session metrics-IO view.
The callback performs only the O(1) fence check and conflated signal attempt.

The cleanup matrix drives unregister normal return, throw, and nonreturn. A held unregister does not delay
Session HandlerThread shutdown, unrelated roots, or another Session. Throw/nonreturn retains the exact listener,
`DisplayManager`, Main-Looper Handler dependency, collection occurrence, and other still-required built-in
references as applicable; it excludes the Session Android Handler and HandlerThread. A deterministic
platform-snapshot analogue captures a listener delivery before unregister, then enters it from the Main queue
(a) after the authority fence and normal unregister return and (b) independently after Session HandlerThread
shutdown. Both entries remain O(1), cannot publish, cannot wake current state, and make no lifecycle decision.
Tests assert logical lane isolation rather than physical IO thread or Handler object identity. Application Context dependencies remain
with the collection; transient per-read display Contexts do not survive their read.

The structured metrics matrix precreates one plain parent `Job`, one
`CoroutineScope(parentJob + metricsIoView)`, one `launch(start = CoroutineStart.LAZY)` collector child, the empty
observe-return/Flow cell, collection-entry/provider-outcome cells, and the parent-completion cell. All are attached
before the unlocked `child.start()` call. The following rows are canonical:

| Controlled lifecycle case | Required observation |
| --- | --- |
| Lazy start versus terminal cancellation | `parentJob.cancel()` is requested outside gates. The child either has no entry or enters once; final parent completion is the one receipt. A no-entry result has no `observe()`, collection, or body-cleanup entry fact. |
| `observe()` normal return | The Flow cell is empty before attachment and remains empty until `observe()` returns. The returned reference is published once before the collection-entry decision; a nonnull Flow and provider remain rooted until final parent completion. Terminal/cleanup may win between return and collection entry. |
| Flow normal completion before terminal | The documented pre-first-value or post-first-value outcome is published, child cleanup completes, the child completion handler calls `parentJob.complete()`, and the final parent completion supplies the one receipt. |
| Provider `CancellationException` while the collector Job is active | The catch boundary calls `ensureActive()`, which returns normally; the original provider exception is the raw cause of `InternalFailure`. |
| Parent cancellation wins before the same catch classification | `ensureActive()` throws the Job cancellation, selecting terminal cancellation rather than provider failure. Barriers on both sides of the active check cover both race orders and one resulting classification. |
| Other provider throw from `observe()` or collection | The original throwable is the raw `InternalFailure` cause; child cleanup and final parent completion remain the mechanical path. |
| Entered getter, collection, built-in read, or unregister does not return | The parent remains incomplete. The exact parent/scope/child/provider branch, returned Flow when present, and required built-in collection dependencies remain rooted while independent roots progress. Unregister residue excludes the Session Android Handler and HandlerThread. A late return may complete only this parent and reduce only its residue. |

The child completion handler only calls `parentJob.complete()`. The parent completion handler only fills the
precreated completion cell and performs the nonblocking drainer wake. Each handler is invoked while
`sessionGate` or `settlementGate` is deliberately held by the test and must return without acquiring an engine
gate, waiting, running cleanup/controller work, calling outward code, or creating another receipt/action object.
Positive structural and behavioral evidence proves the explicit parent-child relation, one lazy collection,
terminal propagation through the parent, and one final-parent-completion receipt; no source-text blacklist or
implementation-name parser is part of this suite.

The Android call recorder verifies these exact boundaries and lanes:

| Existing test | Calls and required result |
| --- | --- |
| `A-CAP`, `A-CL` built-in metrics | Execute the complete Section-4.2 matrix and assert exact provider/listener identities, uniform Main-Looper callback routing, upstream/metrics-IO work, parent receipt, late-snapshot fencing, HandlerThread independence, and unresolved residue. |
| `A-CAP` projection/display | On the Android Handler: register the projection callback with that Handler; call the sole `createVirtualDisplay` with the fixed private name, positive selected width/height/density, automatic-mirroring flag, the typed `CurrentTarget` Surface, and null display callback/Handler; call exact-identity `resize`, `setSurface`, callback unregister, VirtualDisplay release, and projection stop. No uninstalled `PreparedTarget` reaches this call. Null creation and the documented Security/OOM/illegal-state/unexpected throw partitions remain distinct; void calls record normal, throw, or unresolved return. |
| `A-CAP` target | After one-time positive per-axis GL-cap admission, the exact-context GL lane creates OES, `SurfaceTexture(oes, false)`, its default size, and `Surface`, adopting each return into `PreparedTarget`; deterministic seams classify constructor faults. Exactly one timely current complete result installs as `CurrentTarget`; all other complete/partial results take its cleanup path. Only installed state permits listener or producer work; its pre-producer path accepts typed safely unentered/inapplicable or returned-no-owner create/attach evidence. Pre-retirement denial performs no target call or retirement. The Android Handler installs/removes the listener with the same-Handler sentinel; the GL lane calls `updateTexImage()`, `getTransformMatrix(precreatedFloat16)`, and API-33+ `getDataSpace()` only after producer admission. |
| `A-CAP`, `A-CL`, `H-OS` target-listener occurrences | Installation and removal are separate preterminal Android-operation occurrences governed by the Document-07 Android constant. For each, drive normal/throw at `D - 1`, `D`, and `D + 1`, empty-at-`D`, nonreturn, terminal-after-entry, and late completion. Terminal-before-entry proves that no bounded occurrence starts; any required cleanup removal then uses the Document-06 no-watchdog path. Only timely normal installation permits producer attach; only timely preterminal or no-watchdog cleanup removal plus its sentinel advances retirement. After removal timeout, a late normal removal is its exact cleanup receipt and may advance only that cleanup chain. Listener timeout/late settlement fabricates no frame, production attempt, or frame-drop count. A winning timeout attempts source-`SurfaceTarget` `CapabilityCheck` and the matching `SessionTerminal` with the same cause; a higher-priority terminal leaves only cleanup visibility. |
| `A-CAP`, `A-CL`, `A-GL` release chain | Installed `CurrentTarget` uses the documented Android detach/listener/zero-lease prerequisites; mutually exclusive uninstalled `PreparedTarget` cleanup requires none of those inapplicable receipts. Each path preserves `Surface -> SurfaceTexture -> OES`, and only each normal receipt advances to the next applicable owner. Throw/nonreturn roots the exact remainder; late normal receipts advance cleanup only. |

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
| GL construction probes | Drive clean, allocation, other, and mixed/uncleared evidence after a clean boundary, including every partial-adoption prefix and complete `PreparedTarget`. Assert typed residue, exclusive current install or ordered cleanup, no uninstalled carrier lease/frame/current publication, and unchanged old/final drain counts. |
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
the installed configuration without querying or deriving an ABI string. Successful own-DSO load followed by
bootstrap/registration is the runtime structural proof for that process. `P-PKG` separately proves the exact
production DSO and static weak-symbol/dependency/guard plus export/alignment evidence for all four shipped ABIs
in every applicable artifact.

`H-NL` gives loader publication one process-wide monotone result. Concurrent callers observe exactly one direct
`System.loadLibrary` attempt. Its first `OutOfMemoryError` publishes sticky `LoadOome(firstCause)`: a timely
contender and all future Sessions receive `ResourceExhausted`, while no retry, bootstrap, carrier allocation, or
Framework fallback occurs. If another Session result or deadline already won before a late OOM settles, that
Session is unchanged and future Sessions still observe `LoadOome`. Clean pre-entry unavailability and sticky
post-entry bootstrap poison remain separate outcomes. The loader seam accepts no ABI fact.

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

On an eligible installed configuration, `N-JPEG` applies this production-DSO bridge matrix through the existing private
managed/internal test seams. Every row invokes the real registered `NativeBridge` entry and real production
services; none selects a native fault, calls the test DSO, or reads test-DSO state.

Every normal evidence return initializes all five `NativeResultBlock` words: writer status, total bytes,
adopted bytes, remaining bytes, and remaining segments. Native writes the writer-status discriminator as
`uint64_t`; Kotlin accepts only `0` (`Safe`), `1` (`OutOfMemory`), and `2` (`InternalFailure`). Every other bit
pattern—including the upper-half patterns observed as a negative Kotlin `Long`—is malformed evidence selecting
terminal `InternalFailure`, with no byte publication, Framework fallback, or Native-health change.

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
| `N-JPEG` writer status and precedence | Every normal evidence return first proves all five result-block words were initialized. Accept exact `uint64_t` writer codes `0`, `1`, and `2` as `Safe`, `OutOfMemory`, and `InternalFailure`; table-drive representative unknown low and high-bit patterns, including values read as negative Kotlin `Long`, to malformed terminal `InternalFailure` with no bytes, fallback, or health mutation. At runtime, drive null data with nonzero size, invalid capsule/current state, and lock/link/status-publication failure to `InternalFailure`; drive null `malloc` and otherwise-valid segment-size/cumulative-addition managed-`Int` nonrepresentability or overflow to exact OOM. Assert monotone `Safe -> OOM -> InternalFailure`, with `InternalFailure` absorbing. OOM-then-malformed upgrades after contract/state checks only and without ledger mutation; malformed-then-OOM stays `InternalFailure`. Both orders prove `InternalFailure` before OOM before compressor result; defensive exception branches remain `P-PKG` source evidence. |
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
| JPEG and delivery IO views | Construct two distinct non-owned per-Session `Dispatchers.IO.limitedParallelism(1)` views, also separate from the existing metrics view. `H-OS`, `A-FJ`, `N-JPEG`, `H-DL`, and `A-CL` submit only non-suspending owner Runnables and prove one-at-a-time serial occurrence/submission/entry/return behavior on each view without asserting physical worker identity. This includes Framework creation holding a returned Bitmap or optional scratch in its exact occurrence bag. Terminal closes new admission but never shuts down, cancels, closes, quarantines, or awaits termination of a view. A nonreturn retains only the exact Session-owned operation/Runnable and its real carrier/writer/Bitmap/scratch or dispatch residue; the IO view and runtime worker remain non-owned. Late return is cleanup-only. A held creation, encode, recycle, or delivery occurrence does not prevent independent-root or cross-Session progress. There is no executor-termination receipt, private executor cleanup branch, or fabricated return. |
| scheduled one-worker executor | `H-OS`/`H-DL` disable successor authority, cancel every current Future outside gates, and settle caller-dispatch/`Submitting`/retained accepted-callback wake dependencies before normal `shutdown`; `terminated()` is the distinct scheduler receipt. Held/nonreturning termination retains only the scheduler/thread and exact queued/dequeued wake, `Submitting` record, retained callback-deadline link, or deadline record. Late termination reduces only that cleanup root; there is no scheduler watchdog or fabricated operation receipt. |
| Android HandlerThread/Handler | Projection/target Android ownership alone governs `quitSafely`; built-in metrics unregister is not a dependency. Hold, throw, or never return from unregister while completing eligible Android shutdown and thread-`finally`, and separately hold HandlerThread termination while metrics cleanup progresses. Neither condition fabricates the other's receipt. A Main-queued built-in callback entering after HandlerThread shutdown remains O(1) and fenced. |
| structured metrics lifecycle | Execute the canonical Section-4.2 positive parent/one-lazy-child/one-collection/one-receipt matrix, including observe-return cell ownership, provider-cancellation classification, handlers returning while engine gates are held, nonreturn residue, uniform Main-Looper routing, and Session HandlerThread independence. Final parent completion is the metrics-lifecycle receipt; a late return reduces only that exact root. |
| other non-owned Kotlin dispatch service | `H-LC`/`A-CL` prove that `Dispatchers.Default` is never shut down, cancelled, closed, or quarantined. Tests retain only Session-owned controller submission/drainer cells; the global dispatcher remains non-owned even when work is held. |
| cross-Session and root isolation | For every held owned resource or Session-owned operation on a non-owned view above, a second Session's corresponding logical work and all independent roots of the affected Session progress to their applicable real receipts. An unresolved built-in unregister retains only its linked metrics residue and does not delay Session HandlerThread quit/return; Android termination cannot fabricate or consume unregister evidence, and a held JPEG/delivery Runnable retains no dispatch-view ownership. Releasing the held barrier later changes no terminal State, fallback, Stats, or other Session. |

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
| `FrameworkResourceCreationOccurrence`, Framework encode, or preterminal Bitmap recycle | `FrameworkJpeg` |
| complete preterminal Native preparation, Native compression/JNI occurrence, incompatible-native-carrier free, or `NativeCarrierReplacementAllocationOccurrence` | `NativeJpeg` |
| `ManagedDirectCarrierReplacementAllocationOccurrence` | `FrameworkJpeg` |

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
| B2 metrics/API geometry/visibility and pre-Active invalidation | `A-API`, `A-CAP`, `A-CL`, `H-OB`: Section 4.2 is the complete metrics matrix; API 34–37 resize authority and visibility remain the Document-03/06 API cases. |
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
| B13 complete Surface-release slice | `A-CAP`, `A-GL`, `H-OS`, `A-CL` and Sections 4.2–4.3: mutually exclusive PreparedTarget cleanup or CurrentTarget release, exact applicable prerequisites, generation/currentness, deadlines/terminal transfer, ordered receipts, no uninstalled publication/work, and cross-Session isolation. |
| B14 checked bounds, OOM, stale fencing, quarantine | Owning rows `H-GM`, `H-RC`, `H-PS`, `H-OS`, `A-CAP`, `A-GL`, `A-FJ`, `N-JPEG`, and `A-CL`, plus Sections 4.1, 4.3, and 4.5. Exact denial allocates/retires nothing; ordinary real receipts and deterministic fault seams preserve classifications and exact stale residue without forced platform OOM, GC timing, predictive memory, or tuning facts. |
| B15 exact-compatible reuse and smallest replacement | `H-RC`, `H-OS`, `A-CL`: identity reuse; drained Native free then `NativeCarrierReplacementAllocationOccurrence`, or drained Managed logical detach then `ManagedDirectCarrierReplacementAllocationOccurrence`; fresh currentness, no preparation rerun, registry, rollback, or second topology. |
| B16 target replacement with compatible output owners | `H-RC`, `A-CAP`, `A-GL`: only an installed typed CurrentTarget reaches VirtualDisplay/producer work; target dependents change while compatible FBO/texture, carrier, Bitmap/scratch, and JPEG owners retain identity. |
| B17 Bitmap reuse and one-shot recycle | Owning rows `H-RC`, `H-OS`, `A-FJ`, and `A-CL`, plus Section 4.1. Distinctively, exact shape retains identity; incompatible replacement requires one normal recycle receipt and a fresh current/nonterminal recheck, and every acquired non-install Bitmap recycles exactly once. |
| B18 generic settlement, deadline, rejection, transfer, late receipt | Owning rows `H-LC`, `H-OS`, `H-DL`, `A-CAP`, `A-GL`, `A-FJ`, `N-JPEG`, and `A-CL`, plus Sections 4.1, 4.2, 4.6, and 6. Distinctively, transferred or late receipts may reduce only their exact residue and cannot revise the committed winner. |
| B19 carrier/native-health combinations and fault precedence | Owning rows `H-RC`, `H-NL`, `H-OS`, `H-PS`, `H-DL`, `H-OB`, `A-FJ`, `N-JPEG`, and `P-PKG`, plus Sections 4.1 and 4.4. Distinctively, exactly three backend/health combinations remain; one timely safe failure commits `byFailure` and `Disabled` once, the cutoff-first new-admission fixture admits no later delivery opportunity, entry-first delivery settles its existing record/lease, and complete Framework install is required before `Active`. |
| B20 diagnostics ordering, fields, and wall-clock semantics | `H-OB` plus Section 6 boundary suites: sequence attempts, fixed fields, semantic message, raw cause, overflow gaps, and noncontrol behavior. |
| B21 JNI layout, writer, ABI, page size, and isolation | `N-JPEG`, `N-PKG`, `P-PKG`: exact bootstrap/registered signatures, source-identical JNI-free hidden PIC runtime OBJECT, explicit non-owning runtime inputs, exception containment, same-function nested weak guard/re-resolution, native build option and `jnigraphics` linkage, weak undefined symbol/direct dependency, exactly two production exports, hidden operations, and 16-KiB alignment. Package evidence proves the production DSO for `armeabi-v7a`, `arm64-v8a`, `x86`, and `x86_64`; installed runtime evidence proves own load/bootstrap/registration without an ABI-string predicate. Every normal return initializes all five result words; exact writer codes `0/1/2`, unknown low/high patterns including Kotlin-negative `Long`, monotone status, and fault precedence are covered. SUCCESS-gated one-segment-per-callback adoption, ordered local-reference deletion/native free, typed capsule/residue, and DSO/state isolation remain required. |

## 8. Acceptance-scenario traceability

| Scenario | Tests | Scenario | Tests | Scenario | Tests |
| ---: | --- | ---: | --- | ---: | --- |
| 1 | `A-API`, `P-PKG`, `N-PKG` | 10 | `A-CAP` | 19 | `H-DL`, `A-SES` |
| 2 | `A-API`, `H-GM` | 11 | `H-GM`, `A-GL`, `A-FJ`, `N-JPEG` | 20 | `H-PS`, `H-DL`, `A-SES` |
| 3 | `H-LC`, `A-SES` | 12 | `H-GM`, `A-GL` | 21 | `H-DL`, `H-OS`, `A-CL` |
| 4 | `H-LC`, `A-SES`, `H-OB` | 13 | `H-PS` | 22 | `H-DL`, `A-SES` |
| 5 | `H-LC`, `A-SES`, `A-CL` | 14 | `H-PS` | 23 | `H-DL`, `H-OS`, `A-SES` |
| 6 | `H-LC`, `H-RC` | 15 | `H-PS`, `H-OB`, `H-OS` | 24 | `H-GM`, `H-RC`, `H-PS`, `H-OS`, `A-GL`, `A-FJ`, `N-JPEG`, `P-PKG` |
| 7 | `H-RC`, `H-OS`, `A-CAP` | 16 | `A-GL`, `A-FJ`, `N-JPEG`, `P-PKG` | 25 | `H-OS`, `H-RC`, `H-DL`, `A-CAP`, `A-GL`, `A-FJ`, `N-JPEG`, `A-CL` |
| 8 | `A-CAP`, `H-OB` | 17 | `H-RC`, `H-NL`, `A-CAP`, `A-GL`, `N-JPEG`, `N-PKG` | 26 | `H-OB`, `A-CAP`, `A-GL`, `A-FJ`, `N-JPEG` |
| 9 | `A-CAP`, `A-CL`, `H-OB`, `H-RC` | 18 | `H-OS`, `H-RC`, `H-NL`, `A-CAP`, `A-FJ`, `N-JPEG`, `A-CL` | 27 | `H-LC`, `H-RC`, `A-CAP`, `A-GL`, `A-CL` |

## 9. High-risk trace reproduction

| Paper trace | Executable reproduction |
| --- | --- |
| 10.1 concurrent start | `H-LC` and `A-SES` run both gate orders and verify zero access to the losing projection. |
| 10.2 latest desire versus stale rebuild | `H-RC`, `H-OS`, `A-GL`, and `A-CL` run safe/ambiguous stale, destructive A-to-B-to-A, PreparedTarget generation gaps, and exclusive install-or-cleanup with topology-identity barriers. |
| 10.3 geometry change during startup | `A-CAP` and `H-OS` race initial resize at `D - 1`, `D`, and after expiry against provisional-target retention/replacement. |
| 10.4 safe Native failure with healthy target | `N-JPEG`, `H-RC`, `H-OS`, `H-PS`, `H-DL`, `H-OB`, and `A-FJ` reproduce the owning rows. The distinctive trace asserts one safe-failure commit, unchanged target/Direct health, cleanup-only expired/retired results, and both delivery orders: entry-first settles the existing record/lease; the cutoff-first new-admission fixture admits no later delivery opportunity before complete Framework install. |
| 10.5 unsubscribe versus queued callback | `H-DL` runs entry-first, unsubscribe-first, in-call dispatch detach, deadline-first, terminal-before/after-convergence, waiter cancellation, and pre-`Closing` self-rejection; all non-rejected callers observe the one controller-committed shared result, and replacement exclusion clears only on `Success`. |
| 10.6 stop versus callback entry | `H-DL` and `H-LC` run both `sessionGate -> settlementGate` orders and the callback-return terminal cutoff. |
| 10.7 resource limit/allocation around retirement | `H-RC`, `H-OS`, `A-CAP`, `A-GL`, and `A-CL` use the owning rows to prove pre-denial zero work, one typed post-retirement allocation or PreparedTarget construction, exact cleanup, and no rollback topology. |

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
