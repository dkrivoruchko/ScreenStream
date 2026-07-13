# Screen Capture Engine — Implementation Design

## 1. Authority, scope, and implementation posture

This document binds the pre-Kotlin implementation structure for the V1 contract in
[01_design.md](01_design.md), the algorithms and ownership rules in
[02_architecture.md](02_architecture.md), and the acceptance obligations in
[03_verification.md](03_verification.md). [05_gate_b_inputs.md](05_gate_b_inputs.md) defines the
required Gate-B coverage. If this document is less specific than Documents 01–05, those documents
remain authoritative.

The implementation is one Android library module named `screencaptureengine`. Its public package is
`io.screenstream.engine`. All production-private Kotlin declarations are in the one flat package
`io.screenstream.engine.internal`; there are no internal owner subpackages. The implementation uses
the approved compact decomposition of exactly five public Kotlin files and fourteen internal
production Kotlin files. Small records, cells, receipts, adapters, and helpers stay private beside
their owner instead of creating utility files.

The design deliberately has one active topology, direct owner-to-owner transitions, bounded
latest-value cells, one production slot, and exact cleanup records. It has no resource registry,
rollback topology, replacement generation, predictive memory accounting, device allowlist,
performance-based runtime selection, or additional public/runtime axis.

The private deadline and GL-error-bound names are bound here only to their uses. Their numeric values
belong to [07_private_constants.md](07_private_constants.md). Test colors, semantic JPEG quality,
sample regions, and GL/JPEG tolerances belong to
[08_test_implementation_map.md](08_test_implementation_map.md). Neither set is inferred here.

## 2. Source layout and implementation manifest

All paths are repository-relative. The aliases below expand relative to the repository root exactly once:

| Root | Repository-relative expansion |
| --- | --- |
| `MODULE` | `screencaptureengine/` |
| `PUB` | `screencaptureengine/src/main/kotlin/io/screenstream/engine/` |
| `INT` | `screencaptureengine/src/main/kotlin/io/screenstream/engine/internal/` |
| `CPP` | `screencaptureengine/src/main/cpp/` |
| `HOST` | `screencaptureengine/src/test/kotlin/io/screenstream/engine/internal/` |
| `ANDROID_API` | `screencaptureengine/src/androidTest/kotlin/io/screenstream/engine/` |
| `ANDROID_INT` | `screencaptureengine/src/androidTest/kotlin/io/screenstream/engine/internal/` |
| `NATIVE_CPP` | `screencaptureengine/src/nativeTest/cpp/` |

Every manifest entry below is relative to its named root. A `ROOT:path` entry expands once against this table;
no entry is relative to another entry and no undeclared source root is implied.

### 2.1 Public Kotlin files

| Path | Declarations and owner role | Dependencies, contracts, and test slices |
| --- | --- | --- |
| `PUB:ScreenCaptureEngine.kt` | `ScreenCaptureEngine`, `ScreenCaptureSession`, `FrameSubscription`, and the identity-based Session/subscription facades. | Delegates only to `SessionController`; implements 01 §3.1–3.2 and §3.8. Lifecycle, wrong-state, cancellation, one-shot start, registration, and unsubscribe tests. |
| `PUB:ScreenCaptureConfig.kt` | `ScreenCaptureConfig`, `JpegBackendPolicy`, `CaptureMetricsProvider`, `CaptureMetrics`, and `CaptureMetricsProviders`. | Built-in factories delegate to `CaptureMetricsOwner`; implements 01 §3.3 and 02 §1.2. Validation, context/display association, API-specific metrics, and provider-failure tests. |
| `PUB:ScreenCaptureParameters.kt` | `ScreenCaptureParameters` and all caller-constructed region, crop, size, content, rotation, mirror, color, and frame-rate values. | Pure checked validation and manual value semantics; implements 01 §3.4 and §3.8 and 02 §5. Constructor, equality, hashing, formatting, geometry, and overflow tests. |
| `PUB:ScreenCaptureOutput.kt` | Engine-produced geometry/effective-parameter values and `EncodedImageFrame`. | Instances are constructed by the controller/delivery boundary and expose no backing buffer; implements 01 §3.5 and §3.8. Value-semantics, borrowed-lifetime, range-copy, and lease tests. |
| `PUB:ScreenCaptureObservations.kt` | State/running-state hierarchy, stop reasons, problems, exception, Stats/drop values, and diagnostic event. | Built and published through the controller-confined `ObservationOwner` adapter from controller-committed values; implements 01 §3.6–3.8 and §5. State snapshot, exception mapping, saturation, finite-value, and diagnostic-field tests. |

### 2.2 Flat internal Kotlin files

| Path | Single owner and material declarations | Allowed dependencies, contract links, and test slices |
| --- | --- | --- |
| `INT:SessionController.kt` | Sole Session lifecycle, desired/revision, current-topology/plan, reconciliation-occurrence, pacing/repeat-policy, counter, public-observation-value, and result authority; command cells; immutable facts; non-reentrant drainer. | May coordinate every owner through facts and typed commands but owns no platform/GL/JPEG call. `ReconciliationOwner`, `PacingOwner`, and `ObservationOwner` are controller-confined synchronous helpers, never peer authorities. Implements 02 §6.1–6.4. Lifecycle, terminal priority, command races, drainer losslessness, and production cutoff tests. |
| `INT:OperationSettlement.kt` | `OperationOccurrence<R>`, typed `OperationReturnCell<R>`, `DeadlineOccurrence`, `DeadlineWakeLink`, owner bag, operation identity, settlement gate, entry/disposition/domain, and generic receipts. | Used by every opaque/system/ownership-sensitive operation. Implements 02 §6.3 and §7.2. Deadline/wake boundary, rejection, retirement, late-return, lock-order, and allocation-free publication tests. |
| `INT:ReconciliationOwner.kt` | Controller-confined synchronous pure resolver for geometry, plan, compatibility, and smallest-scope transition calculations. | Receives one immutable controller snapshot and returns a calculation only; it owns no desired/revision cell, topology, reconciliation occurrence, command admission, or commit authority. Implements 02 §2 and §5. Latest-wins, A-to-B-to-A, stale key, capacity, reuse, and smallest-scope replacement tests. |
| `INT:AndroidCaptureOwner.kt` | Session Android `HandlerThread`/`Handler`, projection callback adapter, VirtualDisplay owner/operations, Android receipts, and ordered Android cleanup chain. | The only caller of `MediaProjection` and `VirtualDisplay` mutation APIs. Implements 02 §1.1, §2.3, and §7.3. Startup ordering, sole creation, callback authority, resize/attach/detach, failure mapping, and cleanup tests. |
| `INT:CaptureMetricsOwner.kt` | Provider collector owner, built-in metrics adapters, authoritative metrics accumulator, geometry facts, cancellation request, and collector-return receipt. | Uses Android display/window APIs and Flow collection; never mutates capture objects. Implements 02 §1.2 and §7.3. API-band authority, startup loss, storms, cancellation/nonreturn, and association tests. |
| `INT:TargetOwner.kt` | `CurrentTarget`, its one generation-owned `java.util.concurrent.atomic.AtomicBoolean` latest-pending source bit, Full/Downscaled plan, `SurfaceTexture`, `Surface`, target generation, listener adapter/sentinel, target leases, and Surface-release occurrence. | Android attachment is commanded through `AndroidCaptureOwner`; target GL work executes through `GlPipelineOwner` without transferring ownership. A current-generation listener may only set this target-owned bit; only the controller may consume it through the bounded atomic-exchange protocol in Section 9. Implements 02 §1.3, §2.3, §4.1, and §7.3. Planning, pending-bit races, generation fences, prerequisites, fallback, Surface races, and reuse tests. |
| `INT:GlPipelineOwner.kt` | Session EGL owner, serial GL lane, shaders/color classification, target-OES execution adapter, render target, Direct readback, GL receipts, and typed RGBA-carrier lease use. | Owns non-target EGL/GLES resources and all GL calls; borrows CurrentTarget-owned OES objects and carrier only through exact occurrences. Implements 02 §3.1 and §5. EGL, shader, transform, error partition, Direct readback, color, destruction, and image-vector tests. |
| `INT:JpegRuntimeOwner.kt` | Combined `JpegRuntimeOwner`, `NativeJpegHealth`, Kotlin `NativeMallocCarrier`/managed-direct carrier owners, native bridge state, guarded weak-compressor capability, native descriptor, `NativeWriterOwnerBlock`, `NativeResultBlock`, and carrier-free occurrence. | Selects only the closed carrier/backend combinations and invokes the private JNI bridge. It stores no raw function pointer or compressor owner. Implements 02 §3.2 and §7.1. Own-loader/bootstrap, weak eligibility, carrier/writer ownership, native result precedence, fallback, preterminal incompatible-carrier-free timeout/terminal conversion, and receipt tests. |
| `INT:FrameworkJpegOwner.kt` | Common Framework adapter, Bitmap owner, optional row scratch, transfer decision, Framework encoder occurrence, and one-shot recycle occurrence. | Accepts a leased exact-range view from either carrier and writes only to `EncodedStorageOwner`. Implements 02 §3.2 and §7.3. Fast/portable transfer, Bitmap outcomes, reuse, recycle, and Framework image tests. |
| `INT:EncodedStorageOwner.kt` | Checked segmented transaction/`OutputStream`, immutable managed segmented payload, latest/unpublished/displaced roles, frame metadata, and encoded leases. | Receives Framework writes or post-compressor native-segment copies; delivery borrows leases only. Implements 02 §3.2 and §4.2–4.3. Transaction, malformed writes, OOM, cache/repeat, displacement, and lease tests. |
| `INT:DeliveryOwner.kt` | Registration generation, subscription resolution, sole handoff/worker, borrowed frame, dispatch-return cell, trampoline-entry cell, callback-return cell, and accepted-task deadline link. | The sole caller of the configured dispatcher and application callback; leases payloads from storage. Implements 02 §4.4 and §6.3. Dispatch/entry/callback races, busy accounting, terminal cutoff, unsubscribe, and replacement tests. |
| `INT:PacingOwner.kt` | Controller-confined synchronous calculation adapter for fresh/repeat eligibility, rational cadence, next-wake selection, and monotonic clock reads. | Reads the target-owned pending bit, storage occupancy, and immutable controller policy/wake snapshot; returns a calculation only. The controller owns policy, grant phases, attempt/final-outcome occurrences, and current wake identities and commits every action. Implements 02 §4.1–4.2 and §6.6. Pacing/repeat, one-wake cardinality, churn, rejection, pending coexistence, and no-burst tests. |
| `INT:ObservationOwner.kt` | Controller-confined synchronous build/publication adapter for State, Stats, finite means/FPS, and exactly eight diagnostic category constructors. | Builds only from one controller-committed immutable snapshot and assigns/tries emission only outside gates; it owns no counter, result, diagnostic sequence, or public-value authority. Implements 02 §6.4–6.6. Ordering, cadence, saturation, finite protection, Flow configuration, and diagnostic semantics tests. |
| `INT:CleanupOwner.kt` | Cleanup forest, root records, dependency edges, `SessionQuarantineRoot`, cleanup-domain reducers, and logical/physical retirement commands. | Consumes owners transferred by the controller and sends late receipts back to the exact record. Implements 02 §7.3–7.4. Independent roots, blocked subchains, quarantine changes/reductions, and no-revival tests. |

These nineteen files are the complete production Kotlin manifest. No additional production Kotlin file or
package is implied by later test decomposition.

### 2.3 Native, build, packaging, and test-support boundaries

