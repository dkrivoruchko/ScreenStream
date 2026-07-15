# Screen Capture Engine — Current Implementation Tracker

## Authority

The frozen implementation authority is, in order:

1. design/01_design.md
2. design/02_architecture.md
3. design/03_verification.md
4. design/04_gate_a.md
5. design/05_gate_b_inputs.md
6. design/06_implementation_design.md
7. design/07_private_constants.md
8. design/08_test_implementation_map.md
9. design/09_gate_b.md

Documents 01–09 are architectural authority. Kotlin and Git history are implementation evidence. If a correct
implementation cannot fit Documents 01–09, stop and report a designer/user blocker; do not invent a workaround or
silently change authority.

## Permanent working rules

- Root is the exclusive tracker writer, status owner, and gate integrator. Root personally reads, audits, and updates
  this tracker. Technical implementation, research, code audit, diagnostics, builds, tests, and verification are
  delegated to fresh subagents.
- Subagents never edit this tracker, announce closure, delegate, or mutate Git/index unless explicitly authorized.
- Preserve unrelated work. Do not stage, unstage, commit, reset, checkout, revert, or otherwise change index/history
  without explicit user instruction.
- Every technical task first checks current official primary guidance. Android uses official Android/AOSP sources;
  Kotlin/IDE uses official Kotlin/JetBrains; Java/JNI uses official Oracle/OpenJDK/JNI. Record sources and disposition.
- Accepted Kotlin is a high-confidence checkpoint, not frozen code. Bounded reopening, refactoring, extension, or
  rewrite is allowed when evidence requires it and Documents 01–09 remain satisfied.
- Acceptance follows bounded behavioral verification slices, not source-file completion. One writer owns each file at
  a time; overlapping writers never edit the same file concurrently.
- Final passive declarations may land before their later behavioral slice only when they have permanent domain
  ownership and no placeholder behavior, mutable authority, lifecycle transition, owner arbitration, platform call,
  fake health/presence projection, or incomplete execution path.
- Physical layout follows cohesion and one-way dependency. Roughly 800 lines is a review prompt, not a hard limit.
  Never create package-per-type fragmentation or split a cohesive mutable owner by widening mutable authority.
- Every writer uses JetBrains/Android Studio formatting and diagnostics, a focused IDE build, proportional forced
  Gradle compilation, naming/spacing review, and two explicit self-reviews.
- Critical closure uses independent audits and bounded cross-review. Every finding needs exact authority/source/line
  evidence and at least one concrete solution. Peers confirm or refute both finding and solution. Material disagreement
  gets a fresh adjudicator before a writer contract is selected.
- A confirmed critical defect gets at least three independent read-only solution proposals, peer cross-review, and
  fresh adjudication before correction code.
- After every writer handoff, independent audit waits for user manual review and explicit authorization.
- Tests begin only after matching production code exists. Do not launch an emulator without user consent; prefer a
  connected physical device when runtime verification becomes applicable.
- Preserve checked sizes/narrowing, hard platform limits, exact identities, bounded work, reuse, minimal copies, exact
  cleanup, and structural ownership. Do not use Any, fake health/presence booleans, compatibility interfaces, duplicate
  topology wrappers, raw handles as ownership proof, fabricated receipts, incomplete implementations, or predictive
  memory accounting.

## Repository checkpoint

- HEAD: 0e3a89d8 (part 12).
- Gate B remains implementation-authorized; no known Documents 01–09 conflict.
- The post-user-review mechanical handoff is closed. Confirmed unresolved findings: P0/P1/P2 0/0/0.
- User manual review is complete. Some files received user formatting corrections.
- The complete final design/Kotlin snapshot, including the authorized EOF cleanup, is staged by the user. Root did not
  alter the index. Only this tracker is currently unstaged and must be staged by the user before commit.

Current expected worktree:

- staged: design/06_implementation_design.md, design/08_test_implementation_map.md, design/09_gate_b.md;
- staged: internal/AndroidCaptureOwner.kt, CaptureMetricsOwner.kt, EncodedStorageOwner.kt, FrameworkJpegOwner.kt,
  JpegRuntimeCore.kt, JpegRuntimeOperations.kt, JpegRuntimeOwner.kt, TargetOwner.kt,
  internal/settlement/OperationDeadline.kt, and the detected rename of legacy internal/OperationSettlement.kt to
  internal/settlement/OperationSettlement.kt;
- unstaged: this tracker;
- untracked: none in the active handoff.

