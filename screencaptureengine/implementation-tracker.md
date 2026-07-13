# Screen Capture Engine — Final Gate-B Tracker and Handoff

## Authority

The closed implementation package is:

1. `design/01_design.md` — product behavior and public API;
2. `design/02_architecture.md` — architecture, algorithms, ownership, concurrency, and platform rules;
3. `design/03_verification.md` — acceptance scenarios, checks, and high-risk traces;
4. `design/04_gate_a.md` — Gate-A decision record;
5. `design/05_gate_b_inputs.md` — mandatory Gate-B inputs and completion criteria;
6. `design/06_implementation_design.md` — concrete implementation structure and technical bindings;
7. `design/07_private_constants.md` — sole numeric authority for private Gate-B constants;
8. `design/08_test_implementation_map.md` — executable-test layout, evidence boundaries, vectors, tolerances, and traceability;
9. `design/09_gate_b.md` — Gate-B decision record.

Documents 01–05 remain the governing design authority. Documents 06–08 bind their implementation and test
realization. Document 09 records the Gate-B verdict. Earlier revisions, previous handoffs, Git history, agent
reports, and existing implementation are not architectural authority.

## Permanent working rules

- The primary/root agent is the sole tracker writer and gate-verdict owner. Subagents never edit this file or
  announce a gate verdict and never delegate to nested agents.
- Documents 01–09 are closed and frozen. Any design change requires the user's explicit approval and a bounded
  correction plus fresh independent review.
- Every public/product decision and every private numeric constant requires explicit user approval.
- Existing Kotlin, C/C++, Gradle, tests, and Git history remain untrusted implementation hints only. They do not
  override Documents 01–09.
- Memory efficiency remains structural: checked sizes and narrowing, hard platform/backend limits, reuse, minimal
  copies, exact ownership, and cleanup. Predictive memory accounting is forbidden.
- Repository-level AGP/Kotlin-plugin/dependency-resolution/version-catalog/buildscript/wrapper wiring is Android
  project setup outside this package. The approved external baseline remains binding without prescribing that root
  wiring.
- Preserve unrelated work and the user's index. Do not stage, commit, reset, checkout, revert, or otherwise mutate
  Git state without explicit instruction.
- Kotlin, C/C++, Gradle integration, builds, executable tests, IDE, emulator, and device work remain frozen until
  the user separately and explicitly authorizes implementation.

## Final status

- **Gate A:** `READY_FOR_GATE_B`.
- **Gate B:** `READY_FOR_IMPLEMENTATION_AUTHORIZATION`.
- **Implementation:** `FROZEN` pending separate explicit user authorization.
- **Date:** 2026-07-13.
- **Open P0/P1/P2:** `0/0/0`.
- **Placeholders or unresolved product decisions:** none.
- **Decision record:** `design/09_gate_b.md`, independently reviewed `GATE_B_RECORD_READY`, P0/P1/P2 `0/0/0`.

Gate B performed no implementation, build, executable test, IDE, emulator, device, performance, release, or
runtime validation. In particular, it does not claim execution of an API 24–29 native weak-guard path or an API
30+ production weak-address-null path. The post-implementation physical-device smoke check remains later work.

## Closed implementation bindings

- External baseline: API 24–37, Kotlin 2.4.0, kotlinx-coroutines 1.11.0, JVM 17, AGP 9.1.1,
  NDK 28.2.13676358, ABIs `armeabi-v7a`, `arm64-v8a`, `x86`, and `x86_64`, plus one nonpublished same-module
  `nativeTest` build type. Only release is published; the test JNI library is absent from debug/release.
- Kotlin production layout: five public files and fourteen files in the single flat
  `io.screenstream.engine.internal` package, exactly as listed in Document 06. Paths in Documents 06–08 are
  relative to their declared roots.
- Execution: controller on `Dispatchers.Default`; metrics, JPEG, and delivery use three distinct non-owned
  per-Session `Dispatchers.IO.limitedParallelism(1)` views. Android HandlerThread, GL executor, and per-Session
  scheduler remain private. Non-owned views are never closed, cancelled, or quarantined.
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
- Only preterminal incompatible-`NativeMallocCarrier` free uses the existing
  `jpegEnteredOperationSafetyNanos`; terminal-origin or terminal-converted free remains no-watchdog cleanup.
- Production-native runtime evidence is honest: `N-JPEG` covers the feasible eligible API30+ nonnull executing-ABI
  path; managed seams cover managed unavailable classifications; `P-PKG` covers structural weak guard/null/no-hook
  and artifact obligations without claiming production execution of unavailable branches.

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

## Final review closure

- The required simplicity, CPU/GPU/memory-efficiency, and correctness/implementability waves completed with
  independent whole-package reviews.
- Every material finding was independently verified, explicitly dispositioned by the user, corrected by a sole
  writer, and freshly closed.
- The final corrected GL-limit, atomic-pending-bit, carrier-free-deadline, and native-evidence slices completed
  fresh closure reviews with P0/P1/P2 `0/0/0`.
- The Gate-B decision record completed two writer self-reviews and a fresh independent record review with
  P0/P1/P2 `0/0/0`.

## Next handoff

Gate B is complete. There is no remaining Gate-B blocker.

The next action is a separate user decision: either keep implementation frozen or explicitly authorize
implementation against Documents 01–09. No implementation action is implied by the Gate-B verdict.

The root did not alter staging or the index. The worktree contains the current final documents; previously staged
versions may differ and must not be assumed current until the user chooses to update the index.
