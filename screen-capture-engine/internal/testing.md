# Testing and verification

This guide defines verification obligations and their direct oracles. Run commands from the repository root and use the smallest environment that can observe the contract.

## Commands and prerequisites

Gradle must be able to provision the configured JDK 21 runtime. Host-native checks require Android SDK CMake 4.1.2, Ninja, and Clang; they run with AddressSanitizer and UndefinedBehaviorSanitizer.

```shell
./gradlew :screen-capture-engine:testDebugUnitTest
./gradlew :screen-capture-engine:testHostNative
./gradlew :screen-capture-engine:check

./gradlew :screen-capture-engine:assembleDebugAndroidTest
```

`testDebugUnitTest` runs JVM and Robolectric tests; `testHostNative` runs host-C++ tests; `check` includes both routine paths. `assembleDebugAndroidTest` compiles and packages instrumentation only; it is not device-runtime evidence.

## Environment and evidence boundaries

- JVM and Robolectric tests cover deterministic values, state transitions, ownership, injected schedules, and fault mapping at exercised seams. They do not prove real framework, graphics-driver, packaged-JNI, or target-ABI behavior. See Android's [local-test](https://developer.android.com/training/testing/local-tests) and [Robolectric](https://developer.android.com/training/testing/local-tests/robolectric) guidance.
- Host-C++ tests cover exercised native protocol, bounds, cleanup, and sanitizer behavior. They do not prove Android ABI packaging, registered-JNI loading, Android Bitmap behavior, or target-device execution.
- The matrix's `Executable` method requires a direct test oracle; `Inspection` requires a bounded source or build-configuration check. Neither method labels nor markers establish coverage or a passing result. Executable coverage requires a contributor that directly asserts the contract. Instrumentation source and assembly describe procedures, not device results. Device, GPU, artifact, and external-consumer evidence must be established separately.

## Traceability markers

Executable evidence uses a language-neutral source comment with exactly one verification ID per line:

```kotlin
// Verification: API-03
// Verification: SES-01
@Test
fun concurrentStartLoserIsRejected() {
    // ...
}
```

- Put a marker immediately above the narrowest contributing test or C++ test function. Use a class-level marker only when every test in that class contributes to the same contract.
- Repeat an ID at every contributing scope and stack separate marker lines when one test contributes to multiple contracts. Do not add suffixes.
- Mark direct oracles, not fixtures, mocks, helpers, production code, registration tables, or `main()`.

Find contributors with `rg -n -F '// Verification: <ID>' screen-capture-engine/src/test screen-capture-engine/src/androidTest`. Every marker must name a listed contract.

## Contract-test rules

- Document non-obvious scheduling controls, injected faults, and forbidden observations close to the affected tests. Assert product values, outcomes, immutable data, ownership, resource settlement, and documented ordering.
- Fakes, mocks, schedulers, and harnesses may arrange input, timing, or failure, but their incidental calls and structure are not the verdict. Prefer an existing faithful seam and do not add production seams solely for observation.
- For competing actions, assert the permitted winner and the invariant preserved by either winner; do not promise every interleaving.
- Use coroutine-test dispatchers and virtual time only to arrange execution; assert product effects rather than dispatcher or `Job` internals. Follow Android's [coroutine-test guide](https://developer.android.com/kotlin/coroutines/test) and the current [`kotlinx-coroutines-test` API](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-test/). Do not use sleeps or scheduler-step counts as proof.
- Task acceptance, timeout, terminal state, diagnostic emission, reference release, or garbage collection does not prove callback return or resource settlement. Do not observe private structure unless it is itself a maintained contract.
- Prefer the smallest representative case set that distinguishes the contract. Avoid exhaustive race or input matrices and implementation-specific assertions that add no independent product evidence.

## Reproducible image fixtures and tolerances

### Raw RGBA fixture

Generate this top-down, opaque `5 x 3` RGBA fixture in test code; do not add it to production assets:

```text
#FF0000  #B34D26  #000000  #00FFFF  #00FF00
#FF00FF  #404040  #808080  #C0C0C0  #FFFFFF
#0000FF  #7030B0  #26994D  #008080  #FFFF00
```

