# Screen Capture Engine — Current Work Tracker

## Authority and current gate

`design-next/01-authority-router.md` through `design-next/12-domain-delivery-observation.md` are the normative authority in
router order. Doc13 is nonnormative; the old `design/01`–`09` package is not authority. Kotlin is implementation evidence.

Docs01–12 are user-approved/staged and closure-PASS. Docs03/04/05/06 include the user-approved projection-stop terminal delta:
normal stop uses Android Handler; one exact Android-owned final cleanup action may run on Control after the Android lane is
proved unavailable/unentered; only normal return closes projection; throw/nonreturn/ambiguity retains quarantine and Control.
Documentation is frozen.

The independently validated causal startup-ownership/cleanup-foundation refactor plan is **explicitly user-approved**. Packet A
and the B1 Target + C GL + D JPEG/Delivery foundation tranche are implementation-frozen and review-PASS. Metrics remains an
explicit Android-scope blocker. B2, B3, cleanup branches and Session cutover have not begun; Critical Stage 4 is not complete.

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
- Agents perform two self-review passes. Verification is proportional to risk. Android CLI is first when it provides stronger
  Android-specific coverage; official Android/AOSP, Kotlin/JetBrains, Oracle/OpenJDK/JNI and Khronos sources are preferred.
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

## Confirmed open architecture problem

The current `PartialRuntimeRoots`/`RuntimeRoots` split and monolithic `CleanupProgress`/`progressStartupCleanup()` are the wrong
private foundation for later startup, production and reconfiguration. A previously alleged *currently reachable* late-root-after-
transfer race was independently rejected; do not claim or patch that nonexistent interleaving. The mutable nullable-snapshot model
is nevertheless structurally unsafe for future attachment and obscures a closed terminal root manifest.

Independently confirmed current gaps:

- a Ready Android lane in partial startup has no normal stop/quit cleanup path;
- initial VD preparation commits Target producer state before a durable Android operation root;
- unresolved callback cleanup globally blocks independent Target/GL cleanup progress;
- exactly Rejected projection stop has no lane-termination/final-Control continuation;
- Android-owned Metrics endpoint/coupling has no approved decomposition or implementation packet yet;
- the old global root/progress path still prevents dependency-local branch progress and final Session cutover.

Frozen A/B1/C/D-JPEG/Delivery already establish the typed assembly, Target terminal closure, GL namespace retirement and endpoint
termination/resource separation required by the approved direction. The remaining causal path is B2 durable Android ownership →
B3 exact Target application → leaf freeze → dependency-local cleanup branches → Session cutover. A blocked branch must quarantine
only its real dependents; the existing Control aggregate/final-carrier/seal remains semantically unchanged.

No documentation change or new product decision is currently required.

## Validated refactor plan and exact next procedure

Multiple independent architecture, decomposition, maintainability and migration reviews plus focused adjudication have converged;
no material disagreement remains. The plan needs no product or documentation change and is explicitly user-approved. Packet A
and the B1/C/D-JPEG/Delivery tranche are frozen/PASS. D-Metrics and B2/B3 remain open under their dependencies below.

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

### Remaining leaf work after the frozen foundation tranche

- **B2 Android durable graph:** after B1 freeze, durably own exact callback/listener/VD/mutation/post/lane/stop operation graphs
  before Target commit; preserve staged initial VD and existing attach/detach terminal rooting only; exact Rejected stop enables
  lane retirement and later existing final-Control route without retry or fabricated projection closure.
- **B3 Target application, only after B2 freeze:** validate the frozen Target candidate plus exact Android root, then commit or
  return typed unused/retired disposition. Reuse the B1 Target writer only for this one adjacent application packet when its
  context remains valuable.
- **D-Metrics decomposition:** before any writer, use a fresh guardrail/decomposition review to locate the real Android-owned
  coupling and bound the packet without inventing a dead Metrics foundation or second authority.
- Focused leaf reviews follow each packet; after B3 run a fresh Android↔Target provenance/entry-return cross-review. Freeze all
  leaf APIs before branch work.

### Frozen B1/C/D foundation tranche — closure-PASS

- **B1 Target:** detached producer and initial-release candidates, stable commit/unused dispositions, canonical four-state
  producer truth, exact installed/nonterminal-Prepared predecessor retirement, permanent terminal admission closure and split
  Surface/TargetScope/selected-Namespace terminal conversion. Terminal closure drops impossible successor proof and does not
  retain the retired Target graph. Android caller cutover and controller application remain B2/B3.
- **C GL:** healthy deletion, aggregate `ContextNamespace` retirement, structural no-context, exact return/nonreturn/quarantine,
  deferred endpoint release and post-return-OOME reduction. Endpoint termination remains distinct from physical retirement;
  Target-owned occurrence/application authority does not transfer to GL.
- **D JPEG/Delivery:** exact endpoint shutdown, pending-demand replay, real termination receipt, separate endpoint-root release
  and opaque released tokens are implemented. Metrics is not implemented because its real owner/coupling is in Android scope;
  no dead Metrics foundation exists. JPEG terminal admission is exactly `Open -> TerminalCleanupOnly -> Closed`; physical-root
  readiness is separate from endpoint-ticket settlement; pacing uses allocation-minimal scalar selection.
- Three fresh final axes PASS the coherent frozen tranche: concurrency/ownership/terminal truth,
  architecture/decomposition/CPU-GPU-memory efficiency, and full-boundary reachability/completeness.
- Frozen exclusions: D-Metrics; Android initial/staged producer and release cutover; controller/Session predecessor, terminal
  retirement and application wiring; production pacing/JPEG/Delivery reachability. Expected intermediate source incompatibility
  is confined to those B2/B3 cutover seams; no compatibility shell is authorized.

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
  preserve frozen A and the frozen B1/C/D tranche, and continue only from the open gates below.
- Exact next procedure: B2 Android durable graph is authorized because B1 is frozen. In parallel, run a fresh guardrail/
  decomposition review for the D-Metrics Android-owned coupling before assigning any Metrics writer; do not infer its file scope.
  Do not start B3 before B2 freezes. Do not reopen frozen A/B1/C/D-JPEG/Delivery without new concrete evidence and guardrail review.
- Cleanup branches, Session cutover, startup completion, production and reconfiguration remain closed.
- The next stable implementation boundary is integration plus all three final reviews PASS, old root/progress paths absent,
  current startup-terminal cleanup converged or honestly quarantined, public factory still closed and startup completion not begun.
- Intermediate compilation remains non-gating. Tests/build/runtime/device/emulator/performance/Git/bytecode remain out of scope.

## Overall program roadmap and current position

1. **Runtime Spine Path Phase 1 — complete.** Docs01–12 authority, public contract direction, single Session authority,
   six-lane topology and core leaf/runtime/control seams are established and independently reviewed.
2. **Phase 2 / Critical Stage 4 Foundation Refactor — current.** A and B1/C/D-JPEG/Delivery are frozen/PASS. D-Metrics remains
   blocked on its Android-owned coupling; B2 is the next authorized implementation packet, followed by B3, leaf freeze,
   cleanup branches and Session cutover. Completion requires old root/progress models removed, all currently reachable startup-
   terminal cleanup paths converged or honestly quarantined, and all three final review axes PASS. This phase does not add new
   product reachability.
3. **Startup completion.** API34 initial resize/reconciliation → render owner/quiescence → JPEG/Framework + empty Storage +
   Delivery readiness → exact topology-ready proof → first truthful `Active`.
4. **Production completion.** Pacing, GL/JPEG/Storage/Delivery production, Stats, callback unsubscribe and all remaining normal
   streaming behavior.
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
