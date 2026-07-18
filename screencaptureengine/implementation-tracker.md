# Screen Capture Engine — Current Implementation Tracker

## Authority

Documents `design-next/01-authority-router.md` through `design-next/12-domain-delivery-observation.md` are the sole
normative implementation authority, in router order. `design-next/13-future-evolution.md` is nonnormative. The previous
`design/01`–`09` package is not authority.

This tracker contains operational rules, current accepted implementation state, open obligations and retained research
needed by future work. It is not normative. If interruption, compaction or document updates make it stale, current
Documents 01–12 win and this tracker is corrected before more work starts. Kotlin is implementation evidence, not
authority.

Every Markdown file except this tracker is frozen. Only a direct user decision may authorize a separately bounded
documentation task.

## Permanent working rules

- Root is exclusively an orchestrator, the sole tracker writer, status owner and gate integrator. A subagent may read the
  tracker only as required by its bounded task; it never writes it. Root does not perform technical implementation,
  audit, research, diagnostics or production builds unless the user explicitly asks for a bounded root-only check.
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
- Technical agents actually browse current official primary guidance from Android/AOSP, Kotlin/JetBrains,
  Oracle/OpenJDK/JNI or Khronos as applicable. Every handoff records exact title/URL, conclusion and disposition. Frozen
  project Markdown remains normative product authority; external sources validate mechanism and platform best practice.
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
- Prefer an existing exact platform, standard-library, protocol or project-domain constant over an equivalent raw numeric
  literal. Do not invent named constants for self-evident local sizes, offsets, sentinels, counts, formula coefficients or
  frozen numeric protocol values when no authoritative reusable constant exists.
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

## Current checkpoint

- All known pre-integration production corrections are closed. The complete current production candidate is staged; its
  staged and working bytes match, independent closure is CLEAN, and no known production finding remains.
- Forced debug/release Kotlin compilation and debug/release assembly, including all four Native ABIs, are clean. Tests
  were not created, changed or run.
- No technical agent or writer is active; root remains the sole tracker writer.

## Current accepted production pins

Only pins that anchor future work are retained. Rehash every affected file before a new writer.

