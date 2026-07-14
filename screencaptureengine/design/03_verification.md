# Screen Capture Engine — Verification

## 1. Purpose

This document defines how the behavior in [01 Design](01_design.md) and the implementation boundaries in
[02 Architecture](02_architecture.md) will be verified. Those documents remain the product and architecture
authority. A test checks their observable result; it does not introduce another lifecycle, fallback, timing,
memory, or platform rule.

Gate A reviews the scenarios and high-risk interleavings below for an unambiguous implementable result.
Executable tests and a short practical device smoke check follow implementation authorization.

The verification approach is deliberately small:

- deterministic unit tests for public values, controller decisions, pacing, geometry, accounting, and
  failure classification;
- controlled interleaving tests for the few races that can change a public result or resource owner;
- component tests for Android, EGL/GL, JPEG, dispatcher, allocation/ownership, and cleanup boundaries;
- integration tests for representative end-to-end capture paths;
- one short post-implementation manual smoke pass on available physical devices.

Tests use fakes, barriers, and an injected engine clock where determinism matters. Correctness tests do not
depend on real sleeps, scheduler luck, log parsing, or repeated execution until a race happens.

## 2. Acceptance Scenario Map

The following 27 scenarios are the material observable contract. A scenario may be covered by several small
tests; it does not imply one large end-to-end test.

| Scenario | Required observation |
| --- | --- |
| 1. Public surface | The packaged public declarations, visibility, defaults, ordinary-class value semantics, and documented exceptions match 01 Design. Engine-produced value constructors remain internal. |
| 2. Parameter validation | Invalid scalar or structural input fails with `IllegalArgumentException` before capture work; valid boundary values are accepted without clamping. |
| 3. One-shot start | Exactly one `start` call can be accepted. A repeated or concurrent loser does not access, stop, or otherwise consume its supplied `MediaProjection`. |
| 4. Startup outcome | Successful `start` returns only after a visible `Running` state. Startup failure or caller cancellation produces the documented terminal state and exception. |
| 5. Stop | `stop()` synchronously fixes the terminal winner and closes new admission, then State publication and cleanup may finish asynchronously. Repeated calls are harmless. |
| 6. Latest-wins update | In any nonterminal `Running` state, an unequal `updateParameters` replaces the desired parameters and returns immediately. Equal desire is a no-op. Concurrent updates have one order; only the latest accepted desire must converge after input changes stop. |
| 7. Update reconciliation | Policy-only and density-only changes preserve eligible resources. Active requires an actually owned healthy compatible live topology; a historical effective plan cannot substitute for retired resources. A destructive change pauses output, drains and retires the old scope, builds one replacement, and resumes. A deterministic geometry/API/backend limit discovered before retirement suspends without allocating; a current required allocation failure after retirement is terminal; stale safe results cannot publish. |
| 8. Metrics authority | The selected provider supplies positive density and the documented display association. The sole public built-in is an immutable reusable provider fixed to its exact `Display`; the engine observes the configured provider itself, a null configuration creates a Session-private default-display provider, and specialized policies use a custom provider unchanged. API 24–33 use provider dimensions; API 34–37 wait for authoritative projection resize dimensions. |
| 9. Metrics loss and recovery | A missing runtime authority, including an invalid exact Display, suspends with `CaptureUnavailable`; a later valid fact resumes. A provider failure is classified as documented, and stale geometry cannot replace the current generation. |
| 10. VirtualDisplay result | `createVirtualDisplay` null or `SecurityException` becomes `CaptureUnavailable`; a directly thrown `OutOfMemoryError` becomes `ResourceExhausted`; `IllegalStateException` and any other unexpected throwable become `InternalFailure`. Exactly one creation attempt is made for the Session. |
| 11. Geometry and pixels | Region, crop, rotation, mirror, output size, OES transform, orientation, color, and grayscale produce the documented image dimensions, orientation, and semantic transform through Full and every eligible Early Surface Downscale path. Downscaled output may contain ordinary filtering, rounding, and minor platform differences. |
| 12. Color handling | sRGB remains nominal sRGB; exact Display-P3 takes the documented conversion path; wide/HDR input follows the documented best-effort behavior and emits the required diagnostic observation. |
| 13. Fresh-frame pacing | `Auto`, `MaxFps(1..120)`, and `SampleEvery(1_001..3_600_000ms)` admit fresh work according to their separate contracts using elapsed realtime. An early signal remains the sole latest-pending source until eligible and is not counted as dropped. At most one pacing/repeat submission is queued, with at most one already-dequeued stale callback during replacement; a current wake causes at most one action and one successor, with no catch-up burst or stranded pending work. |
| 14. Static repeat | A configured repeat interval of `1_000..3_600_000ms` republishes only a valid cached JPEG, with new sequence and timestamp, without GL or JPEG work. Fresh output wins ties and `MaxFps` remains the output cap. |
| 15. Cache and Stats | A fresh encode, repeat, cached-first offer, rejected delivery, and terminal transition update cache and counters exactly as documented. A callback result committed before terminal arbitration is included in final Stats; one committed only after whole-occurrence transfer is cleanup-only. Stats publish no more than once per second plus important transitions. |
| 16. Baseline output | The Full GLES2 Direct-readback plus Framework-JPEG baseline on API 24–37 produces a decodable JPEG of the exact documented dimensions and orientation and does not expose raw or partial pixels. |
| 17. Independent optimized paths | Early Surface Downscale, Native JPEG, and Display-P3 are selected only by their documented static/runtime capability checks. Eligible Downscale is automatic on API 32–37. Target and JPEG health remain independent; a safe failure disables only the affected optimized path for later frames. |
| 18. Safe and unsafe fallback | A safely returned optional-path failure may drop the affected frame and at most one switchover frame, then uses the documented fallback without double encoding. Ambiguous ownership, nonreturn after possible transfer, or unsafe cleanup is terminal and quarantined. |
| 19. Consumer registration | The Session has zero or one current-or-draining frame consumer. A second registration fails synchronously until the previous subscription has successfully unsubscribed. |
| 20. Cached-first delivery | A new registration receives the current valid cached JPEG immediately when one exists, preserving its original bytes, sequence, timestamp, and `ImageSize`. This does not encode, increment `framesProduced`, or alter pacing. |
| 21. Delivery outcomes | Callback return settles and releases only the callback/frame side; the handoff is reusable only after its separate dispatch-call side also settles. Caller rejection records `byDispatchFailure` only when it commits before entry. If inline callback return precedes dispatch resolution, the occupied gap records new opportunities as `byConsumerBusy`. Active engine delivery-worker scheduling rejection is terminal `InternalFailure` without `byDispatchFailure`; rejection after detachment or terminal is cleanup-only. |
| 22. Unsubscribe | `unsubscribe()` immediately closes new delivery, is idempotent, and waits for the full handoff, including an unresolved in-call dispatch side after callback return. Callback return releases its frame/lease but cannot permit replacement before dispatch returns or throws. Successful return permits replacement registration; self-unsubscribe fails fast; waiter cancellation does not fabricate settlement. |
| 23. Five-second entry deadline | An accepted callback task that has not entered within 5 seconds terminally fails the Session. A late trampoline invokes no user code but may provide its exact resolution. An entered callback has no execution watchdog. |
| 24. Resource bounds and privacy | Checked sizes and narrowing, hard API/backend limits, one active resource topology, exact-compatible owner reuse, smallest-scope replacement, and exact ownership bound engine work. Actual carrier, Bitmap, sink, and platform allocation outcomes decide feasibility. Raw, partial, stale, or unleased data never publishes. |
| 25. Terminal cleanup | Only one terminal winner is published. Independent cleanup continues in dependency order; an unresolved resource remains rooted once in the Session quarantine instead of being treated as released or reusable. |
| 26. Diagnostics | Diagnostic events carry monotonically increasing Session-local `sequence`, `timestampEpochMillis`, `source`, `label`, short semantic `message`, and raw nullable `cause`. The eight required categories cover capability decisions, runtime profile/mode changes, delivery problems, Stats protection, color action, quarantine changes, and terminal outcomes; diagnostics never decide lifecycle or business behavior. |
| 27. Session isolation | Multiple Sessions share no lifecycle, projection, desired parameters, cache, callback registration, metrics runtime state, counters, failure, or quarantine authority, including when they reuse one built-in metrics provider. Aggregate process pressure remains the application's responsibility. |

