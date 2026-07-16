# Screen Capture Engine — Delivery and Observation

## Navigation

- [Scope and interfaces](#1-scope-and-interfaces)
- [DEL-PACE-001](#del-pace-001--pending-source-and-materialization)
- [DEL-PACE-010](#del-pace-010--clock-cadence-and-one-wake)
- [DEL-PACE-020](#del-pace-020--repeat-cache-eligibility-and-output-commit)
- [DEL-HO-001](#del-ho-001--one-registration-and-one-handoff)
- [DEL-HO-010](#del-ho-010--dispatch-entry-callback-and-classification)
- [DEL-HO-020](#del-ho-020--borrowed-frame-and-synchronous-callback-boundary)
- [DEL-HO-030](#del-ho-030--unsubscribe-and-replacement)
- [DEL-HO-040](#del-ho-040--terminal-cutoff-and-delivery-cleanup)
- [DEL-OBS-001](#del-obs-001--coherent-state-and-publication-order)
- [DEL-OBS-010](#del-obs-010--stats-accounting-and-cadence)
- [DEL-OBS-020](#del-obs-020--diagnostic-construction-and-emission)
- [Timeout source matrix](#timeout-source-matrix)
- [Structural bounds](#2-structural-bounds)
- [Exact verification rows](#3-exact-verification-rows)
- [DEL-900](#del-900--forbidden-alternatives)

## 1. Scope and interfaces

This leaf owns pacing calculation and wake execution over the `TGT-050` signal and sole `STORE-020` production
role, delegated repeat/cache mechanics, the physical frame-consumer handoff, subscribe/unsubscribe mechanics,
and unlocked immutable State/Stats/diagnostic construction and publication. Public frame, State, Stats, and
diagnostic schema stay respectively in
[PROD-040](02-product-contract.md#prod-040--effective-output-and-borrowed-frame),
[PROD-060](02-product-contract.md#prod-060--state-and-errors),
[PROD-070](02-product-contract.md#prod-070--stats), and
[PROD-080](02-product-contract.md#prod-080--diagnostics); caller-visible handoff behavior remains
[PROD-050](02-product-contract.md#prod-050--frame-delivery-caching-and-replacement).
Generic occurrence/publication mechanics stay in
[CORE-SET-1](03-shared-runtime.md#core-set-1--generic-operation-occurrence),
[CORE-SET-2](03-shared-runtime.md#core-set-2--entry-return-publication-and-lock-order), and
[CORE-SET-3](03-shared-runtime.md#core-set-3--finite-deadline-arbitration); generic residue stays in
[CORE-CLEAN-1](03-shared-runtime.md#core-clean-1--cleanup-forest-and-dependency-graph) and
[CORE-CLEAN-2](03-shared-runtime.md#core-clean-2--quarantine-nonreturn-and-late-reduction).

The controller alone owns lifecycle, desired/current topology, pacing policy and phases, attempt identities,
counters, public observation values, diagnostic sequence, arbitration, and application. `PacingOwner`,
`DeliveryOwner`, and `ObservationOwner` execute only a controller-authorized action or return an immutable fact;
none can commit controller state. Exact authority and cutoff are
[CTRL-200](05-domain-controller-reconciliation.md#ctrl-200--policy-attempt-counter-and-observation-authority),
[CTRL-300](05-domain-controller-reconciliation.md#ctrl-300--cross-domain-commit-rules), and
[CTRL-400](05-domain-controller-reconciliation.md#ctrl-400--cleanuproot-handoff). Payload bytes, role state,
transactions, and leases are owned by [STORE-020](11-domain-encoded-storage.md#store-020--closed-owner-roles),
[STORE-070](11-domain-encoded-storage.md#store-070--publish-cache-repeat-lease-and-retirement), and
[STORE-080](11-domain-encoded-storage.md#store-080--failure-terminal-and-cleanup).

| Direction | Typed boundary | Rule |
| --- | --- | --- |
| [TGT-050](07-domain-target.md#tgt-050--listener-source-bit-and-generation-fence) -> controller | generation-fenced `AtomicBoolean` latest-pending bit plus lossless signal | Listener only sets `true`; it selects no work and owns no counter. |
| Controller -> `PacingOwner` | immutable policy, current wake/candidate identities, grant phase/times, pending/storage occupancy and sampled `EngineClock` | Helper returns one calculation; controller alone accepts it and commits exchange, grant, wake, or terminal action. |
| `STORE-070` -> delivery | immutable encoded payload metadata plus one counted `EncodedPayloadLease` | Delivery borrows one lease; it never owns/copies/mutates payload storage. |
| Delivery -> controller | identity-fenced scheduler, dispatch, trampoline-entry, callback-return, and handoff-convergence facts | Controller applies admission, counter, terminal-cutoff, and shared unsubscribe result. |
| Controller -> `ObservationOwner` | one immutable committed State/Stats/diagnostic snapshot | Helper constructs and assigns/tries emission unlocked and retains no authority. |

Production files are `DEL:PacingOwner.kt`, `DEL:DeliveryOwner.kt`, and `DEL:ObservationOwner.kt`, with canonical
paths in [router §6](01-authority-router.md#6-source-manifest). `PacingOwner` contains pure cadence and candidate
calculations, `DeliveryOwner` the cohesive handoff/record lifecycle, and `ObservationOwner` unlocked immutable
construction and publication. They retain the sole-owner boundaries below and create no second controller state,
Session scope, dispatcher, scheduler, gate, or handoff machine. This leaf
constructs and emits the product-owned `ScreenCaptureOutput` and observation values but owns no public source
file.

## DEL-PACE-001 — Pending source and materialization

Each Target generation owns exactly one `AtomicBoolean`, initially false. A current listener callback performs
`set(true)` and signals the lossless controller drainer. Repeated sets coalesce and are neither attempts nor
drops. A retired generation can neither signal nor affect a current generation.

Only a controller-authorized consume performs `getAndSet(false)`. It does so exactly when currentness, topology,
fresh cadence, and the sole production slot permit materialization. That exchange creates one fresh-attempt
occurrence, reserves its final-outcome slot, and claims the production slot. A set before the exchange belongs to
that attempt; a set after it remains true for a later rescan. Deferral never clears the bit. Retirement,
suspension, rebuild, Target replacement, or terminal may clear the unmaterialized bit with no counter.

The production slot stays occupied through tentative storage/JPEG work and a completed-unpublished JPEG until
one final disposition: output/cache commit, one classified drop, or terminal transfer. One pending bit may coexist
with the occupied slot, but no second attempt and no JPEG queue exist.

| Calculated/observed condition | Exact `CTRL-200` commit |
| --- | --- |
| source arrives before eligibility | retain one pending bit; no materialization and no drop |
| eligible and slot free | exchange bit, create one attempt, reserve final disposition and slot |
| bounded owner slot synchronously rejects a materialized submission before entry because capacity changed | `byPipelineBusy` exactly once |
| mechanically returned production failure | `byFailure`, even when identity is stale; unsafe failure remains terminal |
| otherwise successful result suppressed solely by stale identity | `byStaleWork` |
| successful current cache/output commit | success; no frame drop |
| terminal retires unclassified attempt, unpublished JPEG, or whole unresolved occurrence | no frame-drop increment |

`byRateLimit` remains zero: cadence retention is pre-materialization, never a classified drop.

## DEL-PACE-010 — Clock, cadence, and one wake

Cadence arithmetic and wake eligibility consume the injected `EngineClock`, checked conversion, and ordering
contract in [CORE-TIME-1](03-shared-runtime.md#core-time-1--clock-and-named-completion-rules). A
`SurfaceTexture` producer timestamp is diagnostic-only and never chooses a fresh/output grant, repeat eligibility,
or public frame timestamp.

The controller owns one current pacing/repeat wake carrier, separate from the Stats wake. It represents the
earliest future eligibility among: pending fresh source, completed-unpublished JPEG, and current-cache repeat.
Replacing policy or a candidate first cancels/removes/coalesces the queued predecessor before the current post.
Physical cardinality is at most one queued submission plus one already-dequeued stale callback from a
cancel/dequeue race. `CTRL-200` commits replacement order and the current identity; this leaf executes the
authorized cancel/remove/coalesce/post calls. There is no timer queue or per-purpose timer set.

Dequeue consumes and checks identity before clock resampling. A stale callback returns without resampling or
offering an action/successor. A current callback resamples and returns one immutable calculation; `CTRL-200`
rechecks candidate identity, policy, and elapsed delta, then commits at most one action and one successor for this
leaf to execute. An early wake calculation proposes no action. There is no catch-up burst. Current required
submission rejection is offered as an immutable fact; the controller commits terminal `InternalFailure` with no
drop, while rejection after stale/detached/terminal disposition is cleanup-only. Thus a retained candidate is
current-wake represented, immediately controller-eligible, or closed by terminal failure—never silently stranded.

Fresh cadence rules:

- `Auto`: admit a pending source as soon as capacity permits.
- `SampleEvery`: grant the first current source immediately, then at most one pending source per elapsed interval.
- `MaxFps`: independently caps fresh grants and all output grants.

For `S=1_000_000_000`, `F=fps`, `q=S/F`, `r=S%F`, and `phase in 0 until F`, one actual grant computes
`sum=phase+r`, `carry=if(sum>=F) 1 else 0`, `phase=sum-carry*F`, and `requiredGap=q+carry`, anchored at its actual
grant time. Fresh and output phases are independent and advance only for their own actual grants. This prevents
catch-up while retaining rational cadence without truncation drift. Valid millisecond intervals convert by
checked arithmetic to positive representable nanoseconds; no sentinel deadline state exists.

## DEL-PACE-020 — Repeat, cache eligibility, and output commit

Repeat is a best-effort target maximum-silence interval, not an execution deadline, and is inactive until a
current fresh JPEG has published. When due it reuses the current immutable encoded
payload, creates new sequence/timestamp metadata and a lease transition, and performs no capture, GL, readback,
JPEG, or payload copy. Fresh unpublished output wins a tie; `MaxFps` may delay either because it caps all output.

`PacingOwner` calculates cache currentness from one controller snapshot as the conjunction of current
image-affecting plan (geometry, region, crop, output size, rotation, mirror, color, JPEG quality),
Target/render/JPEG generations, and absence of invalidation since
publication. `CTRL-200` alone accepts that calculation and commits the storage action. Pause, suspension,
untrusted source, Target replacement, render/JPEG rebuild or fallback, and terminal invalidate it. Frame-rate and
repeat-policy changes alone do not.

When the controller commits the Native-disable switching fence, this leaf admits no new fresh work, repeat,
cached-first offer, encode, or delivery until a complete Framework owner is installed. The switching frame has
already received its one disposition; there is no same-frame retry. This leaf applies that controller fact but
does not select backend health, fallback, or lifecycle state.

Each controller-committed fresh/repeat output receives the next nonreused Session sequence and current
`EngineClock` sample; equal timestamps are valid. Sequence exhaustion fails terminally before reuse. A
fresh/repeat commit increments
`framesProduced`; fresh JPEG success also increments `framesEncoded` and encode-size samples before later stale
suppression. A successful registration atomically checks current cache while claiming the normal handoff. A
current cache is offered with original bytes/sequence/timestamp/size and changes neither encode/production
counters nor pacing phase; absent/stale cache creates no waiting record.

## DEL-HO-001 — One registration and one handoff

`CTRL-200` owns the authoritative registration generation and admission. For its one controller-authorized
current generation, `DeliveryOwner` physically owns one record, one active delivery-worker occurrence, and one
encoded lease. Its execution service is a distinct non-owned per-Session `Dispatchers.IO.limitedParallelism(1)`
view; the owner submits one non-suspending Runnable at a time and claims no physical thread identity, view
termination, or cleanup receipt. Sequential records may reuse capacity; callbacks, cache offers, repeats, and
unsubscribe create no parallel worker or queue. A generation must reach exact successful unsubscribe before
replacement; old and new callbacks never overlap.

Each record precreates, under its `settlementGate`, three independent one-shot cells:

```kotlin
internal enum class HandoffState {
    Prepared, Dispatching, AcceptedQueued, DetachedPreEntry, Entered, Resolved, Quarantined
}
```

The record cells are the actual dispatcher-call return (normal or exact throwable/rejection), separate
trampoline entry/detached self-rejection, and callback return (normal or exact throwable). The record also owns
its borrowed-frame authority and encoded lease until callback settlement or exact unentered resolution/transfer,
plus worker/trampoline sides, fixed scalar/reference fields, and an optional accepted-task deadline link. These
declarations are closed typed shapes; they are not public models or alternate settlement machinery.

```text
Prepared -> Dispatching -> AcceptedQueued -> Entered -> Resolved
Prepared -> Resolved
Dispatching -> Entered
Dispatching -> Resolved
Dispatching | AcceptedQueued -> DetachedPreEntry -> Resolved
Dispatching | AcceptedQueued | DetachedPreEntry | Entered -> Quarantined
Resolved -> idle  (only while the same registration remains active)
```

| State | Remaining exact ownership |
| --- | --- |
| `Prepared` | admitted generation, record, three cells, borrowed authority, lease and worker capacity; no caller dispatch entry |
| `Dispatching` | in-call dispatch side, worker, trampoline, borrowed authority and lease until their exact sides settle |
| `AcceptedQueued` | queued trampoline, accepted-task deadline, borrowed authority and lease; dispatch side settled |
| `DetachedPreEntry` | closed admission; unresolved dispatch side if any, trampoline self-rejection/entry side, deadline when armed, borrowed authority and lease; user code is forbidden |
| `Entered` | while the callback-return cell is empty, the callback side owns borrowed authority/lease; after callback settlement they release immediately, while any unresolved dispatch side alone retains record/worker and the state remains `Entered` |
| `Resolved` | every required side settled and no handoff owner remains |
| `Quarantined` | only unresolved cells and their exact worker/trampoline sides remain; callback authority/lease remain only when the callback-return cell is empty; the residue is rooted once and never reusable |

## DEL-HO-010 — Dispatch, entry, callback, and classification

The worker commits `Prepared -> Dispatching` under the record gate, unlocks, then directly calls
`frameCallbackDispatcher.dispatch(EmptyCoroutineContext, trampoline)`. It catches and commits the actual call
outcome allocation-free. Trampoline entry alone takes `sessionGate -> settlementGate`, verifies Session and
registration admission, commits `Entered` or detached self-rejection, then unlocks before user code. No external
call or callback runs under either gate.

| Winning mechanical fact | Controller application under `CTRL-200`/`CTRL-300` |
| --- | --- |
| trampoline enters before dispatcher outcome | entry owns classification; invoke callback; later dispatch outcome settles only its side |
| normal dispatch return before entry | `AcceptedQueued`; sample return time and arm the accepted-task entry deadline defined by [PROD-050](02-product-contract.md#prod-050--frame-delivery-caching-and-replacement) |
| dispatcher throw/rejection before entry | resolve opportunity; controller increments `byDispatchFailure` and requests `DeliveryProblem`; registration remains active; no retry |
| callback normal return before terminal transfer | release callback authority/lease; no delivery drop |
| callback throw before terminal transfer | release callback authority/lease; controller increments `byCallbackFailure` and requests `DeliveryProblem`; registration remains active |
| new opportunity while any required record side remains occupied | controller increments `byConsumerBusy`; create no record/worker/submission |
| active delivery-worker scheduling rejection before detach/entry/terminal | controller commits terminal `InternalFailure`; no `byDispatchFailure`; safely unentered record/lease resolve in terminal cleanup |
| scheduling rejection after another disposition | cleanup-only |

If callback returns inline while `dispatch` remains in call, callback authority and lease release immediately.
The record remains `Entered`, and only its record/worker/dispatch side stays occupied until the actual dispatch
return/throw; only then can it resolve. The later outcome cannot reclassify entry or increment
`byDispatchFailure`. During this composite interval a new opportunity is `byConsumerBusy`, and unsubscribe waits
for dispatch settlement.

A normal dispatch return for `AcceptedQueued` or a detached in-call record starts the same accepted-task deadline
at that return sample. Entry/self-rejection at or beyond the deadline, or an empty entry fact observed at/after it,
claims expiry under `sessionGate -> settlementGate`. While nonterminal this is `Failed(InternalFailure)`; after a
terminal winner it only roots unresolved residue. A late trampoline invokes no user code and may resolve only its
record. An entered callback and a caller `dispatch` call have no execution watchdog. Nonreturn keeps the one
record occupied; terminal transfers the exact residue.

## DEL-HO-020 — Borrowed frame and synchronous callback boundary

The callback receives one `EncodedImageFrame` backed by the record's counted storage lease. The trampoline records
the callback thread before invocation. Every property, `copyTo`, and `copyBytes` validates both that thread and the
still-open callback authority. Callback return/throw closes authority synchronously before releasing the lease.
Wrong-thread or post-return access throws `IllegalStateException`; invalid `copyTo` range throws
`IndexOutOfBoundsException` and modifies no destination byte. Only caller-created copies may outlive the callback.

Delivery never exposes raw storage, partial/tentative bytes, mutable buffers, or an unleased payload. It does not
retain caller callback/dispatcher data beyond the current registration and exact unresolved handoff.

## DEL-HO-030 — Unsubscribe and replacement

The controller handling the first `unsubscribe()` commits `Open -> Closing` under `sessionGate`, closing
admission immediately. `Prepared`
resolves directly; other states converge through their exact cells. Handoff convergence publishes one
identity-fenced mechanical fact. The controller alone commits one shared monotone result and signals waiters after
unlocking:

```text
Open -> Closing -> Success
Open | Closing -> FailedTerminal(problem)
Open | Closing -> StoppedTerminal(reason)
```

`Success` clears replacement exclusion and is idempotent. Terminal/quarantine winning first maps Failure to
`ScreenCaptureException(problem)` and Stop to `CancellationException`; it leaves replacement forbidden. Caller
wait cancellation affects only that waiter and cannot reopen admission or fabricate settlement; another call may
await the same result. Self-unsubscribe is detected by exact callback-thread identity and fails fast with
`IllegalStateException` before `Closing`. A late terminal cannot revoke committed `Success`.

## DEL-HO-040 — Terminal cutoff and delivery cleanup

Controller terminal arbitration folds every complete callback cell through ordinary accounting before final Stats. A
complete callback side releases its authority/lease; an unresolved dispatch side transfers only its worker/call
residue. If the callback cell is empty, the writable cell, callback occurrence, borrowed authority and lease
transfer whole. Later facts can reduce only that exact cleanup/quarantine child and cannot change counters,
`DeliveryProblem`, unsubscribe result, State, or terminal winner. `QuarantineChanged` is attempted only after an
actual root mutation; cleanup completed before attachment emits none.

Delivery cleanup owns record/cell/worker/trampoline and encoded-lease retirement. It fabricates no dispatcher,
entry, callback, or release fact. Storage frees displaced bytes only after the real counted lease receipt under
[STORE-070](11-domain-encoded-storage.md#store-070--publish-cache-repeat-lease-and-retirement); active-to-cleanup
commit is [CTRL-400](05-domain-controller-reconciliation.md#ctrl-400--cleanuproot-handoff), while generic root
shape and late reduction remain `CORE-CLEAN-1`/`CORE-CLEAN-2`.

## DEL-OBS-001 — Coherent State and publication order

At turn end, after authoritative state and cleanup scheduling are fixed, the controller passes one immutable
snapshot to `ObservationOwner`. It builds and assigns outside all gates and retains no state. Requested
parameters, running/effective state, and visibility form one immutable Running value; rapid updates may conflate
whole equal values but never mix fields from commits. Startup succeeds only after Running assignment.

Terminal publishes final Stats, then makes the one `SessionTerminal` attempt, then assigns terminal State. State
and Stats remain separate StateFlows, not an atomic pair. Supported collectors are nonblocking and non-reentrant;
their execution owns no resource or progress receipt. Publication delay cannot alter already-committed control.
There is no observation worker, observer queue, or helper-owned dispatcher: assignment/emission is direct at the
unlocked end of the controller turn, and collector resumption follows Flow semantics. State/Stats backpressure is
latest-value conflation; diagnostics follows the configuration in
[PROD-080](02-product-contract.md#prod-080--diagnostics).

Publication input is already fenced by the controller's Session lifecycle, desired revision, topology/work
identity, and terminal cutoff. `ObservationOwner` neither joins asynchronous sources nor rechecks/reinterprets
epochs. Native-disable and previously Running GL-poison gaps therefore publish the controller-selected
`Running(Suspended(Reconfiguring))`; startup remains `Starting`. GL namespace retirement may publish the selected
`ResourceExhausted` only after its successful aggregate receipt; ambiguity publishes `InternalFailure`, and a
prior higher-priority terminal remains final.

## DEL-OBS-010 — Stats accounting and cadence

The controller stores counters and ordered accumulators; `ObservationOwner` builds immutable public values.
Counters and totals saturate at `Long.MAX_VALUE`. Every materialized production disposition and delivery outcome
is counted exactly once according to `DEL-PACE-001` and `DEL-HO-010`. `framesEncoded` and byte/encode samples come
from mechanically successful fresh encoding, including success later suppressed as stale; `framesProduced` comes
from fresh/repeat output commits. Successful real readback/encode duration samples likewise remain samples when
later currentness suppresses output. Repeat adds no encode/readback sample; cached-first adds no counter.

Each ordered eligible duration or encoded-byte sample updates its matching controller accumulator at commit.
Production commits retain their first and latest `EngineClock` samples for the FPS projection.
`ObservationOwner` projects these inputs exactly as specified by
[PROD-070](02-product-contract.md#prod-070--stats), including its sample calculation, ordered-mean,
empty-sample, unit-conversion, rounding, finite, and saturation rules; each visibility-worthy protection requests
one `StatsProblem` with null cause.

Accepted start publishes the initial all-zero runtime snapshot immediately. Lifecycle, suspension/resume,
rebuild/fallback, and terminal changes publish dirty Stats immediately. Other dirty
facts coalesce behind one identity-fenced wake anchored at the previous publication; the earliest next ordinary
publication follows the ordinary Stats cadence defined by
[PROD-070](02-product-contract.md#prod-070--stats). There is no catch-up, and scheduler/collector delay gives no
cadence-bound delivery promise. No dirtiness arms no wake. Final Stats is immediate and uses the
production/callback cutoff in
[CTRL-200](05-domain-controller-reconciliation.md#ctrl-200--policy-attempt-counter-and-observation-authority).

At that cutoff the controller folds each already-complete production return, selected production disposition,
and complete callback cell once. Existing output/cache/drop commits remain; unclassified attempt retirement,
completed-unpublished retirement, or whole-record transfer invents no drop. Facts committed only after transfer
are cleanup-only and cannot revise final Stats.

## DEL-OBS-020 — Diagnostic construction and emission

The Session owns the exact diagnostic `MutableSharedFlow` configuration defined by
[PROD-080](02-product-contract.md#prod-080--diagnostics). After the triggering fact is durable and gates are
released, the controller assigns the next nonreused Session sequence (first is 1), samples wall-clock
`timestampEpochMillis`, and requests one typed constructor. `ObservationOwner` builds a short semantic message
without Throwable text and calls `tryEmit` once. Gaps are valid; wall time may repeat or move.
Allocation/emission failure, loss, collector state, and raw cause retention never control the engine. Sequence
exhaustion fails terminally before wrap or reuse.

| Label | Exact source/site and payload rule |
| --- | --- |
| `CapabilityCheck` | Boundary source for each top-level capability selection/failure; name boundary, decision and action; carry exact raw cause when present. |
| `RuntimeProfile` | `Session`, once at initial Running; name selected Target, Direct readback/precision, JPEG/transfer/color modes; null cause. |
| `RuntimeModeChanged` | `SurfaceTarget` for safe Downscaled -> Full, `FrameworkJpeg` for actual transfer-mode change, or `NativeJpeg` for Native -> Framework disablement; name old/new/action and carry a safe returned cause when present; never emit for timeout, stale cleanup, or a considered-only change. |
| `DeliveryProblem` | `FrameConsumer` for caller-dispatch failure committed before entry or callback throw committed before terminal transfer; exact throwable. Busy is Stats-only. |
| `StatsProblem` | `Controller` for finite retention/clamp/freeze; null cause. |
| `ColorAction` | `ColorPipeline` once per Target and on actual classification/action change; null cause. |
| `QuarantineChanged` | `Cleanup` only after attaching/reducing/removing an exact root child; raw cleanup cause when present. |
| `SessionTerminal` | `Session`, once for winning terminal after final Stats and before terminal State; name reason/problem and last modes. Owner/capture stop and pre-Active valid-then-invalid metrics use null cause; another Failure carries its selected raw cause when defined; timeout reuses its `CapabilityCheck` cause. |

Routine geometry, visibility, rebuild, consumer lifecycle, and frame production require no event. Provider/callback
faults keep the boundary source. All allowed source strings and the public six-field event schema are owned by
[PROD-080](02-product-contract.md#prod-080--diagnostics).

### Timeout source matrix

This is the sole cross-domain timeout-to-diagnostic table. The referenced domain owns each interval and injected
boundary cases; `CTRL-300` owns request selection/source/cause, while this leaf only constructs/emits its accepted
immutable request.

| Family | `CapabilityCheck` source | Winning active result / terminal event |
| --- | --- | --- |
| first metrics readiness ([AND-CAP-040](06-domain-android-capture-metrics.md#and-cap-040--android-deadlines-and-call-policy)) | `MetricsProvider` | `CaptureUnavailable`; `SessionTerminal` reuses the same timeout-cause object |
| initial captured resize readiness, API 34–37 ([AND-CAP-040](06-domain-android-capture-metrics.md#and-cap-040--android-deadlines-and-call-policy)) | `MediaProjection` | `CaptureUnavailable`; same cause object |
| Android entered operation ([AND-CAP-040](06-domain-android-capture-metrics.md#and-cap-040--android-deadlines-and-call-policy)) | `MediaProjection` for callback registration/create; `VirtualDisplay` for resize/setSurface; `SurfaceTarget` for listener/Surface calls | `InternalFailure`; same cause object |
| GL entered operation ([GL-040](08-domain-gl.md#gl-040--fail-closed-gles-groups-and-context-integrity)) | `GlPipeline` | `InternalFailure`; same cause object |
| JPEG entered operation ([FJPEG-090](09-domain-framework-jpeg.md#fjpeg-090--exact-operation-deadline-policy), [NJPEG-CONST-001](10-domain-native-jpeg.md#njpeg-const-001--jpeg-entered-operation-interval)) | `FrameworkJpeg` for Framework creation/encode/recycle/managed replacement; `NativeJpeg` for Native preparation/encode/free/native replacement | `InternalFailure`; same cause object |

A higher-priority terminal makes expiry cleanup-only and emits no timeout event. Timeout creates no fallback,
retry, runtime-mode change, duration telemetry, aggregate, or new public field.

## 2. Structural bounds

| Binding | Exact shape |
| --- | --- |
| production/delivery capacity | one production slot, one pending bit per current Target generation, one registration, one handoff, one active delivery worker, one encoded lease |
| pacing/repeat wake | one queued submission plus at most one already-dequeued stale callback; separate from the Stats wake |

No additional delivery/observation private numeric policy or test tolerance exists. Frame-rate and repeat input
ranges remain public in `PROD-030`; image/JPEG tolerances belong to their image-domain verification rows.

## 3. Exact verification rows

The closed implementation packet is indexed by
[router Section 5](01-authority-router.md#5-closed-implementation-packets); executable, acceptance, boundary, and
trace indexes are in [Document 04](04-verification.md#4-executable-namespaces). The matrices below retain this
leaf's local executable proofs; controller authority remains `CTRL-200`/`CTRL-300`/`CTRL-400`.

| Tests | Required matrix |
| --- | --- |
| `H-PS` | source set before/after exchange; retired generation; Auto/MaxFps/SampleEvery boundaries; early retention; occupied/unpublished slot plus pending bit; one-wake churn/cancel/dequeue/current-stale rejection; no burst/stranding; rational phases; repeat/cache/cached-first; all production dispositions; terminal cutoff; Stats formulas/cadence/saturation/finite protection |
| `H-DL` | every state; worker rejection; dispatch normal/throw/reject/nonreturn crossed with entry, unsubscribe and terminal; inline callback settlement with unresolved dispatch keeps `Entered`, releases callback authority/lease to zero, leaves only record/worker/dispatch residue, rejects the next opportunity as busy, makes unsubscribe wait, and resolves only after dispatch settlement; entry at deadline-1/equal/after; detached late normal return and self-rejection; callback return/throw/nonreturn before/after transfer; shared unsubscribe result, cancellation, self-call, replacement exclusion; exact lease/root reduction |
| `H-OB` | coherent Running snapshots, StateFlow conflation, startup/terminal order, all-zero initial Stats, exact formulas and finite guards, immediate/periodic/final publication, all eight categories/sites, cause identity, first/overflow sequence, wall-clock movement, no-emission/noncontrol and terminal cutoff |
| `A-SES` | installed callback dispatcher/thread and borrowed-frame access/range/lifetime; cached-first metadata; registration replacement; main-safe unsubscribe and exact public exception mapping |
| `H-OS`, `A-CL` | generic gate/deadline and whole-occurrence terminal-transfer mechanics by reference; late delivery facts shrink only exact residue and never revise observations |

Required delivery interleavings:

| Order | Observable result |
| --- | --- |
| dispatcher rejection -> entry | one `byDispatchFailure`; trampoline is fenced; registration remains active |
| entry -> late dispatcher throw | callback outcome owns classification; no dispatch drop |
| callback return -> dispatch return | state stays `Entered`; callback authority/lease become zero immediately; only record/worker/dispatch residue remains, next opportunity is `byConsumerBusy`, unsubscribe waits, and resolution occurs only after dispatch settles |
| unsubscribe/stop -> trampoline | detached self-rejection, no user code; exact record resolves or remains deadline/root residue |
| trampoline -> unsubscribe/stop | entered callback may finish after stop return; no later admission |
| callback fact commit -> terminal transfer | fold normal/throw once into final Stats; transfer only unresolved sides |
| terminal transfer -> callback fact commit | cleanup-only release/root reduction; no counter/event/State mutation |
| current wake rejection -> policy replacement | terminal `InternalFailure` if rejection won while required/current; otherwise stale cleanup-only |

All deadline boundary tests use exact checked `D`, `D-1`, `D`, `D+1`, empty-at-`D`, commit-before-signal,
sample-before-gate pause, expiry/return orders, terminal transfer, and late fact. Deterministic clocks, barriers,
dispatcher/scheduler seams, fixed slots, and ownership ledgers are required; no real sleep, scheduler luck, log
parsing, or repetition-until-race is an oracle.

## DEL-900 — Forbidden alternatives

- No per-signal attempt, rate-limit drop, producer-timestamp authority, JPEG queue, timer queue, catch-up burst,
  or second production slot.
- No helper-owned lifecycle, policy, phase, attempt identity, counter, public value, sequence, arbitration, or
  commit; controller facts are never reinterpreted locally.
- No callback execution under gates, coroutine wrapper around the direct dispatcher boundary, parallel delivery
  worker, retry queue, or immediate redispatch of a rejected record.
- No dispatcher-call or entered-callback watchdog, fabricated cancellation/return/release, callback interruption,
  or replacement before complete shared unsubscribe success.
- No borrowed-frame escape, raw/tentative storage exposure, post-return access, payload copy for repeat, or
  cached-first production/pacing mutation.
- No post-terminal counter, `DeliveryProblem`, State, terminal-winner, or unsubscribe-result change from a late
  fact; cleanup owns only exact transferred residue.
- No combined State/Stats snapshot claim, blocking publication under gates, diagnostic retry/reliability/control,
  generic public label constructor, Throwable-text parsing, routine per-frame event, or wall-clock ordering.

## 5. Official sources

- Kotlin [`CoroutineDispatcher`](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/-coroutine-dispatcher/)
  and [coroutine dispatchers](https://kotlinlang.org/docs/coroutine-context-and-dispatchers.html).
- Kotlin [`StateFlow`](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/-state-flow/),
  [`MutableSharedFlow`](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/-mutable-shared-flow/),
  and [`BufferOverflow`](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.channels/-buffer-overflow/).
- Kotlin [cancellation](https://kotlinlang.org/docs/cancellation-and-timeouts.html).

Official public behavior is the boundary. Implementation source or observed scheduler behavior cannot create a
progress, timing, callback, or collection guarantee absent from these contracts.
