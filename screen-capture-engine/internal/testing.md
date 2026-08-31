# Testing and verification

This is the canonical verification guide for ScreenStream Capture Engine. It defines finite contract oracles and evidence availability, not stored execution results. Run commands from the repository root and use the smallest environment that can observe the oracle.

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

- JVM and Robolectric tests can establish deterministic values, state transitions, ownership effects, injected schedules, and fault mapping at exercised seams. They cannot establish real framework, graphics-driver, packaged-JNI, or target-ABI behavior. See Android's [local-test](https://developer.android.com/training/testing/local-tests) and [Robolectric](https://developer.android.com/training/testing/local-tests/robolectric) guidance.
- Host-C++ tests can establish exercised native protocol, bounds, cleanup, and sanitizer behavior. They cannot establish Android ABI packaging, registered-JNI loading, Android Bitmap behavior, or target-device execution.

The checked-in audit uses `Automated` for an executable test that directly asserts the oracle, `Static` for bounded source/build inspection, and `Missing` when the required checked-in executable procedure does not yet exist. Device, GPU, artifact, and external-consumer runtime evidence is outside this audit and is not claimed here.

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

- Place the marker immediately above the narrowest executable test or C++ test function that contributes direct evidence. A class-level marker is acceptable only when every executable test in that cohesive class contributes to the same row.
- Repeat an ID at every contributing scope when its oracle spans multiple tests or classes. Stack separate marker lines when one test contributes to multiple rows; do not add suffixes to an ID.
- Do not mark fixtures, mocks, helpers, production code, C++ registration tables, or `main()`. A marker identifies evidence; it is not itself an assertion or a run result.
- A marker in instrumentation source identifies a checked-in procedure; assembly alone does not establish device behavior.

Find exact executable contributors with `rg -n -F '// Verification: <ID>' screen-capture-engine/src/test screen-capture-engine/src/androidTest`. During review, compare the table and source markers so that every Automated ID has at least one marker and every marker names an ID in one of the tables. This lightweight check deliberately avoids a generated index or custom build task; reviewers still verify that each marker is attached to a direct oracle.

## Run records and status semantics

Evidence availability in the table is not a stored execution result. Record the revision, date, exact command or manual procedure, relevant environment and tool/device details, result, failure or skip reason, and useful retained output. A result applies only to the exercised code, path, schedule, API, ABI, capability, and environment.

| Result | Meaning |
| --- | --- |
| `Pass` | The check ran in the recorded environment and met its oracle. |
| `Fail` | The check ran and did not meet its oracle. |
| `Partial` | Only named parts, schedules, API bands, ABIs, capabilities, or environments were checked; record the remaining gap. |
| `Not run` | No current result exists. Missing evidence is never a pass. |
| `Blocked` | The check cannot currently run; record the missing capability or environment. |
| `Deferred` | The check is intentionally postponed; record the reason. |
| `Not applicable` | The check does not apply to this candidate; record why. |

An optional unsupported capability may be `Not applicable`; an unavailable required environment is `Blocked`. Uncertainty is `Deferred`, never `Pass`.

## Contract-test rules