## 3. Common Test Rules

### 3.1 Time

Pacing, repeat, Stats cadence, readiness, operation deadlines, and callback entry deadlines use one injected
clock corresponding to raw `SystemClock.elapsedRealtimeNanos()`. Test cases advance that clock directly and
then run the relevant wake. They cover a fact just before, at, and after a deadline where the distinction
changes behavior.

A timer or coroutine delay is only a wake request. On wake, the engine resamples elapsed realtime and
rechecks current state and generation. Tests change wall time, producer timestamps, and delayed scheduling to
confirm that none becomes timing authority. Deep sleep is represented by advancing elapsed time without
executing work, then delivering one wake; the result is at most one current action.

The fixed externally visible timing rules are:

- callback task entry deadline: 5,000 ms;
- Stats periodic publication: no more often than every 1,000 ms;
- `MaxFps`: 1 through 120;
- `SampleEvery`: 1,001 through 3,600,000 ms;
- optional repeat: 1,000 through 3,600,000 ms.

Other readiness and entered-operation deadlines are positive finite, non-public Gate-B constants. Gate B
presents their exact values for explicit user agreement before implementation authorization. They create no
SLA or additional product timing semantics; tests use an agreed value only to verify the existing timely and
timeout dispositions.

Every entered opaque, system, or ownership-sensitive operation test uses the operation's unique `settlementGate`, precreated typed
one-shot `OperationReturnCell`, and owner bag. An operation designated as finite deadline-governed additionally
binds its `DeadlineOccurrence`. Before opaque entry, the occurrence has its fixed discriminator and fields for
returned references, scalar values, throwable, and receipt. After opaque return, the worker fills those fields
from already-owned evidence, enters `settlementGate`, and commits the complete slot. A finite deadline-governed
operation samples the engine clock as `T` immediately before that commit; `T` is its settlement linearization
sample. A nondeadline operation applies its named completion rule. The return path
creates no objects, strings, wrappers, or
diagnostic payloads until the complete slot is committed and the gate is released.

For a finite deadline-governed occurrence, the controller acquires `sessionGate` and then that occurrence's
`settlementGate` and verifies this complete transition table under the gate:

| Slot/deadline observation | Required transition |
| --- | --- |
| Complete slot with `T < D` | Apply the timely typed outcome and receipt. |
| Complete slot with `T >= D` and uncommitted expiry | Commit expiry and retain the real returned fact and receipt for its exact cleanup disposition. |
| Empty slot with sampled `now >= D` | Commit expiry while preserving the writable slot and owner bag for a mechanically late return. |
| Expired or retired deadline followed by return commit | Preserve the committed disposition; use the late fact and receipt only to reduce exact cleanup or quarantine residue. |

Equality belongs to expiry. A complete slot with `T < D` is timely even when the worker is preempted after the
sample and before committing the slot, because the worker still owns `settlementGate` throughout that interval.

The common finite-deadline race suite covers these exact orders:

| Interleaving | Required result |
| --- | --- |
| Worker commits with `T = D - 1`, then controller handles the wake | The committed timely return wins. |
| Worker commits with `T = D` | Expiry wins; the return remains available for its exact late-cleanup disposition. |
| Controller commits expiry from an empty slot, then the worker returns | Expiry remains final; the worker commits one late result into the same slot for exact cleanup. |
| The system call returns and the worker is preempted before entering `settlementGate` | Controller may commit expiry; physical return alone is not settlement. |
| Worker samples `T = D - 1` and is preempted before committing the complete slot | The worker retains `settlementGate`; after commit, the published timely return wins. |
| Worker commits a timely return and is preempted before signalling the controller | Controller observes the slot under the gate and applies the timely return. |
| Terminal selection races a safely cancellable unentered occurrence with empty entry and return cells | Terminal resolution closes that occurrence directly. |
| Terminal selection races an entered, in-call, or accepted unresolved occurrence | A return already committed under the gate is applied first; otherwise the intact occurrence, gate, writable slot, owner bag, and deadline state transfer together to cleanup, with only unresolved unsafe residue retained by quarantine. |

The lock-order assertion is `sessionGate -> settlementGate`. Worker settlement acquires only
`settlementGate`. Its critical section contains identity/disposition access, writes of precreated scalar and
reference fields, the finite-deadline linearization sample when applicable, and complete slot commit. Android, JNI, GL, callback, scheduling,
cleanup, diagnostics, Flow, and other owner-transition work executes after the applicable gates are released.
Instrumented gate hooks verify the order, allocation-free settlement, and bounded contents of both critical
sections. Diagnostic construction and publication execute after the mechanical result and receipt are durable.

The generic expiry table is exercised only for occurrences governed by a finite deadline. Every entered opaque,
system, or ownership-sensitive occurrence still binds its own occurrence, `settlementGate`, precreated return
slot, and owner bag. Readiness occurrences, long-lived metrics collection, caller-dispatch call duration, entered application callbacks, and post-terminal or
terminal-converted no-watchdog Surface-release occurrences apply their named lifecycle and
ownership completion rules. A safely cancellable unentered occurrence with empty entry and return cells resolves
directly. A mandatory cleanup occurrence retains its exact owner and specialized one-shot obligation. An
unresolved entered, in-call, or externally accepted occurrence transfers intact to cleanup; quarantine retains
the exact residue whose safe resolution remains unknown.

Engine-scheduler tests race synchronous submission rejection with entry, cancellation, terminal conversion,
and cleanup transfer. Rejection settles only the current unresolved scheduler submission while the operation is
unentered and its entry and return cells are empty. An existing disposition remains authoritative; rejection of
a mandatory cleanup submission preserves the exact owner and specialized cleanup obligation. Scheduler
acceptance retains the occurrence for later entry and mechanical return. External dispatcher return, throw, or
synchronous rejection commits its actual dispatch fact. Trampoline entry commits a separate entry fact. Tests assert that an
actual recorded dispatcher fact is the sole dispatcher-result authority for delivery arbitration.
For the active delivery-worker submission specifically, a rejection that wins before detachment or terminal supplies `Failed(InternalFailure)` under
`sessionGate -> settlementGate`, resolves the safely unentered delivery lease during terminal cleanup, and records no `byDispatchFailure`; when detachment or a
terminal disposition wins first, the rejection is cleanup-only.

### 3.2 Fault injection

Each external boundary is exercised with the small set of outcomes that can change disposition:

1. normal return;
2. documented returned failure or throwable;
3. nonreturn after entry where ownership may have changed;
4. a late result after its state, generation, or operation is no longer current.

Safe returned faults use the documented rejection, suspension, fallback, or terminal classification. A
nonreturn is never treated as implicit cancellation or release. A late safe result can clean up only its own
resources; it cannot publish output, revive the Session, or replace a current decision.

Key staleness and operation lateness are tested separately. A Native encode failure committed through its
`settlementGate` with `T < D` may still monotonically disable the matching current Session Native-health
occurrence when its frame/reconciliation key became stale; it publishes neither that stale frame nor a stale
lifecycle failure and records `byFailure`. A result settled at `T >= D`, after expiry has committed, or after that health occurrence was retired
is cleanup-only and cannot change Native health.

Fault preparation uses the precreated return slot, discriminator, reference/scalar/throwable/receipt fields,
and already-owned evidence so the return path remains settleable under allocation pressure. An instrumented
allocator verifies that preparation and commit request no allocation, while diagnostic construction begins
only after complete mechanical settlement. Tests inject combined returned evidence and verify the common
precedence: unsafe or ambiguous ownership, malformed boundary evidence, or a non-OOM boundary exception is `InternalFailure`;
otherwise an exact boundary/allocation OOM is `ResourceExhausted`; only then may a documented returned result
select its ordinary disposition. The complete chosen outcome is committed once under `settlementGate`.

### 3.3 Resource observation

