# Screen Capture Engine — Current Implementation Tracker

## Authority

`design-next/01-authority-router.md` through `design-next/12-domain-delivery-observation.md` are the sole normative
implementation authority, in router order. `design-next/13-future-evolution.md` is nonnormative; the old `design/01`–`09`
package is not authority. Kotlin is implementation evidence, not authority.

This tracker contains working rules, accepted implementation evidence, current blockers and restart-safe handoffs. It is
not normative. If it conflicts with Docs01–12, root corrects it before more work. Every other Markdown file is frozen unless
the user explicitly authorizes a bounded documentation task.

## Permanent working rules

- Root is exclusively the orchestrator and sole tracker/status/gate owner. Root does not inspect or implement production
  code, perform technical audits/research or run builds unless the user explicitly asks for a bounded root-only check.
  Detailed document/code work belongs to bounded subagents; root retains compact evidence, decisions, disagreements and gates.
- At most three direct subagents run at once. They do not read this tracker, use its procedure as their task contract, spawn/
  resume/message agents or delegate work. Root gives each an exact product packet, authority routes, invariants, file scope,
  negative manifest and required evidence. Long-running agents send a concise heartbeat.
- Three independent axes precede elevated architecture, product, concurrency, graphics, cleanup/publication or JNI decisions.
  Root consolidates them and returns critical disagreements/writer boundaries for cross-review. A material unresolved
  disagreement uses a fresh adjudicator. Fresh agents are required for independent axes, broad reopening and adjudication;
  reuse is preferred for a writer correction or exact-context continuation.
- Root never interrupts, cancels, replaces or duplicates an active agent without direct user permission. If capacity is
  unavailable, root reports it truthfully and continues only with work that is actually active.
- Agents use current Docs authority and current source. Full Docs01–12 reading is reserved for genuine architecture reopening
  or cross-document adjudication. Technical mechanism checks use official primary Android/AOSP, Kotlin/JetBrains,
  Oracle/OpenJDK/JNI or Khronos guidance. Android CLI is first for Android-specific coverage when stronger, IDE semantic
  tooling second, and raw terminal/Gradle only for unsupported checks. Frozen Docs remain product authority.
- Findings are classified as intrinsic defect, intentional migration state or named future-owned gap and include exact
  evidence plus a concrete correction. Product/platform/concurrency/ownership/safety/performance ambiguity stops for the
  user. Secondary private structure may use the simplest sound recorded choice.
- Product behavior is the stable approval boundary. Technical architecture, decomposition and mechanism in Docs are
  best-effort hypotheses, not implementation facts. Implementation evidence may reopen them, but divergence is cross-reviewed
  and agreed with the user before code relies on it.
- Normative Docs are strict about product behavior, semantic authority, ownership, boundaries, critical partial orders,
  platform/binary contracts and verification obligations. They are not a complete implementation design and do not freeze
  transient algorithms, private class/file/type shapes, migration mechanics or exhaustive call sequences.
- The goal is a finished correct product: observable behavior, platform correctness, concurrency, graphics, ownership/
  lifetime, cleanup/failure/JNI safety, privacy/security and appropriate efficiency, followed by maintainable idiomatic Kotlin.
  Formal guarantees or machinery that cost disproportionately to their product value are weakened or removed explicitly.
- Root does not begin a materially different slice until the user agrees the next action. After each implementation stage,
  one bounded next-stage revalidation uses the interfaces actually produced; material architecture change reopens three axes.
- Preserve unrelated work. Root and agents never stage, unstage, commit, reset, checkout, revert, mutate history/index or run
  hash procedures without direct user instruction. The user owns Git. Tests, builds, runtime/device/emulator and performance
  work are entirely out of scope until separately authorized.
- One writer owns every affected file; overlapping writers are forbidden. Accepted Kotlin is a checkpoint, not frozen code.
  Current code may be refactored, split, merged, replaced or deleted when that better satisfies authority and whole-design fit.
- Foundation constructibility/reachability is a hard gate. Every executable API, mutable occurrence, cell, ticket or receipt
  graph must have in the same accepted slice an authoritative root, direct producer/consumer, submission/entry/return
  arbitration, exact claim and terminal transfer/reduction. If a link is future-owned, land only permanent immutable vocabulary
  or defer it. No unreachable scaffold may close as a foundation.
- Semantic authority does not imply physical co-location. Canonical owners may use cohesive private collaborators without a
  second gate, lane, writer, lifecycle/currentness/terminal authority, ownership root, state machine or commit path. Reject
  mega-owners, forwarding shells over mega-owners, mixed authority+mechanics+cleanup units, generic manager/registry/event-bus
  layers, and also file-per-method/mechanical fragmentation or `utils/common` dumps.
