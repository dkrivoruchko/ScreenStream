# Screen Capture Engine — Delivery and Observation

## Navigation

- [Scope and interfaces](#1-scope-and-interfaces)
- [DEL-PACE-001](#del-pace-001--pending-source-and-materialization)
- [DEL-PACE-010](#del-pace-010--clock-cadence-and-one-wake)
- [DEL-PACE-020](#del-pace-020--repeat-cache-eligibility-and-output-commit)
- [DEL-HO-001](#del-ho-001--one-registration-and-one-handoff)
- [DEL-HO-010](#del-ho-010--submission-entry-callback-and-classification)
- [DEL-HO-020](#del-ho-020--borrowed-frame-and-synchronous-callback-boundary)
- [DEL-HO-030](#del-ho-030--unsubscribe-and-replacement)
- [DEL-HO-040](#del-ho-040--terminal-cutoff-and-delivery-cleanup)
- [DEL-OBS-001](#del-obs-001--coherent-state-and-publication-order)
- [DEL-OBS-010](#del-obs-010--stats-accounting-and-cadence)
- [DEL-OBS-020](#del-obs-020--diagnostic-construction-and-emission)
- [Structural bounds](#2-structural-bounds)
- [Exact verification rows](#3-exact-verification-rows)
- [DEL-900](#del-900--safety-boundaries)
- [Official sources](#4-official-sources)

## 1. Scope and interfaces

This leaf owns pacing calculation and wake execution over the `TGT-050` signal and sole `STORE-020` production
role, delegated repeat/cache mechanics, paused-admission enforcement, the physical frame-consumer handoff, subscribe/unsubscribe mechanics,
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
| [TGT-050](07-domain-target.md#tgt-050--listener-source-bit-and-generation-fence) -> controller | one generation-fenced conflated pending indication plus lossless signal | Listener selects no work and owns no counter. |
| Controller -> `PacingOwner` | immutable policy, current wake/candidate identities, grant phase/times, pending/storage occupancy and sampled `EngineClock` | Helper returns one calculation; controller alone accepts it and commits exchange, grant, wake, or terminal action. |
| `STORE-070` -> delivery | immutable encoded payload metadata plus one counted `EncodedPayloadLease` | Delivery borrows one lease; it never owns/copies/mutates payload storage. |
| Delivery -> controller | identity-fenced scheduler, Delivery submission, callback-entry/return, and handoff-convergence facts | Controller applies admission, counter, terminal-cutoff, and shared unsubscribe result. |
| Controller -> `ObservationOwner` | one immutable committed State/Stats/diagnostic snapshot | Helper constructs and assigns/tries emission unlocked and retains no authority. |

Private pacing, handoff, and observation collaborators retain the sole-owner boundaries below and create no
second controller state, Session scope, scheduler, gate, or handoff machine. This leaf constructs/delivers public
frames and constructs/publishes observation values.

## DEL-PACE-001 — Pending source and materialization

Each Target generation owns one conflated pending-source indication. A current listener sets it and signals the
lossless drainer; repetitions are neither attempts nor drops, and retired generations are inert. Only a
controller-authorized consume may clear it, when currentness, topology, cadence, and the sole production slot
permit one fresh attempt. A signal racing after that consume remains pending for a later scan; deferral never
loses it. While paused there is no consume, materialization, or production submission. Retirement, suspension,
reconfiguration, or terminal may discard unmaterialized work without a counter.

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

Cadence retention is pre-materialization and therefore creates no drop.

## DEL-PACE-010 — Clock, cadence, and one wake

Cadence arithmetic and wake eligibility consume the injected `EngineClock`, checked conversion, and ordering
contract in [CORE-TIME-1](03-shared-runtime.md#core-time-1--clock-and-named-completion-rules). A
`SurfaceTexture` producer timestamp is diagnostic-only and never chooses a fresh/output grant, repeat eligibility,
or public frame timestamp.

The controller owns one current pacing/repeat wake link, separate from the Stats wake link. Both use the central
submission/Future/callback/cancellation/termination/successor mechanics in
[CORE-WAKE-2](03-shared-runtime.md#core-wake-2--one-shot-control-wake-link-and-scheduling-rejection); this leaf
retains only pacing/repeat eligibility and calculation. The pacing/repeat link represents the
earliest future eligibility among: pending fresh source, completed-unpublished JPEG, and current-cache repeat.
Replacing policy or a candidate first cancels/removes/coalesces the queued predecessor before the current post.
Operational cardinality is one current generation. A suppressed predecessor may retain only the exact physical
outer-frame residue permitted by `CORE-WAKE-2`; its removal success or outer return is not successor authority.
`CTRL-200` commits replacement order and the current identity; this leaf executes the authorized
cancel/remove/coalesce/post calls. There is no timer queue or per-purpose timer set.

After the central link publishes Fired, the leaf checks identity before clock resampling. A stale wrapper loses
its first generation CAS and returns before leaf access. A current fact resamples and returns one immutable calculation; `CTRL-200`
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
payload, creates new sequence/timestamp metadata with the current committed effective descriptor and a lease transition, and performs no capture, GL, readback,
JPEG, or payload copy. Fresh unpublished output wins a tie; `MaxFps` may delay either because it caps all output.

`PacingOwner` calculates cache currentness from one controller snapshot as the conjunction of Active lifecycle and current
image-affecting plan (geometry, region, crop, output size, rotation, mirror, color, JPEG quality),
Target/render/JPEG generations, and absence of invalidation since
publication. `CTRL-200` alone accepts that calculation and commits the storage action. Cache is unavailable
during every pause or suspension. Image-, backend-, or topology-affecting change, untrusted source, Target
replacement, render/JPEG rebuild, fallback, and terminal invalidate it. An exact-compatible frame-rate or
repeat-policy-only update may preserve the immutable bytes, but cached-first/repeat eligibility is rechecked only
at resume.

The `CTRL-120` Running value and admission cutoffs govern every reconciliation. This leaf admits no fresh
materialization, repeat, cached-first offer, encode, or new handoff until Active; `PROD-050` owns the caller
effect and `DEL-HO-001`/`DEL-HO-010` own the already-admitted handoff race. Each
controller-committed fresh/repeat output receives the next nonreused Session sequence and current
`EngineClock` sample; equal timestamps are valid. Sequence exhaustion fails terminally before reuse. A
fresh/repeat commit increments
`framesProduced`; fresh JPEG success also increments `framesEncoded` and encode-size samples before later stale
suppression. A successful Active registration atomically checks current cache while claiming the normal handoff.
A registration created in Reconfiguring or Suspended persists without a cache check or waiting record. When
Active is restored, ordinary
currentness/admission decides whether preserved bytes may be offered. A current cache is offered with original
bytes/sequence/timestamp/effective descriptor and changes neither encode/production counters nor pacing phase; absent/stale cache
creates no waiting record.

## DEL-HO-001 — One registration and one handoff

`CTRL-200` owns the authoritative registration generation and admission. For its one controller-authorized
current generation, `DeliveryOwner` physically owns one handoff record, one `CORE-EXEC-1` Delivery ticket, and
one encoded lease. The Session-owned Delivery endpoint is constructed and prestarted before callback admission;
the callback runs directly on its one thread. Sequential records may reuse the healthy endpoint, but at most one
submitted-not-fully-settled ticket exists. Callbacks, cache offers, repeats, and unsubscribe create no parallel
worker or delivery queue. A generation must reach exact successful unsubscribe before replacement; old and new
callbacks never overlap.

Registration may be created or remain current while paused, but creates no new handoff until Active. A handoff
whose creation/admission commit won before pause is grandfathered and may enter during
`Reconfiguring`; it remains bound to its immutable old output, effective descriptor, and registration generation.
This is draining delivery, not a new output. Each handoff independently settles submission, entry, callback
return, and Runnable return under one occurrence. It owns borrowed-frame authority and the encoded lease until
callback settlement or proven no-entry. Reconfiguration neither cancels nor waits for its app callback, so
callback duration does not block capture/production/Target resource drain; terminal still tracks callback/lease
residue. Full resolution requires every applicable side; terminal transfers only unresolved sides. Callback settlement releases callback authority and
lease immediately even when submission or Runnable return still keeps the handoff occupied. No callback
deadline exists. The one registration, one outstanding handoff, and serial Delivery lane prevent any later
post-resume callback from overlapping a grandfathered callback; existing storage bounds, backpressure, and drop
classification are unchanged.

## DEL-HO-010 — Submission, entry, callback, and classification

Submission follows `CORE-EXEC-1`. The Runnable's first domain action uses
`sessionGate -> settlementGate` to validate the committed handoff admission, registration generation, ticket,
endpoint health, and unsubscribe/terminal cutoff, then commits entered or inert before unlocking. A later
reconfiguration pause does not invalidate an otherwise-current admitted handoff. Only an entered winner invokes
the callback, and no external call or callback runs under either gate.

| Winning mechanical fact | Controller application under `CTRL-200`/`CTRL-300` |
| --- | --- |
| Runnable enters before `execute` outcome | entry owns callback classification; later submission outcome settles its own side and may poison/fail-close the endpoint |
| normal `execute` return before entry | the admitted handoff remains eligible to enter without an execution receipt or deadline; pause alone does not make it inert |
| `execute` throws `Exception` before entry | poison Delivery; controller commits terminal `InternalFailure`; no delivery drop or retry |
| `execute` throws `Error` or other direct non-`Exception` throwable | poison Delivery, retain exact raw fatal authority, and rethrow directly from the engine owner-Runnable boundary under `CORE-FATAL-1` |
| callback normal return before terminal transfer | release callback authority/lease; no delivery drop |
| callback throws `Exception` before terminal transfer | release callback authority/lease and report the exact callback-failure fact; controller increments `byCallbackFailure` and alone selects any diagnostic snapshot; registration remains active |
| callback throws `Error` or other direct non-`Exception` throwable | release only through fatal settlement, poison Delivery, fail-close, and directly rethrow the exact raw object from the engine owner-Runnable boundary; no delivery drop |
| new opportunity while any required record side remains occupied | controller increments `byConsumerBusy`; create no record/worker/submission |
| scheduling rejection after another disposition | cleanup-only |

If callback and Runnable return while `execute` remains in call, callback authority and lease release
immediately. The record remains `Entered`, and only its record/ticket/submission side stays occupied until the
actual `execute` return/throw; only then can it resolve. The later outcome cannot reclassify the callback, but an
outward throw still poisons Delivery and applies the internal submission failure policy. During this composite
interval a new opportunity is `byConsumerBusy`, and unsubscribe waits for submission settlement. An unentered
accepted Runnable has no queue-start watchdog. Submission nonreturn or Runnable nonreturn keeps the record
occupied; terminal transfers the exact residue.

## DEL-HO-020 — Borrowed frame and synchronous callback boundary

The callback receives one `EncodedImageFrame` backed by the record's counted storage lease. The Delivery Runnable
records its thread before invocation. Every property, `copyTo`, and `copyBytes` validates both that thread and the
still-open callback authority. Callback return/throw closes authority synchronously before releasing the lease.
Wrong-thread or post-return access throws `IllegalStateException`; invalid `copyTo` range throws
`IndexOutOfBoundsException` and modifies no destination byte. Only caller-created copies may outlive the callback.

The frame exposes the exact immutable effective descriptor captured by handoff admission. Fresh and repeat
handoffs use their committed output descriptor; cached-first uses the cached output's original descriptor; a
grandfathered handoff retains its old descriptor even if callback entry follows a newer `Active` commit.

Delivery never exposes raw storage, partial/tentative bytes, mutable buffers, or an unleased payload. It does not
retain caller callback data beyond the current registration and exact unresolved handoff.

## DEL-HO-030 — Unsubscribe and replacement

The first `unsubscribe()` closes admission immediately under `sessionGate`; unsubmitted work resolves directly
and other work converges through its exact sides. The controller alone commits one shared monotone success,
failed-terminal, or stopped-terminal result and signals waiters after unlocking.

`Success` clears replacement exclusion and is idempotent. Terminal/quarantine winning first maps Failure to
`ScreenCaptureException(problem)` and Stop to `CancellationException`; it leaves replacement forbidden. Caller
wait cancellation affects only that waiter and cannot reopen admission or fabricate settlement; another call may
await the same result. Self-unsubscribe is detected by exact callback-thread identity and fails fast with
`IllegalStateException` before `Closing`. A late terminal cannot revoke committed `Success`.

## DEL-HO-040 — Terminal cutoff and delivery cleanup

Controller terminal arbitration folds every complete callback cell through ordinary accounting before final Stats. A
complete callback side releases its authority/lease; an unresolved submission side transfers only its
ticket/endpoint residue. If the callback cell is empty, the writable cell, callback occurrence, borrowed authority and lease
transfer whole. Later facts can reduce only that exact cleanup/quarantine child and cannot change counters,
diagnostic selection, unsubscribe result, State, or terminal winner. A quarantine-mutation fact is offered only
after an actual root mutation; `CTRL-300` alone selects any `QuarantineChanged` snapshot, and cleanup completed
before attachment offers none.

Delivery cleanup owns record/cells/ticket/endpoint and encoded-lease retirement. It fabricates no submission,
entry, callback, Runnable-return, endpoint-termination, or release fact. After the final ticket settles, Delivery
receives one `shutdown()` and zero `shutdownNow()` calls; only `terminated()` releases its endpoint/thread root.
Storage frees displaced bytes only after the real counted lease receipt under
[STORE-070](11-domain-encoded-storage.md#store-070--publish-cache-repeat-lease-and-retirement); active-to-cleanup
commit is [CTRL-400](05-domain-controller-reconciliation.md#ctrl-400--cleanuproot-handoff), while generic root
shape and late reduction remain `CORE-CLEAN-1`/`CORE-CLEAN-2`.

## DEL-OBS-001 — Coherent State and publication order

At turn end, after authoritative state and cleanup scheduling are fixed, the controller passes one immutable
snapshot to `ObservationOwner`. It builds and assigns outside all gates and retains no state. Requested
parameters, direct Running variant fields, and visibility form one immutable Running value; rapid updates may conflate
whole equal values but never mix fields from commits. Startup succeeds only after Running assignment.

An accepted unequal update logically commits its latest requested value with `Reconfiguring` during the public
call. State assignment occurs before the first outward reconfiguration effect, but may occur after setter return
and collector resumption is never a synchronous oracle. During reconciliation the last effective value is
historical last committed Active output, not a claim that output remains available. Ordinary Stats is assigned only when
one of its public fields changes.

Terminal publishes final Stats, then emits the one controller-selected `SessionTerminal` attempt, then assigns terminal State. State
and Stats remain separate StateFlows, not an atomic pair. Supported collectors are nonblocking and non-reentrant;
their execution owns no resource or progress receipt. Publication delay cannot alter already-committed control.
There is no observation worker, observer queue, or helper-owned dispatcher: assignment/emission is direct at the
unlocked end of the controller turn, and collector resumption follows Flow semantics. State/Stats backpressure is
latest-value conflation; diagnostics follows the configuration in
[PROD-080](02-product-contract.md#prod-080--diagnostics).

Publication input is already fenced by the controller's Session lifecycle, desired revision, topology/work
identity, and terminal cutoff. `ObservationOwner` neither joins asynchronous sources nor rechecks/reinterprets
epochs. Native-disable and previously Running GL-poison gaps therefore publish the controller-selected
`Reconfiguring`; startup remains `Starting`. GL namespace retirement may publish the selected
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
empty-sample, unit-conversion, rounding, finite, and saturation rules; each visibility-worthy protection reports
one typed protection fact, from which `CTRL-300` alone selects any `StatsProblem`/null-cause snapshot.

Session creation publishes the initial all-zero Stats snapshot. Accepted start preserves that same value and
performs no Stats assignment or wake; a Stats fact becomes dirty only when an authoritative public field changes.
Reconfiguration alone does not assign an equal value. Dirty facts coalesce
behind one identity-fenced Stats wake link anchored at the previous publication; its physical
submission, cancellation, fire, termination fallback, and successor settlement are solely `CORE-WAKE-2`. The
earliest next ordinary
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
released, `CTRL-300` supplies one complete immutable snapshot containing its selected sequence, timestamp,
source, label, site/message inputs, and cause. `ObservationOwner` constructs the public event, builds the short
semantic message without Throwable text, and calls `tryEmit` once. It never selects or revises any field.
Gaps are valid; wall time may repeat or move.
Allocation/emission failure, loss, collector state, and raw cause retention never control the engine. Sequence
exhaustion fails terminally before wrap or reuse.

The public vocabulary and schema remain `PROD-080`; source/label/cause/site and timeout selection remain solely
`CTRL-300`. Routine facts produce an event only when that controller mapping selects one.

## 2. Structural bounds

| Binding | Exact shape |
| --- | --- |
| production/delivery capacity | one production slot, one pending bit per current Target generation, one registration, one handoff, one submitted-not-fully-settled Delivery ticket, one encoded lease |
| pacing/repeat wake | one current operational generation plus exact physically unsettled stale outer-frame residue permitted by `CORE-WAKE-2`; separate from the Stats wake |

No additional delivery/observation private numeric policy or test tolerance exists. Frame-rate and repeat input
ranges remain public in `PROD-030`; image/JPEG tolerances belong to their image-domain verification rows.

## 3. Exact verification rows

The closed implementation packet is indexed by
[router Section 5](01-authority-router.md#5-closed-implementation-packets); executable, acceptance, boundary, and
trace indexes are in [Document 04](04-verification.md#4-executable-namespaces). The matrices below retain this
leaf's local executable proofs; controller authority remains `CTRL-200`/`CTRL-300`/`CTRL-400`.

| Tests | Required matrix |
| --- | --- |
| `H-PS` | source exchange and retired generation; Auto/MaxFps/SampleEvery boundaries; early retention and occupied/unpublished-slot coalescing; common wake matrix for pacing/repeat and Stats; zero materialization/repeat/cached-first/new-handoff admission while paused; exact-compatible policy-only cache preservation versus image/backend/topology invalidation and resume currentness; no burst, stranding, or fabricated progress; rational phases; all production dispositions; terminal cutoff; Stats formulas/cadence/saturation/finite protection |
| `H-DL` | Delivery prestart/one-ticket mechanics and the `DEL-HO-001`/`DEL-HO-010` admission-first versus pause-first matrix, crossed with submission, callback, unsubscribe, terminal, poison/fatal, shutdown and exact lease/root settlement; `PROD-050` and `CTRL-120` remain the caller/lifecycle oracles |
| `H-OB` | coherent direct Running snapshots, logical requested/Reconfiguring commit and State order; creation-time all-zero Stats with no accepted-start assignment/wake, field-change-only dirty publication and final publication; faithful construction/one-shot emission of each `CTRL-300`-selected immutable diagnostic snapshot, sequence/cause identity, gaps/wall-clock movement, and noncontrol |
| `A-SES` | dedicated Delivery callback thread and borrowed-frame access/range/lifetime; cached-first metadata; registration replacement; main-safe unsubscribe and exact public exception mapping |
| `H-OS`, `A-CL` | generic gate/deadline and whole-occurrence terminal-transfer mechanics by reference; late delivery facts shrink only exact residue and never revise observations |

Required delivery interleavings:

| Order | Observable result |
| --- | --- |
| `execute` Exception -> entry | unentered record resolves into internal terminal cleanup; Delivery is poisoned; no delivery drop |
| entry -> late `execute` throw | callback outcome remains classified; Delivery is still poisoned and fail-closes; no delivery drop |
| callback return -> `execute` return | state stays `Entered`; callback authority/lease become zero immediately; only record/ticket/submission residue remains, next opportunity is `byConsumerBusy`, unsubscribe waits, and resolution occurs only after submission settles |
| handoff admission -> pause -> Runnable entry | the grandfathered old-generation handoff enters normally during Reconfiguring as draining delivery; reconfiguration does not wait for it, and serial Delivery prevents a later callback from overlapping it |
| pause -> handoff admission attempt | no handoff, submission, lease, or user-code entry is created; registration may persist until Active |
| unsubscribe/stop -> Runnable entry | detached/inert self-rejection, no user code; exact record resolves or remains root residue |
| Runnable entry -> unsubscribe/stop | entered callback may finish after stop return; no later admission |
| callback fact commit -> terminal transfer | fold normal/throw once into final Stats; transfer only unresolved sides |
| terminal transfer -> callback fact commit | cleanup-only release/root reduction; no counter/event/State mutation |
| current wake rejection -> policy replacement | terminal `InternalFailure` if rejection won while required/current; otherwise stale cleanup-only |
| successful suppression + outer remove false -> successor | after all required publications, the successor may become operationally current; remove false proves no dequeue/entry/no-run fact, and any stale frame has zero engine/domain access |
| suppressed stale wrapper nonreturns -> successor | the single Control worker prevents physical `g+1` entry; retain exact worker/frame cleanup residue and fabricate no Fired/return/termination receipt |

All applicable operation-deadline tests use exact checked `D`, `D-1`, `D`, `D+1`, empty-at-`D`, commit-before-signal,
sample-before-gate pause, expiry/return orders, terminal transfer, and late fact. Deterministic clocks, barriers,
executor/scheduler seams, fixed slots, and ownership ledgers are required; no real sleep, scheduler luck, log
parsing, or repetition-until-race is an oracle.

## DEL-900 — Safety boundaries

Delivery uses one pending indication, production slot, registration, handoff, encoded lease, and serial callback
endpoint. Controller facts remain the sole policy/counter/publication authority. Pause closes new output and
handoff admission while preserving an already-admitted handoff; terminal transfers only exact unresolved residue.

The safety oracle retains these critical absences: no callback under gates, interruption, or watchdog; no
fabricated cancellation/return/release; no raw, tentative, unleased, or post-return frame access; no post-terminal
observation mutation; and no diagnostic or wall-clock control. Replacement requires complete shared unsubscribe
success, and repeat never copies payload bytes.

## 4. Official sources

- Java 17 [`ThreadPoolExecutor`](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/concurrent/ThreadPoolExecutor.html),
  [`ArrayBlockingQueue`](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/concurrent/ArrayBlockingQueue.html),
  and [`RejectedExecutionHandler`](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/concurrent/RejectedExecutionHandler.html).
- Kotlin [`StateFlow`](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/-state-flow/),
  [`MutableSharedFlow`](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/-mutable-shared-flow/),
  and [`BufferOverflow`](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.channels/-buffer-overflow/).
- Kotlin [cancellation](https://kotlinlang.org/docs/cancellation-and-timeouts.html).

Official public behavior is the boundary. Implementation source or observed scheduler behavior cannot create a
progress, timing, callback, or collection guarantee absent from these contracts.