Test doubles expose acquisition, ownership transfer, detach, release return, and outstanding owners. A checked
size or successful earlier allocation is not a later resource's release receipt. Tests assert that each owned object is either:

- still owned by the active current scope;
- released after the matching operation returned;
- transferred to the new owner; or
- retained once by the Session quarantine because safe release is unknown.

This ownership observation is recorded locally by the component test.

### 3.4 Image oracle

Image tests use a few asymmetric synthetic scenes: distinct colored corners, odd dimensions, a one-sided
marker, horizontal and vertical gradients, and grayscale ramps. These scenes make channel swaps, row/stride
errors, upside-down output, wrong rotation/mirror/crop, unexpected bars, grayscale mistakes, and catastrophic
JPEG corruption visible with a small test set.

For raw geometry stages, the oracle checks output dimensions, mapped landmarks, bounds, and orientation. For
JPEG, the oracle requires successful decode, exact dimensions and orientation, expected landmark placement,
recognizable color/grayscale relationships, and absence of catastrophic corruption. It does not require
byte equality or exact decoded-pixel equality between lossy encoders. Gate B supplies a small fixed set of
vectors and finite numeric tolerances for automated image checks.

## 4. Public Contract And Lifecycle Tests

### 4.1 API and values

One table-driven suite checks the public inventory and every parameter default/range in 01 Design. It also
checks these architectural surface rules:

- caller-created input types have only the documented public constructors;
- engine-produced State, Stats, effective output, `ImageSize`, frame, diagnostic event, and exception values
  cannot be constructed by callers;
- public models are ordinary classes, with manual equality/hash/text only where 01 Design promises it;
- public signatures expose no implementation types or unintended data-class `copy`/`componentN` surface;
- packaged ABI inspection plus Kotlin-metadata compilation confirms the documented external Kotlin
  visibility; Java interoperability remains the existing public contract;
- `EncodedImageFrame` exposes `ImageSize`, not `android.util.Size`;
- `updateParameters` is synchronous and returns `Unit`;
- `FrameSubscription` exposes suspending `unsubscribe()` and no cancellation alias.

Constructor and update local validation cover all lower and upper scalar/structural boundaries, non-finite
scale, nonpositive dimensions, nonnegative crop-inset boundaries, invalid frame-rate/repeat ranges, JPEG
quality, Kotlin non-null contracts, and integer-overflow-prone local combinations. Locally invalid values create
no Session work. Whether locally valid crop insets leave a nonempty source is geometry-dependent feasibility;
startup and asynchronous reconciliation test that outcome below rather than treating it as local validation.

### 4.2 Start, update, and stop

Controlled barriers select both orders of concurrent `start` calls. The winner alone registers callbacks and
touches its projection. Startup tests then cross valid metrics, missing metrics, projection stop, caller
cancellation, and each VirtualDisplay outcome in Scenario 10.

A startup geometry-feasibility case supplies locally valid nonnegative crop insets that consume the selected
source under the authoritative geometry. Parameter construction succeeds; startup then becomes
`Failed(InvalidRequest)` and `start` throws `ScreenCaptureException(InvalidRequest)` rather than rejecting the
crop as a local validation error.

A startup-metrics invalidation case first supplies a valid tuple, then supplies nonpositive geometry or density
before initial `Running(Active)` commits. The Session becomes `Failed(CaptureUnavailable)` and `start` throws
`ScreenCaptureException(CaptureUnavailable)`. The invalid fact attempts source `MetricsProvider`, label
`CapabilityCheck`, a short message semantically identifying startup metrics loss, and the raw nullable boundary cause. The cause-free injected invalid-value
case expects null `cause`. Terminal commit also attempts source `Session`, label `SessionTerminal`, a short message identifying
`CaptureUnavailable` and the absence of prior active modes, and null `cause`. Tests do not require literal message wording.

Update tests begin in every nonterminal `Running` variant. They cover:

- desire equal to current desire;
- desire equal to effective parameters but different from current desire;
- multiple concurrent unequal calls with a recorded acceptance order;
- geometry or lifecycle change while reconciliation is running;
- invalid or unavailable latest desire retained across later availability;
- a locally valid crop that consumes the selected source under current geometry, which is accepted as desire,
  reconciles to `Running(Suspended(InvalidRequest))`, and is retried after a new desire or geometry fact;
- clean denial before retirement and clean failure after retirement;
- stale safe completion and stale ownership ambiguity;
- desired-revision exhaustion, which terminally fails with `InternalFailure`;
- a JPEG-quality-only change, which invalidates the old cache/repeat source without changing unrelated
  pacing, a healthy exact-compatible Bitmap/carrier, or healthy backend selection;
- image-affecting changes that drain and fence prior work, invalidate cache/repeat, retain each healthy exact-compatible target/FBO/texture/carrier/Bitmap/
  scratch/JPEG owner, and rebuild only the smallest incompatible scope without a registry, alternate planner, or rollback topology;
- a target replacement whose output shape and requirements remain compatible: the target and physical dependents change, while the healthy exact-compatible
  FBO/texture, carrier, Bitmap/scratch, and JPEG owners remain the same objects;
- an A-to-B destructive rebuild that crosses retirement, followed by desire A again before B commits: equality with A's historical effective parameters is
  not a no-op, the missing required scope is rebuilt once, and Active is assigned only after a healthy compatible A topology is actually owned;
- quiescence after a burst of updates.

The final assertion is simple: after desired parameters, geometry, lifecycle, and relevant availability stop
changing, the latest accepted desire reaches `Active`, an exact `Suspended` problem, or terminal failure.
Intermediate desires need not be applied.
When irreversible retirement has completed but no current replacement has committed, the documented
`Reconfiguring` suspension is observable only for that real gap; it is not an update result or a queued-update
state.

Stop tests pause work before and after its admission/commit points. After `stop()` returns, no new start,
update, frame acquisition, encode, repeat, cached offer, or callback entry can be admitted. A callback that
already entered may still return later. Final Stats precedes terminal State publication.

## 5. Android Capture And Metrics Tests

### 5.1 Platform branches

Branch tests cover the actual API splits:

| API range | Geometry and display observation |
| --- | --- |
| 24–29 | The selected provider supplies authoritative width, height, and density. The public built-in remains fixed to its exact `Display` and uses a fresh transient display Context for every complete density read. |
| 30–33 | The selected provider supplies authoritative width, height, and density. The public built-in retains one WindowContext per continuous valid-display epoch for maximum-window bounds and uses a fresh transient display Context for every complete density read. |
| 34–37 | Provider density remains live and required. Projection `onCapturedContentResize` supplies authoritative width and height after the first valid resize; no frame is admitted before that fact. Provider dimensions may support only the documented provisional startup target. Visibility is informational only. |

For each branch, tests supply valid values, null/invalid values, provider completion before first valid value,
provider failure before and after readiness, density-only change, dimension change, display change, and stale
callback facts. API 34–37 additionally verify provisional startup target handling, first-resize timeout,
matching-Full retention, conditional retarget, and resize after Running.

An API-surface check confirms that every call introduced after API 24 is guarded by its documented runtime
branch. It covers MediaProjection callback registration/order, resize and visibility availability, foreground-
service owner obligations exposed to the caller, current projection-reuse restrictions, native 16-KiB page
compatibility. These checks validate the supported
API 24–37 contract without requiring a separate device for every API level.

The canonical executable provider matrix is Document 08 Section 4.2. It proves that null configuration uses
one Session-private default-display provider; `fromDisplay(context, exactDisplay)` is an immutable reusable
exact-Display provider; the Session calls `observe()` once on the exact configured provider when nonnull; custom
providers retain exact identity; and the metrics Display neither selects nor verifies the MediaProjection source.
Direct, repeated, parallel, and concurrent-Session built-in collections remain independent cold-Flow
collections. Every built-in `DisplayListener` callback on API 24–37 is routed through an explicit process
Main-Looper Handler, while registration, reads, emission, and unregister remain in the collection's controlled
upstream context or the Session metrics-IO view.

