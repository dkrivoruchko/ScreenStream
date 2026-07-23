# Screen Capture Engine — Implementation Tracker

## Authority and current gate

[`design/00-authority-index.md`](design/00-authority-index.md) is the activated authority router. Its routed `design/01–08` topic owners contain the normative product, architecture, platform, package and verification contracts. Read the router and the topic owners relevant to each task; this tracker owns only work coordination and current implementation state.

The accepted documents are current authority, not irrevocable final decisions. If implementation exposes a concrete platform conflict, disproportionate practical complexity or a materially simpler solution that preserves product intent, stop work at that boundary and present the evidence, trade-offs and proposed documentation change for explicit user approval. Continue under the existing authority until that change is approved.

Existing production code is evidence, not presumed-correct architecture. The current gate is Agreed implementation plan step 1; production editing opens only after user approval of its refactor map.

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

The goal is the fastest practical path to the complete accepted product without lowering correctness, reliability, maintainability, efficiency, verification or critical-review standards. Adjust local sequencing as implementation evidence reveals real dependencies or a simpler route. Root records those plan changes and continues; material course or authority changes require the user-agreement procedure.

### 1. Conformance inventory and refactor map — current

Use up to three bounded read-only agents in parallel over complementary inventory scopes: ownership/concurrency, product/platform/JNI/ABI/build anchors, and simplicity/performance/file decomposition. This is source mapping, not critical closure review.

The merged map classifies current code as retain, locally correct, move/split, replace, delete, or missing. It defines package/file ownership, large-file responsibility splits, preserved external anchors, dependency order, coherent implementation slices, useful parallel work and expected temporary noncompilation boundaries. Root presents the map and stops for user approval before production editing.

### 2. Foundation and first internal vertical slice

Establish the real foundational owners and execution boundaries, remove superseded authority as its replacement becomes connected, and implement the smallest truthful internal path from start through capture, Framework JPEG and storage/delivery to normal stop. Use disjoint dependency-ready parallel work and validate the resulting coherent causal boundary through the applicable verification and replicated-review procedure. This slice validates the architecture early but does not reduce the required final capability set.

### 3. Standalone capability completion

Complete every remaining routed domain obligation, including platform lifecycle, Metrics, rendering, Framework and Native/Auto JPEG, fallback, storage, pacing, delivery, observation, reconfiguration, failure and terminal behavior. Continue through local packets without user stops while accepted authority remains sufficient and course-health rules remain satisfied.

### 4. Standalone closure

Complete the standalone public facade/factory and its acceptance evidence. Restore compilation, apply the relevant verification matrix, remove obsolete paths, run replicated critical review over coherent standalone scenarios, repair and recheck every material finding, then stop for user review before standalone freeze.

### 5. Application integration

Integrate the frozen standalone library into application modes and wiring as a separate gate. Preserve required product variants and perform the approved integration verification without reopening library authority absent concrete evidence.

### 6. Final verification and efficiency closure

Complete the applicable [`design/08-verification.md`](design/08-verification.md) end-to-end scenarios, required product variants, repository-wide obsolete-path check, evidence-led CPU/GPU/memory work and final replicated critical review. Repair and recheck every material finding, then stop for user review before final freeze.

The mandatory user stops are the step-1 refactor map, standalone freeze readiness and final integration freeze readiness. Add a stop only for a material authority/course decision, repeated structural defect or blocker requiring user choice.