## Accepted behavior checkpoints

| Source | Accepted or independently reclosed SHA-256 | Current meaning |
| --- | --- | --- |
| ScreenCaptureParameters.kt | 7485bc5e76b4dc8ca2b5b6983304924c24f773d2df8f30df7f7940e96fc65160 | Accepted. |
| ScreenCaptureOutput.kt | 923f0e0d8250a7e0b83c74f3f25a2add5f421628029f3ae188b34a2e466bdac2 | Accepted. |
| ScreenCaptureObservations.kt | 3a2c4c708a68e6cec8280ff7c0bb7d38601a37534da53e4b28e2ddb057b0449e | Accepted. |
| legacy internal/OperationSettlement.kt | b99b5b3a060c5a811e95d6462075d109ebb507373d208e69b7040d166dba7a12 | Accepted behavior baseline for the current relocation. |
| internal/TargetOwner.kt | 11351ebb7a3375a9b1b508ff5fe4990e39ca6e34240e016e0d4593e33e806a7f | Accepted behavior; later PreparedTarget reopen remains. |
| ScreenCaptureConfig.kt | 61f37d061b67f2d5a76b9784ff61471cd850e141c8bf74156b54471af8fdd086 | Accepted. |
| internal/CaptureMetricsOwner.kt | ddf0550f36e8f820282d363f948cfffd3fad7e973ae337d677a5b4a72c256b7a | Accepted behavior. |
| internal/EncodedStorageOwner.kt | c4d47b1c476a6bc5b24c9675e7653cec3525838c4a38b29719ea9b6035193747 | Fatal/carrier correction independently reclosed 0/0/0. |
| internal/AndroidCaptureOwner.kt | 9fa48d10b56963326ca1d6768858d89de6581e275efb14a0c1bb94099b8f23d1 | Accepted behavior; later typed Target boundary reopen remains. |
| internal/JpegRuntimeOwner.kt | 3f7b213c29e3ce98f77863d4196312fd392bcb350507bd7856e50d28fb635b64 | Accepted monolithic behavior baseline for the current split. |
| internal/FrameworkJpegOwner.kt | bd862f29773fce902604266935a2eb781f7f45c3aebf47181cb13b7ddc74fbe5 | Fatal/carrier correction independently reclosed 0/0/0; not full Document-08 closure. |

The accepted SHAs prove the behavior baselines; the closed current-byte snapshot and its independent evidence are
recorded below.

## Closed post-review mechanical handoff

Status: MECHANICAL_LAYOUT_CLOSED_COMMIT_CHECKPOINT_PENDING_USER_STAGE_TRACKER_AND_COMMIT.

Snapshot observed by root on 2026-07-15:

| Current path | SHA-256 | Lines |
| --- | --- | ---: |
| internal/AndroidCaptureOwner.kt | f29a68db833e782d826dba8a31c4142d8aee520c06cf1fead13a92d427eb3b44 | 952 |
| internal/CaptureMetricsOwner.kt | 114f4165848bde0a807b5390371986c4e29fe7fc9ff83ad39528acc81962abcf | 564 |
| internal/EncodedStorageOwner.kt | f52948740742a86fc42811f808d987ada5148f034d4f3dd6305c3008483aa8ad | 617 |
| internal/FrameworkJpegOwner.kt | 9dededada52c5b128ffadd8aa7260e67e012f37bef1477ee1e42dbb9650a151b | 1,476 |
| internal/JpegRuntimeCore.kt | f194ed231370f3ec6235dd54593ec255c916d69beca42363fbf25712af451721 | 483 |
| internal/JpegRuntimeOperations.kt | ccc58af229ec8200d80714838d25058f7909555a9c76ac8391c518168debe569 | 487 |
| internal/JpegRuntimeOwner.kt | 207e8e94f994c8772d95cc3017dea729d7b6dcbe4129589eb725d15c7d1acd2a | 2,018 |
| internal/TargetOwner.kt | 41747e9ea342eb2bd8d9ebfe2f4afa54e3388e8de3813a5654df1b5131ea8bba | 484 |
| internal/settlement/OperationDeadline.kt | c46f3d7d6a16f9542ab7a038452a0b919b052068c747adeeeb0f189d67f4fd87 | 389 |
| internal/settlement/OperationSettlement.kt | b05ca6d3f511c7a6901b8140de48b4bb14d640ce4a6c96beb677e01f305db25d | 428 |

Required mechanical contract:

- Relocate the accepted settlement kernel into internal/settlement/OperationDeadline.kt and
  internal/settlement/OperationSettlement.kt. Owners depend on settlement; settlement depends only on Kotlin/JDK.
- Keep JPEG in io.screenstream.engine.internal and split declarations into JpegRuntimeCore.kt,
  JpegRuntimeOperations.kt, and JpegRuntimeOwner.kt. Core owns products, carriers, leases, finite identities,
  carrier-free native-free operations, and JpegIoOperation. Operations owns typed preparation/replacement/encode
  records and call-block loans. Owner retains all mutable Session JPEG/JNI authority.
- The split changes paths/imports/formatting only. It must preserve every declaration, modifier, signature, constant,
  body, ownership rule, runtime policy, and accepted behavior.
- Do not move or reshape JpegRuntimeOwner.NativeBridge or EncodedStorageOwner.NativeSegmentSink. Their binary names,
  six NativeBridge native descriptors, and adoptNativeSegment(ByteBuffer, int) descriptor are frozen.
- Do not add compatibility shims, wildcard imports, reverse Core-to-Operations dependency, broader visibility, a
  second manager/executor authority, or a behavioral acceptance claim.
- Preserve the independently reclosed Framework fatal-sink/carrier-exit contract: first exact non-OOM write Error
  retention, exact rethrow precedence around Bitmap.compress, and single exact-range exit with the approved transfer/
  exit precedence. No manager/adapter/health/topology/executor authority is added.

### Closure evidence and exact next action

- Mechanical equivalence audit: 0/0/0. Settlement is exactly 32/32 and JPEG exactly 40/40 declarations against the
  accepted monoliths after authorized package/import/formatting normalization. Bodies, signatures, modifiers,
  constants, visibility, one-way DAG, ownership, and JpegIoOperation placement are preserved.
- Platform/JNI/API audit: 0/0/0. Official Android/AOSP, Kotlin/JetBrains, Oracle/OpenJDK/JNI guidance was checked. API
  guards use VERSION_CODES; checked narrowing/platform limits, fatal/cleanup precedence, exact-range resolution, six
  NativeBridge descriptors, and NativeSegmentSink/adoptNativeSegment binary identity are preserved. Forced Kotlin
  compilation and lint passed.
- Compile/integration audit: 0/0/1 only for surplus EOF blank lines. Studio errors-only diagnostics were zero for all
  ten files; focused Studio build and forced compileDebugKotlin passed; inventories, imports, dependencies, and
  callsites matched. The user authorized direct removal without another review cycle. Only those EOF lines were
  removed, every file now has one terminal LF, and working-tree git diff --check is clean.
- No P0/P1 finding, substantive P2, authority conflict, solution disagreement, or adjudication need remains.

Sole next action: root stops. The user stages this tracker, verifies the desired index, and commits the checkpoint.
Root does not start passive declarations or any later slice before the user resumes and explicitly authorizes
continuation.

## Accepted future dependency order

No future slice starts until the user completes the commit checkpoint and explicitly authorizes continuation.

1. Final passive TargetContracts.kt and GlPipelineContracts.kt declarations only. No live/mutable owner, outward call,
   Any, placeholder interface, raw-handle proof, fake health/presence Boolean, duplicate topology wrapper, or
   GlRenderTargetOwner. Stop for user review before independent audit.
2. Fresh Target gate peer review, then bounded PreparedTarget implementation in TargetOwner.kt. Reuse the construction
   settlementGate as the sole publication/partial/disposition/settlement/owner-bag/transfer gate; preserve lock order
   sessionGate then construction gate, exact same-stack adoption, and no outward call under gates.
3. After Target acceptance, bounded AndroidCaptureOwner.kt reopen: retain exact installed CurrentTarget and use
   Target-owned typed safe-no-producer evidence without fabricating cleanup receipts. Preserve accepted Handler/lane/
   callback/no-entry/cleanup behavior.
4. Complete GL behavior with concrete referential GlRenderTargetOwner, then Reconciliation against exact installed
   Target/GL/JPEG/Framework owners, then controller/cleanup/delivery integration.
5. Add matching Document-08 tests only after each production slice exists; native/build/device execution remains
   deferred and separately authorized.

The designer-approved Reconciliation ordering issue is closed: passive immutable contracts may precede behavioral
owners, but live topology is evaluated only after the real concrete owner exists. The authoritative detailed contracts,
GL inventory, verification maps, and gate obligations remain in Documents 06, 08, and 09 rather than being duplicated
here.