The same matrix verifies each complete API-band read, one WindowContext per continuous valid-display epoch on
API 30–37, a fresh transient display Context for each density read, exact-Display validity, and complete-tuple
publication. Selected add/remove boundaries use the sticky-bit exchange and a separate unavailable pass;
ordinary changes request a later complete reread. Barriers around the exchange, reads, final validation, and
installation prove that no boundary is erased, no fields from different reads are combined, and retired or
unrelated Display facts cannot publish.

For a Session built-in listener, terminal cleanup first fences metrics authority and closes its signal, then
attempts exact unregister on the metrics-IO view. Unregister throw or nonreturn retains the exact listener,
`DisplayManager`, Main-Looper Handler dependency, and collection occurrence as applicable, but never the Session
Android Handler or HandlerThread. Other cleanup roots, Android HandlerThread shutdown, and another Session
therefore progress independently. Tests model a platform callback snapshot already captured before unregister:
both after the authority fence and a normal unregister return and, independently, after Session HandlerThread
shutdown, its late Main-queued callback enters only the O(1) fenced path and cannot publish, wake current state,
or make a lifecycle decision.

### 5.2 Metrics collection lifetime

Each Session's metrics owner is exercised as the one structured lifetime detailed by Document 08 Section 4.2:
the attached provider, plain parent `Job`, Session scope on the metrics dispatcher view, empty preattached
Flow-return cell, exactly one lazy collector child, and parent completion observer are attached before unlocked
start. Positive evidence establishes the exact parent-child relation, one collection, terminal propagation,
and one final-parent-completion receipt.

The collector lifecycle suite covers these outcomes and races:

- cancellation before child entry executes no provider observation, registration, collector body, or body
  `finally`; cancellation of the parent still reaches final parent completion exactly once;
- after child entry, `observe()` alone may fill the already-owned Flow-return cell. The cell is empty before
  attachment, is filled once only after `observe()` returns, and remains rooted with the provider and
  collection lifetime until final parent completion;
- child start versus terminal parent cancellation has only two legal results: the child never enters, or it
  enters once and performs its exact finally/cleanup before parent completion. It cannot enter provider code
  after cancellation has already won;
- normal provider completion before Session terminal is legal: before a valid value it produces startup
  `CaptureUnavailable`; after a valid value it preserves the last valid metrics and emits the documented
  completion diagnostic. After the child and its cleanup finish, child completion explicitly completes the
  plain parent and the sole parent-completion receipt is observed;
- a `CancellationException` thrown by `observe()` or collection while the parent remains active is a provider
  failure, classified as `InternalFailure` with that raw exception as cause. A cancellation observed after
  terminal parent cancellation follows the terminal-cancellation path instead. Barriers select both orders
  of the provider-origin exception versus terminal cancellation and verify one classification, one cleanup
  path, and one parent receipt;
- every other provider failure uses the documented `InternalFailure` classification. Child completion still
  completes the ordinary parent after its cleanup, and final parent completion remains the sole mechanical
  receipt;
- terminal cancellation is an intent, not a fabricated mechanical return. For an entered collector that does
  not return, the parent stays incomplete and the exact parent/scope/child/provider ownership unit transfers
  intact to cleanup or quarantine;
- a late collector return after terminal selection can complete only that same parent and reduce only its
  exact cleanup residue. It cannot publish metrics, change the terminal winner, or produce another receipt.

Barriers cover lazy start, entry, observe/collect return or failure, cleanup, and parent completion. Handler
tests trigger child and parent completion while engine gates are held: the child handler only completes the
plain parent, and the parent handler only fills the precreated completion cell and issues the nonblocking wake.
Both return without waiting, outward calls, cleanup, lifecycle decisions, or a second receipt.

### 5.3 MediaProjection, VirtualDisplay, and target lifetime

Component tests record all callback registration, VirtualDisplay creation/mutation, Surface attachment, and
cleanup calls. They verify:

- projection callback registration precedes the sole `createVirtualDisplay` call, whose owner boundary receives the installed `CurrentTarget`;
- the sole `createVirtualDisplay` call passes a null `VirtualDisplay.Callback` and null callback Handler;
  `MediaProjection.Callback.onStop` is the sole platform callback authority for `CaptureEnded`, while explicit
  `VirtualDisplay.release` remains part of cleanup;
- the VirtualDisplay always uses authoritative logical `W x H` and density. On API 32–37, an eligible Early
  Surface Downscale changes only the smaller exact-aspect Surface target and relies on the official uniform,
  aspect-preserving fit-and-center behavior;
- API 32–37 Downscaled is selected automatically only when every closed eligibility condition and exact-aspect
  size calculation in 02 Architecture succeeds; otherwise the API 24–37 Full baseline is selected;
- a safely returned Downscaled incompatibility disables that target path and permits the single documented
  rebuild to Full; uncertain target ownership is terminal;
- density-only change uses the documented pause, drain, resize, cache invalidation, and resume path;
- dimension or target-health change uses the destructive rebuild order;
- after resume, the first available producer buffer may be used; target generations still reject stale
  engine work and results;

Prepared/installed target races use this observable matrix:

| Boundary | Required observations |
| --- | --- |
| Preparation | Before construction, one typed `PreparedTarget` has its reserved nonreused generation, empty partial slots, precreated candidate/promotion, and shared release obligation; each returned OES/SurfaceTexture/Surface enters its slot immediately. |
| Arbitration | Timely current completeness transfers allocation-free to `TargetOwner.currentTarget`; partial, stale, late, terminal, and every other noninstall result is cleanup-owned and never current. Generation gaps occur without reuse, and no next candidate starts before cleanup/rooting disposition. |
| Structural API | Only `CurrentTarget` crosses listener, VirtualDisplay create/attach, lease, and frame seams; uninstalled fixtures record zero such calls. |
| Release | Installed fixtures withhold each ordinary prerequisite and separately accept exact no-producer evidence only for safely inapplicable/unentered create/attach or return without producer ownership. Uninstalled fixtures require settled construction, cleanup claim, and structural zero listener/producer/lease use. Neither branch fabricates detach/release; both require Surface normal receipt, then SurfaceTexture normal receipt, then OES destruction. |
| Time and residue | Preterminal construction/destruction uses the existing GL boundary and listener/Surface work the existing Android boundary; terminal conversion has no watchdog. Partial/nonreturn roots only exact dependents, while retired-generation and other-occurrence facts remain cleanup-only. |
- each Surface-release occurrence precreates its `settlementGate`, typed `OperationReturnCell`, fixed
  normal/throw fields, owner bag, and applicable `DeadlineOccurrence` before GL-lane submission. For a finite
  deadline-governed occurrence, normal return committed with `T < D` is timely and supplies the Surface
  receipt; a returned throw committed with `T < D` supplies the current `InternalFailure` disposition and leaves
  Surface ownership unresolved. A complete normal-return slot with `T >= D`, or a normal return committed after
  expiry won from an empty slot, supplies the real Surface receipt only for late dependent cleanup; expiry
  supplies the terminal timeout disposition. A late throw supplies no receipt. The suite covers worker commit
  at `D - 1` and `D`, expiry-before-return, worker preemption before gate entry, after the `T` sample but before
  complete slot commit, and after commit but before controller signalling;
- `OwnerStop` and `CaptureEnded` are raced before release entry, after entry, and in the same controller turn as deadline expiry. Tests assert one occurrence,
  no duplicate call, post-terminal or terminal-converted no-watchdog execution, deadline retirement after a terminal winner, the fixed
  `CaptureEnded -> OwnerStop -> Failed` priority, and the existing startup exception mapping. Return-versus-terminal cases apply a return already committed
  under the occurrence gate before transferring the intact occurrence to cleanup or quarantine;
- a newer desired/geometry key while release is entered builds no stale replacement after a timely normal return and remains terminally unsafe after
  throw/nonreturn. A Downscaled-to-Full fallback likewise proceeds only after a timely normal Surface receipt;
- returned GL-lane submission rejection settles only the current unresolved scheduler submission under the
  same precreated occurrence's `settlementGate`, while the release is unentered and its entry and return cells
  are empty. An existing disposition remains authoritative. The Surface owner and mandatory one-shot cleanup
  obligation survive rejection. Submission acceptance retains that occurrence for later entry and the actual
  release return or throw. Scheduler rejection, entry, mechanical return, and terminal selection are raced
  without substituting a scheduling fact for an operation-return fact. Scheduler nonprogress keeps the general
  no-start-timer exclusion;
