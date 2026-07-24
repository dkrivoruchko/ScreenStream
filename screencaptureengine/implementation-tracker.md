# Screen Capture Engine — Implementation Tracker

## Authority and current gate

[`design/00-authority-index.md`](design/00-authority-index.md) is the activated authority router. Its routed `design/01–08` topic owners contain the normative product, architecture, platform, package and verification contracts. Read the router and the topic owners relevant to each task; this tracker owns only work coordination and current implementation state.

The accepted documents are current authority, not irrevocable final decisions. If implementation exposes a concrete platform conflict, disproportionate practical complexity or a materially simpler solution that preserves product intent, stop work at that boundary and present the evidence, trade-offs and proposed documentation change for explicit user approval. Continue under the existing authority until that change is approved.

Existing production code is evidence, not presumed-correct architecture. The refactor map and foundation are consumed; Native/Auto JPEG and API 32–37 Downscaled Target are source-review closed. The accepted V1 decision is no automatic Target-mode health downgrade or Downscaled-to-Full fallback. This closed source boundary is the current intermediate commit boundary; the next production gate is the pacing/cache packet recorded in the final plan section. Tests, test sources, builds, runtime/device work and `build.gradle.kts` remain explicitly deferred; their accepted final obligations remain recorded but do not gate later Kotlin/C++ source progress.

## Working model

### Coordination and ownership

- Root is the sole orchestrator and sole owner/editor of this tracker. Subagents receive bounded assignments without tracker access.
- Because subagents do not read this tracker, Root includes the relevant authority route, quality standard, Kotlin/testability rules, three-pass self-review and completion-report requirements explicitly in every writer assignment; reviewers receive the corresponding applicable acceptance rules.
- Root delegates production changes and may inspect production code or diffs read-only when needed for accurate orchestration, evidence reconciliation, scope control or a freeze decision.
- Use up to three direct subagents concurrently. Prefer useful independent dependency-ready work with disjoint writes; an unused slot is acceptable when no such work exists.
- Each file has one concurrent writer within an active packet. Transfer ownership only after that writer completes, and preserve unrelated user work.
- Reuse agents for repairs and closely adjacent work when their exact context improves speed or accuracy. Use reviewers who did not write the reviewed boundary for its independent closure review.
- Root changes, interrupts, replaces or duplicates an active assignment only after direct user permission.
- Treat packets and stages as flexible coordination boundaries. Merge, split, reorder or move work when dependencies and cohesion make the implementation simpler and more complete.

### Quality standard

- Every assignment requires the simplest natural correct solution: functionally and architecturally correct, reliable, stable, efficient, understandable, maintainable and proportional to a high-quality ordinary production Android library.
- Every writer and implementation reviewer personally checks the current official guidance and established best practices relevant to the exact APIs, language/runtime mechanisms and platform behavior touched by the task. Accepted architecture and shared source notes provide direction but do not replace this local implementation-level verification.
- Route that verification narrowly through the applicable current Android/AOSP, Kotlin/JetBrains, JDK/JMM/JNI, Khronos or other owning-platform primary sources. Record the material sources actually used and justify any necessary departure; reuse an accepted source anchor only after confirming that its claim remains current and applicable.
- Prefer the smallest standard platform/language mechanism that satisfies the accepted contracts and realistic Android lifecycle, failure and resource conditions. Use fixed typed seams and cohesive direct collaborators, and judge file size by responsibility and cohesion.
- Treat CPU, GPU, memory, allocations, copies, wakes, blocking and latency as implementation acceptance concerns. Optimize within the accepted architecture using evidence.
- Known recurring failure modes remain explicit guardrails: generic registry/event-bus/plugin infrastructure, compatibility shells, duplicate lifecycle/currentness/terminal authority, Boolean proof state, cleanup forests and synchronous cross-role waits require rejection unless normative authority explicitly requires them.
- Every subagent performs three task-appropriate self-review passes before reporting: correctness/completeness, simplicity/scope, and evidence/verification.
- Every writer completion report states: what is genuinely connected, what superseded path was removed, what evidence was checked, and what remains open.

### Kotlin implementation style

