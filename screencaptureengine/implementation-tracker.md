# Screen Capture Engine — Current Implementation Tracker

## Authority

The normative implementation authority is, in order:

1. design-next/01-authority-router.md
2. design-next/02-product-contract.md
3. design-next/03-shared-runtime.md
4. design-next/04-verification.md
5. design-next/05-domain-controller-reconciliation.md
6. design-next/06-domain-android-capture-metrics.md
7. design-next/07-domain-target.md
8. design-next/08-domain-gl.md
9. design-next/09-domain-framework-jpeg.md
10. design-next/10-domain-native-jpeg.md
11. design-next/11-domain-encoded-storage.md
12. design-next/12-domain-delivery-observation.md

`design-next/13-future-evolution.md` is nonnormative product-roadmap material. The previous `design/01`–`09` package is
not implementation authority and must not be loaded as a requirement source. Kotlin and Git history are implementation
evidence. Documents 01–12 are the primary best-effort design reference for the intended final product, not uniformly
absolute predictions of the eventual Kotlin implementation. If code cannot satisfy a critical product/platform/safety/
ownership/performance requirement, stop and report a designer/user blocker. A necessary secondary structural deviation
is recorded and reported rather than hidden, but does not require a complex workaround or frozen-document edit. Loading
all twelve documents into every agent's immediate context is not required when the router defines a smaller sufficient
authority packet.

Every Markdown file except this tracker is frozen at its current content. No agent may edit frozen documentation. An
implementation conflict, ambiguity, difficulty, deviation, or improvement proposal is reported to the user with exact
authority and implementation evidence. Critical product/platform/ownership/safety/performance uncertainty stops for
designer/user resolution. Secondary internal structure, visibility, naming, file-layout, or mechanism differences are
recorded and may proceed with the simplest sound implementation; neither case authorizes an implicit documentation edit.

## Permanent working rules

- Root is the exclusive tracker writer, status owner, and gate integrator. Root personally reads, audits, and updates
  this tracker. Technical implementation, research, code audit, diagnostics, production builds, and production analysis
  are delegated to at most three direct fresh subagents unless the user explicitly requests a bounded root-only check.
  Nested subagent delegation is prohibited.
- Fresh subagents are the default for every new slice, independent audit axis, solution proposal, and adjudication.
  Reuse is allowed for a bounded continuation of the same assigned work, including the same three independent agents
  cross-reviewing their deduplicated finding/solution set, or a small follow-up fix when the exact issue and accepted
  solution are already known and the agent's immediately prior context materially reduces risk. A new independent
  review, broad reopening, materially different task, or disagreement adjudication always uses a fresh agent.
- Subagents never edit this tracker, announce closure, or delegate. Only root writes tracker state and integrates gates.
- Every future subagent running longer than roughly two to three minutes sends root a concise progress heartbeat at
  that cadence: completed reading/work, current phase, any finding/blocker, and remaining bounded work. Heartbeats do
  not pause the task, request a restart, expand scope, or substitute for the final evidence report.
- Root never interrupts, cancels, or replaces an active subagent without the user's direct explicit permission. Long or
  complex work is not evidence of a hang. Root waits or provides noninterrupting assistance and never starts a duplicate
  replacement while the original task can still complete. Once interrupted, never resume it unless the user explicitly
  orders that exact resume.
- Preserve unrelated work. Do not stage, unstage, commit, reset, checkout, revert, or otherwise change index/history
  without explicit user instruction. The user exclusively owns staging and commits.
- Every technical subagent first checks current official primary guidance and applicable current platform/tool best
  practices. Android uses official Android/AOSP sources; Kotlin/IDE uses official Kotlin/JetBrains; Java/JMM/JNI uses
  official Oracle/OpenJDK/JNI; OpenGL/EGL uses official Khronos and Android/AOSP sources. Record exact sources, relevant
  conclusions, and disposition in every handoff.
