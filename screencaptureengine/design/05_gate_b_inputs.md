# Screen Capture Engine — Gate-B Inputs

This file is the checklist for implementation planning. It does not define product behavior. Product and public contracts live in
[01_design.md](01_design.md); internal algorithms and ownership rules live in [02_architecture.md](02_architecture.md); required checks live in
[03_verification.md](03_verification.md).

Every item below is a required Gate-B output or an explicit placeholder awaiting that output. This file does not assert that any binding is complete. These
inputs have no authority to narrow release support, change the product contract, add a runtime-selection axis, or block/reopen Gate A. An unsatisfied input
blocks Gate B and implementation authorization. Any support-impacting obstacle is reported to the user/Gate-A authority for an explicit new product decision
outside this file.

## 1. Required implementation manifest

The implementation manifest contains one row for each architecture component/owner boundary. A row may list
multiple planned production and test paths owned by that boundary:

| Field | Required content |
| --- | --- |
| path | Exact repository-relative production and test path or paths. |
| role | Controller, Android lane, target, GL/Direct readback, JPEG, delivery, metrics, diagnostics, allocator/resource bounds, deadline, cleanup, JNI, or test support. |
| owner | The single owner defined in [02_architecture.md](02_architecture.md#61-components-and-owner-model). |
| declarations | Key private types, cells, occurrence records, carriers, receipts, and entry points. |
| dependencies | Allowed inbound and outbound dependencies, including the lane used for every opaque call. |
| contract links | Design and architecture sections implemented by the file. |
| test slices | Unit, race, fault-injection, instrumentation, native, or image-vector checks covering the file. |

The manifest covers the material Kotlin, C/C++, build, packaging, test-support, and vector boundaries. Each
component row identifies its owner, material contract links, and assigned test slices.

The build/package rows bind module placement, package `io.screenstream.engine`, compile/target API 37, Kotlin 2.4.0, kotlinx-coroutines 1.11.0, JVM 17,
explicit-API enforcement, ABI/page-size packaging, source sets, generated inputs, and the exact verification tasks that inspect those outputs.

## 2. Private constants

Each name below requires an exact positive finite value unless the architecture fixes the value already:

- `firstMetricsReadinessNanos`
- `initialCapturedResizeReadinessNanos`
- `androidEnteredOperationSafetyNanos`
- `glEnteredOperationSafetyNanos`
- `jpegEnteredOperationSafetyNanos`
- `maxOldGlErrorsPerFrame`
- `maxFinalGlErrorsPerFrame`

Every readiness and entered-operation deadline named above is positive, finite, private, and non-public. Gate B
presents the exact values for explicit user agreement before implementation authorization. They define no SLA
or additional product timing semantics. The public callback pre-entry deadline and Stats cadence are consumed
unchanged from [01 Product and Public Design](01_design.md) and are not private bindings. Each private constant
row records its unit, numeric type, exact value, arithmetic guard, use sites, and tests. Runtime benchmarks,
soak results, device allowlists, and image scores are not inputs.

## 3. Controller and delivery bindings

The component/owner manifest binds:

- the concrete atomics and transitions for `IDLE`, `RUNNING`, and `RUNNING_DIRTY`;
- the short synchronous public-command gate and immutable Running/terminal snapshot construction;
- desired/revision total ordering, revision exhaustion, the one-inflight/latest desired cell, the exact reconciliation key, and action selection from actually
  owned healthy compatible live scope rather than historical effective-plan equality;
- active-occurrence fencing, stale-clean versus unsafe result handling, and nonrejecting drainer scheduling;
- distinct `OperationReturnCell`, `DeadlineOccurrence`, entered latch, typed fact, carrier, and receipt representations;
- the sole production slot from materialization through final disposition, including completed-unpublished JPEG; the coexisting latest-pending bit retained
  until fresh cadence eligibility; retained `byRateLimit == 0` and deferral without another drop; and the fixed one-active delivery worker, one registration, one handoff
  record, and one encoded lease;
- the production terminal cutoff under `sessionGate -> settlementGate`: fold each already-complete production return and already-selected classified
  disposition through ordinary accounting before final Stats; preserve cache/output commits and existing `byPipelineBusy`, `byStaleWork`, and `byFailure`;
  count an already-classified terminal-causing failure once; and add no frame drop for an otherwise unclassified materialized attempt, a retired
  completed-unpublished JPEG, whole-occurrence transfer, or cleanup-only late return;
- exact implementations of `Prepared`, `Dispatching`, `AcceptedQueued`, `DetachedPreEntry`, `Entered`, `Resolved`, and `Quarantined`;
- dispatcher-return, callback-entry, callback-return, accepted-task timeout, unsubscribe, terminal, and late-cleanup race slices, including unsubscribe/stop detachment while
  `dispatcher.dispatch` is in-call. A later normal dispatch return arms the sole five-second entry/self-rejection deadline from its return sample, which remains the sole
  deadline occurrence for that accepted task.
  Caller-dispatcher throw/rejection increments `byDispatchFailure` only when its disposition commits before trampoline entry. If entry wins and `dispatch`
  later returns or throws, entry owns settlement and the later outcome cannot reclassify the delivery.
- callback admissibility committed under `sessionGate -> settlementGate` against Session/registration closure, with both gates released before user code;
- terminal callback cutoff and cleanup edge: a complete callback-return cell is folded before final Stats and releases its callback/lease side; any unresolved
  dispatch side transfers with only its remaining worker residue. An empty callback cell transfers with its writable cell, borrowed-frame authority, and encoded
  lease; late return or throw releases only that exact residue and changes no counter,
  `DeliveryProblem`, or State. An actual `SessionQuarantineRoot` ownership change still attempts the ordinary mandatory `QuarantineChanged`; cleanup before
  quarantine emits none;
- inline callback return before dispatch-call return: release frame authority/lease immediately, retain the sole record/worker capacity until the actual
  dispatch result, classify a new opportunity as `byConsumerBusy`, create no second worker or scheduler-failure path, and transfer only remaining dispatch
  residue if terminal wins in that gap;
- unsubscribe closes admission but waits for every side of the handoff, including an in-call dispatch side after callback/frame settlement; replacement remains
  forbidden until dispatch return/throw permits successful unsubscribe completion;
- fresh-attempt outcome precedence: mechanically returned production failure is `byFailure` even for stale identity, `byStaleWork` is otherwise successful
  stale suppression, safe stale failure changes no lifecycle result, and unsafe failure remains terminal.
- one controller/current-wake carrier, separate from Stats cadence, for the earliest future eligibility among pending fresh work, completed-unpublished JPEG,
  and repeat; at most one queued scheduler submission plus one already-dequeued stale callback during replacement; cancel/remove/coalesce before posting the
  current submission; identity consumption before resampling; current-only successor authority; and at most one action plus one successor, with no timer queue,
  stale burst, catch-up burst, or stranded pending work. Current required submission rejection is terminal `InternalFailure` without a drop;
  stale/detached/terminal rejection is cleanup-only.

Every entered opaque, system, or ownership-sensitive operation binds one operation-local `settlementGate`, a
precreated one-shot `OperationReturnCell`, and an owner bag. Each finite deadline-governed operation additionally binds its
identity-fenced `DeadlineOccurrence`; operations governed by caller-dispatch, callback, metrics, or no-watchdog Surface rules
bind their named completion rules. Before opaque entry, the operation record and cell provide the
return discriminator and fixed fields for existing reference, scalar, tag, throwable, and receipt values. After
opaque return, the worker writes only those values into the fixed fields. Object, string, and wrapper creation,
diagnostics, and other follow-on allocation occur after complete settlement publication and gate release. The
operation record retains its gate, writable cell, owner bag, and applicable deadline occurrence until the
accepted result is fully applied or the unresolved record reaches cleanup or quarantine.

The lock order is `sessionGate -> settlementGate`. A returning worker uses only its operation's
`settlementGate`: it prepares the fixed fields, acquires the gate, and commits the completely populated one-shot
return cell before releasing the gate and signalling the controller. For a finite deadline-governed occurrence,
the worker samples monotonic time `T` immediately before that commit. `T` is the settlement linearization sample;
`T < D` is timely and `T >= D` is late. Gate exclusion preserves that decision if the worker is paused after
sampling `T` and before completing the commit. A nondeadline occurrence is consumed by its named completion rule.

The controller arbitrates under `sessionGate -> settlementGate`. For a finite deadline-governed occurrence, a
populated cell with `T < D` applies its timely outcome and retires the applicable deadline. A populated cell with `T >= D` commits expiry and retains
its real fact or receipt for cleanup only. An empty cell at `now >= D` commits expiry. Publication after expiry
or retirement reduces exact cleanup or quarantine residue without changing the settled outcome. A nondeadline
occurrence applies its named completion rule to a complete cell. Safely cancellable unentered operations resolve
directly; mandatory cleanup occurrences retain their exact owner and specialized one-shot obligation. Terminal
retirement transfers each unresolved entered, in-call, or accepted
operation together with its gate, writable cell, owner bag, and applicable deadline occurrence, preserving
later worker publication for cleanup. Cleanup retains the intact record through late-return disposition and
every proven dependent cleanup; quarantine retains only residue that cannot be resolved safely. Gate-held
sections are limited to cell publication and arbitration over existing references, tags, identities, and scalar
values.

Engine-scheduler rejection settles only the current unresolved scheduler submission while the operation is
unentered and its entry and return cells are empty. An existing cancellation, terminal, entry, return, or cleanup
disposition remains authoritative. Rejection of a mandatory cleanup submission preserves the exact owner and
specialized cleanup obligation. For the active delivery-worker submission, a rejection that wins before detachment or terminal supplies
`Failed(InternalFailure)`, safely resolves the unentered delivery record/lease in terminal cleanup, and records no `byDispatchFailure`; if detachment or terminal
won first, that rejection is cleanup-only. A caller dispatcher return, throw, or rejection always records its real
dispatcher fact; trampoline entry remains
a distinct entry fact. Delivery arbitration consumes only recorded dispatcher and trampoline-entry facts. An
unresolved in-call or accepted delivery occurrence follows the same intact-record transfer and late-cleanup
rules.

An accepted-task entry deadline remains attached through terminal detachment. Expiry while the Session is nonterminal commits `Failed(InternalFailure)`;
expiry after `Stopped` or another terminal State roots only the exact unresolved delivery residue and leaves State unchanged. Caller-dispatcher call duration
has no watchdog. The supported dispatcher contract requires each `dispatch` invocation eventually to return or throw; otherwise the sole handoff remains
occupied until a real return or terminal cleanup transfer. Late dispatcher and trampoline facts reduce only their exact residue.

System/JNI/GL calls, callbacks, release or cleanup calls, scheduling, diagnostics, Flow publication, and
follow-on owner transitions run after the applicable gates are released.

## 4. Platform, runtime-service, GL, and JPEG call bindings

### 4.1 Android lane

Bind the concrete Handler/executor and adapters for every MediaProjection callback and target listener. The sole Android lane owns the exact calls for:

- `MediaProjection.registerCallback` and `unregisterCallback`;
- `MediaProjection.createVirtualDisplay` with null `VirtualDisplay.Callback` and null callback Handler, and `MediaProjection.stop`;
- `VirtualDisplay.resize`, `setSurface`, and `release`;
- projection resize, visibility, and `MediaProjection.Callback.onStop` fact conversion; `onStop` is the sole platform callback authority for `CaptureEnded`;
- target listener installation/removal, non-reused `targetGeneration` capture/check, stale-callback cleanup-only disposition, exhaustion, and same-Handler
  sentinel ordering.

The manifest preserves the cleanup dependency `unregisterCallback -> VirtualDisplay.release -> MediaProjection.stop`. The closed target dependency before
`Surface.release()` is exactly: fresh/repeat/delivery admission is closed; all entered target work is drained; `targetGeneration` is fenced; the target listener
is removed and its same-Handler sentinel is recorded; exact target detachment is proved by the current `VirtualDisplay.setSurface(null)` normal return or the
applicable current `VirtualDisplay.release` normal-return receipt; and target leases are zero. `CurrentTarget` is the logical owner of the Surface,
SurfaceTexture, target GL objects, release occurrence, and dependent carriers. It then submits exactly one `Surface.release()` occurrence to the
existing Session-private serial GL lane;
the lane executes the call and receives no ownership. Retired-generation listener facts and late returns from other Android target-operation occurrences are
cleanup-only and cannot satisfy a current prerequisite; there is no additional callback-drain prerequisite. The binding uses the generic
`OperationOccurrence`, typed return cell, `settlementGate`, deadline, owner bag, and cleanup transfer from Section 3, with Surface normal return as its typed
receipt; it introduces no Surface-specific state machine. A startup/runtime occurrence that enters while
nonterminal uses the existing `androidEnteredOperationSafetyNanos` boundary. An occurrence created after terminal or converted before entry has no watchdog; terminal winning
after entry retires the active deadline without creating a duplicate occurrence.

The binding distinguishes normal return published before, exactly at, and after the active deadline, returned throw, empty return cell at deadline, nonreturn,
and every late result. Normal return is always the physical Surface receipt, but only settlement publication with `T < D` may continue current
startup/runtime work. Throw or timeout while
nonterminal supplies an `InternalFailure` candidate subject to the fixed terminal priority, roots unresolved dependent ownership, and forbids replacement or
fallback. A late normal return permits cleanup only; a late throw leaves Surface ownership unresolved. Unrelated cleanup roots continue. Submission rejection
settles only the current unresolved GL-lane submission while release is unentered and its entry and return cells are empty. The exact Surface owner and its
mandatory one-shot cleanup obligation remain attached; scheduler nonprogress retains the general no-start-timer exclusion.

### 4.2 Metrics, SurfaceTexture, clock, scheduling, and observations

Bind exact owners, lanes/dispatchers, arguments, returned facts, cancellation, and receipts for:

- `CaptureMetricsProvider.observe()` invocation and Flow collection, collector cancellation request, mechanical collector-return receipt, and the pre-Active
  valid-then-invalid geometry/density disposition `Failed(CaptureUnavailable)` with matching `start` exception;
- built-in metrics access through `DisplayManager.getDisplay(Display.DEFAULT_DISPLAY)`, `Display.getRealSize`, display `Configuration`,
  `WindowManager.getMaximumWindowMetrics`, `Context.createDisplayContext`, UI/window Context association, and API-specific callback adapters;
- `SurfaceTexture(textureName, false)`, `setDefaultBufferSize`, `setOnFrameAvailableListener`, listener removal/sentinel, `updateTexImage`,
  `getTransformMatrix`, `getDataSpace`, and `release`;
- `SystemClock.elapsedRealtimeNanos()`, checked duration conversions, `Handler.post`/`postDelayed`, selected coroutine dispatch/delay boundaries, delayed wake
  identities, and scheduler-rejection behavior;
- the controller/current-wake carrier selecting the earliest future pending-fresh, completed-unpublished, or repeat eligibility; physical cardinality of one
  queued pacing/repeat scheduler submission plus at most one already-dequeued stale callback during replacement; cancel/remove/coalesce before current post;
  identity-fenced dequeue/terminal behavior; current-only successor authority; and current versus stale rejection disposition. The Stats-cadence wake remains
  separate;
- `MutableStateFlow`/`MutableSharedFlow` construction, immutable State/Stats assignment, diagnostic `tryEmit`, replay/capacity/overflow configuration, collector-facing
  publication sites, and terminal observation order.

### 4.3 EGL, render target, and Direct readback

Bind exact formats, arguments, error checks, and destruction calls for:

- EGL display/config/context/pbuffer creation, binding, unbinding, and destruction without `eglTerminate`;
- OES texture, ESSL 1.00 transform program, canonical state, and GLES2 core `internalformat = GL_RGBA`, `format = GL_RGBA`,
  `type = GL_UNSIGNED_BYTE` RenderTarget allocation arguments, framebuffer attachment, and completeness checks, with checked `B = 4*Ow*Oh` retained only as
  the exact required CPU byte count rather than an estimate of opaque GPU storage;
- Direct `glReadPixels`, exact GL error partitions, return receipts, and destruction receipts.

### 4.4 Framework JPEG

Bind one Framework adapter shared by native and managed tight-RGBA carriers. It receives an exact-range
`ByteBuffer` view and owns one reusable mutable non-HARDWARE sRGB `ARGB_8888` Bitmap plus, only when the tight
copy guard does not hold, one reusable `IntArray(Ow)` row. Bind exact `rowBytes`, `byteCount`, position/limit and
ByteBuffer guards; one `copyPixelsFromBuffer` fast transfer; row `setPixels` without a full-frame temporary
array; `Bitmap.compress`; transaction calls; and small channel, row, alpha, odd-width, and guard vectors. Define practical decoded-image tolerances;
lossy JPEG pixels and encoded bytes are never required to be equal.
Bind the final Framework outcome partition: `Bitmap.compress` false is one-frame `byFailure` with later frames
continuing; Bitmap/sink memory exhaustion is terminal `ResourceExhausted`; unexpected exception or malformed
sink result is terminal `InternalFailure`; ambiguous ownership is terminal and quarantined. No outcome publishes
partial bytes or retries the same frame.

Bind same-shape Bitmap and row-scratch reuse. Before incompatible Bitmap replacement or terminal retirement, every copy/compress use and lease settles and the
encoder consumes the exact owner into one generic `OperationOccurrence` on its existing execution lane. `Bitmap.recycle()` is called exactly once. A
preterminal occurrence uses `jpegEnteredOperationSafetyNanos`; normal return is the sole recycle receipt and must precede reference drop and replacement
allocation. Terminal cleanup uses the same generic occurrence without a watchdog. Rejection, throw, expiry, or nonreturn retains the exact owner/occurrence
under cleanup/quarantine, authorizes no replacement, and creates no retry, duplicate recycle call, Bitmap-specific state machine, or new constant.

### 4.5 Native JPEG

Bind the Kotlin/JNI/C layout and narrowing for every `NativeFrameDescriptor` and `AndroidBitmapInfo` field; module-local NDK-28 weak-API binding for
`AndroidBitmap_compress`; ABI packaging with 16-KiB page-size compatibility; writer callback validation; transactional sink ownership; and native return, fallback,
timeout, and late-return slices. Runtime Native eligibility is the closed own-bridge/API/ABI/guarded-weak-address set. The first
real frame is the first Native compression call and receives the full outcome partition. Page-size compatibility is a packaging requirement.

The engine native module links the system `jnigraphics` library and enables `ANDROID_WEAK_API_DEFS=ON` for the approved NDK 28, while keeping
unguarded-availability diagnostics as errors. This is only a native-module binding and adds no root-project Gradle, Android Gradle Plugin, or Kotlin-plugin
wiring. The library dependency itself is not weak, so its direct `DT_NEEDED` entry may map the system `libjnigraphics.so` when the engine JNI library loads on
API 24–29 or under `FrameworkOnly`; the unknown incremental resident cost is disclosed without predictive memory accounting.

`FrameworkOnly` never evaluates or invokes the platform compressor. Under `Auto`, both the preparation capability query and every actual native-compression
entry call the same native helper. That helper performs the nested `__builtin_available(android 30, *)` check and, only inside that scope, takes
`&AndroidBitmap_compress` and requires the resulting address to be nonnull. API 24–29 never enter that guarded scope or invoke the compressor. On API 30+, a
null weak address during preparation is clean static Native ineligibility and selects Framework before any fresh attempt is assigned to Native. Preparation
publishes only Boolean `Enabled` or `Disabled` Native health; no function address crosses JNI or persists in Session or writable native state.

After `Enabled` evidence, each actual native-compression entry resolves the same guarded weak address again, passes the locally typed non-owning function
pointer by value into the JNI-free runtime for that call, and discards it afterward. A null address at this pre-invocation recheck is defensive
`InternalFailure`, not clean ineligibility or safe Native disablement, and the compressor is not invoked. There is no per-Session `dlopen`, `dlsym`, `dlclose`,
compensating or final close, platform-library handle, compressor capsule, compressor lease, or compressor-close obligation.

Bind one Session-fixed carrier mode and one Session-monotone Native-JPEG health result. The only legal
combinations are native carrier plus Native enabled, native carrier plus Native disabled, and managed carrier
plus Native disabled. Engine-library loading is ownership-free until successful native-bridge publication:
loading and `JNI_OnLoad` create no Session, carrier, sink, or other session-owned native resource. Only a
synchronous `UnsatisfiedLinkError` or `SecurityException` before bridge publication and before any engine JNI
operation entry, with zero native ownership, selects managed carrier plus Framework JPEG. Load-time
`OutOfMemoryError` is terminal `ResourceExhausted`; partial initialization, failure after bridge publication or
engine JNI operation entry, any other throwable, or ambiguous ownership is terminal `InternalFailure` and
permits no fallback. Clean static Native capability/symbol ineligibility after the engine library is available
preserves native carrier ownership and disables only Native JPEG. Managed carrier plus Native enabled is
rejected by construction.

A safe Native failure for the current key, completely published through its settlement gate with `T < D`,
records `byFailure`, disables Native, and selects the common Framework adapter for later frames. A key-stale failure completely
published with `T < D` records `byFailure` and publishes no stale frame or lifecycle failure, but it still disables the matching current Session
Native-health occurrence and reconciliation proceeds for the latest key. A result published with `T >= D`,
after expiry has committed, or for a retired health occurrence is cleanup-only and cannot change Native health.
`ANDROID_BITMAP_RESULT_ALLOCATION_FAILED` is such a safe optional-backend result only when the call returned,
its complete outcome was published with `T < D`, and the exact carrier and transactional sink are settled.
Carrier allocation OOM, sink allocation OOM, partial engine-library load, Native nonreturn, or ambiguous
carrier/sink/writer ownership is terminal and permits no fallback. No safe failure retries the same frame
through Framework.

When one call reports more than one fault, unsafe or ambiguous ownership, a malformed writer callback, a writer-contract
violation, or a non-OOM JNI exception takes precedence as terminal `InternalFailure`; otherwise an exact writer/sink/JNI
OOM takes precedence as terminal `ResourceExhausted`; only then may the compressor result be classified.
`ANDROID_BITMAP_RESULT_ALLOCATION_FAILED` permits Native-to-Framework disablement only when no writer or JNI fault occurred.

## 5. Resource bounds and owner bindings

Bind checked sizes and narrowing, hard API/backend limits, actual allocator/platform results, one active
target/pipeline topology, one stable CPU RGBA carrier, exact-compatible owner reuse, smallest-incompatible-scope replacement, and exact
owner/lease/receipt/quarantine edges. Using only the existing desired revision and current topology, drain and fence affected work, invalidate cache/repeat,
retain every healthy exact-compatible target, FBO/texture, carrier, Bitmap/scratch, and JPEG owner, and retire only the incompatible scope. This binding adds no
resource registry, alternate planner, replacement generation, or rollback pipeline. The
closed encoded-storage roles remain exact owners of their real backing bytes.

Target replacement retires the target and its physical dependents but does not by itself retire healthy exact-compatible output owners. When output shape and
requirements remain compatible, the FBO/texture, CPU carrier, Bitmap/scratch, and JPEG owners retain object identity across target replacement.

Every allocation site records its real result, partial-construction owner transfer, lease, and release edge.
Tests cover every material addition, multiplication, narrowing, buffer/row range, segment cumulative-length
acceptance, actual OOM, and safe or ambiguous partial return. Allocator outcomes alone decide allocation
success; managed-reference retirement never fabricates a physical-release receipt.

The owner/dependency manifest includes these roots:

| Root | Required binding |
| --- | --- |
| Android capture | Serial callback unregister, VirtualDisplay release, and projection stop receipts. |
| Current target | `CurrentTarget` ownership; the exact closed prerequisites in Section 4.1; cleanup-only retired-generation listener facts and other-operation returns; existing serial GL-lane Surface execution; preterminal Android-operation deadline versus terminal no-watchdog conversion; Surface return; exact target/GL/EGL/lane residue; and dependent destruction receipts. |
| JPEG/storage | Encoder occurrence, transactional sink, CPU/raw carrier, encoded roles, and displaced leases. |
| Frame consumer | Prepared resolution, detached pre-entry resolution, entered return, and exact quarantine residue. |
| Metrics | Cancellation request plus the distinct mechanical collector-return receipt. |
| Deadlines | Identity-fenced wake/deadline retirement and late occurrence cleanup. |
| Allocator/diagnostics | Native explicit-free receipt where applicable, managed-reference retirement without a false physical-free receipt, and diagnostic-reference retirement. |
| EGL session | Target-dependent GL destruction followed by context and pbuffer teardown. |

## 6. Diagnostics and observation bindings

Bind the creation sites for exactly the eight required diagnostic categories in [01_design.md](01_design.md#5-diagnostics-contract): `CapabilityCheck`,
`RuntimeProfile`, `RuntimeModeChanged`, `DeliveryProblem`, `StatsProblem`, `ColorAction`, `QuarantineChanged`, and `SessionTerminal`. Each event assigns the next Session-local
`sequence`, samples wall-clock `timestampEpochMillis`, and supplies `source`, `label`, `message`, and optional raw `cause`. Also bind replay 0, capacity 128,
drop-oldest behavior, direct `tryEmit`, State/Stats assignments, visibility-change conflation, Stats finite-value protection, and the absence of routine
geometry, visibility, rebuild, consumer-lifecycle, or per-frame diagnostic requirements. Tests check sequence ordering, fields, and message semantics without
requiring literal message wording; timestamp is correlation data, not an ordering or control clock.

The pre-Active valid-then-invalid metrics case binds source `MetricsProvider`, label `CapabilityCheck`, a short message semantically identifying startup metrics
loss, and the raw nullable boundary cause; the cause-free injected invalid-value case expects null `cause`. Its terminal commit also binds source `Session`,
label `SessionTerminal`, a short message identifying `CaptureUnavailable` and the absence of prior active modes, and null `cause`.

Diagnostics tests bind the first attempted event to sequence 1 and advance sequence before every `tryEmit`, so
drop-oldest overflow can appear as collector-visible gaps. They bind `timestampEpochMillis` to the wall-clock
sample taken at event creation and verify that repeated, forward, or backward wall-clock values never provide
ordering or control authority.

## 7. Required test slices

Gate B assigns a source path and runner to concise checks for:

- one-shot start, lifecycle, latest-wins parameter updates, terminal behavior, and the destructive A-to-B-to-A race where historical A equality cannot restore
  Active until a compatible healthy A topology is live again;
- metrics and API-specific geometry/visibility handling, including valid startup metrics followed by invalid geometry/density before initial Active;
- transforms, grayscale/color behavior, JPEG structure, decoded dimensions/orientation, and obvious channel/row corruption using small vectors and declared
  lossy tolerances;
- pacing, static repeat, cache reuse, sole production-slot retention through completed-unpublished JPEG, pending-bit coexistence without a second attempt,
  early-source retention through fresh eligibility with no drop counter, and policy/candidate churn proving at most one queued pacing/repeat submission plus one
  already-dequeued stale callback, cancel/remove/coalesce-before-post, current-only successor authority, no stale burst or stranded pending work, and exact
  current/stale scheduler-rejection disposition; plus Stats accounting including returned-failure-over-stale precedence;
- production-terminal cutoff races before and after complete return-cell commit, covering normal accounting of a completed return or selected disposition,
  retained cache/output and classified-drop results, one counted terminal-causing `byFailure`, zero drops for unclassified terminal retirement and retired
  completed-unpublished JPEG, whole-occurrence transfer, cleanup-only late return, and unchanged final Stats after transfer;
- Full/Downscaled eligibility, smaller-Surface fit/center behavior, its one safe Full fallback, fixed Direct readback, and the independent Framework/Native
  fallback axis;
- single-consumer registration, unsubscribe/re-register, dispatcher/callback failures, and the five-second pre-entry deadline;
- dispatcher throw/rejection committing before entry as `byDispatchFailure`, versus entry winning before a later `dispatch` return or throw with no
  reclassification;
- inline callback return while dispatch remains unresolved: immediate lease release, occupied record/worker, intervening `byConsumerBusy`, no second
  scheduling attempt, suspended unsubscribe and forbidden replacement, and eventual dispatch-side settlement before unsubscribe success;
- callback admissibility under `sessionGate -> settlementGate` versus unsubscribe/terminal closure, gate release before user code, complete callback return
  folded before final Stats, and whole-occurrence transfer followed by cleanup-only late return/throw with `QuarantineChanged` only for an actual root change;
- active delivery-worker scheduling rejection as terminal `InternalFailure` without `byDispatchFailure`, crossed with prior detachment, terminal, entry, and
  cleanup dispositions;
- the sole VirtualDisplay creation call's null callback/Handler arguments, `MediaProjection.Callback.onStop` as sole platform `CaptureEnded` callback authority,
  and explicit VirtualDisplay release during cleanup;
- the exact closed `Surface.release()` prerequisite set in Section 4.1, including rejection of retired-generation listener facts and other-operation returns, and CurrentTarget
  ownership versus GL execution; startup/runtime and post-terminal paths; normal return before,
  exactly at, and after its active deadline; throw, nonreturn, late normal/throw, terminal-before-entry and terminal-after-entry races, stale desired/geometry
  keys, Downscaled-to-Full fallback gating, GL submission rejection, independent Android cleanup, and cross-Session GL-lane isolation;
- checked resource bounds, arithmetic overflow, actual allocation/OOM outcomes, stale-work fencing, cleanup, and quarantined late returns;
- exact-compatible target/FBO/texture/carrier/Bitmap/scratch/JPEG-owner reuse and smallest-incompatible-scope replacement using existing revision/topology
  authority, with no registry, alternate planner, replacement generation, rollback pipeline, or second healthy topology;
- target replacement with unchanged output shape/requirements, proving replacement of target dependents while every healthy exact-compatible FBO/texture,
  carrier, Bitmap/scratch, and JPEG owner retains object identity;
- same-shape Bitmap reuse and incompatible/terminal Bitmap retirement after all copy/compress uses settle: one generic exactly-once `recycle()` occurrence,
  preterminal `jpegEnteredOperationSafetyNanos`, terminal no-watchdog cleanup, normal-return receipt, rejection/throw/expiry/nonreturn retention, no retry, and
  return-versus-terminal transfer races;
- settlement-gate arbitration for every entered opaque/system operation and delivery return/rejection: complete
  allocation-free cell publication before controller application; finite-deadline cases with a worker paused before gate entry across `D`;
  a worker paused after sampling `T` and before commit; controller wake after a timely `T < D` publication;
  populated-cell `T == D` and `T > D` expiry with cleanup-only facts; empty-cell expiry at `now >= D`;
  publication after expiry or retirement; current unresolved safely cancellable unentered scheduler rejection;
  mandatory-cleanup owner preservation; finite-deadline versus
  caller-dispatch, callback, metrics, and no-watchdog completion rules; terminal intact-record transfer; caller dispatcher
  return/throw/rejection facts; separate trampoline entry; delivery entry/dispatch-return competition; late
  receipt publication into transferred cleanup/quarantine ownership; exact quarantine residue; operation-record
  lifetime; post-settlement diagnostic allocation; and enforcement of `sessionGate -> settlementGate` ordering;
- all three legal carrier/Native-health combinations; the common Framework adapter in both carrier modes; current and stale safe Native disablement;
  clean pre-JNI engine-library unavailability versus load OOM/partial/post-publication/ambiguous failure; closed own-bridge/API/ABI/guarded-weak-address eligibility,
  Boolean-only Native health; the shared guarded helper at preparation and every actual Native call; `FrameworkOnly`; API 24–29 non-evaluation/non-invocation;
  API 30+ preparation null-address ineligibility; an Enabled-then-null pre-invocation `InternalFailure`; and first-real-call outcomes;
  `ANDROID_BITMAP_RESULT_ALLOCATION_FAILED` with exact settled ownership; combined writer/JNI/compressor faults and their precedence;
  `T >= D`, already-expired, and retired-health cleanup-only results;
  terminal carrier/sink OOM and unsafe ownership; and no same-frame retry;
- diagnostics sequence start/attempt ordering, creation-time wall-clock sampling, and required fields, without comparing event objects or requiring every
  best-effort event to survive buffer overflow;
- JNI layout, native writer validation, NDK-28 weak-symbol/availability guards, Boolean health with no persisted function address, per-call local typed pointer
  passage into the JNI-free runtime and discard, linked-`jnigraphics` ELF dependency evidence, absence of dynamic compressor-handle ownership and close paths,
  ABI packaging, and 16-KiB page compatibility.

The checks use unit, race/fault-injection, instrumentation, native, and small image-vector tests where each is useful. JPEG verification is semantic and
structural; it never compares independently encoded lossy output pixel-for-pixel. Exact vectors and practical tolerances are selected during Gate B.

## 8. Gate-B completion

Gate B is complete when every component owner, private constant, material platform/native boundary, and required test slice above has a concrete binding and the
result remains consistent with [01_design.md](01_design.md), [02_architecture.md](02_architecture.md), and [03_verification.md](03_verification.md). A short manual
smoke run on an available physical device happens after implementation as an informal developer check. Runtime selection continues to use the static rules in
the design and architecture.
