# Screen Capture Engine — Current Work Tracker

## Authority and current gate

`design-next/01-authority-router.md` through `design-next/12-domain-delivery-observation.md` are the normative authority in
router order. Doc13 is nonnormative; the old `design/01`–`09` package is not authority. Kotlin is implementation evidence.

Docs01–12 are user-approved/staged and closure-PASS. Docs03/04/05/06 include the user-approved projection-stop terminal delta:
normal stop uses Android Handler; one exact Android-owned final cleanup action may run on Control after the Android lane is
proved unavailable/unentered; only normal return closes projection; throw/nonreturn/ambiguity retains quarantine and Control.
Documentation is frozen.

The causal startup-ownership/cleanup-foundation refactor produced reusable focused-PASS pieces, but the Metrics M1 patch/recheck
route exposed unresolved component-boundary defects and is abandoned as an implementation strategy. The user explicitly approved
moving the full-product internal component-contract specification ahead of all further production work. Existing Kotlin is
diagnostic evidence only; its current file/class/state shape is not an architecture constraint. Packet A and the B1 Target + C GL
+ D JPEG/Delivery tranche remain frozen/review-PASS as behavior/ownership evidence, not mandatory implementation shape. M1 is not
frozen; M2+ and all production integration remain closed. Critical Stage 4 is not complete.

## Permanent working rules

- Root is exclusively the orchestrator and sole tracker/status/gate owner. Root does not inspect or implement production code,
  perform technical audits/research or run builds unless the user explicitly asks for a bounded root-only check. Subagents do
  not read or edit this tracker, delegate work or coordinate other agents.
- At most three direct subagents run at once. Root supplies bounded product scope, authority route, invariants, negative manifest
  and required evidence. One writer owns every affected file.
- Reuse an agent only for a fix or one closely adjacent packet where its exact context is valuable. Rotate writers before long
  multi-domain chains; material independent reviews use fresh agents that did not write the reviewed change.
- Packets are flexible coordination boundaries, not rigid phases. Combine, reorder or transfer them when real coupling demands
  it, but do not spend cycles on artificial packet closure, intermediate compilation or local perfection that does not reduce
  whole-product risk.
- Speed comes from removing coordination waste and using safe parallelism, never from weakening architecture. Refactor, rename,
  move, split, merge or replace components as deeply as needed; preserve useful code without preservation bias or patchwork.
- **Simplicity is a mandatory acceptance criterion, not a preference after correctness.** Every solution must be simultaneously
  functionally correct, architecture-correct, reliable, efficient, easy to understand and maintain, and the simplest natural
  design that satisfies frozen product/platform/cleanup authority. A technically correct but unnecessarily complicated solution
  is a review failure. When several correct designs exist, choose the one with fewer authorities, mutable states, transitions,
  flags, objects, classes, copies, synchronization boundaries and indirection layers. Every retained nontrivial mechanism needs
  a concrete causal or platform reason. Reject speculative extensibility, defensive framework-building, Boolean soup, generic
  infrastructure for one use, compatibility layers and complexity added merely “for safety”; simplicity never permits weaker
  evidence, fabricated receipts, collapsed ownership, lower reliability or unjustified CPU/GPU/memory cost.
- Root must include this simplicity/quality mandate explicitly in **every** subagent assignment—writer, reviewer, researcher and
  adjudicator. Every subagent must actively search for a smaller natural solution, challenge unnecessary structure, prefer
  deletion and closed typed ownership over added checks, and explain why its proposed states and mechanisms are the minimum needed.
  Reviewers must reject over-engineering even when behavior is technically correct. An assignment or result that omits this proof
  of simplicity cannot authorize production work and must be corrected first.
- Product, architecture, concurrency, graphics, cleanup, JNI and implementation-level CPU, GPU and memory efficiency are the
  critical areas; every material component, boundary or decision in any of them requires three independent review axes. Root
  consolidates. If their verdicts differ, the same reviewers first cross-check one another's concrete evidence and attempt one
  agreed result; use a fresh adjudicator only when a material disagreement remains after that cross-check. Efficiency review
  seeks the strongest reasonable implementation within the approved architecture, technical decisions and frozen authority; it
  does not authorize exhaustive optimization, product change or architectural redesign. Focused reviews may guide repairs but
  do not count as final closure; run the three fresh final axes only after the reviewed packet/boundary is coherent and complete.
- A fresh guardrail review is required before accepting a material course change, scope expansion or new authority exception.
  If it needs new product/document authority, stop at the exact blocker and obtain user agreement. Bounded repairs that only
  restore approved invariants use the normal writer/recheck loop.
- Root never interrupts, cancels, replaces or duplicates an active agent without direct user permission.
- Product behavior is the stable approval boundary. Docs specify product behavior, semantic authority, ownership, critical
  partial orders, platform/binary contracts and verification obligations; they do not prescribe private algorithms, class/file
  shapes or exhaustive call sequences.
- Documentation records current decisions, not history. Prefer deletion of obsolete constraints to appended exceptions; keep it
  compact and architectural. Every documentation change requires user agreement and independent closure review.
- Every accepted implementation stage must be truthful and production-reachable. Mutable/executable state needs an authoritative
  root, real producer/consumer, entry/return arbitration, terminal transfer and reduction in the same accepted stage.
- Semantic authority need not be physically co-located. Cohesive private collaborators are allowed, but no second gate, lane,
  lifecycle/currentness/terminal authority, ownership root, state machine or commit path.
- File size is a qualitative signal, not a line target. Prefer cohesive units around a few hundred lines; unusually large files
  require architectural justification. Reject mega-owners, generic manager/registry/bus layers, forwarding shells,
  file-per-method fragmentation and `utils/common` dumps.
