# Screen Capture Engine — Verification Map

> Package authority: [router §1](01-authority-router.md#1-authority-scope).

## Navigation

- [Role and reading rule](#1-role-and-reading-rule)
- [Verification model](#2-verification-model)
- [Source sets and tasks](#21-source-sets-and-tasks)
- [Common deterministic test method](#3-common-deterministic-test-method)
- [Harness](#31-harness)
- [Time and wake rules](#32-time-and-wake-rules)
- [Settlement and terminal-transfer oracle](#33-settlement-and-terminal-transfer-oracle)
- [Scheduler and fault rules](#34-scheduler-and-fault-rules)
- [Ownership and image oracles](#35-ownership-and-image-oracles)
- [Stats value oracle](#36-stats-value-oracle)
- [Executable namespaces](#4-executable-namespaces)
- [Acceptance scenarios](#5-acceptance-scenarios)
- [Verification slices](#6-verification-slices)
- [High-risk paper traces](#7-high-risk-paper-traces)
- [Shared JPEG oracle](#test-vector-jpeg-01--shared-jpeg-64-x-48-oracle)
- [Domain verification routing](#8-domain-verification-routing)

## 1. Role and reading rule

This file owns verification method, shared deterministic support, executable namespaces, and the complete
acceptance, verification-slice, and paper-trace indexes. It does not define product behavior, implementation
ownership, a fallback, or a timing rule. Exact domain cases live only in the linked domain file.

Verification binds behavior, invariants, race outcomes, and oracles rather than private production/test
filenames, harness decomposition, or an equivalent algorithm. Only an explicitly marked public, binary, wire,
platform, safety, source-set, package, or build contract makes those structural details normative. Private
occurrence/owner/helper names and non-ABI layouts are orientation; tests bind their ownership, cardinality,
ordering, copy bounds, and receipts rather than those names or layouts.

| Verification subject | Exact owner |
| --- | --- |
| lifecycle, policy/grants, currentness, counters, authoritative observations, diagnostic sequence and commits | [`CTRL-001`](05-domain-controller-reconciliation.md#ctrl-001--controller-confinement-and-gates)–[`CTRL-400`](05-domain-controller-reconciliation.md#ctrl-400--cleanuproot-handoff) |
| Android API bands, metrics, MediaProjection and VirtualDisplay | [`AND-MET-001`](06-domain-android-capture-metrics.md#and-met-001--source-identity-and-observation-ownership)–[`AND-CAP-070`](06-domain-android-capture-metrics.md#and-cap-070--safety-boundaries) |
| prepared/current target, ports, leases and release chain | [`TGT-001`](07-domain-target.md#tgt-001--files-and-private-declarations)–[`TGT-100`](07-domain-target.md#tgt-100--executable-obligations) |
| EGL/GLES2, Direct readback, color and raw `5 x 3` oracle | [`GL-001`](08-domain-gl.md#gl-001--files-owners-and-typed-boundary)–[`GL-081`](08-domain-gl.md#gl-081--raw-image-vector-and-tolerances) |
| Bitmap and Framework encoder mechanics | [`FJPEG-001`](09-domain-framework-jpeg.md#fjpeg-001--source-and-file-boundary)–[`FJPEG-100`](09-domain-framework-jpeg.md#fjpeg-100--executable-obligations) |
| loader, JNI/native runtime, Native encoder and packaging | [`NJPEG-001`](10-domain-native-jpeg.md#njpeg-001--files-binary-identity-and-authority)–[`NJPEG-120`](10-domain-native-jpeg.md#njpeg-120--executable-obligations) |
| transactional encoded storage and frame leases | [`STORE-001`](11-domain-encoded-storage.md#store-001--file-and-authority-boundary)–[`STORE-100`](11-domain-encoded-storage.md#store-100--executable-obligations) |
| pacing calculations, physical delivery/handoff, subscribe/unsubscribe, and unlocked State/Stats/diagnostic publication | [`DEL-PACE-001`](12-domain-delivery-observation.md#del-pace-001--pending-source-and-materialization)–[`DEL-OBS-020`](12-domain-delivery-observation.md#del-obs-020--diagnostic-construction-and-emission) |
| public schemas and caller-visible behavior | [`PROD-001`](02-product-contract.md#prod-001--product-and-support-boundary)–[`PROD-100`](02-product-contract.md#prod-100--explicit-v1-boundaries) |
| shared Framework/Native JPEG `64 x 48` test oracle | [`TEST-VECTOR-JPEG-01`](#test-vector-jpeg-01--shared-jpeg-64-x-48-oracle) in this file |

For one implementation slice, use its [router §5 packet](01-authority-router.md#5-closed-implementation-packets):
the named `ACC-*`, `B*`, `T*`, executable IDs, and their exact owner anchors.

## 2. Verification model

The suite uses deterministic unit tests for values and policy, controlled races for result- or owner-changing
interleavings, component tests at Android/GL/JPEG/storage boundaries, a small integration set, package/native
inspection, and a short physical-device smoke pass. Correctness tests use fakes, injected facts,
barriers, and an engine clock; they do not use real sleeps, scheduler luck, log parsing, repeated-race
heuristics, benchmarks, device allowlists, aggregate telemetry thresholds, or runtime path selection.

A host fake cannot replace the real Android, Bitmap, EGL/GLES2, native, or packaging suite assigned to the
boundary.

Ordinary visibility and API closure use Kotlin source and architecture checks. JVM bytecode inspection is
optional and reserved for a concrete critical concurrency/JMM, JNI/ABI, or platform question that direct
evidence cannot resolve; required native/JNI binary artifact checks remain in `NJPEG-PKG-001`.

### 2.1 Source sets and tasks

All paths are repository-relative through the aliases in
[the authority router](01-authority-router.md#4-module-and-path-bindings): `HOST:`, `AIT:`, `AI:`, and `NTCPP:`.

| Evidence | Runner/task | Required scope |
| --- | --- | --- |
| `HOST` | Gradle/JUnit via `testDebugUnitTest`; `verifyScreenCaptureEngineHost` | values, controller/races, arithmetic, ownership, storage, Stats and diagnostics; excludes `P-PKG` |
| `AIT`, `AI` | `androidx.test.runner.AndroidJUnitRunner` via one `connectedNativeTestAndroidTest`; `verifyScreenCaptureEngineDevice` and `verifyScreenCaptureEngineNative` both depend on it | public contract, real Android/EGL/Bitmap/native/cleanup/image behavior; absent managed Android branches may use production seams, but absent native branches are not claimed executed |
| built artifacts and sources | separate filtered Gradle/JUnit `Test` task selecting only the `P-PKG` package-verification suite; `verifyScreenCaptureEnginePackage` depends on exact `assembleDebug`, `assembleRelease`, `assembleNativeTest`, and `lintRelease` | every [router §4 module-baseline binding](01-authority-router.md#module-baseline), source-set/package boundary, and NJPEG-owned native artifact evidence |

`P-PKG` requires `lintRelease` to report no `NewApi` finding for any applicable Framework API-band production
code, with no local `NewApi` suppression and no module lint baseline. The `P-PKG` package-verification suite and source
inspection verify those facts.
The NDK, JNI, ABI, symbol, and native packaging requirements remain owned by
[`NJPEG-PKG-001`](10-domain-native-jpeg.md#njpeg-pkg-001--native-build-and-artifacts); `P-PKG` only supplies
their assigned artifact evidence.

`verifyScreenCaptureEngine` aggregates the three tasks. Device evidence uses an eligible connected Android
target. Vectors are programmatic test source; no generated verifier, parser, script, vector, or expected-result
task is introduced.

## 3. Common deterministic test method

### 3.1 Harness

The host harness supplies:

- independently set elapsed and wall clocks;
- controllable Control, Metrics, GL, JPEG, and Delivery executor seams covering prestart, outward
  return/throw-after-entry, poison, replacement-worker checks, one-ticket capacity where applicable, orderly
  shutdown, `terminated()` receipt, exact raw fatal-slot/engine-boundary identity, direct-`Error` thread-top
  identity, and runtime-shaped ordinary-TPE custom-Throwable thread top; plus a typed Android post/entry seam;
- a scheduler recording typed deadline/pacing/repeat/Stats link identity, generation/phase, submission,
  acceptance/rejection, exact Future, fire/body return, cancellation/suppression/outer-removal publications,
  operational settlement, physical outer-frame settlement, successor authority, and termination fallback;
- reusable barriers before the first wake CAS, after first `Active`, after `cancel(false)` returns but before the
  suppression CAS, after a successful suppression CAS but before its parent-gated publication, before outward and
  settlement-gate entry, after the deadline sample, after complete publication but before signalling, around
  controller application, and around cleanup transfer;
- a gate probe enforcing `sessionGate -> settlementGate` and absence of outward work while either gate is held;
- an allocation probe around prepared return publication, gate arbitration, fixed outside-gate action consumption,
  `Queued -> Running`, `Queued -> Suppressed`, guarded operational next-generation `Queued`, and its
  pre-increment/representation guard including exhaustion;
- typed boundary fakes and an ownership ledger for acquisition, transfer, lease, receipt, logical retirement,
  physical release, and exact quarantine identity.

The Android instrumentation harness supplies real Android call receipts, callback adapters, Surface fixtures,
private EGL/GLES2 support, Bitmap/JPEG decode helpers, and programmatic image vectors. Native harness support and
`NTCPP:screen_capture_engine_native_test_jni.cpp` supply only test-DSO controls around the unchanged JNI-free
runtime; they neither access production DSO state nor masquerade as execution of an absent production branch.

Every blocked/nonreturning test owns a release control and, in `finally`, releases barriers, drains the worker
or callback, and awaits the late mechanical receipt. No test may contaminate a later test with held work.

### 3.2 Time and wake rules

Tests assign elapsed time and invoke the recorded wake directly. Wall time and producer timestamps are never
control authority. Deep sleep advances elapsed time without running work, followed by one wake and at most one
current action. At behavior-changing boundaries, cases cover immediately before, exactly at, and after the
deadline. Equality belongs to expiry.

The public Stats cadence remains in [`PROD-070`](02-product-contract.md#prod-070--stats). Private readiness and Android
entered-operation durations live in [AND-CAP-040](06-domain-android-capture-metrics.md#and-cap-040--android-deadlines-and-call-policy),
the GL entered-operation duration in [GL-040](08-domain-gl.md#gl-040--fail-closed-gles-groups-and-context-integrity),
and the shared Framework/Native JPEG entered-operation duration in
[NJPEG-CONST-001](10-domain-native-jpeg.md#njpeg-const-001--jpeg-entered-operation-interval). Generic checked
deadline arithmetic is [CORE-SET-3](03-shared-runtime.md#core-set-3--finite-deadline-arbitration). Tests consume
those values; this file creates no duration authority. A timeout creates no retry, fallback, tuning, public
timeout code, stable message parser, or lifecycle control through diagnostics.

### 3.3 Settlement and terminal-transfer oracle

The behavior authority is [CORE-SET-1](03-shared-runtime.md#core-set-1--generic-operation-occurrence),
[CORE-SET-2](03-shared-runtime.md#core-set-2--entry-return-publication-and-lock-order),
[CORE-SET-3](03-shared-runtime.md#core-set-3--finite-deadline-arbitration), and
[CORE-CLEAN-2](03-shared-runtime.md#core-clean-2--quarantine-nonreturn-and-late-reduction). The shared harness
observes their existing occurrence, gate, return-cell, owner-bag, deadline, transfer, and cleanup facts; it does
not reproduce a settlement state machine.

The common race matrix pauses a worker before settlement-gate entry, after sampling `T`, and after complete
publication but before signalling. It crosses worker publication at `D - 1` and `D`, empty-cell expiry,
expiry-before-return, physical return before gate entry, and terminal selection against unentered and
entered/in-call/accepted work. Assertions consume the exact `CORE-SET-3` timely/expiry result and confirm that
late/nonreturn facts follow `CORE-CLEAN-2` without publication, revival, winner replacement, or fabricated
release. Metrics observation, queued/entered callbacks, readiness, and terminal/no-watchdog cleanup use
their linked named completion rules rather than this finite-deadline matrix.

### 3.4 Scheduler and fault rules

Scheduler-rejection tests observe [CORE-WAKE-2](03-shared-runtime.md#core-wake-2--one-shot-control-wake-link-and-scheduling-rejection)
and each linked domain disposition. `H-LC` verifies the `CTRL-040` scheduler construction/configuration before
any public publication and its startup-failure path. `H-OS` applies the same link matrix independently to deadline,
pacing/repeat, and Stats wakes. Deterministic barriers cover submission, Future publication, entry,
cancellation/suppression, body return/throw, terminal transfer, and successor selection. The matrix distinguishes
definite rejection from acceptance ambiguity; every cancel and outer-removal result, including throws;
currentness/ABA; operational settlement from physical wrapper settlement; stale return/nonreturn; single-worker
exclusion; shutdown; exhaustion; and termination. It asserts the exact `CORE-WAKE-2` outcomes, including zero
engine access after a stale entry loses, no fabricated no-entry proof from removal failure, and no successor
before all required publications.

The Control fatal harness supplies exact typed decorated wrappers and single- and double-`afterExecute` runtime
shapes. For `Error` and a custom non-`Exception` Throwable it asserts one runner capture, one settlement/poison/
emergency fact, `Pending -> Applied` exactly once, identical same-stack rethrow, inert second hook, unchanged
delegate ordering/Future behavior, successful `cancel(false)` removal of the exact outer wrapper, and zero
`get`/cause/reflection/hook-Throwable classification or fabricated receipt. Cleanup tests keep healthy Control as
the cleanup-only last lane until every other lane receipt, external fact, and other physical cleanup root settles;
the prequalified Android-owned final projection stop is its sole physical leaf action. A nonreturn retains that
dependency, and `terminated()` neither signals/resubmits nor revises public state.
`H-DL` and `A-CL` instead apply `CORE-EXEC-1` to Delivery submission/entry order, poison, one-ticket capacity,
exact raw fatal-slot identity and engine-boundary rethrow, direct-`Error` thread-top identity, permitted
runtime-shaped custom-Throwable thread top, exact cleanup/nontermination residue, and the one real endpoint-
termination receipt. No queued or entered
callback watchdog exists. None of these rows introduces a scheduler-progress watchdog.

Fault injection supplies only behavior-changing inputs: normal return,
documented failure/throwable, nonreturn after possible transfer, and late/stale return. Classification assertions
use [CORE-ALLOC-1](03-shared-runtime.md#core-alloc-1--checked-allocation-and-failure-partition) plus the owning
domain's result partition; this file defines neither precedence nor lifecycle action.

### 3.5 Ownership and image oracles

The harness ledger observes the outcomes defined by
[CORE-OWN-1](03-shared-runtime.md#core-own-1--ownership-loans-and-receipts) and
[CORE-CLEAN-1](03-shared-runtime.md#core-clean-1--cleanup-forest-and-dependency-graph): active ownership, matching
release receipt, named transfer, or exact quarantine residue. It never treats checked size, earlier allocation,
or managed-reference retirement as a later physical-release receipt.

Image tests use asymmetric corners, one-sided markers, odd dimensions, gradients, and grayscale ramps. Raw
geometry checks dimensions, landmarks, bounds, orientation, channels, rows, and alpha. JPEG checks successful
decode, exact dimensions/orientation, landmark placement, recognizable channel/grayscale relations, and no
catastrophic corruption; independently encoded lossy output is never byte- or pixel-equal. The GL-specific raw
vector and tolerances live in the GL domain; the cross-encoder JPEG vector lives only in
`TEST-VECTOR-JPEG-01` below.

### 3.6 Stats value oracle

`H-PS` checks the exact [PROD-070](02-product-contract.md#prod-070--stats) values from controller-owned facts:
every saturating component total, derived fields as calculations rather than independent counters, initial
`lastEncodedByteCount == 0`, current fresh success, stale-suppressed fresh success, and every documented
exclusion. `H-OB` verifies only faithful unlocked publication of the already-authoritative immutable Stats
snapshot; it neither recomputes nor owns expected counter values.

## 4. Executable namespaces

An ID denotes a table-driven behavioral slice, not one monolithic test. Canonical ID-to-test-path orientation is
in [router Section 7](01-authority-router.md#7-test-manifest); this file owns only verification responsibility.
The linked rules remain behavior authority; exact domain rows in Section 1 own full matrices and production-
boundary bindings.

| ID | Exact scope | Canonical owners |
| --- | --- | --- |
| `H-LC` | lifecycle/commands, terminal priority, lossless drainer, Session-scheduler construction and shutdown | [`CTRL-010`](05-domain-controller-reconciliation.md#ctrl-010--lossless-action-drainer), [`CTRL-020`](05-domain-controller-reconciliation.md#ctrl-020--public-command-application), [`CTRL-030`](05-domain-controller-reconciliation.md#ctrl-030--lifecycle-and-terminal-application), [`CTRL-040`](05-domain-controller-reconciliation.md#ctrl-040--session-scheduler-resource) |
| `H-RC` | currentness, geometry conflation, common pause/drain/serial/resume, topology reuse or physical replacement, fallback and convergence | [`CTRL-100`](05-domain-controller-reconciliation.md#ctrl-100--currentness-identities)–[`CTRL-130`](05-domain-controller-reconciliation.md#ctrl-130--completion-and-fallback-arbitration), [`GL-060`](08-domain-gl.md#gl-060--geometry-shader-color-and-readback) |
| `H-OS` | operation occurrence, private endpoint submission/poison/raw fatal authority and thread-top partition, settlement, all Control wake kinds, transfer, late return and quarantine | [`CORE-SET-1`](03-shared-runtime.md#core-set-1--generic-operation-occurrence)–[`CORE-WAKE-2`](03-shared-runtime.md#core-wake-2--one-shot-control-wake-link-and-scheduling-rejection), [`CORE-EXEC-1`](03-shared-runtime.md#core-exec-1--private-execute-endpoints), [`CORE-FATAL-1`](03-shared-runtime.md#core-fatal-1--direct-fatal-throwable-policy), [`CORE-CLEAN-1`](03-shared-runtime.md#core-clean-1--cleanup-forest-and-dependency-graph)–[`CORE-CLEAN-2`](03-shared-runtime.md#core-clean-2--quarantine-nonreturn-and-late-reduction) |
| `H-PS` | pacing/production, paused admission, cache preservation/invalidation/resume, repeat, storage roles and memory ledgers, exact Stats values | [`CTRL-200`](05-domain-controller-reconciliation.md#ctrl-200--policy-attempt-counter-and-observation-authority), [`DEL-PACE-001`](12-domain-delivery-observation.md#del-pace-001--pending-source-and-materialization)–[`DEL-PACE-020`](12-domain-delivery-observation.md#del-pace-020--repeat-cache-eligibility-and-output-commit), [`STORE-020`](11-domain-encoded-storage.md#store-020--closed-owner-roles)–[`STORE-100`](11-domain-encoded-storage.md#store-100--executable-obligations), [`PROD-070`](02-product-contract.md#prod-070--stats) |
| `H-DL` | registration persistence, paused new-handoff admission, admission-before-pause grandfathering, Delivery submission/entry/callback, unsubscribe, terminal cutoff and endpoint shutdown races | [`DEL-HO-001`](12-domain-delivery-observation.md#del-ho-001--one-registration-and-one-handoff)–[`DEL-HO-040`](12-domain-delivery-observation.md#del-ho-040--terminal-cutoff-and-delivery-cleanup), [`CTRL-200`](05-domain-controller-reconciliation.md#ctrl-200--policy-attempt-counter-and-observation-authority) |
| `H-GM` | checked API 24–37 geometry, color, bounds, exact Full/Downscaled arithmetic and eligibility | [`PROD-031`](02-product-contract.md#prod-031--geometry-and-visible-transform), [`CTRL-110`](05-domain-controller-reconciliation.md#ctrl-110--reconciliation-decision), [`GL-060`](08-domain-gl.md#gl-060--geometry-shader-color-and-readback) |
| `H-OB` | truthful direct Running variants, requested/Reconfiguring State assignment before outward effect, creation-time zero Stats with no accepted-start reassignment, Stats only on field change, diagnostics, cadence, terminal order and metrics-completion diagnostic | [`CTRL-200`](05-domain-controller-reconciliation.md#ctrl-200--policy-attempt-counter-and-observation-authority), [`CTRL-300`](05-domain-controller-reconciliation.md#ctrl-300--cross-domain-commit-rules), [`DEL-OBS-001`](12-domain-delivery-observation.md#del-obs-001--coherent-state-and-publication-order)–[`DEL-OBS-020`](12-domain-delivery-observation.md#del-obs-020--diagnostic-construction-and-emission), [`AND-MET-020`](06-domain-android-capture-metrics.md#and-met-020--source-observation-and-active-ordering) |
| `H-NL` | exhaustive own-load throwable/fence taxonomy, process loader/facade result and managed Native capability policy | [`NJPEG-040`](10-domain-native-jpeg.md#njpeg-040--own-dso-loader-and-bootstrap), [`NJPEG-060`](10-domain-native-jpeg.md#njpeg-060--weak-compressor-capability-and-non-ownership), [`CTRL-130`](05-domain-controller-reconciliation.md#ctrl-130--completion-and-fallback-arbitration) |
| `A-API` | public Kotlin inventory; private Kotlin constructors for closed engine-produced facades and internal `@JvmSynthetic` engine factories; negative external Kotlin source compilation for constructors and factories; externally implementable metrics source/observer/subscription; arbitrary-caller-thread validation and snapshot-only construction/getters | [`PROD-010`](02-product-contract.md#prod-010--public-session-api), [`PROD-020`](02-product-contract.md#prod-020--configuration-and-metrics), [`PROD-090`](02-product-contract.md#prod-090--values-observation-and-threading) |
| `A-SES` | installed direct lifecycle variants, per-frame exact effective descriptor, consumer, unsubscribe and exception behavior | [`PROD-010`](02-product-contract.md#prod-010--public-session-api), [`PROD-011`](02-product-contract.md#prod-011--start-cancellation-and-wrong-state-outcomes), [`PROD-040`](02-product-contract.md#prod-040--effective-output-and-borrowed-frame), [`PROD-050`](02-product-contract.md#prod-050--frame-delivery-caching-and-replacement), [`PROD-060`](02-product-contract.md#prod-060--state-and-errors), [`PROD-090`](02-product-contract.md#prod-090--values-observation-and-threading) |
| `A-CAP` | real API-band metrics/capture, VirtualDisplay/Surface geometry, readiness, rebuild and Android cleanup | [`AND-MET-001`](06-domain-android-capture-metrics.md#and-met-001--source-identity-and-observation-ownership)–[`AND-CAP-060`](06-domain-android-capture-metrics.md#and-cap-060--exact-androidmetrics-verification), [`CTRL-100`](05-domain-controller-reconciliation.md#ctrl-100--currentness-identities)–[`CTRL-130`](05-domain-controller-reconciliation.md#ctrl-130--completion-and-fallback-arbitration) |
| `A-GL` | real EGL/GLES2, Direct readback, geometry/color/image, Target bridge, GL faults and cleanup | [`GL-010`](08-domain-gl.md#gl-010--private-lane-admission-fatal-fence-and-termination)–[`GL-081`](08-domain-gl.md#gl-081--raw-image-vector-and-tolerances) |
| `A-FJ` | real Bitmap creation/transfer/reuse/recycle, Framework encode/transaction/decode and faults | [`FJPEG-020`](09-domain-framework-jpeg.md#fjpeg-020--installed-resources-and-reuse)–[`FJPEG-100`](09-domain-framework-jpeg.md#fjpeg-100--executable-obligations), [`TEST-VECTOR-JPEG-01`](#test-vector-jpeg-01--shared-jpeg-64-x-48-oracle) |
| `A-CL` | cleanup/quarantine dependencies, scheduler residue, terminal adoption with no active successor/resume authority, independent-root and cross-Session isolation | [`CORE-CLEAN-1`](03-shared-runtime.md#core-clean-1--cleanup-forest-and-dependency-graph)–[`CORE-CLEAN-2`](03-shared-runtime.md#core-clean-2--quarantine-nonreturn-and-late-reduction), [`CTRL-400`](05-domain-controller-reconciliation.md#ctrl-400--cleanuproot-handoff) |
| `N-JPEG` | production JNI black box; result arming and fixed-offset two-word protocol; full status/`P`/`M`/throwable partition; call-scoped RAII close, adoption, late/nonreturn, and native live-byte ledgers | [`NJPEG-020`](10-domain-native-jpeg.md#njpeg-020--session-runtime-jpeg-endpoint-and-legal-products), [`NJPEG-030`](10-domain-native-jpeg.md#njpeg-030--carrier-ownership-and-replacement), [`NJPEG-050`](10-domain-native-jpeg.md#njpeg-050--registration-and-frozen-jni-surface), [`NJPEG-070`](10-domain-native-jpeg.md#njpeg-070--preparation-descriptor-and-entered-interval)–[`NJPEG-100`](10-domain-native-jpeg.md#njpeg-100--cleanup-and-late-return), [`NJPEG-120`](10-domain-native-jpeg.md#njpeg-120--executable-obligations), [`TEST-VECTOR-JPEG-01`](#test-vector-jpeg-01--shared-jpeg-64-x-48-oracle) |
| `N-PKG` | installed own-DSO load/bootstrap, exact process-facade receiver, and connected-ABI structure | [`NJPEG-040`](10-domain-native-jpeg.md#njpeg-040--own-dso-loader-and-bootstrap), [`NJPEG-PKG-001`](10-domain-native-jpeg.md#njpeg-pkg-001--native-build-and-artifacts) |
| `P-PKG` | module SDK/JVM/namespace/explicit API/AAR/coroutines/source-set; exact NDK, exports, JNI inventory/descriptors and native artifacts | [router §4 module baseline](01-authority-router.md#module-baseline), [`NJPEG-PKG-001`](10-domain-native-jpeg.md#njpeg-pkg-001--native-build-and-artifacts) |

## 5. Acceptance scenarios

These 27 scenarios are the complete observable acceptance index. Detailed assertions are routed rather than
repeated.

| ID | Required observation | Tests | Requirement owners |
| --- | --- | --- | --- |
| `ACC-01` | Packaged public inventory, direct Running hierarchy, compact effective descriptor, metrics extension interfaces, defaults and value semantics match the public contract; closed engine-produced facade constructors are private in Kotlin, engine construction uses internal `@JvmSynthetic` factories, and external Kotlin source cannot call either. | `A-API`, `P-PKG`, `N-PKG` | [`PROD-001`](02-product-contract.md#prod-001--product-and-support-boundary), [`PROD-010`](02-product-contract.md#prod-010--public-session-api), [`PROD-020`](02-product-contract.md#prod-020--configuration-and-metrics), [`PROD-030`](02-product-contract.md#prod-030--capture-parameters), [`PROD-090`](02-product-contract.md#prod-090--values-observation-and-threading) |
| `ACC-02` | Invalid local scalar/structural input throws before work; valid boundaries are accepted without clamping. | `A-API`, `H-GM` | [`PROD-020`](02-product-contract.md#prod-020--configuration-and-metrics), [`PROD-030`](02-product-contract.md#prod-030--capture-parameters), [`CTRL-110`](05-domain-controller-reconciliation.md#ctrl-110--reconciliation-decision) |
| `ACC-03` | Exactly one start wins; every loser performs zero access or consumption of its projection. | `H-LC`, `A-SES` | [`CTRL-020`](05-domain-controller-reconciliation.md#ctrl-020--public-command-application) |
| `ACC-04` | Start returns only after visible Running; startup failure/cancellation publishes and throws the exact terminal outcome. | `H-LC`, `A-SES`, `H-OB` | [`PROD-011`](02-product-contract.md#prod-011--start-cancellation-and-wrong-state-outcomes), [`CTRL-030`](05-domain-controller-reconciliation.md#ctrl-030--lifecycle-and-terminal-application), [`DEL-OBS-001`](12-domain-delivery-observation.md#del-obs-001--coherent-state-and-publication-order) |
| `ACC-05` | Stop synchronously fixes the terminal winner and closes admission; repeated stop is harmless and cleanup/publication may finish later. | `H-LC`, `A-SES`, `A-CL` | [`PROD-010`](02-product-contract.md#prod-010--public-session-api), [`CTRL-030`](05-domain-controller-reconciliation.md#ctrl-030--lifecycle-and-terminal-application), [`CORE-CLEAN-1`](03-shared-runtime.md#core-clean-1--cleanup-forest-and-dependency-graph) |
| `ACC-06` | Unequal Running updates totally order latest-wins desires, logically commit requested/Reconfiguring, and serialize one pause/drain/reconfiguration/resume cycle; equality with current requested is an absolute no-op while paused. | `H-LC`, `H-RC`, `H-OB` | [`PROD-030`](02-product-contract.md#prod-030--capture-parameters), [`PROD-090`](02-product-contract.md#prod-090--values-observation-and-threading), [`CTRL-020`](05-domain-controller-reconciliation.md#ctrl-020--public-command-application), [`CTRL-100`](05-domain-controller-reconciliation.md#ctrl-100--currentness-identities) |
| `ACC-07` | Reconciliation closes Production/frame Delivery, drains relevant predecessors and exact Target work, serializes leaf mechanics, reuses healthy compatible owners or rebuilds only required scope, and never publishes stale safe work; unsafe ambiguity remains terminal. | `H-RC`, `H-OS`, `A-CAP` | [`CTRL-100`](05-domain-controller-reconciliation.md#ctrl-100--currentness-identities)–[`CTRL-130`](05-domain-controller-reconciliation.md#ctrl-130--completion-and-fallback-arbitration), [`CORE-SET-2`](03-shared-runtime.md#core-set-2--entry-return-publication-and-lock-order), [`CORE-CLEAN-2`](03-shared-runtime.md#core-clean-2--quarantine-nonreturn-and-late-reduction) |
| `ACC-08` | The exact configured metrics source supplies required density/display association; null config is Session-private default policy; API-band dimension authority is correct. | `A-CAP`, `H-OB` | [`PROD-020`](02-product-contract.md#prod-020--configuration-and-metrics), [`AND-MET-001`](06-domain-android-capture-metrics.md#and-met-001--source-identity-and-observation-ownership), [`AND-MET-010`](06-domain-android-capture-metrics.md#and-met-010--exact-api-band-reads-and-authority) |
| `ACC-09` | Bounded metrics staging arms exact readiness before observation start, requires timely first-valid plus exact handle adoption as one joint commit, maps first-positive/initial-resize expiry to `CaptureUnavailable` with real late results cleanup-only, preserves terminal phase/pre-Active loss, closes once after full attachment-ticket settlement, and applies full-key duplicate/no-op and recovery semantics. | `A-CAP`, `A-CL`, `H-OB`, `H-RC` | [`AND-MET-011`](06-domain-android-capture-metrics.md#and-met-011--epoch-invalidation-and-latest-geometry), [`AND-MET-020`](06-domain-android-capture-metrics.md#and-met-020--source-observation-and-active-ordering), [`AND-MET-021`](06-domain-android-capture-metrics.md#and-met-021--source-mechanical-outcome-partition), [`AND-CAP-040`](06-domain-android-capture-metrics.md#and-cap-040--android-deadlines-and-call-policy), [`CTRL-100`](05-domain-controller-reconciliation.md#ctrl-100--currentness-identities) |
| `ACC-10` | The sole VirtualDisplay creation attempt classifies null, SecurityException, direct OOM, IllegalStateException and unexpected throw exactly. | `A-CAP` | [`AND-CAP-010`](06-domain-android-capture-metrics.md#and-cap-010--projection-and-virtualdisplay-calls), [`CTRL-300`](05-domain-controller-reconciliation.md#ctrl-300--cross-domain-commit-rules) |
| `ACC-11` | Region/crop/rotation/mirror/size/OES/orientation/color/grayscale produce the specified dimensions and semantic image in Full and eligible Downscaled paths. | `H-GM`, `A-GL`, `A-FJ`, `N-JPEG` | [`PROD-031`](02-product-contract.md#prod-031--geometry-and-visible-transform), [`GL-050`](08-domain-gl.md#gl-050--target-oes-bridge-and-gl-construction), [`GL-060`](08-domain-gl.md#gl-060--geometry-shader-color-and-readback), [`TEST-VECTOR-JPEG-01`](#test-vector-jpeg-01--shared-jpeg-64-x-48-oracle) |
| `ACC-12` | sRGB, exact Display-P3 and unsupported wide/HDR input follow their specified color action and diagnostic. | `H-GM`, `A-GL` | [`PROD-031`](02-product-contract.md#prod-031--geometry-and-visible-transform), [`GL-060`](08-domain-gl.md#gl-060--geometry-shader-color-and-readback), [`CTRL-300`](05-domain-controller-reconciliation.md#ctrl-300--cross-domain-commit-rules), [`DEL-OBS-020`](12-domain-delivery-observation.md#del-obs-020--diagnostic-construction-and-emission) |
| `ACC-13` | Auto, MaxFps and SampleEvery admit fresh work by elapsed time only while Active; pending and wake cardinalities prevent lost work, catch-up bursts and false drops. | `H-PS` | [`PROD-050`](02-product-contract.md#prod-050--frame-delivery-caching-and-replacement), [`CTRL-200`](05-domain-controller-reconciliation.md#ctrl-200--policy-attempt-counter-and-observation-authority), [`DEL-PACE-001`](12-domain-delivery-observation.md#del-pace-001--pending-source-and-materialization), [`DEL-PACE-010`](12-domain-delivery-observation.md#del-pace-010--clock-cadence-and-one-wake) |
| `ACC-14` | Static repeat republishes only an Active current cache with new metadata, no GL/JPEG work, correct tie priority and MaxFps cap; pause admits no repeat. | `H-PS` | [`PROD-050`](02-product-contract.md#prod-050--frame-delivery-caching-and-replacement), [`CTRL-200`](05-domain-controller-reconciliation.md#ctrl-200--policy-attempt-counter-and-observation-authority), [`DEL-PACE-020`](12-domain-delivery-observation.md#del-pace-020--repeat-cache-eligibility-and-output-commit), [`STORE-070`](11-domain-encoded-storage.md#store-070--publish-cache-repeat-lease-and-retirement) |
| `ACC-15` | Fresh/repeat/cached/rejected/pause/terminal paths update cache and Stats exactly; policy-only pause may preserve bytes subject to resume currentness, while image/backend/topology change invalidates them. | `H-PS`, `H-OB`, `H-OS` | [`CTRL-200`](05-domain-controller-reconciliation.md#ctrl-200--policy-attempt-counter-and-observation-authority), [`CTRL-300`](05-domain-controller-reconciliation.md#ctrl-300--cross-domain-commit-rules), [`DEL-OBS-010`](12-domain-delivery-observation.md#del-obs-010--stats-accounting-and-cadence), [`STORE-070`](11-domain-encoded-storage.md#store-070--publish-cache-repeat-lease-and-retirement), [`CORE-CLEAN-2`](03-shared-runtime.md#core-clean-2--quarantine-nonreturn-and-late-reduction) |
| `ACC-16` | The baseline Full + Direct + Framework path produces a decodable exact-size/orientation JPEG without exposing raw/partial pixels. | `A-GL`, `A-FJ`, `N-JPEG`, `P-PKG` | [`GL-060`](08-domain-gl.md#gl-060--geometry-shader-color-and-readback), [`FJPEG-040`](09-domain-framework-jpeg.md#fjpeg-040--exact-carrier-to-bitmap-transfer), [`FJPEG-050`](09-domain-framework-jpeg.md#fjpeg-050--framework-encode-and-transaction), [`STORE-050`](11-domain-encoded-storage.md#store-050--commit-immutable-payload-and-caller-copies), [`TEST-VECTOR-JPEG-01`](#test-vector-jpeg-01--shared-jpeg-64-x-48-oracle) |
| `ACC-17` | Downscale, Native JPEG and Display-P3 use only their closed capability rules; optimized-axis health and fallback remain independent. | `H-RC`, `H-NL`, `A-CAP`, `A-GL`, `N-JPEG`, `N-PKG` | [`CTRL-110`](05-domain-controller-reconciliation.md#ctrl-110--reconciliation-decision), [`CTRL-130`](05-domain-controller-reconciliation.md#ctrl-130--completion-and-fallback-arbitration), [`GL-030`](08-domain-gl.md#gl-030--capability-and-admission), [`GL-060`](08-domain-gl.md#gl-060--geometry-shader-color-and-readback), [`NJPEG-060`](10-domain-native-jpeg.md#njpeg-060--weak-compressor-capability-and-non-ownership), [`NJPEG-090`](10-domain-native-jpeg.md#njpeg-090--native-encode-result-and-fallback) |
| `ACC-18` | Safe optional-path return may discard the specified frame(s) then use fallback; ambiguous transfer, unsafe cleanup or nonreturn is terminal and quarantined. | `H-OS`, `H-RC`, `H-NL`, `A-CAP`, `A-FJ`, `N-JPEG`, `A-CL` | [CTRL-130](05-domain-controller-reconciliation.md#ctrl-130--completion-and-fallback-arbitration), [CORE-CLEAN-1](03-shared-runtime.md#core-clean-1--cleanup-forest-and-dependency-graph), [CORE-CLEAN-2](03-shared-runtime.md#core-clean-2--quarantine-nonreturn-and-late-reduction), [TGT-090](07-domain-target.md#tgt-090--successor-admission-cleanup-and-nonreturn), [GL-070](08-domain-gl.md#gl-070--destruction-namespace-poison-and-cleanup-suffix), [FJPEG-050](09-domain-framework-jpeg.md#fjpeg-050--framework-encode-and-transaction), [FJPEG-070](09-domain-framework-jpeg.md#fjpeg-070--cleanup-cancellation-and-lane-ownership), [NJPEG-090](10-domain-native-jpeg.md#njpeg-090--native-encode-result-and-fallback), [NJPEG-100](10-domain-native-jpeg.md#njpeg-100--cleanup-and-late-return) |
| `ACC-19` | A Session has zero or one current/draining consumer; replacement is rejected until successful unsubscribe. | `H-DL`, `A-SES` | [`PROD-050`](02-product-contract.md#prod-050--frame-delivery-caching-and-replacement), [`CTRL-200`](05-domain-controller-reconciliation.md#ctrl-200--policy-attempt-counter-and-observation-authority), [`DEL-HO-001`](12-domain-delivery-observation.md#del-ho-001--one-registration-and-one-handoff), [`DEL-HO-030`](12-domain-delivery-observation.md#del-ho-030--unsubscribe-and-replacement) |
| `ACC-20` | Active registration offers a valid cache first with original immutable bytes, sequence, timestamp, and effective descriptor; paused registration persists but offers nothing until resume currentness succeeds. | `H-PS`, `H-DL`, `A-SES` | [`PROD-050`](02-product-contract.md#prod-050--frame-delivery-caching-and-replacement), [`CTRL-200`](05-domain-controller-reconciliation.md#ctrl-200--policy-attempt-counter-and-observation-authority), [`DEL-HO-001`](12-domain-delivery-observation.md#del-ho-001--one-registration-and-one-handoff), [`STORE-070`](11-domain-encoded-storage.md#store-070--publish-cache-repeat-lease-and-retirement) |
| `ACC-21` | Callback and submission sides settle independently. The two required edges are admission-first (old descriptor may drain in Reconfiguring without blocking it) and pause-first (no handoff); serial Delivery preserves nonoverlap and ordinary busy/drop accounting. | `H-DL`, `H-OS`, `A-CL` | [`PROD-050`](02-product-contract.md#prod-050--frame-delivery-caching-and-replacement), [`CTRL-120`](05-domain-controller-reconciliation.md#ctrl-120--destructive-transition-order), [`DEL-HO-001`](12-domain-delivery-observation.md#del-ho-001--one-registration-and-one-handoff), [`DEL-HO-010`](12-domain-delivery-observation.md#del-ho-010--submission-entry-callback-and-classification) |
| `ACC-22` | Unsubscribe closes immediately but awaits the full handoff, is idempotent, rejects self-call, preserves waiter cancellation, and permits replacement only on shared success. | `H-DL`, `A-SES` | [`PROD-050`](02-product-contract.md#prod-050--frame-delivery-caching-and-replacement), [`CTRL-200`](05-domain-controller-reconciliation.md#ctrl-200--policy-attempt-counter-and-observation-authority), [`DEL-HO-030`](12-domain-delivery-observation.md#del-ho-030--unsubscribe-and-replacement) |
| `ACC-23` | A Session owns exactly six named threads; every private endpoint is prestarted, bounded, poison-on-outward-throw, and termination-receipted. Raw fatal authority is identity-preserved; Control/Android and direct `Error` retain thread-top identity, while ordinary-TPE custom-Throwable thread-top shape is runtime-defined. | `H-LC`, `H-DL`, `H-OS`, `A-CAP`, `A-GL`, `A-FJ` | [`CORE-EXEC-1`](03-shared-runtime.md#core-exec-1--private-execute-endpoints), [`CORE-FATAL-1`](03-shared-runtime.md#core-fatal-1--direct-fatal-throwable-policy), [`CTRL-040`](05-domain-controller-reconciliation.md#ctrl-040--session-scheduler-resource), [`AND-CAP-001`](06-domain-android-capture-metrics.md#and-cap-001--android-lane-and-startup-sequence) |
| `ACC-24` | Checked bounds, one live topology, exact-compatible reuse, typed target authority, leases and actual allocation outcomes bound resources and keep raw/partial/stale/unleased data private. | `H-GM`, `H-RC`, `H-PS`, `H-OS`, `A-GL`, `A-FJ`, `N-JPEG`, `P-PKG` | [CORE-OWN-1](03-shared-runtime.md#core-own-1--ownership-loans-and-receipts), [CORE-ALLOC-1](03-shared-runtime.md#core-alloc-1--checked-allocation-and-failure-partition), [CORE-EFF-1](03-shared-runtime.md#core-eff-1--structural-efficiency-and-reuse), [CORE-PRIV-1](03-shared-runtime.md#core-priv-1--data-lifetime-and-privacy), [CTRL-110](05-domain-controller-reconciliation.md#ctrl-110--reconciliation-decision), [TGT-020](07-domain-target.md#tgt-020--preparation-and-single-disposition), [TGT-030](07-domain-target.md#tgt-030--currenttarget-ports-leases-and-provenance), [GL-030](08-domain-gl.md#gl-030--capability-and-admission), [GL-050](08-domain-gl.md#gl-050--target-oes-bridge-and-gl-construction), [FJPEG-020](09-domain-framework-jpeg.md#fjpeg-020--installed-resources-and-reuse), [FJPEG-040](09-domain-framework-jpeg.md#fjpeg-040--exact-carrier-to-bitmap-transfer), [NJPEG-030](10-domain-native-jpeg.md#njpeg-030--carrier-ownership-and-replacement), [STORE-020](11-domain-encoded-storage.md#store-020--closed-owner-roles), [STORE-090](11-domain-encoded-storage.md#store-090--structural-memory-and-copy-policy) |
| `ACC-25` | One terminal winner is published; independent cleanup continues in order; healthy Control is cleanup-only and terminates last, with the prequalified Android-owned final projection stop as its sole physical leaf action. Unresolved exact residue is rooted once, never treated as released/reusable, and never authorizes active successor or resume. | `H-OS`, `H-RC`, `H-DL`, `A-CAP`, `A-GL`, `A-FJ`, `N-JPEG`, `A-CL` | [CTRL-030](05-domain-controller-reconciliation.md#ctrl-030--lifecycle-and-terminal-application), [CTRL-400](05-domain-controller-reconciliation.md#ctrl-400--cleanuproot-handoff), [CORE-CLEAN-1](03-shared-runtime.md#core-clean-1--cleanup-forest-and-dependency-graph), [CORE-CLEAN-2](03-shared-runtime.md#core-clean-2--quarantine-nonreturn-and-late-reduction), [AND-MET-030](06-domain-android-capture-metrics.md#and-met-030--built-in-close-and-residue), [AND-CAP-030](06-domain-android-capture-metrics.md#and-cap-030--mutation-currentness-and-cleanup), [TGT-070](07-domain-target.md#tgt-070--surface-and-targetscope-destruction), [TGT-080](07-domain-target.md#tgt-080--context-poison-transfer), [TGT-090](07-domain-target.md#tgt-090--successor-admission-cleanup-and-nonreturn), [GL-010](08-domain-gl.md#gl-010--private-lane-admission-fatal-fence-and-termination), [GL-070](08-domain-gl.md#gl-070--destruction-namespace-poison-and-cleanup-suffix), [FJPEG-060](09-domain-framework-jpeg.md#fjpeg-060--bitmap-retirement-and-recycle), [FJPEG-070](09-domain-framework-jpeg.md#fjpeg-070--cleanup-cancellation-and-lane-ownership), [NJPEG-100](10-domain-native-jpeg.md#njpeg-100--cleanup-and-late-return), [STORE-080](11-domain-encoded-storage.md#store-080--failure-terminal-and-cleanup), [DEL-HO-040](12-domain-delivery-observation.md#del-ho-040--terminal-cutoff-and-delivery-cleanup) |
| `ACC-26` | Diagnostics preserve sequence/timestamp/source/label/message/cause: ordinary CapabilityCheck names boundary/decision/action with exact cause when present, and exactly one normal post-readiness MetricsSource completion event occurs per exact observation lifetime without controlling behavior. | `H-OB`, `A-CAP`, `A-GL`, `A-FJ`, `N-JPEG` | [`PROD-080`](02-product-contract.md#prod-080--diagnostics), [`CTRL-300`](05-domain-controller-reconciliation.md#ctrl-300--cross-domain-commit-rules), [`DEL-OBS-020`](12-domain-delivery-observation.md#del-obs-020--diagnostic-construction-and-emission) |
| `ACC-27` | Sessions share no lifecycle, projection, desire, cache, consumer, metrics runtime state, owned endpoint/thread, counters, failure or quarantine authority. | `H-LC`, `H-RC`, `A-CAP`, `A-GL`, `A-CL` | [CORE-EFF-1](03-shared-runtime.md#core-eff-1--structural-efficiency-and-reuse), [CTRL-001](05-domain-controller-reconciliation.md#ctrl-001--controller-confinement-and-gates), [AND-MET-030](06-domain-android-capture-metrics.md#and-met-030--built-in-close-and-residue), [AND-CAP-060](06-domain-android-capture-metrics.md#and-cap-060--exact-androidmetrics-verification), [TGT-090](07-domain-target.md#tgt-090--successor-admission-cleanup-and-nonreturn), [TGT-095](07-domain-target.md#tgt-095--concurrency-and-publication), [GL-010](08-domain-gl.md#gl-010--private-lane-admission-fatal-fence-and-termination), [FJPEG-070](09-domain-framework-jpeg.md#fjpeg-070--cleanup-cancellation-and-lane-ownership), [NJPEG-020](10-domain-native-jpeg.md#njpeg-020--session-runtime-jpeg-endpoint-and-legal-products), [STORE-080](11-domain-encoded-storage.md#store-080--failure-terminal-and-cleanup), [DEL-HO-040](12-domain-delivery-observation.md#del-ho-040--terminal-cutoff-and-delivery-cleanup) |

## 6. Verification slices

This index preserves all B1–B21 closure obligations. Linked owners hold exact behavior matrices and call counts;
test-only oracle ownership follows Section 1.

| ID | Closure obligation | Tests | Exact detail owner |
| --- | --- | --- | --- |
| B1 | lifecycle, one-shot start, latest wins, truthful requested/Reconfiguring commit, action-batch rescan and one serialized reconfiguration cycle | `H-LC`, `H-RC`, `A-SES` | [`CTRL-010`](05-domain-controller-reconciliation.md#ctrl-010--lossless-action-drainer), [`CTRL-020`](05-domain-controller-reconciliation.md#ctrl-020--public-command-application), [`CTRL-100`](05-domain-controller-reconciliation.md#ctrl-100--currentness-identities), [`CTRL-120`](05-domain-controller-reconciliation.md#ctrl-120--destructive-transition-order) |
| B2 | bounded metrics summary/readiness/close, API geometry/visibility, full-key duplicate/no-op, recovery and pre-Active ordering | `A-API`, `A-CAP`, `A-CL`, `H-OB`, `H-RC` | [`AND-MET-001`](06-domain-android-capture-metrics.md#and-met-001--source-identity-and-observation-ownership)–[`AND-MET-030`](06-domain-android-capture-metrics.md#and-met-030--built-in-close-and-residue), [`AND-CAP-060`](06-domain-android-capture-metrics.md#and-cap-060--exact-androidmetrics-verification), [`CTRL-100`](05-domain-controller-reconciliation.md#ctrl-100--currentness-identities) |
| B3 | transforms, color/grayscale, JPEG structure and corruption | `H-GM`, `A-GL`, `A-FJ`, `N-JPEG` | [`PROD-031`](02-product-contract.md#prod-031--geometry-and-visible-transform), [`GL-060`](08-domain-gl.md#gl-060--geometry-shader-color-and-readback), [`FJPEG-040`](09-domain-framework-jpeg.md#fjpeg-040--exact-carrier-to-bitmap-transfer), [`FJPEG-050`](09-domain-framework-jpeg.md#fjpeg-050--framework-encode-and-transaction), [`NJPEG-080`](10-domain-native-jpeg.md#njpeg-080--writer-capsule-result-block-and-adoption), [`TEST-VECTOR-JPEG-01`](#test-vector-jpeg-01--shared-jpeg-64-x-48-oracle) |
| B4 | pacing/repeat/cache, paused admission and resume currentness, production slot, pending-source exchange, central pacing/repeat and Stats wake links, and exact `PROD-070` values | `H-PS`, `H-OS` | [`CORE-WAKE-2`](03-shared-runtime.md#core-wake-2--one-shot-control-wake-link-and-scheduling-rejection), [`CTRL-200`](05-domain-controller-reconciliation.md#ctrl-200--policy-attempt-counter-and-observation-authority), [`DEL-PACE-001`](12-domain-delivery-observation.md#del-pace-001--pending-source-and-materialization)–[`DEL-PACE-020`](12-domain-delivery-observation.md#del-pace-020--repeat-cache-eligibility-and-output-commit), [`STORE-070`](11-domain-encoded-storage.md#store-070--publish-cache-repeat-lease-and-retirement), [`PROD-070`](02-product-contract.md#prod-070--stats) |
| B5 | production-terminal cutoff and exact drop accounting | `H-PS`, `H-OS` | [`CTRL-200`](05-domain-controller-reconciliation.md#ctrl-200--policy-attempt-counter-and-observation-authority), [`CTRL-300`](05-domain-controller-reconciliation.md#ctrl-300--cross-domain-commit-rules), [`DEL-OBS-010`](12-domain-delivery-observation.md#del-obs-010--stats-accounting-and-cadence), [`CORE-CLEAN-2`](03-shared-runtime.md#core-clean-2--quarantine-nonreturn-and-late-reduction) |
| B6 | Full/Downscaled, Direct readback and independent JPEG fallback | `H-GM`, `A-CAP`, `A-GL`, `A-FJ`, `N-JPEG` | [`CTRL-110`](05-domain-controller-reconciliation.md#ctrl-110--reconciliation-decision), [`CTRL-130`](05-domain-controller-reconciliation.md#ctrl-130--completion-and-fallback-arbitration), [`GL-060`](08-domain-gl.md#gl-060--geometry-shader-color-and-readback), [`FJPEG-050`](09-domain-framework-jpeg.md#fjpeg-050--framework-encode-and-transaction), [`NJPEG-090`](10-domain-native-jpeg.md#njpeg-090--native-encode-result-and-fallback) |
| B7 | registration/unsubscribe, direct callback `Exception` and no callback watchdog | `H-DL`, `A-SES` | [`PROD-050`](02-product-contract.md#prod-050--frame-delivery-caching-and-replacement), [`CTRL-200`](05-domain-controller-reconciliation.md#ctrl-200--policy-attempt-counter-and-observation-authority), [`DEL-HO-001`](12-domain-delivery-observation.md#del-ho-001--one-registration-and-one-handoff), [`DEL-HO-010`](12-domain-delivery-observation.md#del-ho-010--submission-entry-callback-and-classification), [`DEL-HO-030`](12-domain-delivery-observation.md#del-ho-030--unsubscribe-and-replacement) |
| B8 | internal `execute` result versus Delivery Runnable entry | `H-DL` | [`CTRL-200`](05-domain-controller-reconciliation.md#ctrl-200--policy-attempt-counter-and-observation-authority), [`DEL-HO-010`](12-domain-delivery-observation.md#del-ho-010--submission-entry-callback-and-classification), [`CORE-EXEC-1`](03-shared-runtime.md#core-exec-1--private-execute-endpoints) |
| B9 | callback-return settlement releases authority/lease immediately; unresolved submission keeps `Entered` busy, makes unsubscribe wait, and leaves only ticket/endpoint residue for terminal rooting | `H-DL` | [`CTRL-200`](05-domain-controller-reconciliation.md#ctrl-200--policy-attempt-counter-and-observation-authority), [`DEL-HO-010`](12-domain-delivery-observation.md#del-ho-010--submission-entry-callback-and-classification) |
| B10 | callback gate ordering; handoff-admission-before-pause versus pause-before-admission; grandfathered callback independence, serial nonoverlap after resume, and terminal cutoff | `H-DL`, `H-OS`, `A-CL` | [`CTRL-300`](05-domain-controller-reconciliation.md#ctrl-300--cross-domain-commit-rules), [`DEL-HO-010`](12-domain-delivery-observation.md#del-ho-010--submission-entry-callback-and-classification), [`DEL-HO-040`](12-domain-delivery-observation.md#del-ho-040--terminal-cutoff-and-delivery-cleanup), [`CORE-SET-2`](03-shared-runtime.md#core-set-2--entry-return-publication-and-lock-order), [`CORE-CLEAN-2`](03-shared-runtime.md#core-clean-2--quarantine-nonreturn-and-late-reduction) |
| B11 | active Delivery `execute` failure, poison, raw fatal authority/thread-top partition, and termination receipt | `H-DL`, `H-OS` | [`CTRL-030`](05-domain-controller-reconciliation.md#ctrl-030--lifecycle-and-terminal-application), [`DEL-HO-010`](12-domain-delivery-observation.md#del-ho-010--submission-entry-callback-and-classification), [`CORE-EXEC-1`](03-shared-runtime.md#core-exec-1--private-execute-endpoints), [`CORE-FATAL-1`](03-shared-runtime.md#core-fatal-1--direct-fatal-throwable-policy) |
| B12 | sole VirtualDisplay creation, callback authority and explicit cleanup | `A-CAP` | [`AND-CAP-010`](06-domain-android-capture-metrics.md#and-cap-010--projection-and-virtualdisplay-calls), [`AND-CAP-030`](06-domain-android-capture-metrics.md#and-cap-030--mutation-currentness-and-cleanup), [`CTRL-300`](05-domain-controller-reconciliation.md#ctrl-300--cross-domain-commit-rules) |
| B13 | complete prepared/current Target, one-operation ports/leases and Surface release | `A-CAP`, `A-GL`, `H-OS`, `A-CL` | [`TGT-020`](07-domain-target.md#tgt-020--preparation-and-single-disposition), [`TGT-030`](07-domain-target.md#tgt-030--currenttarget-ports-leases-and-provenance), [`TGT-070`](07-domain-target.md#tgt-070--surface-and-targetscope-destruction), [`AND-CAP-020`](06-domain-android-capture-metrics.md#and-cap-020--target-facing-typed-boundary), [`AND-CAP-040`](06-domain-android-capture-metrics.md#and-cap-040--android-deadlines-and-call-policy), [`GL-070`](08-domain-gl.md#gl-070--destruction-namespace-poison-and-cleanup-suffix), [`CORE-CLEAN-1`](03-shared-runtime.md#core-clean-1--cleanup-forest-and-dependency-graph) |
| B14 | checked bounds/OOM, stale fencing, GL poison and exact quarantine | `H-GM`, `H-RC`, `H-PS`, `H-OS`, `A-CAP`, `A-GL`, `A-FJ`, `N-JPEG`, `A-CL` | [CORE-ALLOC-1](03-shared-runtime.md#core-alloc-1--checked-allocation-and-failure-partition), [CORE-CLEAN-2](03-shared-runtime.md#core-clean-2--quarantine-nonreturn-and-late-reduction), [CTRL-110](05-domain-controller-reconciliation.md#ctrl-110--reconciliation-decision), [CTRL-130](05-domain-controller-reconciliation.md#ctrl-130--completion-and-fallback-arbitration), [AND-CAP-010](06-domain-android-capture-metrics.md#and-cap-010--projection-and-virtualdisplay-calls), [TGT-090](07-domain-target.md#tgt-090--successor-admission-cleanup-and-nonreturn), [GL-030](08-domain-gl.md#gl-030--capability-and-admission), [GL-040](08-domain-gl.md#gl-040--fail-closed-gles-groups-and-context-integrity), [GL-070](08-domain-gl.md#gl-070--destruction-namespace-poison-and-cleanup-suffix), [FJPEG-031](09-domain-framework-jpeg.md#fjpeg-031--metadata-and-creation-outcome), [FJPEG-050](09-domain-framework-jpeg.md#fjpeg-050--framework-encode-and-transaction), [FJPEG-070](09-domain-framework-jpeg.md#fjpeg-070--cleanup-cancellation-and-lane-ownership), [NJPEG-030](10-domain-native-jpeg.md#njpeg-030--carrier-ownership-and-replacement), [NJPEG-090](10-domain-native-jpeg.md#njpeg-090--native-encode-result-and-fallback), [NJPEG-100](10-domain-native-jpeg.md#njpeg-100--cleanup-and-late-return), [STORE-080](11-domain-encoded-storage.md#store-080--failure-terminal-and-cleanup) |
| B15 | exact-compatible reuse and smallest replacement, including distinct carrier occurrences | `H-RC`, `H-OS`, `A-CL` | [`CTRL-110`](05-domain-controller-reconciliation.md#ctrl-110--reconciliation-decision), [`CTRL-120`](05-domain-controller-reconciliation.md#ctrl-120--destructive-transition-order), [`NJPEG-030`](10-domain-native-jpeg.md#njpeg-030--carrier-ownership-and-replacement), [`STORE-020`](11-domain-encoded-storage.md#store-020--closed-owner-roles), [`STORE-090`](11-domain-encoded-storage.md#store-090--structural-memory-and-copy-policy) |
| B16 | Target replacement only after predecessor mechanical retirement and required receipts, while compatible output owners retain identity; terminal cleanup adoption creates no active successor | `H-RC`, `A-CAP`, `A-GL`, `A-CL` | [`CTRL-110`](05-domain-controller-reconciliation.md#ctrl-110--reconciliation-decision), [`CTRL-120`](05-domain-controller-reconciliation.md#ctrl-120--destructive-transition-order), [`CTRL-400`](05-domain-controller-reconciliation.md#ctrl-400--cleanuproot-handoff), [`TGT-090`](07-domain-target.md#tgt-090--successor-admission-cleanup-and-nonreturn), [`GL-050`](08-domain-gl.md#gl-050--target-oes-bridge-and-gl-construction) |
| B17 | Bitmap reuse, one-shot recycle and fresh replacement eligibility | `H-RC`, `H-OS`, `A-FJ`, `A-CL` | [`CTRL-110`](05-domain-controller-reconciliation.md#ctrl-110--reconciliation-decision), [`CTRL-120`](05-domain-controller-reconciliation.md#ctrl-120--destructive-transition-order), [`FJPEG-020`](09-domain-framework-jpeg.md#fjpeg-020--installed-resources-and-reuse), [`FJPEG-060`](09-domain-framework-jpeg.md#fjpeg-060--bitmap-retirement-and-recycle) |
| B18 | settlement, all wake kinds with operational/physical split, typed Control/Android fatal bridges, ordinary-TPE raw-fatal/thread-top partition, submission/poison/transfer/late receipt and Control-last cleanup across every owned lane | `H-LC`, `H-OS`, `H-DL`, `A-CAP`, `A-GL`, `A-FJ`, `N-JPEG`, `A-CL` | [CORE-SET-1](03-shared-runtime.md#core-set-1--generic-operation-occurrence)–[CORE-WAKE-2](03-shared-runtime.md#core-wake-2--one-shot-control-wake-link-and-scheduling-rejection), [CORE-EXEC-1](03-shared-runtime.md#core-exec-1--private-execute-endpoints), [CORE-FATAL-1](03-shared-runtime.md#core-fatal-1--direct-fatal-throwable-policy), [CORE-CLEAN-1](03-shared-runtime.md#core-clean-1--cleanup-forest-and-dependency-graph), [CORE-CLEAN-2](03-shared-runtime.md#core-clean-2--quarantine-nonreturn-and-late-reduction), [CTRL-040](05-domain-controller-reconciliation.md#ctrl-040--session-scheduler-resource), [AND-CAP-001](06-domain-android-capture-metrics.md#and-cap-001--android-lane-and-startup-sequence), [GL-010](08-domain-gl.md#gl-010--private-lane-admission-fatal-fence-and-termination), [FJPEG-070](09-domain-framework-jpeg.md#fjpeg-070--cleanup-cancellation-and-lane-ownership), [NJPEG-020](10-domain-native-jpeg.md#njpeg-020--session-runtime-jpeg-endpoint-and-legal-products), [DEL-HO-010](12-domain-delivery-observation.md#del-ho-010--submission-entry-callback-and-classification), [DEL-HO-040](12-domain-delivery-observation.md#del-ho-040--terminal-cutoff-and-delivery-cleanup) |
| B19 | legal carrier/native-health combinations, disable cutoff and fault precedence | `H-RC`, `H-NL`, `H-OS`, `H-PS`, `H-DL`, `H-OB`, `A-FJ`, `N-JPEG`, `P-PKG` | [`CTRL-130`](05-domain-controller-reconciliation.md#ctrl-130--completion-and-fallback-arbitration), [`CTRL-300`](05-domain-controller-reconciliation.md#ctrl-300--cross-domain-commit-rules), [`NJPEG-020`](10-domain-native-jpeg.md#njpeg-020--session-runtime-jpeg-endpoint-and-legal-products), [`NJPEG-040`](10-domain-native-jpeg.md#njpeg-040--own-dso-loader-and-bootstrap), [`NJPEG-060`](10-domain-native-jpeg.md#njpeg-060--weak-compressor-capability-and-non-ownership), [`NJPEG-090`](10-domain-native-jpeg.md#njpeg-090--native-encode-result-and-fallback), [`FJPEG-020`](09-domain-framework-jpeg.md#fjpeg-020--installed-resources-and-reuse), [`DEL-HO-040`](12-domain-delivery-observation.md#del-ho-040--terminal-cutoff-and-delivery-cleanup) |
| B20 | controller-selected diagnostic attempts/payloads, immutable event construction, exact per-observation MetricsSource completion cardinality, overflow gaps and wall-clock noncontrol | `H-OB`, `A-CAP`, `A-GL`, `A-FJ`, `N-JPEG` | [`PROD-080`](02-product-contract.md#prod-080--diagnostics), [`CTRL-300`](05-domain-controller-reconciliation.md#ctrl-300--cross-domain-commit-rules), [`DEL-OBS-020`](12-domain-delivery-observation.md#del-obs-020--diagnostic-construction-and-emission) |
| B21 | exact result arming/two-word layout/truth table, RAII cleanup, throwable containment, process-facade inventory/descriptors, NDK/namespace, ABI/page size, exports and DSO isolation | `N-JPEG`, `N-PKG`, `P-PKG` | [`NJPEG-050`](10-domain-native-jpeg.md#njpeg-050--registration-and-frozen-jni-surface), [`NJPEG-080`](10-domain-native-jpeg.md#njpeg-080--writer-capsule-result-block-and-adoption), [`NJPEG-090`](10-domain-native-jpeg.md#njpeg-090--native-encode-result-and-fallback), [`NJPEG-PKG-001`](10-domain-native-jpeg.md#njpeg-pkg-001--native-build-and-artifacts), [`NJPEG-120`](10-domain-native-jpeg.md#njpeg-120--executable-obligations) |

## 7. High-risk paper traces

Executable tests reproduce both material orders with barriers. These are readable review traces, not formal
proofs.

| Trace | Fixed trace sequence | Executable reproduction |
| --- | --- | --- |
| T1 concurrent start | Two starts reach the gate; one changes NotStarted to Starting; loser observes it and performs zero projection access; winner alone registers/starts. | `H-LC`, `A-SES` |
| T2 latest desire during reconfiguration | A mechanic is pending; B replaces desire. If A is definitely unentered/unexposed, B may replace the plan. Otherwise A settles exactly; safe actual state feeds one next serial cycle without Active/admission gap, while ambiguous or unsafe A remains terminal. | `H-RC`, `H-OS`, `A-GL`, `A-CL` |
| T3 geometry change during startup | API 34+ provisional target admits no frame; first resize becomes authority; exact Full may stay otherwise target rebuilds; only current authoritative target runs. Initial-resize expiry is `CaptureUnavailable`, and a later resize is cleanup-only. | `A-CAP`, `H-OS` |
| T4 safe Native failure with healthy target | Timely safe failure records one failure, disables Native, enters `Reconfiguring`, and fences admission/cache; one later serialized cycle installs Framework before resume. Target and Direct health stay unchanged; unsafe/late partitions retain their specified outcome. | `N-JPEG`, `H-RC`, `H-OS`, `H-PS`, `H-DL`, `H-OB`, `A-FJ` |
| T5 handoff admission versus reconfiguration pause | Admission-first grandfathers the immutable old-generation handoff and its effective descriptor: its Runnable may enter while State is Reconfiguring, and reconfiguration neither cancels nor waits for its app callback. Pause-first creates no handoff or submission. Serial Delivery prevents later post-resume callback overlap; unchanged busy/drop rules apply while the handoff remains occupied. Unsubscribe and terminal retain their independent cutoffs and exact settlement. | `H-DL` |
| T6 stop versus callback entry | Entry-first may run user code after stop returns; stop-first fences entry. No later delivery is admitted; pre-transfer callback return enters final Stats and post-transfer return is cleanup-only. | `H-DL`, `H-LC` |
| T7 resource limit/allocation during pause | Deterministic limits are checked without allocation; denial retains recoverable old ownership but no output; otherwise relevant predecessors drain and old scope retires before one physical replacement; required post-retirement allocation failure is terminal, with no rollback or second healthy topology. | `H-RC`, `H-OS`, `A-CAP`, `A-GL`, `A-CL` |

## TEST-VECTOR-JPEG-01 — Shared JPEG `64 x 48` oracle

This test-only vector is the sole decoded-image oracle shared by the Framework and Native encoder suites. The
source is a `4 x 3` array of exact `16 x 16` opaque tiles, encoded at semantic quality `80`:

```text
#E02020  #B34D26  #20B0C0  #20C040
#C020C0  #404040  #808080  #D0D0D0
#2040E0  #7030B0  #26994D  #E0C020
```

For every tile `(c, r)`, the sampled half-open interior is exactly
`[16*c + 4, 16*c + 12) x [16*r + 4, 16*r + 12)`. All twelve interiors are checked.

| Oracle | Exact test requirement |
| --- | --- |
| structure | decode succeeds; dimensions are exactly `64 x 48`; placement, orientation, and alpha `255` are exact |
| per-tile channels | for each channel over each `8 x 8` interior, mean absolute error is at most `24` |
| row integrity | for each of the eight rows in each interior, channel mean absolute error is at most `36` |
| grayscale neutrality | decoded channel-mean spread is at most `8` |
| grayscale order | means for `#404040`, `#808080`, and `#D0D0D0` strictly increase, with adjacent separation at least `32` |

`A-FJ` applies this oracle to real Direct readback, Framework transfer/encode/transaction, and decode. On an
eligible API 30+ connected target, `N-JPEG` independently applies it to the real production Native path. An
ineligible Native row remains unexecuted; host, test-DSO, or package evidence cannot replace it. Framework and
Native encoded bytes, decoded pixels, and tile values are never compared with each other. Downscaled output is
checked against its source/landmark oracle, not Full-path pixel identity. Quality forwarding is exact; encoded
size, quality monotonicity, performance, and an aggregate image score are not assertions.

## 8. Domain verification routing

Exact domain matrices and call counts live once in their canonical owner anchors listed in §1 and referenced by
the acceptance, slice, trace, and executable-ID rows below. This file owns only shared method, indexes, and
cross-domain oracles.

## 9. Acceptance and smoke

A slice closes only when its public result and every involved owner are active, released, transferred, or
exactly quarantined in the test `OwnershipLedger`. Passing image tests do not certify performance or select an
optimization. An eligible API 30+ connected target is required to close representative Direct-to-Native
execution; otherwise that row is unexecuted, while package evidence still covers all shipped ABIs.

A short physical-device smoke check covers consent/start, visible JPEG, rotation/resize,
parameter update, unsubscribe/re-register, stop, and obvious crash/leak/black-frame/orientation/color failure.
It supplements automation, is not part of `verifyScreenCaptureEngine`, and creates no device allowlist.