- File size is a qualitative review signal, not a numeric acceptance target. The user's rough expectation is normally a few
  hundred lines and only exceptionally around/above a thousand with architectural justification. Optimize for cohesive
  responsibility, readable visibility/dependency direction and official Kotlin/Android best practices, never line arithmetic.
- Every subagent performs two self-review passes. Verification is proportional to changed risk. Elevated design, GL,
  concurrency/lock/publication, cleanup/fatal and Native/JNI closure uses three independent axes and one consolidated review;
  a narrow mechanical correction may receive a user-approved narrower recheck. Unchanged evidence is not repeatedly audited.
- Production Kotlin must be direct, typed, compact and idiomatic: immutable facts, narrow visibility, explicit ownership,
  checked identities/sizes/limits, bounded work, minimal copies and exact cleanup. Avoid `Any`, fake health/receipts, raw
  handles as ownership proof, duplicate authority/topology, compatibility layers and predictive accounting.
- Keep this tracker current, compact and nonarchival. Retain rules, accepted baseline, open decisions and next gate; delete
  writer/auditor chronology, hashes, line pins, superseded findings and repeated verification narration. Before compaction or
  restart, root records the complete handoff here and rereads it.

## Accepted baseline

- The final de-prescribed Docs01–12 are explicitly user-approved/staged and three-axis closure-PASS. Doc02 product authority
  is unchanged. Product math/outcomes, semantic owners/gates/lanes/currentness, ownership/receipt/terminal/cleanup, platform
  calls, security, JNI/ABI/wire/build/source-set anchors and verification scenarios/races/oracles remain hard.
- Accepted Runtime Spine, Control, RF1 and passive-RF2 checkpoints are valuable implementation evidence, not immunity from
  refactoring and not proof of a complete product.
- Reusable kernels with strong fit evidence include public immutable models; settlement occurrence/deadline/fatal concepts;
  isolated endpoint mechanics; Android lane semantics; pure reconciliation/pacing/observation calculations; GL/EGL mechanics;
  Native/Framework JPEG, storage and JNI/C++ contracts. Their physical owners may still require cohesive refactoring.
- RF1 retained Target seal/quiescence/reopen and retained GL actual/currentness/compensation semantics are closure-PASS.
  Passive RF2 accepted only Android actual VD tuple/callback currentness/create-release ownership, minimal detach/attach evidence
  and passive facet vocabulary; its unreachable executable Android mutation overreach was removed.
- The later W-TARGET edits in `TargetContracts.kt`, `TargetPorts.kt` and `CurrentTarget.kt` are unreachable, **REOPEN and
  unaccepted**. They directly inspect `OperationOccurrence` and mint Target-side post facts, conflicting with the approved
  Android-owned typed-outcome boundary. They must be deleted/replaced without an adapter after architecture/migration freeze.

## Current implementation reality

- The engine is not a finished reachable product: production does not yet expose a complete public engine/session facade,
  `SessionController` is not a live end-to-end steady-state engine, and production GL→JPEG→Storage→Delivery integration is
  absent. Delivery/Pacing/admission foundations are not proof of a working frame path.
- `SessionController` is the correct sole Session authority in concept but physically mixes lifecycle/currentness/commit,
  Control mechanics, startup, reconciliation inputs, topology installation, leaf result interpretation, publication, terminal
  arbitration and detailed domain cleanup. Existing `Session*Facet` files are largely mutable bags, not closed collaborators.
- The old active reconfiguration path is invalid for the approved architecture: it broadly retires compatible ownership,
  may construct a replacement JPEG runtime, does not implement the same-VD independent detach/attach graphs and must be
  replaced rather than moved behind a facade.
- Controller-side leaf cleanup reads domain operation/evidence/owner-bag internals. Cleanup authority is split between
  Controller, facets and `CleanupOwner`; late returns and domain suffixes must become leaf-owned typed progress.
- `DeliveryEndpoint` duplicates executor/prestart/submission/poison/shutdown/termination mechanics already represented by
  Runtime Spine. Delivery-specific handoff/callback/borrowed-lease settlement is distinct and must be preserved when its
  duplicate endpoint lifecycle is replaced in the first live Delivery slice.
- Other concentration hotspots include Control scheduling/deadline composition, Metrics/Android/Target physical owners,
  `JpegRuntimeOwner`, `EncodedStorageOwner` and `DeliveryOwner`. Size alone is not the defect; mixed authority, mechanics,
  settlement, cleanup and action construction is.

## Candidate target architecture — cross-reviewed, awaiting adjudication