- Root continuously tracks course health through subagent evidence, not only packet completion. At every writer handoff record
  whether authority/state histories decreased, obsolete claims/callers were deleted, every new fact has a real producer/consumer,
  terminal cleanup remains reachable, file responsibility became more cohesive and hot-path allocations/wakes/state copies did
  not regress without cause. A packet that only moves code or adds another proof layer is not progress.
- Stop the writer chain and run a fresh broad course review when the same causal defect class survives a bounded repair/recheck,
  old and new authority must coexist to proceed, causal closure repeatedly requires unplanned cross-domain expansion, large files
  do not materially lose mixed responsibility, or independent reviewers repeatedly expose contradictions in the chosen model.
  Ordinary isolated implementation bugs use the normal writer/recheck loop; recurrence or structural drift reopens the path.
- Agents perform two self-review passes. Verification is proportional to risk. Android CLI is first when it provides stronger
  Android-specific coverage; official Android/AOSP, Kotlin/JetBrains, Oracle/OpenJDK/JNI and Khronos sources are preferred.
- **Current official guidance is part of implementation correctness.** Frozen project documents remain product/authority truth;
  within that boundary, every technical design, implementation and review must follow the current official platform, language,
  runtime, concurrency, graphics, JNI and API guides and established best practices relevant to the exact files and domains being
  touched. Root must name this obligation in every subagent assignment. Agents must verify material claims against primary official
  sources rather than memory, cite the sources they actually used, and explicitly justify any necessary departure. Community
  articles, convention or remembered behavior cannot override frozen authority or replace Android/AOSP, Kotlin/JetBrains,
  Oracle/OpenJDK/JMM/JNI, Khronos or other owning-platform documentation.
- Kotlin caller support is normative; Java is best effort. Ordinary access is reviewed at source level. Bytecode is optional only
  for a concrete critical JMM/JNI/ABI/platform question.
- Preserve unrelated work. Do not stage, unstage, commit, reset, checkout, revert, inspect hashes or mutate Git history/index.
  Tests, builds, runtime/device/emulator and performance work remain out of scope.
- Keep this tracker restart-safe: retain rules, current authority, accepted implementation boundary, open gate and exact next
  procedure; remove agent chronology, superseded plans, line pins, hashes and repeated review narration.

## User-approved product decisions

- Runtime updates use **pause → relevant drain → serialized reconfiguration → resume**. At most one reconfiguration is active;
  new desired values coalesce to the latest pending value.
- A handoff accepted before pause is grandfathered and may enter/finish its callback during `Reconfiguring`; pause blocks only new
  handoff creation. Reconfiguration neither cancels nor waits for that callback.
- Stop/fatal may win at every point. Late results reduce cleanup only and never resume, publish or replace the terminal winner.
- Healthy Control is cleanup-only and terminates last. Its sole physical specialization is the approved opaque Android-owned
  final projection-stop action; no raw projection or Android authority transfers to Session/Control.
- One MediaProjection, one VirtualDisplay, one Session JPEG runtime, exact Android entry/return evidence, platform currentness,
  typed ownership and cleanup are mandatory. No receipt may be fabricated.
- No final product capability is removed: complete public API; one active callback subscriber; Framework and Native/Auto JPEG;
  repeat/cache/cached-first; pacing; State/Stats/diagnostics/observations; Metrics/Android/Target/GL/JPEG/Storage/Delivery;
  fallback, security, JNI/ABI/wire/build requirements.
- Public contracts remain direct `Active | Reconfiguring | Suspended : Running`, compositional effective parameters, exact
  per-frame descriptor without separate `imageSize`, one reusable `CaptureMetricsSource`, no `byRateLimit`, and
  `updateParameters(): Unit`.
- The first externally reachable library stage must deliver the complete approved facade/lifecycle. The public factory remains
  closed until the whole standalone library contract is reachable.

## Frozen architecture and accepted implementation boundary

- Selective reconstruction remains approved: one compact `SessionAuthority` gate with stateless transitions, policy-free
  runtime/control mechanics and physical Android/Target/GL/JPEG/Storage/Delivery owners. No actor/event bus, compatibility
  patchwork, whole-engine rewrite or later thinning pass.
- Exactly six Session lanes exist after accepted start: Control, Metrics, Android, GL, JPEG and Delivery. Target, Storage,
  Observation and cleanup have no lane.
- The old `SessionController`, its facets/helpers and legacy `CleanupOwner` are deleted. `SessionAuthority` is the sole Session
  commit/terminal authority. Authority/runtime/control correction passes are focused-PASS.
- Public Output/Observation values and Kotlin construction bridges match authority. Metrics, Target pending-source, GL integrity,
  JPEG/Storage descriptor and role settlement, Delivery pacing/admission/fact claims/borrowed-frame/Observation leaf seams have
  focused PASS. JNI/native ABI is unchanged.
- Real startup truthfully reaches Metrics readiness, projection callback registration, GL Session/capability, reconciled Target,
  listener installation and sole VirtualDisplay creation/application. API34–37 stops at `AwaitingInitialResize`; older APIs stop
  at `AwaitingRenderTarget`. `Ready`, topology-ready and `Active` remain unreachable.
- Reusable exact cleanup work already implemented: Target `Prepared | StructurallyAbsent`; listener
  `NeverRequested | RetiredBeforeBinding | NoPlatformEntry | ListenerNeverInstalled | Installed`; fatal-closed Android listener
  Bound root; Android-owned callback cleanup outcome; producer absence; normal/final-Control projection-stop one-shot arbiter;
  exact Android lane receipts; Control wake-residue aggregate, sole final carrier, final-turn seal and Control-last action.