- Authority loading is proportional to task type, scope, and risk. Root gives each agent a minimum sufficient authority
  packet: current tracker state and exact pins, approved task/fix contract, directly governing sections, affected code
  and boundary call sites, required invariants, and relevant official primary sources. Full Documents 01–12 reading is
  the default for design, architecture, cross-document reconciliation, and authority adjudication. Selective loading
  never permits an unsupported conformance claim or silently ignoring a relevant requirement.
- The finished working product is the objective. Documents 01–12 describe that product and a best-effort route toward a
  correct implementation, but design-time predictions have unequal importance and incomplete implementation knowledge.
  Root may change intermediate order, supporting declaration placement, exact internal mechanism, or bounded
  cross-domain sequencing when the result is simpler and improves final correctness. Record and report every material
  deviation; never silently imply that the frozen text already said something different.
- Apply authority proportionally. Highest priority is correct public/observable behavior; reliable platform/system
  interaction; concurrency/JMM and thread/lane behavior; graphics/EGL/GLES correctness; ownership, resource lifetime,
  cleanup, failure and JNI/ABI safety; privacy/security; and appropriate memory, CPU, GPU, allocation and latency
  efficiency. Next is maximally simple, compact, maintainable and idiomatic Kotlin. Internal visibility, exact names,
  file count/location, class decomposition, and a design-proposed implementation mechanism are secondary.
- When a design instruction is hard to implement, determine whether it protects a critical product invariant or is
  secondary guidance. Critical uncertainty, contradiction, or unavoidable tradeoff stops for the user. For a secondary
  mismatch, choose the simplest technically sound implementation, record the exact deviation/reason and report it.
- Classify every finding before selecting a correction: intrinsic current defect; intentional temporary migration
  state; or integration-dependent gap owned by a named future Controller/GL/JPEG/Android/Delivery slice. Fix intrinsic
  stable defects. Record and defer future-owned gaps when the accepted next topology naturally supplies the missing
  path; do not add a temporary workaround that later integration will delete.
- Architectural authority contains current product, architecture, system, implementation-binding, verification, and
  explicitly nonnormative future-product content. Work orchestration, review procedure, migration history, tracker/Git/
  index instructions, and change history live only in this tracker or temporary handoffs.
- Keep this tracker operational rather than archival. Retain permanent rules, current action queue, exact accepted
  checkpoints, accepted invariants/deviations, open blockers and named future-owned obligations. Once a slice closes,
  delete intermediate hashes, writer/auditor chronology, superseded findings, manual-review states, severity counts and
  repeated verification narration after confirming no future task depends on them.
- Before releasing an agent whose report may be needed later, exhausting its context, or performing a user-requested
  root restart, root writes every material handoff, exact pin, unresolved disagreement, proposed solution and next gate
  into this tracker and rereads the saved section for completeness.
- Frozen documentation has no implementation-side writer. Only a new direct user decision may lift the freeze for a
  separately bounded documentation task.
- Accepted Kotlin is a high-confidence checkpoint, not frozen code. Bounded reopening, refactoring, extension, or
  rewrite is allowed when evidence requires it and Documents 01–12 remain satisfied.
- Acceptance follows bounded production source slices, not source-file completion. One writer owns each file at a time;
  overlapping writers never edit the same file concurrently.
- Final passive declarations may land before later behavior only when they have permanent domain ownership and no
  placeholder behavior, mutable authority, lifecycle transition, arbitration, platform call, fake health/presence
  projection, or incomplete execution path.
- Physical layout follows the authority source manifest, cohesion, and one-way dependency. Active implementation units
  normally target 250–500 lines; a cohesive state/resource machine may reach roughly 600–700 lines. More than 800 lines
  requires an explicit cohesion review, not automatic mechanical splitting.
- Every subagent performs at least two explicit self-review passes before handoff. Every writer additionally uses
  JetBrains/Android Studio formatting and diagnostics, a focused IDE build, proportional forced Gradle compilation, and
  a naming/spacing review. JetBrains/Android Studio MCP is used when directly available; otherwise note once and continue
  without blocking.