| Slice | Accepted pins |
| --- | --- |
| Shared deadline/settlement | `OperationDeadline.kt` `0a811bf63e14a7dd4fe92d011f9b7795907f466474d6c738a5cb1b0988ef5fd7` (595); `OperationSettlement.kt` `7c643bb7d80192def414b942544b4e69920a56eed6e756baa6855d7fd724b270` (493) |
| Android metrics | `ScreenCaptureConfig.kt` `8816033c93e987e9ebd3dc53d482a939f2092cd2f1dfb4abe47201bd6aa9842e` (298); `CaptureMetricsOwner.kt` `73f1854f6724f0569eef73b9d2300b362e1b56d0e3cafe63ea606c7b34be0cad` (461) |
| Android↔Target typed closure | `AndroidCaptureOwner.kt` `27483ee0d444b291e3690a41de16f1e44f59c3aa09feb20231ece4a40362f525` (1,021); `VirtualDisplayOperations.kt` `4270f4b788a773d15a98f72c5c64ed98797a22a665aa970ac75361dec0dec98d` (420); `TargetPorts.kt` `ee346c4294953eaf1efe2d8fc936746dca5c8c444777ba94d1c65742241e53e4` (886); `CurrentTarget.kt` `c3758ed6e66cdefb34dcba891479b2dd51f18bd6e172b7574090f02b3688ef76` (610) |
| Reconciliation/Pacing | `ReconciliationOwner.kt` `5404665bd84e62710f35195d13ae35100afa36bc8649532d5f98021b39132c54` (325); `PacingOwner.kt` `77d9265cb5ede4ed67eb176f236cfba4394b1e2eac9522759e098c97fcf851e9` (254) |
| Delivery mechanics | `DeliveryOwner.kt` `04c24d8ec0dd8181ecbad28ae8f0570e9db809b6cea264c5183c693744a3c904` (933) |
| Legacy platform cleanup | `ScreenCaptureStartupTransaction.kt` `deba4875b94d7d9a566affc421ede585bc7f5f7abca077e013be2bdbc164eade` (453) |
| Framework JPEG current | `FrameworkJpegContracts.kt` `2ed1ee0735095f1b777a2243de8fb3f48143c1fa2d699c8f28f3299e2d6d477e` (321); `FrameworkJpegOwner.kt` `6b0f4a57d8028700b0903b2c5f81085a3eb60c17f1d370f1f46c426aef20aba3` (330); `FrameworkBitmapOwner.kt` `1502022eabf44bcd98def10bc9edcdec8cf09f2c8ef793380ff41308705e8844` (159); `FrameworkJpegExecution.kt` `7050542ebdb03ac12425600eaa112bc6b7ae0f10ffdc85962df1e76e1ef1b1d3` (502); `FrameworkJpegCleanup.kt` `f6b50e104248210b7f98a2e99b2fa7c48d5a83c43a6da4c52162289c1bece159` (289) |
| Managed Native/carrier current | `JpegRuntimeOwner.kt` `85f47bc162d03f1a9700024653ec22c43af5e13693a6bb05cb061c9678365be9` (1,718); `JpegRuntimeOperations.kt` `44dce690dfaa5c9398c16f6865ac8342f5426f16732183dd30466d0b7696add1` (479); `JpegCarrierLifecycle.kt` `e3adc8fd7a33dd65deff3f51ebbdcb86bf27f6a995773a297073542f57674c6f` (500); `NativeEncodeCoordinator.kt` `32c62e3285d99b0c7d223b3ef9180073233835af68f647659a094a739f3a46b0` (174); `NativeResultProtocol.kt` `53d6aa09573179a1130978df9042139fc7e4e4fd59f4144de922a2b1ae579788` (171); `NativeJpegProcess.kt` `80daebf4fd3872fa99e5aaddda34bdf0c1c2abaa760018e55570db30aaa8aa21` (128) |
| Encoded Storage current | `EncodedStorageOwner.kt` `973fe656b289b2ac239ac1b90425b40c89101e6f61083dca5d2414c7c6033784` (623) |
| GL current | `GlPipelineContracts.kt` `df93456525c0650631ac3d46f218c7c85539608727c3e03a3a43d1fc423e3368` (190); `GlCleanup.kt` `11146159057bbe5417a941fffc99c27019373f970964a1c4b5eaf58d854b431f` (577); `GlFramePipeline.kt` `f33ba377c65ee75d360944fa78bc7b150302d883bc33c9647aed3633bdec6d1d` (362) |
| Native C++/package | `screen_capture_engine_jni.cpp` `18196d84a192a672bdc6dd50f4730467ba9370f95bb3e1dff4460ec1459eefb1` (371); `native_jpeg_runtime.cpp` `bb0e247468599d4602cb3bb16e7f3a64e121b2f301cafe8bd907eeb260c7e30c` (187); `native_jpeg_runtime.h` `edaba48044b514f38b45c0f4a63e643095aa3efb3b9eca83ab17c18e6e388f98` (106); `CMakeLists.txt` `76e361b00eb3b7f6a97d750dc0daf4b68bce773fa6ee593341e7f04103415585` (57); `screencaptureengine.map.txt` `8f8b39754f7be5b1a3797247ddbfafd0a2897b77efe724ea6628ae43e8f04697` (7); `consumer-rules.pro` `da2bf6e5c4a8674d75344b83e3cc13ea54dc42dcef27a45ac50fb1f3ffee0137` (13); `build.gradle.kts` `8ba4808c1b4129f33e020ddcce90d9f75f089cc58cfc0d0e80f912e8416e2a34` (49) |

## Current queue

1. **CHECKPOINT — USER ACTION.** Stage this tracker and create the planned commit checkpoint.
2. **LARGE CONTROLLER / OBSERVATION / CLEANUP / DELIVERY INTEGRATION — NOT STARTED.** Authority packets: Controller/lifecycle,
   Session scheduler, Cleanup integration and Delivery/observation in router §5. Requires a separate user-approved task
   and three fresh independent plans from current Docs01–12.
3. **CARRIER INTEGRATION — FUTURE CONTROLLER/GL/JPEG SLICE.** Authority: `NJPEG-020`, `NJPEG-030`, `GL-060` and routed
   Controller ownership in Docs01/03/05/08/10.
4. **TARGET/GL EVIDENCE INTEGRATION — FUTURE.** Authority: Target and GL production packets in router §5.

No additional standalone small pre-integration production task is currently known.

## Retained research not present in frozen authority

This preliminary evidence supplements but does not replace Docs01–12. It is not an active plan or writer authorization.

- Future three-axis planning must resolve whether `ObservationOwner` co-lands or follows in the next behavior-complete
  slice, and must adjudicate the proposed precreated dispatch-return/failure handshake that prevents a possibly-started
  Runnable from beginning useful work before an acceptance-ambiguous dispatch throw is observed.

## Implementation watchpoints not present in frozen authority

- `AndroidCaptureOwner.kt` and `TargetPorts.kt` remain cohesive large single-authority files. Reassess only when Controller
  integration actually removes responsibilities.
- Narrow module-internal seams are accepted where Kotlin multifile composition makes file-private access impractical.
