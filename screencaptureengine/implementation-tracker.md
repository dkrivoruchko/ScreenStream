# Screen Capture Engine — Final Gate-B Tracker and Handoff

## Authority

The closed implementation package is:

1. `design/01_design.md` — product behavior and public API;
2. `design/02_architecture.md` — architecture, algorithms, ownership, concurrency, and platform rules;
3. `design/03_verification.md` — acceptance scenarios, checks, and high-risk traces;
4. `design/04_gate_a.md` — Gate-A decision record;
5. `design/05_gate_b_inputs.md` — mandatory Gate-B inputs and completion criteria;
6. `design/06_implementation_design.md` — concrete implementation structure and technical bindings;
7. `design/07_private_constants.md` — sole numeric authority for private Gate-B constants and fixed native
   protocol discriminators;
8. `design/08_test_implementation_map.md` — executable-test layout, evidence boundaries, vectors, tolerances, and traceability;
9. `design/09_gate_b.md` — Gate-B decision record.

Documents 01–05 remain the governing design authority. Documents 06–08 bind their implementation and test
realization. Document 09 records the Gate-B verdict. Earlier revisions, previous handoffs, Git history, agent
reports, and existing implementation are not architectural authority.

## Permanent working rules

- The primary/root agent is the sole tracker writer and gate-verdict owner. Subagents never edit this file or
  announce a gate verdict and never delegate to nested agents.
- The root remains facilitator, scheduler, evidence integrator, blocker escalator, and implementation-status
  owner. Writers and independent reviewers—not root—run source formatting, IDE diagnostics, selective/full
  builds, tests, bytecode/ABI inspection, and other implementation verification. Root reads their reports and
  repository diffs/status but does not duplicate those checks itself. A root-side duplicate selective build was
  run once during the Output cycle, changed no source, and is not counted as independent evidence.
- Documents 01–09 are closed and frozen. Any design change requires the user's explicit approval and a bounded
  correction plus fresh independent review.
- Documents 01–09 are the implementation starting authority, but implementation and test evidence may reveal a
  latent ambiguity, infeasible binding, correctness defect, or disproportionate accidental complexity. In that
  case the affected file/slice stops before an architectural workaround is invented. The root reports the exact
  conflict, evidence, ownership impact, and reasonable options to the user. Work resumes only after the user
  selects a direction; any authorized frozen-document correction is bounded and receives fresh independent
  review before dependent implementation continues.
- Every public/product decision and every private numeric constant requires explicit user approval.
- Existing Kotlin, C/C++, Gradle, tests, and Git history remain untrusted implementation hints only. They do not
  override Documents 01–09.
- Memory efficiency remains structural: checked sizes and narrowing, hard platform/backend limits, reuse, minimal
  copies, exact ownership, and cleanup. Predictive memory accounting is forbidden.
- Frozen implementation constraints are interpreted by their observable correctness, ownership, bounded-work,
  and steady-state allocation purpose. Incidental one-time JVM/Kotlin class or enum initialization is not by
  itself a material gate-path allocation defect and must not trigger prewarming, integer-coded state, cascade
  branches that replace a clearer enum `when`, or other non-idiomatic complexity. It becomes material only when it
  creates a real repeated/unbounded cost, an observable protocol failure, or a resource-ownership failure. IDE weak
  suggestions require an engineering disposition rather than automatic rewriting, but ordinary idiomatic Kotlin
  remains the default when semantics are equivalent.
- Bytecode/ABI inspection is targeted evidence for genuinely material questions: concurrency lowering and
  synchronization boundaries, repeated hot-path allocation, checked arithmetic, Kotlin-source visibility when it
  affects the supported API, JNI/native linkage, weak-symbol guards, or another explicitly identified high-risk
  mechanism. It is not a design input for ordinary Kotlin syntax and must not be used to replace standard idiomatic
  `enum`, `when`, properties, or simple value code with custom machinery merely because their cold JVM lowering has
  incidental classes or one-time initialization. When no serious behavior needs proof, source-level semantics,
  official Kotlin conventions, formatter output, and focused IDE/compiler diagnostics are sufficient.
- Repository-level AGP/Kotlin-plugin/dependency-resolution/version-catalog/buildscript/wrapper wiring is Android
  project setup outside this package. The approved external baseline remains binding without prescribing that root
  wiring.
- Preserve unrelated work and the user's index. Do not stage, commit, reset, checkout, revert, or otherwise mutate
  Git state without explicit instruction.
- During active implementation, the root gives the user concise periodic progress updates at meaningful
  transitions and during longer work: active agents, their current bounded task, completed evidence, open
  findings, and blockers. Updates stay brief enough to monitor direction without replacing the tracker.
- Test design and implementation never run as speculative prefetch for production code that does not yet exist.
  A test slice may be analyzed only after its corresponding fresh production implementation has been written;
  it then derives concrete cases from that implementation under Document 08 without treating implementation
  choices as architectural authority. Parallel prefetch, when useful, targets a later production manifest file,
  remains read-only, and cannot bypass the current production file's closure.
- Subagent briefs contain only the bounded task, authoritative inputs, relevant constraints, required checks, and
  expected result. They omit unrelated process history, earlier orchestration mistakes, superseded assignments,
  and prior-slice narrative so independent agents stay focused and do not inherit irrelevant framing.