- The governing strategy is **coherence-first, reuse-where-fit**. The end state is one coherent design, not adapters or
  patchwork around legacy pieces. Retain/refine code only when authority, lifecycle and cohesion fit the whole; otherwise
  locally rewrite it. Whole-engine rewrite is nondefault, but no bounded component is protected by sunk cost or prior closure.
- `SessionController` becomes a thin sole-`sessionGate` decision/commit shell: public-command linearization, lifecycle/
  currentness/terminal winner, cross-partition decisions, exact active→cleanup transfer and sealing of immutable effects.
  It does not contain scheduler, platform, leaf settlement, cleanup suffix or publication mechanics.
- Gate-confined lifecycle/topology/reconfiguration/production/delivery/Stats/cleanup partitions expose closed transitions
  under the same `sessionGate`. They have no gate, lane, signal, outward work, independent policy or commit path.
- Control owns its scheduler/drainer/wake/fatal/termination mechanics and returns typed facts. Metrics, Android, Target, GL,
  JPEG, Storage and Delivery own physical resources, outward calls, settlement and local cleanup suffixes. Pure calculations
  remain pure. A thin public facade delegates to the reachable Session aggregate without owning policy.
- Target prepares its port/provenance/evidence boundary; Android precreates and roots the exact occurrence/ticket/result graph,
  owns `RetiredUnused | DefinitelyUnentered | PostExposed`, and returns the typed outcome plus exact platform result. Target
  never reads Android occurrence/ticket/Handler internals. Replacement uses independent old `setSurface(null)` detach and new
  `setSurface(surface)` producer graphs on the same VirtualDisplay.
- One `Retained | Replacement` Session reconfiguration correlation seals Production and new Delivery, holds only Session-level
  phase/currentness/progress and commits once after typed leaf facts. One Session JPEG runtime/endpoint remains. Terminal moves
  each unresolved leaf graph intact and never fabricates reopen or cleanup evidence.
- Cleanup is one typed forest/quarantine model. Controller owns cutoff/transfer/matching reduction commit; leaf domains own
  serial suffixes and return immutable progress. Control remains receive-only last and terminates only after non-Control
  receipts and every external late-fact dependency settle.
- Package direction remains shallow and domain-oriented (`session/controller`, `control`, `settlement`, `metrics`, `android`,
  `target`, `gl`, `jpeg`, `storage`, `delivery`, `cleanup` or semantic equivalents). Exact private files/classes are not frozen;
  a reviewed responsibility map must prove canonical owners remain thin and internal units cohesive.

## Preserve / refactor / replace / delete

- **Keep/refine:** public immutable models; settlement/fatal/runtime kernels; Android lane; pure reconciliation/pacing/
  observation; GL/EGL mechanics; Native/Framework JPEG, storage and JNI/C++ platform contracts.
- **Refactor substantially:** Control composition; Metrics/Android/Target physical cohesion; GL/JPEG canonical owners where
  mechanics concentrate; Storage internals while preserving `EncodedStorageOwner$NativeSegmentSink` binary identity;
  Delivery owner/handoff composition; typed cleanup forest and publication construction.
- **Replace slice-by-slice:** current `SessionController` implementation while retaining its authority identity; mutable facet
  bags; incomplete retained-only reconfiguration/admissions; W-TARGET; Controller-owned leaf cleanup; duplicate Delivery
  endpoint lifecycle.
- **Delete in the same vertical cutover:** current invalid replacement-JPEG/reconfiguration helpers and state, staged Target APIs
  and direct Android evidence imports, duplicate Delivery executor protocol, adapters/feature flags/alternate planners,
  duplicate state machines and unreachable scaffold.

## Migration invariants and provisional order

- Freeze semantic owners, authority, dependency direction, typed boundaries, lifecycle/terminal rules and architecture-quality
  criteria—not exact private Kotlin layout or migration choreography.
- Use vertical replacement slices. Every new command/fact has one producer and consumer, is reachable in that slice, carries
  exact terminal/late-return cleanup, and deletes the displaced path in the same checkpoint. No prolonged dual authority,
  compatibility adapter or future-owned executable foundation may close.
- A behavior-preserving structural containment checkpoint is legal only when the extracted code is immediately used by the
  current/same-slice path and the old body is deleted. No broad facet/action/cleanup framework precedes its consumer.
- Cross-review agrees that Replacement precedes Production; every slice closes its own terminal residue; Delivery runtime
  consolidation occurs only in the first live Delivery slice; final terminal work connects already-closed suffixes and proves
  whole-forest Control-last. Remaining post-product decomposition must be nonsemantic only.