| Path | Owner, declarations, and role | Dependencies and contracts | Assigned test slice |
| --- | --- | --- | --- |
| `CPP:CMakeLists.txt` | Build owner for the one shipped JNI library, its module-local system `jnigraphics` link, target/object composition, hidden visibility, unguarded-availability diagnostics as errors, and one hidden PIC native-runtime OBJECT target compiled once. | NDK/platform libraries, the Gradle-supplied weak-API toolchain configuration, and the ABI/page/build contract in 05 §§1, 4.5, and 7. | Configure/build, weak-definition flag consumption, direct `DT_NEEDED`, object reuse, exported-symbol, ABI, ELF-alignment, and package-content checks. |
| `CPP:screen_capture_engine_jni.cpp` | Production JNI glue owner: version-only `JNI_OnLoad`, the exact exported instance bootstrap, one `RegisterNatives` table, the same-function guarded weak helper/capability entry, registered runtime entries, direct writer/result-block validation, real writer services, JNI adoption adapter, narrowing, and exception classification. It contains no fault hook or writable compressor state. | Private methods in `JpegRuntimeOwner` plus the JNI-free native runtime API; JNI/descriptor rules in 02 §3.2 and 05 §4.5. | Production own-load/bootstrap/registration, weak guard/address, real-service, block validation, adoption, exception-precedence, and black-box executing-ABI tests. |
| `CPP:native_jpeg_runtime.h` | JNI-free native JPEG declarations: non-owning compressor-function type, immutable per-call runtime services, adoption callback/context, descriptor, typed `WriterCapsule`, native-only writer segments, and evidence/receipts. | Included by the runtime implementation and both production/test JNI glue units; exposes no JNI type, exported C++ API, compressor owner, or managed-block alias of a native struct. | Compile-time layout, non-owning function/service/adopter contract, and writer-capsule owner-state probes. |
| `CPP:native_jpeg_runtime.cpp` | JNI-free native JPEG mechanics: invocation through the supplied non-owning compressor function, writer-capsule allocation/token publication, callback segments, post-compressor adoption calls, explicit segment frees, and evidence. | Uses only the non-owning function and immutable services/adopter supplied per call; compiled once into the hidden PIC OBJECT target. Implements 02 §3.2 and 05 §4.5. | Source-identical weak-call mechanics, writer fault/OOM, combined-fault, segment-release, and timeout/late-return tests. |
| `MODULE:src/main/AndroidManifest.xml` | Packaging owner: empty library manifest with no component, permission, service, provider, or runtime feature. | Merged by the Android library plugin. | Manifest-merge inspection. |
| `MODULE:consumer-rules.pro` | Shrinker boundary retaining only the exact bootstrap/registered bridge binary names and methods plus the native adoption sink binary name/method. | Consumed by release AAR users; no diagnostic suppression or wholesale package keep. | Minified bootstrap/registered-JNI smoke and rule-content inspection. |
| `MODULE:build.gradle.kts` | Future module-local integration point for the engine's Android/CMake source sets, ABIs, release publication, explicit API, JVM target, and verification tasks. It passes the exact CMake configure argument `-DANDROID_WEAK_API_DEFS=ON` through `externalNativeBuild` for every production/nativeTest native configuration that uses the shared production target. Exact repository/root Gradle wiring is outside Gate B. | The approved external build baseline and `CPP:CMakeLists.txt`; no root plugin, classpath, version-catalog, or wrapper binding is asserted here. | Once implementation is authorized: weak-API configure-argument coverage, variant/source-set isolation, API dump/explicit API, publication, package, and module verification-task checks. |
| `HOST:` | Host support owner for deterministic controller, clock, scheduler, fake owners, arithmetic, storage, Stats, diagnostics, and race/fault harnesses. | Production internal package; no Android opaque operation is treated as verified by a host fake. | Unit and deterministic race/fault slices assigned in Document 08. |
| `ANDROID_API:` | Public instrumentation support and API entry points. | Installed library/public package and AndroidJUnit runner. | Public lifecycle, borrowed-frame, observation, and exception contract slices. |
| `ANDROID_INT:` | Platform and native instrumentation support for metrics/capture seams, EGL/GLES, SurfaceTexture, Bitmap, Framework/native JPEG, packaging, programmatic vectors, and `NativeTestHarness.kt`. | The one `androidTest` Kotlin tree runs against build type `nativeTest`; native-specific tests may call the test-only JNI bridge without creating a second Kotlin source root. | Platform, image, cleanup receipt, own-loader/weak-guard/writer/compressor-call, native OOM/fault, late-return, and ABI/package slices assigned in Document 08. |
| `NATIVE_CPP:CMakeLists.txt` and `NATIVE_CPP:screen_capture_engine_native_test_jni.cpp` | Test-DSO build and JNI glue owner: test-local services, fake compressor/adopter, barriers, and probes around the same native-runtime OBJECT code. | Linked only into the `nativeTest` instrumentation variant; it owns no production token/state and does not resolve a hidden production symbol. | Deterministic service/adoption faults, layout, callback-thread independence, object-code parity, ELF/package isolation, and receipt probes. |

Exact test classes, runners, generated inputs, vector values, and task-to-slice assignments are fixed by
Document 08. Programmatic image vectors are generated in test code; production contains no test image asset.

## 3. Runtime topology, lanes, and dependency direction

Each Session constructs these execution resources after accepted start and roots them immediately:

- one `HandlerThread` and `Handler` for all MediaProjection, VirtualDisplay, projection-callback, and
  target-listener Android work;
- one Session-private single-thread executor for EGL/GL, including target GL work and Surface release;
- one non-owned per-Session `Dispatchers.IO.limitedParallelism(1)` view for non-suspending Native preparation,
  Framework/native JPEG, Bitmap recycle, and `NativeMallocCarrier`-free owner Runnables;
- a distinct non-owned per-Session `Dispatchers.IO.limitedParallelism(1)` view for the one non-suspending
  delivery-owner Runnable at a time;
- one Session-private scheduled executor for deadline and wake callbacks;
- one standalone retained metrics collector `Job` on a non-owned per-Session
  `Dispatchers.IO.limitedParallelism(1)` view, with no parent supervisor receipt;
- the controller drainer submitted directly to `Dispatchers.Default` through its lossless atomic wake state.

Every owned executor/thread creation is an owned startup operation. Rejection or partial construction is
classified through the ordinary startup and ownership rules. The JPEG, delivery, and metrics views are three
distinct per-Session serialization views over the non-owned IO dispatcher; they are never closed, cancelled, or
quarantined. Their serial admission and occurrence authority are independent across lanes and Sessions, while a
nonreturning Runnable may still occupy one shared IO worker. Android, GL, and scheduler resources remain
Session-private and physically owned.

The controller never performs platform, GL, JPEG, allocation, release, callback, Flow collection, or other
potentially blocking work. An owner receives immutable commands, settles an exact occurrence, publishes an
immutable fact, and signals the controller. The controller records the logical transition and emits follow-on
commands only after releasing gates. Application code is reached only by `DeliveryOwner` through the configured
dispatcher.

Execution-resource ownership and retirement are exact:

| Resource | Owner and active lifetime | Shutdown request and dependency | Mechanical completion evidence and unresolved residue |
| --- | --- | --- | --- |
| Android `HandlerThread` and `Handler` | `AndroidCaptureOwner`; created during accepted startup and owns projection callbacks, VirtualDisplay mutations, target-listener callbacks, and callback delivery for built-in exact-display listeners. Metrics registration, reads, and unregister never run on this lane. | After terminal fences metrics authority and closes its signal, projection callback unregister, VirtualDisplay release, projection stop, target cleanup, and all other Android-owned work settle independently; then `quitSafely` may proceed without waiting for DisplayListener unregister. No other owner may quit the thread. | The thread's `finally` publishes a precreated lane-return cell. Unresolved metrics unregister separately retains its listener, DisplayManager, and Handler reference but no lease on the Android thread. A failed or late listener callback has no authority, and no Handler return fabricates an unregister receipt. |
| GL one-worker `ThreadPoolExecutor` | `GlPipelineOwner`; Session-private from EGL construction through target/non-target GL teardown and Surface-release execution. | After the Surface occurrence and every dependent GL/EGL operation settle, call `shutdown`; never use `shutdownNow` to invent cancellation of entered work. | A private `terminated()` hook publishes the executor-return receipt. Nontermination retains the executor/thread, queued exact occurrences, and dependent target/EGL owners under the GL root without blocking other roots or Sessions. |
| JPEG IO view | `JpegRuntimeOwner` retains one distinct non-owned per-Session `Dispatchers.IO.limitedParallelism(1)` view and submits only non-suspending owner Runnables for Native preparation, Framework/native encode, Bitmap recycle, and `NativeMallocCarrier` free. | Each submission preserves its precreated operation, submission, entry, return, deadline, and owner cells. Terminal closes admission and settles or transfers exact occurrences; it never closes, cancels, or quarantines the view and has no executor-shutdown step. | There is no termination receipt. A nonreturn retains the exact Session-owned Runnable occurrence and its attached carrier/Bitmap/transaction/writer residue, but not the shared dispatcher or view. Weak compressor capability adds no resource dependency. |
| Delivery IO view | `DeliveryOwner` retains a second distinct non-owned per-Session `Dispatchers.IO.limitedParallelism(1)` view and submits only its non-suspending owner Runnable, preserving the sole worker-capacity rule. | Each submission preserves its separate operation, submission, entry, dispatch-return, trampoline-entry, callback-return, and owner cells. Terminal closes admission and settles or transfers exact handoff sides; it never closes, cancels, or quarantines the view and has no executor-shutdown step. | There is no termination receipt. A nonreturn retains the exact Session-owned Runnable occurrence and unresolved dispatch residue, but not the shared dispatcher or view; a lease already released by callback return remains released. |
| Scheduled one-worker `ScheduledThreadPoolExecutor` | `SessionController` owns the executor and identity-fenced deadline, pacing/repeat, and separate Stats-wake submissions; `PacingOwner` only calculates synchronous next-wake actions. It is configured with remove-on-cancel enabled and both continue-existing-periodic and execute-existing-delayed-after-shutdown policies disabled; no periodic task is used. | Cancel outside gates every current Future, disable successor authority, and wait for caller dispatch still in call, every `Submitting` outcome, and every retained/later-armable accepted-callback wake before `shutdown`. An already-dequeued callback may publish only its generation-tagged fact. | `terminated()` publishes the scheduler receipt. Nontermination retains the scheduler/thread and only the exact queued/dequeued wake, `Submitting` record, retained callback-deadline link, or deadline record. Scheduler progress and post-terminal completion have no watchdog. |
| Standalone metrics collector `Job` and IO view | `CaptureMetricsOwner`; exactly one retained collector Job owns the provider, returned Flow, and collection from accepted attachment through its mechanical return. For built-ins the same per-Session `Dispatchers.IO.limitedParallelism(1)` view serializes registration, all platform reads, emission, and unregister; blocking metrics work cannot occupy the Android lane. The view is structural serialization, not a tunable pool size. | Request cancellation on that Job at terminal; built-in cleanup closes its signal and performs exact unregister on this IO view. Never close or quarantine the non-owned IO view. Cancellation intent is never a return receipt, and there is no parent scope or supervisor completion to await. | Registration, unregister, the collector Job's single `finally`-published completion cell, and Android Handler return are distinct evidence. That completion cell is the sole mechanical collector-return receipt. Read/unregister nonreturn retains the exact metrics Job and adapter dependencies, but not the IO dispatcher or caller-owned upstream threads, under the metrics root. |
| `Dispatchers.Default` | Non-owned Kotlin runtime service used only for controller-drainer dispatch. | Never close, cancel, or quarantine the dispatcher itself. Session records own only controller submissions and their cells. | A Session controller-submission return/rejection and drainer return are the evidence. The global dispatcher never enters `SessionQuarantineRoot`. |

All owned-resource shutdown calls and completion publication occur outside controller/settlement gates. A
returned shutdown error is recorded against that resource and independent roots continue. Cleanup never waits by
blocking an owner lane; completion arrives through the precreated cell. JPEG and delivery have occurrence return
cells but no view-shutdown or view-return cell.

The scheduler construction is exactly `ScheduledThreadPoolExecutor(1)` followed before publication by
`setRemoveOnCancelPolicy(true)`, `setExecuteExistingDelayedTasksAfterShutdownPolicy(false)`, and
`setContinueExistingPeriodicTasksAfterShutdownPolicy(false)`. The structural worker count is not configurable;
all pacing, Stats, and deadline records remain independently identity-fenced.

## 4. Controller, commands, and public linearization

### 4.1 Session gate and lossless drainer

`SessionController` owns nonfair `sessionGate` and is the sole writer of lifecycle, desired/revisions, current
topology/plan, reconciliation occurrence, pacing/repeat policy and grant state, counters, public observation
values, and public/terminal results. `ReconciliationOwner`, `PacingOwner`, and `ObservationOwner` are invoked only
by the controller with immutable snapshots, return or publish synchronously, and retain no competing authority.
Blocking and opaque owners retain their physical resources and publish immutable facts; only the controller
accepts those facts into authoritative Session state. Public commands make bounded changes; owner returns publish cells
without the gate; and the drainer calls neither public entry nor outward owner while holding it, preventing reentrance.
All return/latest/command cells publish before signalling. The 02 §6.3 atomic state is exactly `IDLE`, `RUNNING`,
or `RUNNING_DIRTY`; only the idle winner submits. Each bounded scan applies complete cells into precreated fixed
action slots. If the batch saturates, the drainer itself unconditionally forces/stays `RUNNING_DIRTY`, executes
the claimed batch, returns to `RUNNING`, and rescans without requiring a producer wake;
it repeats until a full scan finds no completed fact. Only that empty full scan may attempt `RUNNING -> IDLE`.
Gated code writes only existing fields/bits; construction, diagnostics, scheduling, outward calls, and release
execute from claimed slots after unlock.

Active `Dispatchers.Default.dispatch` throw publishes the precreated emergency cell, then its observing thread
runs one atomically exclusive fail-closed bounded turn that can commit terminal without capture/encode/cleanup.
Post-terminal rejection only follows Section 13 residue rules. Authoritative facts remain in cells and never
depend solely on the rejected signal.

### 4.2 Public commands

- `create` performs bounded config validation, constructs the controller's all-zero observation cells and
  publication adapter, the empty quarantine root, and the one-shot Session facade. It retains the application context except for the documented UI metrics
  association.
- `start` validates input parameters before attempting acceptance. Under `sessionGate`, exactly one call changes
  `NotStarted` to `Starting`, assigns the first nonzero desired revision, and transfers only its projection.
  Losers throw without inspecting or retaining their projections. The accepted caller waits on a Session-owned
  completion that is not cancelled by waiter cancellation. Cancellation after acceptance submits owner stop;
  cancellation before acceptance transfers nothing.
- `updateParameters` performs shared local validation before `sessionGate`. In a legal Running state, structural
  equality returns without revision change. The call precreates its fixed update/publication cell before the
  gate. For an unequal value, the gate writes the new desired reference, next nonreused revision, and one complete
  coherent Running-snapshot cell containing that requested value plus the same-turn current effective/running/
  visibility references, then publishes those fields atomically as the latest-publication cell. It signals the
  sole drainer/reconciliation path outside the gate; the public setter never assigns StateFlow itself. The serial
  drainer claims only the latest complete cell, constructs and assigns that whole Running value outside gates,
  and marks it consumed before another turn may publish. An unclaimed overwritten update is superseded only as
  one whole snapshot, never mixed with another effective/running/visibility set.
  Exhaustion commits the specified terminal failure in that same critical section.
- `onFrame` uses `sessionGate` to require a nonterminal allowed lifecycle and no current unresolved registration.
  It installs one new nonreused generation and, if current cached bytes exist, atomically reserves the same sole
  delivery slot and lease used by normal delivery. It never encodes or copies cached bytes.
- `stop` uses `sessionGate` to commit the idempotent owner-stop fact and close start, desired, reconciliation,
  production, repeat, and delivery admission before returning. Observation and physical cleanup remain
  asynchronous.

No public synchronous method waits for an owner lane. `start` and `unsubscribe` suspend without blocking their
caller thread.