- Every writer applies the JetBrains/Android Studio formatter to each owned source file before handoff, then
  self-reviews line breaks and layout against the current official Kotlin coding conventions. This writer-owned
  formatter pass is sufficient; no separate formatting-only subagent audit runs unless the user explicitly asks
  for one. The user performs an additional manual formatting review during file acceptance.
- Formatter output is only the mechanical layout baseline. Every Kotlin writer also performs a manual semantic
  spacing pass: meaningful validation, state-transition, ownership, error-handling, and return phases should be
  separated by blank lines when that improves scanning, without inserting empty lines mechanically or creating
  excessive vertical noise. This readability pass applies to new files and to bounded reopenings of accepted files.
- Every Kotlin implementation and every bounded reopening includes an explicit naming audit for local variables
  and private/internal variables, functions, classes, and transfer types. Names must describe their domain role or
  ownership clearly, avoid obscure abbreviations and misleading generic terms, and remain simple idiomatic Kotlin.
  Frozen public declarations are not renamed without explicit user approval.
- When root-side implementation, verification, and the required independent correctness closure for one file
  finish, the root explicitly reports that file as technically closed and awaiting user acceptance. The next
  production file may be analyzed read-only in parallel, but it receives no writer and no code change until the
  user accepts the completed file or reports corrections.
- Keep the prepared-work queue shallow. Read-only prefetch may fill otherwise idle slots for the same immediate
  dependency chain, but once several files are writer-ready, stop expanding analysis and prioritize the active
  writer, required closure audits, user acceptance, and the next already-prepared implementation. Remove
  superseded operational history from this tracker when it no longer affects authority, evidence, blockers, or
  resumption.
- Pre-analysis is not writer-ready merely because reviewers report no blocker. Before assigning a writer, the root
  must consolidate and record in this tracker one selected implementation blueprint for that exact file: concrete
  declarations and type placement, ownership and state transitions, internal seams and call boundaries,
  dependencies and honest compile closure, required naming/layout choices, and disposition of materially different
  alternatives. The writer brief points to that recorded blueprint and uses Documents 01–09 to validate it; the
  writer translates the selected solution into simple idiomatic code and may choose only nonmaterial local details.
  If the blueprint is missing, stale after a document change, or leaves a material choice open, no writer starts.
- The root does not invent or independently author implementation logic. Every technical design or bounded-fix
  solution is proposed by at least two fresh context-isolated independent subagents and compared by the root;
  OS/system-library, concurrency, native/JNI, GL, ownership/cleanup, and other critical mechanisms require three
  independent solution analyses before writer assignment. The root only facilitates, reconciles documented
  agreement, records the selected blueprint, assigns one writer, and integrates review status. If independent
  proposals materially disagree, no writer starts until further analysis or user direction resolves the choice.
- User acceptance means the current implementation is sufficient to stage and continue; it does not permanently
  freeze that source file. Later integration or test evidence may reopen it through a bounded, tracked correction
  with the normal one-writer, self-review, formatting, and fresh closure rules.
- The engine's supported source/API audience is Kotlin-only and Kotlin-first. All production and test code must be
  idiomatic Kotlin as a mandatory quality requirement; Kotlin source visibility and Kotlin metadata define caller
  accessibility. Java interoperability and JVM-surface hygiene are best effort where a simple, officially
  supported implementation does not degrade the Kotlin API, readability, architecture, ownership, performance,
  exact frozen declarations, or implementation complexity. Java reflection, Groovy source compatibility, and
  reflection-proof encapsulation are not product-contract boundaries. Do not add factories, bridges, alternate
  constructors, unsupported annotations, or design changes solely for Java/Groovy/JVM compatibility.
- The user explicitly authorized implementation in this chat on 2026-07-13. Implementation proceeds in small
  independently reviewed slices; authorization does not relax Documents 01–09, file ownership, evidence, Git,
  physical-device, or emulator rules.

## Final status

- **Gate A:** `READY_FOR_GATE_B`.
- **Gate B:** `GATE_B_COMPLETE`.
- **Implementation:** `AUTHORIZED_IN_PROGRESS`.
- **Open implementation P0/P1/P2:** `0/0/0`. The corrected exact JpegRuntime SHA completed three fresh critical
  closure audits at `0/0/0`; prior design and accepted-source findings remain closed.
- **Date:** 2026-07-14.
- **Gate-B package open P0/P1/P2:** `0/0/0`.
- **Unresolved design decisions:** none. One Framework implementation declaration-model reconciliation remains
  before writer admission; it is not an open frozen-design finding. Gate-B package status is unchanged.
- **Decision record:** `design/09_gate_b.md`, independently reviewed
  `GATE_B_COMPLETE — IMPLEMENTATION_AUTHORIZED`, P0/P1/P2 `0/0/0`.

Gate B performed no implementation, build, executable test, IDE, emulator, device, performance, release, or
runtime validation. In particular, it does not claim execution of an API 24–29 native weak-guard path or an API
30+ production weak-address-null path. The post-implementation physical-device smoke check remains later work.

## Closed implementation bindings

- External baseline: API 24–37, Kotlin 2.4.0, kotlinx-coroutines 1.11.0, JVM 17, and ABIs `armeabi-v7a`,
  `arm64-v8a`, `x86`, and `x86_64`, plus one nonpublished same-module `nativeTest` build type. For implementation
  integration, the user explicitly approved retaining the repository's AGP/build-tools configuration and using
  installed NDK `29.0.14206865` instead of the earlier Gate-B NDK input. Read-only local-toolchain and current
  official-guide checks confirmed that NDK 29 supports `ANDROID_WEAK_API_DEFS`, guarded API-30
  `AndroidBitmap_compress`, direct `jnigraphics` linkage for all four ABIs, and default 16-KiB ELF alignment.
  This integration decision changes no public/product behavior, private constant, owner, sequencing, runtime
  axis, or Documents 01–09. Only release is published; the test JNI library is absent from debug/release.