- Thorough review of critical or high-risk code uses three fresh independent read-only agents on distinct technical
  axes. Root deduplicates their reports; the same three cross-review material, complex, disputed, or writer-contract-
  changing findings and proposed corrections. Trivial aligned observations and mechanical inventory facts do not
  require three-way cross-review.
- High-risk includes platform and Android interaction; graphics/EGL/GLES/driver/image pipelines; concurrency, threading,
  scheduling, locks, atomics, publication, deadlines, cancellation, late return, nonreturn and resource lifetime; JNI/
  native/ABI boundaries; and complex stateful business logic.
- One independent axis in every high-risk review explicitly checks Documents 01–12 architecture and technical-decision
  conformance. Ambiguity, contradiction, missing decision, or disproportionate machinery is reported with exact
  evidence and bounded options. Critical uncertainty stops for the user; secondary structure uses the simplest recorded
  deviation. Frozen documentation is never silently reinterpreted.
- Implementation may start after cross-review when all three axes converge on both the problem and technical solution.
  Any material disagreement about the finding, scope, or solution requires a fresh adjudicator.
- Every finding needs exact authority/source/line evidence and at least one concrete solution. Severity labels are not
  required and do not replace agreement on the problem, impact, and solution.
- GL and every concurrency/lock/publication slice are elevated-risk. Planning, implementation and closure require three
  independent axes, mutual cross-review, fresh adjudication for disagreement, official primary authority and explicit
  gate/allocation/deadline/late-return/nonreturn/nontermination probes. A single clean report never closes such a slice.
- Production code uses Kotlin 2.4 and must be maximally simple, compact, readable and idiomatic. Prefer direct typed
  composition, immutable data, narrow visibility, explicit ownership and the smallest understandable control flow.
  Avoid cleverness, speculative abstractions, unnecessary layers, duplicated models and generic machinery. If the
  simplest conforming implementation remains convoluted, stop and escalate the design.
- After every writer handoff, independent audit waits for user manual review and explicit authorization unless the user
  directly selects a narrower bounded verification for an already cross-reviewed mechanical correction.
- Preserve checked sizes/narrowing, hard limits, exact identities, bounded work, reuse, minimal copies, exact cleanup
  and structural ownership. Do not use `Any`, fake health/presence booleans, compatibility interfaces, duplicate
  topology wrappers, raw handles as ownership proof, fabricated receipts, incomplete implementations, or predictive
  accounting.

## Repository checkpoint

- The user exclusively manages staging, commits, index and history; root and subagents never mutate them without a
  direct instruction.
- Target and GL source decomposition are accepted on the current worktree pins below. The user has manually reviewed and
  staged the current checkpoint and is ready to commit.
- The production-Kotlin source-decomposition barrier remains active for JPEG and Android. Functional implementation and
  integration remain paused until both close.
- Tests remain entirely out of scope.

## CURRENT ACTION QUEUE

1. **USER COMMIT — NEXT.** Root performs no staging, commit or history operation.
2. **JPEG — TOMORROW'S ACTIVE DOMAIN.** After the user commit and explicit continuation, repin current GL/JPEG seams,
   run three fresh final JPEG axes, mutually cross-review findings/solutions and adjudicate disagreement before any
   writer. Use the readiness plan below; do not cross unresolved native-evidence boundaries.
3. **ANDROID — AFTER JPEG.** Repin after prior-domain closure, run the mandated three axes and complete decomposition.
4. **POST-BARRIER INTEGRATION.** Resume Controller/Delivery and retained F1–F6 obligations only after all four domains
   close.

## Mandatory source-decomposition procedure

Status: `REQUIRED_BEFORE_FURTHER_IMPLEMENTATION`.

Current scope is production Kotlin only. Tests are entirely out of scope: agents do not inspect test sources, plan tests,
edit tests, run test tasks, map executable test IDs, or use test requirements to shape current work. Production
formatting, static analysis, compilation, two writer self-reviews and independent production-code audit are the complete
verification scope.

