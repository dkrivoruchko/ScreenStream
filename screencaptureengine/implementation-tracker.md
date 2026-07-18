# Screen Capture Engine — Current Implementation Tracker

## Authority

Documents `design-next/01-authority-router.md` through `design-next/12-domain-delivery-observation.md` are the sole
normative implementation authority, in router order. `design-next/13-future-evolution.md` is nonnormative. The previous
`design/01`–`09` package is not authority.

This tracker is operational state only. It may become stale after interruption, compaction or document updates; any
conflict is resolved from current Documents 01–12 and the tracker is corrected before more work starts. Kotlin is
implementation evidence, not authority.

Every Markdown file except this tracker is frozen. Only a direct user decision may authorize a separately bounded
documentation task.

## Permanent working rules

- Root is exclusively an orchestrator, the sole tracker reader/writer, status owner and gate integrator. Root does not
  perform technical implementation, audit, research, diagnostics or production builds unless the user explicitly asks
  for a bounded root-only check.
- At most three direct subagents run simultaneously. Every subagent must do its task personally and must not create,
  run, resume, delegate to or message another subagent or use collaboration/multi-agent. Nested delegation is forbidden.
- Before every production writer, three independent planning agents derive the plan from current Markdown authority.
  Aligned plans proceed directly; no redundant cross-review is required. Material disagreement uses a fresh adjudicator.
- Fresh agents are required for new independent axes, broad reopening, architecture and adjudication. Reuse is preferred
  for the same task's bounded continuation, mechanical writer, writer correction or exact-context follow-up.
- If a slot/thread is stuck, root reports the real capacity immediately, continues with available agents, and resumes the
  remaining axis when capacity releases. Root never pretends rejected work is active.
- Root never interrupts, cancels, replaces or duplicates an active agent without direct user permission. An interrupted
  agent is never resumed unless the user explicitly orders that exact resume.
- Agents working longer than roughly two to three minutes send a concise heartbeat: completed work, current phase,
  finding/blocker and bounded remainder.
- Root supplies proportional authority: current tracker state and pins, exact task contract, governing document sections,
  affected code/boundaries, required invariants and relevant current official primary sources. Full Docs01–12 reading is
  required for architecture, design and cross-document adjudication; bounded slices use the router's sufficient packet.
- Technical agents check current official primary guidance: Android/AOSP, Kotlin/JetBrains, Oracle/OpenJDK/JNI, or
  Khronos as applicable. They must actually browse the current sources during the task; memory-only source claims are
  not acceptable. Every handoff records exact title/URL, conclusion and disposition. Frozen project Markdown remains
  normative product authority; external sources validate mechanism and current platform best practice.
- Every finding is classified as an intrinsic defect, intentional migration state or named future-owned integration gap.
  Fix stable intrinsic defects; do not add temporary workarounds for future-owned gaps.
- Every finding includes exact authority/source/line evidence and at least one concrete correction. Labels and severity
  never substitute for agreement on the problem, impact and solution.
- Critical product/platform/concurrency/ownership/safety/performance ambiguity stops for the user/designer. Secondary
  structure, visibility, naming, layout or mechanism may use the simplest sound recorded deviation.
- The finished working product is the objective. Priority is observable correctness, platform behavior, concurrency,
  graphics, ownership/resource lifetime, cleanup/failure/JNI safety, privacy/security and appropriate efficiency; then
  simple maintainable idiomatic Kotlin. Names, internal visibility, file placement and predicted mechanism are secondary.
- Root does not begin a materially different slice after completing the current one until the user agrees the next
  action. Safe bounded continuations inside an already approved slice follow its recorded procedure.
- Preserve unrelated work. Root and agents never stage, unstage, commit, reset, checkout, revert, mutate index/history or
  otherwise take over Git ownership without direct user instruction. The user exclusively owns staging and commits.
- Tests are entirely out of scope until the user separately authorizes them.
- One writer owns every affected file. Overlapping writers are forbidden. Accepted Kotlin is a checkpoint, not frozen
  code, and may be reopened when evidence requires it.
- Final passive declarations may land early only with permanent domain ownership and no placeholder behavior, mutable
  authority, lifecycle transition, arbitration, platform call, fabricated health/presence or incomplete execution path.