- Kotlin production layout: five public files and fourteen files in the single flat
  `io.screenstream.engine.internal` package, exactly as listed in Document 06. Paths in Documents 06–08 are
  relative to their declared roots.
- Execution: controller on `Dispatchers.Default`; metrics, JPEG, and delivery use three distinct non-owned
  per-Session `Dispatchers.IO.limitedParallelism(1)` views. One engine-private process Main-Looper Handler routes
  only built-in metrics `DisplayListener` callbacks; the Session Android HandlerThread remains private to
  projection/target work. GL executor and per-Session scheduler remain private. Non-owned views are never closed,
  cancelled, or quarantined.
- `SessionController` is the sole lifecycle/reconciliation/result authority. `TargetOwner` owns a generation-bound
  `AtomicBoolean` latest-pending source bit; the listener performs `set(true)` then lossless signaling and the
  controller consumes only an admissible current target with `getAndSet(false)`.
- GL feasibility queries immutable `GL_MAX_TEXTURE_SIZE`, both `GL_MAX_VIEWPORT_DIMS` components, and fragment
  precision once after a valid bind. Target and output dimensions use the per-axis minimum of texture and viewport
  limits before retirement. Two small direct native-order client buffers are created once and reused.
- Native JPEG uses the NDK-28 weak-API binding: module-local `build.gradle.kts` passes exact
  `-DANDROID_WEAK_API_DEFS=ON`; CMake owns `jnigraphics` linkage and target/visibility/diagnostic policy; the address
  is taken and null-checked only inside the same `__builtin_available(android 30, *)` guard and is never stored or
  passed through JNI. There is no dynamic compressor loader or compressor cleanup owner.
- While Native JPEG is enabled, Framework Bitmap and row scratch do not exist. A safe Native disable affects a
  subsequent frame; the switching frame has no Framework allocation or retry.
- Native callback storage adopts safe `SUCCESS` segments sequentially into managed storage. Persistent payload is
  managed `J`; transfer peak is `J + Smax`, worst case `2J`. Non-success invokes no adopter and disposes every
  provably owned native segment.
- A timely normal preterminal incompatible-`NativeMallocCarrier` free receipt permits a separate replacement
  allocation occurrence only after the controller rechecks the nonterminal current key. Both occurrences use the
  existing `jpegEnteredOperationSafetyNanos`; stale, late, or terminal returned carriers are cleanup-only and are
  freed exactly once. Terminal-origin or terminal-converted free remains no-watchdog cleanup.
- Native runtime eligibility requires successful own-DSO load/bootstrap/registration plus the guarded API-30 weak
  capability; it has no Kotlin executing-ABI predicate. `P-PKG` separately proves the exact production DSO for all
  four shipped ABIs. A direct load OOME publishes the sticky process-private `LoadOome(firstCause)` state without
  retry, bootstrap, carrier allocation, or Framework fallback until process death.
- `NativeResultBlock.writerStatus` uses fixed `uint64_t`/Kotlin-`Long` wire values `Safe = 0`,
  `OutOfMemory = 1`, and `InternalFailure = 2`; every other bit pattern is malformed `InternalFailure` evidence.
  `FrameworkTransferAdapter` is not a runtime entity: one private synchronous method in `FrameworkJpegOwner`
  transfers either leased carrier form while the existing owners retain carrier, Bitmap/scratch, and transaction
  ownership.
- Framework pixel resources are created by one finite `FrameworkResourceCreationOccurrence` on the serial JPEG IO
  view using the existing JPEG safety interval. It owns Bitmap creation, immediate partial ownership, API-banded
  metadata inspection, optional scratch creation, and allocation-free publication. A timely Native disable commits
  `Suspended(Reconfiguring)` atomically with the disable; complete Framework installation alone permits `Active`.
- Native and managed incompatible-carrier allocations use distinct typed replacement occurrences. Native retains
  its separate free-receipt prerequisite; Managed drains uses, logically detaches without a physical-free receipt,
  rechecks the current nonterminal key, and calls `ByteBuffer.allocateDirect(B)`. Both allocation occurrences use
  the existing JPEG safety interval and never rerun preparation.
- GL target construction owns an occurrence-local `PreparedTarget` with a reserved generation, partial
  OES/`SurfaceTexture`/`Surface` owners, a precreated `CurrentTarget` candidate, and shared release obligations.
  Timely current completion transfers it allocation-free to `CurrentTarget`; every noninstalling outcome remains
  cleanup-owned. Installed and uninstalled targets use mutually exclusive readiness proofs for the one
  Surface -> SurfaceTexture -> OES cleanup route.

## Approved private constants

Document 07 is the sole numeric authority:

- `firstMetricsReadinessNanos = 5_000_000_000L`;
- `initialCapturedResizeReadinessNanos = 5_000_000_000L`;
- `androidEnteredOperationSafetyNanos = 5_000_000_000L`;
- `glEnteredOperationSafetyNanos = 10_000_000_000L`;
- `jpegEnteredOperationSafetyNanos = 15_000_000_000L`;
- `maxOldGlErrorsPerFrame = 8`;
- `maxFinalGlErrorsPerFrame = 8`.