- a blocked release in one Session does not occupy another Session's private GL lane, while the Android Handler of the affected Session can still process
  projection callbacks and its independent cleanup subchain.

Platform failure classification is asserted directly:

| `createVirtualDisplay` observation | Problem |
| --- | --- |
| null return | `CaptureUnavailable` |
| `SecurityException` | `CaptureUnavailable` |
| directly thrown `OutOfMemoryError` | `ResourceExhausted` |
| `IllegalStateException` | `InternalFailure` |
| any other unexpected throwable | `InternalFailure` |

No test infers recovery from a new Session after ambiguous process-level ownership. A caller may create a
new Session with fresh consent; that new attempt makes no claim that old unresolved resources were repaired.

## 6. Frame Production And Image Tests

### 6.1 Geometry, GL, and color

The asymmetric scenes in Section 3.4 cross the material geometry choices that can produce different visible
behavior. The suite includes:

- one Full source with odd dimensions and nonzero crop;
- one half-region case;
- each rotation with one asymmetric marker;
- horizontal and vertical mirror;
- `ScaleFactor` and `TargetSize` with each content mode where behavior differs;
- one eligible exact-aspect Early Surface Downscale case and one ineligible Full case;
- sRGB color, exact Display-P3 conversion, grayscale, and one unsupported wide/HDR classification.

For each case the test checks final `ImageSize`, landmark placement, orientation, active-content bounds,
expected bars or absence of bars, opaque output, and diagnostic color action where required. Shader precision
selection checks highp first and mediump fallback, and records the selected result diagnostically.

Baseline GL tests check texture/FBO completeness, FBO-only readback, tight RGBA layout, row direction, alpha,
and cleanup. Framework Bitmap tests exercise both direct tight-row copy and the reusable row-conversion path, same-shape Bitmap/scratch reuse, and
incompatible-shape retirement before replacement.

### 6.2 JPEG and encoded ownership

The Framework baseline and every enabled Native product use the image oracle in Section 3.4. The test checks
quality forwarding, transactional output, immutable published bytes, exact dimensions/orientation, and
recognizable content. Partial, stale, or failed output is discarded and never replaces the
valid cache.

Framework outcome tests keep the result partition small: `Bitmap.compress` false is one frame `byFailure` and
later frames continue; Bitmap/sink memory exhaustion is terminal `ResourceExhausted`; an unexpected exception
or malformed sink result is terminal `InternalFailure`; and ambiguous ownership takes the terminal quarantine
path. No failure publishes partial bytes or retries the same frame.

Framework-resource verification uses this compact matrix:

| Axis | Required observations |
| --- | --- |
| Settlement | One combined occurrence and deadline span Bitmap creation/adoption, actual-metadata mode selection, optional scratch creation, and complete allocation-free publication. Cross `D - 1`, `D`, `D + 1`, empty-at-`D`, pre-gate/post-sample pauses, commit-before-signal, rejection, inline entry, cancellation, and terminal before/after entry. |
| Outcome | Inject Bitmap success, OOM, unexpected failure, and nonreturn; after Bitmap success, inject scratch success, OOM, unexpected/malformed evidence, and nonreturn for current, stale, expired, late, and terminal results. Timely current OOM is `ResourceExhausted` with no drop; other unsafe current outcomes are `InternalFailure`; timely stale success does not install, safe stale failure/OOM does not change lifecycle, and ambiguity remains terminal. A noninstalled Bitmap is recycled exactly once after settlement, scratch is logically dropped, and timeout diagnostics preserve the existing `FrameworkJpeg` `CapabilityCheck`/`SessionTerminal` cause identity. |
| Topology | Complete owner installation precedes first/resumed Active. `FrameworkOnly` and clean Native ineligibility create only after backend/carrier selection; Native `Enabled` and the safely failed switching frame own no Framework resources, and that frame settles once as `byFailure` without retry. Later creation remains `Reconfiguring`; compatible identity is reused, while incompatible replacement requires settled use, one timely recycle receipt, and a fresh current/nonterminal eligibility check. A-to-B-to-A and target-replacement races fence stale creation. |

Bitmap-lifetime tests hold copy/compress use open and prove that recycle cannot enter. Once every use and lease settles, incompatible preterminal replacement
consumes the owner into one generic occurrence, calls `Bitmap.recycle()` exactly once on the Framework-encoder execution lane, and requires normal return before
dropping the reference or allocating the replacement. It uses `jpegEnteredOperationSafetyNanos`; timely normal return permits replacement, while rejection,
throw, expiry, or nonreturn retains the exact owner/occurrence, admits no replacement, and never retries recycle. Same-shape reuse creates no recycle call.
Terminal retirement uses the same generic occurrence without a watchdog: normal return drops the reference, while rejection, throw, or nonreturn retains the
exact residue. Races cover return versus terminal transfer without a Bitmap-specific state graph or constant.

Native capability and fallback tests cover exactly three legal carrier/health combinations: native carrier with
Native enabled, native carrier with Native disabled, and managed carrier with Native disabled. Managed carrier
with Native enabled is impossible. Both carrier leases traverse the same private synchronous transfer path in
`FrameworkJpegOwner`; only native-carrier ownership exposes a stable address to Native JPEG. Ownership,
allocation, and cleanup ledgers contain no separate transfer-adapter owner or lifecycle.

Engine-library loading is ownership-free until the native bridge is successfully published: loading and
`JNI_OnLoad` create no Session, carrier, sink, or other session-owned native resource. Only a synchronous
`UnsatisfiedLinkError` or `SecurityException` before bridge publication and before any engine JNI operation
entry, with zero native ownership, selects the managed carrier and Framework JPEG. A load-time
`OutOfMemoryError` publishes the first-cause process-lifetime `LoadOome` result: the current timely preparation
and every future preparation fail with `ResourceExhausted`, and no load retry, bootstrap, carrier allocation, or
Framework fallback occurs. A late OOM does not revise the Session outcome that already won, but it fixes that
same result for future Sessions. Partial initialization, any failure after bridge publication or engine JNI
operation entry, any other throwable, and ambiguous ownership are terminal `InternalFailure` poison and cannot
select fallback. Tests distinguish all three process results—clean load unavailability, `LoadOome`, and bootstrap
poison—and race concurrent preparation against the one load attempt.

Native eligibility uses the documented API, successful own-DSO load/bootstrap/registration, and guarded weak
capability checks. Runtime code neither derives nor asserts an ABI string: package inspection proves the exact
production DSO for each of the four shipped ABIs, while installed runtime evidence proves the loaded bridge on
that process. The first real frame is the first compression call. Tests apply the complete
normal/safe/unsafe/timeout result partition to that call and verify that a safely returned Native failure drops
that frame, disables Native for later frames, and never retries the same frame through Framework.

Carrier-replacement races use this matrix:

| Carrier | Required observations |
| --- | --- |
| `NativeMallocCarrier` | Timely normal free receipt precedes a fresh current/nonterminal-key recheck and its distinct `NativeCarrierReplacementAllocationOccurrence`. Current success installs; current OOM is `ResourceExhausted`; unsafe/rejection/expiry/nonreturn is `InternalFailure`; stale/late/terminal return is freed once for cleanup. |
| `ManagedDirectCarrier` | Settled old uses precede logical detach without a receipt, then a fresh current/nonterminal-key and no-extant-occurrence recheck starts exactly one `ManagedDirectCarrierReplacementAllocationOccurrence`, separate from Native free/allocation. Its JPEG-IO `allocateDirect(B)` return is immediately occurrence-owned; current success installs, current OOM is `ResourceExhausted`, unsafe/rejection/expiry/nonreturn is `InternalFailure`, safe stale success/OOM changes no lifecycle result, and late/terminal return is logically dropped only after mechanical settlement. |
| Common races | Cross `D - 1`, `D`, `D + 1`, pre-entry terminal, staleness after recheck, terminal transfer, and late return under the existing JPEG deadline. Native timeout diagnostics use `NativeJpeg`; managed replacement uses `FrameworkJpeg`. Compatible direct writable `B`-byte identity is reused; managed replacement performs no preparation rerun, manual free, or GC-receipt claim. |