The controller selects the first terminal fact permanently. Within one turn it applies CaptureEnded, then
OwnerStop, then Failed priority. It completes accepted `start` only after direct Running assignment; pre-Running
Stopped maps to capture-unavailable start failure, Failed preserves its problem/cause, and caller cancellation
retains cancellation semantics exactly as in 01 §3.2.

## 5. Generic operation settlement and deadlines

### 5.1 Record shape

Every opaque, system, ownership-sensitive, or mandatory-release call uses one
`OperationOccurrence<R>`. The record owns:

- a nonreused operation identity and active/cleanup domain;
- a one-shot entered latch and scheduler-submission disposition;
- a nonfair occurrence-local `ReentrantLock` named `settlementGate`;
- a precreated typed `OperationReturnCell<R>` containing a discriminator and fixed scalar/reference fields for
  normal value, throwable, typed receipt, and already-existing returned owner;
- an owner bag containing every carrier or release obligation that travels with unresolved work;
- when designated finite, one identity-fenced `DeadlineOccurrence` with checked deadline and disposition.

Return/emergency cells are preallocated. Immediately before outward entry, the worker takes only
`settlementGate`, verifies current unresolved domain, commits Entered plus checked `S`/`D`/deadline identity,
unlocks, signals, and invokes once on the same stack without controller round trip or recheck. A terminal/expiry
winner in the post-commit pre-invocation interval cannot cancel that call. On return the worker fills fixed fields,
samples finite `T`, and publishes the complete cell under the same gate before signalling; it never takes
`sessionGate`. Queued work has no queue-start timer.

### 5.2 Deadline wake link

Every finite deadline owns one minimal `DeadlineWakeLink` inside its parent occurrence or delivery record; the
link has no independent active/cleanup domain and always follows that parent. Its fixed state is:

- phase exactly `Unarmed`, `Armed`, `Expired`, or `Retired`, plus the deadline identity and checked `S`/`D`;
- one precreated generation-tagged wake record whose submission is exactly `None`, `Requested`, `Submitting`,
  `Accepted`, or `Rejected`, with fixed Future and rejection fields;
- one independent generation-tagged `Fired` cell with its clock sample, so a callback may publish before the
  scheduling call publishes its returned Future; and
- the precreated family timeout-cause reference used unchanged by arbitration and diagnostics.

Arming publishes under the parent gate; the controller folds the primary fact first, then current Armed
`None -> Requested`. The claimed action verifies generation and commits `Submitting` under that gate, calls
`schedule(wake, max(0, D - EngineClock.now()), NANOSECONDS)` unlocked, then publishes exact Future or rejection.
Scheduling never moves/delays the same-stack outward call. The callback verifies generation under the parent gate,
samples time, publishes only its `Fired` cell, unlocks, and signals; `Fired` may precede Future publication. Early
fire is evidence only, and a successor waits for both Fired and Future sides, preventing orphan Futures. At/after-
deadline fire requests arbitration; timely primary fact still wins. Future cancellation/removal is unlocked;
stale callback settles only its generation.

Active current scheduling rejection is engine-scheduler `InternalFailure`, never expiry/readiness/dispatcher/drop;
after transfer it is cleanup-only. Terminal retires links and cancels accepted Futures unlocked, except an
unentered callback handoff transfers its Unarmed/Armed link intact. Later normal dispatch return may arm it from
that sample; accepted queued work retains its wake until entry/detached rejection. These wakes are cleanup-only.
Scheduler receipt therefore waits for in-call dispatch, every `Submitting`, and retained callback wakes.

### 5.3 Arbitration and active-to-cleanup cutoff

The sole nested order is `sessionGate -> one settlementGate`. There the controller may inspect identity/state,
sample only `EngineClock`, apply a complete cell, claim expiry, reject safe unentered submission, or transfer the
intact occurrence to cleanup. No allocation, Flow publication, scheduling, outward call, or release occurs there.
Only complete publication sampled before deadline is timely; at/after deadline, empty-at-deadline, or retired
publication is cleanup-only. Timer order is irrelevant, and a worker sampling under the gate fixes linearization.

Terminal folds already-complete active cells, then transfers unresolved gate/cell/owner bag/deadline/worker as one
record. This permanent cutoff lets late facts reduce only Section 13 residue, never active counters, delivery,
State, cache, health, fallback, or reconciliation. Logical transfer is gated; physical work uses the unlocked
action list, including apparently immediate detach/free.

Synchronous engine-scheduler rejection may settle only a current safely unentered submission whose entry and
return cells are empty. It never erases a mandatory cleanup obligation. Caller-dispatch return/throw is not an
engine-scheduler rejection and follows the separate delivery cells in Section 11.

The seven private names and their numeric literals are owned exclusively by
[07_private_constants.md](07_private_constants.md). This document binds only their use sites:

| Private name | Occurrence span and disposition |
| --- | --- |
| `firstMetricsReadinessNanos` | From accepted provider attachment through `observe()`, collection entry, and first valid combined tuple. A timely tuple wins; missing/late readiness is startup `CaptureUnavailable`; later collection remains long-lived and late evidence is cleanup-only. |
| `initialCapturedResizeReadinessNanos` | API 34–37 only, from complete normal nonnull VirtualDisplay-owner publication to the first authoritative resize. The Android worker fixes `S`/`D` with that publication; an earlier resize retains its own `T`. Timely resize wins; missing/late resize is startup `CaptureUnavailable`. |
| `androidEnteredOperationSafetyNanos` | One deadline per nonterminal projection registration, VirtualDisplay create/resize/setSurface, preterminal target-listener installation/removal call, or preterminal Surface release logical call. Timely typed return applies; expiry/late return selects existing `InternalFailure`, and a late normal release/removal is cleanup receipt only. Post-terminal listener removal and Surface release use the same occurrences without watchdogs. |
| `glEnteredOperationSafetyNanos` | One deadline per finite EGL/GL construction-validation, materialized-frame GL, or preterminal incompatible-scope destruction occurrence. Expiry/late result is unsafe `InternalFailure`; late pixels/receipts cannot authorize fallback or reuse. |
| `jpegEnteredOperationSafetyNanos` | One deadline for the complete preterminal Native preparation operation, one per complete Framework encode transaction or Native encode, one per preterminal Bitmap recycle, and one per preterminal incompatible-`NativeMallocCarrier` free occurrence. Expiry/late result is `InternalFailure`; late preparation cannot select carrier/backend or publish owners, late bytes cannot publish or alter health, and a late normal carrier-free return is cleanup-only. Terminal-origin or terminal-converted carrier free uses the same occurrence without a watchdog. |
| `maxOldGlErrorsPerFrame`, `maxFinalGlErrorsPerFrame` | Bound the respective preserved nonzero-error drain followed by one sentinel probe. A nonzero sentinel proves uncleared state and uses existing unsafe GL `InternalFailure`. |

Every deadline uses checked `D = Math.addExact(S, C)` after requiring positive `C`, nonnegative `S`, and a
representable sum. The fact/return worker samples `T` immediately before complete publication while holding the
occurrence gate. Strictly `T < D` is timely; `T >= D` is late. Timer order has no authority.
For both readiness occurrences, callback arrival, signalling, controller-drainer observation, and timer execution
order are non-authoritative; only the atomic `S`/`D` points above and each fact's stored `T` decide timeliness.

The complete terminal/no-watchdog exclusions are: long-lived metrics collection after first readiness;
caller-dispatch duration; entered application callbacks; blocking or Unconfined Flow collectors; engine
scheduler progress; public Flow/diagnostic publication; every post-terminal cleanup call/completion; and terminal
or terminal-converted listener removal, Surface release, Bitmap recycle, and `NativeMallocCarrier` free. Queued
engine work has no queue-start deadline.
Terminal-before-entry converts the same occurrence to no-watchdog cleanup; terminal-after-entry retires its active
deadline and transfers the unresolved record intact. The public callback-entry and Stats-cadence values remain
exactly those in Documents 01–02.

## 6. Reconciliation, allocation decisions, and owner reuse

`SessionController` owns the one latest desired/revision cell, latest combined geometry cell, current topology and
plan, capture availability, lifecycle epoch, target/native health facts, and the sole reconciliation occurrence.
For that occurrence it passes one immutable snapshot to the controller-confined `ReconciliationOwner`; the
helper synchronously returns a pure resolution/compatibility/transition calculation. The exact frozen key plus a
private nonreused occurrence identity remains controller-owned. Only the controller validates currentness,
commits a decision, and emits owner commands after unlocking; the helper never stores authority or redirects
entered work.

The pure resolver performs checked geometry and target planning exactly in 02 §§1.3 and 5, using checked `Long`
intermediates and explicit narrowing. It then inspects actual owner identity, health, generation, leases, and
shape compatibility. A historical effective value is not topology evidence.

After the initial valid EGL bind, `GlPipelineOwner` queries and retains one immutable Session capability fact:
positive `GL_MAX_TEXTURE_SIZE`, positive width and height components of `GL_MAX_VIEWPORT_DIMS`, and the fragment-
precision selection from Section 8. Let `maxW = min(GL_MAX_TEXTURE_SIZE, GL_MAX_VIEWPORT_DIMS[0])` and
`maxH = min(GL_MAX_TEXTURE_SIZE, GL_MAX_VIEWPORT_DIMS[1])`. Before any irreversible retirement,
reconciliation requires both `Tw <= maxW` and `Th <= maxH`, and both `Ow <= maxW` and `Oh <= maxH`. A plan that
deterministically fails this preflight allocates no doomed target or output resource and takes the existing
startup `ResourceExhausted` or Running pre-retirement `Suspended(ResourceExhausted)` path. It creates no
Downscaled compatibility fallback or runtime-selection axis.

The transition order is:

1. keep the old healthy output active before the irreversible boundary when allowed;
2. close only affected admission and drain entered affected work;
3. fence the old work identity and invalidate cache/repeat for image-affecting changes;
4. retain every healthy exact-compatible target, render target, carrier, Bitmap/scratch, and JPEG owner;
5. logically retire only the smallest incompatible scope, then physically settle its releases;
6. allocate the required replacement only after the required retirement receipts;
7. commit Active only for a still-current key and complete healthy live topology.

No normal replacement owns two healthy pipelines. Target replacement retires target physical dependents but does
not by itself retire shape-compatible output texture/FBO, carrier, Bitmap/scratch, or JPEG owners. Same-shape
reuse preserves object identity.

All feasibility decisions use checked representation, documented API/backend limits, and actual allocation or
creation results. The implementation does not query available-memory headroom or estimate opaque GPU/gralloc
storage. The startup, pre-retirement suspension, post-retirement terminal, safe-stale, and ambiguity partitions
are applied exactly as specified. Every allocation transfers immediately into a typed partial-construction owner.

## 7. Android capture, metrics, and target

### 7.1 Android lane

`AndroidCaptureOwner` is the only owner allowed to register/unregister `MediaProjection.Callback`, create/resize/
retarget/release the VirtualDisplay, and stop MediaProjection. Callback bodies contain the captured Session epoch
and post immutable facts on the same explicit Handler; they never mutate controller or GL state.

Startup uses the exact order in 02 §1.1. On APIs 24–33, the sole `createVirtualDisplay` call uses the positive
provider-authoritative logical width, height, and density. On APIs 34–37, it uses the positive provider width,
height, and density provisionally; no frame is admitted until the first timely valid projection resize supplies
authoritative width/height, while provider density remains authoritative. The call also uses automatic mirroring,
a nonnull Surface, and null VirtualDisplay callback/Handler arguments. `MediaProjection.Callback.onStop` is the
only platform `CaptureEnded` authority. Explicit VirtualDisplay release is cleanup evidence only.

The Android/target binding is exact:

| Boundary and lane | Exact calls/arguments | Result, order, and late rule |
| --- | --- | --- |
| projection callback, Android Handler | `projection.registerCallback(callback, androidHandler)` once before VirtualDisplay creation; cleanup calls `projection.unregisterCallback(callback)` once. The adapter implements resize, visibility, and `onStop` only for its captured Session epoch. | Normal registration return is required startup receipt. A timely throw uses the Android operation classification. Unregister normal return is the callback-release receipt; late return is cleanup-only. |
| VirtualDisplay creation, Android Handler | `projection.createVirtualDisplay("ScreenCaptureEngine", W, H, densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null)` exactly once. `W/H/densityDpi` use the API-band authority above. | Nonnull normal return publishes the sole display owner. Null or `SecurityException` is `CaptureUnavailable`; direct `OutOfMemoryError` is `ResourceExhausted`; `IllegalStateException` or any other throwable is `InternalFailure`. A late nonnull owner is cleanup-only and must still be released. |
| VirtualDisplay mutation, Android Handler | Density/size reconciliation calls `virtualDisplay.resize(W, H, densityDpi)`; target attach calls `virtualDisplay.setSurface(surface)`; detach calls `virtualDisplay.setSurface(null)`. Terminal cleanup calls `virtualDisplay.release()`, then after its receipt calls `projection.stop()`. | Each call owns its typed normal/throw cell. Only timely current normal resize/attach/detach may authorize reconciliation. Normal detach or applicable display-release return is the producer-detach receipt; late normal returns advance cleanup only. Projection stop has no authority to fabricate `CaptureEnded`. |
| target construction, GL lane then Android Handler | With the exact context current and the Section-6 per-axis texture/viewport preflight satisfied, create OES, call `SurfaceTexture(oes, false)`, `setDefaultBufferSize(Tw, Th)`, and `Surface(surfaceTexture)`. Before producer attach, the Android Handler enters one `androidEnteredOperationSafetyNanos` occurrence and calls `setOnFrameAvailableListener(listener, androidHandler)`. | Every returned object immediately enters `CurrentTarget`; partial construction keeps exact reverse cleanup. A direct `Surface.OutOfResourcesException` from either `SurfaceTexture` or `Surface` construction is an actual mandatory allocation `ResourceExhausted` under the existing startup/after-retirement rules. Any other unexpected constructor failure is `InternalFailure`. Only timely normal listener-install return permits attach; throw/expiry is existing `InternalFailure`, and late return is cleanup-only. A current-generation listener callback performs `pendingSource.set(true)` and then uses the lossless controller-drainer signal; it has no consume or materialization authority. |
| target acquisition, GL lane | For one materialized current frame call `updateTexImage()`, then `getTransformMatrix(precreatedFloat16)` using the one precreated 16-element array, and on API 33+ `getDataSpace()`. | All three calls remain inside the one GL frame occurrence. No callback timestamp controls pacing; a throw/error/late result follows the existing GL partition and publishes no pixels. |
| target retirement, Android Handler then GL lane | Handler enters one listener-removal occurrence, calls `setOnFrameAvailableListener(null, androidHandler)`, and after normal return posts the same-Handler sentinel. It uses `androidEnteredOperationSafetyNanos` only preterminal; post-terminal/converted cleanup has no watchdog. After sentinel, detach/release receipt, closed admission/drained work, and zero leases, GL calls `surface.release()`. Only its normal receipt permits `surfaceTexture.release()`, then OES unbind/delete. | Each normal return is distinct. Timely preterminal removal may advance retirement; expiry/late return is existing `InternalFailure` with late normal receipt cleanup-only. Throw/nonreturn preserves residue. No later receipt substitutes for listener removal, Surface, or SurfaceTexture receipt. |