The source row and total byte ranges are exactly 20 and 60 bytes. `IMG-01` must drive the real listener, Target, OES texture, GLES renderer, and readback path when the producer already equals the Target; its independent CPU oracle implements the documented geometry, bilinear weights, retained-neighbour clamping, quantization, and grayscale rules and compares every output pixel and exact alpha. The 29 raw-render cases are 27 Full cases, one producer-already-target Downscaled case, and one provisional-Full case. They preserve LeftHalf columns 0-1, RightHalf columns 2-4, crop `(1,0,1,1)` to `3 x 2`, every rotation/mirror combination including non-square 90-degree cases, `ScaleFactor(2.0)` to `10 x 6`, Stretch to `8 x 8`, and AspectFit to `8 x 5`. Four Full regressions additionally cover LeftHalf at scale 2, crop `(1,0,1,1)` at scale 2, that crop rotated 90 degrees and horizontally mirrored, and single retained pixel crop `(2,1,2,1)` at scale 2. Independent literal edge and interior anchors distinguish excluded-neighbour bleed from vertex-endpoint compression; the single-pixel case exercises equal minimum and maximum bounds. For Full identity-grid cases the oracle clamps each integer neighbour to the retained rectangle without changing its original weight; the Downscaled case clamps to the whole Target.

The real Android procedure verifies dimensions, carrier shape, every actual RGB value within the naturally selected high/medium tolerance, alpha 255, and the literal anchors. Its Canvas producer and `SurfaceTexture` path do not inject an arbitrary acquired-buffer transform and cannot establish real API 32+ `MediaProjection` scaling into a smaller Surface; validate that separately on a physical device with asymmetric orientation landmarks. Source inspection verifies that the unified renderer parameter refresh resets all four bounds for Full before the same-output-size Apply early return, including Full to restricted to Full. That source check is configuration evidence, not executed same-target GPU pixel evidence.

Each device execution must report the named case and the naturally selected fragment precision passed from `EglOwner` to `GLRenderer`. Label renderer setup as selected/attempted and only a completed real read as exercised; label the other branch unexercised. A device that naturally selects medium precision may mark high precision not applicable. Forcing the mediump shader on high-precision hardware does not prove behavior at the GLSL ES mediump minimum. AndroidTest assembly supplies no selected-branch or exercised-branch result.