They are private positive finite safety policy, not SLA values. Document 08 is the sole implementation-test map
for their exact boundaries and for all approved image vectors and tolerances.

Document 07 also fixes the separate native writer-status protocol discriminators `Safe = 0L`,
`OutOfMemory = 1L`, and `InternalFailure = 2L`. They are wire values, not safety-policy constants, durations,
tolerances, or runtime policy axes.

## Gate-B package review closure

- The required simplicity, CPU/GPU/memory-efficiency, and correctness/implementability waves completed with
  independent whole-package reviews.
- Every material frozen-package finding was independently verified, explicitly dispositioned by the user, corrected
  by a sole document writer, and freshly closed. This does not claim closure of the open implementation findings
  recorded below.
- The current exact-provider/process-Main metrics binding and its API-34–37 late-platform-snapshot handling
  completed fresh holistic, Android/platform, and concurrency closure reviews with P0/P1/P2 `0/0/0`.
- The current JPEG ABI proof, replacement-allocation occurrence, sticky load-OOME state, writer-status protocol,
  and Framework transfer ownership completed independent focused analyses, fresh final reviews, and focused
  diagnostic closure with P0/P1/P2 `0/0/0`.
- The final corrected GL-limit, atomic-pending-bit, carrier-free-deadline, and native-evidence slices completed
  fresh closure reviews with P0/P1/P2 `0/0/0`.
- The compressed Framework-resource-creation binding, API 24–25/26+ handling, Native-disable sequencing, partial
  ownership, delivery cutoff, and executable mapping completed final independent revalidation at P0/P1/P2
  `0/0/0`.
- The managed-carrier replacement and prepared-target/unpublished-target corrections completed focused JPEG,
  Android/GL, and whole-package final revalidation at P0/P1/P2 `0/0/0`.
- The Gate-B decision record completed two writer self-reviews and a fresh independent record review with
  P0/P1/P2 `0/0/0`.

## Current implementation handoff

Gate B remains complete and there is no Gate-B blocker. Implementation is explicitly authorized and active.
Work proceeds one production or test file at a time. Before each file, at least two fresh direct non-nesting
subagents independently analyze its concrete internals against Documents 01–09 and current primary official
guidance; critical mechanisms require three. The root records one reconciled direction and assigns exactly one
writer for that file. The writer performs at least two
explicit self-review passes before handoff. Review depth is risk-based: OS/system/native-library interaction,
concurrency, GL, ownership/cleanup, operation settlement, delivery races, and other complex state structures
require three fresh independent read-only audits; a genuinely straightforward value, adapter, test, or build
file requires one fresh independent audit unless its analysis exposes higher risk. Any material finding receives
a bounded one-writer correction, two correction self-reviews, and fresh independent closure review at the
applicable risk depth; the next file starts only after the current file has no open material finding. The root
alone owns integration status and updates this tracker after every analysis, implementation, review, correction,
verification, and blocker transition. Current implementation status and the bounded resumption points are recorded
below. `USER_ACCEPTED_CHECKPOINTED` marks a sufficiently sound committed integration baseline, not an immutable implementation
freeze. Later production slices and the resulting whole-system view must proactively reopen an accepted file when
that improves cross-file ownership, fit, clarity, correctness, or efficiency. Such changes are expected during
incremental construction; they still use one sole writer per file, preserve the user's staging/index, and pass the
ordinary self-review, independent closure, and user-acceptance sequence before the revised file is considered
accepted again.

Fresh context-isolated subagents remain the default. Every new production file, materially new solution candidate,
and every review/audit/verification whose value depends on independence starts from no forked conversation turns and
rereads the current authoritative files. Independent evidence never reuses the writer, reconciliation agent, prior
auditor, or another agent whose retained context could bias the result. A subagent may instead be continued or
reopened within the same bounded slice when its accumulated task context materially improves correctness or avoids
redundant work—for example reconciliation to sole writer, or a writer completing its own bounded correction and
self-reviews. Such reused work is never counted as an independent proposal, audit, or closure review; all required
independent evidence still comes from fresh agents. Every brief or continuation remains narrowly scoped to the exact
assignment, authoritative inputs, relevant constraints, required checks, and prohibitions, without unrelated chat
history or prior-slice narrative.

## Confirmed implementation-time frozen-document conflicts

All previously reported metrics, Framework, JPEG, and GL document conflicts are closed in the current Documents
01–09. There is no open frozen-document blocker; Document 09 remains `GATE_B_COMPLETE — IMPLEMENTATION_AUTHORIZED`
at P0/P1/P2 `0/0/0`.

## Implementation progress

**Active coordination:** `STABLE_HANDOFF_FRAMEWORK_TWO_EXACT_REBINDS_COMPLETE_ADJUDICATION_REQUIRED`.
Stable checkpoint commit `c077c346` (`part 12`) contains Documents 01–09 and the nine earlier user-accepted Kotlin
sources listed below. `JpegRuntimeOwner.kt` is now also user-accepted and staged at exact SHA-256
`3f7b213c29e3ce98f77863d4196312fd392bcb350507bd7856e50d28fb635b64`.