- Focused reviews pass those individual seams, the 13 currently constructible Control wake links, final carrier/seal and core
  projection entry/return arbitration. They are reusable, but the whole startup-cleanup foundation is not closure-PASS.
- Packet A cleanup assembly is frozen: exactly eight fixed typed domain slots live in one whole-state CAS authority; exact claims
  and roots are assembly-bound; only untouched slots become typed absent-at-cutoff; admission cutoff is distinct from absence
  evidence; the canonical transfer owns one non-null sealed aggregate. Three final review axes PASS linearization/provenance,
  architecture/scope and the full contract including bounded CPU/memory behavior.

## Current gate — component architecture before code

The frozen product authority in Docs01–12 remains valid. No production edit, M1 repair, M2 work or code-shape preservation is
authorized. The active deliverable is one separate full-product internal component architecture and inter-component contract
specification. It must define the smallest correct component boundaries, responsibilities, typed ports, ownership, execution and
terminal protocols across the entire Screen Capture Engine before any implementation plan is accepted.

Current code and focused-PASS packets may supply facts, failure traces and reusable ideas, but the specification must independently
derive the target structure from frozen behavior, platform contracts and end-to-end causal requirements. It may retain, replace,
merge, split or delete existing components. After specification closure, plan one coherent broad refactor to conform to those
boundaries and delete superseded code; do not resume incremental patching or build compatibility shells.

## Validated refactor plan and exact next procedure

The causal refactor direction remains independently validated, needs no product/documentation change and is explicitly
user-approved. Packet A and the B1/C/D-JPEG/Delivery tranche are frozen/PASS; Metrics A-prime is implemented/focused-PASS. The
expanded recommended B2 structural refactor is now explicitly user-approved. It may create, move, split, merge or replace private
files and may leave intermediate source noncompiling. Its acceptance criterion is less duplicated authority and a smaller causal
model, not preservation of the current class shape or cosmetic line movement.

### A — cleanup assembly contract — frozen/PASS

- Add only `internal/session/cleanup/SessionCleanupAssembly.kt` and revise
  `internal/session/cleanup/SessionCleanupTransfer.kt`. Do not touch Session runtime/authority, transitions, leaves or Control.
- One concrete `AtomicReference<AssemblyState>` owns exactly eight fixed typed top-level slots: Control, Metrics, Android, Target,
  GL, JPEG, Storage and Delivery. Slot state is closed and typed: `Unclaimed | Pending(exactClaim) |
  Owned(exactClaim, typedRoot) | StructurallyAbsent(exact evidence)`.
- Claim, exact resolution and terminal seal use whole-state unlocked CAS. Claim-before-seal remains in the sealed manifest until
  it resolves; seal-before-claim returns typed cutoff and forbids outward work. Untouched slots become typed absent-at-cutoff.
- `SessionCleanupTransfer` is an identity-bearing handle to the non-null sealed aggregate, never a copied nullable snapshot.
  Target generations, Android operations, GL resources and quarantine children remain inside their one domain root.
- No compatibility shell over the old residue path. Intermediate noncompilation is allowed. Three independent final axes PASS
  after the repair/cross-check/adjudication loop; packet A is frozen.

### User-approved B2 structural foundation refactor — current

The refactor applies one ownership rule throughout: one physical entity/process has one state owner, one closed aggregate and one
linearization history; other domains receive immutable typed facts. Pure reducers calculate Session effects but never own Session
state. Facades assemble and route cohesive collaborators but do not mirror their states. Delete superseded claims/fields/call
paths in the packet that replaces them; no old/new compatibility pair or later thinning pass is accepted.

File size is reduced by ownership decomposition, not file-per-method slicing. The intended component shape is:

- Metrics: small `CaptureMetricsOwner` facade; one cumulative observation aggregate; cohesive attachment/refresh owner; built-in
  source adapter; pure `SessionMetricsReducer`.
- Android: small `AndroidCaptureOwner` facade; physical `AndroidLane`; one `AndroidLaneLifecycle`; callback owner; VirtualDisplay
  owner; one `ProjectionStopAggregate`; policy-free Android cleanup mechanics.
- Session: compact sole `SessionAuthority`; runtime assembly; startup/mechanical/cleanup coordinators; immutable root/fact types.
  Collaborators do not become gates or second lifecycle/currentness/terminal authorities.

Prefer components around a few hundred lines. As nonbinding decomposition checks, the large facades/runtime files should normally
settle near 350–750 lines, pure reducers near 200–350 and `SessionAuthority` near 600–900. Exceeding those ranges is allowed only
when cohesion and authority are clearer than another split. The refactor must materially shrink current mega-owners and duplicate
state/proof code; merely redistributing the same model across files is a review failure.

#### M1 — Metrics authority spine — current design gate

M1 is **implemented but not closure-PASS**; M2 remains closed. Targeted structural R1/R2/R3 removed the copied mutable Metrics
ingress, callback-side Session materialization, the physical Boolean/nullable progression and endpoint-only whole-root inference.
The first fresh whole-M1 closure still found four implementation blockers. No product/documentation authority is open.

**Course-health stop:** repeated standalone fatal-retention defects and the unresolved pre-RuntimeStarted attachment-cutoff
boundary have triggered the permanent stop rule. The user has stopped the local fix/recheck chain. No further production writer or
patch is authorized in this cycle. Let the already-running broad standalone review and the same-reviewer Session/Runtime cross-check
finish as diagnosis only, consolidate their evidence here, and stop. Those diagnostics are now complete. M1 is not frozen. Any move to the component-contract
specification before M1 closure is a material sequencing change and must be explicitly agreed after the diagnostic summary; M2
remains closed regardless.