- Choose concise, conventional, responsibility-revealing names for packages, files, types, functions and values. Documentation terminology guides semantics but does not require awkward internal names.
- Place constants, functions and private types with their clear semantic owner. Use a top-level declaration only when no natural owner exists and top-level scope accurately represents its responsibility.
- Prefer compact idiomatic Kotlin with cohesive control flow. Keep single-use logic inline when that remains clearer; extract a function when the name or boundary materially improves understanding, isolates an invariant or platform/resource concern, enables focused testing, or removes meaningful repeated code.
- Keep tightly related private declarations near their owner, including in the same file when cohesion remains clear. Prefer small obvious local duplication over an abstraction that has neither a real contract nor meaningful reuse.
- Actively use relevant stable features available in the project-pinned Kotlin toolchain and, where the authority permits coroutine use, the project-pinned current stable `kotlinx.coroutines`. Adopt a feature when it improves clarity, correctness, lifecycle/cancellation handling, efficiency or removes boilerplate without hiding ownership or control flow.
- Use comments and KDoc to explain contracts, reasons, invariants and platform quirks rather than narrating evident code. Keep extension functions dependency-transparent and use Kotlin concision without dense or clever constructs that make control flow harder to follow.
- Preserve the fixed typed contracts and ownership clarity required by the authority while minimizing wrappers, forwarding layers and fragmented one-line helpers.
- Prepare production code for practical unit and integration testing even when tests are scheduled for a later wave. Keep deterministic decisions independently exercisable where natural, make time/thread/platform effects controllable through real component seams, and avoid hidden global dependencies. Use testability to reinforce genuine responsibilities rather than to create production abstractions with no other value.

### Milestones, tools and course control

- Intermediate noncompilation is acceptable during an approved coherent refactor without an artificial packet-count or time limit. Freeze a milestone only when its intended source boundary compiles and its applicable verification is complete.
- A frozen milestone is wired into the production path: mutable state has a real owner, facts have real producers and consumers, terminal handling is reachable, and the superseded authority/path is removed.
- Use JetBrains MCP/IDE tooling for navigation, references, file visibility and diagnostics when available and useful; otherwise continue with normal repository tools.
- Builds, tests, runtime/device/emulator work, performance measurement, Git/index/history changes and destructive cleanup begin with the applicable approved implementation step or explicit user instruction.
- Stop for user agreement when work requires a material course change, a new product decision or a normative-documentation change.
- When bounded repair and recheck repeatedly expose the same causal defect or require duplicate authority, pause the patch chain and reassess the local design.
- Keep this tracker restart-safe and current with active authority, working rules, accepted decisions, current gate and exact next procedure.

### Tracker maintenance and recovery

- Root alone integrates reports and edits this tracker. Update it when a user decision, current gate, milestone state, material blocker or exact next procedure changes.
- Record only accepted working rules, current decision-grade results, active status and next action. Put necessary detailed evidence in the code or a dedicated durable artifact referenced here.
- Replace superseded entries in the same update. Keep chronology, raw agent transcripts, temporary findings and closed plans out of the tracker.
- Before releasing completed agent context or a planned full Codex restart, integrate every material result that affects the active gate into this tracker or its referenced durable artifact, reread the written result, and record any still-pending work. Routine local findings already embodied and verified in code need no separate history entry.
- After root-context compaction, reread this tracker completely, inspect available agent statuses and messages, collect results that completed around compaction, reconcile them with the recorded state, and only then continue from the exact current gate.
- Normative product and architecture decisions belong to the routed `design/00–08` topic owner after user approval. This tracker records their operational consequence and link rather than duplicating the contract.
- Keep the Agreed implementation plan as the final tracker section and update its current step, accepted results, material blockers and exact next action in place as work advances.

### Replicated critical review

Self-review is an agent checking its own work; independent confidence comes from three different reviewers checking the same bounded causal boundary.

Critical implementation risk uses this checklist:

1. product and observable behavior: public API, lifecycle, State/Stats, fallback, pacing, reconfiguration and externally observable results;
2. state authority and concurrency: sole authority, gates, execution roles, currentness, linearization, identities, coalescing, stale/late results, blocking and progress;
3. physical ownership and terminal cleanup: Android, Surface, EGL/GLES, buffers, Bitmap, carriers, native memory, transfer, return, quarantine and terminal release;
4. external and binary boundaries: Delivery callbacks, subscriber arbitration, JNI/ABI/wire/build anchors, failure containment, privacy and security;
5. hot-path efficiency: CPU/GPU work, full-frame copies, allocations, memory bounds, queues, wakes, blocking, backpressure and driver interaction;
6. structural quality: simplicity, component responsibility, cohesion, maintainability, testability and absence of duplicate authority.

For each coherent critical boundary:

1. Root defines one bounded causal scenario, routes only its relevant normative sections, owners/producers/consumers/terminal path, material official-source claims, and marks the applicable checklist groups.
2. One fresh trio that did not write the boundary independently reviews the same scenario, authority scope, code slice, primary question and applicable checklist. The checklist is one shared review surface, not six separate waves.
3. Reuse that trio for two or three closely related scenario slices while its context remains reliable. If context pressure would reduce quality, finish the current scenario and start the next coherent slice with a fresh trio.
4. Root deduplicates the reports. Three PASS verdicts close the scenario; consistent material findings proceed to repair; only unique or conflicting material claims return to the trio for targeted evidence cross-check.
5. A fresh neutral adjudicator resolves only material disagreement that remains after targeted cross-check. A separate adversarial reviewer is reserved for a concrete exceptional risk such as a novel mechanism, repeated defect class or unusually fragile final boundary.
6. The same trio performs focused rechecks over repaired findings, the changed causal slice and its immediate regression surface. Repeat the full review only when the repair materially expands or redesigns the boundary.