Atomic-disable tests require one `sessionGate -> settlementGate` commit for a timely safe result whose Native-health occurrence is current. It records
`byFailure`, disables Native, advances the lifecycle epoch, closes affected fresh/repeat/new-delivery admission, fences obsolete backend/frame identities,
invalidates cache/repeat, and selects `Running(Suspended(Reconfiguring))`; entered delivery is not revoked, and State/Stats publish coherently after gate
release. A current key discards its attempt once; a key-stale attempt with current health does the same without stale output or lifecycle failure. Neither
allocates Framework resources or retries the frame, and only later complete current Framework-owner installation reopens admission and permits Active.

`ANDROID_BITMAP_RESULT_ALLOCATION_FAILED` enters that commit only after safe return with exact carrier/sink settlement; carrier or sink allocator OOM remains terminal
`ResourceExhausted`, and nonreturn or ambiguous/malformed ownership remains terminal `InternalFailure`. A terminal winner keeps fixed priority over suspension.
Results at `T >= D`, after expiry, or for retired health are cleanup-only and cannot disable Native.

Native race tests cross current-key/current-health, key-stale/current-health, expired or late, retired-health, and terminal contenders at `D - 1`/`D`, with
pauses before gate entry, after sampling, and before signalling. They verify allocation-free complete evidence publication before classification: the first two
receive the atomic commit above, expired/late/retired cases change only exact cleanup ownership, and terminal arbitration retains its fixed priority.

Tests accumulate all writer and JNI evidence before classifying a returned call. Unsafe or ambiguous ownership,
a malformed writer call or writer-contract violation, and any non-OOM JNI exception take precedence as terminal
`InternalFailure`. Otherwise an exact writer, sink, or JNI OOM is terminal `ResourceExhausted`. Only when neither
class of fault exists may the compressor integer determine success or a safe optional-axis failure. A
combined-fault case records both an exact OOM and malformed writer evidence and verifies that `InternalFailure`
wins. Every normal native evidence return initializes all five `NativeResultBlock` words. Its unsigned writer
status is exactly `0` (`Safe`), `1` (`OutOfMemory`), or `2` (`InternalFailure`); every other 64-bit pattern,
including one observed as a negative Kotlin `Long`, is malformed `InternalFailure` and authorizes no bytes,
fallback, or Native-health change.

### 6.3 Pacing, repeat, cache, and Stats

A fake source and fake clock exercise fresh signals just before, at, and after each selected pacing boundary.
`Auto`, `MaxFps`, and `SampleEvery` are tested separately. Bursts collapse to the documented latest-pending
work. An early signal remains pending through the eligibility boundary and is then processed even when no later signal arrives; its deferral increments neither
`byRateLimit` nor another drop counter. The sole production slot remains occupied from materialization through final disposition, including while a completed
JPEG is waiting for output pacing; a pending source bit may coexist, but no second attempt materializes and no `byPipelineBusy` is invented for that
pre-materialization coalescing.
Static or blank source content does not create a fabricated first-frame timeout.

One-wake tests combine pending fresh work, completed-unpublished JPEG, repeat eligibility, and rapid policy/candidate churn. They directly observe at most one
queued pacing/repeat scheduler submission plus at most one already-dequeued stale callback in a cancel-versus-dequeue race. Replacement cancels/removes or
coalesces the queued predecessor before posting the current submission; dequeue consumes identity before clock resampling; stale callbacks perform no action
and install no successor; and only the current carrier may perform at most one action and install at most one successor. Current-submission rejection is
terminal `InternalFailure` without a drop, while stale/detached/terminal rejection is cleanup-only. After churn stops, retained pending work eventually receives
one action or the Session has the exact terminal rejection outcome; no stale burst or silent stranding occurs. Stats cadence remains separately owned. Repeat
tests also cover absent cache, valid cache, cache invalidated by pause/rebuild, a fresh-frame tie, a delayed wake, and `MaxFps` interaction. A repeat reuses
immutable JPEG bytes while producing new frame metadata.

Stats assertions distinguish successful encodes, produced outputs including repeats, and every documented
frame- and delivery-drop reason, including retained `byRateLimit == 0` across early paced signals. They explicitly race stale identity with mechanically
returned production failure: safe failure is `byFailure`, otherwise
successful stale suppression is `byStaleWork`, and unsafe failure remains terminal. Counter saturation is safe. Periodic dirty Stats are published at most once
per second, while lifecycle, fallback, rebuild, and terminal facts request immediate publication.

A production-terminal cutoff test pauses the production worker immediately before and after complete return-cell commit and races each pause with terminal
arbitration under `sessionGate -> settlementGate`. A return or already-selected classified disposition that wins is folded through normal accounting before
final Stats; this includes exactly one `byFailure` when that classified failure causes terminal. When terminal wins first, an otherwise unclassified
materialized attempt, a completed-unpublished JPEG retired without output commit, and an unresolved occurrence transferred whole to cleanup each add no
dropped-frame counter. A return committed after transfer performs cleanup only and leaves final Stats unchanged. The test also proves that an existing
cache/output commit and the existing `byPipelineBusy`, `byStaleWork`, and `byFailure` dispositions are neither lost nor counted twice.

## 7. Optional Paths And Fallback Tests

The optional-path suite is organized by three independent axes:

| Axis | Enabled observation | Safe failure | Unsafe failure |
| --- | --- | --- | --- |
| Early Surface Downscale | Closed eligibility, exact-aspect target, and automatic API 32–37 selection | Disable Downscaled and rebuild once to Full | Terminal and quarantine |
| Native JPEG | Closed API/own-bridge/guarded-symbol eligibility, separate four-ABI and 16-KiB-compatible packaging, and the first real frame as the first compression call | Disable Native, use Framework later | Terminal and quarantine |
| Display-P3 color | Exact dataspace classification | Use the documented best-effort color action | Terminal only when the ordinary pipeline itself is unsafe |

Each axis is tested once enabled, once ineligible, once with a safe returned runtime fault, and once with an
ownership-ambiguous fault. A small integration set crosses the two reachable readback/JPEG products,
Direct+Framework and Direct+Native. It verifies decodable output and independence: a Native fallback does not change Direct readback or target health.

Downscaled integration checks the public output dimensions and orientation, the expected semantic transform,
active-content coverage, and the single Full fallback after a detected runtime target-path failure. Ordinary
filtering, rounding, and minor platform differences are accepted and Full-path pixel identity is not an
oracle. Runtime selection uses the closed eligibility and runtime-failure rules.

## 8. Frame Consumer Tests

Registration tests cover no cache, a valid cache, an invalidated cache, and registration concurrent with a
fresh output commit. Exactly one of the valid current cached image or the later fresh image is offered first
according to the documented serialization; neither is duplicated. Cached-first uses the ordinary sole
handoff and lease.

Dispatcher/callback tests independently exercise:

- synchronous dispatcher throw or rejection;
- caller dispatcher call nonreturn as the documented unsupported-progress case;
- trampoline entry followed by a late dispatch return or throw, where entry owns settlement and no
  `byDispatchFailure` is recorded;
- inline callback return while the dispatch invocation is deliberately held unresolved;
- accepted task entering before 5 seconds;
- accepted task still queued at 5 seconds;
- late trampoline after terminal timeout;
- callback normal return;
- callback throw;
- entered callback nonreturn.

Each delivery record precreates its `settlementGate`, typed `OperationReturnCell`, fixed dispatch-result and
entry fields, owner bag, and optional five-second task-entry deadline link. An
external dispatcher normal return, throw, or synchronous rejection commits its actual dispatch fact through
that record gate. Trampoline entry or detached self-rejection commits its separate entry fact. Callback return,
unsubscribe, and terminal contenders use the same record gate for their own exact transitions. An empty cell
represents an unresolved dispatcher call; the actual external-call fact resolves it.