1. Work domain by domain in this order: Target, GL, JPEG, Android. Storage remains cohesive unless concrete review
   demonstrates that its current unit obstructs implementation.
2. Give one writer exclusive ownership of affected owner files for the whole domain move. Supporting import/package/
   constant edits may cross the current boundary for a compile-clean migration but do not start later behavior work.
3. Start with permanent immutable contracts, models, evidence, receipts and narrow commands. Do not predeclare
   speculative whole-engine models, placeholders, TODO behavior or fake-success paths.
4. Extract cohesive state/resource components, then reduce the owner to its sole-authority facade. Use composition and
   narrow typed boundaries, never extension files exposing mutable owner fields.
5. Preserve exactly one existing gate, lane, scope/view, scheduler, executor, HandlerThread/Handler, fatal fence and
   resource-lifetime state machine wherever authority assigns one. File boundaries never authorize another runtime axis.
6. Preserve exact root binary identities and native signatures of
   `io.screenstream.engine.internal.JpegRuntimeOwner$NativeBridge` and
   `io.screenstream.engine.internal.EncodedStorageOwner$NativeSegmentSink`.
7. Keep sealed families in a legal common Kotlin package, maintain one-way dependencies and move domain deadline
   constants without alias/duplicate; generic deadline machinery remains settlement-owned.
8. After each domain move, format, statically analyze and compile affected production Kotlin, perform two writer
   self-reviews and obtain independent read-only production-code audit before the next domain.

Exit criteria:

- Canonical responsibilities match Documents 01–12, subject to recorded secondary deviations that improve simplicity
  without weakening critical requirements.
- No affected file exceeds 800 lines without cohesion justification; typical units are 250–500 lines and a cohesive
  resource machine may remain roughly 600–700 lines.
- JNI identities, sole-owner authority, concurrency/lifetime/resource invariants and observable behavior are preserved.
- Target, GL, JPEG and Android production slices close compilation, static analysis, writer self-reviews and independent
  production-code audit.
- Only then may functional implementation and integration resume.

## Accepted Target checkpoint

Status: `CLOSED_SOURCE_DECOMPOSITION_ACCEPTED`.

| Target file | SHA-256 | Lines |
| --- | --- | ---: |
| `target/TargetContracts.kt` | `0e0048b10a0c5253c31e4500e2522559c5d1a42a64dc236d810e34e2314cca1e` | 171 |
| `target/TargetConstruction.kt` | `44d13a9d468ab7371929b612792fc542f639adc596aac4ff3e929498c92f720f` | 588 |
| `target/TargetPorts.kt` | `fc9daf71e4fb1d03fbebe0154c5aa9d5e33db212a9c2154255f76e7a24a392eb` | 755 |
| `target/CurrentTarget.kt` | `c79daef9d796f2ad25f3ddf749f6ce6878f8ec34265808e19a9a692b77035c9a` | 607 |
| `target/TargetRetirement.kt` | `65577fa36d756d52d6a284dbc53ee757526bc53b6f47743b86f87e2d60900ee5` | 606 |
| `target/TargetOwner.kt` | `7e0009af811e6378af40cbecc54fe51dc9d7cc7aec758ab6d68586c98851b5a7` | 202 |

Accepted invariants and deviations:

- Six cohesive files under `internal.target`; one small Session-facing `TargetOwner`; one shared nonfair `targetGate`;
  occurrence-local settlement gates only; no extra owner, lock, monitor, CAS authority, lane, scope, dispatcher,
  scheduler or runtime axis.
- One normative `latestPendingSource: AtomicBoolean`; zero Target `compareAndSet`/`synchronized`; held-gate-only
  readiness seams; unlocked outward/platform work and signalling after gate release.
- Direct `VirtualDisplayCreation` `OutOfMemoryError` releases its completed lease and rethrows the identical object;
  every other `Error` retains fatal lease semantics.