- Physical layout follows the authority manifest, cohesion and one-way dependency. File size is only a review signal:
  roughly 250–500 lines is convenient, 600–700 may suit a cohesive state machine, and more than 800 requires a cohesion
  justification, never compression, shortened names, removed evidence or a split that widens authority.
- Every subagent performs two self-review passes. A writer also formats only its scope, uses available JetBrains/Android
  Studio diagnostics, performs proportional focused/forced production compilation, reviews naming/spacing and stops for
  user manual review. Unavailable IDE tooling is noted once and is not a blocker.
- After user manual review, closure uses independent read-only audit proportional to risk. The user may explicitly select
  a narrower audit for a planned mechanical correction.
- Critical/high-risk review uses three fresh independent axes. High-risk includes Android/platform, GL/EGL/GLES,
  concurrency, scheduling, locks, atomics, publication, deadlines, cancellation, late/nonreturn, resource lifetime,
  JNI/native/ABI and complex stateful logic. One axis explicitly checks Docs01–12 conformance.
- GL and every concurrency/lock/publication slice are always elevated-risk. Their planning and closure use three axes,
  official primary authority and explicit gate, allocation, deadline, late-return, nonreturn and nontermination probes.
  Aligned results proceed without redundant cross-review; material findings follow the cross-review rule below.
- Cross-review is required only for material, complex, disputed or writer-contract-changing findings/solutions. Trivial
  aligned observations and mechanical inventory facts do not require it.
- Native/JNI is a stricter exception: every candidate defect returned by any closure axis must receive an explicit
  independent confirm/reject disposition from all three current fresh axes before closure or correction. Root circulates
  one consolidated finding packet; agents never communicate with one another.
- Screen Capture Engine production audits inspect `build.gradle.kts` only where the active slice requires native
  configuration or wiring. For Native/JNI this includes NDK, CMake, ABI filters, weak-API flags, native targets,
  DSO/AAR packaging and consumer native keep boundaries. Generic `minSdk`, `targetSdk`, `compileSdk` and unrelated module
  configuration are out of scope and are not findings.
- Production Kotlin 2.4 must be direct, typed, compact, readable and idiomatic. Prefer immutable facts, narrow visibility
  and explicit ownership; avoid speculative abstractions, compatibility layers, duplicate models and generic machinery.
- Android platform branches read `Build.VERSION.SDK_INT` at the owning boundary and use typed `Build.VERSION_CODES`
  constants. Do not thread caller-supplied SDK surrogates or duplicate platform constants.
- Preserve checked sizes, narrowing, limits, identities, bounded work, minimal copies, exact cleanup and resource roots.
  Do not use `Any`, fake health/presence flags, fabricated receipts, raw handles as ownership proof, incomplete behavior,
  duplicate authority/topology or predictive accounting.
- Keep this tracker current, not archival. Retain rules, current queue, compact accepted pins/deviations, open blockers,
  future obligations and approved handoffs. Delete writer/auditor chronology, intermediate hashes, superseded findings,
  manual-review history and repeated verification narration once no future task depends on them.
- Retain consolidated audit/research/design conclusions, exact decisions and evidence when a future task depends on
  them. Remove raw conversational logs and repeated narration, not useful future knowledge.
- Before compaction, restart or releasing context needed later, root records every material current handoff, pin,
  disagreement, solution and next gate here, then rereads the saved section.

## Current repository checkpoint

- Production source decomposition for Android, Target, GL and JPEG is accepted. Functional work proceeds through bounded
  authority-owned slices.
- Native/JNI production implementation and its bounded corrections are closed. Final independent audits are clean,
  mutex/lock behavior is authority-conformant and F3 is adjudicated not to be a defect. The user formatted, manually
  reviewed and staged the complete Kotlin/C++/configuration tuple.
- A targeted C++ formatting audit proved semantic identity by whitespace, preprocessor-token, object-file and normalized
  CMake-graph comparison plus production builds. A final staged pin/inventory gate is clean: the production/configuration
  snapshot exactly matches the accepted pins below, has no unstaged production overlap, unrelated staged files or tracked
  generated artifacts. `screencaptureengine/.cxx/` is ignored.
- This tracker contains the final current checkpoint and is the only file awaiting user staging before commit. No agent
  or writer is active.
