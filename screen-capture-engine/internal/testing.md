# Testing and verification

This is the canonical testing guide for ScreenStream Capture Engine. It defines verification contracts, their executable contributors, and the evidence boundaries of each test environment. Run commands from the repository root and use the smallest environment that can observe the contract.

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
- `Automated` means a checked-in executable test directly asserts the contract. `Static` means bounded source or build-configuration inspection. `Missing` means that the required checked-in procedure does not exist. Device, GPU, artifact, and external-consumer evidence must be established separately.

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
- Do not mark fixtures, mocks, helpers, production code, registration tables, or `main()`. A marker identifies a direct oracle; it is not evidence by itself. An instrumentation marker identifies a checked-in procedure, not a device result.

Find contributors with `rg -n -F '// Verification: <ID>' screen-capture-engine/src/test screen-capture-engine/src/androidTest`. Every `Automated` ID must have a direct contributor and every marker must name a listed contract.

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

The source row and total byte ranges are exactly 20 and 60 bytes. The checked-in `IMG-01` procedure drives the real listener, Target, OES texture, GLES renderer, and readback path when the producer already equals the Target; its independent CPU oracle implements the documented geometry, sampling, quantization, and grayscale rules and compares every output pixel. Its 25 raw-render cases are 23 Full cases, one producer-already-target Downscaled case, and one provisional-Full case. They cover LeftHalf columns 0-1, RightHalf columns 2-4, crop `(1,0,1,1)` to `3 x 2`, every rotation/mirror combination including non-square 90-degree cases, `ScaleFactor(2.0)` to `10 x 6`, Stretch to `8 x 8`, and AspectFit to `8 x 5`. Real API 32+ `MediaProjection` scaling into a smaller Surface is not covered by this procedure and requires validation on a physical device with asymmetric orientation landmarks.

### JPEG fixture

The Framework/Native JPEG fixture is top-down opaque RGBA, `64 x 48`, quality 80, with `16 x 16` tiles:

```text
#E02020  #B34D26  #20B0C0  #20C040
#C020C0  #404040  #808080  #D0D0D0
#2040E0  #7030B0  #26994D  #E0C020
```

The checked-in androidTest fixtures are designed to encode and decode these pixels through Framework JPEG and, on eligible API 30+ devices, the registered-JNI Native path. The separate real-renderer androidTest is a checked-in procedure that passes the raw fixture through the real listener, Target, OES texture, production GLES renderer, and readback path when the producer already equals the Target. The JVM/Robolectric Framework Bitmap owner test asserts exact visible pixels after padded-row transfer; real Framework JPEG fidelity remains owned by the instrumentation procedure. Checked-in instrumentation source and assembly are procedures, not claimed device results.

### Numeric bounds

| Oracle | Checks | Pass bound |
| --- | --- | --- |
| Raw nominal-sRGB, high precision / medium precision | dimensions, mapping, top-down order, alpha 255 for every pixel | maximum absolute RGB error `2` / `6` |
| Raw grayscale, high precision / medium precision | every pixel and `R == G == B` | maximum absolute RGB error from integer Y `2` / `6` |
| Raw producer-already-target Downscaled, high precision / medium precision | every output pixel | maximum absolute RGB error `2` / `6` |
| Framework or Native JPEG | decoded dimensions, tile orientation, alpha 255; each half-open tile interior `[16c+4,16c+12) x [16r+4,16r+12)` | channel interior MAE at most `24`; per-row MAE at most `36`; grayscale mean spread at most `8`; gray means strictly increase with adjacent separation at least `32` |

Do not use JPEG byte equality, decoded backend-to-backend equality, encoded size, quality monotonicity, an aggregate score, or performance as a correctness oracle. If a device reports no high-precision fragment capability, record the medium-precision path and mark the high-precision case `Not applicable` for that device.

## Verification contracts

The exact executable contributors to an Automated row are the source locations marked `// Verification: <ID>`.
Generic `RUN-01` evidence for a shared runtime primitive cannot replace each owner's typed outcome, resource-settlement, and lost-wake evidence.