The sole writer corrected bounded A–G/H without changing I/J, public/product behavior, constants, JNI descriptors,
accepted-file APIs, threads, buffers/copies/views, predictive accounting, or runtime axes. JetBrains formatting,
Studio diagnostics, forced module compilation, whitespace, naming, semantic spacing, two writer self-reviews, and
three fresh independent native/ownership, compile/API/integration, and holistic concurrency/terminal audits all
closed the exact staged SHA at P0/P1/P2 `0/0/0`.

Both saved `ReconciliationOwner.kt` prefetches agree on a stateless synchronous pure calculator with checked frozen
planning and controller-owned currentness/commit/terminal authority, but exact Framework/Jpeg/Target/GL/controller
bindings remain deferred. Two fresh final Framework rebind agents fully read the frozen authority, exact staged Jpeg
SHA and accepted dependencies, completed two self-reviews each at local `0/0/0`, and found no missing accepted seam or
platform blocker. Their common technical contract is closed, but their materially different owner/declaration models
require one narrow fresh adjudication before a writer may start.

Repository housekeeping: the untracked root-level `settlementGate` was a zero-byte file, had no build or source
reference, and was not part of the frozen file manifest. It was removed as an accidental workspace artifact without
touching Git/index or implementation code.

**Exact next sequence:** a fresh root runs one narrow read-only Framework declaration-model adjudication using the two
recorded alternatives below, records one exact writer contract, and only then may admit a sole Framework writer. No
test, device, emulator, source writer, commit, or root-owned index action is active.

### Accepted and checkpointed Kotlin sources

| File | Current staged SHA-256 | Status and retained boundary |
| --- | --- | --- |
| `ScreenCaptureParameters.kt` | `7485bc5e76b4dc8ca2b5b6983304924c24f773d2df8f30df7f7940e96fc65160` | `USER_ACCEPTED_CHECKPOINTED`; exact public parameter inventory, immutable manual value semantics, bounded validation, and one internal revalidation seam. Geometry/planning remains outside this file. |
| `ScreenCaptureOutput.kt` | `923f0e0d8250a7e0b83c74f3f25a2add5f421628029f3ae188b34a2e466bdac2` | `USER_ACCEPTED_CHECKPOINTED`; exact five public output declarations. Kotlin-only `internal constructor` behavior is accepted; no Java/JVM workaround. |
| `ScreenCaptureObservations.kt` | `3a2c4c708a68e6cec8280ff7c0bb7d38601a37534da53e4b28e2ddb057b0449e` | `USER_ACCEPTED_CHECKPOINTED`; exact observation/state/problem/stats inventory, cause identity, finite-value handling, and saturating totals. |
| `internal/OperationSettlement.kt` | `b99b5b3a060c5a811e95d6462075d109ebb507373d208e69b7040d166dba7a12` | `USER_ACCEPTED_CHECKPOINTED`; typed occurrences/cells/owner bags, one nonfair settlement gate, exact deadlines, inline-entry-safe submission, terminal-to-cleanup transfer, cancellation and late-receipt mechanics. |
| `internal/TargetOwner.kt` | `11351ebb7a3375a9b1b508ff5fe4990e39ca6e34240e016e0d4593e33e806a7f` | `USER_ACCEPTED_CHECKPOINTED; BOUNDED_REOPEN_REQUIRED`; retain current target/lease semantics and add the corrected-document `PreparedTarget`, pre-reserved generation, precreated promotion, exclusive install/cleanup claim, shared release obligations, and installed/uninstalled readiness proofs before GL writer admission. |
| `ScreenCaptureConfig.kt` | `61f37d061b67f2d5a76b9784ff61471cd850e141c8bf74156b54471af8fdd086` | `USER_ACCEPTED_CHECKPOINTED`; exact public config/metrics inventory, immutable fixed/default built-in definitions, process-Main callback routing, independent cold collections, API-band metrics reads, continuous display epochs, and bounded sticky invalidation/recovery. |
| `internal/CaptureMetricsOwner.kt` | `ddf0550f36e8f820282d363f948cfffd3fad7e973ae337d677a5b4a72c256b7a` | `USER_ACCEPTED_CHECKPOINTED`; compact direct-readiness occurrence, durable precreated facts, typed lifecycle bag, exact provider/Flow sequencing, strict readiness arbitration, parent-only cancellation, and sole parent-completion receipt. Three critical audits closed this exact SHA at `0/0/0`. |
| `internal/EncodedStorageOwner.kt` | `3817f73ac4842e6d588f238a76e4cab0669629d50e6e7265b9e84d916526bf21` | `USER_ACCEPTED_CHECKPOINTED`; exact private JNI adoption sink, segmented Framework/Native transactions, immutable payload roles, one active lease/displaced bound, and allocation-free identity transitions. Three critical audits closed this exact SHA at `0/0/0`. |
| `internal/AndroidCaptureOwner.kt` | `9fa48d10b56963326ca1d6768858d89de6581e275efb14a0c1bb94099b8f23d1` | `USER_ACCEPTED_CHECKPOINTED; LATER_BOUNDED_REOPEN_REQUIRED`; current projection/lane/no-entry/allocation scope closed by three critical audits at `0/0/0`. After `TargetOwner` reopening, add only the corrected-document `CurrentTarget`-typed producer boundary and typed safe no-producer evidence through a separate bounded cycle. |
| `internal/JpegRuntimeOwner.kt` | `3f7b213c29e3ce98f77863d4196312fd392bcb350507bd7856e50d28fb635b64` | `USER_ACCEPTED_STAGED`; exact Native/Framework product, carrier/lease, preparation/free/replacement/encode, staged Native-disable, JNI protocol, result-cause and cleanup bindings. Writer plus three fresh critical audits closed this exact SHA at `0/0/0`. |