- Tests remain out of scope. The user owns all staging, index, commits and history.

## Current action queue

1. **NATIVE/JNI — CLOSED; PRODUCTION/CONFIGURATION STAGED, TRACKER READY TO STAGE AND COMMIT.** F1 fatal-return
   publication and F2 capsule lexical exception containment are corrected. The fallback F1 path atomically completes
   `InternalFailure` plus exact fatal under `settlementGate`, does not mutate an already-published cell, signals only after
   unlock and rethrows the identical object. F2 catches post-construction C++ exceptions while the capsule remains alive,
   then explicitly closes, verifies `closed()` and only afterward publishes count-first/status-last. Mutex/freeze/adoption
   and pending-Java-throwable behavior are authority-conformant. Fresh adjudication rejected F3: runtime continues to own
   capsule/segment/release services while glue owns the JNI adoption adapter; do not refactor runtime for F3.

   Final independent audits aligned clean on Kotlin and deep C++/JNI safety. The subsequent formatting-only native diff is
   proven semantically identical: staged/working NDK Clang tokens, direct objects and normalized CMake Ninja graphs match;
   debug/release native production builds pass for all four ABIs; ownership, mutex/freeze/adoption and ABI behavior are
   unchanged. Android Studio reports no errors; advisory warnings are pre-existing and token-identical. A final read-only
   staged gate confirms the complete production/configuration tuple matches the pins below with no mixed overlap,
   unrelated staged file or generated `.cxx` artifact. Only this final tracker update remains for the user to stage.
2. **FRAMEWORK/JPEG REMAINING CORRECTIONS — OPEN.** Bitmap lifetime, returned recycle, fatal/allocation partition,
   failure evidence, reference clearing, platform wiring, carrier integration and shared retention remain listed below.
   Each production slice starts with three independent plans.
3. **CONTROLLER / OBSERVATION / CLEANUP / DELIVERY D6 — FUTURE, NO ACTIVE TASK.** Do not land a standalone signal,
   drainer, registry or placeholder Controller. Retained preliminary research and unresolved planning points are below.
4. **TARGET/GL F4 — FUTURE INTEGRATION.** Replace raw TargetScope progress/receipt seams with private exact GL evidence
   only when the real Controller/GL/Target integration slice starts.

## Accepted functional checkpoints

Only current pins needed to anchor future work are retained. Rehash affected files before every new writer.