- Narrow module-internal seams are accepted where Kotlin multifile composition makes file-private access impractical.
- Exact JNI identities remain `JpegRuntimeOwner$NativeBridge` and `EncodedStorageOwner$NativeSegmentSink`.

Future Controller-owned watchpoint: concrete `SettlementSignal` is a precreated Session-owned lossless wake that commits
durable facts before signalling, runs unlocked, is prompt/nonblocking/allocation-free/non-throwing and uses exact
`IDLE/RUNNING/RUNNING_DIRTY` coalescing. Dispatch rejection/throw is captured by identity in the Controller emergency
fact and handled by its exclusive fail-closed turn. Do not add a Target-local catch, retry, wrapper, lane or scheduler.

## Accepted GL checkpoint

Status: `CLOSED_SOURCE_DECOMPOSITION_ACCEPTED`.

The user performed final formatting-only cleanup after technical closure. The table pins the current reviewed/staged
worktree bytes; staging remains user-owned.

| GL file | SHA-256 | Lines |
| --- | --- | ---: |
| `gl/GlPipelineContracts.kt` | `385112dd940e0b21e1ca76517f6cd9725c521b9ff866c846f53e8b6d382ac0f2` | 189 |
| `gl/GlLaneRuntime.kt` | `f7f8a96984ae0cd5f94d61fa09fdf5ec1e7125c482fdc79a47ee2f7d240dba5f` | 625 |
| `gl/EglSession.kt` | `298d9584427a38e9772286ad58a0f3dc67b06ba5bfa7a5b268544fe05f828372` | 771 |
| `gl/GlProgram.kt` | `87df44349231f1a0b9c5de4ec5600701fd8d07e5e59e81f30de3c06675e515fe` | 326 |
| `gl/GlTargetBridge.kt` | `424e40f26f6a6a8859656c5d8763eeb2f9a29f3962c9c806afa26889071734d8` | 230 |
| `gl/GlRenderTarget.kt` | `f062067f22dad6da41eaa4036d9a5a4b823981e2422b4180df4a8640333f22cb` | 239 |
| `gl/GlFramePipeline.kt` | `e187a84af32d9cefdf1806eb22e444da68939402a7e9eeb98dea3fa98b21be84` | 356 |
| `gl/GlCleanup.kt` | `e5e51c05783236ceea41302cff44acaa123fdeef7abe077fbaa7dbff57c20759` | 577 |
| `gl/GlPipelineOwner.kt` | `c282a70492f886264249322fcc51dc967dca6cb65d019ffcd3321f2cf62ac1bf` | 711 |

Accepted invariants:

- Nine cohesive files under `internal.gl`; one nonfair `glGate`, one executor/lane, one fatal cell and no added monitor,
  lock, atomic, scheduler, dispatcher, scope, state machine or runtime axis.
- Complete generic and required outer submission `Error` boundaries; executor-only rejection ranges; first-fatal-wins
  identity; winner-only unlocked no-throw fatal signal; best-effort fatal cleanup; exact normal-false cleanup proofs.
- Extension-first compile-time fragment shaders with unchanged body, P3/grayscale math, uniforms and GL state.
- Every fallible owner/component/array/direct-buffer initialization precedes construction and prestart of `laneRuntime`;
  the lane is the final `GlPipelineOwner` property initializer.
- Repeated-submit, deadline/late/nonreturn, EGL/GLES error-read/OOM poison, Target/carrier, namespace/reservation,
  shutdown and residue behavior closed the independent authority, concurrency and platform review cycle.
- Exact JNI identities/descriptors remain unchanged.

Deferred secondary watchpoint: three `Ref` plus three capturing function allocations per entered frame. Revisit only
after integration profiling or new authority evidence. The separately named TargetScope F4 remains post-barrier
integration-owned.

## JPEG next-slice readiness

Status: `READY_FOR_FINAL_AUDIT_AFTER_USER_COMMIT`.

Current planning pins:

