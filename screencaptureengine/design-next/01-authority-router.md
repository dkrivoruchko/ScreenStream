# Screen Capture Engine — Authority Router

## 1. Authority scope

Documents 01–12 form the normative product, architecture, implementation-binding, and verification package.
Document 13 is nonnormative.

## 2. Precedence and sole ownership

Each rule has one owner. Another file may link to its exact ID but may not restate or narrow it.

| Order | File | Sole responsibility |
| ---: | --- | --- |
| 1 | `01-authority-router.md` | Precedence, packets, module/path/source/test manifests, and inbound-interface routing. |
| 2 | `02-product-contract.md` | Product schema, public API/behavior, values, limits, defaults, outcomes, and public timing. |
| 3 | `03-shared-runtime.md` | Generic ownership, occurrence/settlement/deadline/wake, allocation/efficiency, cleanup/quarantine, and privacy kernel. |
| 4 | `04-verification.md` | Verification method, executable index, all 27 acceptance scenarios, B1–B21, seven traces, and shared JPEG oracle. |
| 5 | `05-domain-controller-reconciliation.md` | Controller/lifecycle/drainer, Session scheduler, desired/currentness, reconciliation, terminal and cross-domain commit authority. |
| 6 | `06-domain-android-capture-metrics.md` | Metrics, geometry-source arbitration, MediaProjection, VirtualDisplay, and Android lane. |
| 7 | `07-domain-target.md` | Target preparation/install, operation ports/leases, producer/listener evidence, retirement, and Target cleanup. |
| 8 | `08-domain-gl.md` | EGL/GLES, render/readback/color, GL lane/fatal fence, context integrity, GL cleanup, and GL-only oracle/tolerances. |
| 9 | `09-domain-framework-jpeg.md` | Bitmap creation/transfer/encode/recycle and Framework-specific cleanup. |
| 10 | `10-domain-native-jpeg.md` | JPEG runtime/carriers, loader/JNI/writer/adoption, native cleanup, and native build/package contract. |
| 11 | `11-domain-encoded-storage.md` | Managed transactions, immutable segmented payload/cache roles, leases, and storage retirement. |
| 12 | `12-domain-delivery-observation.md` | Pacing calculation, delivery/handoff, unsubscribe, and unlocked State/Stats/diagnostic construction/emission. |
| — | `13-future-evolution.md` | Future product directions; no current requirements. |

Document 13 cannot modify Documents 01–12.

Runtime constants live once in their consuming product/domain owner. Shared deadline arithmetic alone lives in
`CORE-SET-3`; `CORE-TIME-1` owns only the common clock and exclusions. The cross-domain JPEG vector
`TEST-VECTOR-JPEG-01` lives in `04-verification.md`; GL-only vector/tolerances live in `GL-081`; any other
domain-only value lives in its leaf.

## 3. Canonical IDs

These are the final routing namespaces; descriptive aliases are not requirement identities.