| Slice | Current accepted pins |
| --- | --- |
| Shared deadline/settlement | `OperationDeadline.kt` `0a811bf63e14a7dd4fe92d011f9b7795907f466474d6c738a5cb1b0988ef5fd7` (595); `OperationSettlement.kt` `7c643bb7d80192def414b942544b4e69920a56eed6e756baa6855d7fd724b270` (493) |
| Android Metrics | `ScreenCaptureConfig.kt` `8816033c93e987e9ebd3dc53d482a939f2092cd2f1dfb4abe47201bd6aa9842e` (298); `CaptureMetricsOwner.kt` `73f1854f6724f0569eef73b9d2300b362e1b56d0e3cafe63ea606c7b34be0cad` (461) |
| Android↔Target typed closure | `AndroidCaptureOwner.kt` `27483ee0d444b291e3690a41de16f1e44f59c3aa09feb20231ece4a40362f525` (1,021); `VirtualDisplayOperations.kt` `4270f4b788a773d15a98f72c5c64ed98797a22a665aa970ac75361dec0dec98d` (420); `TargetPorts.kt` `ee346c4294953eaf1efe2d8fc936746dca5c8c444777ba94d1c65742241e53e4` (886); `CurrentTarget.kt` `c3758ed6e66cdefb34dcba891479b2dd51f18bd6e172b7574090f02b3688ef76` (610) |
| Reconciliation | `ReconciliationOwner.kt` `96da06c1172e4d125ea83f7730bc4ca4c16b17eac1b18dadebca4feab936498c` (327) |
| Pacing | `PacingOwner.kt` `77d9265cb5ede4ed67eb176f236cfba4394b1e2eac9522759e098c97fcf851e9` (254) |
| Delivery F1/F2/F3 mechanics | `DeliveryOwner.kt` `04c24d8ec0dd8181ecbad28ae8f0570e9db809b6cea264c5183c693744a3c904` (933); `OperationDeadline.kt` pin above |
| Framework JPEG entry signals | `FrameworkBitmapOwner.kt` `c6bf078cd2f43bfac4478554c4affe4d378dfcece0c03404d1b6889f1924ab43` (341); `FrameworkJpegExecution.kt` `0e4b628d1862f4c48779ca01b6db4803bca273d1533392739ad7e9ea0db5eb42` (447); `FrameworkJpegCleanup.kt` `2396d3bdb6606ac611c5ca3fa441d0854a35a461465c4000457be76bd7e8f3d1` (281) |
| Native/JNI production | `JpegRuntimeOwner.kt` `a70e5b8b8cc2214aafe89ce7da3c0de0bb027f3008b61b708225e3f2ebaf9a4c` (1,547); `JpegRuntimeOperations.kt` `f29b5a7f39d889d6599d569efe82580a032a0ab477ba5f9bc52ad2390e2fb39a` (383); `NativeEncodeCoordinator.kt` `10faec745518df22240139c12ad0116a19cc4d4dde9eae17a6ebce2e0d9875a0` (128); `NativeJpegProcess.kt` `80daebf4fd3872fa99e5aaddda34bdf0c1c2abaa760018e55570db30aaa8aa21` (128); `NativeResultProtocol.kt` `06f5e4994a01921caaa9bf86f947c998424630493430cfd0cdefa20cfe5839b1` (162); `screen_capture_engine_jni.cpp` `acfeefe1c14575a497b697e479ad95c1953e8524cbd68d0ec718a086702c55ce` (371); `native_jpeg_runtime.cpp` `bb0e247468599d4602cb3bb16e7f3a64e121b2f301cafe8bd907eeb260c7e30c` (187); `native_jpeg_runtime.h` `b5904c361232e3aff530605f6694a6c2dfc1ec1c097355c18b428041d651ae13` (105); `CMakeLists.txt` `76e361b00eb3b7f6a97d750dc0daf4b68bce773fa6ee593341e7f04103415585` (57); `screencaptureengine.map.txt` `8f8b39754f7be5b1a3797247ddbfafd0a2897b77efe724ea6628ae43e8f04697` (7); `consumer-rules.pro` `da2bf6e5c4a8674d75344b83e3cc13ea54dc42dcef27a45ac50fb1f3ffee0137` (13); `build.gradle.kts` `8ba4808c1b4129f33e020ddcce90d9f75f089cc58cfc0d0e80f912e8416e2a34` (49) |

Current accepted implementation facts needed later:

- `OperationDeadline` atomically commits `Armed + Requested`. `Future.cancel(false) == true` is not a stop receipt:
  queued work may be generation-suppressed, while running work retains its generation until exact `Fired` or scheduler
  termination receipt.
- Only definite `RejectedExecutionException` proves scheduler non-acceptance. Other schedule/cancel throws retain
  ambiguous generation evidence. Fatal scheduling/cancellation errors preserve exact identity.
- Android owns a closed `Attached / MechanicallyDetached / null` VirtualDisplay state. Target applies first; Android
  validates exact immutable facts and identity-CASes second. Future Controller owns currentness and claim ordering.
- Reconciliation and Pacing are stateless calculations. Future Controller owns revalidation, commit, scheduling,
  currentness and policy.
- Delivery F1–F3 mechanics are closed. End-to-end Delivery remains open only through D6 below.
- All eight JPEG/Framework successful-entry paths now signal after durable entry and before same-stack outward/resource
  work. The user reviewed and committed the five-file tuple; the user-selected narrow independent closure audit was clean.

Accepted deviations/watchpoints:

- `AndroidCaptureOwner.kt` and `TargetPorts.kt` remain cohesive large single-authority files. Reassess only when real
  Controller integration removes responsibilities; do not split by widening mutable authority.
- GL currently allocates three `Ref` objects plus three capturing functions per entered frame. Revisit only after
  integration profiling or new authority evidence.
- Narrow module-internal seams are accepted where Kotlin multifile composition makes file-private access impractical.

## Future Controller / Observation / Cleanup research

This is retained preliminary work, not an active plan or writer authorization.