| JPEG planning file | SHA-256 | Lines |
| --- | --- | ---: |
| `JpegRuntimeOwner.kt` | `207e8e94f994c8772d95cc3017dea729d7b6dcbe4129589eb725d15c7d1acd2a` | 2,018 |
| `JpegRuntimeCore.kt` | `1b37341e2f6aa2b1c1326d635f348c8912cb8e1dc2858b399da1aa5fcf243cb4` | 483 |
| `JpegRuntimeOperations.kt` | `ccc58af229ec8200d80714838d25058f7909555a9c76ac8391c518168debe569` | 487 |
| `FrameworkJpegOwner.kt` | `9dededada52c5b128ffadd8aa7260e67e012f37bef1477ee1e42dbb9650a151b` | 1,476 |
| `EncodedStorageOwner.kt` | `f52948740742a86fc42811f808d987ada5148f034d4f3dd6305c3008483aa8ad` | 617 |
| `settlement/OperationDeadline.kt` | `dc7dc3fecade761b98412e411e570481453f7c3e9125d7de7cfee89b45c33c40` | 388 |

Exact ABI anchors:

- `io.screenstream.engine.internal.JpegRuntimeOwner$NativeBridge`;
- `io.screenstream.engine.internal.EncodedStorageOwner$NativeSegmentSink`;
- `adoptNativeSegment(ByteBuffer, Int): Unit`;
- unchanged complete `nativeCompress` descriptor.

Required first action tomorrow:

1. Repin current GL/JPEG/settlement seams after the user commit.
2. Launch three fresh axes: authority/layout; concurrency/JMM/resource lifetime; JNI/native/platform/ownership.
3. Mutually cross-review material findings and solutions; use a fresh adjudicator for disagreement.
4. Select a writer contract only after convergence.

Known intrinsic candidates for the final audit:

1. Broad `Throwable`/`Error` catches and blanket OOM normalization across dispatcher, JNI/result/free, Bitmap recycle and
   Framework housekeeping. Preserve nonordinary `Error` identity after no-throw bookkeeping; keep
   `FrameworkTransaction.firstFatalWriteError`.
2. Native deadline entry occurs before range/block/sink preparation rather than immediately before `nativeCompress`.
3. Kotlin interprets the opaque writer token using `getLong`. The current result evidence has no token-presence fact;
   safe correction requires native-side invariant/evidence revalidation.
4. Two call-block allocations can lose the first buffer before typed partial ownership.
5. Surplus Bitmap lock/settlement nesting and likely call-block gate; `RgbaCarrier.leaseGate` needs post-GL revalidation.
6. Carrier/free-bag direct-buffer references survive physical/logical retirement and must clear only after exact receipt
   or all managed uses settle.
7. Oversized misplaced owners and the JPEG deadline constant require decomposition without a new runtime axis.

Conditional first compile-safe landing after the audit:

- move complete `JpegRuntimeCore.kt` and `JpegRuntimeOperations.kt` to `internal.jpeg`;
- move `jpegEnteredOperationSafetyNanos` from settlement into `JpegRuntimeCore.kt` with no alias/duplicate;
- update imports in root `JpegRuntimeOwner`, current `FrameworkJpegOwner`, `GlPipelineOwner` and `GlFramePipeline`;
- keep root `JpegRuntimeOwner.NativeBridge` and root `EncodedStorageOwner.NativeSegmentSink` physically unchanged;
- change no method body, catch, lock, state, native descriptor or ownership behavior.

Planned later sequence: move Framework owner into `internal.jpeg`; extract Framework contracts, Bitmap owner, execution
and cleanup; extract carrier lifecycle with exact buffer-reference retirement; correct partial call-block ownership;
then extract native encode coordination/result protocol. `EncodedStorageOwner.kt` remains cohesive and unmoved.

Blocking decisions before the native encode landing:

- opaque token presence/residue requires native-side contract revalidation; if unavailable, stop for user/designer
  resolution rather than inventing Kotlin proof;
- decide the exact no-extra-lock Bitmap/call-block/carrier protocol after the final concurrency axis;
- define stage-local OOM and identical-fatal mechanics through the final cross-review.

