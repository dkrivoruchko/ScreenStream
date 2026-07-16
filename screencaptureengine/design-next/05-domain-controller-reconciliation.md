# Screen Capture Engine — Controller and Reconciliation

This leaf owns the concrete `SessionController` and reconciliation bindings: command arbitration, lossless
draining, lifecycle transition application, desired/currentness decisions, topology selection, terminal
integration, and the immutable cross-domain command/fact boundary. Public results belong to
[`02-product-contract.md`](02-product-contract.md); generic occurrence, deadline, allocation, ownership,
cleanup, and quarantine rules belong to [`03-shared-runtime.md`](03-shared-runtime.md). This leaf
links those rules and adds only controller-specific mechanics.

## Navigation

- [CTRL-001](#ctrl-001--controller-confinement-and-gates)
- [CTRL-010](#ctrl-010--lossless-action-drainer)
- [CTRL-020](#ctrl-020--public-command-application)
- [CTRL-030](#ctrl-030--lifecycle-and-terminal-application)
- [CTRL-040](#ctrl-040--session-scheduler-resource)
- [Typed domain boundary](#2-typed-domain-boundary)
- [CTRL-100](#ctrl-100--currentness-identities)
- [CTRL-110](#ctrl-110--reconciliation-decision)
- [CTRL-120](#ctrl-120--destructive-transition-order)
- [CTRL-130](#ctrl-130--completion-and-fallback-arbitration)
- [CTRL-200](#ctrl-200--policy-attempt-counter-and-observation-authority)
- [CTRL-300](#ctrl-300--cross-domain-commit-rules)
- [CTRL-400](#ctrl-400--cleanuproot-handoff)
- [CTRL-900](#ctrl-900--forbidden-alternatives)
- [Exact verification rows](#4-exact-verification-rows)
- [Numeric and structural bindings](#5-controller-owned-numeric-and-structural-bindings)

## 1. Source boundary and authority

| Role | Repository-relative production path | Authority |
| --- | --- | --- |
| Session state and serial turns | `CTR:SessionController.kt` | sole lifecycle, desired/revision, current plan/topology, reconciliation/production/delivery occurrences, pacing/repeat policy and grant/phase state, counters/accumulators, public-observation values, diagnostic sequence, and public/terminal result authority |
| Pure transition calculation | `CTR:ReconciliationOwner.kt` | synchronous checked geometry, `Full`/`Downscaled`, compatibility, and smallest-transition resolver over one immutable snapshot; no retained cell, owner, transition, policy, or result authority |
| Public facade | `PUB:ScreenCaptureEngine.kt` | validation and command forwarding under the public contract; no resource or lifecycle authority outside `SessionController` |

`CTR:` and `PUB:` are defined by the [router path convention](01-authority-router.md#4-module-and-path-bindings). Physical
file boundaries do not create a second state machine. Domain owners retain and operate their resources; the
controller accepts only immutable facts and emits immutable commands after releasing all gates.
`DEL:PacingOwner.kt` and `DEL:ObservationOwner.kt` are physically routed to
[`12-domain-delivery-observation.md`](12-domain-delivery-observation.md); this leaf owns only the controller's
input snapshots, acceptance and application of their immutable calculations/builds, and every resulting
authoritative value or commit.

## CTRL-001 — Controller confinement and gates

The controller owns one nonfair `sessionGate` and never reenters itself. Public command critical sections and
controller turns inspect or write only bounded existing references, tags, identities, bits, and scalars.
Allocation, scheduling, Flow assignment/emission, platform work, GL/JPEG work, callbacks, release, diagnostics,
and cleanup execution occur after all applicable gates are released.

The only nested order is the shared `sessionGate -> one exact settlementGate` order from `CORE-SET-2`. A worker
never acquires `sessionGate`. Helper return does not commit authority: the controller must revalidate currentness
and commit the selected action under `sessionGate`.

The controller directly owns the one Session scheduler specified by `CTRL-040`. `Dispatchers.Default` remains a
non-owned drainer service; no other execution resource becomes controller-owned by composition.

## CTRL-010 — Lossless action drainer

The controller owns this exact wake protocol:

1. each producer completes its durable return/latest/command cell before signalling;
2. signalling performs exactly `IDLE -> RUNNING`, `RUNNING -> RUNNING_DIRTY`, or
   `RUNNING_DIRTY -> RUNNING_DIRTY`; only the `IDLE -> RUNNING` winner dispatches one controller drainer to
   `Dispatchers.Default`;
3. one bounded complete scan claims facts once into precreated fixed action slots;
4. a full action batch changes or retains the state as `RUNNING_DIRTY`, executes claimed actions unlocked, and
   requires a rescan without a producer signal;
5. before each required rescan, the drainer wins `RUNNING_DIRTY -> RUNNING` by CAS; after any nonempty batch it
   performs another complete scan, so a nonfull batch cannot hide a concurrently completed fact;
6. only a complete empty scan may attempt `RUNNING -> IDLE`. If a producer wins `RUNNING -> RUNNING_DIRTY`
   first, the idle CAS fails and the drainer changes `RUNNING_DIRTY -> RUNNING` and rescans. If the drainer wins
   `RUNNING -> IDLE` first, the producer then wins `IDLE -> RUNNING` and dispatches the successor drainer.

Claimed actions contain existing references/tags/scalars only. The drainer invokes neither a public entry point
nor an outward owner while gated. Active controller-dispatch throw/rejection publishes the precreated emergency
fact; one atomically exclusive observing thread performs the bounded fail-closed terminal turn. A rejected wake
does not erase a previously published fact. `Dispatchers.Default` is non-owned and is never closed, cancelled, or
placed in quarantine.

## CTRL-020 — Public-command application

Public validation, legal states, exceptions, waiter cancellation, and visible results are owned by
[`PROD-010`](02-product-contract.md#prod-010--public-session-api) and
[`PROD-011`](02-product-contract.md#prod-011--start-cancellation-and-wrong-state-outcomes). The concrete command
path is:

| Command | Controller commit | Unlocked continuation |
| --- | --- | --- |
| `create` | none; create all-zero observation cells, empty quarantine root, one-shot facade, normalized application Context, and configured metrics-provider value | no runtime owner starts before accepted `start` |
| winning `start` | validate parameters first; under `sessionGate`, commit `NotStarted -> Starting`, first nonzero desired revision, and transfer only that projection | signal startup owners; complete the Session-owned waiter only after direct `Running(Active)` assignment returns |
| losing `start` | observe non-`NotStarted`; commit and allocate nothing | throw without inspecting, retaining, registering, or stopping the losing projection |
| equal legal `updateParameters` | after common local validation, change nothing | return `Unit` |
| unequal legal `updateParameters` | use a precreated update/publication cell; atomically replace desired value, reserve the next nonreused revision, and complete one coherent snapshot containing the new requested value plus same-turn running/effective/visibility references | signal once; the drainer claims only the latest complete whole snapshot and publishes it without mixing fields |
| `onFrame` admission | require allowed nonterminal lifecycle and no unresolved predecessor; install one nonreused registration generation; if a current cache exists, reserve the ordinary handoff/lease atomically | send the registration or cached-first command to delivery; perform no encode or payload copy |
| `stop` | idempotently fix the owner-stop contender and close start, desire/reconciliation, production, repeat, and delivery admission before return | signal terminal convergence and cleanup |

Revision or identity exhaustion commits `InternalFailure` before wrap/reuse. No synchronous command waits for an
owner lane. A suspending waiter observes Session-owned completion; its cancellation cannot cancel or fabricate
the underlying operation.

## CTRL-030 — Lifecycle and terminal application

Public terminal values and caller mappings are
[`PROD-011`](02-product-contract.md#prod-011--start-cancellation-and-wrong-state-outcomes) and
[`PROD-060`](02-product-contract.md#prod-060--state-and-errors); unlocked construction/emission is
[`DEL-OBS-001`](12-domain-delivery-observation.md#del-obs-001--coherent-state-and-publication-order). The
controller owns the permanent winner, contender priority, cutoff, and this bounded application turn:

```text
close every admission
-> arbitrate CaptureEnded, then OwnerStop, then Failed
-> fold already-authoritative operation/accounting facts under the shared lock order
-> fix final counters, results, and owner/occurrence transfers
-> schedule independent cleanup roots
-> send the selected final Stats snapshot for direct unlocked assignment
-> reserve and synchronously attempt SessionTerminal through DEL-OBS-020
-> send terminal State for direct unlocked assignment
```

An accepted start succeeds only after `Running(Active)` assignment. If terminal wins first, no Running value is
invented. A result published only after intact transfer to cleanup is cleanup-only and cannot revise State,
Stats, cache, fallback, health, admission, or the selected cause.

## CTRL-040 — Session scheduler resource

After accepted start, `SessionController` constructs and immediately roots one owned
`ScheduledThreadPoolExecutor(1)` as an owned startup operation. Before publication it sets:

```text
setRemoveOnCancelPolicy(true)
setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
setContinueExistingPeriodicTasksAfterShutdownPolicy(false)
```

No periodic task is created. This scheduler serves finite-deadline, pacing/repeat, accepted-callback-entry, and
Stats wakes. [`CORE-WAKE-2`](03-shared-runtime.md#core-wake-2--deadline-wake-link-and-scheduling-rejection) owns
each generic deadline-wake submission/Future/rejection protocol; `DEL-PACE-*`, `DEL-HO-*`, and `DEL-OBS-*` own
their domain calculations and physical callback mechanics. `SessionController` alone accepts the resulting
facts and controls scheduler admission and lifetime.

Terminal first closes every successor authority and cancels each current Future outside gates. Scheduler
shutdown becomes eligible only after every caller-dispatch invocation still in call, every `Submitting` result,
and every retained or later-armable accepted-callback wake have settled. The controller then invokes exactly one
unlocked orderly `shutdown()` and invokes `shutdownNow()` zero times. Scheduler shutdown and termination have no
watchdog.

The private `terminated()` hook release-publishes its precreated scheduler-termination receipt, then signals the
controller. Nontermination roots only the scheduler/thread plus the exact queued/dequeued wake, `Submitting`
record, retained callback-deadline link, or deadline record that still depends on it. It cannot root a non-owned
dispatcher or an unrelated owner/root.

## 2. Typed domain boundary

The table names the complete controller-facing payload, not a permission to reach through an owner. Where the
physical declaration is domain-private, “fact” means its immutable typed record from that domain leaf.

| Direction | Typed value, owner, fact, or receipt | Controller use |
| --- | --- | --- |
| inbound | `ScreenCaptureParameters` plus accepted desired revision | resolve the latest requested plan; never redirect already-entered work |
| inbound | provider/projection geometry and availability facts, each limited to the fields owned by its API-band source | update the sole combined-geometry accumulator, assign `geometryGeneration`, and form the reconciliation key |
| inbound | exact installed `CurrentTarget` reference with generation/provenance/health facts | target compatibility and smallest replacement decision; copied handles/facts are insufficient |
| inbound | exact installed `GlRenderTargetOwner` reference plus immutable GL capability/shape/health facts | live output compatibility, checked per-axis capacity, and current render selection |
| inbound | exact Framework/native JPEG owner and carrier references with shape, lease, backend-health, and operation facts | encoder/carrier reuse, replacement, optional fallback, and production disposition |
| inbound | immutable `DEL-PACE-*` calculation/fact carrying exact candidate/wake/policy identity, sampled clock inputs, and storage occupancy/result | accept or reject calculation; commit grant/phase/wake, attempt/final disposition, cache-role command, counters, and cutoff |
| inbound | immutable `DEL-HO-*` registration/handoff/dispatch/entry/callback fact | commit admission, classification/counter, terminal cutoff, and shared unsubscribe result |
| inbound | identity-fenced scheduler submission/Future/rejection/Fired fact or scheduler-termination receipt | apply `CORE-WAKE-2`, current domain authority, and `CTRL-040` lifetime without inferring execution or cancellation |
| inbound | complete `OperationReturnCell`, deadline disposition, typed domain receipt, cleanup reduction, or terminal contender | apply `CORE-SET-2`/`CORE-SET-3`, `CORE-CLEAN-1`/`CORE-CLEAN-2`, and terminal arbitration without inferring adjacent success |
| outbound | immutable Android/Target/GL/JPEG/storage/delivery owner command carrying full key, occurrence identity, exact owner reference/loan, and fixed plan | execute only the named current operation; return a typed fact, never mutate controller authority |
| outbound | immutable `DEL-PACE-*` calculation input and one controller-accepted wake/admission/storage command | `PacingOwner` calculates and delivery/storage executes without retaining controller authority |
| outbound | one unlocked scheduler submit/cancel/orderly-shutdown action carrying its exact current identity | scheduler callback returns only its precreated identity-fenced fact or termination receipt |
| outbound | one immutable committed Running/terminal/Stats/diagnostic construction snapshot, including reserved diagnostic sequence | `ObservationOwner` constructs and assigns/tries emission unlocked; the returned/public value remains controller-authoritative |
| outbound | exact cleanup-root transfer containing occurrence gate, writable cell, owner bag, worker/wake link, and domain obligation | continue only the transferred cleanup dependency graph |

The Android, Target, GL, Framework JPEG, Native JPEG, storage, and delivery leaves own how these payloads are
created and physically settled. `CTRL-*` owns their controller state, arbitration, commit, and combination.

## CTRL-100 — Currentness identities

The exact reconciliation key is:

```text
(desiredRevision, geometryGeneration, lifecycleEpoch)
```

The controller owns one authoritative accumulator and one latest cell for the combined positive
`(widthPx, heightPx, densityDpi, geometryGeneration)` tuple. Provider and projection facts update only the fields
their API-band source owns under
[`AND-MET-010`](06-domain-android-capture-metrics.md#and-met-010--exact-api-band-reads-and-authority); no owner publishes
a second combined cell or generation. Before replacing the cell, the controller reserves the next strictly
increasing nonreused `geometryGeneration`. Exhaustion fails terminally before replacement or identity reuse.

Replacing an unclaimed revision settles that revision as `Superseded` and creates no rebuild. The controller
claims only the newest cell value. While one claimed revision has materialized reconciliation/rebuild work,
later revisions overwrite the same latest cell and are reconsidered only after that occurrence settles. Every
materialized revision ends exactly once as `Applied`, `Superseded`, `Invalid/Suspended`, or terminal failure.
There is no geometry-event queue, parallel geometry rebuild, or per-event storm growth.

An independent nonreused reconciliation-occurrence identity fences retries of the same key. The controller
advances:

- `lifecycleEpoch` for accepted-start authority, capture available/unavailable transitions, terminal, and each
  committed monotone target/backend-health fallback;
- the applicable target, plan, production, registration, handoff, wake, and domain occurrence identities only at
  their owning transitions.

Active/Suspended publication and steps already owned by the current reconciliation do not advance
`lifecycleEpoch`. A completion must match the complete key, its occurrence identity, applicable lifecycle/health
identity, and the actual live owner references. Equality with historical effective parameters never proves
current ownership.

## CTRL-110 — Reconciliation decision

There is at most one reconciliation occurrence plus the latest desired cell. `ReconciliationOwner` receives one
immutable snapshot and returns one checked transition calculation; the controller alone accepts it. Selection
inspects referential owner identity (`===`) and immutable generation, shape, capability, health, lease, and
lifetime facts.

For authoritative capture `(W,H,D)` and resolved positive output `(Ow,Oh)`, Target mode selection is closed:

- `Full` is the API 24–37 baseline: VirtualDisplay is `W x H` at density `D`, and the Surface target is
  `W x H`.
- `Downscaled` is eligible exactly on API 32–37 when `sourceRegion == Full`, crop is zero,
  `outputSize == ScaleFactor(f)` with `f < 1`, and the checked planner below returns `k < g`. Rotation, mirror,
  and color do not remove eligibility.
- `TargetSize`, either half-region, any crop, API outside 32–37, or `k == g` selects `Full`. A selected
  Downscaled plan that later fails a deterministic limit or actual allocation follows the ordinary denial/failure
  rule; it does not reselect `Full`.

No device, GPU, driver, benchmark, soak, or image-score allowlist participates in this selection.

```text
g = gcd(W, H)
baseW = W / g; baseH = H / g
rotation 0/180: requiredSourceW = Ow; requiredSourceH = Oh
rotation 90/270: requiredSourceW = Oh; requiredSourceH = Ow
ceilDiv(n,d) = n / d + (if n % d == 0 then 0 else 1)
k = min(g, max(1, max(ceilDiv(requiredSourceW, baseW),
                      ceilDiv(requiredSourceH, baseH))))
Tw = baseW * k; Th = baseH * k
```

All planner arithmetic is checked before narrowing. `Tw:Th == W:H`, and each Target axis supplies at least its
rotation-aware required source samples. The VirtualDisplay remains `W x H` at `D`; only the Surface target is
`Tw x Th`. API 32–33 plan from provider-authoritative dimensions. API 34–37 bootstrap with provisional `Full`
and closed frame admission; after the first valid projection resize, that Target is retained only when
authoritative `W,H` and the selected mode require the exact same Full Target. Otherwise the ordinary pre-Running
destructive transition installs the authoritative Full or Downscaled Target.

For unchanged authoritative `W,H`, a healthy Full Target is retained exactly when the new selection is Full; a
new Downscaled selection rebuilds it. A healthy Downscaled Target is retained while the plan remains eligible
and `Tw >= requiredSourceW` and `Th >= requiredSourceH`; it is never rebuilt only to shrink. Changed `W,H`, a
larger eligible demand that violates either inequality, or any Downscaled ineligibility rebuilds the Target,
with ineligibility selecting Full. The controller is the sole selector and cell authority; the Android leaf
supplies source-owned facts and the Target leaf executes only the accepted immutable plan.

| Difference or observed state | Exact controller decision |
| --- | --- |
| same resolved plan and complete healthy compatible live topology | retain or commit Active with zero resource work |
| historical plan matches but required live scope is missing, retired, unhealthy, or incompatible | rebuild the missing smallest scope |
| pacing and/or repeat only | replace policy/wake eligibility; retain target, pipeline, and cache |
| JPEG quality only | invalidate old cache/repeat bytes; retain every healthy exact-compatible owner and rebuild only an incompatible JPEG scope |
| density only | apply the Android-domain density path and its independent target-retention decision |
| region, crop, output size, rotation, mirror, or color changed | fence affected work, invalidate cache/repeat, retain every healthy exact-compatible owner, and replace only the smallest incompatible output scope |
| capture dimensions, target-health fault, larger downscaled demand, or downscale ineligibility | replace Target and its physical dependents; retain every healthy exact-compatible output texture/FBO, carrier, Bitmap/scratch, and JPEG owner |
| deterministic mandatory denial before retirement | allocate and retire nothing; during Running park old scope without output and select recoverable `Suspended(ResourceExhausted)` |

Preflight uses checked geometry/representation and deterministic API/backend capability limits only. It performs
no allocation and predicts no opaque memory result. Startup has no Suspended outcome: startup denial follows the public terminal startup rule,
while a required current creation/allocation failure after retirement is terminal `ResourceExhausted` with no
rollback.

## CTRL-120 — Destructive transition order

An image-affecting transition has this exact order:

```text
snapshot key and actual topology
-> resolve geometry/plan and run allocation-free deterministic preflight
-> while reversible, retain healthy old output Active
-> close only affected fresh/repeat/new-delivery admission
-> invalidate affected cache and old identities
-> drain entered affected work and fence the superseded occurrence
-> cross irreversible retirement and publish Suspended(Reconfiguring)
-> detach, retire, and release or cleanup-transfer the smallest incompatible scope
-> after real release authority, recheck nonterminal/current key and allocate one replacement
-> validate returned owners against key/occurrence/current topology
-> install current success and reopen admission, or safely retire stale success
```

Target replacement performs no speculative replacement and does not itself retire exact-compatible output
owners. Incompatible Bitmap/carrier replacement waits for all uses/leases and the applicable typed recycle/free
or managed-detach outcome, then performs a fresh gated current/nonterminal check before one replacement
allocation. An A-to-B-to-A sequence after A retirement rebuilds A; it cannot restore Active from historical
equality. Normal replacement never retains two healthy complete pipelines.

## CTRL-130 — Completion and fallback arbitration

| Completion | Controller disposition |
| --- | --- |
| timely current safe success with complete compatible topology | install/retain owners, commit effective parameters, reopen applicable admission, publish Active |
| safe stale success | publish nothing; safely retire only its returned owners and reconcile latest |
| safe stale failure | publish no stale lifecycle/output result; settle its owners and reconcile latest |
| unsafe or ownership-ambiguous result, including stale | terminal `InternalFailure`; transfer exact residue |
| current capture/metrics unavailable or geometry-invalid desire | retain desire in exact recoverable `Suspended(CaptureUnavailable)` or `Suspended(InvalidRequest)` |
| current mandatory build failure after retirement | terminal with the classified problem; no rollback |
| safely returned optional-axis failure | disable only that Session-monotone acceleration once, advance lifecycle epoch, and select mandatory fallback for later work |

Recoverable suspension is reconsidered only after a new desire or the applicable geometry, availability, or
health fact. Once relevant inputs quiesce, the latest key converges to Active, its exact recoverable suspension,
or terminal.

A timely safe Native JPEG failure that still owns the current health occurrence atomically records the switching
attempt as `byFailure`, sets Native `Disabled`, advances `lifecycleEpoch`, closes affected fresh/repeat/new-delivery
admission, fences obsolete backend/frame identities, invalidates cache/repeat, and commits
`Running(Suspended(Reconfiguring))`. It performs no Framework allocation, retry, or same-frame fallback. Entered
delivery is not revoked. Only later installation of a complete healthy current `FrameworkJpegOwner` reopens the
affected admission and permits Active; target and Direct-readback health remain unchanged.

A safely returned current Downscaled-target fault similarly commits the one monotone target-health disable,
advances `lifecycleEpoch`, fences affected work/cache, and lets a later reconciliation build Full. It performs no
same-frame retry. Ownership ambiguity remains terminal, and a safe stale result publishes no stale output.

For a poisoned private GL context, a previously Running Session similarly remains
`Running(Suspended(Reconfiguring))` while the GL domain resolves namespace retirement; startup remains Starting.
The controller may select the GL-provided `ResourceExhausted` candidate only after its exact successful context-
namespace retirement prerequisite. Ambiguous retirement selects `InternalFailure`; a prior higher-priority
terminal winner remains final. Independent later GL cleanup-suffix facts never rewrite it.

## CTRL-200 — Policy, attempt, counter, and observation authority

The controller owns every authoritative field in this boundary; Document 12 owns only the listed pure
calculation, physical handoff, and unlocked construction/emission mechanics.

| Controller-owned state | Accepted input/helper | Exact commit authority |
| --- | --- | --- |
| current pacing/repeat policy; fresh/output last-grant times, rational phases and required gaps; current wake/candidate identities | one immutable `DEL-PACE-010` calculation from `PacingOwner` | accept only after lifecycle, policy, candidate, clock, and identity recheck; commit an actual grant/phase advance, no-action successor, wake replacement, or active rejection terminal |
| current Target source identity; sole production-occurrence identity and final-disposition slot | `DEL-PACE-001` pending/materialization calculation and current storage capacity | authorize the one current bit exchange and atomically create the attempt/final slot, or retain the candidate without a drop |
| current output identity/sequence, cache eligibility decision, storage-role commands and production disposition | `DEL-PACE-020`, `STORE-*`, encoder/readback facts | commit current output/cache or one drop/terminal disposition; stale/unsafe precedence remains `CTRL-130` |
| registration generation, delivery admission, current handoff identity, shared unsubscribe result | `DEL-HO-*` mechanical facts | commit one registration/admission/classification/result; `DeliveryOwner` cannot select or revise it |
| all frame/delivery counters, ordered accumulators and current authoritative Stats fields | complete production/readback/encode/storage/delivery facts | apply each eligible fact once at its controller cutoff; prepare one immutable construction snapshot |
| authoritative lifecycle/Running/terminal fields and current public State/Stats values | controller commits plus `DEL-OBS-001`/`DEL-OBS-010` builders | select the value and publication order; unlocked helper construction/assignment retains no state or reinterpretation authority |
| diagnostic sequence and one typed diagnostic request | durable domain/controller fact plus `DEL-OBS-020` constructor | reserve the next nonreused sequence and exact payload before unlocked `tryEmit`; emission outcome has no control effect |

`PacingOwner` may calculate the exact cadence arithmetic, candidate choice, and next wake defined by
[`DEL-PACE-001` through `DEL-PACE-020`](12-domain-delivery-observation.md#del-pace-001--pending-source-and-materialization),
but it owns no policy, phase, candidate, attempt, wake, counter, cache decision, or commit. `ObservationOwner`
constructs/assigns/emits as defined by
[`DEL-OBS-001` through `DEL-OBS-020`](12-domain-delivery-observation.md#del-obs-001--coherent-state-and-publication-order),
but it owns no authoritative value, accumulator, sequence, classification, or publication decision.

For every pacing/production/delivery fact the controller rechecks lifecycle admission, full currentness,
topology, exact registration/occurrence identity, and prior disposition under the applicable gate. It commits one
accepted action or final disposition, then schedules/calls the helper or physical owner unlocked. Superseded
calculations have no effect and cannot clear a candidate or advance a phase.

At terminal cutoff the controller folds every already-complete production/callback fact and already-selected
disposition once before final Stats. Existing cache/output/drop commits remain; unclassified attempt retirement,
completed-unpublished retirement, whole-record transfer, and facts committed only after transfer add no counter.
The Native-disable cutoff in `CTRL-130` closes fresh, repeat, cached-first, encode, and new-delivery admission until
complete Framework installation without revoking entered delivery.

## CTRL-300 — Cross-domain commit rules

The controller integrates domain facts only through these rules:

1. a fact is mechanically complete before it is considered;
2. full key, occurrence identity, lifecycle/health identity, and exact installed-owner identity are rechecked;
3. fact classification precedes policy action; ambiguity cannot be downgraded by staleness or a returned code;
4. controller state and owner transfer are committed under the applicable gates;
5. immutable owner commands, publication snapshots, diagnostics, scheduling, and cleanup actions execute unlocked;
6. a late cleanup fact may reduce only its matching residue.

Domain diagnostic facts become requests only after their controlling mechanical fact is durable. After releasing
all gates, the serial controller reserves the next nonreused Session sequence (first is one), samples wall clock,
selects the typed category/source/cause payload, and passes the complete construction snapshot to
[`DEL-OBS-020`](12-domain-delivery-observation.md#del-obs-020--diagnostic-construction-and-emission). Sequence
exhaustion fails terminally before reuse. Construction and `tryEmit` occur only for the accepted request; wall
clock and emission outcome never select lifecycle, fallback, currentness, allocation, counters, or another attempt.

## CTRL-400 — Cleanup/root handoff

Generic cleanup record shape, dependency progression, quarantine, nonreturn, and late reduction are owned by
[`CORE-CLEAN-1`](03-shared-runtime.md#core-clean-1--cleanup-forest-and-dependency-graph) and
[`CORE-CLEAN-2`](03-shared-runtime.md#core-clean-2--quarantine-nonreturn-and-late-reduction). The controller owns
only the active-to-cleanup commit: under the normal gates it selects the exact disposition, attaches each present
owner/occurrence once to its named root or quarantine child, and records the terminal cutoff before publication.
It then issues cleanup actions unlocked. A later cleanup fact is accepted only for its matching transferred
identity and may update only the authoritative root/quarantine child; it cannot authorize active state or a
successor. The scheduler root follows `CTRL-040`: its termination receipt releases that root; nontermination
retains only the exact scheduler/thread and dependent wake/submission residue.

## CTRL-900 — Forbidden alternatives

This design permits no:

- second lifecycle/reconciliation/pacing/result writer or helper-owned authority cell;
- generic resource registry, alternate planner, rollback topology, replacement generation, or historical-plan
  ownership inference;
- speculative replacement before safe retirement or second healthy complete topology;
- redirect of entered work to a newer key, stale publication, or ambiguity-to-fallback conversion;
- unbounded event/timer/JPEG queue, per-purpose pacing timer set, catch-up state, or second production attempt;
- second Session scheduler, periodic scheduler task, `shutdownNow()`, repeated orderly shutdown, or scheduler
  shutdown watchdog;
- controller outward call, allocation, callback, Flow publication, diagnostic construction, scheduling, or
  cleanup while gated;
- synchronous public wait for an owner lane, waiter-owned operation cancellation, or fabricated receipt;
- predictive memory accounting, available-memory sampling, or cross-Session headroom policy;
- retry/same-frame Framework fallback after Native disable or output admission before complete Framework install.

## 4. Exact verification rows

No new tolerance or test-only numeric value is introduced here. Deterministic tests use barriers and fake clocks;
generic deadline boundary rows remain owned by
[`CORE-SET-1` through `CORE-WAKE-2`](03-shared-runtime.md#core-set-1--generic-operation-occurrence).

Closed packet membership is in [router §5](01-authority-router.md#5-closed-implementation-packets); shared
closure/routing is in [Document 04](04-verification.md), and exact test paths are in
[router §7](01-authority-router.md#7-test-manifest). The rows below retain only controller-specific evidence.

| Test ID | Required controller/reconciliation evidence |
| --- | --- |
| `H-LC` | Both concurrent-start gate orders and zero losing-projection access; accepted-cancellation orders; equal/unequal/concurrent update acceptance with coherent whole Running snapshots and no setter-return observation oracle; revision exhaustion; stop/admission cutoff; all terminal contenders and final Stats-before-State; every `CTRL-010` signal transition, drainer `RUNNING_DIRTY -> RUNNING` CAS, and both producer-versus-`RUNNING -> IDLE` orders; saturated fixed action batch self-dirties and consumes all complete cells once without a later producer signal; active drainer-dispatch failure uses one emergency fail-closed turn; scheduler construction is one owned startup operation with exact pre-publication configuration and no periodic task; cross-Session authority isolation. |
| `H-RC` | Complete-key and occurrence currentness; combined-geometry newest-only claim, unclaimed supersession, one running rebuild plus later latest revisions, exact materialized dispositions, exhaustion-before-reuse, and storm bounds; safe/unsafe stale returns; destructive A-to-B-to-A; exact Full/Downscaled retain, shrink-retain, larger-demand, ineligibility, and changed-dimensions decisions; actual owner-identity/shape/health reuse; deterministic pre-retirement denial with zero allocation/retirement; one smallest-scope post-retirement replacement; target replacement retaining compatible output-owner identities; typed recycle/free or managed-detach followed by fresh current/nonterminal recheck; PreparedTarget install-or-cleanup arbitration; Native-disable-to-complete-Framework-install sequence; quiescent convergence; no registry, rollback, predictive accounting, or second topology. |
| `H-PS` | Cross each `PacingOwner` calculation and production/storage fact with lifecycle, current policy/candidate/wake identity, topology, slot and terminal state. Assert that only `SessionController` commits source exchange/materialization, phase/grant/wake action, attempt disposition, cache-role command, every counter/accumulator update, and authoritative Stats snapshot; superseded calculations change none. Exact calculation and storage mechanics are exercised through `DEL-PACE-*` and `STORE-*`. |
| `H-DL` | Cross every scheduler/dispatch/entry/callback/convergence fact with controller admission, registration generation, prior disposition and terminal cutoff. Assert one controller classification/counter/shared-unsubscribe commit, no helper-owned result, and no late revision; shutdown waits for in-call dispatch and every retained/later-armable accepted-callback wake before the one orderly request; physical record/lease mechanics remain `DEL-HO-*`. |
| `H-OS` | Controller consumption uses the generic settlement lock/order and currentness rules; complete-before-signal, `Submitting`, Future publication/cancel, Fired/dequeue, rejection, terminal transfer, orderly shutdown, `terminated()` receipt, and late-return rows cannot duplicate controller authority or revise terminal/public results. Domain-specific operation mechanics stay in their owning leaves. |
| `H-GM` | Checked `gcd`/`ceilDiv` planner arithmetic, rotation-aware required axes, `Tw,Th`, every closed Full/Downscaled eligibility branch, and per-axis deterministic capability denial feed the controller decision before retirement; denial performs zero target/output allocation and no fallback. |
| `H-OB` | Controller-authoritative accumulators and State/Stats values, coherent construction snapshots, Native-disable/GL-poison Reconfiguring commits, terminal order, exact diagnostic sequence reservation/exhaustion, and emission noncontrol. `ObservationOwner` construction/assignment/`tryEmit` is `DEL-OBS-*` and cannot reinterpret the snapshot or retain authority. |
| `A-SES` | Installed facade observes main-safe start, wrong-state commands, update/stop linearization, public startup/terminal mappings, cached-first admission, and controller-committed shared unsubscribe result without exposing internal authority. |
| `A-CAP` | API-band source facts update only their owned combined-geometry fields; API 32–33 planning uses provider dimensions; API 34–37 provisional Full admits no frame and first valid resize drives exact retain-or-rebuild selection without a second geometry authority. |
| `A-CL` plus owning suites | Terminal cutoff transfers exact intact occurrences; independent roots and another Session progress; scheduler termination releases its root, while nontermination retains only the exact scheduler/thread and dependent wake/`Submitting`/callback-deadline/deadline residue; late receipts reduce only matching cleanup/quarantine residue and cannot admit replacement or alter the winner. |

### Required interleavings

The test rows reproduce both sides of these controller decision points:

| Race | Order A | Order B |
| --- | --- | --- |
| concurrent start | A commits Starting; B touches nothing | B commits Starting; A touches nothing |
| desire versus rebuild | old safe completion loses currentness and cleans | unsafe old completion remains terminal despite staleness |
| geometry storm versus rebuild | unclaimed revisions settle Superseded without rebuild | revisions arriving during one rebuild overwrite the latest cell and only the newest is reconsidered afterward |
| A-to-B-to-A after retirement | absent A scope is rebuilt | no historical equality path restores Active |
| source signal versus controller consume | pre-consume signal may enter the one authorized attempt | post-consume signal remains a distinct pending fact; it cannot be lost or folded into the prior attempt |
| pacing calculation versus newer policy/candidate | still-current calculation may commit one grant/wake action | superseded calculation changes no phase, candidate, wake, attempt, or counter |
| terminal versus production return | complete fact/disposition is folded once | whole unresolved occurrence transfers and later return is cleanup-only |
| terminal versus delivery fact | complete eligible callback fact is folded once | post-transfer fact reduces only delivery cleanup residue |
| stop versus callback entry | entered callback may finish | stop-first fences user-code entry |
| optional-health failure versus terminal | current safe failure disables one axis and starts reconciliation | terminal winner prevents health/fallback/publication change |
| allocation around retirement | deterministic denial allocates/retires nothing | required current post-retirement allocation failure is terminal, with no rollback |

## 5. Controller-owned numeric and structural bindings

This leaf owns no numeric policy constant or test tolerance. Its material structural bound is one latest desired
cell, one latest combined-geometry cell, and at most one reconciliation occurrence. Delivery/observation
physical cardinalities and cadence values live with `DEL-*`; generic deadline-wake mechanics live with
`CORE-WAKE-2`. `CTRL-040` additionally fixes one Session scheduler worker, zero periodic tasks, one orderly
shutdown, zero `shutdownNow()`, and no shutdown watchdog. The five private safety intervals live with their
boundary domains.

## 6. Official sources

- [SystemClock elapsed realtime](https://developer.android.com/reference/android/os/SystemClock) — the engine's
  monotonic interval source.
- [CoroutineDispatcher](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/-coroutine-dispatcher/)
  and [coroutine dispatchers](https://kotlinlang.org/docs/coroutine-context-and-dispatchers.html) — non-owned
  controller dispatch service and dispatch semantics.
- [StateFlow](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/-state-flow/)
  — whole-value observation and permitted equality conflation.
- [cancellation](https://kotlinlang.org/docs/cancellation-and-timeouts.html) — waiter cancellation semantics.

Official public API behavior is the implementation boundary. Platform/runtime implementation details may inform
diagnostics but create no guarantee absent from the accepted authority.
