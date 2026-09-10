# Verification contracts

This catalogue defines the verification obligations for continued engine development. The public [Usage](../../docs/usage.md) owns caller behavior, and [Architecture](../../docs/architecture.md) supplies the shared design model. Internal owner pages explain the algorithms behind these obligations. Tests provide evidence for that behavior; they do not redefine it.

Use [Testing](testing.md) for commands, [traceability markers](testing.md#traceability-markers), and [contract-test rules](testing.md#contract-test-rules). [Image test oracles](image-test-oracles.md) supplies the fixtures and numeric bounds required by image checks.

## Read the evidence method

**Executable** requires a contributing test that directly asserts the contract. **Inspection** requires a bounded source or build-configuration check. An ID, method label, or source marker alone establishes neither coverage nor a passing result. Instrumentation source and assembly describe procedures, not device results; device, GPU, artifact, and external-consumer evidence must be established separately.

Generic `RUN-01` evidence for a shared runtime primitive cannot replace each owner's typed outcome, resource-settlement, and lost-wake evidence. A broad test class does not implicitly cover another contract. Update an entry only when its contract or evidence changes.

## Find a contract

- [Public values and session decisions](#public-values-and-session-decisions)
- [Metrics](#metrics)
- [Capture and raw images](#capture-and-raw-images)
- [Encoding and native ABI](#encoding-and-native-abi)
- [Storage, delivery, and observation](#storage-delivery-and-observation)
- [Runtime and test infrastructure](#runtime-and-test-infrastructure)
- [Bootstrap](#bootstrap)
- [Framework and color failures](#framework-and-color-failures)
- [Unregister and terminal read settlement](#unregister-and-terminal-read-settlement)
- [Target replacement and reuse](#target-replacement-and-reuse)

## Public values and session decisions

| ID | Obligation | Method |
| --- | --- | --- |
| <a id="api-01"></a> `API-01` | Public constants, validation, defaults, equality/identity rules, and immutable value snapshots have their documented values. | Executable |
| <a id="api-02"></a> `API-02` | A new session exposes stable State, Stats, and diagnostic Flow facades with initial `NotStarted` and zero Stats; accessing or collecting State and Stats starts no capture work, while diagnostics is asserted here only as a stable facade. | Executable |
| <a id="api-03"></a> `API-03` | Start admits once and takes no projection: a concurrent valid loser gets `IllegalStateException`; cancellation does not return the Session-owned projection to the caller; pre-Active normal stop follows plain cancellation semantics, while a real startup failure yields its exact failure outcome. | Executable |
| <a id="api-04"></a> `API-04` | `updateParameters` rejects after ordinary admission closes. An admitted equal value is normally a no-op, but explicit resubmission of the settled current suspended desire admits one fresh normal reevaluation when no newer request or reevaluation is pending. | Executable |
| <a id="upd-01"></a> `UPD-01` | For one admitted unequal update racing a terminal successor, the newest desire is published before the first unlocked effect; if terminal wins, no ordinary successor or effect follows. | Executable |
| <a id="api-05"></a> `API-05` | Inspect that the public-package source surface is confined to Kotlin files directly under `screen-capture-engine/src/main/kotlin/io/screenstream/capture/`, excludes `internal/` subpackages, and enables `explicitApi()` in `screen-capture-engine/build.gradle.kts`. Package inspection does not prove exact member compatibility; verify that separately against an API-signature reference. | Inspection |
| <a id="ses-01"></a> `SES-01` | Successful synchronous factory return transfers projection ownership to the Session; factory failure leaves it with the caller. Bootstrap/Capture handoffs are internal and at most once. Stopping a created Session retires its ownership even when startup never enters. | Executable |
| <a id="ses-02"></a> `SES-02` | Terminal selection preserves problem priority, publishes final Stats before terminal State, settles the accepted start once with the exact outcome, and keeps `requestStop()` idempotent and nonwaiting. After `requestStop()` returns, a new Session may start while old callback or cleanup tails remain rooted; new admission does not prove old settlement. | Executable |
| <a id="ses-03"></a> `SES-03` | Current topology and owner results reconcile to one revision; a stale result settles its resource but cannot publish output or overwrite current State. A denied current Apply remains quiescent for the same desired revision, while an accepted explicit equal resubmission or relevant desired, geometry, or Metrics-availability revision reopens convergence. | Executable |
| <a id="ses-04"></a> `SES-04` | Source region, crop, rotation, mirror, scale, and target dimensions resolve with checked geometry and the documented problem class. | Executable |
| <a id="ses-05"></a> `SES-05` | Fresh-capture and output deadlines use the admitted time and parameters, retain at most one pending pacing wake, advance rational cadence without catch-up, and preserve a completed fresh payload while its output is deferred. | Executable |
| <a id="ses-06"></a> `SES-06` | Fresh, cached, failed, stale, and terminal production outcomes preserve the exact output identity and one materialized production bound. A held callback keeps its borrowed bytes and metadata immutable while later fresh outputs replace the cache, an obsolete cache candidate cannot invalidate its replacement, and late compatible cached-first delivery exposes the latest fresh committed identity. | Executable |
| <a id="ses-07"></a> `SES-07` | Encoded, produced, failed, consumer-busy, and callback-failure outcomes update finite Stats. Each later fresh output offered while a callback remains occupied adds one consumer-busy drop; a successful stale encode contributes encoding statistics without an output commit, and the terminal fold contains no later ordinary updates. | Executable |
| <a id="ses-08"></a> `SES-08` | `stop()` requests the terminal cutoff and normally returns only after assigned/frozen terminal values, settled shared startup, and normally returned at-most-once projection stop. Cancellation affects only its waiter; Bootstrap/Capture transfer and repeated callers preserve the durable result. Required stop failure is separate from run failure. Accepted nonentry/nonreturn cannot produce success; later callback/graphics cleanup need not finish. | Executable |

## Metrics

| ID | Obligation | Method |
| --- | --- | --- |
| <a id="met-01"></a> `MET-01` | Session metrics subscribes once, conflates the latest snapshot, fences completion/failure, and closes its exact handle at most once. A positive callback received before `subscribe` returns remains unready until that exact returned handle is adopted. | Executable |
| <a id="met-02"></a> `MET-02` | Built-in metrics registers its Display listener and, on API 31+, the exact cached WindowContext callback before its first bounds read. Display and context changes feed the coalesced refresh; context-only updates and same-valid-display `Changed` during an entered read have distinct oracles. Epoch replacement and close fence stale callbacks and settle each exact callback and listener once without waiting for unrelated callbacks. API 30 has no WindowContext callback. | Executable |
| <a id="met-03"></a> `MET-03` | Coordinator Metrics folding distinguishes source failure, completion without positive Metrics, and completion with an adopted frozen positive snapshot. Completed-null is classified as unavailable independently of the exact close return. A frozen positive snapshot can satisfy first-Active readiness independently of completion-close return and remains usable after first Active. Close remains an exact resource obligation; failures follow the maintained Metrics failure mapping. Post-Active completion does not itself terminate a Session suspended for unavailable Metrics. | Executable |

## Capture and raw images

| ID | Obligation | Method |
| --- | --- | --- |
| <a id="cap-01"></a> `CAP-01` | Projection ownership adopts at most one display, reports null/security/stop outcomes exactly, and retires the owned projection/display resources at most once. | Executable |
| <a id="cap-02"></a> `CAP-02` | Source reservation and Capture-facing RGBA layout validation preserve checked dimensions, current source identity, and exact reservation settlement. | Executable |
| <a id="cap-03"></a> `CAP-03` | EGL setup failure, independently proved unbinding, context/surface and Android initialization-reference ownership, quarantine, and dependency-ordered teardown preserve the exact owned-resource outcome. Actual-thread shared-display model evidence is separate from public deferred-stop harness evidence; neither proves native GPU drain. | Executable |
| <a id="cap-04"></a> `CAP-04` | Direct RGBA renderer readback, carrier range validation, and local GL failure quarantine preserve the exact local outcome. | Executable |
| <a id="cap-05"></a> `CAP-05` | An exact matching Capture return settles its carrier once; an accepted-but-unentered read task remains rooted until exact entry; retirement fences it to `CutoffInert`; and post-retirement submission is rejected. This row does not claim a carrier-lifecycle sweep. | Executable |
| <a id="cap-06"></a> `CAP-06` | The generic Capture callback boundary forwards one ordinary `Exception` with its exact callback identity and cause, locally contains an ordinary `Exception` thrown by that boundary, and propagates non-`Exception` throwables unchanged without invoking the boundary. | Executable |
| <a id="img-01"></a> `IMG-01` | An instrumentation procedure must drive the real listener, Target, OES texture, GLES renderer, and readback path when the producer already equals the Target; an independent CPU oracle verifies the raw fixture's transform, retained-neighbour sampling, quantization, grayscale, orientation, 27 Full cases, one Downscaled case, and one provisional-Full case. | Executable |

## Encoding and native ABI

| ID | Obligation | Method |
| --- | --- | --- |
| <a id="enc-01"></a> `ENC-01` | A successful encode settles one input loan and transaction once, exposes one committed immutable payload, and uses the shared checked duration rule: nonnegative timestamp ordering succeeds and regressed ordering is rejected. | Executable |
| <a id="enc-02"></a> `ENC-02` | Auto/Native selection and backend health produce the exact typed outcome without same-frame Framework fallback. | Executable |
| <a id="enc-03"></a> `ENC-03` | Before submission, a contained transaction-construction `OutOfMemoryError` settles the ready input as `ResourceExhausted`, and an ordinary production-construction `Exception` settles it as an internal failure; an uncontained `Error` or non-`Exception` propagates while the unproved loan remains retained. No case creates a task or exposes partial output. | Executable |
| <a id="enc-04"></a> `ENC-04` | Managed/native wire decoding and host C++ encode paths preserve status values, bounds, pending-Throwable behavior, partial-output rejection, cleanup, and JNI result layout. | Executable |
| <a id="enc-05"></a> `ENC-05` | Encoding-owner reconcile and production submission preserve their exact callback and input identity across reuse, definitive rejection, accepted cutoff, failure, and later recovery, without inferring an owner outcome from the runtime slot alone. | Executable |
| <a id="enc-06"></a> `ENC-06` | Managed direct-carrier allocation classification and no-residue outcomes, linear loan ownership and retirement, plus Framework Bitmap/scratch validation, adoption, and residue preserve the exact classified outcome and owned roots. | Executable |
| <a id="enc-07"></a> `ENC-07` | Transaction commit, abort, and ordinary fault paths expose immutable bytes only after a valid commit and never publish tentative bytes from a failed or aborted transaction. | Executable |
| <a id="enc-08"></a> `ENC-08` | Inspect managed transaction segment allocation, tail normalization, and payload construction to verify that their `OutOfMemoryError` catches map to `ResourceExhausted` and publish no payload. Inspection of exhaustion handling is not dynamic allocation-exhaustion evidence. | Inspection |
| <a id="enc-09"></a> `ENC-09` | Native-malloc allocation maps `OutOfMemoryError` to `ResourceExhausted` and ordinary `Exception` to `InternalFailure` with no residue; a malformed direct range is retained then freed exactly once; an ordinary free `Exception` is attempted once and leaves `Retained` with its exact stable carrier-local cause; and a non-`Exception` propagates identically after pre-call quarantine, then remains `Retained` without retry. A successful exact-free case closes once. | Executable |
| <a id="abi-01"></a> `ABI-01` | Inspect source and build configuration for the `screen_capture_engine` DSO name; `armeabi-v7a`, `arm64-v8a`, `x86`, and `x86_64` filters; the weak-API flag; `jnigraphics`; hidden visibility; the version map with sole global `JNI_OnLoad`; CMake `LINK_DEPENDS` and link options; exact C++ registration names; and consumer keep rules. Source inspection does not establish emitted ELF/AAR/APK contents, R8 or external-consumer behavior, packaged-ABI loading, or 16-KiB-page behavior; use the corresponding artifact and runtime checks for those claims. | Inspection |

## Storage, delivery, and observation

| ID | Obligation | Method |
| --- | --- | --- |
| <a id="sto-01"></a> `STO-01` | Segmented payload construction and range copy validate before mutation and expose immutable ordered bytes; cache and callback handoff preserve observable frame bytes and metadata. | Executable |
| <a id="del-01"></a> `DEL-01` | Delivery preserves one bounded physical occupancy and callback-thread-only borrow; dispatch rejection, revocation, callback failure or nonreturn, callback-thread clearing before durable ordinary-exit proof, fact staging/readiness, and roots required while the borrow can still be used have exact outcomes. | Executable |
| <a id="del-02"></a> `DEL-02` | Session Delivery reserves one exact handoff identity before physical offer, preserves exact registration, cached-first, offer/result, and fact correlation, and forwards deferred unregister actions once. Terminal publication does not wait for an entered callback or settle its unregister completion early; late exact return or queued cutoff may complete that independent obligation without reviving Session publication or claiming physical release. | Executable |
| <a id="del-03"></a> `DEL-03` | Inspect `runControlTurn -> executePendingUnregisterAction -> claimPendingUnregisterAction` to verify that deferred unregister reaches one exact `Complete` or `RequestCutoff` action. Static forwarding inspection is not execution evidence for the publication race. | Inspection |
| <a id="obs-01"></a> `OBS-01` | State and Stats publish complete current values; bounded replay-free diagnostic delivery is optional and may be lost. A caught ordinary `Exception` during diagnostic publication cannot block terminal State. A non-`Exception` is not promised containment: it may propagate after final Stats assignment while State remains at its prior value. | Executable |

## Runtime and test infrastructure

| ID | Obligation | Method |
| --- | --- | --- |
| <a id="run-01"></a> `RUN-01` | A submitted task distinguishes rejection, accepted entry, accepted-never-entry, and real return while retaining the exact task roots until settlement. | Executable |
| <a id="tst-01"></a> `TST-01` | Deterministic test-infrastructure dispatcher, delayed scheduler, completion, and clock controls faithfully expose acceptance, explicit entry, completion, rejection, throwing, submission order, and set/advance behavior without becoming product verdicts. | Executable |

## Bootstrap

| ID | Obligation | Method |
| --- | --- | --- |
| <a id="bsp-01"></a> `BSP-01` | If the Control lane thread cannot start, startup terminates with the mapped failure and releases the accepted projection once. | Executable |
| <a id="bsp-02"></a> `BSP-02` | If Bootstrap obtains no usable Looper, startup terminates with the mapped failure and releases the accepted projection once. | Executable |
| <a id="bsp-03"></a> `BSP-03` | If Handler construction throws before Control entry, startup terminates with the mapped failure and releases the accepted projection once. | Executable |
| <a id="bsp-04"></a> `BSP-04` | First Control post returning `false` proves non-entry and offers `InternalFailure` through the existing pre-Control terminal authority, subject to its priority and currentness rules. Startup settlement requires no second contender; no retry, replacement lane, or cleanup receipt is inferred. | Executable |
| <a id="bsp-05"></a> `BSP-05` | If the first Control post throws before entry, startup terminates and Bootstrap releases the projection once. | Executable |

## Framework and color failures

| ID | Obligation | Method |
| --- | --- | --- |
| <a id="fwk-01"></a> `FWK-01` | A Framework `Bitmap.compress(...) == false` aborts the transaction, publishes no bytes, settles the input once, and reports the frame-local encoding failure. | Executable |
| <a id="p3-01"></a> `P3-01` | Exact Display P3 metadata maps at the Capture boundary to `UnsupportedColorSpace`, consumes the source opportunity, leaves a reusable owner, and produces no frame. | Executable |
| <a id="p3-02"></a> `P3-02` | A current Capture P3 failure terminates the Session with `UnsupportedColorSpace` and produces no frame. | Executable |
| <a id="p3-03"></a> `P3-03` | A stale Capture P3 failure is cleanup-only and cannot publish output or change terminal selection. | Executable |

## Unregister and terminal read settlement

| ID | Obligation | Method |
| --- | --- | --- |
| <a id="unr-01"></a> `UNR-01` | Public unregister with no handoff commits logical removal and completes without waiting for physical callback work. | Executable |
| <a id="unr-02"></a> `UNR-02` | Public unregister that wins cutoff before callback entry completes after cutoff settlement; the later task is inert. | Executable |
| <a id="unr-03"></a> `UNR-03` | Public unregister after callback entry waits for that exact callback return, then completes once without retry. | Executable |
| <a id="unr-04"></a> `UNR-04` | Public self-unregister from inside the borrowed callback fails with the documented rejection and does not deadlock or revoke early. | Executable |
| <a id="unr-05"></a> `UNR-05` | Caller cancellation of the unregister wait remains cancellation, does not reopen delivery or cancel the underlying completion obligation, and causes no duplicate physical settlement. Cancellation is not proof of callback drain. | Executable |
| <a id="unr-06"></a> `UNR-06` | Exact unregister completion survives Session stop or failure and completes only after proof that no handoff remains, exact pre-entry cutoff, or return of its entered callback. Session termination does not replace that completion with a terminal exception; neither `stop()` nor `requestStop()` waits for it. | Executable |
| <a id="term-01"></a> `TERM-01` | Terminal freeze while one Capture read and its Encoding input loan are outstanding detaches ordinary publication; the real late return discards once and requests no ordinary wake. | Executable |

## Target replacement and reuse

| ID | Obligation | Method |
| --- | --- | --- |
| <a id="tgt-01"></a> `TGT-01` | A Target replacement failure proven pre-attachment or fully rolled back preserves the old usable Target and reports the exact local failure. | Executable |
| <a id="tgt-02"></a> `TGT-02` | An ambiguous `setSurface` or incomplete Target rollback poisons the owner: neither candidate nor old graph is reused, and retirement retains the required roots. | Executable |
| <a id="tgt-03"></a> `TGT-03` | Full reuse preserves an exact matching Target/source. Downscaled reuse preserves sufficient rotation-aware size, matching source aspect, and the source-to-Target mapping; size fit alone cannot admit an aspect-incompatible Target. Minimum size governs new allocation, not mandatory shrinking on reuse. | Executable |