Current diagnosis, not an authorized patch plan:

- **Standalone source boundary:** broad forward/reverse review FAIL. Display selection, epoch validation, registration-attempt
  ownership, callback sealing and bounded coalescing are sound. The terminal architecture is not: worker-thread reentrant
  observer `close()` can run terminal cleanup inside the observer stack, allowing the observer to catch the retained authoritative
  fatal; a later sealed pass then has no path that must rethrow it. `ThreadPoolExecutor.terminated()` runs in `TIDYING` before the
  executor reaches `TERMINATED`, so the current latch is not exact final-termination evidence. Unexpected rejection performs
  worker-confined terminal mutation on the submitting caller and is a second terminal path. `OPEN` versus `CLOSE`, listener
  `Closed` and atomic fatal ownership may be redundant consequences of that split authority. Do not apply another local fatal
  patch. Re-specify one confined terminal site after callback unwind, exact external termination observation and caller-side
  rejection behavior before any further writer.
- **Session/Runtime startup cutoff:** both cross-check reviewers agree identity lookup alone loses the `absent -> later publish`
  window and attachment acquisition must use `mandatoryCleanup=false` at terminal cutoff. Both require one precreated fixed typed
  Session-owned cutoff, sole-sealed by Session and mechanically claimed by Runtime before attachment submission. They did not
  converge on its correct breadth: one proposes a six-state Metrics publish/claim cell retaining exact access; the other a
  four-state startup-admission cell that stores only identities/tags and replaces the runtime-private lock/Boolean admission for
  later startup work. Do not adjudicate or implement this shape inside the stopped patch cycle; settle it against all startup
  components and seams in the component-contract specification.

Current M1 authority and boundary:

- Internal success is exactly
  `Starting.AwaitingMetrics → MetricsReady → Starting.AwaitingProjectionCallbackRegistration`; projection registration belongs
  to M2 and Target/topology/first `Active` belongs to M3.
- `SessionAuthority` owns one precreated bounded raw Metrics stage/version under `sessionGate`. Callback/poll ingress may fold
  raw evidence under the legal `sessionGate -> exact attachment settlementGate` order and signal Control, but cannot materialize,
  publish or commit Session state. Serial Control alone captures exact refs/scalars, constructs effects unlocked, revalidates and
  commits. The reducer is pure/authority-less.
- The stage retains earliest timely positive, sticky first pre-Active loss, latest sample, first terminal, joint readiness,
  completion and cutoff without timestamp reconstruction. Exact `T < D`; D expires. Completion diagnostic precedes any later
  terminal diagnostic. Terminal cutoff closes ingress; late results are cleanup-only and exact handle/residue remains rooted.
- `CaptureMetricsOwner` owns physical attachment/refresh/close/observation settlement. Attachment is the first Metrics ticket;
  refresh opens only after its complete settlement/registration/initial read and endpoint-operation release. Atomic callback
  admission closes before physical close. Observation settlement and endpoint termination are separate typed results; whole
  Metrics termination requires both.
- A fixed built-in policy retains and reads only the caller-supplied `Display`; before/after each read it validates frozen ID,
  supplied validity, current valid same-ID logical association and the sticky remove/add epoch fence. Manager wrappers are never
  substituted or compared referentially.
- Public direct/repeated built-in subscriptions are source-owned observations; Session custom sources subscribe once; Session
  built-in uses the one Metrics endpoint attach/register equivalent. Main callbacks are allocation-free O(1) atomic signalling.
- The opt-in reusable carrier uses the existing Metrics executor/thread and one precreated one-slot nonsemantic carrier. It adds
  no Session lane, pool, task object, registry or bus. Ordinary endpoints retain their original queue path.

Implemented structural boundary:

- **R1:** `SessionMetricsReducer.kt`, `SessionState.kt`, `SessionAuthority.kt`. Mutable Metrics ingress was removed from copied
  `SessionState`; `materializeMetricsEffects` and callback/poll alternate commit paths were deleted. The same file owner adapted
  the reducer to R2's sealed handle result without an alias.
- **R2:** `CaptureMetricsContracts.kt`, `CaptureMetricsMechanicsOwner.kt`, `CaptureMetricsOwner.kt`,
  `BuiltInCaptureMetricsSource.kt`, new `BuiltInCaptureMetricsAttachment.kt`. Typed attachment/refresh/close/endpoint and
  observation outcomes replaced the old coordinator flags; the substantive Android attachment was extracted from Mechanics.
- **R3:** `RuntimeOwnership.kt`, `SessionRuntime.kt`. Attachment handoff precedes `SessionRuntimeStartedFact`; failed handoff is
  explicit. Runtime consumes typed observation settlement plus endpoint termination and no longer infers whole Metrics closure
  from endpoint termination alone.

Current closure blockers:

- **Early completion:** completion before `RuntimeStarted` can be consumed while readiness cannot enter Session, miss close against
  the unpublished partial Metrics root and strand later startup in `AwaitingMetrics`.
- **Attachment terminal conversion:** a lazy second `MetricsIngressBinding` projection can hide the eager attachment access from
  terminal cutoff before first ingress/poll. The projection must be deleted and terminal must arbitrate the exact accepted
  attachment under its one settlement gate.
- **Carrier admission:** failed slot CAS can recover `QUEUED -> RESERVED | REARM` with an empty slot and no actor obligated to
  re-admit, losing the Main-to-Metrics edge.
