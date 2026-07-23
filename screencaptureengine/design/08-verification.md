# Screen Capture Engine — Verification

## Scope and navigation

This document is the sole authority for verification method, deterministic reproduction, test-only oracles,
source-set and Gradle-task binding, and evidence reporting. It proves the behavior owned elsewhere; it does not
define caller-visible behavior, choose a runtime path, create a fallback, or turn a measurement into a product
or implementation selector.

Use [the authority index](00-authority-index.md#topic-router) first. The exact asserted behavior remains with:

- [product](01-product-contract.md) for public API, values, outcomes, observations, limits, and support;
- [internal architecture](02-internal-architecture.md) for authority, roles, currentness, cutoff, ownership,
  residue, and forbidden topology;
- [metrics/platform](03-metrics-platform.md) for source lifetime and API-band geometry reads;
- [capture/rendering](04-capture-rendering.md) for projection, display, Target, EGL/GLES, image, and release;
- [Framework/storage](05-framework-encoding-storage.md) for Bitmap/JPEG and managed byte ownership;
- [Native/JNI/package](06-native-jni-package.md) for loader, wire, writer/adoption, ABI, and artifacts; and
- [delivery/observation](07-delivery-observation.md) for pacing, handoff, unsubscribe, and publication.

An executable ID below names a stable testing responsibility, not another behavior specification. Host IDs are
`H-LC` (lifecycle/bootstrap), `H-RC` (reconciliation), `H-OS` (ownership/cutoff), `H-PS`
(production/storage), `H-DL` (delivery), `H-GM` (geometry), `H-OB` (observations), and `H-NL` (native loader).
Android IDs are `A-API`, `A-SES`, `A-CAP`, `A-GL`, `A-FJ`, and `A-CL`; native/package IDs are `N-JPEG`,
`N-PKG`, and `P-PKG`. Private test class and helper names may change while these responsibilities remain.

## Exact source sets and Gradle tasks

All paths and tasks are relative to `:screencaptureengine`:

- **HOST:** `src/test/kotlin/io/screenstream/engine/internal/`. JUnit runs through
  `testDebugUnitTest`; `verifyScreenCaptureEngineHost` depends on it and owns every `H-*` row. The package
  verifier is excluded from this ordinary host run.
- **AIT:** `src/androidTest/kotlin/io/screenstream/engine/`. Public black-box tests `A-API` and `A-SES` run with
  `androidx.test.runner.AndroidJUnitRunner` through exactly `connectedNativeTestAndroidTest`.
- **AI:** `src/androidTest/kotlin/io/screenstream/engine/internal/`. `A-CAP`, `A-GL`, `A-FJ`, `A-CL`,
  `N-JPEG`, and `N-PKG` use that same instrumentation invocation;
  `verifyScreenCaptureEngineDevice` depends on `connectedNativeTestAndroidTest`.
- **NTCPP:** `src/nativeTest/cpp/`, including `CMakeLists.txt` and
  `screen_capture_engine_native_test_jni.cpp`. `verifyScreenCaptureEngineNative` owns compilation and execution
  of its JNI-free runtime/test-DSO rows. It cannot substitute for a production-facade device row.
- **Package evidence:** `verifyScreenCaptureEnginePackage` is a dedicated filtered Gradle/JUnit task of type
  `Test` that selects only `P-PKG`. It depends on exactly `assembleDebug`, `assembleRelease`,
  `assembleNativeTest`, and `lintRelease`, then inspects those artifacts and the assigned source/build anchors.
  `lintRelease` must have no applicable Framework `NewApi` finding, local `NewApi` suppression, or module lint
  baseline.
- **Aggregate:** `verifyScreenCaptureEngine` depends on `verifyScreenCaptureEngineHost`,
  `verifyScreenCaptureEngineDevice`, `verifyScreenCaptureEngineNative`, and
  `verifyScreenCaptureEnginePackage`.

Device evidence records the device API and ABI. When a required branch is ineligible or no eligible connected
target is present, that exact row is reported **unexecuted**, never passed, inferred from packaging, or replaced
by a host fake. In particular, real Direct-to-Native output requires an eligible API-30+ target. No generated
verifier, parser, script, vector, or expected-result task is part of the contract.

## Deterministic method

Correctness tests use no sleep, scheduler luck, repeated-race heuristic, log parsing, benchmark threshold,
collector timing, device allowlist, or observed queue layout.

The shared harness provides:

- independently assignable elapsed and wall clocks; elapsed time is advanced without automatically running
  work, and wall time is diagnostic input only;
- fake Control and Capture Handler queues whose post acceptance, first Runnable entry, and next turn are
  advanced separately; the existing production Bootstrap seam/runtime composition is controllable before either
  HandlerThread start, after every partial HandlerThread-start prefix, at each Looper acquisition (including
  null, caught failure, and nonreturn), at each Handler construction (including caught failure), and around the
  first Control post's false/return/throw and Runnable entry. It can hold an accepted post without entry and let
  entry race a later post return or throw; these controls are harness composition, not a new production or
  normative fake interface;
- distinct, asynchronous, non-direct fake Metrics, JPEG, and Delivery serial roles with controllable permit,
  acceptance/rejection, queued entry, outward entry, return, throw, late return, and nonreturn; the fake proves
  serialization without promising a dedicated thread;
- a typed recorder for the six delayed-wake families—readiness, pacing, repeat, Stats, current Capture timeout,
  and current JPEG timeout—with exact family/currentness identity, post result, cancellation/suppression, and
  body entry; tests invoke the recorded wake directly;
- barriers immediately before and after `sessionGate` arbitration, first outward call, durable return, typed
  result installation, Control rescan, terminal publication/mode switch, each of the four activation calls, and
  each typed cleanup transition;
- fake Metrics sources/handles, projection/display callbacks and calls, Target/source events, EGL/GLES calls,
  Bitmap and storage boundaries, native facade/writer/adopter services, and application callbacks; and
- an ownership ledger that records acquisition, partial adoption, transfer, lease, logical retirement, real
  close/release receipt, and exact retained residue by identity.

The gate probe fails on outward work or waiting while `sessionGate` is held and on role code running inline on
Control, Bootstrap, or the caller. Each fake records calls before returning or throwing, so an assertion never
infers no-entry or release from cancellation, timeout, post failure, reference drop, or queue observation.

Every Bootstrap prefix/outcome above is crossed with cutoff and pre-Control closure before, during, and after
the outward step. The oracle fixes the `sessionGate` winner and retains the accepted projection plus every exact
partial thread/Looper/Handler/post owner in `BootstrapCapsule`. Only definite null/false evidence or a caught
ordinary startup failure learned before first Control entry may offer the existing pre-Control
`InternalFailure`; an accepted post with no entry and startup nonreturn fabricate neither handoff nor rejection.
Entry winning against a later post return/throw performs the one handoff, and every subsequently learned startup
fact routes to Control. No row adds a watchdog, retry, replacement Handler, fabricated receipt, fifth role, or
second Bootstrap lifecycle.

Time rows construct the checked deadline `D` from the owning document's duration and test `T = D - 1`, `D`, and
`D + 1`, plus empty-at-`D`, return-before-observation, observation-before-return, terminal-before-entry, and
terminal-after-entry. Only durable `T < D` is timely; equality is expiry. Deep sleep is modeled by advancing
elapsed time and firing one current wake; it must not create a catch-up burst.

Allocation injection has two layers. Checked-size/range/narrowing cases supply symbolic valid-boundary,
invalid-boundary, overflow, and exhaustion inputs and assert zero allocator/platform/native call on denial.
Physical cases inject the result of the exact factory or allocator call—success, null/malformed result, direct
OOM at a documented allocation boundary, ordinary `Exception`, another identical `Throwable`, or nonreturn—
and record actual calls and adopted partial owners. Replacement cases place the injection both before
retirement and after predecessor retirement, so rollback or a second healthy topology cannot hide a required
post-retirement failure.

Failure injection is boundary-local and includes normal return, documented failure, direct throwable, pending
JNI throwable, malformed evidence, acceptance ambiguity, late/stale return, and nonreturn after each possible
transfer. Tests assert the owner document's precedence and exact residue; diagnostics, predicted memory, or a
test outcome never select classification or fallback.

For each asynchronous role, acceptance, pre-entry cutoff, entry, ordinary outward `Exception`, direct `Error`,
custom non-`Exception` throwable, inert later entry, and return/nonreturn are crossed independently. Raw fatal
identity is preserved through the fixed engine boundary. A Delivery dispatch/entry failure is fail-closed and
adds no delivery drop; a callback `Exception` uses only its product-owned callback classification. No test
requires a replacement worker, termination receipt, or progress watchdog.

Every blocking/nonreturning test owns a release control. Its `finally` block opens every barrier, lets the real
late mechanical return/close path finish when modeled as returning, drains the fake role, and checks that no
work or owner contaminates the next test.

## Requirement/scenario/test traceability

This is the one acceptance index. The proof-focus text is only a verification summary; the linked document is
the normative behavior oracle. There is no parallel `B*`, `T*`, leaf-test, packet, or executable acceptance
catalog.

| ID | Unique proof focus | Executables | Normative owner |
| --- | --- | --- | --- |
| `ACC-01` | Packaged public inventory/value semantics and negative external compilation of closed facade constructors and internal factories. | `A-API`, `P-PKG`, `N-PKG` | [Product §§1–3, 12](01-product-contract.md#1-product-and-support-boundary), [index baseline](00-authority-index.md#module-baseline-and-navigation-anchors) |
| `ACC-02` | Invalid scalar/structural input fails before work; every valid boundary is accepted without clamping. | `A-API`, `H-GM` | [Product §§3–4](01-product-contract.md#3-configuration-and-metrics-extension-api) |
| `ACC-03` | Both start-gate orders: exactly one accepted start and zero projection access/consumption by the loser. | `H-LC`, `A-SES` | [Architecture: one gate and Bootstrap](02-internal-architecture.md#front-door-and-bootstrap) |
| `ACC-04` | Running is visible before successful return; startup cancellation/failure publishes and throws its exact outcome. | `H-LC`, `A-SES`, `H-OB` | [Product lifecycle](01-product-contract.md#7-lifecycle-operations-and-cancellation), [Architecture: Active readiness](02-internal-architecture.md#active-readiness) |
| `ACC-05` | Stop fixes cutoff/winner synchronously and idempotently while publication and physical cleanup may complete later. | `H-LC`, `A-SES`, `A-CL` | [Product lifecycle](01-product-contract.md#7-lifecycle-operations-and-cancellation), [Architecture: terminal cutoff](02-internal-architecture.md#terminal-cutoff-publication-and-residue) |
| `ACC-06` | Unequal updates are latest-wins through one serialized pause/drain/reconcile/resume cycle; equal requested value is an absolute paused no-op. | `H-LC`, `H-RC`, `H-OB` | [Architecture: latest desire](02-internal-architecture.md#latest-desire-and-one-serial-cycle), [Product parameters](01-product-contract.md#4-capture-parameters-and-validation) |
| `ACC-07` | Closed-command reconciliation proves smallest healthy reuse/rebuild, exact predecessor closure, stale suppression, actual-state continuation, and terminal unsafe ambiguity. | `H-RC`, `H-OS`, `A-CAP`, `A-GL`, `A-CL` | [Architecture: closed seam and reconciliation](02-internal-architecture.md#closed-controlcapture-seam), [Capture Target ownership](04-capture-rendering.md#target-planning-and-direct-ownership) |
| `ACC-08` | Exact configured/default Metrics-source identity and correct platform dimension/density authority in every API band. | `A-CAP`, `H-OB` | [Metrics source identity](03-metrics-platform.md#source-identity-and-attachment-lifetime), [API geometry seam](03-metrics-platform.md#api-band-geometry-facts-and-the-captured-resize-seam) |
| `ACC-09` | Bounded inline/concurrent staging; timely positive-plus-handle joint readiness; expiry/loss/completion/duplicate/recovery and late exact close. | `A-CAP`, `H-RC`, `H-OB`, `A-CL` | [Metrics ingress/readiness](03-metrics-platform.md#bounded-observer-ingress), [Metrics close](03-metrics-platform.md#handle-adoption-close-and-local-residue) |
| `ACC-10` | Sole display creation partitions nonnull, null, `SecurityException`, direct OOM, `IllegalStateException`, unexpected exception, and other fatal throwable exactly. | `A-CAP` | [Capture platform calls](04-capture-rendering.md#exact-platform-calls-and-outcomes) |
| `ACC-11` | Full and eligible Downscaled dimensions, crop/region, rotation, mirrors, OES orientation, color, grayscale, and semantic image. | `H-GM`, `A-GL`, `A-FJ`, `N-JPEG` | [Product transform](01-product-contract.md#5-dimensions-transform-color-and-jpeg-semantics), [Capture transform/readback](04-capture-rendering.md#transform-color-draw-and-readback) |
| `ACC-12` | sRGB, exact Display-P3, unsupported wide/HDR action, and the selected diagnostic site. | `H-GM`, `A-GL`, `H-OB` | [Product color](01-product-contract.md#5-dimensions-transform-color-and-jpeg-semantics), [Capture color actions](04-capture-rendering.md#dataspace-and-shader-color-actions) |
| `ACC-13` | Auto/MaxFps/SampleEvery elapsed-time admission with one pending source/current wake, no burst, no lost work, and no false drop. | `H-PS` | [Product delivery](01-product-contract.md#8-delivery-cache-repeat-and-callback-behavior), [Pacing/cadence](07-delivery-observation.md#pacing-source-coalescing-and-repeat) |
| `ACC-14` | Cache-only repeat, elapsed-time tie priority, cap, counters, new metadata, and zero repeat while paused or GL/JPEG work. | `H-PS` | [Product delivery](01-product-contract.md#8-delivery-cache-repeat-and-callback-behavior), [Repeat eligibility](07-delivery-observation.md#repeat-eligibility-and-cache-use) |
| `ACC-15` | Fresh/repeat/cached/rejected/pause/terminal transitions produce exact cache and Stats effects, preservation, and invalidation. | `H-PS`, `H-OB`, `H-OS` | [Product Stats](01-product-contract.md#10-stats-schema-and-formulas), [Framework/storage roles](05-framework-encoding-storage.md#framestore-roles-and-transitions), [Stats publication](07-delivery-observation.md#stats-construction-and-publication) |
| `ACC-16` | Real Full + Direct + Framework path yields exact-size/orientation decodable JPEG and never exposes raw/partial data. | `A-GL`, `A-FJ`, `P-PKG` | [Capture carrier use](04-capture-rendering.md#canonical-frame-and-carrier-use), [Framework compression/storage](05-framework-encoding-storage.md#one-framework-compression) |
| `ACC-17` | Downscale, Native JPEG, and Display-P3 obey only closed capability rules; their health and later fallback remain independent. | `H-RC`, `H-NL`, `A-CAP`, `A-GL`, `N-JPEG`, `N-PKG` | [Architecture reconciliation](02-internal-architecture.md#latest-desire-and-one-serial-cycle), [Native products/health](06-native-jni-package.md#legal-carrierbackend-products-and-native-health) |
| `ACC-18` | Safe optional failure permits only the owned later fallback; ambiguous transfer, unsafe cleanup, or nonreturn is terminal with exact residue. | `H-OS`, `H-RC`, `A-CAP`, `A-FJ`, `N-JPEG`, `A-CL` | [Architecture failure/residue](02-internal-architecture.md#time-allocation-failure-and-data-safety), [leaf cleanup owners](04-capture-rendering.md#dependency-ordered-physical-release-and-residue) |
| `ACC-19` | Zero/one current-or-draining registration; replacement excluded until shared successful unsubscribe. | `H-DL`, `A-SES` | [Product registration](01-product-contract.md#8-delivery-cache-repeat-and-callback-behavior), [Registration/handoff](07-delivery-observation.md#registration-opportunity-coalescing-and-handoff-creation) |
| `ACC-20` | Cached-first preserves original immutable bytes, sequence, timestamp, and descriptor; paused registration waits for current resume. | `H-PS`, `H-DL`, `A-SES` | [Product cache](01-product-contract.md#8-delivery-cache-repeat-and-callback-behavior), [FrameStore repeat/cache](05-framework-encoding-storage.md#repeat-cache-and-lease) |
| `ACC-21` | Admission-first and pause-first handoff orders, independent callback/task-result convergence, serial nonoverlap, and ordinary busy accounting. | `H-DL`, `H-OS`, `A-CL` | [Architecture reconciliation](02-internal-architecture.md#latest-desire-and-one-serial-cycle), [Callback entry/convergence](07-delivery-observation.md#non-direct-serial-execution-and-callback-entry) |
| `ACC-22` | Unsubscribe closes admission immediately, waits for full physical handoff, is idempotent, rejects self-call, preserves waiter cancellation, and gates replacement on shared success. | `H-DL`, `A-SES` | [Product delivery](01-product-contract.md#8-delivery-cache-repeat-and-callback-behavior), [Unsubscribe execution](07-delivery-observation.md#unsubscribe-execution) |
| `ACC-23` | Finite Bootstrap covers both Handler lanes, every partial start/Looper/Handler/post prefix and its pre-entry, handoff, fault, nonreturn, and cutoff outcome; Metrics/JPEG/Delivery remain asynchronous non-direct serial roles, no role becomes replacement authority, and no fixed six-thread topology survives. | `H-LC`, `H-OS`, `H-DL`, `A-CAP`, `A-GL`, `A-FJ` | [Architecture roles](02-internal-architecture.md#execution-roles-and-responsibilities), [Bootstrap](02-internal-architecture.md#finite-bootstrap-handoff) |
| `ACC-24` | Checked bounds plus actual allocation, single topology/carrier/role cardinalities, complete owner transfer, lease fencing, and privacy of raw/partial/stale bytes. | `H-GM`, `H-RC`, `H-PS`, `H-OS`, `A-GL`, `A-FJ`, `N-JPEG`, `P-PKG` | [Architecture bounds/safety](02-internal-architecture.md#bounded-ownership-and-currentness), [Framework memory bounds](05-framework-encoding-storage.md#managed-memory-and-copy-bounds) |
| `ACC-25` | Four typed retirement fields and fixed activations, independent local cleanup, nonreusable residue, typed late reduction, and progress of an unrelated Session. | `H-OS`, `H-RC`, `H-DL`, `A-CAP`, `A-GL`, `A-FJ`, `N-JPEG`, `A-CL` | [Architecture terminal/residue](02-internal-architecture.md#terminal-cutoff-publication-and-residue) |
| `ACC-26` | Exact public event data/cardinality, ordered terminal publication, terminal-cleanup Attach/Reduce/Remove emission, valid gaps, and strict diagnostic noncontrol. | `H-OB`, `A-CAP`, `A-GL`, `A-FJ`, `N-JPEG` | [Product diagnostics](01-product-contract.md#11-diagnostics-schema-vocabulary-causes-and-order), [Architecture terminal sink](02-internal-architecture.md#terminal-cutoff-publication-and-residue), [Terminal cleanup diagnostics](07-delivery-observation.md#typed-terminal-cleanup-diagnostics) |
| `ACC-27` | Two Sessions share no lifecycle, projection, desire, capsule, identity, role permit, payload/cache, registration, counter, failure, or residue authority. | `H-LC`, `H-RC`, `A-CAP`, `A-GL`, `A-CL` | [Architecture scope/identities](02-internal-architecture.md#scope-and-authority), [leaf local-residue owners](03-metrics-platform.md#handle-adoption-close-and-local-residue) |

## Hard-race reproduction list

Every entry is run in both material orders with barriers, typed fakes, and the ownership ledger. This is the
only race list; a row is not another behavioral owner.

1. **Concurrent start:** A wins/B touches no projection, and B wins/A touches no projection.
2. **Bootstrap entry versus cutoff/failure:** at every HandlerThread-start, Looper-acquisition, Handler-
   construction, and first-post prefix, cutoff-first starts no later step and retains the exact partial owners;
   entry-first retains them in `BootstrapCapsule` through handoff or pre-Control closure. Null Looper, caught
   ordinary pre-entry failure, definite post false, accepted-without-entry, and startup nonreturn each preserve
   their distinct evidence without a fabricated release or progress result.
3. **First Control post versus handoff:** accepted post changes no authority; only first Control Runnable entry
   under `sessionGate` hands off, unless pre-Control terminal closure already won. Post false/caught throw before
   entry may offer only the existing pre-Control failure; entry racing a later post return/throw wins the handoff
   and routes the later fact to Control.
4. **Stop versus Control wake:** open-first may fold already-durable accepted work; cutoff-first sends every
   later entry/return to typed cleanup only.
5. **Projection `onStop` versus owner stop:** each contender can arrive first; product priority selects once and
   the loser cannot rewrite the terminal winner.
6. **New config versus `ApplyPlan`:** definitely unstarted/unexposed work can be replaced; entered work returns
   truthful actual old-revision state before one next cycle reconciles the latest desire.
7. **Cutoff versus queued role work:** cutoff-first commits `CutoffInert` with zero outward call; entry-first
   commits `Entered`, and a late exact-capsule return cannot revive the Session.
8. **Source versus production-busy state:** source-first stays represented once; slot-first consumes it into one
   `productionId`, with no loss or extra queue.
9. **Source during readback:** the later source remains one pending candidate; no second read or raw carrier is
   created.
10. **JPEG success versus newer config:** matching success may publish; stale success performs only the
    owner-defined mechanical accounting/retirement and cannot publish. Safe Native failure disables it and
    installs Framework only in a later cycle, never by same-frame retry.
11. **Role return versus cutoff:** open-first installs one typed result and dirty wake; cutoff-first leaves
    `ReturnExpected`, and the durable return is cleanup-only.
12. **Handoff admission versus reconfiguration:** admission-first grandfathers the immutable old descriptor;
    pause-first creates no handoff. Reconfiguration neither cancels nor waits for the callback.
13. **Stop/unsubscribe versus callback entry:** entry-first may finish user code; cutoff-first prevents user-code
    entry. Stop and unsubscribe retain their distinct convergence/result rules.
14. **Callback return versus next frame:** callback return closes borrowed authority and releases the lease;
    pending typed-result installation may retain only the exact handoff residue and make the next opportunity
    busy; the later installation never reclassifies the callback.
15. **Metrics callback versus subscribe return:** inline valid value stages before handle return; only the same
    timely value plus normally returned adopted handle can establish joint readiness.
16. **Metrics terminal versus later value:** first terminal stays first; an earlier valid value remains latest
    only where the product permits, and later callbacks cannot reopen ingress.
17. **Blocked role versus semantic terminal:** independently block Capture, Metrics, JPEG, and Delivery;
    terminal publication remains responsive while each exact physical capsule/residue remains truthful.
18. **Diagnostic sequence versus emission:** the sink reserves and attempts in order; failed `tryEmit` leaves a
    valid gap and changes no engine state.
19. **Terminal publication versus activation:** final Stats, terminal event attempt, terminal State, and sink
    mode switch are contiguous; the same terminal turn then activates the exact Metrics, Capture, Encoder, and
    Delivery retirement identities exactly once in that fixed order, with no dynamic registry or deferred wake.
    The oracle binds those effects and accepts equivalent private helper decomposition; it does not require a
    literal hand-unrolled Kotlin spelling or the absence of every private helper.
20. **Activation versus role return:** durable return first changes `ReturnExpected -> Closed` with no cleanup
    event; activation first changes it to exact process residue with one Attach, and a later real return may only
    Reduce/Remove.
21. **Return around sink mode switch:** a pre-switch return remains capsule-rooted for activation; a post-switch
    return invokes its typed sink transition directly, without Control wake or strand.
22. **Duplicate/stale activation or cleanup identity:** the matching transition winner emits at most one exact
    action; a duplicate, empty, losing, or identity-mismatched call changes and emits nothing.
23. **Concurrent residue reductions:** each real nonempty winning reduction is ordered by the sink and emits
    once; duplicate/empty losers emit nothing and never revise public state.
24. **Captured resize versus display-create result:** on API 34–37, resize-first and nonnull-result-first join the
    same accepted projection owner, keep exactly one display, and admit no provisional frame.
25. **Allocation around retirement:** deterministic denial before retirement performs no allocation or
    retirement; a required post-retirement allocation failure has the owning terminal result, no rollback, and
    no second healthy topology.

## API 24–37 and boundary matrices

The platform matrix is branch evidence, not a requirement for fourteen physical devices. `A-CAP` injects SDK
facts 24, 29, 30, 33, 34, and 37 through production seams and runs real applicable calls on connected targets.
Every injected row asserts both the selected call and zero guarded calls from other bands.

- **Metrics 24–29:** every complete read uses `Display.getRealSize(Point)` and a fresh transient display Context
  for density; no API-30+ metrics/window symbol is evaluated.
- **Metrics 30:** dimensions use one epoch-cached WindowContext/`WindowManager` created through
  `createWindowContext(TYPE_APPLICATION, null)`; density still uses a fresh display Context per read.
- **Metrics 31–37:** dimensions use one epoch-cached pair created through
  `applicationContext.createWindowContext(epochDisplay, TYPE_APPLICATION, null)`; density remains fresh per
  read. Every band covers epoch invalidation at each read/install boundary, ordinary-change races, callback
  storms, recovery, coherent tuples, and zero frame-path reads.
- **Geometry 24–33:** the latest complete selected-source tuple owns width, height, and density.
- **Geometry 34–37:** source width/height is provisional until the first positive resize for the accepted
  projection owner; captured resize then owns width/height while source density remains authoritative. Both
  resize/create-result orders, missing-resize expiry, later cleanup-only resize, visibility, stale owner, and
  `onStop` are exercised.
- **Projection 24–37:** exact callback Handler and registration order, single display name/size/density/flag/
  Surface arguments, one create, resize/setSurface cardinality, and null/Security/OOM/illegal-state/unexpected/
  fatal/nonreturn partitions are checked. API 34–37 additionally proves no provisional admission.
- **Target/GL 24–31:** Full is the only Target mode. **Target/GL 32–37:** every closed early-Downscaled
  eligibility and retain/rebuild branch is crossed with exact-aspect platform behavior and per-axis capacity.
  API 24–32 performs nominal-SDR handling with zero `getDataSpace()` call; API 33–37 exercises exact sRGB,
  Display-P3, scRGB/scRGB-linear, ST2084/HLG, and other-integer classification in precedence order.
- **Framework 24–25:** actual shape/config/mutability/row/count/allocation metadata is checked with zero
  `getColorSpace()` or `Bitmap.Config.HARDWARE` access. **Framework 26–37:** guarded exact-sRGB and non-HARDWARE
  checks are added. Exact seam cases are 24, 25, 26, and 37.
- **Native 24–29:** own DSO/package behavior may execute, but the weak compressor guard is not entered and no
  compressor call occurs. **Native 30–37:** null/available weak capability, preparation, enabled-frame
  re-resolution, and production facade are tested; an eligible row is explicitly unexecuted when unavailable.

Capture/GL boundary rows additionally cross partial adoption, current/stale/terminal command entry, producer
attach versus detach evidence, every EGL true/sentinel/malformed/currentness result, GLES pre/post probes,
dirty/poison state, draw/readback, Surface release, and dependency-ordered suffix failure/nonreturn. Framework/
storage rows cross Bitmap creation and recycle; both `TightCopy` and `RowConversion`; real compression; every
transaction write/flush/close/commit/abort order; Framework growth and exact normalization; Native segment
copy/adoption; role transitions; held leases; allocation/copy faults; and exact owner/copy counts. Host fakes do
not replace the real Android, EGL/GLES, Bitmap, decode, JNI, or artifact row.

## Image oracles

### GL raw `5 x 3`

Tests generate this top-down opaque RGBA vector; production contains no test asset:

```text
#FF0000  #B34D26  #000000  #00FFFF  #00FF00
#FF00FF  #404040  #808080  #C0C0C0  #FFFFFF
#0000FF  #7030B0  #26994D  #008080  #FFFF00
```

All 15 pixels verify row/channel order. Required landmarks are the four corners, `(0,1)`, and `(2,1)`; tight
row is exactly 20 bytes and range exactly 60 bytes. Geometry cases are LeftHalf columns 0–1, RightHalf columns
2–4, crop `(1,0,1,1)` yielding `3 x 2`, every rotation and both mirrors, `ScaleFactor(2.0)` yielding `10 x 6`,
`TargetSize(8,8)` yielding Stretch `8 x 8` and AspectFit `8 x 5`, eligible Downscaled from a 2x-expanded
`10 x 6` source to `5 x 3`, and every closed Full case.

Dimensions, bounds, mapped landmarks, top-down orientation, and alpha 255 are exact. Maximum absolute 8-bit RGB
error is 2 for nominal-sRGB highp, 6 for nominal-sRGB mediump, 3 for Display-P3 highp, 8 for Display-P3
mediump, 2 for grayscale-highp versus integer Y, 6 for grayscale-mediump versus integer Y, and 12 for Early
Downscale at each exact landmark versus the CPU/source oracle. Raw grayscale also requires exact `R == G == B`.
Color classification/action is exact independently of tolerance. No tolerance selects precision, Target mode,
fallback, or runtime path.

### Shared Framework/Native JPEG `64 x 48`

The sole shared decoded-image oracle is a `4 x 3` array of exact `16 x 16` opaque tiles at semantic quality 80:

```text
#E02020  #B34D26  #20B0C0  #20C040
#C020C0  #404040  #808080  #D0D0D0
#2040E0  #7030B0  #26994D  #E0C020
```

For tile `(c,r)`, sample exactly the half-open interior
`[16*c + 4, 16*c + 12) x [16*r + 4, 16*r + 12)`; all twelve interiors are checked. Decode, dimensions
`64 x 48`, placement/orientation, and alpha 255 are exact. Each channel's mean absolute error over an interior is
at most 24; each channel's mean absolute error for each of its eight interior rows is at most 36. Decoded
grayscale channel-mean spread is at most 8. Means for `#404040`, `#808080`, and `#D0D0D0` strictly increase,
with adjacent separation at least 32.

`A-FJ` applies the vector to real Direct readback, Framework transfer/compress/transaction, and decode.
`N-JPEG` independently applies it to the production Native path on an eligible API-30+ target. The encoders are
never compared by bytes, decoded pixels, tile values, encoded size, quality monotonicity, aggregate score, or
performance. Downscaled output uses its own source/landmark oracle.

## Native writer, adoption, and package truth

`N-JPEG` initializes both native-order signed 64-bit result words to `Pending == -1` at offsets 0 and 8 in an
exact-capacity 16-byte direct block. Tests prove:

- invalid, non-direct, missing-address, or non-16-byte result memory stays unarmed and unchanged; compressor,
  adopter, and call-scoped writer are never created/called, and normal return, `Exception`, and every other
  identical `Throwable` follow the Native owner's partition;
- after arming, every lookup, pixels/sink/direct-buffer, range/count, narrowing, descriptor, and other preflight
  failure calls neither compressor nor adopter, creates no writer, and writes produced count `P = 0` before
  `InternalFailure`; any lookup throwable remains pending for the exhaustive partition;
- every returning final status writes count first and status last and is checked against managed adopted count
  `M`: transfer-complete requires `P > 0`, `M == P`, no throwable, and closed ownership; safe compressor
  allocation failure and native OOM require `0 <= P <= Int.MAX_VALUE`, `M == 0`, no throwable, and closed
  ownership; internal failure requires `0 <= P <= Int.MAX_VALUE`, `0 <= M <= P`, permits a pending throwable
  only for its documented pre-adoption/malformed path, and has closed ownership; Java-throwable requires
  `P > 0`, `0 <= M <= P`, the identical throwable from one adoption boundary, and verified closed ownership;
- every status is crossed with valid and invalid `P`, `M`, pending/absent `Throwable`, closed/missing-close
  evidence, unknown status, still-Pending word, and transaction state; impossible combinations publish no
  bytes and preserve fatal identity;
- zero/nonempty writer callbacks, ordered segments, nullable data for zero, allocation/cumulative-range failure,
  malformed callback evidence, sticky escalation, count-first/status-last, explicit idempotent RAII close,
  cleanup throws, and return/nonreturn are covered; every returning path proves close, while nonreturn proves no
  close receipt and retains the exact native-stack/Kotlin graph;
- `NewDirectByteBuffer` and `adoptNativeSegment` OOM, `Exception`, and other identity-bearing `Throwable` faults
  exercise the exact adoption disposition and proven-owned segment frees; a following control invocation
  succeeds whenever process state remains valid;
- the ledgers assert `N + M = J` before copying a segment, transient
  `N + M + s = J + s <= J + Smax`, transfer of `s` after normal adoption/free, one-segment transient `2J`, and
  final success `P = M = J` with no native segment and one managed payload; and
- the production facade is a black box for real carrier/compressor/adoption/decode. The test DSO supplies only
  test-local writer/compressor/adopter controls around the unchanged JNI-free runtime, accesses no production
  state, and is never evidence that an absent production branch executed.

`P-PKG`/`N-PKG` statically and on the connected ABI verify the exact Native owner rather than redefining it:

- canonical NDK `29.0.14206865`, namespace `io.screenstream.engine`, weak-API configuration, direct
  `DT_NEEDED` on `jnigraphics`, guarded weak symbol, hidden visibility, and the same hidden PIC runtime object in
  production and test DSOs;
- exactly `armeabi-v7a`, `arm64-v8a`, `x86`, and `x86_64`, with 16-KiB load alignment;
- production `libscreencaptureengine.so` exports only `JNI_OnLoad` and
  `Java_io_screenstream_engine_internal_jpeg_NativeJpegProcess_nativeBootstrap`;
- exactly five Kotlin external methods, the four exact registered names/descriptors, the exact
  `NativeSegmentSink.adoptNativeSegment(ByteBuffer, Int)` binary upcall, and matching keep rules;
- debug/release contain only the production DSO; nativeTest contains production plus
  `libscreencaptureengine_native_test.so`; the test DSO is not published and cannot access production state;
- `NTCPP:CMakeLists.txt` and `NTCPP:screen_capture_engine_native_test_jni.cpp` are present and isolated; JNI/C++
  containment, field-wise `memcpy` at offsets 0/8 without alignment assumption, and production-unreachable
  defensive writer exceptions receive static source evidence; and
- the AAR/source sets, manifest, namespace, SDK/JVM/API-mode/coroutines baseline, symbols, ABIs, alignment, and
  package contents match their owners; the library manifest adds no component, permission, service, provider,
  or runtime feature.

## API-37 physical/official-runtime evidence

One physical device or official Android runtime at API 37 runs a short smoke/profile sequence: consent and
start; captured resize; source callback; Direct readback; Framework JPEG and, when eligible, Native JPEG;
delivery; unequal parameter update; unsubscribe/re-register; system `onStop`; owner stop in a separate run; and
dependency-local cleanup. It records visible JPEG size/orientation/color, terminal outcome, exact cleanup
receipts/residue, and obvious crash, black-frame, or leak symptoms.

The same run records distributions or trace evidence for Control and Capture queue latency, CPU time,
allocation churn and full-frame copies, Java heap, native live bytes, process PSS, and obvious GPU/driver stalls.
These measurements diagnose the fixed architecture and prioritize later optimization. They are not pass/fail
latency or memory thresholds, public guarantees, device/driver allowlists, benchmark gates, backend/precision/
Target selectors, or part of `verifyScreenCaptureEngine`.

## Navigation, negative topology, and closure checks

`P-PKG` verifies representative navigation from the [authority index](00-authority-index.md) to every final
owner and back to this verification authority; every link used by the traceability index and package/native
evidence must resolve. It also maps public Kotlin declarations to the public source root, private production to
`internal/`, JNI/C++ to `src/main/cpp/`, and each executable ID to exactly one source set above.

Cohesion review asks whether a unit has accumulated independent lifecycles, synchronization contexts, resource
families, or both policy and physical mechanics. Numeric SLOC, state-group, and package-size figures are
advisory signals for that human responsibility review only: no verification task, source scan, or package check
turns them into an executable pass/fail threshold or demands a split solely because a number was crossed.

Source/build/architecture checks must find zero live positive implementation or test requirement for the
superseded topology. The scan is structural where syntax matters and token-assisted only for orientation; a
negative mention is not a false failure. It rejects:

- a fixed six-thread Session, Session-private prestarted TPE/STPE per component, replacement Control/Capture
  authority, or a thread/executor/lock/state machine per component;
- `settlementGate`, a generic occurrence/settlement/endpoint/fatal-proof framework, fabricated termination or
  release receipt, attachment-ticket cleanup, global cleanup forest, iterable shutdown registry, or Control
  cleanup orchestra;
- a second lifecycle/currentness/fallback/counter/publication/terminal writer, a generic bus/registry/provider,
  common capsule base, universal result envelope, or mutable component context;
- synchronous Control-to-role calls, cross-role waits/joins, timeout/cancellation/queue state as reclamation,
  callback watchdog/interruption, or post-terminal revival;
- frame/raw/encoded/source/callback queues, per-frame coroutine, second carrier/topology/parallel encode,
  same-frame Native-to-Framework retry, predictive memory/device/test selection, or publication of raw,
  tentative, stale, or unleased bytes; and
- any `B*`, `T*`, leaf-review, packet, or second executable table that attempts to become a competing
  acceptance authority.

A verification result proves only its linked owner. Tests, profiles, diagnostics, package contents, and this
document never own runtime or product selection.