- Provisional sequence: architecture freeze → disputed reachability/Retained ordering → Replacement → Production → Delivery/
  public integration → full cleanup/Control-last convergence → remaining nonsemantic cohesion.

## Open adjudication and exact post-restart procedure

- All pre-restart agents are complete and their contexts are disposable. No writer is authorized. A fresh adjudicator is the
  first action after restart and reads current Docs/source, not this tracker.
- The adjudicator must resolve one material sequencing disagreement:
  1. minimal public/runtime reachability spine before Retained, because the current aggregate has no production construction;
  2. or Retained first, with public reachability only after Production/Delivery, to avoid a facade over an incomplete engine.
  It must distinguish a real constructibility root from premature public product exposure.
- It must also validate the optional narrow live-Control containment exception, Delivery Runtime-Spine consolidation,
  exact slice order, preserve/refactor/replace/delete ledger and exclusive writer boundaries.
- Before any writer, three bounded migration views then cover:
  A. reachability and slice dependency order;
  B. package/file/writer topology plus official Kotlin/JetBrains and Android/AOSP best-practice fit;
  C. constructibility, same-slice deletion, terminal closure and proportional source verification.
  The adjudicator decides whether those axes review one complete roadmap or the exact first-slice packet. Cross-review and a
  fresh adjudicator resolve any remaining material disagreement. Root shows the final roadmap to the user before writers.
- Tests/builds/runtime/Git remain outside this planning. Android CLI is used first where it gives stronger Android coverage;
  the known sandbox bundle-lock failure is noted once and official source/guidance is the fallback.

## Architecture-quality gate

- A projected package/file responsibility map is mandatory before implementation. It covers every large domain, not only
  Controller, and proves canonical owners are thin semantic anchors rather than forwarding shells over hidden mega-owners.
- File size is qualitative: normally a few hundred lines; around/above a thousand is exceptional and requires architectural
  justification. This is not a line budget. Review responsibility count, lifecycle/protocol cohesion, visibility, dependency
  direction and platform ownership. Reject both mega-files and mechanical fragmentation.
- The dedicated topology axis checks official Kotlin composition/visibility/package guidance and Android/AOSP resource,
  Handler/VirtualDisplay/currentness guidance. Exact JNI/ABI/build anchors override private layout freedom where required.
- Parallel writing is legal only after typed contracts and exclusive file ownership freeze. One integration writer owns each
  cross-domain occurrence/cutover/deletion set; disjoint leaf writers may run in parallel only when they do not change shared
  boundary types, Controller call sites, lifecycle/cleanup contracts or the same semantic owner.

## Compact product handoff anchors

- One active Session owns exactly six runtime threads: Control, Metrics, Android HandlerThread, GL, JPEG and Delivery.
  Main/provider/collector/native callback threads are non-owned. Metrics/GL/JPEG/Delivery use separate bounded serial
  endpoints; Control and Android use their specialized scheduler/post protocols.
- Exact raw fatal authority, entry/return separation, poison after acceptance ambiguity, one orderly shutdown and real
  termination receipts remain mandatory. Control is receive-only last; terminal transfer never fabricates physical cleanup.
- Metrics joint readiness requires a timely first positive and adopted normal handle; Latest is not readiness authority.
  Android owns the single VirtualDisplay actual tuple and callback currentness. Same-authority equal `W/H/D` is a no-op.
- Target uses one `targetGate`, exact identities/ports/leases/provenance and real producer/detach/release evidence. GL/JPEG/
  Storage retain exact platform/resource ownership and cleanup receipts. One JPEG runtime serves the Session.
- Delivery invokes the application callback on its isolated lane with one handoff, borrowed-frame checks and exact
  unsubscribe/callback settlement. Public State, Stats, diagnostics, dynamic Metrics, cache/repeat and fallback remain required.
- Preserve stop-before-start zero-runtime semantics: OwnerStop, default requested parameters, null effective parameters, no
  desired revision and no lane construction.

## Immediate watchpoints

- Do not extend, relocate or wrap the current invalid active-reconfiguration path. Replace it through the adjudicated vertical
  cutover; do not accept W-TARGET, hide `advanceReconfigurationBoundary` behind a helper or construct a replacement JPEG runtime.
- Do not expose a public facade merely to make unreachable internals appear complete; adjudication must define the minimum
  reachability root and its truthful observable contract.
- Do not connect Delivery as-is; its duplicate endpoint lifecycle must be replaced only with a live handoff consumer and
  without changing Delivery-specific settlement.
- Do not defer terminal/cleanup for a newly introduced owner or occurrence to a later global phase.
- Do not use file size, previous closure or sunk effort as a substitute for whole-design fit.