- **Carrier quiescence publication:** after seal/poison/termination, Control can observe the carrier on-stack and defer shutdown;
  the later physical transition to quiescent `IDLE` currently has no guaranteed successor signal.
- **Standalone registration/fatal ownership:** registration-attempt escape is not retained for matching unregister; a direct fatal
  can pass the `Exception` catch and requeue/retry the drain on a replacement worker.

Fresh adjudication fixed the repair constraints:

- Accepted healthy attachment terminal-before-entry remains the same mandatory one-shot, converted under its settlement gate to
  no-watchdog cleanup; its later Runnable performs the original subscribe/register once, adopts and closes any handle. Ambiguous
  submission and entered/in-call work transfer intact. Poisoned late entry is inert. The occurrence itself owns the sole finite
  readiness deadline; do not fabricate `StructurallyNoHandle` before exact no-entry/termination evidence.
- Carrier failed-slot recovery needs a complete iterative re-admission handoff. In addition, an actual post-task transition to
  quiescent `IDLE` after `SEALED | POISONED | TERMINATED` publishes one allocation-free best-effort settlement signal; ordinary
  open returns do not add a redundant wake.
- Completion is consumed once into `ClosedRetainingLast`, emits its one Control-serialized diagnostic and requests close, while
  retaining one typed joint-readiness projection. A later matching `RuntimeStarted` consumes that projection once without
  reopening ingress or re-emitting completion. Close must find the exact partial Metrics root before runtime publication.
- Standalone registration uses a closed `Prepared -> RegistrationAttempted -> Registered/Closed` progression. Direct fatal closes
  admission, preserves/rethrows the same raw throwable and permits cleanup only; it never retries registration.

Three fresh cohesive design axes, their cross-check and fresh adjudication selected the minimal implementation:

- **Carrier:** retain the existing five phases, one atomic word and `REARM`. On failed carrier slot CAS, roll back
  `QUEUED -> RESERVED`, recheck the slot and self-admit if already empty; otherwise the exact semantic remover owns the next
  admission. Add one `CLOSED_QUIESCENCE_SIGNALED` bit in the same word; the first CAS winner that reaches closed `IDLE` signals
  Control once. No extra atomic, phase, queue object, file or unconditional open-return wake.
- **Occurrence deadline:** add one named opt-in Boolean `deferDeadlineArmUntilOutwardCall` and one settlement-gate-confined
  one-shot authorization bit to `OperationOccurrence`. Reuse existing entry results and terminal arbitration; no new enum/result
  hierarchy. Attachment entry claims the occurrence, then authorization immediately before outward subscribe/register arms the
  sole deadline in Active or permits the same call without watchdog in Cleanup. Remove the separate Metrics deadline/guard state.
- **Runtime/Session:** expose eager attachment access from the physical owner and delete `MetricsIngressBinding`. Early completion
  commits `ClosedRetainingLast` once with its close/diagnostic and one typed latent readiness; later exact `RuntimeStarted` consumes
  that readiness once. Close resolves either published runtime or exact partial Metrics root. Existing completion effect ordering
  remains unchanged; `CommittedTurn` is not touched.
- **Standalone:** replace the registration Boolean with worker-confined `Prepared -> RegistrationAttempted -> Registered/Closed`;
  attempted/registered owns one unregister attempt. Direct fatal closes admission, prevents requeue/retry, preserves cleanup-only
  ownership and rethrows the identical raw throwable.

Exact implementation waves:

1. Parallel disjoint writers: `PrivateExecutorRuntime.kt`; `OperationSettlement.kt`; `BuiltInCaptureMetricsSource.kt`.
   Settlement is repaired and fresh focused-PASS: one private gate-confined closed authorization state
   `NotRequired | Awaiting | Authorized | Denied`; all return-publication paths accept only `NotRequired` or `Authorized`.
   Carrier state-machine recheck PASSed B1/B2 but found bounded queue-mechanics blockers: replace one-pointer LockSupport waiters
   with proper multi-waiter `ReentrantLock` Conditions, make `remove` equality-correct and make `drainTo` lifecycle exception-safe.
   That queue-mechanics repair is implemented. Its fresh focused recheck PASSed B1/B2 and queue basics but found one remaining
   carrier blocker: administrative removal (`remove`, iterator removal, `clear`, `drainTo`/`shutdownNow`) must cancel the exact
   queued carrier and never invoke the semantic-displacement re-admission path; shutdown must seal carrier admission before any
   destructive queue path. That repair is correctness-PASS on fresh focused recheck, but simplicity FAIL: `SEALED`, `POISONED`
   and `TERMINATED` carrier bits are behaviorally read only as one closed union while their reasons already live in durable runtime
   state. They are now collapsed to one `CARRIER_CLOSED` bit and a no-argument admission-close operation, retaining the five
   phases, `REARM` and the same-word quiescence-signal bit. A fresh two-pass recheck PASSed correctness, JMM/queue contracts,
   simplicity and bounded CPU/memory/wakes; the carrier seam is focused-PASS.
   Standalone's first terminal-order repair was insufficient: a fresh recheck proved split callback/dirty/admission atomics could
   permit post-seal requeue; the latch could open before exact executor termination; observer `onFailure` nonreturn could precede
   mandatory unregister; rejected execute could call the observer outside terminal protocol. The same owner has now replaced those
   mechanics with one allocation-free `OPEN/DIRTY/EPOCH/ADMITTED/CLOSE/SEALED` word and CAS seal, orders shutdown -> exact
   unregister -> ordinary observer report, and releases the external close latch only from exact executor termination. Fresh
   focused recheck PASSed those mechanics but found one deterministic fatal-precedence bug: an ordinary failure followed by the
   first non-`Exception` cleanup fatal can keep the ordinary failure authoritative and swallow/replace the earlier fatal. The same
   writer repaired the existing `retainFirst` path so every direct fatal first nominates the exact earliest-fatal reference, which
   remains authoritative while all distinct other failures are safely suppressed. Its fresh recheck PASSed that repair but found
   one adjacent reentrant-close loss: worker-thread `close()` discarded `terminateOnWorker(null)`'s authoritative fatal. The same
   writer is applying the minimal propagation repair. Because two successive rechecks exposed the same fatal-retention class,
   freeze no further standalone patch chain: after this repair run one fresh broad standalone terminal/ownership/simplicity review
   before whole-M1 closure. No new state is allowed.