| ID | Contract | Status |
| --- | --- | --- |
| `API-01` | Public constants, validation, defaults, equality/identity rules, and immutable value snapshots have their documented values. | Automated |
| `API-02` | A new engine exposes stable State, Stats, and diagnostic Flow facades with initial `NotStarted` and zero Stats; accessing or collecting State and Stats starts no capture work, while diagnostics is asserted here only as a stable facade. | Automated |
| `API-03` | Start admits once: cancellation before admission leaves the projection untouched; a concurrent valid loser gets `IllegalStateException`; an admitted stop/failure yields its exact public outcome. | Automated |
| `API-04` | `updateParameters` rejects after ordinary admission closes and treats an admitted equal value as a no-op. | Automated |
| `UPD-01` | For one admitted unequal update racing a terminal successor, the newest desire is published before the first unlocked effect; if terminal wins, no ordinary successor or effect follows. | Automated |
| `API-05` | Static inspection proves that the public-package source surface is bounded to the nine Kotlin files directly under `screen-capture-engine/src/main/kotlin/io/screenstream/capture/`, excludes `internal/` subpackages, and enables `explicitApi()` in `screen-capture-engine/build.gradle.kts`. It does not prove exact member compatibility; that requires a future checked-in API-signature baseline and verification procedure. | Static |
| `SES-01` | Accepted Bootstrap/startup publishes only legal lifecycle transitions and transfers the accepted projection at most once. | Automated |
| `SES-02` | Terminal selection preserves problem priority, publishes final Stats before terminal State, settles the accepted start once with the exact outcome, and keeps public `stop()` idempotent. | Automated |
| `SES-03` | Current topology and owner results reconcile to one revision; a stale result settles its resource but cannot publish output or overwrite current State. | Automated |
| `SES-04` | Source region, crop, rotation, mirror, scale, and target dimensions resolve with checked geometry and the documented problem class. | Automated |
| `SES-05` | Fresh-output and repeat deadlines use the admitted time/parameters and retain at most one pending wake of each kind. | Automated |
| `SES-06` | Fresh, cached, repeated, failed, stale, and terminal production outcomes preserve the exact output identity and one materialized production bound. | Automated |
| `SES-07` | Produced, failed, consumer-busy, and callback-failure outcomes update finite Stats; the terminal fold contains no later ordinary updates. | Automated |
| `MET-01` | Session metrics subscribes once, conflates the latest snapshot, fences completion/failure, and closes its exact handle at most once. | Automated |
| `MET-02` | Built-in metrics registration, refresh, callback fencing, and unregister settle the exact listener once without waiting for unrelated callbacks. | Automated |
| `MET-03` | Coordinator custom-Metrics folding covers five cells: an open-admission source failure selects terminal `InternalFailure` and the same exact start failure; pre-Active completion without Metrics fails startup as `CaptureUnavailable` after settled close; pre-Active completion with positive Metrics reaches first `Active` after settled close; post-Active positive completion preserves the current `Active`; and post-Active `null -> Suspended(CaptureUnavailable) -> completion/settled close` remains nonterminal and preserves that suspension. | Automated |
| `CAP-01` | Projection ownership adopts at most one display, reports null/security/stop outcomes exactly, and retires the owned projection/display resources at most once. | Automated |
| `CAP-02` | Source reservation and Capture-facing RGBA layout validation preserve checked dimensions, current source identity, and exact reservation settlement. | Automated |
| `CAP-03` | EGL setup failure, context/surface ownership, quarantine, and dependency-ordered teardown preserve the exact owned-resource outcome. | Automated |
| `CAP-04` | Direct RGBA renderer readback, carrier range validation, and local GL failure quarantine preserve the exact local outcome. | Automated |
| `CAP-05` | An exact matching Capture return settles its carrier once; an accepted-but-unentered return remains rooted until exact entry; retirement fences it to `CutoffInert`; and post-retirement submission is rejected. This row does not claim a carrier-lifecycle sweep. | Automated |
| `CAP-06` | The generic Capture callback boundary forwards one ordinary `Exception` with its exact callback identity and cause, locally contains an ordinary `Exception` thrown by that boundary, and propagates non-`Exception` throwables unchanged without invoking the boundary. | Automated |
| `IMG-01` | A checked-in androidTest drives the real listener, Target, OES texture, GLES renderer, and readback path when the producer already equals the Target; an independent CPU oracle verifies the raw fixture's transform, sampling, quantization, grayscale, orientation, 23 Full cases, one Downscaled case, and one provisional-Full case. | Automated |
| `ENC-01` | A successful encode settles one input loan and transaction once, exposes one committed immutable payload, and uses the shared checked duration rule: nonnegative timestamp ordering succeeds and regressed ordering is rejected. | Automated |
| `ENC-02` | Auto/Native selection and backend health produce the exact typed outcome without same-frame Framework fallback. | Automated |
| `ENC-03` | Before submission, a contained transaction-construction `OutOfMemoryError` settles the ready input as `ResourceExhausted`, and an ordinary production-construction `Exception` settles it as an internal failure; an uncontained `Error` or non-`Exception` propagates while the unproved loan remains retained. No case creates a task or exposes partial output. | Automated |
| `ENC-04` | Managed/native wire decoding and host C++ encode paths preserve status values, bounds, pending-Throwable behavior, partial-output rejection, cleanup, and JNI result layout. | Automated |
| `ENC-05` | Encoding-owner reconcile and production submission preserve their exact callback and input identity across reuse, definitive rejection, accepted cutoff, failure, and later recovery, without inferring an owner outcome from the runtime slot alone. | Automated |
| `ENC-06` | Managed direct-carrier allocation classification and no-residue outcomes, linear loan ownership and retirement, plus Framework Bitmap/scratch validation, adoption, and residue preserve the exact classified outcome and owned roots. | Automated |
| `ENC-07` | Exercised transaction commit, abort, and ordinary fault paths expose immutable bytes only after a valid commit and never publish tentative bytes from a failed or aborted transaction; this row does not dynamically cover allocation-exhaustion paths. | Automated |
| `ENC-08` | Static source inspection of managed transaction segment allocation, tail normalization, and payload construction verifies that their `OutOfMemoryError` catches map to `ResourceExhausted` and publish no payload. It does not dynamically exercise exhaustion and has no executable marker or seam. | Static |
| `ENC-09` | Native-malloc allocation maps `OutOfMemoryError` to `ResourceExhausted` and ordinary `Exception` to `InternalFailure` with no residue; a malformed direct range is retained then freed exactly once; an ordinary free `Exception` is attempted once and leaves `Retained` with its exact stable carrier-local cause; and a non-`Exception` propagates identically after pre-call quarantine, then remains `Retained` without retry. The existing successful exact-free case closes once. | Automated |
| `ABI-01` | Static source and build-configuration inspection verifies the `screen_capture_engine` DSO name; `armeabi-v7a`, `arm64-v8a`, `x86`, and `x86_64` filters; the weak-API flag; `jnigraphics`; hidden visibility; the version map with sole global `JNI_OnLoad`; P7-08 CMake `LINK_DEPENDS` and link options; exact C++ registration names; and consumer keep rules. It does not inspect emitted ELF, AAR, or APK artifacts, run R8 or an external consumer, prove packaged-ABI or device-load behavior, or prove 16-KiB-page behavior. | Static |
| `STO-01` | Segmented payload construction and range copy validate before mutation and expose immutable ordered bytes; cache, repeat, and callback handoff preserve observable frame bytes and metadata. | Automated |
| `DEL-01` | Delivery preserves one bounded physical occupancy and callback-thread-only borrow; dispatch rejection, revocation, callback failure or nonreturn, fact staging/readiness, and roots required while the borrow can still be used have exact outcomes. | Automated |
| `DEL-02` | Session Delivery preserves exact registration, cached-first, offer/result, and fact correlation; forwards deferred unregister actions once; settles terminal registrations exactly after the retirement entry fence without waiting for callback return; and treats late or frozen facts as cleanup-only. | Automated |
| `DEL-03` | Static inspection of `runControlTurn -> executePendingUnregisterAction -> claimPendingUnregisterAction` proves that deferred unregister reaches one exact `Complete` or `RequestCutoff` action. It does not claim that the publication race ran. | Static |
| `OBS-01` | State and Stats publish complete current values; bounded replay-free diagnostic delivery is optional and may be lost. A caught ordinary `Exception` during diagnostic publication cannot block terminal State. A non-`Exception` is not promised containment: it may propagate after final Stats assignment while State remains at its prior value. | Automated |
| `RUN-01` | A submitted task distinguishes rejection, accepted entry, accepted-never-entry, and real return while retaining the exact task roots until settlement. | Automated |
| `TST-01` | Deterministic test-infrastructure dispatcher, delayed scheduler, completion, and clock controls faithfully expose acceptance, explicit entry, completion, rejection, throwing, submission order, and set/advance behavior without becoming product verdicts. | Automated |
| `BSP-01` | If the Control lane thread cannot start, startup terminates with the mapped failure and releases the accepted projection once. | Automated |
| `BSP-02` | If Bootstrap obtains no usable Looper, startup terminates with the mapped failure and releases the accepted projection once. | Automated |
| `BSP-03` | If Handler construction throws before Control entry, startup terminates with the mapped failure and releases the accepted projection once. | Automated |
| `BSP-04` | If the first Control post returns `false`, no Control entry occurs; accepted startup remains pending and the projection stays Bootstrap-owned until a separate terminal contender settles it. | Automated |
| `BSP-05` | If the first Control post throws before entry, startup terminates and Bootstrap releases the projection once. | Automated |
| `FWK-01` | A Framework `Bitmap.compress(...) == false` aborts the transaction, publishes no bytes, settles the input once, and reports the frame-local encoding failure. | Automated |
| `P3-01` | Exact Display P3 metadata maps at the Capture boundary to `UnsupportedColorSpace`, consumes the source opportunity, leaves a reusable owner, and produces no frame. | Automated |
| `P3-02` | A current Capture P3 failure terminates the Session with `UnsupportedColorSpace` and produces no frame. | Automated |
| `P3-03` | A stale Capture P3 failure is cleanup-only and cannot publish output or change terminal selection. | Automated |
| `UNR-01` | Public unregister with no handoff commits logical removal and completes without waiting for physical callback work. | Automated |
| `UNR-02` | Public unregister that wins cutoff before callback entry completes after cutoff settlement; the later task is inert. | Automated |
| `UNR-03` | Public unregister after callback entry waits for that exact callback return, then completes once without retry. | Automated |
| `UNR-04` | Public self-unregister from inside the borrowed callback fails with the documented rejection and does not deadlock or revoke early. | Automated |
| `UNR-05` | Caller cancellation of public unregister remains cancellation and causes no duplicate physical settlement. | Automated |
| `UNR-06` | Terminal cancellation or failure settles public unregister with the exact terminal outcome and no duplicate physical settlement. | Automated |
| `TERM-01` | Terminal freeze while one Capture read and its Encoding input loan are outstanding detaches ordinary publication; the real late return discards once and requests no ordinary wake. | Automated |
| `TGT-01` | A Target replacement failure proven pre-attachment or fully rolled back preserves the old usable Target and reports the exact local failure. | Automated |
| `TGT-02` | An ambiguous `setSurface` or incomplete Target rollback poisons the owner: neither candidate nor old graph is reused, and retirement retains the required roots. | Automated |
| `TGT-03` | Full mode reuses an exact matching Target/source; Downscaled mode reuses when the producer shrinks within the existing Target and replaces when growth exceeds it. | Automated |

Update a contract entry only when its contract or evidence changes. A marker is not a test result, and a broad test class does not implicitly cover another contract.