| IDs | Owner |
| --- | --- |
| `PROD-001`, `PROD-010`, `PROD-011`, `PROD-020`, `PROD-030`, `PROD-031`, `PROD-040`, `PROD-050`, `PROD-060`, `PROD-070`, `PROD-080`, `PROD-090`, `PROD-100` | `02-product-contract.md` |
| `CORE-001`, `CORE-OWN-1`, `CORE-SET-1`–`3`, `CORE-WAKE-2`, `CORE-TIME-1`, `CORE-ALLOC-1`, `CORE-EFF-1`, `CORE-CLEAN-1`–`2`, `CORE-PRIV-1` | `03-shared-runtime.md` |
| `ACC-01`–`ACC-27`, `B1`–`B21`, `T1`–`T7`, `TEST-VECTOR-JPEG-01` | `04-verification.md` |
| `CTRL-001`, `CTRL-010`, `CTRL-020`, `CTRL-030`, `CTRL-040`, `CTRL-100`, `CTRL-110`, `CTRL-120`, `CTRL-130`, `CTRL-200`, `CTRL-300`, `CTRL-400`, `CTRL-900` | `05-domain-controller-reconciliation.md` |
| `AND-MET-001`, `AND-MET-010`, `AND-MET-011`, `AND-MET-020`, `AND-MET-021`, `AND-MET-030`; `AND-CAP-001`, `AND-CAP-010`, `AND-CAP-020`, `AND-CAP-030`, `AND-CAP-040`, `AND-CAP-050`, `AND-CAP-060`, `AND-CAP-070` | `06-domain-android-capture-metrics.md` |
| `TGT-001`, `TGT-010`, `TGT-020`, `TGT-030`, `TGT-040`, `TGT-050`, `TGT-060`, `TGT-070`, `TGT-080`, `TGT-090`, `TGT-095`, `TGT-098`, `TGT-100` | `07-domain-target.md` |
| `GL-001`, `GL-010`, `GL-020`, `GL-030`, `GL-040`, `GL-050`, `GL-060`, `GL-070`, `GL-080`, `GL-081`, `GL-090`, `GL-100` | `08-domain-gl.md` |
| `FJPEG-001`, `FJPEG-010`, `FJPEG-020`, `FJPEG-030`, `FJPEG-031`, `FJPEG-040`, `FJPEG-050`, `FJPEG-060`, `FJPEG-070`, `FJPEG-080`, `FJPEG-090`, `FJPEG-100`, `FJPEG-110` | `09-domain-framework-jpeg.md` |
| `NJPEG-001`, `NJPEG-010`, `NJPEG-020`, `NJPEG-030`, `NJPEG-040`, `NJPEG-050`, `NJPEG-060`, `NJPEG-070`, `NJPEG-080`, `NJPEG-090`, `NJPEG-100`, `NJPEG-CONST-001`, `NJPEG-WIRE-001`, `NJPEG-PKG-001`, `NJPEG-110`, `NJPEG-120` | `10-domain-native-jpeg.md` |
| `STORE-001`, `STORE-010`, `STORE-020`, `STORE-030`, `STORE-040`, `STORE-050`, `STORE-060`, `STORE-070`, `STORE-080`, `STORE-090`, `STORE-100`, `STORE-110` | `11-domain-encoded-storage.md` |
| `DEL-PACE-001`, `DEL-PACE-010`, `DEL-PACE-020`; `DEL-HO-001`, `DEL-HO-010`, `DEL-HO-020`, `DEL-HO-030`, `DEL-HO-040`; `DEL-OBS-001`, `DEL-OBS-010`, `DEL-OBS-020`; `DEL-900` | `12-domain-delivery-observation.md` |

Stable executable IDs are `H-LC`, `H-RC`, `H-OS`, `H-PS`, `H-DL`, `H-GM`, `H-OB`, `H-NL`, `A-API`,
`A-SES`, `A-CAP`, `A-GL`, `A-FJ`, `A-CL`, `N-JPEG`, `N-PKG`, and `P-PKG`.

## 4. Module and path bindings

All paths are repository-relative. Kotlin production references use these bases:

| Alias | Base |
| --- | --- |
| `PUB:` | `screencaptureengine/src/main/kotlin/io/screenstream/engine/` |
| `INT:` | `screencaptureengine/src/main/kotlin/io/screenstream/engine/internal/` |
| `CTR:` | `screencaptureengine/src/main/kotlin/io/screenstream/engine/internal/controller/` |
| `AND:` | `screencaptureengine/src/main/kotlin/io/screenstream/engine/internal/android/` |
| `TGT:` | `screencaptureengine/src/main/kotlin/io/screenstream/engine/internal/target/` |
| `GL:` | `screencaptureengine/src/main/kotlin/io/screenstream/engine/internal/gl/` |
| `JPG:` | `screencaptureengine/src/main/kotlin/io/screenstream/engine/internal/jpeg/` |
| `DEL:` | `screencaptureengine/src/main/kotlin/io/screenstream/engine/internal/delivery/` |
| `SET:` | `screencaptureengine/src/main/kotlin/io/screenstream/engine/internal/settlement/` |
| `CPP:` | `screencaptureengine/src/main/cpp/` |
| `HOST:` | `screencaptureengine/src/test/kotlin/io/screenstream/engine/internal/` |
| `AIT:` | `screencaptureengine/src/androidTest/kotlin/io/screenstream/engine/` |
| `AI:` | `screencaptureengine/src/androidTest/kotlin/io/screenstream/engine/internal/` |
| `NTCPP:` | `screencaptureengine/src/nativeTest/cpp/` |
| `MOD:` | `screencaptureengine/` |

### Module baseline

| Binding | Canonical value |
| --- | --- |
| Android API | minimum 24; compile 37; target 37 |
| JVM | toolchain/bytecode 17 |
| namespace | `io.screenstream.engine` |
| Kotlin API mode | explicit API |
| published artifact | public release AAR |
| runtime concurrency dependency | `kotlinx-coroutines` `1.11.0` |