Every Android outward call has a generic typed occurrence. Section 13 owns the cleanup root; locally, the fixed
subchain is callback unregister -> VirtualDisplay release -> projection stop. Throw records failure and advances;
nonreturn blocks only its suffix.

### 7.2 Metrics

Accepted start first creates the metrics readiness occurrence, observe-return cell, collection-entry cell,
first-tuple cell, collector-return cell, owner bag, and exactly one standalone retained collector `Job`. Under
`sessionGate -> settlementGate` it attaches the provider and atomically fixes first-readiness `S` and `D` with
accepted attachment ownership before any metrics fact publication; the same gated commit samples `S` and
calculates checked `D`. Only then does exactly one collector coroutine on the Session's non-owned
`Dispatchers.IO.limitedParallelism(1)` view call `CaptureMetricsProvider.observe()` once and, if still allowed,
`collect` the returned Flow. The structural parallelism value means one engine collector, not a configuration or
tunable runtime pool. The retained Job has no supervisor parent or separate scope receipt.

A normal late getter return first publishes the returned Flow into its precreated cell, then rechecks the exact
metrics occurrence/domain under `settlementGate`. If terminal or active-to-cleanup transfer already won, it never
starts `collect`; the same coroutine exits through its `finally`, and the Flow/provider/job settle only through
that cleanup record. Otherwise the same sole job
commits collection entry and collects. A caller-supplied `flowOn` or private upstream thread remains caller/Flow
owned; the engine owns only its collector job and references, and never closes or quarantines that executor.
Collection `finally` is the sole mechanical collector-return receipt. Cancellation is intent only. A valid first
tuple satisfies readiness but does not release the Flow/provider/job.

Each built-in owns one local `Channel<Unit>(Channel.CONFLATED)`. On the metrics IO view it registers before
seeding the first signal, then for each received token performs one fresh complete platform read and emits one
immutable result. A callback during a read leaves one later token; storms remain one token. Callbacks only
`trySend(Unit)` and never read metrics or touch owner/controller state; closed-signal failure is cleanup-only.
There is no adapter mutex, reread state machine, Handler post, or metrics sentinel.

Except for API-30+ `fromUiContext`, every built-in uses one exact-display `DisplayListener`: on the IO view it
calls `registerDisplayListener(listener, androidHandler)`; the Handler is callback-delivery only. The listener
captures selected ID, signal, and immutable token, filters that ID, and signals. Cleanup fences authority, closes
the signal, and calls exact-identity `unregisterDisplayListener(listener)` on the IO view. Unregister normal return
is still its sole receipt; throw or nonreturn retains the listener, DisplayManager, and Handler reference in the
metrics residue and never fabricates that receipt.

| Factory | Retained association | API 24–29 fresh read | API 30–37 fresh read |
| --- | --- | --- | --- |
| null config | Application Context; each read reselects `Display.DEFAULT_DISPLAY` through `DisplayManager` and rejects null. | Fresh `createDisplayContext(display)`; `display.getRealSize(Point)` and that Context's Configuration density. | Fresh display Context then `createWindowContext(TYPE_APPLICATION, null)`; that window Context's `maximumWindowMetrics.bounds` and display Context Configuration density. |
| `fromActivityDisplay` | Snapshot `defaultDisplay` on API 24–29 or nonnull `activity.display` on API 30–37, create and retain one application-safe display Context, and on API 30–37 create and retain one window Context from it; then drop Activity. Later moves are irrelevant. | On every read use the fixed Display's `getRealSize(Point)` and the retained display Context's current Configuration density. | On every read use the retained window Context's current `maximumWindowMetrics.bounds` and the retained display Context's current Configuration density. |
| `fromDisplay` | From the Application Context and exact explicit Display, create and retain one application-safe display Context and, on API 30–37, one window Context from it; never switches Display. | On every read use the fixed Display's `getRealSize(Point)` and the retained display Context's current Configuration density. | On every read use the retained window Context's current `maximumWindowMetrics.bounds` and the retained display Context's current Configuration density. |
| `fromUiContext` | API 24–29 unwraps Activity or throws documented `IllegalArgumentException`, snapshots `defaultDisplay`, then drops UI objects. API 30–37 validates and retains the exact real UI Context. | Each read creates a fresh application-safe display Context for the snapshotted Display, uses `display.getRealSize(Point)`, and reads that Context's current Configuration density. | On every read require `context.display`, and on API 31+ `context.isUiContext`; use that retained Context's own `WindowManager.maximumWindowMetrics.bounds` and `context.resources.configuration.densityDpi`. API-30 association/read failure is a metrics boundary failure; never substitute an application-derived Context chain. |

API-30+ `fromUiContext` instead registers exact `ComponentCallbacks` on the retained Context from the IO view.
Arbitrary-thread `onConfigurationChanged` only signals; `onLowMemory` is a no-op and not a memory-policy input.
Cleanup closes the signal and unregisters exact identity on the IO view. Contexts used
by the null/default and API-24–29 `fromUiContext` paths are transient read helpers. The retained safe display and
window Contexts for `fromActivityDisplay` and `fromDisplay` preserve their fixed association without retaining an
Activity; the API-30+ `fromUiContext` path retains the exact real UI Context as required.
On API 34–37 provider bounds remain provisional while provider density stays live; the first timely valid
projection resize makes projection width/height authoritative. The projection resize, visibility, and stop
adapters are registered once on the Android Handler by `AndroidCaptureOwner`; their bodies post occurrence-tagged
immutable facts on that Handler.

Each provider or projection geometry fact updates only its API-table fields. A valid combined tuple gets a new
geometry revision before replacing the latest cell; overwritten unclaimed values are superseded rather than
queued. Display removal, null display lookup, invalid tuple, observe/collect throw, and unexpected Flow completion
publish the exact boundary cause into the metrics cells.

Built-in cleanup closes the signal before exact IO-view unregister. Registration return, unregister return,
collector `finally`, and Handler-thread return are distinct receipts. Registration failure maps to pre-first-value
`InternalFailure` and still attempts exact unregister. Because unregister is inside Flow cleanup, its nonreturn
prevents collector return; its throw permits collector return but leaves the exact listener/DisplayManager/Handler
reference residue. Unexpected Flow completion publishes its boundary fact and separate `finally`.

Cancellation remains intent only. A blocking read is domain-fenced; late return can only enter cleanup, while
nonreturn does not block projection/controller/other Sessions. Once terminal fencing, signal close, and all
Android-owned work settle, `quitSafely` may proceed regardless of unresolved metrics unregister. A callback that
runs after fencing or after quit was requested can only fail to signal or publish a stale token; it has no
authority. Section 13 owns the exact metrics residue. First validity retains collection ownership. Pre-Active
loss uses the frozen terminal mapping; later loss suspends. Resize/visibility facts remain epoch-fenced and
visibility observation-only.

### 7.3 Current target and Surface release

The table above is canonical for target construction/retirement and the two listener occurrences. `CurrentTarget`
retains logical ownership while GL borrows it. Surface-release entry requires closed admission, drained work,
fenced generation, timely-preterminal or no-watchdog-cleanup listener removal and sentinel, exact producer
detach/release, and zero leases; retired facts cannot satisfy these. Its sole typed
`Surface.release()` occurrence uses the Android deadline only preterminal. Only normal return permits dependent
SurfaceTexture/OES destruction; late normal return is cleanup-only, and throw supplies no receipt. Downscaled-to-
Full fallback requires safe returned incompatibility, never timeout or ambiguity. Section 13 owns residue.

## 8. EGL, GLES2, Direct readback, and color

`GlPipelineOwner` performs the complete EGL occurrence on its GL lane with these exact calls and arguments:

| Step | Binding and validation |
| --- | --- |
| display | `EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)` must not return `EGL_NO_DISPLAY`; the handle is non-owned. `eglInitialize(display, major, 0, minor, 0)` must return true. |
| config | `eglChooseConfig` receives an attribute list requiring `EGL_SURFACE_TYPE = EGL_PBUFFER_BIT`, `EGL_RENDERABLE_TYPE = EGL_OPENGL_ES2_BIT`, red/green/blue/alpha sizes of 8, depth/stencil sizes of 0, terminated by `EGL_NONE`. It requests one config and requires true, one returned config, and a nonnull descriptor. The config is non-owned. |
| context | `eglCreateContext(display, config, EGL_NO_CONTEXT, [EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE], 0)` must not return `EGL_NO_CONTEXT`. The returned context immediately enters the partial-construction owner. |
| pbuffer | `eglCreatePbufferSurface(display, config, [EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE], 0)` must not return `EGL_NO_SURFACE`. The pbuffer is only the private current surface, never the render/readback target. |
| current | `eglMakeCurrent(display, pbuffer, pbuffer, context)` must return true before Session GL ownership is published, followed by the exact owned-context check below. No other caller invokes `eglMakeCurrent`. |
| teardown | After target and render dependencies settle, call `eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT)` with exactly those arguments, require true and classify its exact EGL error on failure, then require only `eglGetCurrentContext() == EGL_NO_CONTEXT`. Call `eglDestroyContext(display, context)`, `eglDestroySurface(display, pbuffer)`, and `eglReleaseThread()`, recording each boolean/error receipt. Drop the non-owned config/display references and never call `eglTerminate`. |

Partial failure destroys only successfully owned context/pbuffer objects after a safe unbind, outside the
settlement gate. A failed unbind/destroy cannot be treated as a receipt; it roots the exact remaining
context/pbuffer/lane dependency. Per-Session resources become reusable only after the successful current and
validation result for their occurrence.

Every EGL false result or sentinel return immediately calls `eglGetError()` once and records that exact code in
the precreated return cell. `EGL_BAD_ALLOC` is `ResourceExhausted` only when it is the coherent error from the
exact display/config/context/pbuffer construction boundary with safe partial ownership. Every other code,
missing/inconsistent error, current-context mismatch, or teardown failure is `InternalFailure`; a late teardown
result is only its physical cleanup evidence.

After the exact initial bind and before every GL construction, frame, or destruction occurrence, the currentness
helper performs only `eglGetCurrentContext()` and requires identity with the Session-owned context. It never calls
`eglGetCurrentDisplay` or either `eglGetCurrentSurface` getter. A mismatch records one exact `eglGetError()` result
and is `InternalFailure` whether that result is `EGL_SUCCESS`, another code, missing, or inconsistent; it fails
closed without a repair `eglMakeCurrent`. The exact teardown unbind call retains its normal/false/error receipt.
After normal unbind, the inverse helper performs only `eglGetCurrentContext()` and requires
`EGL_NO_CONTEXT`; mismatch records one exact `eglGetError()` result, supplies no completed unbind/currentness
receipt, and prevents dependent destruction from being claimed complete. No code path rebinds or repairs.

Immediately after the valid initial bind/currentness result and before any size-dependent GL construction,
`GlPipelineOwner` performs one capability-query group. An initial `glGetError()` must return `GL_NO_ERROR`; an
existing error is preserved as `InternalFailure`, not drained. It then calls
`glGetIntegerv(GL_MAX_TEXTURE_SIZE, maxTextureSize, 0)`,
`glGetIntegerv(GL_MAX_VIEWPORT_DIMS, maxViewportDims, 0)`, and
`glGetShaderPrecisionFormat(GL_FRAGMENT_SHADER, GL_HIGH_FLOAT, highFloatRange, 0, highFloatPrecision, 0)` into
precreated owner-local arrays, followed by exactly one final `glGetError()`. A clean final result plus positive
maximum-texture, viewport-width, and viewport-height values publishes one immutable capability fact. Any GL error,
missing result, or nonpositive size limit is `InternalFailure`; this nonallocating query does not reinterpret an
error as `ResourceExhausted`. A clean high-float result selects the highp fragment variant only when precision and
both range components are positive. The documented all-zero unsupported result selects mediump without rejecting
the Session; mixed or otherwise invalid precision evidence is `InternalFailure`. This query is the sole fragment-
precision and GL-size capability authority for the Session.

GL construction/allocation groups use one isolated probe protocol after a previously clean receipt: the first
`glGetError()` equal to `GL_NO_ERROR` is clean. If the first result is `GL_OUT_OF_MEMORY`, one immediate
confirmation probe must return `GL_NO_ERROR`; only that sole coherent OOM with exact safe ownership is
`ResourceExhausted`. A first non-OOM error or any nonzero confirmation is `InternalFailure`. This protocol is not
used by the approved old/final per-frame drains and creates no new count constant.

