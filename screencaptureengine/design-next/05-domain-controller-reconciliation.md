# Screen Capture Engine — Controller and Reconciliation

This leaf owns the Session lifecycle and reconciliation authority: command arbitration,
lossless draining, lifecycle transition application, desired/currentness decisions, topology selection,
terminal integration, and the immutable cross-domain command/fact boundary. Public results belong to
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
- [CTRL-900](#ctrl-900--safety-boundaries)
- [Exact verification rows](#4-exact-verification-rows)
- [Numeric and structural bindings](#5-controller-owned-numeric-and-structural-bindings)

## 1. Source boundary and authority

The controller domain has one Session commit authority. It alone owns public-command linearization,
lifecycle/admission, currentness and cross-domain decisions, terminal winner/cutoff, exact owner transfer, and
commits spanning topology, production, delivery, observations, or cleanup. Domain resources and operation cells
retain their own mechanical owners and gates; the aggregate consumes immutable facts and issues immutable
effects only after releasing gates.

This semantic authority does not prescribe one class, file, occurrence type, or private state layout. Cohesive
calculation, action, scheduler, settlement, cleanup, and publication collaborators hold no independent
lifecycle/currentness/terminal authority or alternate commit path. One Session admits at most one serialized
reconfiguration cycle; physical reuse, resize, detach, attach, and replacement remain leaf-owned mechanics.

## CTRL-001 — Controller confinement and gates

The controller owns one nonfair `sessionGate` and never reenters itself. Public command critical sections and
controller turns inspect or write only bounded existing references, tags, identities, bits, and scalars through
the one Session commit authority.
Allocation, scheduling, Flow assignment/emission, platform work, GL/JPEG work, callbacks, release, diagnostics,
and cleanup execution occur after all applicable gates are released.

The only nested order is the shared `sessionGate -> one exact settlementGate` order from `CORE-SET-2`. The only
non-controller paths that acquire `sessionGate` are the Metrics observer ingress in `AND-MET-020` and Delivery
Runnable admission in `DEL-HO-010`; each uses its explicitly defined arbitration and lock order. Every other owner
worker publishes a complete fact through its settlement authority, never acquires `sessionGate`, and cannot
mutate controller state. Helper return does not commit authority: the controller must revalidate currentness and
commit the selected action under `sessionGate`.

The controller directly owns the one Session Control scheduler/thread specified by `CTRL-040`. Both controller
drains and all one-shot Control wakes execute there; no shared coroutine dispatcher or caller execution service participates in
controller progress.

`sessionGate` alone commits the Production/frame-Delivery pause, resume, and terminal cutoffs. `CTRL-120` owns the
serialized ordering and grandfathered-handoff rule. Resume requires exact current-owner/key revalidation and one
Session commit; terminal may win once from any phase and never reopens admission.

## CTRL-010 — Lossless action drainer

Each producer makes its durable fact complete before signalling. The signal protocol permits at most one live
drainer, records work arriving during a drain, and loses no fact across the drain-to-idle race: only a complete
empty scan may become idle, while any claimed work or concurrent signal guarantees another bounded complete
scan without relying on a later producer.

Claimed actions contain existing references/tags/scalars only. The drainer invokes neither a public entry point
nor an outward owner while gated. At most one drainer identity is logically live, but the Control scheduler may
also contain the finite set of currently owned deadline, pacing, repeat, and Stats wakes. The private-executor
one-ticket rule in `CORE-EXEC-1` does not apply to Control; its bound is the protocol cardinality of those named
wakes plus the one drainer.

Drainer submission owns a typed submission/entry record. Normal `execute` return proves acceptance, not entry;
entry may commit before that return or a throw. Entry wins the drainer occurrence, while every outward submission
throw is still recorded and monotonically poisons Control. An active pre-entry `Exception` or any Control poison
publishes the precreated emergency fact; one atomically exclusive producer performs the bounded allocation-free
fail-closed terminal turn. A direct fatal throwable additionally follows `CORE-FATAL-1`. A rejected or poisoned
wake cannot erase a durable fact, create a second drainer, or authorize scheduler reuse.

## CTRL-020 — Public-command application

Public validation, legal states, exceptions, waiter cancellation, and visible results are owned by
[`PROD-010`](02-product-contract.md#prod-010--public-session-api) and
[`PROD-011`](02-product-contract.md#prod-011--start-cancellation-and-wrong-state-outcomes). The concrete command
path is:

| Command | Controller commit | Unlocked continuation |
| --- | --- | --- |
| `create` | none; create all-zero observation cells, empty quarantine root, one-shot facade, normalized application Context, and configured metrics-source value | no runtime owner starts before accepted `start` |
| winning `start` | validate parameters first; under `sessionGate`, commit `NotStarted -> Starting`, first nonzero desired revision, and transfer only that projection | signal startup owners; complete the Session-owned waiter only after direct `Active` assignment returns |
| losing `start` | observe non-`NotStarted`; commit and allocate nothing | throw without inspecting, retaining, registering, or stopping the losing projection |
| equal legal `updateParameters` | after common local validation, change nothing | return `Unit` |
| unequal legal `updateParameters` | atomically replace desired value, reserve the next nonreused revision, close new Production and frame Delivery, and commit one coherent `Reconfiguring(requested, lastEffective, lastKnownGeometry, visibility)` snapshot | assign State before the first outward reconfiguration effect, then signal once; return confirms desire, not publication delivery or convergence |
| `onFrame` admission | require allowed nonterminal lifecycle and no unresolved predecessor; install one nonreused registration generation; reserve a cached handoff only when Active and its cache is current | send only an Active registration/cached-first command to delivery; a registration created in Reconfiguring or Suspended persists without creating callback work |
| `stop` | apply `PROD-011` under `sessionGate`: without an accepted start/runtime owner, fix `OwnerStop` and close start without creating desire or runtime ownership; otherwise idempotently fix the contender and close desire/reconciliation, production, repeat, and delivery admission | without a runtime owner, directly execute the already-selected final zero-Stats, terminal-diagnostic, and terminal-State publication actions without Control; otherwise signal normal terminal convergence and cleanup |

Revision or identity exhaustion commits `InternalFailure` before wrap/reuse. Equality compares the current
requested parameters and is an absolute no-op in Reconfiguring or Suspended. No synchronous command waits for an owner lane,
and no setter-return or synchronous collector-delivery oracle exists. A suspending waiter observes Session-owned
completion; its cancellation cannot cancel or fabricate the underlying operation.

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

An accepted start succeeds only after `Active` assignment. If terminal wins first, no Running value is
invented. A result published only after intact transfer to cleanup is cleanup-only and cannot revise State,
Stats, cache, fallback, health, admission, or the selected cause.

Metrics readiness consumes only the bounded `AND-MET-020` summary under `sessionGate`: earliest validated
positive `(sequence,T,value)`, sticky first post-valid pre-Active loss, latest nullable `(sequence,value)`, first
terminal `(sequence,kind,cause,BeforeJointReadiness|AfterJointReadiness)`, and the distinct handle
outcome/adoption. It folds semantic sequence without an event queue. One-shot joint readiness requires `T < D`
plus normal nonnull exact-handle adoption before prior loss/terminal/expiry. Normal completion before that commit
is startup `CaptureUnavailable`; after it, including before first Active, completion retains the latest tuple and
does not prevent startup. Null handle or outward attachment `Exception` while unresolved is authoritative
`InternalFailure` over staged callbacks; committed expiry or Session terminal remains fixed. A pre-Active loss
remains startup `CaptureUnavailable` despite later valid latest data. Ingress-sequence exhaustion applies the
precreated `InternalFailure` before reuse.

## CTRL-040 — Session scheduler resource

After accepted start, the Session constructs and roots one single-worker `ScheduledThreadPoolExecutor` as an
owned startup operation. It is unpublished until its worker is prestarted, removes cancelled work, runs no
existing delayed or periodic task after shutdown, and creates no periodic task.

No periodic task is created. This scheduler serves the one controller drainer and one-shot deadline, pacing,
repeat, and Stats wakes. [`CORE-WAKE-2`](03-shared-runtime.md#core-wake-2--one-shot-control-wake-link-and-scheduling-rejection) owns
every wake's central submission/Future/callback/cancellation/termination/successor protocol; `DEL-PACE-*`,
`DEL-HO-*`, and `DEL-OBS-*` own
their domain calculations and physical callback mechanics. The controller alone accepts the resulting
facts and controls scheduler admission and lifetime.

Each Control task is typed and preserves the exact scheduled delegate's Future, cancellation, delay, periodicity,
and ordering semantics without a second result. Direct fatal settlement is one-shot, poisons Control, closes
admission, and rethrows the identical object at the worker boundary; hooks neither call `get`, unwrap/classify
causes, inspect untyped tasks, nor create duplicate facts. A replacement worker can only settle poisoned work
inert. Wake cancellation/removal and successor rules remain solely `CORE-WAKE-2`.

After terminal, healthy Control is cleanup-only. It remains the last Session lane until every other owned
lane/thread termination receipt, every external late fact, every controller-consumed cleanup dependency, every
drainer submission in call, every `Submitting` result, every created Control wake link, and every other
independent physical cleanup root fully settles. Its sole physical domain action is the opaque Android-owned one-shot final
projection stop admitted by `AND-CAP-030`; Control receives no projection authority. The final Control turn
closes all remaining successor authority, cancels current Futures outside gates, invokes that action when
eligible, and requests exactly one unlocked orderly `shutdown()` only after its actual normal-return closure
receipt or when it is structurally inapplicable. External nonreturn or final-action throw/nonreturn retains the
exact residue and leaves Control unshut. `shutdownNow()` is never invoked and termination has no watchdog.

The private `terminated()` hook must not signal, resubmit, or execute another controller turn. It
release-publishes its precreated scheduler-termination receipt and one-shot inline releases only its own
scheduler/thread root, plus the strictly required already-defined best-effort cleanup diagnostic if applicable.
It cannot revise active/public state or create a successor. Poison or nontermination retains the scheduler/thread
and exact dependent wake, `Submitting`, drainer, external-fact, or cleanup residue; it cannot root an unrelated
owner.

## 2. Typed domain boundary

The table names the complete controller-facing payload, not a permission to reach through an owner. Where the
physical declaration is domain-private, “fact” means its immutable typed record from that domain leaf.

| Direction | Typed value, owner, fact, or receipt | Controller use |
| --- | --- | --- |
| inbound | `ScreenCaptureParameters` plus accepted desired revision | resolve the latest requested plan; never redirect already-entered work |
| inbound | selected-source/projection geometry and availability facts, each limited to the fields owned by its API-band source | update the sole combined-geometry accumulator, assign `geometryGeneration`, and form the reconciliation key |
| inbound | exact installed `CurrentTarget` reference with generation/provenance/health facts | target compatibility and smallest replacement decision; copied handles/facts are insufficient |
| inbound | exact installed `GlRenderTargetOwner` reference plus immutable GL capability/shape/health facts | live output compatibility, checked per-axis capacity, and current render selection |
| inbound | exact Framework/native JPEG owner and carrier references with shape, lease, backend-health, and operation facts | encoder/carrier reuse, replacement, optional fallback, and production disposition |
| inbound | immutable `DEL-PACE-*` calculation/fact carrying exact candidate/wake/policy identity, sampled clock inputs, and storage occupancy/result | accept or reject calculation; commit grant/phase/wake, attempt/final disposition, cache-role command, counters, and cutoff |
| inbound | immutable `DEL-HO-*` registration/handoff/submission/entry/callback fact | commit admission, classification/counter, terminal cutoff, and shared unsubscribe result |
| inbound | identity-fenced scheduler submission/Future/rejection/Fired fact or scheduler-termination receipt | apply `CORE-WAKE-2`, current domain authority, and `CTRL-040` lifetime without inferring execution or cancellation |
| inbound | complete `OperationReturnCell`, deadline disposition, typed domain receipt, cleanup reduction, or terminal contender | apply `CORE-SET-2`/`CORE-SET-3`, `CORE-CLEAN-1`/`CORE-CLEAN-2`, and terminal arbitration without inferring adjacent success |
| outbound | immutable Android/Target/GL/JPEG/storage/delivery owner command carrying full key, occurrence identity, exact owner reference/loan, and fixed plan | execute only the named current operation; return a typed fact, never mutate controller authority |
| outbound | immutable `DEL-PACE-*` calculation input and one controller-accepted wake/admission/storage command | `PacingOwner` calculates and delivery/storage executes without retaining controller authority |
| outbound | one unlocked scheduler submit/cancel/orderly-shutdown action carrying its exact current identity | scheduler callback returns only its precreated identity-fenced fact or termination receipt |
| outbound | opaque Android-owned one-shot final projection-stop action, bound outcome, and exact eligibility evidence | execute only as the final physical cleanup action; the controller receives neither raw projection nor a second authority |
| outbound | one immutable committed Running/terminal/Stats/diagnostic construction snapshot, including reserved diagnostic sequence | `ObservationOwner` constructs and assigns/tries emission unlocked; the returned/public value remains controller-authoritative |
| outbound | exact cleanup-root transfer containing occurrence gate, writable cell, owner bag, worker/wake link, and domain obligation | continue only the transferred cleanup dependency graph |

The Android, Target, GL, Framework JPEG, Native JPEG, storage, and delivery leaves own how these payloads are
created and physically settled. `CTRL-*` owns their controller state, arbitration, commit, and combination.

## CTRL-100 — Currentness identities

The controller is the only source of Session currentness identity. Each immutable identity snapshot combines
the desired revision, geometry generation, and lifecycle epoch. It is not a resource lease, lifetime token, or permission to use an installed
owner. Operation acceptance additionally rechecks its exact occurrence and live owner references as required
below; no domain owner creates a competing Session currentness identity.

The controller holds one authoritative combined positive
`(widthPx, heightPx, densityDpi, geometryGeneration)` tuple. The full duplicate/no-op authority key includes the
observation/source identity, selected Display identity plus continuous-validity epoch or projection-owner epoch,
availability phase, and every authoritative field. Selected-source and projection facts update only the fields
their API-band source owns under
[`AND-MET-010`](06-domain-android-capture-metrics.md#and-met-010--exact-api-band-reads-and-authority); no owner publishes
a second combined value or generation. Before replacing it, the controller reserves the next strictly
increasing nonreused `geometryGeneration`. Exhaustion fails terminally before replacement or identity reuse.

After ingress consumption, equality of that full key is a complete no-op: it creates no geometry/lifecycle
generation, reconciliation, rebuild, State/Stats publication, or diagnostic. Repeated unavailable in the same
phase is likewise a no-op. First valid input, an availability transition, a new Display epoch/projection owner,
or any authoritative-field change is real. Unavailable followed by a value equal to historical geometry is a
recovery transition, never a duplicate.

Replacing an unclaimed revision settles that revision as `Superseded` and creates no mechanics. The controller
claims only the newest pending value. While one cycle has materialized work, later revisions replace that latest
pending value. They may replace its plan only while every outward mechanic is proven definitely unentered and
unexposed; after submission ambiguity, port/lease exposure, or entry, the exact work settles before the latest
cell is reconsidered from actual state. Every claimed revision ends exactly once as `Applied`, `Superseded`,
`Suspended`, or terminal failure. There is no geometry-event queue, parallel rebuild, or per-event storm
growth.

An independent nonreused reconciliation-occurrence identity fences retries of the same key. The controller
advances:

- `lifecycleEpoch` for accepted-start authority, capture available/unavailable transitions, terminal, and each
  committed monotone target/backend-health fallback;
- the applicable target, plan, production, registration, handoff, wake, and domain occurrence identities only at
  their owning transitions.

Active/Reconfiguring/Suspended publication and steps already owned by the current reconciliation do not advance
`lifecycleEpoch`; an ordinary serialized reconfiguration does not advance it. A
completion must match the complete key, its occurrence identity, applicable lifecycle/health identity, and the
actual live owner references. Equality with historical effective parameters never proves current ownership.

## CTRL-110 — Reconciliation decision

There is at most one reconciliation occurrence plus the latest desired value. One checked transition decision
uses an immutable snapshot; the controller alone accepts it. Selection
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
`Tw x Th`. API 32–33 plan from source-authoritative dimensions. API 34–37 bootstrap with provisional `Full`
and closed frame admission; after the first valid projection resize, that Target is retained only when
authoritative `W,H` and the selected mode require the exact same Full Target. Otherwise the ordinary pre-Running
destructive transition installs the authoritative Full or Downscaled Target.

For unchanged authoritative `W,H`, a healthy Full Target is retained exactly when the new selection is Full; a
new Downscaled selection rebuilds it. A healthy Downscaled Target is retained while the plan remains eligible
and `Tw >= requiredSourceW` and `Th >= requiredSourceH`; it is never rebuilt only to shrink. Changed `W,H`, a
larger eligible demand that violates either inequality, or any Downscaled ineligibility rebuilds the Target,
with ineligibility selecting Full. The controller is the sole selector and commits the result; the Android leaf
supplies source-owned facts and the Target leaf executes only the
accepted immutable plan.

| Difference or observed state | Exact controller decision |
| --- | --- |
| same resolved plan and complete healthy compatible live topology | retain or commit Active with zero resource work; an accepted unequal user update still uses the common pause/publication cycle before this commit |
| historical plan matches but required live scope is missing, retired, unhealthy, or incompatible | rebuild the missing smallest scope |
| pacing and/or repeat only | replace policy/wake eligibility; retain target, pipeline, and exact-compatible cache through the pause subject to currentness at resume |
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

One protocol applies to every accepted unequal Running update and to Running geometry, metrics, backend, or
health reconciliation that requires mechanics. Availability loss may publish its more precise Suspended problem,
but it uses the same admission close and relevant-predecessor drain. There is no public pause/resume operation and
no Session-level reconfiguration variant family.

The serialized protocol is:

```text
commit latest requested value and a truthful Reconfiguring or recoverable Suspended state; close new Production and frame Delivery
-> assign State before the first outward reconfiguration effect
-> drain only predecessors that may touch changing capture, production, or Target resources
-> obtain exact Target quiescence
-> execute at most one leaf mechanic at a time from the current actual state
-> settle its exact result, ownership, and physical evidence
-> either commit the latest compatible topology and resume, or continue with one next serial cycle
```

No outward call runs under `sessionGate` or a settlement gate. New materialization, repeat, cached-first offer,
and frame-handoff creation/admission stay closed throughout the pause. An `onFrame` registration may be created
or remain installed. A handoff whose admission commit won before the pause is grandfathered: its Runnable may
enter during `Reconfiguring`, and its callback completes under the immutable old output, effective descriptor,
and registration generation. Reconfiguration neither cancels nor waits for that app callback, so it does not
block capture/resource drain. Terminal and unsubscribe retain their independent cutoffs.

Drain scope is dependency-based rather than whole-Session: it waits only for admitted predecessors that can
touch the resources being changed, then obtains the exact Target quiescence required by `TGT-030`. Leaf owners may
reuse, resize, detach, attach, recycle, free, or physically replace their resources only under their own typed
ports, leases, receipts, and safety orders. Reconfiguration preserves the same MediaProjection authority, sole
VirtualDisplay, sole Session JPEG runtime, and one live topology. Independent normal `setSurface(null)` detach
and `setSurface(newSurface)` producer evidence remain mandatory when Target replacement needs them; neither
proves the other, and new-surface attachment never proves old detach.

A newer desire replaces the latest pending plan immediately. It may redirect the current cycle only while every
mechanic is proven definitely unentered and unexposed. Once submission is ambiguous, a port or lease escaped, or
entry won, that exact work settles and its real result updates only actual mechanical state. The controller then
resumes if actual state matches the latest desired key, otherwise begins one next serial cycle from actual state
without an intervening Active or admission-open gap. There are no concurrent or chained Session transactions,
historical-equality restoration, rollback topology, or Session-level compensation protocol.

Cache is unavailable while paused. Image-, backend-, or topology-affecting change invalidates it; an
exact-compatible policy-only change may preserve its bytes, but cached-first/repeat/handoff eligibility is
rechecked only at resume. Current rejection, throw, expiry, nonreturn, mutation ambiguity, or unsafe ownership
remains terminal under its owning partition even if the desire changed. Terminal wins once, transfers unresolved
graphs intact, permanently closes admission, and leaves late results cleanup-only.

## CTRL-130 — Completion and fallback arbitration

| Completion | Controller disposition |
| --- | --- |
| timely current safe success with complete compatible topology | install/retain owners, commit effective parameters, reopen Production/frame Delivery, publish Active |
| safe stale success | publish nothing; record exact actual state or safely retire returned owners, then continue the latest serial cycle |
| safe stale failure | publish no stale lifecycle/output result; settle its owners and reconcile latest |
| unsafe or ownership-ambiguous result, including stale | terminal `InternalFailure`; transfer exact residue |
| current capture/metrics unavailable or geometry-invalid desire | retain desire in exact recoverable `Suspended(CaptureUnavailable)` or `Suspended(InvalidRequest)` |
| current mandatory build failure after retirement | terminal with the classified problem; no rollback |
| safely returned optional-axis failure | disable only that Session-monotone acceleration once, advance lifecycle epoch, and select mandatory fallback for later work |

`CTRL-120` owns the common pause/drain/serial/resume protocol. Final success requires exact current-owner/key
revalidation, all admitted mechanics that can touch the changing capture/production/Target resources settled or
definitely unentered/unexposed, required Target quiescence and
reopen facts, and one `sessionGate` commit. A physical Target replacement additionally requires exact old detach
and retirement plus one installed producer-attached replacement Target on the same VirtualDisplay. Transfer is
terminal-only. Leaf facts alone cannot reopen admission or publish Active; safe stale facts may update actual
mechanical state only.

Recoverable suspension is reconsidered only after a new desire or the applicable geometry, availability, or
health fact. Once relevant inputs quiesce, the latest key converges to Active, its exact recoverable suspension,
or terminal.

A timely safe Native JPEG failure that still owns the current health occurrence atomically records the switching
attempt as `byFailure`, sets Native `Disabled`, advances `lifecycleEpoch`, enters the common pause lifecycle,
fences obsolete backend/frame identities, invalidates cache/repeat, and commits
`Reconfiguring`. It performs no Framework allocation, retry, or same-frame fallback. A
previously admitted delivery handoff is not revoked. One later serialized cycle installs a complete healthy current `FrameworkJpegOwner`
before resume; target and Direct-readback health remain unchanged.

A safely returned current Downscaled-target fault similarly commits the one monotone target-health disable,
advances `lifecycleEpoch`, enters the common pause lifecycle, fences affected work/cache, and lets the serialized reconciliation build Full. It performs no
same-frame retry. Ownership ambiguity remains terminal, and a safe stale result publishes no stale output.

For a poisoned private GL context, a previously Running Session similarly remains
`Reconfiguring` while the GL domain resolves namespace retirement; startup remains Starting.
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

For every pacing/production/delivery fact the controller rechecks the applicable committed admission, full
currentness, topology, exact registration/occurrence identity, and prior disposition under the applicable gate.
Grandfathered handoff eligibility and independent terminal/unsubscribe cutoffs follow `CTRL-120`. The controller commits one
accepted action or final disposition, then schedules/calls the helper or physical owner unlocked. Superseded
calculations have no effect and cannot clear a candidate or advance a phase.

At terminal cutoff the controller folds every already-complete production/callback fact and already-selected
disposition once before final Stats. Existing cache/output/drop commits remain; unclassified attempt retirement,
completed-unpublished retirement, whole-record transfer, and facts committed only after transfer add no counter.
Reconfiguration admission and registration behavior follows `CTRL-120`; Native disable additionally requires
complete Framework installation before resume.

## CTRL-300 — Cross-domain commit rules

Every cross-domain turn is a closed typed fact, gated aggregate-root commit, bounded precreated immutable action
batch, unlocked effect, and closed typed returned fact. There is no generic bus, mutable context, or alternate
commit path. The aggregate root integrates domain facts only through these rules:

1. a fact is mechanically complete before it is considered;
2. full key, occurrence identity, lifecycle/health identity, and exact installed-owner identity are rechecked;
3. fact classification precedes policy action; ambiguity cannot be downgraded by staleness or a returned code;
4. the controller commits state and owner transfer under `sessionGate` and seals bounded immutable effects;
5. immutable owner commands, publication snapshots, diagnostics, scheduling, and cleanup actions execute unlocked;
6. effects return closed typed facts; a late cleanup fact may reduce only its matching residue.

For an accepted unequal update, the requested/Reconfiguring logical commit occurs in the public call. Its
unlocked State assignment is ordered before the first outward reconfiguration effect. Stats is assigned only
when a public Stats field changed under `PROD-070`. Neither Flow
collector resumption nor observation before setter return is required.

Domain diagnostic facts become requests only after their controlling mechanical fact is durable. After releasing
all gates, the serial controller reserves the next nonreused Session sequence (first is one), samples wall clock,
selects the vocabulary label, source, site, cause, and semantic message inputs, and passes the complete
construction snapshot to
[`DEL-OBS-020`](12-domain-delivery-observation.md#del-obs-020--diagnostic-construction-and-emission). Sequence
exhaustion fails terminally before reuse. Construction and `tryEmit` occur only for the accepted request; wall
clock and emission outcome never select lifecycle, fallback, currentness, allocation, counters, or another attempt.

`PROD-080` owns the public fields, labels, source strings, and category meanings. Controller selection is exact:

| Label | Selected source/site/cause rule |
| --- | --- |
| `CapabilityCheck` | Select the boundary source and site for each top-level capability decision/failure, with its exact cause when present. Select one null-cause `MetricsSource` completion after joint readiness per observation lifetime; pre-readiness completion, source failure, and duplicates select none. |
| `RuntimeProfile` | `Session`, once at initial Running, with null cause and the committed runtime modes. |
| `RuntimeModeChanged` | `SurfaceTarget`, `FrameworkJpeg`, or `NativeJpeg` only for the specified actual change; retain a safe returned cause when defined. Timeout, stale cleanup, and considered-only change select none. |
| `DeliveryProblem` | `FrameConsumer` only for a callback `Exception` committed before terminal transfer, with that exact throwable. |
| `StatsProblem` | `Controller` for finite retention/clamp/freeze, with null cause. |
| `ColorAction` | `ColorPipeline` once per Target and on actual classification/action change, with null cause. |
| `QuarantineChanged` | `Cleanup` only after an exact root attach/reduction/removal, with its raw cleanup cause when present. |
| `SessionTerminal` | `Session`, once after final Stats and before terminal State. Owner/capture stop and pre-Active valid-then-invalid metrics use null cause; another failure uses its selected cause; a winning timeout reuses its `CapabilityCheck` cause identity. |

Timeout selection is likewise controller-owned:

| Occurrence family | `CapabilityCheck` source | Winning active classification |
| --- | --- | --- |
| first-positive metrics readiness | `MetricsSource` | `CaptureUnavailable`; terminal diagnostic reuses the same cause |
| API-34+ initial-resize readiness | `MediaProjection` | `CaptureUnavailable`; terminal diagnostic reuses the same cause |
| Android entered operation | `MediaProjection` for registration/create, `VirtualDisplay` for resize/setSurface, `SurfaceTarget` for listener/Surface | terminal `InternalFailure`; terminal diagnostic reuses the same cause |
| GL entered operation | `GlPipeline` | terminal `InternalFailure`; terminal diagnostic reuses the same cause |
| JPEG entered operation | `FrameworkJpeg` or `NativeJpeg` according to the selected boundary | terminal `InternalFailure`; terminal diagnostic reuses the same cause |

A prior higher-priority terminal leaves timeout cleanup-only and selects no timeout event. Timeout selects no
fallback, retry, runtime-mode change, duration telemetry, aggregate, or additional public field.

## CTRL-400 — Cleanup/root handoff

Generic cleanup record shape, dependency progression, quarantine, nonreturn, and late reduction are owned by
[`CORE-CLEAN-1`](03-shared-runtime.md#core-clean-1--cleanup-forest-and-dependency-graph) and
[`CORE-CLEAN-2`](03-shared-runtime.md#core-clean-2--quarantine-nonreturn-and-late-reduction). The controller owns
only the active-to-cleanup commit: under the normal gates it selects the exact disposition, attaches each present
owner/occurrence once to its named root or quarantine child, and records the terminal cutoff before publication.
It then issues cleanup actions unlocked. A later cleanup fact is accepted only for its matching transferred
identity and may update only the authoritative root/quarantine child; it cannot authorize active state or a
successor. The scheduler root follows `CTRL-040`: healthy Control is the cleanup-only last lane, with only the
named `AND-CAP-030` final-action specialization. Its inline termination hook may release its root only after that
action is inapplicable or has its actual normal-return closure receipt and every other inbound dependency has
settled. Final-action throw/nonreturn or Control poison/nontermination retains only the exact transferred residue
and its scheduler/thread dependencies.

## CTRL-900 — Safety boundaries

One Session has one lifecycle/currentness/terminal commit authority, one gate, one scheduler, one bounded latest
desire, and one serialized reconfiguration cycle. Entered work settles against its original identity; replacement
starts only after mechanical retirement and every required receipt, preserving one healthy topology. Terminal
cleanup adoption preserves exact residue and authorizes neither active replacement nor lifecycle resume. Bounded production,
wake, and event cardinalities are those owned by `CORE-*` and `DEL-*`.

The safety oracle retains these critical absences: no outward work while gated, no fabricated receipt, no stale
publication or ambiguity-to-fallback conversion, no predictive memory admission, no callback interruption or
scheduler shutdown watchdog, and no same-frame Framework retry after Native disable. Framework output resumes
only after its complete owner is installed.

## 4. Exact verification rows

No new tolerance or test-only numeric value is introduced here. Deterministic tests use barriers and fake clocks;
generic deadline boundary rows remain owned by
[`CORE-SET-1` through `CORE-WAKE-2`](03-shared-runtime.md#core-set-1--generic-operation-occurrence).

Closed packet membership is in [router §5](01-authority-router.md#5-closed-implementation-packets); shared
closure/routing is in [Document 04](04-verification.md), and canonical test source sets are in
[router §7](01-authority-router.md#7-test-manifest). The rows below retain only controller-specific evidence.

| Test ID | Required controller/reconciliation evidence |
| --- | --- |
| `H-LC` | Both concurrent-start gate orders and zero losing-projection access; accepted-cancellation orders; equal/unequal/concurrent update acceptance; unequal update logically commits requested/Reconfiguring and assigns State before first outward effect without a setter-return collector oracle; creation publishes the zero Stats snapshot, accepted start does not reassign/arm it, and later Stats assignment occurs only on public-field change; equality is an absolute no-op in Reconfiguring or Suspended; revision exhaustion; stop/admission cutoff; all terminal contenders and final Stats-before-State; lossless bounded Control draining under producer/drainer races and dispatch failure; scheduler configuration before publication with no periodic task; post-terminal Control remains cleanup-only and last until all inbound dependencies settle, permits only the prequalified final Android action, then uses its actual closure receipt for one final shutdown and a nonsignalling inline termination release; cross-Session authority isolation. |
| `H-RC` | Complete-key and occurrence currentness; full metrics authority-key duplicate and repeated-unavailable no-op with zero generation/reconciliation/publication, contrasted with first-valid, availability transition, new epoch/owner, field change, and unavailable-to-historically-equal recovery; newest-only desire claim, unclaimed supersession, one serialized cycle plus later latest revisions, exact dispositions, exhaustion-before-reuse, and storm bounds; common pause for unequal updates and Running geometry/metrics/backend/health mechanics; Production/frame-Delivery closure, relevant-predecessor drain, exact Target quiescence, no old-Active promise, one leaf mechanic at a time, final current-owner/key commit and resume; plan replacement only with definite unentered/unexposed proof, otherwise exact settlement and one next cycle from actual state without an Active gap; safe/unsafe stale returns; exact null-surface detach/retirement and producer-only new-surface attach on the same sole VirtualDisplay when replacement is needed; terminal at every phase with no reopen; exact Full/Downscaled reuse and rebuild decisions; actual owner identity/shape/health reuse; deterministic pre-retirement denial with zero allocation/retirement; typed recycle/free or managed-detach followed by fresh current/nonterminal recheck; PreparedTarget install-or-cleanup arbitration; Native-disable-to-complete-Framework-install sequence; quiescent convergence; no Session variant family, compensation protocol, rollback, predictive accounting, second display, or second topology. |
| `H-PS` | Cross each `PacingOwner` calculation and production/storage fact with lifecycle, pause, current policy/candidate/wake identity, topology, slot and terminal state. Assert zero paused materialization/repeat/cached-first/new-handoff admission, exact-compatible policy-only cache preservation versus image/backend/topology invalidation and resume currentness, and that only the controller commits source exchange/materialization, phase/grant/wake action, attempt disposition, cache-role command, every counter/accumulator update, and authoritative Stats snapshot; superseded calculations change none. Exact calculation and storage mechanics are exercised through `DEL-PACE-*` and `STORE-*`. |
| `H-DL` | Cross every scheduler/Delivery-submission/entry/callback/convergence fact with controller admission, registration generation, pause and terminal cutoff. Assert registration persistence while paused and zero new cached-first/handoff admission until Active. Fix both pause races: admission-first grandfathers the immutable old-generation handoff and its effective descriptor; pause-first creates no handoff. Reconfiguration neither cancels nor waits for the app callback. Assert serial callback execution across resume, one controller classification/counter/shared-unsubscribe commit, ordinary busy/drop accounting, no helper-owned result, and no late revision; Delivery shutdown waits for each exact ticket side and its `terminated()` receipt. |
| `H-OS` | Controller consumption uses generic settlement, wake, lock-order, currentness, and terminal-transfer rules. Serialized reconfiguration accepts only matching quiescence, sealed Android-owned `AndroidTargetPostOutcome`, settled mechanical and Target-owned application facts; definitely unentered/unexposed work may be superseded, while exposed/ambiguous/entered work settles exactly before one next cycle. Independently cross old-detach and new-producer acceptance, rejection/no-entry, entry/return/throw, fatal/ambiguity, inert-after-poison, cancellation-before-platform-entry, terminal, and nonreturn; neither graph proves the other. Operational wake succession may precede stale wrapper return only after all required publications, and stale work has zero engine access. Typed Control fatal paths preserve one fact/poison, identical rethrow identity, and no fabricated receipt. Late rows cannot duplicate controller authority or revise terminal/public results. |
| `H-GM` | Checked `gcd`/`ceilDiv` planner arithmetic, rotation-aware required axes, `Tw,Th`, every closed Full/Downscaled eligibility branch, and per-axis deterministic capability denial feed the controller decision before retirement; denial performs zero target/output allocation and no fallback. |
| `H-OB` | Controller-authoritative accumulators and State/Stats values, coherent requested/Reconfiguring snapshots, State assignment before first outward reconfiguration effect without synchronous collector delivery, creation-time zero Stats with no accepted-start assignment/wake and later publication only for real field change, Native-disable/GL-poison commits, truthful historical last-effective values, terminal order, exact diagnostic vocabulary/source/site/cause/sequence selection, and emission noncontrol. `ObservationOwner` construction/assignment/`tryEmit` is `DEL-OBS-*` and cannot reinterpret the snapshot or retain authority. |
| `A-SES` | Installed facade observes main-safe start, wrong-state commands, update/stop linearization, public startup/terminal mappings, cached-first admission, and controller-committed shared unsubscribe result without exposing internal authority. |
| `A-CAP` | API-band source facts update only their owned combined-geometry fields; API 32–33 planning uses source dimensions; API 34–37 provisional Full admits no frame and first valid resize drives exact retain-or-rebuild selection without a second geometry authority. |
| `A-CL` plus owning suites | Terminal cutoff transfers exact intact occurrences; independent roots and another Session progress; every non-Control lane receipt, external late dependency, and physical cleanup root reaches healthy cleanup-only Control before its final turn. Cross normal Android stop with typed lane-never-started or final-lane/no-entry eligibility, one shared arbitration, entered/ambiguous exclusion, actual normal-return closure, and final-action throw/nonreturn retention. External nonreturn retains the Control dependency; the Control hook cannot signal/resubmit/revise and releases only its own root inline. Poison/nontermination retains the exact thread/endpoint and dependent wake/ticket/`Submitting` residue; late receipts cannot admit replacement or alter the winner. |

### Required interleavings

The test rows reproduce both sides of these controller decision points:

| Race | Order A | Order B |
| --- | --- | --- |
| concurrent start | A commits Starting; B touches nothing | B commits Starting; A touches nothing |
| desire versus rebuild | old safe completion loses currentness and cleans | unsafe old completion remains terminal despite staleness |
| geometry storm versus rebuild | unclaimed revisions settle Superseded without rebuild | revisions arriving during one rebuild overwrite the latest cell and only the newest is reconsidered afterward |
| newer desire during mechanics | definitely unentered/unexposed work may be replaced | ambiguous/exposed/entered work settles, then one next cycle starts from actual state without Active gap |
| source signal versus controller consume | pre-consume signal may enter the one authorized attempt | post-consume signal remains a distinct pending fact; it cannot be lost or folded into the prior attempt |
| pacing calculation versus newer policy/candidate | still-current calculation may commit one grant/wake action | superseded calculation changes no phase, candidate, wake, attempt, or counter |
| terminal versus production return | complete fact/disposition is folded once | whole unresolved occurrence transfers and later return is cleanup-only |
| terminal versus delivery fact | complete eligible callback fact is folded once | post-transfer fact reduces only delivery cleanup residue |
| handoff admission versus reconfiguration pause | admission-first grandfathers the old-generation handoff and later callback entry remains eligible | pause-first creates no handoff; reconfiguration does not wait for the grandfathered callback |
| stop versus callback entry | entered callback may finish | stop-first fences user-code entry; reconfiguration pause alone does not |
| optional-health failure versus terminal | current safe failure disables one axis and starts reconciliation | terminal winner prevents health/fallback/publication change |
| allocation around retirement | deterministic denial allocates/retires nothing | required current post-retirement allocation failure is terminal, with no rollback |
| Target seal versus frame port entry | exact inert/sealed fact routes from `TGT-100` | exact quiesced/reopened or terminal fact routes from `TGT-100`; controller never infers Target mechanics |
| leaf mechanic versus newer desire/terminal | definitely unentered/unexposed work may be superseded; otherwise it settles and reconciliation continues without an open interval | terminal absorbs the graph and no late fact reopens or publishes |

## 5. Controller-owned numeric and structural bindings

This leaf owns no numeric policy constant or test tolerance. Its material structural bound is one latest desired
cell, one latest combined-geometry cell, and at most one serialized Session reconfiguration cycle. Delivery/observation
physical cardinalities and cadence values live with `DEL-*`; generic one-shot Control-wake mechanics live with
`CORE-WAKE-2`. `CTRL-040` additionally fixes one Session scheduler worker, zero periodic tasks, one orderly
shutdown, zero `shutdownNow()`, and no shutdown watchdog. The five private safety intervals live with their
boundary domains.

## 6. Official sources

- [SystemClock elapsed realtime](https://developer.android.com/reference/android/os/SystemClock) — the engine's
  monotonic interval source.
- Java 17 [`ScheduledThreadPoolExecutor`](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/concurrent/ScheduledThreadPoolExecutor.html)
  and [`ThreadPoolExecutor`](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/concurrent/ThreadPoolExecutor.html)
  — Control task wrapping, orderly shutdown, and termination hooks.
- [StateFlow](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/-state-flow/)
  — whole-value observation and permitted equality conflation.
- [cancellation](https://kotlinlang.org/docs/cancellation-and-timeouts.html) — waiter cancellation semantics.

Official public API behavior is the implementation boundary. Platform/runtime implementation details may inform
diagnostics but create no guarantee absent from the accepted authority.