All accepted files passed their required naming, idiomatic-Kotlin, semantic-spacing and risk-depth reviews and
remain reopenable through the normal bounded correction process if later integration or test evidence requires it.

### `JpegRuntimeOwner.kt` current binding

- **Status:** `USER_ACCEPTED_STAGED`; exact source SHA is `3f7b213c…`. Three fresh critical audits independently
  closed the implemented A–G/H contract at P0/P1/P2 `0/0/0`; the user reviewed, accepted, and staged it.
- **Canonical products and ownership:** exactly `NativeEnabled(NativeMallocCarrier)`,
  `FrameworkOnNativeCarrier(NativeMallocCarrier)`, and `FrameworkOnManagedCarrier(ManagedDirectCarrier)`; the
  stable product identity is the Native-health occurrence. Concrete carriers and Native/Managed leases remain typed,
  identity-fenced, view-free, and exact-range. There is no parallel health cell, pointer transport, manual managed
  free, Cleaner/GC receipt, same-frame fallback, or predictive accounting.
- **Attachment/publication:** each precreated concrete carrier owns one private `Empty -> Attached -> Ready|Rejected`
  attachment shell. Phase 1 gates exact buffer attachment and immediate typed-bag adoption; opaque buffer validation
  runs unlocked; phase 2 gates readiness, exact returned-owner evidence, and return-cell publication. Terminal between
  phases transfers the intact bag-owned occurrence; only exact Ready installation becomes operational.
- **Execution and process state:** one Session-private owner uses the injected non-owned serial JPEG IO view; the
  dispatcher remains private. `internal val jpegIoSettlementSignal` and generic
  `submitJpegIoOperation(JpegIoOperation<E>)` are the sole same-module submission seam. Process load/bootstrap is
  monotone and may publish `Unattempted/Available/CleanUnavailable/LoadOome/Poisoned` only during initial load and
  bootstrap; runtime operation failures never mutate an already-Available process state.
- **Native/JNI protocol:** nested private `NativeBridge` retains the exact six Document-06 descriptors, weak API-30
  guarded capability/invocation, exact writer statuses `0L/1L/2L`, stable direct writer/result blocks, ambiguity and
  malformed evidence before OOM, and no ABI predicate/stored function pointer/dynamic loader. Raw token values are
  never published; residue release requires success plus an immediate cleared-token reread.
- **Occurrences and cleanup:** preparation, Native free, Native replacement, Managed replacement, and Native encode
  remain distinct typed occurrences over accepted `OperationSettlement`. Bags own every partial physical return.
  Finite stale cleanup uses a fresh full JPEG deadline; terminal-origin or terminal-converted cleanup is no-watchdog.
  Normal free receipt consumption is separate from timely replacement authorization.
- **Encode/storage:** the encode bag retains exact product, operation-retained lease, call-block owner/loan, Storage
  owner/transaction/sink, unpublished-retirement stage, and disabled candidate. External Storage/carrier/block work
  runs outside the occurrence gate; each successful identity-bound stage is recorded under the gate and fields clear
  only after success. No same-frame Framework retry or duplicate storage/payload owner exists.
- **Controller boundary:** the controller alone owns desired revision, geometry generation, lifecycle epoch,
  currentness, lifecycle/result/Stats, admission/cache fencing, terminal state, and Native-disable policy commit. This
  owner may expose fixed reference/scalar authorization/finalization seams but may not invent a local authoritative
  work key or mutate controller lifecycle independently.
- **Closure:** bounded A–G/H are implemented and closed; I/J remain explicit nonchanges. Future changes require a
  new bounded reopen rather than continuation of the completed correction cycle.

### `FrameworkJpegOwner.kt` prepared input

- **Status:** `TWO_FINAL_EXACT_REBINDS_COMPLETE_MATERIAL_DECLARATION_ADJUDICATION_REQUIRED`; no source, selected final
  blueprint, or writer exists. Both fresh agents bound exact staged Jpeg SHA `3f7b213c…`, accepted settlement/storage/
  output declarations, current official Bitmap/Kotlin guidance, and finished two self-reviews at local `0/0/0` with
  no edits. There is no Jpeg reopen, adapter, dispatcher exposure, Android API blocker, or missing compile seam.
- **Exact accepted seams:** the two Framework `JpegRuntimeProduct` variants; `RgbaCarrier.byteCount`;
  `RgbaCarrierLease.carrier`, `retainForOperation`, `enterExactRange`, `exitExactRange`, and `releaseFromOperation`;
  `JpegIoOperation<E>`, `jpegIoSettlementSignal`, `submitJpegIoOperation`, and generic operation arbitration/terminal
  conversion; `EncodedStorageOwner.FrameworkTransaction.outputStream`, attach/replace/detach/retire operations,
  transaction commit/abort/failure/committed-payload facts; accepted `ImageSize`; separately injected `EngineClock`.
  JPEG dispatcher ownership remains private.
- **Closed common declaration families:** one transfer-mode enum; typed creation/encode/recycle settlements and
  evidence; one private Bitmap returned-owner shell; distinct creation, encode, and recycle occurrences/owner bags;
  typed recycle origin and normal receipt; and one `FrameworkJpegOwner`. No duplicate product, carrier, lease,
  backend-health, topology-key, replacement, allocation-failure, executor, or transfer-adapter type is permitted.