- No designer blocker remains for `SettlementSignal`. It is one Session-owned, prompt, nonblocking, allocation-free and
  universally nonthrowing wake. Producers publish durable facts first, release their gate, then signal.
- Controller-dispatch rejection/throw is retained by exact identity in a precreated emergency fact. One exclusive
  bounded allocation-free fail-closed turn transfers fixed unresolved roots; signal still returns to the producer.
  Leaf-domain fatal errors remain separate and are rethrown by their owning boundary.
- The wake uses exact `IDLE / RUNNING / RUNNING_DIRTY` coalescing, one precreated Runnable and the single Session
  deadline scheduler. Only a complete empty scan may return to `IDLE`.
- The call-site inventory found 107 direct/helper settlement-signal edges plus one dormant wrapper and no hidden alias or
  alternate implementation. `TargetSourceSignal.signal(generation)` is a separate nominal producer contract.
- A standalone signal/drainer is forbidden dormant authority. A live vertical landing needs real public reachability,
  `SessionController`, direct source/action slots, a complete scanner and `CleanupOwner`. Observation remains stateless
  over Controller-owned immutable snapshots and Session-owned Flow cells.
- Future three-axis planning must resolve whether `ObservationOwner` co-lands or follows in the next behavior-complete
  slice, and must adjudicate the proposed precreated dispatch-return/failure handshake that prevents a possibly-started
  Runnable from beginning useful work before an acceptance-ambiguous dispatch throw is observed.
- Do not start more Controller planning, adjudication or a writer without a separate user-approved task.

## Open Framework/JPEG obligations

The successful-entry signal correction is closed. Remaining work:

1. **Bitmap lifetime — intrinsic.** Remove `BitmapReturnedOwner`/`bitmapGate` without a replacement axis. The creation
   occurrence owns the partial shell; installed Bitmap/scratch/metadata are immutable; the encode occurrence bag is the
   sole Bitmap-use loan. Unentered cancellation returns it; entered/late/nonreturn retains it.
2. **Returned recycle — intrinsic.** Snapshot under the source gate, precreate an empty recycle occurrence unlocked,
   arbitrate on its gate, revalidate and transfer the whole owner under the source gate, then submit unlocked. Only exact
   normal recycle return plus claimed settlement permits clearing the owner slot.
3. **Allocation/fatal partition — intrinsic.** Ordinary resource exhaustion is limited to checked creation-size denial,
   direct Bitmap creation, row scratch, Bitmap compress including entered sink growth, separately Storage-owned commit
   trim/freeze and checked cumulative sink overflow. Other fatal errors are published/signalled and rethrown by identity
   after fixed nonthrowing bookkeeping. Root dispatch treats ordinary `Exception` as rejection and rethrows fatal errors.
4. **Failure evidence — intrinsic plus Storage-owned.** Replace return-path checks/new causes with closed discriminators
   and precreated evidence. Preserve exact managed OOM causes where promised; typed `NativeOutOfMemory` may map to a
   nullable public cause and never fabricates an OOME. Preserve `FrameworkTransaction.firstFatalWriteError`.
5. **Reference clearing — intrinsic with Controller completion.** Retain one exact nullable slot per owner/product/use/
   transaction obligation until currentness, use, transaction, payload and cleanup settle. Transfer unresolved roots.
6. **Platform wiring — future-owned.** Remove caller `sdkInt` at final wiring; branch on actual
   `Build.VERSION.SDK_INT`. API 24–25 performs no API-26 metadata access.
7. **Carrier integration — future Controller/GL/JPEG.** Preserve `RgbaCarrier.leaseGate` until one exact
   Controller-owned `CarrierRangeLoan` replaces it through `sessionGate -> settlementGate`. Never enter a raw range
   before `tryEnter`.
8. **Shared retention.** Clear carrier, result block and direct buffer only after exact receipt or all managed uses
   settle. The Native/JNI landing removes the former cross-return writer block/gate/loan. The encode occurrence roots
   transaction, carrier, result block and live native call through unentered, entered, late and nonreturn states.

## Native/JNI approved implementation handoff

Status: `CLOSED_PRODUCTION_STAGED_TRACKER_READY_TO_STAGE_AND_COMMIT`.

The final contract is frozen and committed in Documents 01/03/04/10/11. Current Kotlin is replacement evidence, not a
compatibility boundary.