2. After the settlement API: Metrics writer owns `CaptureMetricsMechanicsOwner.kt` and `CaptureMetricsOwner.kt`; then runtime
   writer owns only `SessionRuntime.kt` and deletes the lazy binding/projection. Both are implemented with two self-reviews:
   eager non-null attachment access is owner-bound; the partial Metrics root is CAS-published before handoff and an allocation-free
   `runtime -> partial -> runtime` lookup closes the publication/clear race, so exact close/arbitration works before RuntimeStarted.
3. Session semantic writer owns `SessionState.kt`, `SessionMetricsReducer.kt` and `SessionAuthority.kt` for typed latent readiness.
   Its pre-edit causal review found one exact runtime-boundary blocker: after partial Metrics-root/attachment publication but before
   `RuntimeStarted`, terminal Session state has observation identity but no owner, while `metricsAttachmentAccess(owner)` requires
   that owner. `requestMetricsClose(identity)` fences/closes the leaf but does not arbitrate the deferred attachment occurrence;
   therefore terminal cutoff can miss exact occurrence arbitration and a later outward subscribe can still win. No semantic
   files were changed. The writer's second causal pass further reports that identity lookup alone can lose to publication after
   lookup, and nesting the private runtime startup-admission lock under `sessionGate` would violate the frozen lock order. Its
   two-pass review and fresh guardrail cross-check now agree that identity lookup alone is insufficient and that active attachment
   cutoff uses `mandatoryCleanup=false`: cutoff cancels unentered outward subscribe while already-entered work transfers. They
   agree on one precreated fixed typed Session-owned cutoff and disagree only on the breadth recorded in the course-health diagnosis
   above. No edit is authorized. Do not add a second authority, registry, lazy/nullable projection, Boolean side channel or
   forbidden nested gate.
4. Every writer performs two source-level self-reviews. Then run three entirely fresh whole-M1 closure axes. On three PASS
   verdicts, record
   `M1 frozen/PASS; M2 next but not started` and stop for the user.

Do not change `CommittedTurn.kt`, `CaptureMetricsObservationIdentity.kt`, semantic ordinals, public API, docs, cleanup cutover or
M2+ domains. Reject opportunistic deletion, another flag/check patch, new lane/queue/authority/framework or broad generic redesign.

Acceptance remains source-level causal review of inline/reentrant/reverse callbacks; positive/handle order; null/throw/reject/
poison/nonreturn/late handle; D-1/D/D+1; duplicate/loss/recovery/dirty successor; early completion; terminal cutoff; exact residue/
producer settlement; carrier admission/quiescence; standalone fatal cleanup; architecture/simplicity and bounded CPU/memory.
Tests/build/runtime/device/performance/Git/bytecode/hash work remains unauthorized.

### Internal component-contract specification — current user-approved gate

The user explicitly moved this gate ahead of unfinished M1 and all further production code. Create **one separate Markdown document**
for the full Screen Capture Engine internal component architecture and inter-component contracts. It is subordinate to and must not
rewrite frozen Docs01–12; after its own closure review it becomes implementation authority for internal component boundaries.
Use `screencaptureengine/design-next/14-internal-component-contracts.md`; Doc13 already exists as nonnormative material. Do not put
the specification itself in this low-level tracker.

The document covers all critical engine components and seams, including Session authority/runtime/control, settlement/executor,
Metrics, Android projection/lane/VirtualDisplay, Target, GL, JPEG, Storage, Delivery, Observation/Stats, production,
reconfiguration, cleanup assembly/coordination, public facade and JNI/platform adapters where applicable. Use one compact contract
card per real seam, not per class and not one mega-interface. Begin with one complete top-level component/seam inventory and a
small set of thin end-to-end scenarios (start, first readiness/Active, frame delivery, reconfiguration, normal stop and fatal/
partial-start cleanup) so that missing boundaries and circular ownership are visible before cards are frozen. Each card records:

- sole semantic owner, physical resource owner and exact responsibility boundary;
- minimal directed typed inputs/outputs, identity/provenance/cardinality and real producer/consumer;
- thread/lane/gate context, legal lock order, linearization point and progress/wake obligation;
- closed success/rejection/stale/failure/terminal-transfer/late-result/cleanup-residue outcomes;
- ownership-transfer versus immutable-fact semantics and partial-start/terminal behavior;
- CPU/GPU/memory/allocation/thread bounds on critical paths;
- frozen authority references plus current primary official platform/language/runtime guidance;
- negative manifest, minimal race/proof matrix and thin end-to-end trace through adjacent components;
- explicit classification of every material item as **known/frozen**, **implementation-private**, or **open/ambiguous** with the
  exact research, official-source check, isolated spike/proof or user decision required to close it.

