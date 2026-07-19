# Screen Capture Engine — Current Implementation Tracker

## Authority

Documents `design-next/01-authority-router.md` through `design-next/12-domain-delivery-observation.md` are the sole
normative implementation authority, in router order. `design-next/13-future-evolution.md` is nonnormative. The previous
`design/01`–`09` package is not authority.

This tracker contains operational rules, current accepted implementation state, open obligations and compact handoffs.
It is not normative. If it becomes stale, current Documents 01–12 win and this tracker is corrected before more work
starts. Kotlin is implementation evidence, not authority.

Every Markdown file except this tracker is frozen. Only a direct user decision may authorize a separately bounded
documentation task.

## Permanent working rules

- Root is exclusively an orchestrator, the sole tracker owner/writer, status owner and gate integrator. Subagents receive
  bounded product tasks from root; they do not analyze this tracker or use its internal procedure as their task contract.
  Root does not perform technical implementation, audit, research, diagnostics or production builds unless the user
  explicitly asks for a bounded root-only check.
- Root protects its orchestration context. Detailed document/code reading, implementation and technical audit belong to
  bounded subagents. Root retains only compact decisions, dependencies, material evidence, disagreements, gates and the
  current handoff; it does not ingest full document copies, code dumps or repeated agent reports when a compact verdict is
  sufficient. Context economy never replaces evidence or agent dialogue needed for a correct decision: root requests and
  retains full technical detail whenever the unresolved product risk requires it. After compaction, root restores the task
  from this tracker instead of restarting completed analysis.
- At most three direct subagents run simultaneously. Every subagent must do its task personally and must not create,
  run, resume, delegate to or message another subagent or use collaboration/multi-agent. Nested delegation is forbidden.
- Before elevated-risk architecture, product, concurrency, graphics, cleanup/publication or JNI work, three independent
  planning axes derive the product plan from current Markdown authority. Root consolidates their material conclusions and
  sends one bounded product packet back for cross-review of critical decisions, disagreements and the writer boundary.
  Unchanged evidence and mechanical consequences are not independently re-derived or re-audited. A material unresolved
  disagreement uses a fresh adjudicator.
- Fresh agents are required for genuinely independent axes, broad reopening, architecture and adjudication. Reuse is
  preferred for the same task's bounded continuation, mechanical writer, correction or exact-context follow-up.
- If a slot/thread is stuck, root reports the real capacity immediately, continues with available agents, and resumes the
  remaining axis when capacity releases. Root never pretends rejected work is active.
- Root never interrupts, cancels, replaces or duplicates an active agent without direct user permission. An interrupted
  agent is never resumed unless the user explicitly orders that exact resume.
- Agents working longer than roughly two to three minutes send a concise heartbeat: completed work, current phase,
  finding/blocker and bounded remainder.
- Root gives each agent an exact product task, affected boundaries, required invariants and the router-selected authority
  packet. Agents read the normative documents needed for that product decision. Full Docs01–12 reading is reserved for a
  genuine architecture reopening or cross-document adjudication, not repeated for an unchanged bounded scope.
- Technical agents browse applicable official primary guidance from Android/AOSP, Kotlin/JetBrains, Oracle/OpenJDK/JNI
  or Khronos. For Android-specific inspection, documentation, project analysis, deployment or device work, use Android CLI
  first whenever it offers the stronger or more complete operation; use JetBrains/IDE MCP second where IDE semantic
  analysis is better or Android CLI lacks the capability, then raw terminal/Gradle only for unsupported verification.
  Frozen project Markdown remains normative product authority; external sources validate mechanism.
- Every finding is classified as an intrinsic defect, intentional migration state or named future-owned integration gap,
  and includes exact authority/source evidence plus a concrete correction.
- Critical product/platform/concurrency/ownership/safety/performance ambiguity stops for the user/designer. Secondary
  structure, visibility, naming, layout or mechanism may use the simplest sound recorded deviation.
- Frozen Markdown is immutable to implementers, not presumed infallible. When implementation-depth evidence reveals a
  gap, inconsistency, impractical constraint, needless complexity or a materially simpler design, stop that decision and
  return a concise designer-escalation packet instead of hiding it behind a workaround.
- Product behavior is the stable approval boundary: any proposed product-level change stops for explicit user decision.
  Technical architecture, ownership decomposition, file layout and implementation mechanisms in the frozen documents
  are best-effort design hypotheses informed by incomplete pre-implementation knowledge. They may be challenged and
  revised when implementation evidence shows a simpler or more correct whole-product design, but implementers never
  diverge silently: the proposed technical change, reason and document impact are cross-reviewed and agreed with the
  user before code relies on it.