Named future-owned work: Controller construction/currentness/health/cleanup-root and concrete lossless signal; Delivery
publication/lease/cache retirement; native C++/headers/CMake/RegisterNatives/package/consumer rules; final GL carrier
integration.

## Android forward readiness

Status: `PRELIMINARY_TWO_AXES_COMPLETE_JPEG_BARRIER_ACTIVE`.

Current pins:

| Android planning file | SHA-256 | Lines |
| --- | --- | ---: |
| `AndroidCaptureOwner.kt` | `c77d915d2e7e59efe46243d3a7b062a8bb6c3b6dc273df7a562a491e20ba1227` | 1,058 |
| `CaptureMetricsOwner.kt` | `114f4165848bde0a807b5390371986c4e29fe7fc9ff83ad39528acc81962abcf` | 564 |
| `ScreenCaptureConfig.kt` | `61f37d061b67f2d5a76b9784ff61471cd850e141c8bf74156b54471af8fdd086` | 289 |
| `settlement/OperationDeadline.kt` | `dc7dc3fecade761b98412e411e570481453f7c3e9125d7de7cfee89b45c33c40` | 388 |

Preliminary seven-file direction under `internal.android`: contracts/deadlines; sole HandlerThread/Handler lane;
MediaProjection operations; VirtualDisplay operations; metrics contracts; metrics lifetime owner; small Android capture
facade. `ScreenCaptureConfig.kt` keeps public provider declarations. Add no `androidGate`, executor, lane, scope,
dispatcher or compatibility shim.

Candidates for the later final three-axis audit:

1. All finite Android operations enter without requesting the existing deadline wake.
2. API 34–37 initial resize needs an actual child deadline occurrence sharing the creation settlement gate.
3. Timely metrics can overwrite durable wake-submission rejection.
4. Two invalidity exits lose the recovery turn; persistent absence must not create a self-rescheduling spin.
5. Returned VirtualDisplay can collide before it is rooted in returned-owner evidence.
6. Lane/post and startup/finally boundaries can convert or replace unexpected `Error`; do not add an Android fatal fence.
7. Metrics collection catches downstream engine failures as provider `CollectionFailed`; use downstream-transparent
   `Flow.catch`.
8. Shared `OperationDeadline` can strand `Submitting`/`Cancelling` on unexpected scheduler/cancel `Error`.
9. Decompose monolith/package/deadline layout; unused raw getters should not survive the facade.

Future-owned: Controller result application and currentness; exact provenance; closed Attached/MechanicallyDetached
display state; owner-derived reasons; concrete lossless signal; geometry/lifecycle/terminal application; final quit
eligibility.

After JPEG closure, repin `OperationDeadline`, `OperationSettlement`, GL/Target seams and the complete Android manifest
before the final Android audit.

## Deferred post-barrier integration obligations

These remain paused until Target, GL, JPEG and Android decomposition all close:

- **F1:** The sole Controller/action drainer claims opaque Android results and applies Target state before terminal
  fencing, without worker-side authoritative apply, secondary atomics or a second sequencer.
- **F2:** Exact wrapper/occurrence/bag/target/port/provenance/kind/consumer reference checks replace scalar-only trust.
- **F3:** Maintain private closed VirtualDisplay state:
  `Attached(exact target, generation, producer operation/port/provenance)` or
  `MechanicallyDetached(exact detach provenance)`.
- **F4:** Remove raw TargetScope progress/normal/receipt-getter seams; GL returns private exact evidence after real
  SurfaceTexture/OES work and Target consumes only the exact settled result.
- **F5:** Replace caller reason arguments with owner-derived `ReturnedWithoutProducer`, `Unentered` and mechanically
  settled `Inapplicable` issuance.
- **F6:** Replace `Any` with private nominal construction/factory authority.

These are implementation dependencies, not design contradictions. Do not add temporary Target/Android/GL workarounds
during the decomposition barrier.