Shader source is a private string beside `GlPipelineOwner`. It is ESSL 1.00 with
`GL_OES_EGL_image_external`, applies the frozen crop/rotate/mirror/scale transform and copied OES matrix exactly
once, performs the separate framebuffer row inversion, and fuses color conversion/grayscale without an extra
framebuffer. Runtime fragment precision query selects the highp or mediump variant; absence of highp does not
reject the Session.

With its GL resource construction, `GlPipelineOwner` creates exactly two small direct native-order
`FloatBuffer`s containing the canonical position and texture-coordinate data. It owns and reuses those same two
buffers for every frame, restores each required buffer position before its `glVertexAttribPointer` call, and
drops both references with the owner after all GL occurrences settle. It creates no VBO, pool, per-frame direct
buffer, or per-frame dependent transform allocation; plan-dependent transform/color values remain reconciled
outside materialized-frame work.

The GLES2 binding is exact and adds no owner or state machine:

| Phase | Exact calls and arguments | Required result, classification, and receipt |
| --- | --- | --- |
| target OES texture | After the Section-6 target-size preflight, call `glGenTextures(1, ids, 0)`; `glBindTexture(GL_TEXTURE_EXTERNAL_OES, oes)`; `glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MIN_FILTER, GL_LINEAR)`, `glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MAG_FILTER, GL_LINEAR)`, `glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)`, and `glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)`. That exact `oes` name is passed to `SurfaceTexture(oes, false)`. | A nonzero name plus the isolated construction probe publishes the target-texture receipt. Failure retains only the partial target owner and uses the GL-error classification above. |
| shaders and program | For `GL_VERTEX_SHADER` and the selected highp/mediump `GL_FRAGMENT_SHADER`: `glCreateShader(type)`, require nonzero, `glShaderSource(shader, source)`, `glCompileShader(shader)`, then `glGetShaderiv(shader, GL_COMPILE_STATUS, status, 0)` and require true. Create a nonzero program with `glCreateProgram()`, call `glAttachShader(program, shader)` for both shaders, `glBindAttribLocation(program, 0, "aPosition")`, `glBindAttribLocation(program, 1, "aTexCoord")`, `glLinkProgram(program)`, and require `glGetProgramiv(program, GL_LINK_STATUS, status, 0)` true and every used uniform location nonnegative. After successful validation, `glDetachShader` and `glDeleteShader` each shader. | Required status plus the isolated construction probe is the receipt. Absent a sole confirmed OOM, a zero name, compile/link failure, missing used location, or any GL error is `InternalFailure`. Shader/program ownership remains in the partial-construction record until its clean deletion receipt. |
| output texture and framebuffer | After the Section-6 output-size preflight, call `glGenTextures(1, ids, 0)`; `glBindTexture(GL_TEXTURE_2D, output)`; `glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST)`, `glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST)`, `glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)`, and `glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)`; then `glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, Ow, Oh, 0, GL_RGBA, GL_UNSIGNED_BYTE, null)`. Call `glGenFramebuffers(1, ids, 0)`, `glBindFramebuffer(GL_FRAMEBUFFER, fbo)`, `glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, output, 0)`, and `glCheckFramebufferStatus(GL_FRAMEBUFFER)`. | Nonzero names, `GL_FRAMEBUFFER_COMPLETE`, and the isolated construction probe publish the RenderTarget receipt. A sole confirmed `GL_OUT_OF_MEMORY` is `ResourceExhausted`; incomplete framebuffer without that exact evidence, another error, or ambiguity is `InternalFailure`. |
| canonical per-frame state | `glBindFramebuffer(GL_FRAMEBUFFER, fbo)`, `glUseProgram(program)`, `glViewport(0, 0, Ow, Oh)`, `glActiveTexture(GL_TEXTURE0)`, `glBindTexture(GL_TEXTURE_EXTERNAL_OES, oes)`, and `glUniform1i(oesSamplerLocation, 0)`. Upload the frozen transform/color scalars and arrays at their validated locations; each matrix call uses count one, `transpose = false`, and array offset zero. Bind client arrays with `glBindBuffer(GL_ARRAY_BUFFER, 0)`, restore the required position on each of the two owner-retained buffers, call `glVertexAttribPointer(0, 2, GL_FLOAT, false, 0, positionBuffer)` and `glVertexAttribPointer(1, 2, GL_FLOAT, false, 0, textureCoordinateBuffer)`, then call `glEnableVertexAttribArray(0)` and `glEnableVertexAttribArray(1)`. Call `glColorMask(true, true, true, true)`, `glPixelStorei(GL_PACK_ALIGNMENT, 1)`, and `glDisable` for `GL_BLEND`, `GL_DEPTH_TEST`, `GL_STENCIL_TEST`, `GL_SCISSOR_TEST`, `GL_CULL_FACE`, and `GL_DITHER`. | These calls occur after the bounded old-error drain and before drawing. Any preserved current-phase GL error uses the classification row below; canonical state is reinstalled for every materialized frame without creating client buffers. |
| draw and Direct readback | `glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)`, then `glReadPixels(0, 0, Ow, Oh, GL_RGBA, GL_UNSIGNED_BYTE, carrier)` where `carrier` is the exact leased direct range with position zero and limit `B = 4 * Ow * Oh`. | Only the clean final bounded drain publishes the draw/readback receipt. Bytes remain tentative until the enclosing current frame occurrence settles timely; no PBO or second raw-frame-sized copy exists. |
| GL errors | The old and final frame drains use exactly the Document-07 bounds and preserve every code. Construction uses only the isolated probe protocol above. | For the current frame after a clean old drain, a sole attributable `GL_OUT_OF_MEMORY` with safe ownership is the existing `ResourceExhausted` storage result. Any other code, mixed evidence, unattributable old error, uncleared sentinel, or ownership ambiguity is `InternalFailure`. `GL_NO_ERROR` after earlier errors does not erase them. |
| destruction | With the exact context current and applicable leases closed, destroy independent output/program objects first: bind `fbo`, detach with `glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, 0, 0)`, bind framebuffer zero, call `glDeleteFramebuffers(1, ids, 0)` and `glDeleteTextures(1, ids, 0)` for the output, then call `glUseProgram(0)` and `glDeleteProgram(program)`. Only after the dependent SurfaceTexture release receipt, call `glBindTexture(GL_TEXTURE_EXTERNAL_OES, 0)` and `glDeleteTextures(1, ids, 0)` for the OES name. Partial construction calls the applicable same deletion functions in reverse ownership order; any still-owned shader uses `glDeleteShader(shader)`. | Each logical deletion group receives a typed receipt only when its one post-group `glGetError()` is `GL_NO_ERROR`. Any nonzero destruction probe is an absent receipt and unsafe `InternalFailure` while preterminal, or exact cleanup residue after terminal; a failed/late destruction never authorizes reuse. |

The matrix is canonical for GL state, target/render ownership, per-frame error drains, Direct readback, and typed
destruction receipts. Dataspace is classified after acquisition in frozen first-match order and emits
`ColorAction` only on action change. Tight RGBA byte count is a checked CPU/API range, not physical-storage
prediction; there is no PBO or second raw-frame-sized copy. Return cells include mechanical readback duration.
Section 13 owns unresolved target-dependent GL residue.

## 9. Fresh work, pacing, production slot, and cache

Each target generation owns exactly one `java.util.concurrent.atomic.AtomicBoolean` latest-pending source bit,
initially false. Its listener callback performs `set(true)` and then uses the lossless controller-drainer signal;
repeated sets coalesce. The controller may consume only the current, admissible generation and only when cadence,
production-slot capacity, and current topology allow immediate source materialization. That consume is the one
atomic `getAndSet(false)` that creates the fresh-attempt occurrence, reserves its final-outcome slot, and claims
the sole production slot. Cadence or any other deferral does not read-clear or otherwise clear the bit.

A listener `set(true)` that linearizes before the controller exchange is included in that materialization. A
`set(true)` that linearizes after the exchange remains true; its lossless signal, or the current drainer's dirty-
state self-rescan, causes a later admissibility check without requiring another source callback. The controller
never consumes a retired generation's bit: target retirement fences that generation and drops its bit with the
old `TargetOwner`. The production slot remains occupied through tentative encoding and a completed but not yet
publishable JPEG. A current pending bit may coexist, but no second attempt or JPEG queue is created.

The controller owns pacing/repeat policy, grant phases, and one current pacing/repeat wake identity separate from
the Stats wake. From one immutable snapshot, controller-confined `PacingOwner` synchronously calculates the
earliest future eligibility among pending fresh work, completed-unpublished JPEG, and repeat; the controller alone
commits or rejects that calculation. Replacement first cancels/removes/coalesces the queued current wake before
posting its successor. Physical cardinality is one queued pacing/repeat submission plus at most one
already-dequeued stale callback. Only the controller-authorized current callback may install the successor. A
stale rejection is cleanup-only; a current active rejection follows the required terminal rule.

An early source remains pending without a drop. Repeat reuses immutable payload bytes, creates new output metadata
and a lease transition, and performs no readback or encode. Suspending/image-affecting/fallback invalidation clears
cache and repeat eligibility exactly as frozen. Cached-first delivery reserves the normal handoff, preserves
metadata and bytes, and does not update production or pacing phase.

## 10. JPEG runtime, Framework adapter, native bridge, and storage

### 10.1 Combined runtime owner and carriers

One `JpegRuntimeOwner` stores both the Session-fixed carrier mode and Session-monotone native health. Its only
stable products are:

- `NativeMallocCarrier` with Native enabled by guarded AndroidBitmap capability and no Framework Bitmap or row
  scratch;
- `NativeMallocCarrier` with Native disabled and the common Framework adapter;
- managed direct carrier with Native disabled and the common Framework adapter.

The Kotlin `NativeMallocCarrier` owner holds one checked `malloc` allocation, address, exact-range direct
ByteBuffer view, leases, and one typed one-shot free occurrence. The managed direct carrier owns one checked
direct ByteBuffer and leases; retirement drops the last engine reference only after settlement and never
fabricates a physical-free receipt. A carrier is
stable and reused while exact-compatible. Managed storage can never be paired with Native JPEG.

Native-enabled state owns zero Framework pixel resources. `FrameworkJpegOwner` may allocate its Bitmap and
optional row scratch only after Framework has actually been selected: by `FrameworkOnly`, clean Native
ineligibility, or a safely completed Native disablement for a subsequent frame. The Native failure/switching
frame performs no Framework allocation or work and receives no same-frame Framework retry.

`JpegRuntimeOwner` submits each preparation, encode, recycle, or carrier-free operation as one non-suspending
owner Runnable on its JPEG IO view. The view serializes those Runnables but owns none of their operation,
submission, entry, return, deadline, carrier, Bitmap, transaction, or native-residue state.

After every lease and entered use has settled, retirement of an exact-incompatible `NativeMallocCarrier` before
terminal consumes that carrier into a one-shot free occurrence and submits its existing owner Runnable. Entry
while nonterminal uses `jpegEnteredOperationSafetyNanos`. A timely normal `nativeFreeCarrier` return is the sole
free receipt and permits the old owner to be dropped before replacement allocation. A winning submission
rejection, returned throw, expiry, or nonreturn selects the existing `InternalFailure`, retains the exact
carrier/ByteBuffer/occurrence residue, authorizes no replacement, and never retries the free call. A normal return
settled at or after the deadline, or after expiry, is usable only as cleanup evidence.

If terminal wins before that same occurrence enters, it converts to the existing no-watchdog cleanup domain
rather than creating another occurrence. If terminal wins after entry, arbitration first folds an already-
complete return; otherwise it retires and cancels the active deadline wake and transfers the intact occurrence
and carrier owner to cleanup, where mechanical return is awaited without a terminal watchdog. A carrier free
created because of terminal retirement likewise uses one no-watchdog occurrence from its origin. None of these
terminal paths changes the terminal winner or authorizes reuse.

The native implementation boundary is JNI-free below `screen_capture_engine_jni.cpp`.
`native_jpeg_runtime.h/.cpp` accept one non-owning compressor function and immutable per-call writer services plus
an explicit adoption callback/context for post-compressor segment transfer. The runtime owns only writer state,
segments, and evidence; it owns no compressor, platform-library handle, `JNIEnv`, Java reference, selector global,
or cross-DSO state. Production JNI glue supplies the guarded weak function and real writer services/adopter;
native-test JNI glue supplies only a test-local function, services, adopter, barriers, and probes around the same
compiled runtime object code.

### 10.2 Own JNI library loading and registration

Pipeline preparation owns one finite preterminal `OperationOccurrence` on the JPEG IO view, governed by
`jpegEnteredOperationSafetyNanos`. Its one entered interval starts immediately before `System.loadLibrary` and
spans own-loader serialization, `JNI_OnLoad`, bootstrap/registration, the `Auto` SDK/ABI checks, the native
same-function guarded weak-address query, carrier/backend selection, and complete preparation-result publication.
These steps never receive separate deadlines. `FrameworkOnly` does not call the weak-address query. A complete
result at `T < D` uses the ordinary clean own-library unavailability, OOM, poison, weak-address ineligibility, and
eligibility partitions. Empty-at-deadline or `T >= D` is timeout/nonreturn `InternalFailure`: late capability or
clean-unavailability evidence is cleanup-only, cannot publish a carrier/backend choice, and exact returned owners
remain in the occurrence bag for Section 13. Terminal conversion after entry retires the deadline and keeps the
same writable occurrence under no-watchdog cleanup.

Own-library loading is serialized by one process-private synchronized loader state. It calls
`System.loadLibrary("screencaptureengine")` directly. `JNI_OnLoad` is version-only: it verifies
`JNI_VERSION_1_6`, returns that version, and performs no class lookup, registration, global-reference creation,
Session/carrier/sink/capsule ownership, or thread creation. After the load call returns normally, the private
nested Kotlin object invokes its instance external `nativeBootstrap(): Int` exactly once. The exported bootstrap
uses `GetObjectClass(receiver)` and one fixed `RegisterNatives` table; successful zero/JNI-OK return with no
pending exception publishes the bridge.