Large boundaries are divided by end-to-end causal scenarios rather than arbitrary files or checklist directions. Ordinary noncritical defects use the writer and a bounded focused recheck. Every review has a defined question, evidence scope and stopping condition.

## Agreed implementation plan

The goal is the fastest practical path to the complete accepted product without lowering correctness, reliability, maintainability, efficiency, verification or critical-review standards. Local packet boundaries may change when dependencies or implementation evidence make a different split simpler; material authority or product changes still require user agreement.

### 1. Conformance inventory and refactor map — approved and consumed

The generic legacy ownership graph has been replaced by the direct typed architecture: one public facade, one Front Door and `sessionGate`, finite Bootstrap, Control/Capture Handler lanes, separate Metrics/JPEG/Delivery permits, four permanent capsules, one Control-owned `FrameStore`, and no compatibility bridge to the removed graph.

Fixed external anchors remain: public package/API identity and explicit API mode; SDK 24–37 and JVM 17; Native source paths, NDK/ABI/DSO/JNI descriptors and order, 16-byte result wire, frozen sink upcall, exports and alignment; empty manifest and shrinker boundary. Authority-08 compilation, runtime, ABI, package and artifact evidence remains deferred rather than satisfied.

### 2. Foundation and first vertical slice — source-review closed

The connected production path covers facade/start, Metrics readiness, Full + Direct capture, retained EGL rendering, Framework JPEG, segmented immutable storage, fresh Delivery, State/Stats/diagnostics, reconfiguration fencing, terminal ownership transfer and normal stop. The superseded Kotlin graph is removed. The Display-P3 transform is one fused shader pass with `highp` preferred and `mediump` best effort.

The foundation causal review closed with three `PASS` verdicts. Source-only IDE diagnostics over all 47 production Kotlin files reported zero errors and zero timeouts. This is source evidence only, not build, runtime or package proof.

### 3. Standalone capability completion — in progress

Source-review-closed packets:

- Native/Auto JPEG: one legal carrier/backend product, Native compression without the Framework Bitmap copy, exact JNI/wire/adoption boundaries, Session-wide Native health, later Framework reconciliation and exact stale/terminal ownership.
- API 32–37 Downscaled Target: exact Full/Downscaled planner, logical display versus physical Target separation, provisional API 34+ Full, Target retention/replacement, truthful mode observation, whole-production drain and revision-safe reconciliation.

The accepted V1 decision is no automatic Target-mode health downgrade or Downscaled-to-Full fallback. A Target denial follows the existing `Suspended` or terminal failure contract; a later revision selects Target mode only from its current eligibility. This decision is owned by Product, Architecture and Capture authority and requires no production rollback path.

Remaining source work, in order:

1. Pacing/cache packet: cache unchanged plan and Metrics decisions, remove incidental Control-turn allocations, and complete MaxFps, repeat and cached-first behavior with truthful Stats/diagnostics and backpressure.
2. Broader reconciliation packet: finish parameter/geometry/visibility/subscription changes, stale/late result handling, recoverable suspension/resume and terminal races not already closed by the existing packets.
3. Standalone source closure: complete any remaining public-facade/factory wiring, remove obsolete production paths, run source diagnostics and replicated critical review over the complete standalone causal surface.

Exact next procedure after the intermediate commit is to reread this tracker and the authority router, inventory the pacing/cache producers, owners and consumers in Product `01`, Architecture `02` and Delivery/Observation `07`, then assign disjoint Kotlin production ownership. Continue to ignore tests, test sources, Gradle/CMake, builds, runtime/device/package/performance and Git unless the user explicitly reopens them.

### 4. Standalone closure

After source capability completion, close the standalone public boundary and its source-only acceptance evidence, then stop for user review. Compilation, tests, runtime and artifact evidence remain deferred under the current constraint.

### 5. Application integration

Integrate the source-closed standalone library into application modes and wiring as a separate gate while preserving product variants. Perform only the source work and review allowed by the active user constraint.

### 6. Final source and efficiency closure

Complete the source-applicable parts of Authority-08, the repository obsolete-path check, evidence-led CPU/GPU/memory review and final replicated critical review. Build, test, runtime, ABI, AAR and package proof remains an explicit deferred gap until the user reopens it.

The next mandatory stop is standalone source-freeze readiness; later stops remain application-integration readiness and final source closure. Add a stop only for a material authority/course decision, repeated structural defect or blocker requiring user choice.