Tests cover dispatcher return, throw, or rejection immediately before and after trampoline entry, unsubscribe, and terminal selection. Trampoline entry acquires
`sessionGate -> settlementGate`, commits either admissible `Entered` or detached self-rejection for the exact record, and releases both gates before user code or
cleanup. A normal dispatch return before entry samples the return time and arms the task-entry deadline from it. When trampoline entry commits first, a later dispatch return or throw settles the worker
side without changing the entry-owned delivery outcome or recording `byDispatchFailure`. When synchronous rejection commits first, the delivery settles once
as `byDispatchFailure` and the registration remains eligible for a later output.

The inline-return case proves the two already-distinct sides of one record. Callback return immediately releases borrowed-frame authority and the encoded
lease, but the record and sole delivery worker remain occupied until actual dispatch return/throw. A new output in that gap records `byConsumerBusy`, creates no
record or worker, and cannot produce scheduler rejection or `InternalFailure`; the eventual dispatch outcome only retires its side. If terminal wins during
the gap, final Stats folds the callback result and cleanup receives only the remaining worker/dispatch residue, not the already-released lease.

Engine scheduling of the delivery worker is tested separately. Synchronous scheduler rejection settles only
the current unresolved scheduler submission while the delivery occurrence is unentered and its entry and return
cells are empty. When it wins for an active current delivery, it supplies terminal `InternalFailure`, safely resolves the unentered delivery record and lease
through terminal cleanup, and does not increment `byDispatchFailure`. Cancellation, detachment, terminal transfer, entry, return, or another settled disposition
that wins first remains authoritative, making the later rejection cleanup-only. Scheduler acceptance retains the occurrence for the later external-dispatch
entry and actual dispatcher fact.

The delivery race harness preempts both the dispatch worker and the trampoline immediately before their
respective gate entry and after commit but before controller signalling. For accepted-task entry it also pauses after the deadline sample but before complete
fact commit, preserving the sampled timely/late classification under `settlementGate`. Controller observation of the committed slot determines settlement in
the final order. A safely cancellable unentered delivery occurrence resolves directly. Terminal transfer
preserves an unresolved in-call or accepted delivery occurrence, `settlementGate`, writable slot, encoded
lease, and callback-resolution owner bag so a late dispatcher or trampoline fact can resolve only that record;
quarantine retains only residue whose resolution remains unsafe or unknown.

Detachment is also raced while `dispatcher.dispatch` is still in-call. A later normal dispatch return arms the same sole five-second
entry/self-rejection deadline from the return sample, which remains attached to the occurrence through terminal detachment. A later trampoline entry resolves
the detached record without invoking user code.

Only the accepted-but-not-entered deadline is fixed at five seconds. Synchronous rejection and callback throw settle one delivery and permit a later output.
Entry-deadline expiry while the Session is nonterminal commits `Failed(InternalFailure)`. Expiry after `Stopped` or another terminal State roots the exact
unresolved delivery residue without rewriting State. A caller dispatcher that never returns violates the supported-dispatcher progress condition: no watchdog
changes State, the sole record remains occupied, unsubscribe waits, and terminal transfer roots that exact residue. Entered callback nonreturn behaves the same
for its entered record. Late dispatcher and trampoline facts reduce only their exact residue; a late trampoline remains fenced from user code.

Callback-return tests pause immediately before and after complete return-cell commit. Terminal arbitration folds a cell already complete under
`settlementGate` before final Stats, so a throw complete before whole-occurrence transfer contributes exactly one `byCallbackFailure`. If terminal transfers the
whole unresolved occurrence first, a later normal return or throw only releases its borrowed-frame authority and encoded lease and reduces that exact cleanup/quarantine root; it contributes
no counter or `DeliveryProblem` and cannot change State. Two branches assert diagnostics precisely: if consuming the late receipt actually changes
`SessionQuarantineRoot`, the ordinary mandatory `QuarantineChanged` attempt occurs; if cleanup consumes it before quarantine, no diagnostic is emitted.

Unsubscribe tests start from idle, prepared, dispatching, accepted-queued, entered, resolved, failed, and
stopped conditions. They assert immediate closure, idempotent waiters, waiter cancellation, self-call
rejection, terminal exception mapping, and the requirement that replacement registration begins only after
successful unsubscribe. The inline-return/dispatch-unresolved case releases the frame lease, calls `unsubscribe()`, and proves that both unsubscribe and
replacement registration remain blocked until the actual dispatch return/throw settles the full handoff. Old and replacement callbacks never overlap.

Frame-lease tests verify successful property access, `copyTo`, and `copyBytes` during the callback on its
callback thread. An invalid `copyTo` destination range throws `IndexOutOfBoundsException` without modifying
the destination. Wrong-thread and post-return property or copy access throws `IllegalStateException`.

## 9. Resource Bounds, Diagnostics, Cleanup, And Privacy Tests

### 9.1 Resource bounds and allocation

Resource tests use checked arithmetic and injected allocator/platform outcomes. They prove one active
target/pipeline topology, one stable CPU RGBA carrier, one installed or creation-occurrence-owned shape-compatible Framework Bitmap and row
scratch only while Framework is selected and Native is disabled, the closed encoded-storage roles,
one callback lease, and exact already-owned quarantine residue. They also prove that a Framework Bitmap is an
encoder-owned pixel store rather than a second stable CPU carrier.

A compatibility matrix changes one plan dimension at a time and verifies direct reuse of every healthy exact-compatible target, FBO/texture, carrier,
Bitmap/scratch, and JPEG owner, with retirement and allocation limited to the smallest incompatible scope. The test observes the existing desired revision and
current topology only; it finds no resource registry, alternate planner, replacement generation, rollback pipeline, or second healthy topology.

Every checked size, multiplication, addition, narrowing, buffer range, row stride, and segment cumulative length
is tested at valid and overflow/invalid boundaries. Each real allocation boundary covers success, directly
thrown OOM, partial construction with safe return, and nonreturn after possible ownership transfer. A
deterministic geometry/API/backend limit before retirement allocates nothing and leaves the old pipeline
recoverable. After retirement, a current required carrier, Bitmap, sink, or platform allocation failure is
terminal `ResourceExhausted`. Tests treat loss of a managed-buffer reference only as logical ownership
retirement and never as proof of immediate physical reclamation.

Privacy assertions verify that raw pixel storage, mutable encoder input, partial JPEG bytes, stale results,
and borrowed frame wrappers never outlive their owner or cross the callback lease. Caller-copied JPEG bytes
remain caller-owned. Crop and source-region selection are not treated as security redaction.

### 9.2 Diagnostics

Tests subscribe before start because the SharedFlow has no replay. They verify `replay = 0`, buffer 128, and
drop-oldest behavior without treating delivery as reliable. Every attempted event receives the next
Session-local `sequence`; a collector may observe gaps when events are dropped. `timestampEpochMillis` is the
documented system timestamp for correlation with other logs, not lifecycle or ordering authority.
The first attempted event has sequence 1, and every later attempt advances sequence before `tryEmit`, so
overflow remains visible as gaps. `timestampEpochMillis` samples wall clock when the event is created; repeated,
forward, or backward wall-clock values do not affect sequence ordering or engine control.

The single diagnostics table in 01 Design is the required source/label coverage list. Tests trigger each of its eight categories through representative
capability selection/failure, initial runtime profile, actual mode fallback, delivery problem, Stats protection, color action, quarantine change, and terminal
outcome. They check `source`, `label`, a short semantically useful `message`, and the original nullable `cause`, without requiring literal message wording.
They do not require geometry, visibility, rebuild, consumer-lifecycle, or routine per-frame events.

### 9.3 Cleanup and quarantine

Terminal tests trigger owner stop, capture end, startup failure, callback-entry timeout, safe terminal
resource failure, and unsafe ownership. Exactly one terminal winner closes all admission. Final Stats is
assigned before terminal State; diagnostic delivery remains best effort.