Only `UnsatisfiedLinkError` or `SecurityException` thrown directly by `System.loadLibrary` before bootstrap and
with zero native owner selects the managed carrier. A direct load `OutOfMemoryError` is `ResourceExhausted`; any
other load throwable is `InternalFailure`. Once load returns, any bootstrap/`GetObjectClass`/registration failure,
pending exception, or unexpected bootstrap result poisons the process loader state as terminal
`InternalFailure`; it is never retried and never becomes clean unavailability. After successful publication,
registered runtime operations use their own frozen result partitions and cannot retroactively change loader
state. Once available, failure to allocate `NativeMallocCarrier` is required-storage exhaustion and does not switch
carrier mode. `FrameworkOnly` still performs this own-bridge/carrier selection; it neither evaluates nor invokes
the platform compressor helper and fixes Native health Disabled.

The production library exports exactly `JNI_OnLoad` and
`Java_io_screenstream_engine_internal_JpegRuntimeOwner_00024NativeBridge_nativeBootstrap`; the latter has JNI
signature `()I` and receives the `NativeBridge` instance as `jobject`. Every other native method is registered.
`MODULE:consumer-rules.pro` preserves the exact nested bridge binary name, bootstrap name/signature, registered
method names/signatures, and the adoption sink name/signature without retaining the surrounding package.

The single registered table is exactly:

| Kotlin method | JNI signature | Native argument/result order and owner reference |
| --- | --- | --- |
| `nativeAllocateCarrier(byteCount: Long): ByteBuffer` | `(J)Ljava/nio/ByteBuffer;` | Signed `jlong` is checked positive and narrowed to `size_t`; malloc pointer becomes the carrier owner only after `NewDirectByteBuffer(pointer, byteCount)` succeeds. Failure frees any provably owned partial pointer before throwing. |
| `nativeFreeCarrier(pixels: ByteBuffer): Unit` | `(Ljava/nio/ByteBuffer;)V` | Before JNI, the Kotlin `NativeMallocCarrier` owner requires exact referential ByteBuffer identity, expected range/capacity, sole ownership with zero leases, and exactly-once free admission. The one-argument native entry requires a nonnull direct-buffer address and valid positive representable capacity, then frees that returned address. It does not claim independently provable original-pointer provenance. Normal JNI return is the typed free receipt; the retired ByteBuffer reference is never reused. |
| `nativeHasWeakCompressor(): Boolean` | `()Z` | Used only by `Auto` preparation. The entry calls the one native helper that performs the nested `__builtin_available(android 30, *)` check and takes the same function's weak address only inside that scope. True publishes capability only; no pointer or owner crosses JNI. |
| `nativeCompress(writerBlock, pixels, pixelByteCount, width, height, stride, format, flags, dataspace, compressFormat, quality, sink, resultBlock): Int` | `(Ljava/nio/ByteBuffer;Ljava/nio/ByteBuffer;JIIIIJIIILio/screenstream/engine/internal/EncodedStorageOwner$NativeSegmentSink;Ljava/nio/ByteBuffer;)I` | Argument order is zero-token writer-owner block, direct pixels, byte-count `jlong`, width/height/stride/format `jint`, flags `jlong`, dataspace/format/quality `jint`, local sink reference, and `NativeResultBlock`. The entry calls the same guarded helper, requires its nonnull non-owning function result for the enabled occurrence, and passes it directly into the JNI-free runtime. No raw pointer crosses Kotlin or enters writable global/Session native state. With no pending Java exception, normal return is the platform compressor `jint`; pending adoption exception and result evidence retain their frozen precedence. |
| `nativeReleaseWriterResidue(writerBlock: ByteBuffer): Int` | `(Ljava/nio/ByteBuffer;)I` | Enters only for the exact `NativeWriterOwnerBlock` whose nonzero token names independently releasable `WriterCapsule` residue after all adoption calls returned. Zero return plus cleared token is the residue/segment-release receipt; ambiguous borrowed ownership remains quarantined. |

Every production and native-test JNI export or registered entry has an outer C++ exception-containment boundary;
no C++ exception may cross JNI. `JNI_OnLoad` is `noexcept`, version-only, and uses no throwing primitive.
Production `nativeBootstrap` catches every C++ exception, including `std::bad_alloc`, as bootstrap failure; Kotlin
then poisons the process loader state with the existing `InternalFailure`. Only an `OutOfMemoryError` thrown
directly by `System.loadLibrary` uses the load-time `ResourceExhausted` partition. At registered runtime entries
and production/test adoption, compressor-call, or writer boundaries whose frozen result partition permits exact
allocation/JNI OOM, `std::bad_alloc` maps to that exact OOM; a boundary without such a permitted outcome maps it
to `InternalFailure`. Every other typed or unknown C++ exception maps to the existing exact `InternalFailure`,
preserving owner/result evidence. A pending Java exception is neither cleared nor replaced; only pending-
exception-safe evidence/local-reference cleanup occurs before return.

`AndroidBitmap_CompressWriteFunc` is `noexcept` and uses only nonthrowing mutex, checked-integer, malloc/free,
copy, intrusive-link, and status-publication primitives; it contains no throwing container growth or callback.
A null `malloc` or defensively caught `std::bad_alloc`, or an otherwise-valid segment size/cumulative addition
that overflows or is not managed-`Int`-representable, records sticky exact OOM and returns false. Null data for a
nonzero size, invalid capsule/current state, lock/link/status-publication failure, or any other defensively caught
unexpected internal exception records sticky `InternalFailure` and returns false. Writer status is monotone
exactly `Safe -> OOM -> InternalFailure`; `InternalFailure` is absorbing. A callback observing `InternalFailure`
returns false immediately. A callback observing OOM performs only nonthrowing contract/state validation: later
malformed/internal evidence upgrades it to `InternalFailure`, otherwise it remains OOM. It never allocates,
copies, links, or changes accepted bytes/cumulative length after observing either failure status. Thus OOM then
malformed becomes `InternalFailure`, while malformed then allocation evidence remains `InternalFailure`.
Adoption adapters also contain all exceptions before returning typed evidence and always delete any created
local reference.

The exact production post-compressor callback target is private binary class
`io/screenstream/engine/internal/EncodedStorageOwner$NativeSegmentSink` method
`adoptNativeSegment(segment: ByteBuffer, byteCount: Int): Unit`, JNI signature
`(Ljava/nio/ByteBuffer;I)V`. On the original JNI thread, production glue resolves that method before runtime
entry and constructs the per-call adopter context/callback. Only after compressor return does the JNI-free runtime
invoke that callback for a segment; the production adapter creates the temporary direct-buffer local reference,
calls the method synchronously, records the returned adoption evidence, and deletes the local reference before
the runtime frees the segment. The method validates the exact range, synchronously copies it, records adoption,
and never retains the view. Consumer rules preserve this binary class/method and the `NativeBridge` table names/
signatures, but no surrounding package.

`NativeWriterOwnerBlock` is the only private managed native-order owner block. It contains exactly one opaque
`uint64_t ownerToken`; zero means no writer capsule, and nonzero references one separately allocated typed native
`WriterCapsule`. Kotlin never interprets or passes that token as a `Long`. Native validates the exact direct-block
identity/range and reads/writes the token explicitly; the block never aliases, embeds, or backs a native C++
struct. Native requires a nonzero `uintptr_t` capsule pointer to round-trip through `uint64_t` before publication.
The capsule owns only writer mutex/state, segments, and cleanup state. There is no compressor block or token.

`NativeResultBlock` is a separate preallocated native-order direct buffer with exactly five explicit `uint64_t`
fields in this order: writer status discriminator, total encoded bytes, managed adopted bytes, native remaining
bytes, and native remaining segment count. Native accesses these fields explicitly rather than casting the block
to a struct. Writer residue identity remains solely in `NativeWriterOwnerBlock`; no raw residue handle appears in
the result block or crosses JNI.

For compression, positive Kotlin width/height/stride narrow to native `uint32_t`; format/dataspace/compress-format/
quality narrow to their signed native enum/integer fields; flags narrow from nonnegative `jlong` to `uint32_t`;
pixel byte count narrows to `size_t`; and the direct pixel capacity must cover the exact range. The resulting
`AndroidBitmapInfo` field order is width, height, stride, format, flags; the remaining descriptor order is
dataspace, compress format, quality, pixels, writer function, writer context. Every narrowing occurs before
compressor entry and retains the carrier/sink owner references on failure.

Before calling `AndroidBitmap_compress`, native separately allocates one `WriterCapsule` and publishes its token
into the zero-token writer block. Native initializes/updates the fixed result fields without allocating a result
wrapper. Zero remaining bytes/segments, a cleared writer token, and normal JNI return prove that only managed
transaction owners remain. A nonzero writer token transfers that exact capsule/segment-list owner block into the
operation owner bag for cleanup or quarantine; it is never a cache handle.

### 10.3 Weak compressor capability and non-ownership

`MODULE:build.gradle.kts` passes the exact CMake configure argument `-DANDROID_WEAK_API_DEFS=ON` through
`externalNativeBuild` for every production/nativeTest native configuration using the shared production target.
With NDK 28 so configured, `CPP:CMakeLists.txt` links system `jnigraphics` directly and owns target/object
composition, hidden visibility, and the policy that unguarded-availability diagnostics remain errors; it does not
enable the toolchain option by itself. Because the library dependency is
not weak, the production DSO has a direct `DT_NEEDED` dependency that may map system `libjnigraphics.so` when the
engine DSO loads even on API 24–29 or under `FrameworkOnly`. The exact incremental resident cost of that mapping
is unknown, disclosed, and excluded from predictive memory accounting.

`FrameworkOnly` never calls the helper and never invokes the compressor. For `Auto`, Kotlin first checks the
closed shipped-ABI rule and then calls `nativeHasWeakCompressor()` during the one preparation occurrence. Both
that registered entry and `nativeCompress` use the same native helper and same function inside the nested guard:

```cpp
if (__builtin_available(android 30, *)) {
    auto compressor = &AndroidBitmap_compress;
    if (compressor != nullptr) {
        // Return/use this non-owning function value only inside native code.
    }
}
```

API 24–29 never enters the guarded scope and never invokes the compressor. On API 30+, a null address is clean
static ineligibility and preparation keeps the `NativeMallocCarrier` while selecting Framework before any fresh
attempt is assigned to Native. A true preparation result publishes only Session-monotone enabled capability to
Kotlin. At an enabled `nativeCompress` entry, the helper resolves the same address again and passes that
non-owning function value directly into the JNI-free runtime for the call; an inconsistent null is
`InternalFailure` before compressor invocation. No raw pointer crosses JNI, and no writable global or Session
native state stores it.

The weak function is only a non-owning call target: it creates no native compressor state, handle, token, lease,
cleanup obligation, or receipt. Native disablement therefore changes only controller health and future backend
selection and creates no native cleanup action.

### 10.4 Framework JPEG

Only after Framework selection, `FrameworkJpegOwner` may create one mutable non-HARDWARE sRGB `ARGB_8888` Bitmap
of the exact output shape and, only when needed, one row scratch array. Native-enabled state never precreates
either resource. `FrameworkOnly` or clean Native ineligibility permits creation after that selection; a timely
safe Native failure first completes disablement, drops that switching frame without Framework allocation or work,
and permits creation only for a later frame. With the carrier lease held, the adapter resets an exact-range
ByteBuffer view. It uses the single-call buffer copy only when row bytes, byte count, position, limit, and remaining
satisfy the frozen tight guard. Otherwise it converts one row at a time to logical opaque ARGB integers and calls
`setPixels` for that row; it never allocates a full-frame conversion array.

It calls `Bitmap.compress` into the common checked transaction `OutputStream`. True plus successful transaction
commit is success. False discards and counts the one failed attempt without retry. Exact OOM/unrepresentable sink
length is the specified terminal resource failure; malformed sink state, unexpected runtime failure, nonreturn,
or ambiguous ownership is the specified terminal internal failure. Partial bytes never publish.

Same-shape Bitmap/scratch are reused. After uses/leases settle, incompatible replacement or retirement enters one
JPEG-IO-view recycle occurrence: bounded only preterminal, one `recycle()` call, no retry. Only normal return
permits drop/replacement; every other outcome follows Section 13 residue rules.

### 10.5 Native descriptor, native-only callback writer, and managed adoption

Before JNI entry, Kotlin checks every positive dimension, tight byte count, row stride, quality, and Kotlin-to-
JNI scalar narrowing. Segment pointer/size/cumulative validation belongs exclusively to the native writer because
those values do not exist before the callback. The immutable native descriptor contains exactly the frozen pixels, byte count,
`AndroidBitmapInfo`, dataspace, format, quality, and writer context/function values. The carrier and transaction
roles remain leased until the complete native occurrence settles; the compressor function is non-owning.

The `AndroidBitmap_CompressWriteFunc` callback is native-only because the platform does not promise its thread. Its
typed `WriterCapsule` contains a native mutex, sticky writer status, checked cumulative length, and an ordered
list of exactly callback-sized malloc segments. Each callback locks the capsule; validates nonnull data for nonempty size, native
`size_t` to the managed `Int` range, current writer state, addition overflow, and cumulative `Int`
representability; allocates one exact segment; copies the bytes; links its owner; and unlocks. It never stores or uses a
`JNIEnv`, calls Kotlin/Java, or publishes a managed view. Any contract violation or allocation failure becomes a
sticky fact in the exact OOM/InternalFailure partition above while retaining exact segment ownership. Its bool result is exact: a validated zero-size callback or
successful validated allocation/copy/link returns true; validation/range/sticky/allocation failure returns false.
After OOM, later callbacks return false without changing bytes, cumulative length, or segment ownership; the sole
permitted state mutation is the stronger malformed/internal upgrade. After `InternalFailure`, they return false
immediately with no mutation.