- Give a cohesive test file a focused preamble when its scheduling controls, injected faults, or forbidden implementation observations are not obvious from its verification IDs and test names. Simple tests need no package-wide boilerplate. Assert values, typed problems, State/Stats, callback results, immutable bytes, exact resource settlement, bounded ownership, and documented ordering.
- Fakes, mocks, injected faults, schedulers, and harnesses may arrange input, schedule, or failure. Their incidental calls, private structure, or call order are not the verdict unless that boundary interaction is itself the maintained owner contract. Prefer an existing faithful seam; otherwise add the smallest cohesive test fixture. Do not add a production seam solely for observation.
- For competing actions, assert the permitted winner and the invariant preserved by either winner; do not promise every interleaving.
- Coroutine tests may use `runTest`, test dispatchers, `runCurrent`, `advanceUntilIdle`, and virtual time only to arrange execution. Assert the contract effect, not dispatcher or `Job` internals. Before designing or editing such a test, read Android's [coroutine-test guide](https://developer.android.com/kotlin/coroutines/test) and the current [`kotlinx-coroutines-test` API](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-test/). Do not use sleeps, unsupported `Job` inheritance, incidental `isActive`/checkpoint counts, or scheduler-step counts as proof.
- Task acceptance, timeout, terminal State, diagnostic emission, reference release, or garbage collection is not proof that a callback returned or a resource settled.
- Do not require private fields, locks, classes, checkpoints, line-level call order, exact queue size, or helper structure unless it directly expresses a maintained owner bound. Do not add reflection or artificial state solely to exhaust counters such as `Long.MAX_VALUE`.
- Do not demand every seam or race, Cartesian device/input matrices, cross-Flow atomic snapshots, byte-identical JPEGs, encoded-size monotonicity, performance targets, or cleanup inferred from timeout/GC.

## Reproducible image fixtures and tolerances

### Raw RGBA fixture

Generate this top-down, opaque `5 x 3` RGBA fixture in test code; do not add it to production assets:

```text
#FF0000  #B34D26  #000000  #00FFFF  #00FF00
#FF00FF  #404040  #808080  #C0C0C0  #FFFFFF
#0000FF  #7030B0  #26994D  #008080  #FFFF00
```

The source row and total byte ranges are exactly 20 and 60 bytes. A CPU oracle must independently implement the documented geometry, sampling, quantization, and grayscale rules and compare every output pixel. Exercise LeftHalf columns 0-1, RightHalf columns 2-4, crop `(1,0,1,1)` to `3 x 2`, every rotation/mirror combination including non-square 90-degree cases, `ScaleFactor(2.0)` to `10 x 6`, Stretch to `8 x 8`, AspectFit to `8 x 5`, eligible Downscaled input from `10 x 6` to `5 x 3`, and each Full Target case in which frame admission is closed, including provisional API 34-37 geometry.

### JPEG fixture

The Framework/Native JPEG fixture is top-down opaque RGBA, `64 x 48`, quality 80, with `16 x 16` tiles:

```text
#E02020  #B34D26  #20B0C0  #20C040
#C020C0  #404040  #808080  #D0D0D0
#2040E0  #7030B0  #26994D  #E0C020
```

The checked-in androidTest fixtures are designed to encode and decode these pixels through Framework JPEG and, on eligible API 30+ devices, the registered-JNI Native path. Separate real-renderer androidTest evidence passes the raw fixture through production GLES readback and preserves asymmetric orientation landmarks for Downscaled cases. Checked-in instrumentation source and assembly are procedures, not claimed device results.

### Numeric bounds

| Oracle | Checks | Pass bound |
| --- | --- | --- |
| Raw nominal-sRGB, high precision / medium precision | dimensions, mapping, top-down order, alpha 255 for every pixel | maximum absolute RGB error `2` / `6` |
| Raw grayscale, high precision / medium precision | every pixel and `R == G == B` | maximum absolute RGB error from integer Y `2` / `6` |
| Raw early Downscaled | every output pixel | maximum absolute RGB error `12` |
| Framework or Native JPEG | decoded dimensions, tile orientation, alpha 255; each half-open tile interior `[16c+4,16c+12) x [16r+4,16r+12)` | channel interior MAE at most `24`; per-row MAE at most `36`; grayscale mean spread at most `8`; gray means strictly increase with adjacent separation at least `32` |

Do not use JPEG byte equality, decoded backend-to-backend equality, encoded size, quality monotonicity, an aggregate score, or performance as a correctness oracle. If a device reports no high-precision fragment capability, record the medium-precision path and mark the high-precision case `Not applicable` for that device.