Cleanup component tests hold one dependency indefinitely while unrelated roots and another Session continue. Each unresolved unit is recorded once in
`SessionQuarantineRoot` as its exact occurrence, gate, writable return cell, owner bag, entered/deadline facts, worker, and transitive resources; late settlement
can reduce only that unit and cannot change terminal outcome or make the Session reusable. This generic assertion includes Framework creation's partial bag:
after worker/use settlement any returned Bitmap is recycled exactly once and scratch is logically dropped. The Android subchain remains serial, and each
target, GL, JPEG/storage, callback, or diagnostics root retains its documented local order.
For `Surface.release`, normal return supplies the Surface-return receipt and permits dependent target cleanup;
a returned throw is recorded and leaves the Surface unresolved while unrelated roots continue; nonreturn
roots the call and blocks only its physical dependents. Tests assert that the rooted residue is exact: the release worker/occurrence, Surface,
SurfaceTexture, target OES/GL objects, their live carriers, and transitively dependent EGL context, pbuffer, and GL-lane records that cannot yet
be destroyed. Provably independent GL resources retire before release entry, and Android capture, JPEG/storage, frame-consumer, metrics, unrelated deadlines,
allocator-owned carrier/storage and diagnostics roots continue.

Cleanup tests instantiate both mutually exclusive release branches in Section 5.3 and prove that installed prerequisites, typed no-producer evidence, and
uninstalled structural evidence cannot satisfy one another. Retired-generation or other-occurrence facts remain cleanup-only.

Terminal Surface tests distinguish an occurrence created after terminal from a preterminal occurrence converted before entry and an occurrence already entered
when terminal wins. All three use one call, one `settlementGate`, and one `OperationReturnCell`; the first two have no watchdog, while the third retires its deadline. A late normal return
reduces only the exact target root after subsequent SurfaceTexture/GL receipts; a late throw leaves Surface ownership unresolved. Neither result rewrites the
terminal winner, reopens admission, enables fallback, allocates a replacement, or releases an unproved owner.

## 10. High-Risk Paper Traces

These seven traces are reviewed before implementation. Executable tests later reproduce both relevant
orders with barriers. They are ordinary readable traces, not formal proofs.

### 10.1 Concurrent start

1. Start A and start B arrive at the Session gate.
2. A linearizes first and changes `NotStarted` to `Starting`.
3. B observes `Starting`, fails, and its projection has zero recorded access.
4. A alone registers platform callbacks and reaches Running or the single terminal startup outcome.

Reversing steps 2–3 changes only which caller wins.

### 10.2 Latest desire versus stale rebuild

1. Desire A begins a destructive rebuild for key `(A, geometry 1, lifecycle 1)`.
2. Desire B replaces the desired cell before A completes.
3. A returns safely; its key is stale, so it publishes nothing and releases its local result.
4. Reconciliation runs for B and may alone commit the next effective output.

If A returns with ambiguous ownership, safety wins and the Session fails even though A is stale.

The destructive A-to-B-to-A variant crosses retirement before the second A desire. Although A equals the historical effective parameters, its live scope is
gone; reconciliation rebuilds the required A scope and assigns Active only after that compatible healthy topology is owned.

### 10.3 Geometry change during startup

1. API 34+ starts with provisional target geometry but frame admission is closed.
2. The first valid projection resize supplies authoritative width and height.
3. If Full already matches exactly, the target is retained; otherwise it is retired and rebuilt.
4. Only the authoritative current target may enter Running and produce a frame.

If the readiness deadline wins first, startup fails with `CaptureUnavailable`; a later resize cannot revive it.

### 10.4 Safe Native failure while target mode remains healthy

1. Native JPEG for one frame returns a safely classified optional-backend error with exact carrier and sink ownership.
2. One authoritative commit records `byFailure`, disables Native, advances the lifecycle epoch, closes affected admission, fences obsolete identities,
   invalidates cache/repeat, and selects `Running(Suspended(Reconfiguring))`; it neither allocates/retries Framework nor revokes entered delivery, and coherent
   State/Stats publication follows gate release.
3. Later reconciliation stays suspended until complete healthy `FrameworkJpegOwner` installation, which alone reopens admission and permits Active.
4. A later frame uses that owner with the same carrier; Direct readback and the current Full or Downscaled target remain unchanged throughout.

A nonreturn or uncertain carrier/sink/writer ownership takes the unsafe terminal path instead.

### 10.5 Unsubscribe versus queued callback

1. One callback task is accepted by the dispatcher but has not entered.
2. `unsubscribe()` closes the registration and waits for that exact record.
3. The trampoline enters before the five-second deadline, sees the closed generation, invokes no user code,
   and resolves the record.
4. `unsubscribe()` returns successfully; only now can a replacement register.

If the entry deadline wins, the Session fails and replacement remains forbidden until the documented terminal
mapping; timeout alone does not fabricate record release.

If unsubscribe instead detaches while `dispatcher.dispatch` remains in-call, a later normal dispatch return
arms the same existing five-second entry/self-rejection deadline. Trampoline entry resolves that detached
record without user-code entry. This is another ordering of the same handoff occurrence, not a new timer or
trace.

### 10.6 Stop versus callback entry

1. A dispatched trampoline and `stop()` race for their respective commit points.
2. If callback entry commits first under `sessionGate -> settlementGate`, user code may run and return after `stop()` returns.
3. If stop commits first, the trampoline is fenced and invokes no user code.
4. In both orders, no later frame delivery is admitted and the callback lease settles or remains quarantined. A return cell complete before terminal arbitration
   is folded into final Stats; a return committed only after whole-occurrence transfer is cleanup-only.

### 10.7 Resource limit and allocation around retirement

1. Reconciliation checks dimensions, arithmetic, and deterministic API/backend limits without allocating a replacement.
2. A current deterministic limit found before retirement leaves the old scope parked and publishes
   `Suspended(ResourceExhausted)` with latest desire retained.
3. Otherwise the old scope retires before one replacement allocation begins.
4. A current required replacement allocation failure after that point is terminal `ResourceExhausted`.

No branch silently restores a resource already destroyed or keeps two healthy complete pipelines.

## 11. Coverage Map

This map provides the single traceability from each material behavior to its acceptance scenarios, executable
sections, and paper traces.

| Concern | Acceptance scenarios | Primary executable sections | Paper trace |
| --- | --- | --- | --- |
| Public API and values | 1–2 | 4.1 | — |
| Lifecycle and latest-wins updates | 3–7 | 4.2 | 10.1, 10.2, 10.6 |
| Metrics and Android capture | 8–10 | 5 | 10.3 |
| Geometry, color, JPEG | 11–12, 16 | 6.1–6.2 | — |
| Pacing, repeat, cache, Stats | 13–15 | 6.3 | — |
| Optional paths and fallback | 17–18 | 7 | 10.4 |
| Consumer, frame lease, and callback deadline | 19–24 | 8, 9.1 | 10.5, 10.6 |
| Resource bounds, allocation, cleanup, privacy | 24–25 | 9.1, 9.3 | 10.7 |
| Diagnostics | 26 | 9.2 | — |
| Independent Sessions | 27 | 4.2, 9 | — |

## 12. Post-Implementation Manual Smoke Check

Before release, run a short manual smoke check on an available physical device. Brief test notes are
sufficient. Confirm consent and start, visible JPEG output, rotation/resize, a parameter update, consumer
unsubscribe/re-register, stop, and absence of an obvious crash, leak, black frame, wrong orientation, or
broken color. Exercise an optional path only when the device naturally enables it.

This smoke check is outside Gate A. It supplements automated tests for real MediaProjection, Surface,
BufferQueue, GPU/driver, and native behavior; it does not select runtime behavior or create a device allowlist.

## 13. Gate-A Verification Readiness

The verification design is ready for Gate A when:

- every scenario in Section 2 has the unambiguous expected result stated here or in 01 Design;
- the component boundaries and failure classifications needed to implement those scenarios are fixed in
  02 Architecture;
- the seven high-risk traces have no missing owner, contradictory winner, or hidden product decision;
- the automated JPEG checks use the finite oracle in Section 3.4, while the practical device smoke check
  remains a post-implementation activity;
- a fresh independent review reports no unresolved material product or architecture contradiction in the
  five-document package.

Gate-A status and the review decision are recorded in [04 Gate A](04_gate_a.md). Exact implementation
constants, concrete image vectors/tolerances, and executable test organization are completed in
[05 Gate-B Inputs](05_gate_b_inputs.md) before Kotlin implementation begins.