- The finished working product is the objective. Priority is observable correctness, platform behavior, concurrency,
  graphics, ownership/resource lifetime, cleanup/failure/JNI safety, privacy/security and appropriate efficiency; then
  simple maintainable idiomatic Kotlin. Predicted mechanism and physical layout are secondary.
- Documentation exists to make that product correct, complete, simple and implementable, not to maximize formal
  guarantees or sustain an endless audit cycle. Keep product-critical behavior exact; when a non-product guarantee needs
  disproportionate machinery, weaken or remove it explicitly and close with the smallest sound design.
- Root does not begin a materially different slice after completing the current one until the user agrees the next
  action. Safe bounded continuations inside an approved slice follow its recorded procedure.
- After each implementation stage, root delegates one bounded revalidation of the next roadmap stage to a fresh or
  exact-context subagent. That check uses the interfaces and ownership boundaries actually produced, and does not repeat
  the completed audit. Mechanical impact updates the next task narrowly; a material architecture or critical-boundary
  change reopens the three independent planning axes before any writer. Root orchestrates this check and does not perform
  the technical revalidation itself.
- Preserve unrelated work. Root and agents never stage, unstage, commit, reset, checkout, revert, mutate index/history or
  otherwise take over Git ownership without direct user instruction. The user exclusively owns staging and commits.
- Tests are entirely out of scope until the user separately authorizes them.
- One writer owns every affected file. Overlapping writers are forbidden. Accepted Kotlin is a checkpoint, not frozen
  code, and may be reopened when evidence requires it.
- Current code is best-effort implementation evidence, never a design constraint. Future work may refactor, replace,
  split, merge, simplify or remove it whenever that better satisfies Documents 01–12.
- Final passive declarations may land early only with permanent domain ownership and no placeholder behavior, mutable
  authority, lifecycle transition, arbitration, platform call or fabricated health/receipt.
- Physical layout follows the authority manifest, cohesion and one-way dependency. File size is only a review signal;
  never compress names or remove evidence merely to meet a line target.
- Every subagent performs two self-review passes. A writer formats only its scope, uses available diagnostics, performs
  proportional verification and stops for user manual review. Unavailable IDE tooling is noted once and is not a blocker.
- Closure uses one independent read-only round proportional to changed product risk. Elevated design, GL,
  concurrency/lock/publication, cleanup/fatal and Native/JNI decisions use three independent axes and consolidated
  cross-review once; JNI candidate defects receive explicit disposition from all three. Gate, deadline, late-return,
  nonreturn and nontermination behavior is checked where the changed boundary can actually affect it. Unchanged evidence
  and already-settled claims are not rechecked without a concrete conflicting signal. The user may explicitly select a
  narrower review for a planned mechanical correction.
- Screen Capture Engine production audits inspect `build.gradle.kts` only where the active slice requires native
  configuration or wiring. Generic SDK configuration is not a finding outside that scope.
- Production Kotlin 2.4 must be direct, typed, compact, readable and idiomatic. Prefer immutable facts, narrow visibility
  and explicit ownership; avoid speculative abstractions, compatibility layers, duplicate models and generic machinery.
- Android platform branches read `Build.VERSION.SDK_INT` at the owning boundary and use typed
  `Build.VERSION_CODES` constants. Do not thread caller-supplied SDK surrogates or duplicate platform constants.
- Prefer an existing exact platform, standard-library, protocol or project constant over an equivalent raw literal. Do
  not invent named constants for self-evident local sizes, offsets, sentinels, counts or frozen protocol values.
- Preserve checked sizes, narrowing, limits, identities, bounded work, minimal copies, exact cleanup and resource roots.
  Do not use `Any`, fake health flags, fabricated receipts, raw handles as ownership proof, incomplete behavior,
  duplicate authority/topology or predictive accounting.
- Keep this tracker current, not archival. Retain rules, current queue, open blockers and approved handoffs; delete
  writer/auditor chronology, hashes and line-count pins, superseded findings and repeated verification narration.
- Work from the current relevant file contents. Do not spend work cycles routinely polling Git/index state, calculating
  hashes or rereading unchanged files. Recheck a source only after its relevant contents changed or when a concrete
  product-critical contradiction requires it. Git ownership remains governed by the preservation rule above.