Any future real-device spatial check must use realistic rotated content with asymmetric orientation landmarks, record horizontal and vertical landmark displacement separately before alignment, and ensure registration does not translate or warp away that displacement. Record the spatial result as `Deferred` until the product owner defines a numeric per-axis bound; color/JPEG tolerance or successful execution alone cannot supply that bound.

## Checked-in verification audit

The 53 rows below audit 52 Automated, one Static, and zero Missing checked-in obligations.

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
| `CAP-01` | Projection ownership adopts at most one display, reports null/security/stop outcomes exactly, and retires the owned projection/display resources at most once. | Automated |
| `CAP-02` | Source reservation and region/layout validation preserve checked dimensions, current source identity, and exact reservation settlement. | Automated |
| `CAP-03` | EGL setup failure, context/surface ownership, quarantine, and dependency-ordered teardown preserve the exact owned-resource outcome. | Automated |
| `CAP-04` | Direct RGBA renderer readback, carrier range validation, and local GL failure quarantine preserve the exact local outcome. | Automated |
| `CAP-05` | An exact matching Capture-owner read return settles its carrier once; read retirement makes stale, mismatched, or late returns cleanup-only and forbids reuse. | Automated |
| `CAP-06` | The generic Capture callback boundary forwards one ordinary `Exception` with its exact callback identity and cause, locally contains an ordinary `Exception` thrown by that boundary, and propagates non-`Exception` throwables unchanged without invoking the boundary. | Automated |
| `IMG-01` | A checked-in real-listener-driven renderer/readback androidTest and an independent CPU pixel oracle verify the raw fixture's transform, sampling, quantization, grayscale, orientation, and required Full and Downscaled Target behavior. | Automated |
| `ENC-01` | A successful encode settles one input loan and transaction once, exposes one committed immutable payload, and uses the shared checked duration rule: nonnegative timestamp ordering succeeds and regressed ordering is rejected. | Automated |
| `ENC-02` | Auto/Native selection and backend health produce the exact typed outcome without same-frame Framework fallback. | Automated |
| `ENC-03` | Before submission, a contained transaction-construction `OutOfMemoryError` settles the ready input as `ResourceExhausted`, and an ordinary production-construction `Exception` settles it as an internal failure; an uncontained `Error` or non-`Exception` propagates while the unproved loan remains retained. No case creates a task or exposes partial output. | Automated |
| `ENC-04` | Managed/native wire decoding and host C++ encode paths preserve status values, bounds, pending-Throwable behavior, partial-output rejection, cleanup, and JNI result layout. | Automated |
| `ENC-05` | Encoding-owner reconcile and production submission preserve their exact callback and input identity across reuse, definitive rejection, accepted cutoff, failure, and later recovery, without inferring an owner outcome from the runtime slot alone. | Automated |
| `ENC-06` | Managed direct-carrier and Framework Bitmap/scratch allocation, validation, adoption, failure-residue, and retirement preserve the exact classified outcome and owned roots. | Automated |
| `ENC-07` | Transaction commit, abort, and fault paths expose immutable bytes only after a valid commit and never publish tentative bytes from a failed or aborted transaction. | Automated |
| `STO-01` | Segmented payload construction and range copy validate before mutation and expose immutable ordered bytes; cache, repeat, and callback handoff preserve observable frame bytes and metadata. | Automated |
| `DEL-01` | Delivery has one bounded handoff occupancy; borrow access is callback-thread-only and revoked before return; rejection/failure/nonreturn preserve their exact roots and accounting. | Automated |
| `DEL-02` | Session handoff correlation accepts only the exact request/token facts; cutoff makes a retained queued entry inert, and late or terminal-frozen facts are stale or cleanup-only. | Automated |
| `OBS-01` | State, Stats, and bounded replay-free diagnostics publish complete values; diagnostic delivery, loss, or failure never changes the lifecycle outcome. | Automated |
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

Update an audit row only when its contract or accepted evidence changes. A marker is not a run result, and a broad test class does not implicitly cover another oracle.