Source-set, public-artifact, and package checks route through `P-PKG` in `04-verification.md`. Native NDK,
weak-API, ABI, DSO, and alignment bindings remain solely in `NJPEG-060` and `NJPEG-PKG-001`.

## 5. Closed implementation packets

Each packet contains exactly the production and verification anchors shown; links do not recursively add
requirements. Verification-only domain anchors appear only in testing supplements.

| Slice | Production packet | Exact testing supplement |
| --- | --- | --- |
| Public API and values | §4 module baseline; §6 Product/public; `PROD-001`, `PROD-010`, `PROD-011`, `PROD-020`, `PROD-030`, `PROD-031`, `PROD-040`, `PROD-050`, `PROD-060`, `PROD-070`, `PROD-080`, `PROD-090`, `PROD-100` | [04 §2.1](04-verification.md#21-source-sets-and-tasks); [04 §§3.1–3.6](04-verification.md#31-harness); `ACC-01`, `ACC-02`, `ACC-04`–`ACC-06`, `ACC-08`, `ACC-11`–`ACC-14`, `ACC-16`, `ACC-19`, `ACC-20`, `ACC-22`, `ACC-23`, `ACC-26`; `B1`–`B3`, `B7`; `T1`, `T6`; `A-API`, `A-SES`, `P-PKG` |
| Controller, lifecycle, and reconciliation | §6 Shared runtime and Controller/reconciliation; [05 §§1–2](05-domain-controller-reconciliation.md#1-source-boundary-and-authority); `PROD-010`, `PROD-011`, `PROD-030`, `PROD-031`, `PROD-050`–`PROD-090`; `CORE-001`, `CORE-OWN-1`, `CORE-SET-1`–`CORE-SET-3`, `CORE-WAKE-2`, `CORE-TIME-1`, `CORE-ALLOC-1`, `CORE-EFF-1`, `CORE-CLEAN-1`, `CORE-CLEAN-2`, `CORE-PRIV-1`; `CTRL-001`, `CTRL-010`, `CTRL-020`, `CTRL-030`, `CTRL-040`, `CTRL-100`, `CTRL-110`, `CTRL-120`, `CTRL-130`, `CTRL-200`, `CTRL-300`, `CTRL-400`, `CTRL-900`; `DEL-PACE-001`–`DEL-PACE-020`, `DEL-HO-001`–`DEL-HO-040`, `DEL-OBS-001`–`DEL-OBS-020` | [04 §2.1](04-verification.md#21-source-sets-and-tasks); [04 §§3.1–3.6](04-verification.md#31-harness); [05 §4](05-domain-controller-reconciliation.md#4-exact-verification-rows); `ACC-02`–`ACC-15`, `ACC-17`–`ACC-27`; `B1`, `B2`, `B4`–`B12`, `B14`–`B20`; `T1`–`T7`; `H-LC`, `H-RC`, `H-OS`, `H-PS`, `H-DL`, `H-GM`, `H-OB`, `H-NL`, `A-SES`, `A-CAP` |
| Session scheduler | §6 Shared runtime, Controller/reconciliation, and Delivery/observation; [05 §§1–2](05-domain-controller-reconciliation.md#1-source-boundary-and-authority); [12 §1](12-domain-delivery-observation.md#1-scope-and-interfaces); `CORE-OWN-1`, `CORE-SET-1`–`CORE-SET-3`, `CORE-WAKE-2`, `CORE-TIME-1`, `CORE-CLEAN-1`, `CORE-CLEAN-2`; `CTRL-001`, `CTRL-010`, `CTRL-030`, `CTRL-040`, `CTRL-200`, `CTRL-300`, `CTRL-400`, `CTRL-900`; `DEL-PACE-010`, `DEL-HO-001`, `DEL-HO-010`, `DEL-HO-040`, `DEL-OBS-010` | [04 §2.1](04-verification.md#21-source-sets-and-tasks); [04 §§3.1–3.6](04-verification.md#31-harness); [05 §4](05-domain-controller-reconciliation.md#4-exact-verification-rows); [12 §3](12-domain-delivery-observation.md#3-exact-verification-rows); `ACC-13`, `ACC-15`, `ACC-21`, `ACC-23`, `ACC-25`, `ACC-27`; `B4`, `B7`, `B8`, `B10`, `B11`, `B18`; `T5`, `T6`; `H-OS`, `H-PS`, `H-DL`, `A-CL` |
| Metrics and Android capture | §6 Android/metrics; `AND-CAP-050`; `PROD-001`, `PROD-020`, `PROD-060`, `PROD-080`; `CORE-SET-1`–`CORE-TIME-1`, `CORE-CLEAN-1`, `CORE-CLEAN-2`; `CTRL-030`, `CTRL-040`, `CTRL-100`, `CTRL-110`, `CTRL-130`, `CTRL-300`, `CTRL-400`; `TGT-010`, `TGT-030`, `TGT-040`, `TGT-050`, `TGT-060`; `AND-MET-001`, `AND-MET-010`, `AND-MET-011`, `AND-MET-020`, `AND-MET-021`, `AND-MET-030`; `AND-CAP-001`, `AND-CAP-010`, `AND-CAP-020`, `AND-CAP-030`, `AND-CAP-040`, `AND-CAP-050`, `AND-CAP-070` | [04 §2.1](04-verification.md#21-source-sets-and-tasks); [04 §§3.1–3.6](04-verification.md#31-harness); `AND-CAP-060`; `ACC-07`–`ACC-10`, `ACC-17`, `ACC-18`, `ACC-25`, `ACC-27`; `B2`, `B12`–`B14`, `B18`; `T3`; `A-API`, `A-CAP`, `A-CL`, `H-OS`, `H-OB`, `H-RC` |
| Target | §6 Target; `TGT-001`, `TGT-010`; `PROD-031`; `CORE-OWN-1`, `CORE-SET-1`–`CORE-TIME-1`, `CORE-CLEAN-1`, `CORE-CLEAN-2`; `CTRL-040`, `CTRL-100`–`CTRL-130`, `CTRL-200`, `CTRL-300`, `CTRL-400`; `AND-CAP-010`–`AND-CAP-040`; `GL-001`, `GL-030`, `GL-040`, `GL-050`, `GL-070`; `DEL-PACE-001`; `TGT-001`, `TGT-010`, `TGT-020`, `TGT-030`, `TGT-040`, `TGT-050`, `TGT-060`, `TGT-070`, `TGT-080`, `TGT-090`, `TGT-095`, `TGT-098` | [04 §2.1](04-verification.md#21-source-sets-and-tasks); [04 §§3.1–3.6](04-verification.md#31-harness); `TGT-100`; `ACC-07`, `ACC-17`, `ACC-18`, `ACC-24`, `ACC-25`, `ACC-27`; `B4`, `B6`, `B13`, `B14`, `B16`, `B18`; `T2`, `T3`, `T7`; `H-RC`, `H-OS`, `H-PS`, `A-CAP`, `A-GL`, `A-CL` |
| GL, readback, and color | §6 GL; `GL-001`; `PROD-031`, `PROD-080`; `CORE-001`, `CORE-OWN-1`, `CORE-SET-1`–`CORE-SET-3`, `CORE-WAKE-2`, `CORE-TIME-1`, `CORE-ALLOC-1`, `CORE-EFF-1`, `CORE-CLEAN-1`, `CORE-CLEAN-2`, `CORE-PRIV-1`; `CTRL-040`, `CTRL-100`, `CTRL-110`, `CTRL-130`, `CTRL-300`, `CTRL-400`; `AND-CAP-040`; `TGT-030`, `TGT-070`, `TGT-080`; `NJPEG-030`; `GL-001`, `GL-010`, `GL-020`, `GL-030`, `GL-040`, `GL-050`, `GL-060`, `GL-070`, `GL-090`, `GL-100` | [04 §2.1](04-verification.md#21-source-sets-and-tasks); [04 §§3.1–3.6](04-verification.md#31-harness); `GL-080`, `GL-081`; `ACC-07`, `ACC-11`, `ACC-12`, `ACC-16`–`ACC-18`, `ACC-24`–`ACC-27`; `B3`, `B6`, `B13`, `B14`, `B16`, `B18`; `T2`, `T7`; `H-GM`, `H-OS`, `A-GL`, `A-CL` |
| Framework JPEG | §6 Framework JPEG; `FJPEG-001`, `FJPEG-010`; `PROD-040`, `PROD-060`, `PROD-070`, `PROD-080`; `CORE-001`, `CORE-OWN-1`, `CORE-SET-1`–`CORE-SET-3`, `CORE-WAKE-2`, `CORE-TIME-1`, `CORE-ALLOC-1`, `CORE-EFF-1`, `CORE-CLEAN-1`, `CORE-CLEAN-2`, `CORE-PRIV-1`; `CTRL-040`, `CTRL-100`, `CTRL-110`, `CTRL-130`, `CTRL-200`, `CTRL-300`, `CTRL-400`; `NJPEG-020`, `NJPEG-030`, `NJPEG-CONST-001`; `STORE-030`–`STORE-050`, `STORE-080`; `FJPEG-001`, `FJPEG-010`, `FJPEG-020`, `FJPEG-030`, `FJPEG-031`, `FJPEG-040`, `FJPEG-050`, `FJPEG-060`, `FJPEG-070`, `FJPEG-080`, `FJPEG-090`, `FJPEG-110` | [04 §2.1](04-verification.md#21-source-sets-and-tasks); [04 §§3.1–3.6](04-verification.md#31-harness); `FJPEG-100`; `ACC-11`, `ACC-16`, `ACC-18`, `ACC-24`–`ACC-26`; `B3`, `B6`, `B14`, `B17`–`B19`; `T4`; `H-RC`, `H-OS`, `H-PS`, `H-OB`, `A-FJ`, `A-CL` |
| Native JPEG and package | §6 Native JPEG/carrier; `NJPEG-001`, `NJPEG-010`; `PROD-060`, `PROD-100`; `CORE-001`, `CORE-OWN-1`, `CORE-SET-1`–`CORE-SET-3`, `CORE-WAKE-2`, `CORE-TIME-1`, `CORE-ALLOC-1`, `CORE-EFF-1`, `CORE-CLEAN-1`, `CORE-CLEAN-2`, `CORE-PRIV-1`; `CTRL-040`, `CTRL-100`, `CTRL-110`, `CTRL-130`, `CTRL-200`, `CTRL-300`, `CTRL-400`; `GL-060`; `STORE-010`, `STORE-030`, `STORE-060`, `STORE-080`; `NJPEG-001`, `NJPEG-010`, `NJPEG-020`, `NJPEG-030`, `NJPEG-040`, `NJPEG-050`, `NJPEG-060`, `NJPEG-070`, `NJPEG-080`, `NJPEG-090`, `NJPEG-100`, `NJPEG-CONST-001`, `NJPEG-WIRE-001`, `NJPEG-PKG-001`, `NJPEG-110` | [04 §2.1](04-verification.md#21-source-sets-and-tasks); [04 §§3.1–3.6](04-verification.md#31-harness); `NJPEG-120`; `ACC-01`, `ACC-11`, `ACC-16`–`ACC-18`, `ACC-24`–`ACC-26`; `B3`, `B6`, `B14`, `B15`, `B18`, `B19`, `B21`; `T4`; `H-NL`, `H-RC`, `H-OS`, `H-PS`, `H-OB`, `N-JPEG`, `N-PKG`, `P-PKG`, `A-FJ`, `A-CL` |
| Encoded storage | §6 Encoded storage; `STORE-001`, `STORE-010`; `PROD-040`, `PROD-050`, `PROD-070`; `CORE-OWN-1`, `CORE-ALLOC-1`, `CORE-CLEAN-1`, `CORE-CLEAN-2`, `CORE-PRIV-1`; `CTRL-200`, `CTRL-300`, `CTRL-400`; `FJPEG-050`; `NJPEG-080`; `DEL-PACE-020`, `DEL-HO-001`, `DEL-HO-020`, `DEL-HO-040`; `STORE-001`, `STORE-010`, `STORE-020`, `STORE-030`, `STORE-040`, `STORE-050`, `STORE-060`, `STORE-070`, `STORE-080`, `STORE-090`, `STORE-110` | [04 §2.1](04-verification.md#21-source-sets-and-tasks); [04 §§3.1–3.6](04-verification.md#31-harness); `STORE-100`; `ACC-14`–`ACC-16`, `ACC-18`, `ACC-20`, `ACC-24`, `ACC-25`; `B4`, `B14`, `B15`; `T4`, `T5`; `H-PS`, `H-DL`, `A-FJ`, `N-JPEG`, `A-CL` |
| Pacing, delivery, and observation | §6 Delivery/observation; [12 §1](12-domain-delivery-observation.md#1-scope-and-interfaces); `PROD-040`–`PROD-090`; `CORE-SET-1`–`CORE-TIME-1`, `CORE-CLEAN-1`, `CORE-CLEAN-2`; `CTRL-040`, `CTRL-200`, `CTRL-300`, `CTRL-400`; `TGT-050`; `STORE-020`, `STORE-070`, `STORE-080`; `DEL-PACE-001`, `DEL-PACE-010`, `DEL-PACE-020`, `DEL-HO-001`, `DEL-HO-010`, `DEL-HO-020`, `DEL-HO-030`, `DEL-HO-040`, `DEL-OBS-001`, `DEL-OBS-010`, `DEL-OBS-020`, `DEL-900` | [04 §2.1](04-verification.md#21-source-sets-and-tasks); [04 §§3.1–3.6](04-verification.md#31-harness); [12 §3](12-domain-delivery-observation.md#3-exact-verification-rows); `ACC-04`, `ACC-12`–`ACC-15`, `ACC-19`–`ACC-23`, `ACC-26`; `B4`, `B5`, `B7`–`B11`, `B19`, `B20`; `T5`, `T6`; `H-PS`, `H-DL`, `H-OB`, `H-OS`, `A-SES`, `A-CL` |
| Cleanup integration | §6 Shared runtime, Controller/reconciliation, Android/metrics, Target, GL, Framework JPEG, Native JPEG/carrier, Encoded storage, and Delivery/observation rows; `CORE-SET-1`–`CORE-TIME-1`, `CORE-CLEAN-1`, `CORE-CLEAN-2`; `CTRL-040`, `CTRL-400`; `AND-MET-030`, `AND-CAP-030`; `TGT-070`, `TGT-080`, `TGT-090`; `GL-010`, `GL-070`; `FJPEG-060`, `FJPEG-070`; `NJPEG-100`; `STORE-080`; `DEL-HO-040` | [04 §2.1](04-verification.md#21-source-sets-and-tasks); [04 §§3.1–3.6](04-verification.md#31-harness); [05 §4](05-domain-controller-reconciliation.md#4-exact-verification-rows); `AND-CAP-060`, `TGT-100`, `GL-080`, `FJPEG-100`, `NJPEG-120`, `STORE-100`; [12 §3](12-domain-delivery-observation.md#3-exact-verification-rows); `ACC-18`, `ACC-25`, `ACC-27`; `B10`, `B13`, `B14`, `B17`, `B18`; `T2`, `T4`–`T7`; `H-OS`, `H-RC`, `H-DL`, `A-CAP`, `A-GL`, `A-FJ`, `N-JPEG`, `A-CL` |
| Module and package | §4 module baseline; §6 Product/public and Native JPEG/carrier; `NJPEG-060`, `NJPEG-PKG-001` | [04 §2.1](04-verification.md#21-source-sets-and-tasks); [04 §§3.1–3.6](04-verification.md#31-harness); `NJPEG-120`; `ACC-01`, `ACC-16`, `ACC-17`, `ACC-24`; `B21`; `A-API`, `N-PKG`, `P-PKG` |

## 6. Source manifest

| Packet slice | Production files |
| --- | --- |
| Product/public | `PUB:ScreenCaptureEngine.kt`, `PUB:ScreenCaptureConfig.kt`, `PUB:ScreenCaptureParameters.kt`, `PUB:ScreenCaptureOutput.kt`, `PUB:ScreenCaptureObservations.kt` |
| Shared runtime | `INT:CleanupOwner.kt`; `SET:OperationDeadline.kt`, `SET:OperationSettlement.kt` |
| Controller/reconciliation | `CTR:SessionController.kt` including the Session scheduler owner; `CTR:ReconciliationOwner.kt` |
| Android/metrics | `AND:AndroidCaptureContracts.kt`, `AND:AndroidLane.kt`, `AND:MediaProjectionOperations.kt`, `AND:VirtualDisplayOperations.kt`, `AND:CaptureMetricsContracts.kt`, `AND:CaptureMetricsOwner.kt`, `AND:AndroidCaptureOwner.kt`; built-in provider declarations in `PUB:ScreenCaptureConfig.kt` |
| Target | `TGT:TargetContracts.kt`, `TGT:TargetConstruction.kt`, `TGT:TargetPorts.kt`, `TGT:CurrentTarget.kt`, `TGT:TargetRetirement.kt`, `TGT:TargetOwner.kt` |
| GL | `GL:GlPipelineContracts.kt`, `GL:GlLaneRuntime.kt`, `GL:EglSession.kt`, `GL:GlProgram.kt`, `GL:GlTargetBridge.kt`, `GL:GlRenderTarget.kt`, `GL:GlFramePipeline.kt`, `GL:GlCleanup.kt`, `GL:GlPipelineOwner.kt` |
| Framework JPEG | `JPG:FrameworkJpegContracts.kt`, `JPG:FrameworkBitmapOwner.kt`, `JPG:FrameworkJpegExecution.kt`, `JPG:FrameworkJpegCleanup.kt`, `JPG:FrameworkJpegOwner.kt` |
| Native JPEG/carrier | `JPG:JpegRuntimeCore.kt`, `JPG:JpegRuntimeOperations.kt`, `JPG:JpegCarrierLifecycle.kt`, `JPG:NativeResultProtocol.kt`, `JPG:NativeEncodeCoordinator.kt`, `JPG:NativeJpegProcess.kt`; Session owner `INT:JpegRuntimeOwner.kt`; `CPP:CMakeLists.txt`, `CPP:screen_capture_engine_jni.cpp`, `CPP:native_jpeg_runtime.h`, `CPP:native_jpeg_runtime.cpp`; `MOD:build.gradle.kts`, `MOD:src/main/AndroidManifest.xml`, `MOD:consumer-rules.pro` |
| Encoded storage | `INT:EncodedStorageOwner.kt` |
| Delivery/observation | `DEL:PacingOwner.kt`, `DEL:DeliveryOwner.kt`, `DEL:ObservationOwner.kt` |
| Native test boundary | `NTCPP:CMakeLists.txt`, `NTCPP:screen_capture_engine_native_test_jni.cpp` |

Semantic ownership is ID-based. A production path appearing in more than one packet slice denotes physical
co-location only and does not create duplicate authority.

The source tree is shallow and domain-oriented: `controller`, `android`, `target`, `gl`, `jpeg`, `delivery`,
and the existing `settlement` package. It uses neither a flat set of monolithic owners nor package-per-class,
file-per-method, mechanical extension-function, `utils`, or `common` decomposition. Simple immutable contracts,
facts, evidence, receipts, and construction models may be separate when they form a real domain unit. Mutable
resource state stays with its complete lease/use/release lifecycle even when that cohesive unit is larger.

Each domain owner facade is the sole mutable authority. Subordinate components accept narrow typed
facts/commands/ports and contain no competing owner or state machine. The source layout contains no additional
`CoroutineScope`, dispatcher, executor, scheduler, `HandlerThread`, lane, gate, lock, fatal fence, or
resource-lifetime machine. Its runtime topology is the exact Session scope/views, one Android lane, one GL lane
and fatal fence, JPEG `limitedParallelism(1)` view, `sessionGate`, `targetGate`, settlement gates, and their lock
order.

An active implementation unit is normally 250–500 lines. A cohesive resource-lifetime or state machine may be
roughly 600–700 lines; more than 800 lines is exceptional and requires a concrete cohesion justification, not
an automatic split. File count is bounded by the responsibilities above; no empty package is created for
symmetry. `JpegRuntimeCore.kt`, `JpegRuntimeOperations.kt`, `OperationDeadline.kt`, and
`OperationSettlement.kt` remain cohesive units. Domain-specific deadline constants stay in their consuming
domain file/package; generic deadline machinery stays settlement-owned. `EncodedStorageOwner.kt` remains at the
root while its single storage machine is cohesive.

Framework consumes carrier and transaction interfaces but owns neither declaration. Native owns the carrier,
call-scoped JNI writer, and frozen adoption-target descriptor/call/keep boundary. Storage owns the nested adoption-sink
declaration and its managed validation/copy behavior, transactions, and payload roles. Physical files are not
acceptance slices.

The exact JNI binary anchors are
`io.screenstream.engine.internal.jpeg.NativeJpegProcess` in `JPG:NativeJpegProcess.kt` and
`io.screenstream.engine.internal.EncodedStorageOwner$NativeSegmentSink` in `INT:EncodedStorageOwner.kt`.
`NativeJpegProcess` owns only process loader state and the stateless JNI facade; `JpegRuntimeOwner` remains the
sole Session mode/health/backend/carrier authority.

## 7. Test manifest

This section is the sole executable-test ID-to-source-path manifest; Document 04 and domain leaves reference
these stable IDs.

| ID | Exact path | ID | Exact path |
| --- | --- | --- | --- |
| `H-LC` | `HOST:ControllerLifecycleTest.kt` | `H-RC` | `HOST:ReconciliationAndAllocationTest.kt` |
| `H-OS` | `HOST:OperationSettlementRaceTest.kt` | `H-PS` | `HOST:PacingStorageStatsTest.kt` |
| `H-DL` | `HOST:DeliveryOwnerRaceTest.kt` | `H-GM` | `HOST:GeometryColorAndBoundsTest.kt` |
| `H-OB` | `HOST:ObservationAndDiagnosticsTest.kt` | `H-NL` | `HOST:NativeLoaderPolicyTest.kt` |
| `A-API` | `AIT:PublicApiInstrumentationTest.kt` | `A-SES` | `AIT:SessionContractInstrumentationTest.kt` |
| `A-CAP` | `AI:AndroidCaptureInstrumentationTest.kt` | `A-GL` | `AI:GlPipelineImageInstrumentationTest.kt` |
| `A-FJ` | `AI:FrameworkJpegInstrumentationTest.kt` | `A-CL` | `AI:CleanupIsolationInstrumentationTest.kt` |
| `N-JPEG` | `AI:NativeJpegInstrumentationTest.kt` | `N-PKG` | `AI:NativePackagingInstrumentationTest.kt` |
| `P-PKG` | `HOST:BuildPackagingContractTest.kt` |  |  |

Exact runner/task/module bindings are solely in
[`04-verification.md` §2.1](04-verification.md#21-source-sets-and-tasks); this router does not duplicate them.

## 8. Cross-domain inbound routing

| Consumer | Inbound owner IDs | Consumer authority |
| --- | --- | --- |
| Controller/reconciliation | immutable owners/facts/receipts exposed by each leaf's typed-boundary section | `CTRL-040`, `CTRL-100`, `CTRL-110`, `CTRL-120`, `CTRL-130`, `CTRL-200`, `CTRL-300`, `CTRL-400` |
| Session scheduler | deadline wake requests from `CORE-WAKE-2`; pacing/Stats/accepted-task wake commands from `CTRL-200`, `DEL-PACE-010`, `DEL-HO-001`, `DEL-HO-010` | `CTRL-040` |
| Android capture | Target ports/leases, producer/listener/detach evidence and provenance from `TGT-030`, `TGT-040`, `TGT-050`, `TGT-060` | `AND-CAP-020`, `AND-CAP-030` |
| GL | Target ports/leases from `TGT-030`, `TGT-070`; exact carrier lease from `NJPEG-030` | `GL-001`, `GL-050`, `GL-060`, `GL-070` |
| Target | Android results from `AND-CAP-020`, `AND-CAP-030`; GL construction/destruction/namespace evidence from `GL-050`, `GL-070` | `TGT-010`, `TGT-040`, `TGT-050`, `TGT-060`, `TGT-070`, `TGT-080` |
| Framework JPEG | carrier lease from `NJPEG-020`, `NJPEG-030`; transaction from `STORE-030`, `STORE-040`, `STORE-050`; selection/currentness from `CTRL-100`, `CTRL-110`, `CTRL-130`, `CTRL-300` | `FJPEG-010` |
| Native JPEG | GL range/metadata from `GL-060`; transaction/adoption endpoint from `STORE-010`, `STORE-030`, `STORE-060`; selection/currentness from `CTRL-100`, `CTRL-110`, `CTRL-130` | `NJPEG-010` |
| Storage | producer facts from `FJPEG-010`, `FJPEG-050`, `NJPEG-010`, `NJPEG-080`; role commands from `CTRL-200`, `CTRL-300` | `STORE-010` |
| Delivery/observation | payload/lease from `STORE-070`; accepted calculations, commits, snapshots and cutoff from `CTRL-200`, `CTRL-300`, `CTRL-400` | `DEL-PACE-001`, `DEL-PACE-010`, `DEL-PACE-020`, `DEL-HO-001`, `DEL-HO-010`, `DEL-HO-020`, `DEL-HO-030`, `DEL-HO-040`, `DEL-OBS-001`, `DEL-OBS-010`, `DEL-OBS-020`, `DEL-900` |
| Cleanup | unresolved occurrence from `CORE-SET-1`, `CORE-SET-2` plus the affected leaf's typed obligations | `CORE-CLEAN-1`, `CORE-CLEAN-2`, `CTRL-400`, and the leaf's explicit cleanup section |