Freeze only stable cross-component obligations: ownership, ports, execution context, ordering, closed outcomes, terminal/cleanup
semantics and performance bounds. Do not freeze helper layout, private algorithms or speculative CAS phases. Unknown platform or
concurrency behavior must never be hidden by nullable state, Boolean flags or assumptions; if safety depends on an unproven callback
order, ownership after throw, receipt or happens-before edge, stop dependent implementation until primary-source research and proof
close it. AOSP may explain behavior but cannot strengthen the public API contract.

The specification is critical and requires three fresh independent research/input axes before its writer consolidates the first
complete draft, followed by three entirely fresh independent closure-review axes after it is complete: (1) product/ownership/
component responsibility and completeness, (2) concurrency/linearization/terminal-cleanup proof, and (3) simplicity/
maintainability/CPU-GPU-memory plus current official-guidance fit. The same reviewers cross-check disagreements; adjudicate only
residual material conflict. Review must reject duplicate authority, mega-interfaces, generic registry/event-bus/plugin frameworks,
one-type-per-Boolean evidence inflation, premature Gradle modularization and contract duplication of frozen docs. M2 opens only
after all findings are fixed and all three axes PASS.

#### M2 — Android lane/projection-stop claim core

- Preserve the physical Handler/Looper execution lane. Introduce one closed `AndroidLaneLifecycle` owning constructed,
  start-requested, ready, failed-before-run, closing/poisoned and returned states plus admission/reservation/termination evidence.
- Introduce one `ProjectionStopAggregate` owning projection identity, normal-ticket binding, callback-stop observation,
  direct-final obligation/admission, physical call and closure/final-return disposition. Remove nullable never-start proofs and the
  superseded lane-start/terminal/direct-admission claims in the same packet.
- Adapt immediate Session and VirtualDisplay callers exhaustively. Pending, ready and failed-before-run partial roots all retain
  an exact cleanup route; ambiguous posts refine only after exact worker-return evidence. Preserve same-handler callback sentinel,
  exact Target dispositions and final-Control sole-last-action semantics.
- Decompose `AndroidCaptureOwner` only along disjoint ownership graphs. Keep the generic `OperationSettlement`/deadline kernel;
  narrow use or strengthening of an existing terminal-refinement API is allowed, generic redesign is not.

#### M3 — exact pre-Active topology handoff

- Reconcile the canonical Metrics fact, exact Android root and frozen Target candidate/application contract into one typed
  `AwaitingTopology`/`TopologyBuildRequired` handoff. Validate and either commit the Target candidate or return exact
  unused/retired disposition. Complete partial-start cleanup from every reachable pre-Active state.
- Public state remains `Starting`; start completion and Production/frame-Delivery admission remain closed. Do not add render,
  JPEG production, Storage or public `Active` merely to make the checkpoint reachable.
- M1–M3 form one coherent internal foundation checkpoint, not formal B2 closure or product readiness. When stable, run three fresh
  independent axes: concurrency/provenance/entry-return/terminal; architecture/decomposition/maintainability/CPU-memory; and
  whole startup-cleanup reachability/product-authority fit. The same reviewers cross-check divergent evidence; use a fresh
  adjudicator only if material disagreement remains. Fix all findings and rerun all three before recording the checkpoint PASS.

#### M4/M5 — later startup and production integration

- **M4:** build compatible Target producer + GL render topology + JPEG carrier/backend/framework-owner topology and healthy
  prestarted Delivery endpoint. Only exact complete topology evidence may publish the first truthful `Active` and reopen
  Production/frame-Delivery admission. First encode/payload/consumer/Storage transaction is not an `Active` prerequisite.
- **M5:** complete GL/JPEG/Storage/Delivery production, pacing, Stats/Observation and remaining normal streaming behavior.
- M4/M5 stay closed until the M1–M3 internal foundation receives all three final PASS verdicts. Cleanup branch work and Session
  cutover follow leaf/topology stabilization according to the sections below.

### Frozen B1/C/D foundation tranche — closure-PASS

- **B1 Target:** detached producer and initial-release candidates, stable commit/unused dispositions, canonical four-state
  producer truth, exact installed/nonterminal-Prepared predecessor retirement, permanent terminal admission closure and split
  Surface/TargetScope/selected-Namespace terminal conversion. Terminal closure drops impossible successor proof and does not
  retain the retired Target graph. Android caller cutover and controller application remain B2/B3.
- **C GL:** healthy deletion, aggregate `ContextNamespace` retirement, structural no-context, exact return/nonreturn/quarantine,
  deferred endpoint release and post-return-OOME reduction. Endpoint termination remains distinct from physical retirement;
  Target-owned occurrence/application authority does not transfer to GL.
- **D JPEG/Delivery:** exact endpoint shutdown, pending-demand replay, real termination receipt, separate endpoint-root release
  and opaque released tokens are implemented. This frozen tranche excluded Metrics because its real owner/coupling is in Android
  scope; the separate Metrics A-prime checkpoint above now implements that leaf without inventing a dead foundation. JPEG terminal
  admission is exactly `Open -> TerminalCleanupOnly -> Closed`; physical-root readiness is separate from endpoint-ticket
  settlement; pacing uses allocation-minimal scalar selection.
- Three fresh final axes PASS the coherent frozen tranche: concurrency/ownership/terminal truth,
  architecture/decomposition/CPU-GPU-memory efficiency, and full-boundary reachability/completeness.
- Frozen exclusions: Metrics leaf freeze/Session consumption; Android initial/staged producer and release cutover;
  controller/Session predecessor, terminal retirement and application wiring; production pacing/JPEG/Delivery reachability.
  Expected intermediate source incompatibility is confined to B2/B3 cutover seams; no compatibility shell is authorized.