After context/current-writer validation, `size == 0` is a valid successful no-op: `data` may be null, and the
callback performs no allocation, copy, list link, or cumulative-length change. A nonempty segment still requires
nonnull `data` and all checks above.

After `AndroidBitmap_compress` returns on the original JNI caller thread, runtime first freezes compressor result
and writer evidence. Managed adoption is permitted only when writer evidence is safe and the compressor result is
exactly `ANDROID_BITMAP_RESULT_SUCCESS`. Writer fault/OOM retains its existing higher precedence and invokes no
adopter. Every non-SUCCESS compressor result likewise invokes no adopter: runtime frees/discards every provably
owned native segment, retains any ambiguous residue, and then publishes the result for ordinary classification.

Only the safe-writer SUCCESS branch performs sequential post-compressor transfer through the per-call adopter
into the Framework-shared transaction. For each segment, production creates
a temporary direct view and synchronously calls the unchanged adoption method, which validates range/cumulative
length, allocates exact `ByteArray(s)`, copies/appends one sealed segment, and returns without retaining the view.
Glue deletes the local reference and native frees that segment with receipt before the next adoption. Segments are
never grouped or used as payload/cache backing. Failure preserves exact managed/native facts, frees every proven-
unborrowed segment, and returns sticky outcome; uncertain adoption is terminal/quarantined.

Kotlin supplies preallocated owner/result blocks; JNI validates them and runtime writes only their defined token/
evidence fields. Adoption Java exception stays pending while runtime records safe segment facts; Kotlin catches
that exact throwable. No compressor `Int` reaches Kotlin in this branch: it is ignored/non-authoritative, while
the precreated return cell still publishes throwable plus block evidence under existing JNI/OOM precedence. JNI
allocates no result wrapper.

During transfer, native-owned `N` plus adopted `M` equals `J`; copying current `s` yields
`N + M + s = J + s <= J + Smax`. Its release moves `s` from `N` to `M` and removes the duplicate. One segment of
size `J` gives transient `2J`; completion leaves only managed `J` and no native cache backing. Structural live
bytes are raw carrier `B`, latest `L`, optional displaced leased `O`, tentative `J`, and current managed-copy
`Smax`, plus opaque overhead. These are actual ranges, never predictions. Managed segment/object/reference
overhead is callback-proportional, disclosed but uncapped/unpredicted; allocation failure uses the existing split.

The precreated result cell contains present/absent compressor result, writer/JNI/transaction status, and typed
carrier/compress/segment/adoption receipts. Precedence is ambiguity/contract/non-OOM JNI fault, then exact OOM/
unrepresentable length, then present compressor result; adoption evidence can exist only for safe-writer SUCCESS.
Only timely safe optional allocation failure disables
Native for later frames, never retries the frame; stale timely safe failure may still disable current health.
Late/expired/retired-health result is cleanup-only.

### 10.6 Managed segmented storage

`EncodedStorageOwner` is the canonical authority for the sole production role (tentative transaction or completed
unpublished payload), one latest published payload, and at most one displaced payload retained by the sole
delivery lease. Publication/displacement transfer logical ownership under `sessionGate`; zero-lease retirement
drops the exact final managed reference outside it.

The owner-confined states are exactly `Open`, `ProducerClosed`, `Faulted`, `Committed`, and `Aborted`. `close()`
idempotently moves healthy Open to ProducerClosed without commit/trim/publication; `flush()` is open-state no-op.
`commit()` accepts only healthy Open/ProducerClosed and transfers after all final work succeeds. Allocation-free
`abort()` idempotently detaches tentative owners; post-Commit abort is invariant failure. First fault is sticky and
later writes do not mutate. Exact OOM/unrepresentable `Int` length is `ResourceExhausted`; malformed state/range or
unexpected `Exception` is `InternalFailure`; Framework does not normalize non-OOM fatal VM `Error`.

The Framework producer facade is the private checked `OutputStream`. `write(int)` validates a healthy writable
transaction and checked `total + 1`, then appends exactly the low eight bits without a temporary one-byte array.
Bulk `write(bytes, offset, count)` validates state, the entire source range with overflow-safe checks, and the
complete new cumulative `Int` total before its first mutation. A valid zero count is an allocation-free no-op.
It fills the current tail and, if bytes remain, creates at most one new tail for that call using only derived
values:

```text
A = bytes already accepted when a new tail is needed
R = positive bytes remaining in this write call
V = Int.MAX_VALUE - A
C = min(max(A, R), V)
```

The prior cumulative check proves `R <= V`, hence `C >= R > 0`. The first bulk write allocates exact `R`;
successive isolated `write(int)` calls derive capacities `1, 1, 2, 4, ...`. There is no chunk-size constant,
pool, predictive sizing, or copying of existing full segments. An allocation/copy failure after tentative bytes
from the current call makes the whole transaction sticky-Faulted; no partial bytes can publish.

Framework commit rejects zero-byte encoder success. Full final tail transfers directly; partial used `U` trims to
exact `ByteArray(U)`. Commit follows successful trim and immutable ordered-reference freeze and never concatenates.
For `J > 0`, open capacity is at most `2J`; trim retains `J + C < 2J`; persistent capacity is exact `J`. Object/
container/allocator/Bitmap/platform/reclamation overhead is disclosed, never predictively accounted.

The Native producer facade uses the same transaction state/commit/abort/cache path but cannot mix with Framework
writes. Every nonempty callback adoption contributes its one exact sealed `ByteArray(s)` from Section 10.5; a
zero-size writer callback is an allocation-free no-op and creates no managed segment. The JNI adoption signature
remains `(Ljava/nio/ByteBuffer;I)V`. Commit is possible only after the complete ordered transfer succeeds; every
failure publishes no partial payload.

Immutable payload owns ordered private segments and exact total. `copyTo` validates the whole destination before
direct traversal. Sole public flattening `copyBytes()` allocates caller-owned exact `J`, transiently beside engine
`J`. Every frame access checks captured callback thread/token; return invalidates token before lease release.
No array/view/builder/reference container escapes; no native-backed cache or per-payload native cleanup exists.

## 11. Delivery, direct dispatcher boundary, and unsubscribe

### 11.1 One handoff and three independent cells

`DeliveryOwner` owns one registration generation, one handoff record, one delivery-worker capacity, and a
reference to its distinct non-owned per-Session IO serialization view. It submits one non-suspending owner
Runnable at a time; the view is execution service rather than a handoff or lifecycle owner. A prepared record
owns one encoded lease and precreates three independent one-shot facts under its settlement gate:

- the dispatch-call return cell: normal return or actual thrown/rejected throwable;
- the trampoline-entry cell: entered or detached self-rejection;
- the callback-return cell: normal return or callback throwable.

`HandoffState` is a private closed enum with exactly `Prepared`, `Dispatching`, `AcceptedQueued`,
`DetachedPreEntry`, `Entered`, `Resolved`, and `Quarantined`. Its graph and owner contents are fixed:

```text
Prepared -> Dispatching -> AcceptedQueued -> Entered -> Resolved
Prepared -> Resolved
Dispatching -> Entered
Dispatching -> Resolved
Dispatching | AcceptedQueued -> DetachedPreEntry -> Resolved
Dispatching | AcceptedQueued | DetachedPreEntry | Entered -> Quarantined
```

| State | Exact authority and owners |
| --- | --- |
| `Prepared` | Registration-generation admission, handoff record, borrowed-frame authority, encoded lease, precreated three cells, and reserved worker capacity; no external dispatcher side has entered. Unsubscribe/terminal may resolve it directly and release the lease. |
| `Dispatching` | The delivery worker commits `Prepared -> Dispatching` under the record `settlementGate`, releases that gate, and only then invokes caller dispatch outside all gates. The record owns the in-call dispatch side, worker capacity, trampoline, borrowed-frame authority, and lease until entry/callback settlement says otherwise. |
| `AcceptedQueued` | Normal dispatch return won before trampoline entry. The dispatch side is settled; the queued trampoline, accepted-task deadline, borrowed-frame authority, and lease remain. |
| `DetachedPreEntry` | Unsubscribe or terminal closed admission after external dispatch began but before entry. It owns only the unresolved dispatch return when still in call, trampoline self-rejection/entry cell, applicable accepted-task deadline after a later normal return, borrowed-frame authority, and lease. It never invokes user code. |
| `Entered` | Trampoline entry won. The callback-return side owns borrowed-frame authority and lease until its mechanical return/throw; the separate dispatch side and worker remain if the dispatch call is still unresolved. Callback completion releases only callback authority/lease and does not resolve the record while dispatch remains. |
| `Resolved` | Every side required by that occurrence has mechanically settled and no handoff owner remains. It may return to the registration's idle slot only while that same generation stays active; closed registration proceeds to unsubscribe arbitration. |
| `Quarantined` | The exact still-unresolved dispatch, entry, or callback cell plus only the worker, trampoline, borrowed authority, and lease that side still owns are children of `SessionQuarantineRoot`. It is never reusable and admits no successor. |

This is the sole delivery machine. Record transitions/cells/releases use its gate; callback admission/terminal use
`sessionGate -> settlementGate`. Terminal folds complete sides, then transfers only unresolved residue. Later
facts settle that residue only: no delivery counter, `DeliveryProblem`, State, or replacement change; only actual
root mutation may attempt `QuarantineChanged`.

The delivery owner Runnable commits `Prepared -> Dispatching`, unlocks, then directly calls
`frameCallbackDispatcher.dispatch(EmptyCoroutineContext, trampoline)`. The same Runnable catches and publishes the
actual dispatch outcome allocation-free; no controller hop interprets it. Trampoline entry alone briefly takes
the nested gates to commit entry/detached rejection, then unlocks before user code. Normal pre-entry dispatch
return arms the accepted-task entry deadline from that sample; dispatch-call duration has no watchdog.

Entry wins classification; later dispatch outcome only settles its side. Callback return immediately releases
borrowed authority/lease, but an in-call dispatch keeps its worker/record, making intervening opportunities busy.
Delivery-worker submission rejection is a separate generic occurrence: active safely-unentered victory is
terminal internal failure, while any earlier detach/terminal/entry/cleanup disposition remains authoritative.
The submission/entry/return cells, serial capacity, fencing, and classification are unchanged by the non-owned
view. A nonreturn keeps the exact Runnable/record residue but never makes the IO view a cleanup child.

### 11.2 Controller-committed unsubscribe result

The subscription owns one shared completion signal independent of any calling coroutine and one
controller-authoritative monotone result:

```text
Open -> Closing -> Success
Open | Closing -> FailedTerminal(problem)
Open | Closing -> StoppedTerminal(reason)
```

First `unsubscribe` changes `Open` to `Closing` under `sessionGate` and closes admission; `Prepared` resolves
directly, while other handoff states follow their exact cells. Handoff convergence publishes one identity-fenced
mechanical fact and signals the controller; it does not complete waiters or select success itself. In one
`sessionGate` turn the controller atomically selects `Success` if the shared result is still `Closing` and no
terminal/quarantine winner exists. If terminal or quarantine already won, its shared terminal result is retained
instead. Only the `Success` commit clears replacement exclusion. The controller completes the one shared signal
after unlocking, so every current or later waiter observes the same committed result without a per-waiter gate
reacquisition or a second arbitration point. A later terminal cannot revoke an already committed `Success`.

Exact thread-local callback identity rejects same-callback unsubscribe before `Closing`. Caller cancellation is
local to that wait: it neither changes the shared result nor reopens admission, and a later call may await the
same signal. `Success` calls are idempotent. An unresolved terminal/quarantined handoff retains the closed
registration and replacement exclusion even if late facts shrink it.

### 11.3 Terminal delivery cutoff

Before final Stats, terminal folds a complete callback cell and releases its side; otherwise the writable
callback/lease transfers. A complete callback with unresolved dispatch transfers only worker residue. Section 13
owns later root reduction; it cannot update delivery counters, diagnostics, or State.

## 12. State, Stats, and diagnostics

### 12.1 Observation construction and publication

`SessionController` holds all counters, diagnostic sequence, public observation values, and result authority; only
read-only Flow views escape. After an authoritative turn commits and cleanup scheduling is selected, it passes one
immutable snapshot to controller-confined `ObservationOwner`. That helper synchronously builds the State, Stats,
or diagnostic publication and assigns/tries emission unlocked without carrier; it retains no competing state.
Requested/effective/running/visibility form one State.
Startup completes after Running assignment. `updateParameters` constructs exactly once from its coherent complete
snapshot cell; equality conflation can suppress only that whole value, never mix fields. Terminal assigns final
Stats before terminal State; the two flows are not an atomic pair.

### 12.2 Stats

Counters saturate; ordered finite means retain prior finite value and diagnose nonfinite candidates. Frozen
rounding/FPS/repeat/zero/saturation formulas are exact. Lifecycle/suspension/rebuild/fallback publishes
immediately; other dirtiness coalesces behind one identity-fenced wake anchored at last publication, without
catch-up; final Stats is immediate.

Each materialized production owns one classification slot. Terminal folds complete returns/selected dispositions
before final Stats. Existing output/cache/classified drops remain; unclassified retirement, unpublished-JPEG
retirement, or whole-record transfer adds no drop. Late cleanup cannot change Stats, and returned production
failure precedes stale-success accounting.

### 12.3 Diagnostics

The owner provides exactly eight category constructors: `CapabilityCheck`, `RuntimeProfile`,
`RuntimeModeChanged`, `DeliveryProblem`, `StatsProblem`, `ColorAction`, `QuarantineChanged`, and
`SessionTerminal`. Call sites pass a fixed allowed source, semantic fields, and raw nullable cause. There is no
generic public label constructor.