That physical validation must distinguish creation from reuse, including an aspect-changing source shrink that still fits within the old Target. Android can introduce letterboxing in that case; updated geometry metadata alone does not prove the [source-to-Target mapping](contracts/image-pipeline.md#output-sizing-and-requested-versus-applied).

### JPEG fixture

The Framework/Native JPEG fixture is top-down opaque RGBA, `64 x 48`, quality 80, with `16 x 16` tiles:

```text
#E02020  #B34D26  #20B0C0  #20C040
#C020C0  #404040  #808080  #D0D0D0
#2040E0  #7030B0  #26994D  #E0C020
```

Instrumentation must encode and decode these pixels through Framework JPEG and, on eligible API 30+ devices, the registered-JNI Native path. A successful decode must report `BitmapFactory.Options.outMimeType` exactly `image/jpeg` before the pixel oracle runs. JVM/Robolectric checks can assert exact visible Bitmap pixels after padded-row transfer; real Framework JPEG fidelity requires instrumentation. The separate raw-renderer procedure above verifies the producer-already-target path. AndroidTest assembly supplies no JPEG MIME or pixel result.

### Numeric bounds

These bounds apply to the fixtures above. They do not establish color conversion for unknown or non-sRGB inputs, or geometry fidelity at arbitrary capture dimensions. In particular, [GLSL ES 1.00 precision minima](https://registry.khronos.org/OpenGL/specs/es/2.0/GLSL_ES_Specification_1.00.pdf) do not make binary64 CPU mapping proof of medium-precision GPU sampling at phone-sized dimensions. Validate that compatibility with a representative large-image landmark procedure.

| Oracle | Checks | Pass bound |
| --- | --- | --- |
| Raw nominal-sRGB, high precision / medium precision | dimensions, mapping, top-down order, alpha 255 for every pixel | maximum absolute RGB error `2` / `6` |
| Raw grayscale, high precision / medium precision | every pixel and `R == G == B` | maximum absolute RGB error from integer Y `2` / `6` |
| Raw producer-already-target Downscaled, high precision / medium precision | every output pixel | maximum absolute RGB error `2` / `6` |
| Framework or Native JPEG | decoded dimensions, tile orientation, alpha 255; each half-open tile interior `[16c+4,16c+12) x [16r+4,16r+12)` | channel interior MAE at most `24`; per-row MAE at most `36`; grayscale mean spread at most `8`; gray means strictly increase with adjacent separation at least `32` |

Do not use JPEG byte equality, decoded backend-to-backend equality, encoded size, quality monotonicity, an aggregate score, or performance as a correctness oracle. If a device reports no high-precision fragment capability, record the medium-precision path and mark the high-precision case `Not applicable` for that device.

## Verification contracts

Generic `RUN-01` evidence for a shared runtime primitive cannot replace each owner's typed outcome, resource-settlement, and lost-wake evidence.

| ID | Contract | Method |
| --- | --- | --- |
| `API-01` | Public constants, validation, defaults, equality/identity rules, and immutable value snapshots have their documented values. | Executable |
| `API-02` | A new engine exposes stable State, Stats, and diagnostic Flow facades with initial `NotStarted` and zero Stats; accessing or collecting State and Stats starts no capture work, while diagnostics is asserted here only as a stable facade. | Executable |
| `API-03` | Start admits once and takes no projection: a concurrent valid loser gets `IllegalStateException`; cancellation does not return the Session-owned projection to the caller; pre-Active normal stop follows plain cancellation semantics, while a real startup failure yields its exact failure outcome. | Executable |
| `API-04` | `updateParameters` rejects after ordinary admission closes and treats an admitted equal value as a no-op. | Executable |
| `UPD-01` | For one admitted unequal update racing a terminal successor, the newest desire is published before the first unlocked effect; if terminal wins, no ordinary successor or effect follows. | Executable |
| `API-05` | Inspect that the public-package source surface is bounded to the nine Kotlin files directly under `screen-capture-engine/src/main/kotlin/io/screenstream/capture/`, excludes `internal/` subpackages, and enables `explicitApi()` in `screen-capture-engine/build.gradle.kts`. Package inspection does not prove exact member compatibility; verify that separately against an API-signature reference. | Inspection |
| `SES-01` | Successful synchronous factory return transfers projection ownership to the Session; factory failure leaves it with the caller. Bootstrap/Capture handoffs are internal and at most once. Stopping a created Session retires its ownership even when startup never enters. | Executable |
| `SES-02` | Terminal selection preserves problem priority, publishes final Stats before terminal State, settles the accepted start once with the exact outcome, and keeps `stop()` idempotent and nonwaiting. After `stop()` returns, a new Session may start while old callback or cleanup tails remain rooted; new admission does not prove old settlement. | Executable |
| `SES-03` | Current topology and owner results reconcile to one revision; a stale result settles its resource but cannot publish output or overwrite current State. A denied current Apply remains quiescent for the same desired revision, while relevant desired, geometry, or Metrics-availability revisions reopen convergence. | Executable |
| `SES-04` | Source region, crop, rotation, mirror, scale, and target dimensions resolve with checked geometry and the documented problem class. | Executable |
| `SES-05` | Fresh-output and repeat deadlines use the admitted time/parameters and retain at most one pending wake of each kind. Successful repeat commits continue through later coalesced Control turns across two autonomous quiet deadlines without source or consumer ingress. | Executable |
| `SES-06` | Fresh, cached, repeated, failed, stale, and terminal production outcomes preserve the exact output identity and one materialized production bound. A held original callback keeps its borrowed bytes and metadata immutable while later repeats commit, and late cached-first delivery exposes the latest repeated identity. | Executable |
| `SES-07` | Produced, failed, consumer-busy, and callback-failure outcomes update finite Stats; repeated output while one callback remains occupied counts each consumer-busy opportunity, and the terminal fold contains no later ordinary updates. | Executable |
| `MET-01` | Session metrics subscribes once, conflates the latest snapshot, fences completion/failure, and closes its exact handle at most once. A positive callback received before `subscribe` returns remains unready until that exact returned handle is adopted. | Executable |
| `MET-02` | Built-in metrics registers its Display listener and, on API 31+, the exact cached WindowContext callback before its first bounds read. Display and context changes feed the coalesced refresh; context-only updates and same-valid-display `Changed` during an entered read have distinct oracles. Epoch replacement and close fence stale callbacks and settle each exact callback and listener once without waiting for unrelated callbacks. API 30 has no WindowContext callback. | Executable |
| `MET-03` | Coordinator Metrics folding distinguishes source failure, completion without positive Metrics, and completion with an adopted frozen positive snapshot. Completed-null is classified as unavailable independently of the exact close return. A frozen positive snapshot can satisfy first-Active readiness independently of completion-close return and remains usable after first Active. Close remains an exact resource obligation; failures follow the maintained Metrics failure mapping. Post-Active completion does not itself terminate a Session suspended for unavailable Metrics. | Executable |
| `CAP-01` | Projection ownership adopts at most one display, reports null/security/stop outcomes exactly, and retires the owned projection/display resources at most once. | Executable |
| `CAP-02` | Source reservation and Capture-facing RGBA layout validation preserve checked dimensions, current source identity, and exact reservation settlement. | Executable |
| `CAP-03` | EGL setup failure, independently proved unbinding, context/surface and Android initialization-reference ownership, quarantine, and dependency-ordered teardown preserve the exact owned-resource outcome. Actual-thread shared-display model evidence is separate from public deferred-stop harness evidence; neither proves native GPU drain. | Executable |
| `CAP-04` | Direct RGBA renderer readback, carrier range validation, and local GL failure quarantine preserve the exact local outcome. | Executable |
| `CAP-05` | An exact matching Capture return settles its carrier once; an accepted-but-unentered return remains rooted until exact entry; retirement fences it to `CutoffInert`; and post-retirement submission is rejected. This row does not claim a carrier-lifecycle sweep. | Executable |
| `CAP-06` | The generic Capture callback boundary forwards one ordinary `Exception` with its exact callback identity and cause, locally contains an ordinary `Exception` thrown by that boundary, and propagates non-`Exception` throwables unchanged without invoking the boundary. | Executable |
| `IMG-01` | An instrumentation procedure must drive the real listener, Target, OES texture, GLES renderer, and readback path when the producer already equals the Target; an independent CPU oracle verifies the raw fixture's transform, retained-neighbour sampling, quantization, grayscale, orientation, 27 Full cases, one Downscaled case, and one provisional-Full case. | Executable |
| `ENC-01` | A successful encode settles one input loan and transaction once, exposes one committed immutable payload, and uses the shared checked duration rule: nonnegative timestamp ordering succeeds and regressed ordering is rejected. | Executable |
| `ENC-02` | Auto/Native selection and backend health produce the exact typed outcome without same-frame Framework fallback. | Executable |
| `ENC-03` | Before submission, a contained transaction-construction `OutOfMemoryError` settles the ready input as `ResourceExhausted`, and an ordinary production-construction `Exception` settles it as an internal failure; an uncontained `Error` or non-`Exception` propagates while the unproved loan remains retained. No case creates a task or exposes partial output. | Executable |
| `ENC-04` | Managed/native wire decoding and host C++ encode paths preserve status values, bounds, pending-Throwable behavior, partial-output rejection, cleanup, and JNI result layout. | Executable |
| `ENC-05` | Encoding-owner reconcile and production submission preserve their exact callback and input identity across reuse, definitive rejection, accepted cutoff, failure, and later recovery, without inferring an owner outcome from the runtime slot alone. | Executable |
| `ENC-06` | Managed direct-carrier allocation classification and no-residue outcomes, linear loan ownership and retirement, plus Framework Bitmap/scratch validation, adoption, and residue preserve the exact classified outcome and owned roots. | Executable |
| `ENC-07` | Transaction commit, abort, and ordinary fault paths expose immutable bytes only after a valid commit and never publish tentative bytes from a failed or aborted transaction. | Executable |
| `ENC-08` | Inspect managed transaction segment allocation, tail normalization, and payload construction to verify that their `OutOfMemoryError` catches map to `ResourceExhausted` and publish no payload. Inspection of exhaustion handling is not dynamic allocation-exhaustion evidence. | Inspection |
| `ENC-09` | Native-malloc allocation maps `OutOfMemoryError` to `ResourceExhausted` and ordinary `Exception` to `InternalFailure` with no residue; a malformed direct range is retained then freed exactly once; an ordinary free `Exception` is attempted once and leaves `Retained` with its exact stable carrier-local cause; and a non-`Exception` propagates identically after pre-call quarantine, then remains `Retained` without retry. A successful exact-free case closes once. | Executable |
| `ABI-01` | Inspect source and build configuration for the `screen_capture_engine` DSO name; `armeabi-v7a`, `arm64-v8a`, `x86`, and `x86_64` filters; the weak-API flag; `jnigraphics`; hidden visibility; the version map with sole global `JNI_OnLoad`; CMake `LINK_DEPENDS` and link options; exact C++ registration names; and consumer keep rules. Source inspection does not establish emitted ELF/AAR/APK contents, R8 or external-consumer behavior, packaged-ABI loading, or 16-KiB-page behavior; use the corresponding artifact and runtime checks for those claims. | Inspection |
| `STO-01` | Segmented payload construction and range copy validate before mutation and expose immutable ordered bytes; cache, repeat, and callback handoff preserve observable frame bytes and metadata. | Executable |
| `DEL-01` | Delivery preserves one bounded physical occupancy and callback-thread-only borrow; dispatch rejection, revocation, callback failure or nonreturn, callback-thread clearing before durable ordinary-exit proof, fact staging/readiness, and roots required while the borrow can still be used have exact outcomes. | Executable |
| `DEL-02` | Session Delivery reserves one exact handoff identity before physical offer, preserves exact registration, cached-first, offer/result, and fact correlation, and forwards deferred unregister actions once. Terminal publication does not wait for an entered callback or settle its unregister completion early; late exact return or queued cutoff may complete that independent obligation without reviving Session publication or claiming physical release. | Executable |
| `DEL-03` | Inspect `runControlTurn -> executePendingUnregisterAction -> claimPendingUnregisterAction` to verify that deferred unregister reaches one exact `Complete` or `RequestCutoff` action. Static forwarding inspection is not execution evidence for the publication race. | Inspection |
| `OBS-01` | State and Stats publish complete current values; bounded replay-free diagnostic delivery is optional and may be lost. A caught ordinary `Exception` during diagnostic publication cannot block terminal State. A non-`Exception` is not promised containment: it may propagate after final Stats assignment while State remains at its prior value. | Executable |
| `RUN-01` | A submitted task distinguishes rejection, accepted entry, accepted-never-entry, and real return while retaining the exact task roots until settlement. | Executable |
| `TST-01` | Deterministic test-infrastructure dispatcher, delayed scheduler, completion, and clock controls faithfully expose acceptance, explicit entry, completion, rejection, throwing, submission order, and set/advance behavior without becoming product verdicts. | Executable |
| `BSP-01` | If the Control lane thread cannot start, startup terminates with the mapped failure and releases the accepted projection once. | Executable |
| `BSP-02` | If Bootstrap obtains no usable Looper, startup terminates with the mapped failure and releases the accepted projection once. | Executable |
| `BSP-03` | If Handler construction throws before Control entry, startup terminates with the mapped failure and releases the accepted projection once. | Executable |
| `BSP-04` | First Control post returning `false` proves non-entry and offers `InternalFailure` through the existing pre-Control terminal authority, subject to its priority and currentness rules. Startup settlement requires no second contender; no retry, replacement lane, or cleanup receipt is inferred. | Executable |
| `BSP-05` | If the first Control post throws before entry, startup terminates and Bootstrap releases the projection once. | Executable |
| `FWK-01` | A Framework `Bitmap.compress(...) == false` aborts the transaction, publishes no bytes, settles the input once, and reports the frame-local encoding failure. | Executable |
| `P3-01` | Exact Display P3 metadata maps at the Capture boundary to `UnsupportedColorSpace`, consumes the source opportunity, leaves a reusable owner, and produces no frame. | Executable |
| `P3-02` | A current Capture P3 failure terminates the Session with `UnsupportedColorSpace` and produces no frame. | Executable |
| `P3-03` | A stale Capture P3 failure is cleanup-only and cannot publish output or change terminal selection. | Executable |
| `UNR-01` | Public unregister with no handoff commits logical removal and completes without waiting for physical callback work. | Executable |
| `UNR-02` | Public unregister that wins cutoff before callback entry completes after cutoff settlement; the later task is inert. | Executable |
| `UNR-03` | Public unregister after callback entry waits for that exact callback return, then completes once without retry. | Executable |
| `UNR-04` | Public self-unregister from inside the borrowed callback fails with the documented rejection and does not deadlock or revoke early. | Executable |
| `UNR-05` | Caller cancellation of the unregister wait remains cancellation, does not reopen delivery or cancel the underlying completion obligation, and causes no duplicate physical settlement. Cancellation is not proof of callback drain. | Executable |
| `UNR-06` | Exact unregister completion survives Session stop or failure and completes only after proof that no handoff remains, exact pre-entry cutoff, or return of its entered callback. Session termination does not replace that completion with a terminal exception; `stop()` does not wait for it. | Executable |
| `TERM-01` | Terminal freeze while one Capture read and its Encoding input loan are outstanding detaches ordinary publication; the real late return discards once and requests no ordinary wake. | Executable |
| `TGT-01` | A Target replacement failure proven pre-attachment or fully rolled back preserves the old usable Target and reports the exact local failure. | Executable |
| `TGT-02` | An ambiguous `setSurface` or incomplete Target rollback poisons the owner: neither candidate nor old graph is reused, and retirement retains the required roots. | Executable |
| `TGT-03` | Full reuse preserves an exact matching Target/source. Downscaled reuse preserves sufficient rotation-aware size, matching source aspect, and the source-to-Target mapping; size fit alone cannot admit an aspect-incompatible Target. Minimum size governs new allocation, not mandatory shrinking on reuse. | Executable |

Update an entry only when its contract or evidence changes. A broad test class does not implicitly cover another contract.