### Target topology and binding

- Add `internal/jpeg/NativeJpegProcess.kt` with binary identity
  `io.screenstream.engine.internal.jpeg.NativeJpegProcess`. It owns only loader/bootstrap state and the stateless JNI
  facade. `JpegRuntimeOwner` retains Session mode, health, backend and carrier authority.
- Keep exact Storage identity `io.screenstream.engine.internal.EncodedStorageOwner$NativeSegmentSink` and upcall
  descriptor `adoptNativeSegment(Ljava/nio/ByteBuffer;I)V` unchanged.
- The production inventory is five Kotlin external methods: exported `nativeBootstrap` plus four registered runtime
  methods for carrier allocation, carrier free, weak-compressor query and compression. `RegisterNatives` has four
  entries. Production exports are only `JNI_OnLoad` and
  `Java_io_screenstream_engine_internal_jpeg_NativeJpegProcess_nativeBootstrap`.
- `JNI_OnLoad` is `noexcept` and version-only. Explicit receiver bootstrap after successful loading distinguishes a
  missing DSO from post-load registration poison.
- `nativeCompress` returns `Unit` with exact descriptor:

```text
(Ljava/nio/ByteBuffer;JIIIIJIIILio/screenstream/engine/internal/EncodedStorageOwner$NativeSegmentSink;Ljava/nio/ByteBuffer;)V
```

### Call-scoped native ownership

- One `WriterCapsule`, its mutex and every native segment belong to one `nativeCompress` stack frame. Remove
  `NativeWriterOwnerBlock`, writer-token inspection/initialization, residue disposition, writer-block loan/gate,
  `nativeReleaseWriterResidue` and the second cleanup JNI boundary.
- Validate the exact direct result-block address and 16-byte capacity, install an allocation-free nonthrowing finalizer,
  and arm the result channel immediately. Perform remaining lookup, pixels/sink/buffer, range/count, narrowing,
  descriptor and representation checks after arming but before capsule creation or outward work.
- The callback performs no JNI and is `noexcept`. Retain the capsule mutex because callback thread identity and
  serialization are not guaranteed. Callback failures become allocation-free sticky evidence and return `false`.
- Catch every C++ exception while the capsule remains in lexical scope. Every returning path explicitly performs
  idempotent `releaseAll() noexcept`, verifies closed state, writes the complete result and returns. The destructor is
  only an idempotent safety net; no C++ exception crosses JNI.
- Remove each segment from the owned list and free it exactly once after synchronous sink return. `releaseAll()` frees
  only the remaining list.
- Nonreturn proves no cleanup receipt: capsule/segments remain on the native stack and the exact Kotlin occurrence,
  worker, result block, transaction and carrier remain quarantined. Late return performs the same native cleanup and
  publishes only cleanup evidence through the existing settlement identity.

### Exact result protocol

Kotlin initializes a native-order 16-byte direct block to `-1/-1`. Native uses fixed-offset field-wise `memcpy`, never a
struct or potentially unaligned `int64_t*`:

```text
offset 0: nativeStatus
offset 8: nativeProducedByteCount
```

Native writes count first and status last after verified cleanup. Status-last is a semantic completion marker, not a
memory fence. The same worker copies both scalars into managed return evidence; settlement-gate publication supplies
cross-thread visibility. Controller never reads the direct block.

```text
Pending                          = -1
NativeTransferComplete           = 0
SafeCompressorAllocationFailure  = 1
NativeOutOfMemory                 = 2
InternalFailure                  = 3
JavaThrowable                    = 4
```

Let `P = nativeProducedByteCount` and `M = NativeTransaction.byteCount`.

| Final status | Required `P` | Required `M` | Pending throwable |
| --- | ---: | ---: | --- |
| `NativeTransferComplete` | `P > 0` | `M == P` | none |
| `SafeCompressorAllocationFailure` | `0..Int.MAX_VALUE` | `M == 0` | none |
| `NativeOutOfMemory` | `0..Int.MAX_VALUE` | `M == 0` | none |
| `InternalFailure` | `0..Int.MAX_VALUE` | `0 <= M <= P` | only exact pre-adoption/malformed path |
| `JavaThrowable` | `P > 0` | `0 <= M <= P` | required from adoption boundary |