| Label | Exact creation sites and source | Semantic message and raw cause |
| --- | --- | --- |
| `CapabilityCheck` | `MetricsProvider`, `MediaProjection`, `VirtualDisplay`, `SurfaceTarget`, `GlPipeline`, `FrameworkJpeg`, `NativeJpeg`, or `Controller` at each top-level capability-axis selection/failure, including the timeout table below. | Names the boundary or selected capability, decision, and action. Carries only the raw boundary cause when one exists; an injected invalid value has null cause. |
| `RuntimeProfile` | `Session`, once when initial Running commits. | Names selected target, GLES2 Direct readback, precision, JPEG, Framework transfer, and color modes. Cause is null. |
| `RuntimeModeChanged` | `SurfaceTarget` for the one safe Downscaled-to-Full change, `FrameworkJpeg` for an actual Framework transfer-mode change, or `NativeJpeg` for Native-to-Framework disablement. | Names previous mode, new mode, and fallback/action. Carries the safe returned boundary cause when one exists; otherwise null. It is never created for timeout, stale cleanup, or a merely considered mode. |
| `DeliveryProblem` | `FrameConsumer` when a caller-dispatch rejection/throw commits before entry or an entered callback throw commits before terminal transfer. | Names dispatch or callback failure and that the opportunity was dropped while registration remains as applicable. Carries the exact dispatcher/callback throwable. Busy delivery is Stats-only and creates no event. |
| `StatsProblem` | `Controller` at nonfinite mean retention, produced-FPS finite clamp, or saturated-derived-value freeze requiring visibility. | Names the affected average/FPS and retained/clamped action. Cause is always null. |
| `ColorAction` | `ColorPipeline` once per target and when the observed dataspace classification/action actually changes. | Names observed classification and applied nominal, P3-to-sRGB, wide/HDR best-effort, or grayscale/color action. Cause is null. |
| `QuarantineChanged` | `Cleanup` only after attaching, reducing, or removing an exact `SessionQuarantineRoot` child. | Names the affected owner/occurrence class and cleanup action without exposing bytes or handles. Carries the raw cleanup boundary cause that produced the mutation when one exists; otherwise null. Resolution before root mutation creates none. |
| `SessionTerminal` | `Session`, once for the winning terminal commit after final Stats is fixed and before terminal State assignment. | Names Stopped reason or Failed problem and last active modes. Normal owner/capture stop has null cause. The required pre-Active valid-then-invalid metrics terminal attempt also has null cause even when its boundary CapabilityCheck carries a cause. Other Failed outcomes carry the selected raw terminal cause when defined; a timeout winner reuses the identical timeout-cause object from its CapabilityCheck. |

The five private timeout families bind diagnostics without new telemetry:

| Family | Winning `CapabilityCheck` source/site | Required terminal visibility |
| --- | --- | --- |
| first metrics readiness | `MetricsProvider` at the readiness occurrence | Semantic missing/invalid first metrics action, raw timeout cause, `CaptureUnavailable`; following `SessionTerminal` reuses that cause. |
| initial captured resize readiness | `MediaProjection` at the API 34–37 readiness occurrence | Semantic missing initial capture geometry action, raw timeout cause, `CaptureUnavailable`; following `SessionTerminal` reuses it. |
| Android entered operation | `MediaProjection` for projection-callback registration or `createVirtualDisplay`, `VirtualDisplay` for `resize` or `setSurface`, and `SurfaceTarget` for preterminal listener installation/removal or Surface release | Semantic timed operation and cleanup/failure action, raw timeout cause, existing `InternalFailure`; following `SessionTerminal` reuses it. |
| GL entered operation | `GlPipeline` at EGL/GL/readback construction, frame, or destruction occurrence | Semantic timed GL boundary/action, raw timeout cause, existing `InternalFailure`; following `SessionTerminal` reuses it. |
| JPEG entered operation | `FrameworkJpeg` for Framework encode/recycle or `NativeJpeg` for Native preparation, JNI/native encode, or preterminal incompatible-`NativeMallocCarrier` free | Semantic timed backend or carrier-free boundary/action, raw timeout cause, existing `InternalFailure`; following `SessionTerminal` reuses it. |

Higher-priority terminal makes expiry cleanup-only with no timeout event. Timeout creates no mode change,
fallback, retry, duration telemetry, parser field, or aggregate; the public six-field schema is unchanged.
Unlocked turn-end emission increments nonreused Session sequence, samples wall clock, constructs from short fixed
semantic templates without Throwable text, and `tryEmit`s once without retry/control authority. Routine geometry,
visibility, rebuild, consumer lifecycle, and frames emit nothing. Every identity family is overflow-checked before
increment; exhaustion fails terminally rather than wrapping/reuse.

## 13. Cleanup, release, and quarantine

Terminal commit closes all admission, invalidates cache/repeat, and logically transfers every present owner into
one of the cleanup forest's root records before public terminal assignment. Each root is independently progressed;
a nonreturn blocks only its physical dependents.

The Android root follows its fixed subchain. The target root enforces all Surface prerequisites and its dependent
GL/EGL order. JPEG/storage separately settles encoder, transaction, carriers, Bitmap recycle, payload roles, and
leases. Delivery retains only its unresolved cells/worker/lease residue. JPEG and delivery never shut down, await,
or root their non-owned IO views and have no executor-termination receipt; a nonreturn roots only the exact
Session-owned Runnable occurrence and resources it still owns. Metrics distinguishes registration, unregister,
cancellation request, and collector return, and likewise never roots its IO dispatcher view. Unresolved
DisplayListener unregister retains its listener, DisplayManager, and Handler reference only; it neither blocks
Android `quitSafely` after Android-owned work settles nor gains a fabricated unregister receipt from Handler
termination. Deadline/wake links remain children of their parent records and retire by identity.
`NativeMallocCarrier` free, writer-owner-block token clear, writer-capsule/segment release, and managed adoption
have typed receipts. Preterminal incompatible-carrier free uses the JPEG entered-operation boundary; terminal-
origin or terminal-converted free uses the same one-shot occurrence without a watchdog. Only its normal return
drops the native owner, while rejection, throw, or nonreturn retains exact residue without retry. The weak
compressor function owns nothing and has no cleanup record.

One empty `SessionQuarantineRoot` is created with the Session. Under the controller's sole nested
`sessionGate -> settlementGate` order, unsafe residue is logically attached to the root before any outside
cleanup action. The root stores exact owner/occurrence records, never copied raw references without their gates.
Late receipts move only their matching child to released or a smaller residue. An actual child-set change queues
one cleanup diagnostic outside the gate. No cleanup result can revive admission, replace an owner, alter backend
health, or rewrite terminal State.

Managed retirement means dropping the exact final engine reference after leases/operations settle. Native memory,
Bitmap, Surface, SurfaceTexture, GL objects, and owned executors require their real typed release/free/return
receipt. Non-owned Kotlin dispatcher views require no shutdown or return receipt. Ambiguity never becomes a
fabricated release.

## 14. Build, ABI, source sets, and packaging

Gate B records Android Gradle Plugin `9.1.1`, Kotlin `2.4.0`, kotlinx-coroutines `1.11.0`, minimum API 24,
compile/target API 37, JVM 17, and NDK `28.2.13676358` as approved external inputs. Root plugin/classpath/catalog/
wrapper integration and Kotlin plugin mode are outside this package. After separate implementation authorization,
module-local integration provides namespace `io.screenstream.engine`, Kotlin explicit API, and a public release
AAR without changing that baseline. The same module build script passes `-DANDROID_WEAK_API_DEFS=ON` through
`externalNativeBuild` for all production/nativeTest native configurations using the shared production target;
this is module-local integration and does not prescribe root AGP, Kotlin plugin, classpath, catalog, or wrapper wiring.

| Variant boundary | CMake target, packaged output, and load name | Exact wiring and isolation |
| --- | --- | --- |
| production main/debug/release/nativeTest | Target `screencaptureengine`; `libscreencaptureengine.so`; load name `screencaptureengine`. | For every listed native configuration, module `externalNativeBuild` passes exact `-DANDROID_WEAK_API_DEFS=ON`. Main CMake consumes that toolchain configuration, compiles hidden PIC OBJECT target `screencaptureengine_runtime` once from `native_jpeg_runtime.cpp`, then links it with production JNI glue and system `jnigraphics`; CMake owns target/object composition, hidden visibility, and unguarded-availability diagnostics as errors, not activation of the option itself. Production glue/registration is identical and hook-free in every variant; the resulting direct `DT_NEEDED` is inspected. |
| nativeTest only | Target `screencaptureengine_native_test`; `libscreencaptureengine_native_test.so`; test harness load name `screencaptureengine_native_test`. | Only `nativeTest` passes `SCREEN_CAPTURE_ENGINE_NATIVE_TEST=ON` and includes `NATIVE_CPP:`. Its local JNI glue links the same runtime object; the option adds only this DSO and never changes runtime-object compilation. |

The library sets `testBuildType = "nativeTest"`; all Kotlin instrumentation stays in `ANDROID_API`/`ANDROID_INT`
and executes once as `connectedNativeTestAndroidTest`. Only native C++ has a nativeTest-specific source root.
Debug/release contain only production DSO; nativeTest contains both. The test DSO is nonpublished, never affects
own-library availability, Native health, production `JNI_OnLoad`, consumer rules, or hidden production state; it
owns all fake functions/services/adopters, barriers, probes, writer capsules, and tokens. Production owns no test selector or
cross-DSO state.

The production DSO builds for exactly `armeabi-v7a`, `arm64-v8a`, `x86`, and `x86_64`, uses hidden default
visibility, and exports exactly `JNI_OnLoad` plus
`Java_io_screenstream_engine_internal_JpegRuntimeOwner_00024NativeBridge_nativeBootstrap`; every other bridge
method is registered. It has no writable global Session owner or owner-producing initializer. Its native helper
performs the same-function nested `__builtin_available(android 30, *)` check before taking and null-checking the
weak `AndroidBitmap_compress` address; no raw address crosses Kotlin or persists in writable native state.

Native linking/packaging meets current 16-KiB alignment. The direct `jnigraphics` dependency may map the system
library with the engine DSO even on API 24–29 or under `FrameworkOnly`; its unknown incremental resident cost is
disclosed but never predicted. Static verification covers all four packaged ABIs and
ELF alignment; runtime instrumentation covers only the executing ABI. Host `test` packages no native code.
Production-DSO instrumentation is black-box; deterministic native faults use test-local glue around the
source-identical runtime object. Consumer rules retain only the exact bootstrap/registered bridge members and
adoption sink required by JNI; public API is naturally retained by use.

After implementation authorization, `verifyScreenCaptureEnginePackage` is a module-local filtered host `Test`
task over selected debug/release/nativeTest artifacts. It depends on assembly and release lint; checks publication/
API visibility, DSO placement, four ABI entries, exactly the two production exports above, the production DSO's
direct `jnigraphics` dependency, module-Gradle configure-argument coverage for all production/nativeTest native
configurations, and 16-KiB load alignment. It is not instrumentation and creates no generated
verifier/parser.

The compact verification handoff to Document 08 is complete platform call/receipt and API-band coverage; generic
deadline/late/quarantine races including saturated-batch self-rescan, Native preparation, listener operations,
and preterminal incompatible-carrier free with terminal no-watchdog conversion; per-axis texture/viewport limits
for both target and output; atomic pending-source set/exchange races across drainer scans and generation
retirement; GL transforms, errors, draw/readback, and destruction; own-loader/JNI registration, guarded weak binding,
non-owning compressor-call provenance, module-owned weak-API configure-argument coverage, direct `jnigraphics`
dependency, and complete C++
exception containment; `AndroidBitmap_CompressWriteFunc` true/false/sticky behavior; SUCCESS-gated adoption and
non-SUCCESS no-adopter cleanup; native adoption ordering/residue/
exception precedence; segmented-storage state, allocation, copy, and byte bounds; delivery arbitration; coherent
observations; distinct non-owned JPEG/delivery IO-view serialization with preserved occurrence cells and no view-
termination receipt; and artifact/API/package isolation. Document 08 owns exact test IDs, vectors, tolerances,
runners, and task assignments. Implementation begins only after Gate B and separate authorization.

## 15. Official implementation references

The binding relies on current official contracts, including:

- Android capture and target APIs: [MediaProjection](https://developer.android.com/reference/android/media/projection/MediaProjection),
  [VirtualDisplay](https://developer.android.com/reference/android/hardware/display/VirtualDisplay), and
  [SurfaceTexture](https://developer.android.com/reference/android/graphics/SurfaceTexture);
- EGL/GLES: [EGL14](https://developer.android.com/reference/android/opengl/EGL14),
  [GLES20](https://developer.android.com/reference/android/opengl/GLES20), and the
  [OpenGL ES 2.0 specification](https://registry.khronos.org/OpenGL/specs/es/2.0/es_full_spec_2.0.pdf);
- Framework/native JPEG: [Bitmap](https://developer.android.com/reference/android/graphics/Bitmap),
  [Android bitmap compression](https://developer.android.com/ndk/reference/group/bitmap), and
  [JNI direct-buffer support](https://docs.oracle.com/en/java/javase/17/docs/specs/jni/functions.html#nio-support);
- JNI/packaging: [JNI tips](https://developer.android.com/ndk/guides/jni-tips),
  [Android ABIs](https://developer.android.com/ndk/guides/abis),
  [newer NDK APIs](https://developer.android.com/ndk/guides/using-newer-apis), and
  [page-size compatibility](https://developer.android.com/guide/practices/page-sizes);
- Kotlin concurrency/observation: [coroutine dispatchers](https://kotlinlang.org/docs/coroutine-context-and-dispatchers.html),
  [StateFlow](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/-state-flow/),
  and [SharedFlow](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/-shared-flow/).

Official public API behavior is the implementation boundary. Platform source may inform diagnostics but cannot
create a runtime assumption absent from these contracts or Documents 01–05.