- Before compaction or restart, root records every material current handoff, decision and next gate here, then rereads it.

## Current state

- Three fresh read-only strategy axes and their consolidated cross-review are complete. All reject both Stage 5/API on
  top of the current Controller and a disposable Stage 5 code draft; no material disagreement remains and no adjudicator
  is needed. No production code or frozen documentation changed, and no builds/tests or Git/index operations ran.
- Documents 01–12 remain current authority. Doc01 contains the short user-authorized Controller source-manifest amendment.
  Runtime/Metrics/Android, Target/GL, JPEG, `CleanupOwner` and `ObservationOwner` remain accepted leaf foundations, but all
  current Kotlin is best-effort evidence and may be substantially refactored or replaced.
- Stage 4 is **STOP after final reclosure and consolidated cross-review**. No material disagreement or document ambiguity
  remains, so no adjudicator is needed. Builds/tests were not run and Stage 4 conformance is not claimed.
- Current Controller structure is not accepted. `SessionController.kt` is 3,756 lines. `SessionControlScheduler.kt` is a
  substantive extraction and `SessionStartupTopology.kt` is partial; `SessionReconfiguration.kt`,
  `SessionTerminalCleanup.kt` and `SessionPublication.kt` remain thin while most orchestration stays in Controller.
  `SessionStartupTopology` receives raw `sessionGate` through a command, contrary to the amended subordinate-unit rule.
- Binding blocker groups:
  1. **Control fatal/lifetime:** direct fatal records poison but does not close admission or establish emergency fail-close;
     poison is not a scheduler-level first-action fence; failure publication may signal before poison; later signal,
     remove or shutdown can replace the exact raw failure. Healthy final Control shutdown is absent. `terminated()`
     illegally takes `sessionGate`, allocates collections and signals instead of only releasing its precreated receipt/root.
  2. **Terminal transfer/Control residue:** cutoff attaches top-level owners but does not atomically fold and transfer the
     exact bounded occurrence/deadline/wake/ticket/owner-bag inventory. Post-cutoff code still performs active arbitration,
     can install late JPEG preparation and creates new terminal deadlines. `ControlWakeResidue` is not wired, so
     `AwaitingStage5Delivery` overstates Stage 4 cleanup readiness.
  3. **Cleanup evidence/residue:** cleanup actions swallow ordinary failures or return `false`, while the invoker observes
     only outward throws. Attempt return is confused with physical release; Metrics/Android/GL residue may be forgotten,
     and one-shot Target/GL work can remain stuck without quarantine. Typed outcomes and exact normal/quarantine reduction
     are incomplete.
  4. **Metrics readiness:** joint readiness is committed by Metrics and later adopted by Controller through a separate
     `claimLatest()`; an intervening loss can turn valid readiness into the wrong `InternalFailure`.
  5. **Reconfiguration/topology/authority:** API 34 provisional-to-authoritative replacement cannot converge while
     Starting; Running replacement is not smallest-scope and crosses the irreversible boundary before close/drain/fence;
     Android VD resize/detach/attach is unused; Android result classification collapses OOM, illegal state, unexpected
     failure, expiry and rejection into `CaptureUnavailable`; the accepted topology snapshot is hybrid and lacks coherent
     versioned Android/Target/GL/JPEG/Framework health/lease/installed facts. `TargetOwner` retains/acquires
     `sessionGate`. Post-Active deterministic denial retains Active instead of the required recoverable suspension.
- Confirmed positive facts: Control is published only after successful prestart; typed task comparison unwraps the peer
  delegate; asynchronous Android startup failure is folded; initial VirtualDisplay uses logical `W×H,D`; desired-update
  mutation/publication is gate-coherent; base terminal publication remains final Stats → one diagnostic attempt →
  terminal State and physical cleanup starts afterward; Target quarantine has one tagged child. Active
  frame/readback/encode/Delivery work remains Stage 5. JNI frame-compress remains dormant, while Stage 4 legitimately uses
  native library load/capability/carrier allocate/free; no JNI correction is indicated.