### Cleanup branches and Session cutover

- After leaf freeze, write disjoint cohesive branch files: `AndroidProjectionCleanupBranch`,
  `TargetConstructionCleanupBranch`, `GlSessionCleanupBranch` in parallel; then `InstalledTargetCleanupBranch`. Branches never
  call one another or own lifecycle/currentness/terminal policy. Split a branch later only if it proves two independently
  progressing ownership graphs—not for method or line count.
- Add a small `SessionCleanupCoordinator` that joins immutable typed actions/outcomes and progresses every independent branch;
  blocked/nonreturn work quarantines only real dependents. Existing Control wake aggregate, final carrier, final-turn seal and
  Control-last semantics remain unchanged.
- One fresh integration writer then owns the cutover in `RuntimeOwnership.kt`, `SessionRuntime.kt`, `TerminalTransitions.kt` and
  only narrow fact/authority folds if required. Delete `PartialRuntimeRoots`, `RuntimeRoots`, `SessionRuntimeResidue`, nullable
  cleanup wrappers, `CleanupProgress`, global cleanup early returns and cleanup mechanics from `SessionRuntime`; do not replace
  them with forwarding methods. SessionAuthority retains only terminal commit and typed fact consumption.
- After integration: three fresh independent reviews—terminal/cutoff/Control-last; physical ownership/late facts/nonreturn;
  architecture/decomposition/file responsibility. One bounded repair pass and narrowing recheck.

### Restart and stopping boundary

- This tracker is sufficient after compaction/restart; no old agent chronology is required. Root must reread it completely,
  preserve frozen Docs01–12 and accepted behavior/ownership evidence, and continue only from the component-contract gate.
- **Current stopping point:** the user explicitly ended the current work cycle after approving the sequencing change. Start no
  research, review, documentation writer, production writer or other task until a new user instruction begins the next cycle.
- Exact next procedure for that future cycle: start no production writer. Run three fresh independent
  architecture/research axes for the full-product component inventory and contracts, consolidate them through one documentation
  writer into `design-next/14-internal-component-contracts.md`, fix findings and run three new closure-review axes. Use same-reviewer
  cross-check and fresh adjudication only for residual material disagreement. Then stop for user review before planning code.
- The component specification settles the standalone terminal site and shared startup-admission/cutoff boundary across all
  components. Only after its closure-PASS may root plan one coherent replacement/refactor and resume implementation. Existing
  M1/M2/M3 packet labels are nonbinding and may be replaced by boundaries derived from the accepted component graph. Public
  startup/admission, production and release remain closed.
- Any newly discovered material course change first receives a fresh guardrail review. If it requires new product/document
  authority, stop and obtain user agreement. Do not reopen frozen A/B1/C/D-JPEG/Delivery without new concrete evidence.
- M4/M5, cleanup branches, Session cutover, production reconfiguration and public facade remain closed. Intermediate
  noncompilation is allowed; source-level causal completeness is mandatory. Tests/build/runtime/device/emulator/performance/Git/
  bytecode remain out of scope.

## Overall program roadmap and current position

1. **Runtime Spine Path Phase 1 — complete.** Docs01–12 authority, public contract direction, single Session authority,
   six-lane topology and core leaf/runtime/control seams are established and independently reviewed.
2. **Phase 2 / Critical Stage 4 — component-contract gate current.** A and B1/C/D-JPEG/Delivery remain frozen/PASS evidence;
   Metrics A-prime is focused-PASS evidence; M1 is unfinished and no longer drives the design. The full-product internal component-
   contract specification is authored and closure-reviewed before any more code. After it passes, plan one broad conformance
   refactor; M1/M2/M3 labels may be redrawn or removed if the accepted component graph makes them artificial. The refactor must
   delete obsolete authority/state/proof code rather than wrap it, and its coherent boundaries receive three fresh final axes.
   Then follow leaf/topology stabilization, cleanup branches and Session cutover. Completion requires old root/progress
   models removed, all currently reachable startup-terminal cleanup paths converged or honestly quarantined, and all three final
   integration axes PASS. This phase adds no product reachability.
3. **Startup completion.** M4: API34 initial resize/reconciliation → exact Target producer → GL render owner/quiescence →
   JPEG/Framework topology + healthy prestarted Delivery endpoint → exact topology-ready proof → first truthful `Active`.
4. **Production completion.** M5: pacing, GL/JPEG/Storage/Delivery production, Stats, callback unsubscribe and all remaining
   normal streaming behavior.
5. **Serialized reconfiguration.** Pause → relevant drain → serialized reconfiguration → resume, with latest-desired coalescing
   and the approved grandfathered handoff rule.
6. **Extended cleanup closure.** Add and verify cleanup roots made reachable by production, handoff and reconfiguration.
7. **Standalone library cutover.** Complete public facade/factory, lifecycle reachability and packaging; only then open the
   public factory.
8. **Application integration.** Migrate app modes as a later separate gate after standalone library closure.

## Immediate watchpoints

- Do not begin render/JPEG/Storage/Delivery readiness, production, reconfiguration or public facade work before the current
  foundation refactor is closure-PASS.
- Do not preserve seamless Retained/Replacement machinery merely because it exists.
- No outward work under Session or leaf gates; entry/return and ownership remain fact-driven.
- Do not weaken terminal priority, platform ownership, one-VD/one-JPEG-runtime, JNI or Control-last.
- Do not redesign the focused-PASS Control aggregate/final-carrier/seal without new concrete evidence.
- Do not introduce a fake ingress, generic registry/manager/bus, forwarding shell or another mega-owner.