- **Closed creation flow:** precreate the complete finite JPEG-IO occurrence graph; enter immediately before one
  mutable `ARGB_8888` `Bitmap.createBitmap`; adopt the exact Bitmap before fallible inspection; inspect actual
  dimensions/config/mutability/recycled/rowBytes/byteCount outside the gate; API 24–25 touches no API-26 HARDWARE or
  color-space member, while a directly dominating API-26+ path rejects HARDWARE and requires sRGB. Exact tight facts
  select one `copyPixelsFromBuffer`; otherwise allocate and reuse one `IntArray(width)` row. Timely current Ready alone
  installs; partial/stale/late/terminal results retain exact recycle ownership. The Native-disable switching frame
  performs no Framework creation or retry.
- **Closed encode flow:** exact current Framework runtime product and lease identities; precreate occurrence/bag;
  retain operation lease, exact Bitmap use, and one attached Framework transaction; submit only through JPEG IO; enter
  exact carrier/Bitmap use, reset exact buffer range, transfer tight or portable RGBA-to-opaque-ARGB, then call
  `Bitmap.compress` exactly once. Transaction failure classification precedes safe false; success requires commit.
  Direct Bitmap/scratch OOM is ResourceExhausted, unexpected ordinary exception/malformed evidence is InternalFailure,
  and other fatal VM errors are not normalized. Retry-safe settlement resolves uses, releases the lease, moves or
  aborts/detaches the transaction, retires stale/late unpublished payload, and only then clears the occurrence.
- **Closed recycle/controller flow:** close Bitmap admission, drain exact encode/use/lease/transaction/payload
  mechanics, then submit one precreated recycle occurrence. Preterminal incompatible replacement uses the existing
  JPEG interval; terminal-origin or converted cleanup has no watchdog. Possibly entered recycle is never retried;
  exact normal receipt alone drops ownership and only timely incompatible replacement authorizes later creation after
  a fresh controller currentness check. Controller alone owns revision/geometry/lifecycle currentness, install
  authorization, lifecycle/Stats/drop/cache/output/terminal decisions, and `retainCommittedFrame`.
- **Material alternative A — installed-resource model:** `FrameworkJpegOwner` has a private constructor and is itself
  the immutable installed Framework Bitmap resource. It owns `ImageSize`, checked Int pixel/row sizes, the private
  Bitmap shell and transfer mode. Companion `beginResourceCreation`/`settleResourceCreation` returns the already-
  precreated owner reference after controller `installAllowed`; encode and recycle are instance methods. There is no
  manager `isInstalled` cell and no separate frame descriptor.
- **Material alternative B — session-manager model:** one long-lived `FrameworkJpegOwner(jpegRuntimeOwner, clock,
  sdkInt)` owns installed state, `isInstalled`/compatibility queries, and instance begin/settle creation, encode, and
  recycle methods. A separate immutable `FrameworkFrameDescriptor(width, height, stride, pixelByteCount: Long,
  quality, imageSize)` carries encode inputs, and creation settlement returns a typed status rather than the installed
  owner directly.
- **Required adjudication:** choose the authority-faithful owner lifetime and then normalize creation result shape,
  frame-input shape and checked Int/Long boundaries, private/internal bag exposure, and nonmaterial enum names. Do not
  start a writer until one exact declaration/signature contract is recorded. If neither model follows Documents
  01–09 without architectural invention, stop and report the conflict rather than creating a third topology.

### `AndroidCaptureOwner.kt` solution wave

- **Status:** `USER_ACCEPTED_CHECKPOINTED_CURRENT_SCOPE; LATER_BOUNDED_REOPEN_REQUIRED`; current SHA
  `9fa48d10b56963326ca1d6768858d89de6581e275efb14a0c1bb94099b8f23d1`. The correction writer ran JetBrains
  formatting, passed Studio diagnostics with zero errors and module compilation, and completed two self-reviews at
  P0/P1/P2 `0/0/0`. Fresh platform, concurrency/settlement, and holistic/integration audits independently closed
  the exact same SHA at `0/0/0`; the user reviewed and staged it. `ANDROID-IMPL-P1-1` and
  `ANDROID-IMPL-P1-2` are closed.
- Selected correction representation: keep `OperationOccurrence` as sole entry/return/deadline authority; replace
  five reservation booleans with exact retained occurrence references for callback register/unregister,
  VirtualDisplay create/release, and projection stop; add only
  `callbackRegistrationNoPlatformEntryProven` and `virtualDisplayCreationNoPlatformEntryProven`; retain every
  existing real-return and physical-owner field. One generic identity-bound proof helper may set a fact only under
  the exact occurrence `settlementGate` with empty return and exact Cancelled, SchedulerRejected, or
  DeadlineGuardFailed unentered evidence. Two compact readiness helpers consume those facts without fabricating
  return, receipt, owner, unregister, or release.
- Selected cleanup binding: registration absent/no-entry makes unregister inapplicable; entered registration return,
  including throw, still requires real unregister. Creation absent/no-entry or a real returned null/no owner makes
  display release inapplicable; a real returned owner still requires real release. Projection stop waits for both
  exact cleanup helpers and remains a real mandatory call; lane quit still waits for real projection-stop return.
  Entered nonreturn, ambiguous `Submitting`, accepted queue nonprogress, and terminal-after-entry remain blocking.