- The approved program is named **Stage 4+5 Runtime Spine Path** (`Runtime Spine Path` for short):
  1. Short design pass for the final Stage 4+5 runtime spine: production flow/currentness, topology lease, Delivery and
     unsubscribe ownership, complete wake inventory, terminal transfer, cleanup dependencies and reconfiguration
     invalidation. Protect observable Doc02 behavior; identify any technical-only Docs01/03/05/12 amendments explicitly.
  2. Refactor only the critical Stage 4 foundation needed by a real frame: close the five blocker groups, add final
     production/Delivery authority seams, rewrite Delivery as one dedicated direct-callback lane and make it a real
     startup/terminal dependency. Do not first perform a total Controller decomposition or cosmetic file cleanup.
  3. Land one permanent FrameworkOnly vertical slice through the real public Session API: source → pacing → GL/readback
     → Framework JPEG → storage → Delivery callback → unsubscribe → stop and terminal convergence. No mocks, bypasses,
     temporary API or compatibility layer.
  4. Extend the durable path with pacing modes, cache/repeat, complete Stats/diagnostics, Native/fallback and full dynamic
     reconfiguration; then consolidate semantic ownership, file layout and technical documentation from the proven path.
  Keep the current six isolated Session lanes for this work. Stateful subordinate coordinators are technical-only, not
  product changes, but current sole-authority wording requires an explicit agreed amendment before such mutable facets are
  implemented. The Controller must retain lifecycle, terminal winner, public cutoff and cross-domain commit authority.
  The next authorized action is the short Stage 4+5 runtime-spine design pass. Production implementation begins only
  after that pass produces one cross-reviewed responsibility model, exact first-slice boundary and any required concise
  technical-document amendment packet.

## Product handoff anchors

Root converts these compact anchors and the relevant normative document packet into each bounded product task. Subagents
do not need tracker procedure. These anchors are not a substitute for Documents 01–12:

- One active Session owns exactly six runtime threads: Control STPE, Metrics TPE, Android HandlerThread, GL TPE, JPEG
  TPE and Delivery TPE. Main/provider/collector/native callback threads are non-owned.
- Metrics/GL/JPEG/Delivery use separate prestarted bounded `CORE-EXEC-1` endpoints with one unsettled ticket,
  entry-wins, monotone poison/no-reuse after outward submission throw, orderly shutdown and real `terminated()` receipt.
  Control and Android use their separately specified scheduler/post protocols.
- `CORE-FATAL-1` keeps exact raw fatal authority, settlement and poison. Direct `Error` identity remains exact at thread
  top. On the four ordinary TPEs, a custom non-`Exception`/non-`Error` Throwable may be runtime-wrapped at thread top;
  do not add bridges or reinterpret that wrapper. Preserve the narrow optional-Native own-load `UnsatisfiedLinkError`
  fallback and the separate Control/Android fatal specializations.
- Control uses one central wake protocol for deadline, pacing/repeat and Stats. `Suppressed(g)` is complete
  generation-scoped engine-operational settlement after its exact publications settle; outer queue removal/return remains
  physical cleanup/termination evidence, not a successor prerequisite. Control stays receive-only and terminates last;
  its own `terminated()` releases only its exact root inline without resubmission.
- Metrics uses the callback/subscription API. Joint readiness requires both a timely first positive and adopted normal
  nonnull handle. Completion before joint readiness is startup `CaptureUnavailable`; completion after readiness retains
  the last tuple and may continue startup. Null handle/subscribe `Exception` remains authoritative `InternalFailure`.
  Arm `S,D` immediately before observation attachment and sample positive callback `T` with ingress sequence under
  `sessionGate`. Close waits full attachment-ticket settlement; duplicate full authority keys are no-ops.
- Delivery invokes the application callback directly on the isolated Delivery lane. Preserve one handoff, exact
  unsubscribe, borrowed-frame checks, `byConsumerBusy` and `byCallbackFailure`. The caller copies synchronously before
  dispatching its own copy elsewhere.
- Do not implement internal `Dispatchers.Default`, `Dispatchers.IO.limitedParallelism`, pre-use coroutine barriers,
  `DispatchException` unwrap logic, Metrics Flow/Job/Scope/Channel lifecycle, configurable callback dispatcher,
  trampoline states, callback-entry watchdog/SLA or `byDispatchFailure`.
- Public State, Stats, diagnostics, dynamic Metrics, frame delivery, caller-owned copying, cache/repeat and backend
  fallback remain required product capabilities.

## Implementation watchpoints

- `AndroidCaptureOwner.kt` and `TargetPorts.kt` remain cohesive large single-authority files. Reassess only when Controller
  integration actually removes responsibilities.
- Narrow module-internal seams are accepted where Kotlin multifile composition makes file-private access impractical.