- `NativeTransferComplete` proves native production, adoption and cleanup, not Storage commit, currentness or
  publication.
- Native allocation/representation failures eligible for `NativeOutOfMemory` occur before adoption. Typed native OOM
  maps to `ResourceExhausted` with nullable cause and never fabricates OOME. Unexpected `std::bad_alloc` after adoption
  is `InternalFailure` with `0 <= M <= P`.
- `JavaThrowable` is valid only for a pending throwable from `NewDirectByteBuffer` or synchronous
  `NativeSegmentSink.adoptNativeSegment` and verified closed native state.
- Invalid/missing result channel stays unarmed: create no capsule or outward work. Normal returned `Pending` is malformed
  `InternalFailure`; pending `Exception` is exact-cause `InternalFailure`; every other pending throwable is rethrown.
- Armed preflight failure writes `P = 0` then `InternalFailure` without capsule/compressor/adopter. A post-arm lookup
  throwable remains pending with `InternalFailure`; it is never `JavaThrowable`.
- Unknown status/count or impossible status/throw/`M`/`P` pairing is malformed `InternalFailure` while fatal identity is
  preserved.

### Throwable partition

- Valid `JavaThrowable` plus exact OOME becomes `ResourceExhausted` with the same cause.
- Valid `JavaThrowable` plus `Exception` becomes exact-cause `InternalFailure`.
- Valid `JavaThrowable` plus every other throwable completes fixed allocation-free bookkeeping and rethrows the same
  object.
- `InternalFailure`/malformed plus `Exception` remains `InternalFailure`; with every other throwable, fixed bookkeeping
  precedes identical rethrow. Cleanup ambiguity outranks ordinary OOME normalization.

### Current production receipt

- The Kotlin/C++/CMake/Gradle tuple is implemented, manually reviewed and staged. `EncodedStorageOwner.kt` and
  `AndroidManifest.xml` were not changed. The capsule mutex required by `NJPEG-080` remains in place.
- Closure confirmed and corrected F1 fatal-return publication, its fallback-evidence path and F2 lexical C++ exception
  containment. Mandatory three-way candidate dispositions are complete. Fresh adjudication rejected F3 and forbids an
  F3-driven runtime refactor. Final independent Kotlin and deep C++/JNI audits are clean.
- Debug/release Kotlin compilation, native builds for all four ABIs and AAR assembly completed successfully. Artifact
  scans found exactly four production DSOs, the two permitted exports, weak undefined `AndroidBitmap_compress`, direct
  `DT_NEEDED: libjnigraphics.so` and `0x4000` LOAD alignment for every ABI. Old writer-token/residue/release mechanics are
  absent.
- The final C++ formatting audit found identical preprocessor tokens, direct objects and normalized CMake graphs, clean
  production builds and unchanged lifecycle/ABI semantics. Android Studio reports no errors; advisory warnings are
  pre-existing. The staged readiness gate matches every accepted pin, has no production overlap, unrelated staged file,
  generated `.cxx` artifact or whitespace error.
- Tests were neither created, changed nor run.

## Remaining cross-domain obligations

- **Delivery D6:** Future Controller/Cleanup owns Storage consume invocation, Delivery acknowledgement, currentness,
  counters, terminal/unsubscribe, exact root attachment, fact claims and post-claim reference reduction. The intended
  sequence is `DeliveryOwner.releasedLeaseForStorageConsumption` ->
  `EncodedStorageOwner.consumeReleasedLease` ->
  `DeliveryOwner.acknowledgeStorageLeaseConsumption`. Do not add a placeholder Delivery cleanup root.
- **Observation:** It remains stateless/unlocked over Controller snapshots and Session-owned Flow cells. The exact
  diagnostic-allocation OOME boundary remains a future Controller planning question, not a designer blocker.
- **Cleanup:** `CleanupOwner` must co-land with real Controller scheduler/root types, never a generic registry or
  callback placeholder.
- **Target/GL F4:** Remove raw TargetScope progress/normal/receipt getters when GL returns private exact evidence after
  real SurfaceTexture/OES work and Target consumes only the settled result.
- Former F1–F3, F5 and F6 are closed by the accepted typed Android↔Target/Delivery work. Do not keep or reopen their old
  tracker wording without new evidence.