- Selected allocation binding: fully construct every register/create/unregister/release/stop occurrence before its
  reference CAS, and every detach/listener-removal/display-release candidate before Target identity binding. Shared
  submission captures the ready Handler and precreates the captured Runnable, fixed operation-specific post-false
  rejection, and lane-not-ready rejection before `beginSubmission`; real `Handler.post` throw publishes the caught
  object, false publishes the precreated rejection, and true publishes acceptance after the call. Listener-removal
  evidence precreates sentinel-arm and sentinel-post-false failures; the existing sentinel Runnable and separate
  execution receipt remain unchanged. Exact platform and two Handler-post callsites remain unchanged.
- **Later bounded reopening required after `TargetOwner.kt` reopens:** against the corrected Documents 02, 03, and
  05–08, VirtualDisplay creation must accept and retain
  the installed `CurrentTarget` identity rather than a raw `Surface`; only `CurrentTarget` may cross producer and
  listener boundaries. Add the typed safe no-producer outcome for a target-specific create/attach operation that
  was proven inapplicable/unentered or returned without a producer owner. It is distinct from a real detach/release
  receipt; possible-mutation throw remains unsafe. This reopening follows the `TargetOwner.kt` prepared-target
  reopening and receives its own writer/self-review/fresh closure cycle.
### `GlPipelineOwner.kt` solution wave

- **Status:** `DESIGN_P1_CLOSED_DEPENDENCIES_AND_RECONCILIATION_REQUIRED`; no source or writer exists. Candidate A
  completed at P0/P1/P2 `0/1/0`; candidates B/C completed at `0/2/0`. Their common EGL/GLES algorithm remains a
  useful input, but all three reports predate the corrected documents and none is a selected blueprint.
- Retained algorithm: one Session-private one-worker GL executor; EGL14 ES2 context plus 1x1 pbuffer and sole initial
  bind; immutable per-axis capability/fragment-precision facts; one external-OES program; exactly two reusable direct
  native-order client buffers; one RGBA/UNSIGNED_BYTE output texture/FBO; precomputed crop/rotation/mirror mapping;
  one SurfaceTexture transform and one framebuffer inversion; API-33 guarded dataspace classification; direct tight
  readback into the future JPEG carrier lease; bounded old/final error evidence; typed finite construction/frame/
  destruction occurrences; and exact Surface -> SurfaceTexture -> OES -> EGL -> lane cleanup ordering.
- Required reconciliation binding: GL construction receives an occurrence-local `PreparedTarget` whose precreated
  bag owns the reserved nonreused generation, partial OES/`SurfaceTexture`/`Surface`, precreated `CurrentTarget`
  candidate, and shared release/destruction obligations. Each platform/GL constructor return is adopted
  immediately. Timely current completion transfers allocation-free to installed ownership; every noninstalling
  outcome wins cleanup ownership and never becomes current.
- Only installed `CurrentTarget` may cross listener, VirtualDisplay producer, target-lease, frame, or carrier-lease
  boundaries. Uninstalled cleanup uses structural no-listener/no-producer/no-lease evidence; installed cleanup uses
  the existing physical prerequisites or the new typed safe no-producer evidence. Both flow through the sole
  Surface normal return -> `SurfaceTexture` normal return -> OES destruction route, using existing preterminal
  Android/GL intervals and terminal no-watchdog conversion. Partial/nonreturn cleanup roots only exact dependents.
- No selected blueprint or writer admission is recorded until JpegRuntime carrier/lease types, reopened
  `TargetOwner.kt`, and the later Android typed boundary are accepted. No GL-local carrier, transient stale target
  installation, direct partial-owner Surface release, new constant, or duplicate future type is admissible.

### Required dependency and resumption order

1. Run one fresh narrow adjudication of Framework alternatives A/B, record one exact declaration/signature contract,
   then implement `FrameworkJpegOwner.kt` with one writer and close it independently.
2. Reconcile the two saved `ReconciliationOwner.kt` prefetch reports against accepted Jpeg and Framework declarations;
   do not duplicate product/carrier/health/currentness/key types for isolated compilation.
3. Bounded-reopen accepted `TargetOwner.kt` for `PreparedTarget`, reserved generation, allocation-free promotion,
   exclusive install/cleanup claim, shared release obligations, and installed/uninstalled readiness proofs.
4. Bounded-reopen accepted `AndroidCaptureOwner.kt` only after Target acceptance, adding the `CurrentTarget`-typed
   producer boundary and typed safe no-producer evidence while preserving its accepted current scope.
5. Reconcile and implement `GlPipelineOwner.kt` against accepted Jpeg, reconciliation, Target, and Android contracts.
6. Continue controller/cleanup/delivery integration. Analyze and implement Document-08 tests only after the matching
   fresh production slice exists. Use a connected physical device when runtime verification becomes applicable; do
   not launch an emulator without user consent.

Current resumption state on 2026-07-14: Documents 01–09 and nine accepted Kotlin sources are checkpointed at
`c077c346`; exact Jpeg SHA `3f7b213c…` is technically closed by three fresh critical audits at `0/0/0`, user-accepted,
and staged. Two fresh final Framework exact-rebind reports completed at local `0/0/0`: all external seams and
mechanical flows agree, while alternatives A/B leave one material installed-resource-versus-session-manager
declaration choice for a fresh narrow adjudicator. The accidental zero-byte root `settlementGate` artifact is removed.
No agent, source writer, test, device, emulator, commit, or root-owned index action is active.
