# Screen Capture Engine — Native JPEG Domain Contract

This file solely owns the Session JPEG runtime and carriers, own-DSO loader/bootstrap, weak compressor
capability, JNI and JNI-free native boundary, Native encode evidence, writer residue, native cleanup, and native
build/package contract. Framework Bitmap behavior belongs to
[09-domain-framework-jpeg.md](09-domain-framework-jpeg.md), transactional managed bytes and publication to
[11-domain-encoded-storage.md](11-domain-encoded-storage.md), render/readback to
[08-domain-gl.md](08-domain-gl.md), and policy/currentness commits to
[05-domain-controller-reconciliation.md](05-domain-controller-reconciliation.md). Shared occurrence, deadline,
ownership, allocation, and quarantine mechanics are [CORE-SET-1](03-shared-runtime.md#core-set-1--generic-operation-occurrence)
through [CORE-CLEAN-2](03-shared-runtime.md#core-clean-2--quarantine-nonreturn-and-late-reduction). Caller-visible
fallback and errors remain [PROD-060](02-product-contract.md#prod-060--state-and-errors) and
[PROD-100](02-product-contract.md#prod-100--explicit-v1-boundaries).

## Navigation

- [NJPEG-001](#njpeg-001--files-binary-identity-and-authority)
- [NJPEG-010](#njpeg-010--typed-boundary-map)
- [NJPEG-020](#njpeg-020--session-runtime-execution-view-and-legal-products)
- [NJPEG-030](#njpeg-030--carrier-ownership-and-replacement)
- [NJPEG-040](#njpeg-040--own-dso-loader-and-bootstrap)
- [NJPEG-050](#njpeg-050--registration-and-frozen-jni-surface)
- [NJPEG-060](#njpeg-060--weak-compressor-capability-and-non-ownership)
- [NJPEG-070](#njpeg-070--preparation-descriptor-and-entered-interval)
- [NJPEG-080](#njpeg-080--writer-capsule-result-block-and-adoption)
- [NJPEG-090](#njpeg-090--native-encode-result-and-fallback)
- [NJPEG-100](#njpeg-100--cleanup-and-late-return)
- [NJPEG-CONST-001](#njpeg-const-001--jpeg-entered-operation-interval)
- [NJPEG-WIRE-001](#njpeg-wire-001--fixed-writer-codes)
- [NJPEG-PKG-001](#njpeg-pkg-001--native-build-and-artifacts)
- [NJPEG-110](#njpeg-110--forbidden-alternatives)
- [NJPEG-120](#njpeg-120--executable-obligations)

## NJPEG-001 — Files, binary identity, and authority

Production ownership is split without splitting behavior. Every path uses the alias convention in
[router §4](01-authority-router.md#4-module-and-path-bindings):

| Path | Sole role |
| --- | --- |
| `JPG:JpegRuntimeCore.kt` | Cohesive closed JPEG products, `NativeJpegHealth`, carrier owner/lease contracts, and native-free identity/evidence/bag/receipt. |
| `JPG:JpegRuntimeOperations.kt` | Cohesive typed preparation, replacement-allocation and Native-encode occurrences/evidence/bags; frame/result descriptors and writer-block loans. |
| `JPG:JpegCarrierLifecycle.kt` | Native/managed carrier allocation, lease, replacement, free, and logical-retirement mechanics under owner commands. |
| `JPG:NativeResultProtocol.kt` | Exact result-block/writer-status decoding and complete immutable evidence assembly; no policy classification or health mutation. |
| `JPG:NativeEncodeCoordinator.kt` | One admitted Native encode execution, JNI/adoption coordination, and return publication under the owner-selected occurrence. |
| `INT:JpegRuntimeOwner.kt` | Root ABI facade and sole mutable Session `JpegRuntimeOwner`: Session mode/health, process loader, backend/carrier selection, operation admission, and private nested `NativeBridge`. |
| `CPP:screen_capture_engine_jni.cpp` | Production JNI glue, bootstrap/registration, guarded weak helper, registered entries, block validation, writer services, adoption adapter, narrowing, and exception containment. |
| `CPP:native_jpeg_runtime.h`, `CPP:native_jpeg_runtime.cpp` | JNI-free immutable descriptor, non-owning compressor function, writer/adopter services, capsule/segments, encode mechanics, evidence, and release. |
| `CPP:CMakeLists.txt` | Production object composition, `jnigraphics` link, visibility, weak-API diagnostics, and production native target. |
| `MOD:build.gradle.kts`, `MOD:src/main/AndroidManifest.xml`, `MOD:consumer-rules.pro` | Module native configuration, empty library manifest, artifacts, and exact JNI keep boundary. |

The private binary class `io.screenstream.engine.internal.JpegRuntimeOwner$NativeBridge`, its bootstrap method,
and all registered method names/signatures are rooted in `INT:JpegRuntimeOwner.kt`. The production adoption
target is `io.screenstream.engine.internal.EncodedStorageOwner$NativeSegmentSink.adoptNativeSegment`. Both
binary boundaries use these exact root identities, method names, and signatures.

`JpegRuntimeOwner` alone mutates Session carrier mode and Native health. The loader coordinator is process-private;
it owns no Session state. JPEG components act only on narrow owner commands and cannot mutate backend/health
independently or create another scope, dispatcher, gate, lane, or resource-lifetime machine. Native runtime code
owns no Session selector, compressor handle, `JNIEnv`, Java global reference, or cross-DSO mutable owner.

## NJPEG-010 — Typed boundary map

| Direction | Exact boundary | Native-domain obligation |
| --- | --- | --- |
| inbound from [`CTRL-100`](05-domain-controller-reconciliation.md#ctrl-100--currentness-identities), [`CTRL-110`](05-domain-controller-reconciliation.md#ctrl-110--reconciliation-decision), [`CTRL-130`](05-domain-controller-reconciliation.md#ctrl-130--completion-and-fallback-arbitration) | accepted reconciliation key, backend policy, checked output shape/quality, current Native-health occurrence | Prepare or encode only the exact admitted occurrence; return immutable evidence and owners for controller arbitration. |
| inbound from [`GL-060`](08-domain-gl.md#gl-060--geometry-shader-color-and-readback) | leased exact tight RGBA range, positive dimensions, nominal sRGB/opaque metadata | Accept Native encode only with `NativeMallocCarrier`; retain the carrier lease through mechanical return. |
| inbound from [`STORE-010`](11-domain-encoded-storage.md#store-010--typed-boundary), [`STORE-030`](11-domain-encoded-storage.md#store-030--transaction-state-and-arbitration), [`STORE-060`](11-domain-encoded-storage.md#store-060--native-segment-adoption) | one tentative transaction and `NativeSegmentSink` adoption endpoint | Treat the transaction as borrowed through the encode occurrence; never publish or cache bytes here. |
| outbound to [`FJPEG-010`](09-domain-framework-jpeg.md#fjpeg-010--typed-domain-boundary), [`FJPEG-040`](09-domain-framework-jpeg.md#fjpeg-040--exact-carrier-to-bitmap-transfer) | exact-range lease over the installed native or managed carrier after Framework is selected | Keep carrier ownership in `JpegRuntimeOwner`; Framework receives only its operation-scoped view. |
| outbound to [`CTRL-130`](05-domain-controller-reconciliation.md#ctrl-130--completion-and-fallback-arbitration), [`CTRL-200`](05-domain-controller-reconciliation.md#ctrl-200--policy-attempt-counter-and-observation-authority), [`CTRL-300`](05-domain-controller-reconciliation.md#ctrl-300--cross-domain-commit-rules) | carrier/backend product, payload-free `Enabled`/`Disabled`, encode result, safe-disable fact, or exact unsafe residue | Publish fixed evidence before signalling; controller alone changes lifecycle, health, fallback, counters, and topology. |
| outbound to [`STORE-060`](11-domain-encoded-storage.md#store-060--native-segment-adoption), [`STORE-080`](11-domain-encoded-storage.md#store-080--failure-terminal-and-cleanup) | synchronous native-segment calls plus exact native transfer/residue facts | Native preserves one-segment ordering and native ownership; storage alone validates, copies, mutates, commits, or publishes managed bytes. |
| outbound to [`CORE-CLEAN-1`](03-shared-runtime.md#core-clean-1--cleanup-forest-and-dependency-graph), [`CORE-CLEAN-2`](03-shared-runtime.md#core-clean-2--quarantine-nonreturn-and-late-reduction) | unresolved occurrence, writable cells, carrier/transaction leases, writer block/capsule/segments and cleanup operations | Transfer the intact graph; late facts reduce only their exact child. |

A raw pointer, direct-buffer address, writer token, compressor function, ABI string, Boolean capability, or result
code is never ownership or currentness authority by itself.

## NJPEG-020 — Session runtime, execution view, and legal products

After accepted start, each Session retains one distinct, non-owned `Dispatchers.IO.limitedParallelism(1)` view
for non-suspending owner Runnables covering Native preparation, Framework/native encode, Framework
creation/recycle, native carrier free/allocation, and managed-direct replacement allocation. This view is
distinct from delivery and metrics views. It serializes admitted Runnables without owning their occurrences or
promising physical thread identity or cross-Session progress through the shared IO runtime.

Each Runnable carries its precreated submission, entry, return, deadline, and owner cells. Terminal closes new
admission and settles or transfers exact occurrences; the view itself is never closed, cancelled, quarantined,
or awaited and has no shutdown/termination receipt. A nonreturn roots only the Session-owned occurrence and its
real owners/leases, not the dispatcher or view.

One `JpegRuntimeOwner` stores one Session-fixed carrier mode and one Session-monotone `NativeJpegHealth` whose
payload-free values are exactly `Enabled` and `Disabled`. The only stable products are:

```text
NativeEnabled(NativeMallocCarrier)
FrameworkOnNativeCarrier(NativeMallocCarrier)
FrameworkOnManagedCarrier(ManagedDirectCarrier)
```

Native encode accepts only `NativeMallocCarrier`; managed carrier plus Native enabled is unrepresentable.
Framework selection never replaces a healthy compatible native carrier. Native health and Target/GL health are
independent. Framework resource eligibility and switching-frame behavior are
[`FJPEG-030`](09-domain-framework-jpeg.md#fjpeg-030--combined-resource-creation) and
[`CTRL-130`](05-domain-controller-reconciliation.md#ctrl-130--completion-and-fallback-arbitration).

## NJPEG-030 — Carrier ownership and replacement

For output `(Ow, Oh)`, checked `B = 4 * Ow * Oh` is positive and `Int`-representable before allocation or JNI
narrowing. A stable carrier is reused by identity while it is direct, writable, exact-capacity `B`, and healthy.

| Carrier | Exact ownership and retirement |
| --- | --- |
| `NativeMallocCarrier` | Owns one positive checked `malloc` allocation, its pointer, the exact referential direct `ByteBuffer` view, leases, and one one-shot `nativeFreeCarrier` occurrence. Normal JNI return is the sole free receipt. |
| `ManagedDirectCarrier` | Owns one checked direct writable `ByteBuffer` and leases. After every use settles, retirement drops the last engine reference; it has no physical-free or GC receipt. |

An incompatible native carrier replacement is sequential:

```text
drain leases/entered uses
-> one native free occurrence and timely normal receipt
-> fresh current + nonterminal + no-extant-replacement recheck
-> distinct NativeCarrierReplacementAllocationOccurrence
```

The allocation occurrence calls `nativeAllocateCarrier(B)`. Timely current success installs its immediately
owned carrier; current allocation OOM is `ResourceExhausted`; active rejection, unexpected throw, malformed
evidence, expiry, nonreturn, or uncertain ownership is `InternalFailure`. A stale, late, or terminal returned
carrier enters one-shot cleanup free; safe stale OOM changes no lifecycle result. Terminal before entry invokes
no JNI. Free and allocation receive separate full JPEG intervals and never form one compound deadline.

Managed replacement is the distinct `ManagedDirectCarrierReplacementAllocationOccurrence`. After settled uses,
the old carrier is logically detached; a fresh current/nonterminal/no-extant recheck precedes one
`ByteBuffer.allocateDirect(B)` call. The occurrence owns a returned buffer immediately. Timely current success
installs it; current direct OOM is `ResourceExhausted`; active rejection, other throw, malformed evidence,
expiry, nonreturn, or ambiguity is `InternalFailure`. Safe stale OOM changes no lifecycle result, and a stale,
late, or terminal success is logically dropped only after settlement. This path performs no preparation rerun,
JNI free, or reclamation inference.

## NJPEG-040 — Own-DSO loader and bootstrap

One process-private synchronized coordinator permits exactly one direct
`System.loadLibrary("screencaptureengine")` attempt. Its state tag, first-cause slot, bridge-publication slot, and
every preparation result holder exist before load entry. Static initialization and `JNI_OnLoad` create no
carrier, sink, Session resource, worker, owner, global reference, or registration side effect.

`JNI_OnLoad` is `noexcept` and version-only: it accepts and returns `JNI_VERSION_1_6`. After normal load return,
the private `NativeBridge` instance invokes exported `nativeBootstrap(): Int` once. Bootstrap obtains the
receiver class with `GetObjectClass`, installs one fixed `RegisterNatives` table, and publishes the bridge only
for zero/JNI-OK with no pending exception.

| Observation | Process result and Session disposition |
| --- | --- |
| direct load `UnsatisfiedLinkError` or `SecurityException`, before bootstrap/JNI operation and with zero native owner | clean unavailability; preparation selects `FrameworkOnManagedCarrier` and `Disabled` |
| first `OutOfMemoryError` thrown directly by load | sticky `LoadOome(firstCause)` with the identical cause and absent bridge; timely current preparation is `ResourceExhausted` |
| any other load throwable | sticky unsafe `InternalFailure` |
| load returned, then bootstrap/class/registration/pending-exception/unexpected-result failure | sticky post-entry `InternalFailure` poison |
| successful load, bootstrap, registration, and bridge publication | process bridge available; registered operations cannot revise loader state |

`LoadOome` prevents every later load, bootstrap, carrier allocation, and managed fallback until process death;
all later timely current preparations receive `ResourceExhausted` with the same first cause. A load OOM published
after its originating Session's deadline or terminal winner still governs future Sessions but cannot revise that
Session. Clean unavailability and poison likewise create no retry.

Successful own-DSO load/bootstrap/registration is sufficient structural proof that the process is executing a
shipped library. Runtime selection has no executing-ABI query, field, string, enum, `Build.SUPPORTED_ABIS`,
`Process.is64Bit()`, or comparison. Static package inspection separately proves all shipped ABIs.

After bridge publication, initial `NativeMallocCarrier` allocation failure is required-storage
`ResourceExhausted`; it cannot select the managed carrier. A registered runtime-operation failure uses that
operation's frozen partition and cannot retroactively alter the successful loader state.

## NJPEG-050 — Registration and frozen JNI surface

Production exports exactly:

```text
JNI_OnLoad
Java_io_screenstream_engine_internal_JpegRuntimeOwner_00024NativeBridge_nativeBootstrap
```

Every runtime method is in the single registered table:

| Kotlin method | JNI signature | Exact contract |
| --- | --- | --- |
| `nativeAllocateCarrier(byteCount: Long): ByteBuffer` | `(J)Ljava/nio/ByteBuffer;` | Check positive `jlong` to `size_t`; after `malloc`, ownership transfers only when `NewDirectByteBuffer` succeeds. Provably owned partial allocation is freed on failure. |
| `nativeFreeCarrier(pixels: ByteBuffer): Unit` | `(Ljava/nio/ByteBuffer;)V` | Kotlin requires exact view identity/range, zero leases and one-shot admission; JNI requires nonnull direct address and positive representable capacity, frees that address once, and normal return is the receipt. |
| `nativeHasWeakCompressor(): Boolean` | `()Z` | `Auto` preparation only; returns payload-free capability through the guarded helper. |
| `nativeCompress(writerBlock, pixels, pixelByteCount, width, height, stride, format, flags, dataspace, compressFormat, quality, sink, resultBlock): Int` | `(Ljava/nio/ByteBuffer;Ljava/nio/ByteBuffer;JIIIIJIIILio/screenstream/engine/internal/EncodedStorageOwner$NativeSegmentSink;Ljava/nio/ByteBuffer;)I` | Uses this exact argument order; compressor result is authoritative only after complete writer/JNI/adoption evidence. |
| `nativeReleaseWriterResidue(writerBlock: ByteBuffer): Int` | `(Ljava/nio/ByteBuffer;)I` | Exact nonzero-token owner block only after adoption returned; zero result plus cleared token is the capsule/segment-release receipt. |

`MOD:consumer-rules.pro` retains only the exact nested bridge bootstrap/registered members and the adoption sink
binary method. Production JNI contains no fault selector or test hook.

Every JNI export/entry has an outer C++ containment boundary. No C++ exception crosses JNI. Bootstrap maps
`std::bad_alloc`, typed, and unknown exceptions to bootstrap `InternalFailure`; only a direct load OOM has the
load-time `ResourceExhausted` meaning. Runtime boundaries map `std::bad_alloc` to exact OOM only where their
frozen partition admits allocation OOM; other exceptions are `InternalFailure`. An already-pending Java
exception remains pending while only exception-safe evidence and local-reference cleanup proceed.

## NJPEG-060 — Weak compressor capability and non-ownership

The native module links system `jnigraphics` directly and consumes `ANDROID_WEAK_API_DEFS=ON` under NDK
`28.2.13676358`; unguarded-availability diagnostics remain errors. The dependency itself is not weak, so loading
the engine DSO may map `libjnigraphics.so` on API 24–29 and under `FrameworkOnly`. That opaque incremental cost
is not a runtime selector or predictive memory input.

`FrameworkOnly` loads/selects the own bridge and carrier but never evaluates or invokes the platform compressor.
Own-bridge/carrier preparation is not a Native compressor attempt.
`Auto` preparation and every actual Native compression use the same native helper and same function:

```cpp
if (__builtin_available(android 30, *)) {
    auto compressor = &AndroidBitmap_compress;
    if (compressor != nullptr) {
        // use only inside this native call
    }
}
```

API 24–29 never enter the guarded scope or invoke the compressor. On API 30+, null during preparation is clean
static Native ineligibility: keep the native carrier, publish `Disabled`, and select Framework before assigning
a Native frame. A true result publishes only `Enabled`. Each enabled `nativeCompress` re-resolves the function;
an inconsistent null is pre-invocation `InternalFailure` with no compressor call or fallback.

The typed function pointer is passed by value to the JNI-free runtime for one call and discarded on return. No
pointer crosses JNI or persists in Session/global/native writable state; there is no `dlopen`/`dlsym`/`dlclose`,
platform handle, capsule, lease, close obligation, or receipt for the compressor.

## NJPEG-070 — Preparation, descriptor, and entered interval

One finite preparation `OperationOccurrence` covers, in one entered interval:

```text
immediately before System.loadLibrary
-> loader serialization / JNI_OnLoad / bootstrap-registration as applicable
-> policy and guarded capability check
-> carrier/backend selection and required initial carrier allocation
-> allocation-free complete result publication
```

`FrameworkOnly` skips only the compressor capability query. A complete `T < D` result applies the loader,
capability, carrier, and backend partitions above. Empty-at-deadline or `T >= D` is `InternalFailure`; late
capability/unavailability and returned owners are cleanup-only. Terminal after entry retires the deadline and
transfers the same writable occurrence to no-watchdog cleanup.

Each real Native frame is the first compressor call for that frame and receives one immutable
`NativeFrameDescriptor`:

| Field | Exact value |
| --- | --- |
| pixels / byte count | stable tight RGBA carrier address and `B` |
| `AndroidBitmapInfo` | `width=Ow`, `height=Oh`, `stride=4*Ow`, `format=ANDROID_BITMAP_FORMAT_RGBA_8888`, `flags=ANDROID_BITMAP_FLAGS_ALPHA_OPAQUE` with hardware bit clear |
| color/format | `dataspace=ADATASPACE_SRGB`, `compressFormat=ANDROID_BITMAP_COMPRESS_FORMAT_JPEG` |
| remaining | requested quality, exact transaction `userContext`, nonnull `AndroidBitmap_CompressWriteFunc` |

Kotlin checks positive dimensions/stride/count, quality, exact direct pixel capacity/range, and every signed or
unsigned narrowing before entry. Width/height/stride narrow to `uint32_t`; flags from nonnegative `jlong` to
`uint32_t`; pixel bytes to `size_t`; enum/quality values to their signed fields. `AndroidBitmapInfo` field order
is width, height, stride, format, flags; remaining descriptor order is dataspace, compress format, quality,
pixels, writer function, writer context. Carrier and transaction leases remain owned through mechanical return.

## NJPEG-080 — Writer capsule, result block, and adoption

Before compressor entry, native allocates one typed `WriterCapsule` and writes its round-trippable nonzero
`uintptr_t` as `uint64_t` into an exact zero-token `NativeWriterOwnerBlock`. The native-order managed block has
one opaque `uint64_t ownerToken`; Kotlin never interprets it as a `Long`. It does not alias or embed the C++
object. A token must be nonzero and round-trip through `uint64_t`; failure is malformed `InternalFailure`. Kotlin
neither reads nor passes the token as a scalar.

The capsule owns a native mutex, sticky status, checked cumulative length, ordered callback-sized malloc
segments, and cleanup state. The native-only `AndroidBitmap_CompressWriteFunc` is `noexcept`, uses no `JNIEnv` or
managed callback, and applies:

| Callback observation | Result |
| --- | --- |
| `size == 0` after valid capsule/state | true; nullable data is accepted; no allocation, copy, link, or length change |
| valid nonempty data/range/state and successful exact malloc/copy/link | true; append one exact owned segment |
| allocation failure or otherwise-valid segment/cumulative length outside managed `Int` representation | sticky `OutOfMemory`; false |
| null data for nonzero size, invalid capsule/state, lock/link/publication failure, or unexpected internal fault | sticky `InternalFailure`; false |

Status is monotone `Safe -> OutOfMemory -> InternalFailure`; `InternalFailure` is absorbing. After OOM, later
callbacks perform only nonthrowing contract/state checks so malformed evidence may upgrade it, but no bytes,
length, allocation, copy, or link changes. After InternalFailure they return false immediately.

`NativeResultBlock` is a separate preallocated native-order direct block with exactly five explicit `uint64_t`
words, accessed field-by-field:

```text
writerStatus, totalEncodedBytes, managedAdoptedBytes,
nativeRemainingBytes, nativeRemainingSegmentCount
```

Every normal evidence-return path initializes all five words. Writer residue identity exists only in the writer
owner block. The exact writer wire values are owned by [NJPEG-WIRE-001](#njpeg-wire-001--fixed-writer-codes).

Runtime freezes compressor and writer evidence before transfer. Only safe-writer
`ANDROID_BITMAP_RESULT_SUCCESS` invokes the supplied adopter. Each native segment is transferred sequentially:
production creates one temporary direct local view, calls
`NativeSegmentSink.adoptNativeSegment(ByteBuffer, Int)` synchronously, deletes the local reference, and frees
that segment only after the adoption return proves it unborrowed. Exact managed validation, copy, transaction
mutation, and view non-retention are solely [`STORE-060`](11-domain-encoded-storage.md#store-060--native-segment-adoption).
Non-success compressor results invoke no adopter and free every provably owned native segment before
classification. Failure retains exact adopted/native/residue facts; uncertain borrowing is quarantined.

An adoption Java exception stays pending while native records exception-safe evidence, deletes the temporary
local reference, and frees only proven-unborrowed segments. Kotlin catches the identical throwable; the returned
compressor integer is absent/non-authoritative on that branch. A zero token plus zero native remaining bytes/
segments and normal JNI return proves native writer ownership retired; a nonzero token transfers the exact
capsule and remaining segments into the occurrence bag for `nativeReleaseWriterResidue` or quarantine.

Let `J` be the frozen total encoded bytes, `N` the bytes still owned in native segments, `M` the bytes already
adopted into the managed transaction, `s` the current segment size, and `Smax` the largest actual segment in this
call. Before each adoption, `N + M = J`. While copying the current segment, the exact transient relation is
`N + M + s = J + s <= J + Smax`; normal adoption return followed by native free moves `s` from `N` to `M` and
removes that duplicate. A one-segment result has transient `2J`; complete transfer leaves `N = 0`, `M = J`, and
no native cache backing. These are observed ownership equations, not admission estimates or a runtime axis.

The precreated production return cell records the complete evidence inventory before classification:

| Fact | Exact production evidence |
| --- | --- |
| carrier | exact pixel-carrier lease/range identity and whether mechanical JNI return proved the borrow ended |
| compressor | frozen result present only on a normal exception-free return; otherwise explicitly absent, with the exact JNI return/throw fact |
| segments | all five `NativeResultBlock` words, writer-token state, and ordered native remaining/adopted byte and segment ownership facts |
| adoption | each synchronous sink call's segment identity/size, normal return or identical throwable, managed transaction status/bytes, and the structural non-retention obligation for its temporary view |
| free | ordered native-segment free receipts, plus token-clear or exact writer-residue identity for later release |

The test-only `CompressReceipt` is only a projection of this production evidence; it is not a production result
type or substitute receipt.

## NJPEG-090 — Native encode result and fallback

The encode occurrence starts its own JPEG interval immediately before the outward `nativeCompress` entry and
ends only after allocation-free complete return-cell publication. Classification accumulates all writer, JNI,
adoption, transaction, ownership, and compressor evidence before applying this precedence:

1. ownership ambiguity, malformed writer/block/callback/contract evidence, or non-OOM JNI/adoption exception:
   terminal `InternalFailure`;
2. exact writer/sink/adoption/JNI OOM or unrepresentable cumulative length: terminal `ResourceExhausted`;
3. only then, classify the present compressor result.

| Compressor result after clean higher-precedence evidence | Exact disposition |
| --- | --- |
| `ANDROID_BITMAP_RESULT_SUCCESS` plus complete adoption and committable transaction | mechanically successful encode; [`STORE-050`](11-domain-encoded-storage.md#store-050--commit-immutable-payload-and-caller-copies) alone commits immutable bytes, subject to controller currentness |
| `ANDROID_BITMAP_RESULT_ALLOCATION_FAILED` with exact returned carrier/sink ownership | safe optional failure: abort bytes, record this materialized attempt once as `byFailure`, and monotonically disable Native for later work |
| `ANDROID_BITMAP_RESULT_BAD_PARAMETER` | terminal `InternalFailure` for the engine-built descriptor |
| `ANDROID_BITMAP_RESULT_JNI_EXCEPTION` without exact OOM, unknown code, or impossible result/evidence combination | terminal `InternalFailure` |

A safe disable atomically belongs to the still-current Native-health occurrence. A current-key attempt drops the
switching frame; a key-stale/current-health attempt also records `byFailure`, publishes no stale output or
lifecycle failure, and disables Native for the latest reconciliation. Framework allocation/work begins only for
a later frame; there is no same-frame retry. Results at `T >= D`, after expiry, after health retirement, or after
terminal transfer are cleanup-only and cannot disable Native or change lifecycle/counters.

The same call never publishes partial bytes. Unsafe nonreturn or uncertain carrier, transaction, writer, segment,
or adoption ownership is terminal `InternalFailure` and roots the exact occurrence. Native fallback never changes
Target or Direct-readback health.

Native publishes only typed diagnostic-trigger facts. Source/label selection, the sole JPEG timeout-source
matrix, construction, and emission are [`DEL-OBS-020`](12-domain-delivery-observation.md#del-obs-020--diagnostic-construction-and-emission)
and have no control authority.

## NJPEG-100 — Cleanup and late return

Native cleanup follows [CORE-CLEAN-1](03-shared-runtime.md#core-clean-1--cleanup-forest-and-dependency-graph)
without retries:

| Obligation | Real completion and blocked dependents |
| --- | --- |
| native carrier free | one normal `nativeFreeCarrier` return; throw/nonreturn retains carrier, view, occurrence and blocks replacement |
| returned stale/late carrier | its own one-shot free; no install or lifecycle result |
| writer residue | zero `nativeReleaseWriterResidue` return plus cleared token; ambiguous borrowed segment remains rooted |
| encode/adoption | mechanical JNI/adopter return plus exact segment release and transaction abort/retirement facts |
| managed carrier | last engine-reference drop after all uses; no physical receipt |

Terminal before an unentered optional encode cancels without JNI. Terminal before an admitted mandatory free
converts the same occurrence to no-watchdog cleanup; terminal after entry retires its deadline and transfers the
intact writable occurrence. A carrier free created by terminal retirement starts as one no-watchdog occurrence.
Late return can reduce only the matching cleanup child and cannot publish bytes, install a carrier, retry,
fallback, change Native health, counters, State, or terminal winner. Independent roots and another Session
continue; a shared IO worker/vendor nonreturn carries no finite progress guarantee.

## NJPEG-CONST-001 — JPEG entered-operation interval

`jpegEnteredOperationSafetyNanos = 15_000_000_000L` is the sole JPEG private duration. It applies independently
to complete Native preparation, Framework resource creation, Framework/Native encode, preterminal Bitmap recycle,
incompatible native-carrier free, and each native/managed replacement allocation. Terminal-origin or converted
recycle/free cleanup has no watchdog.

For every finite occurrence, [CORE-SET-3](03-shared-runtime.md#core-set-3--finite-deadline-arbitration) owns checked
deadline arithmetic, `T < D` timeliness, equality-to-expiry, terminal transfer, and late facts. Consecutive
stages receive independent intervals. This constant is implementation policy, not a public latency guarantee.

## NJPEG-WIRE-001 — Fixed writer codes

| `NativeResultBlock.writerStatus` | Kotlin raw `Long` bits | Native `uint64_t` |
| --- | ---: | ---: |
| `Safe` | `0L` | `0` |
| `OutOfMemory` | `1L` | `1` |
| `InternalFailure` | `2L` | `2` |

Kotlin decodes by exact raw-bit equality, never enum ordinal, range, or maximum arithmetic. Every other 64-bit
pattern, including a high-bit pattern observed as a negative Kotlin `Long`, is malformed `InternalFailure` and
authorizes no bytes, fallback, or Native-health change.

## NJPEG-PKG-001 — Native build and artifacts

The production JNI-free runtime compiles once as hidden PIC CMake OBJECT target
`screencaptureengine_runtime` and links unchanged into both DSOs. Both JNI glue units include the same
`CPP:native_jpeg_runtime.h`:

| Variant/target | Packaged result and isolation |
| --- | --- |
| production main/debug/release/nativeTest target `screencaptureengine` | `libscreencaptureengine.so`, load name `screencaptureengine`; production glue and runtime are hook-free and unchanged |
| nativeTest-only target `screencaptureengine_native_test` | `libscreencaptureengine_native_test.so`; test-local glue/services/probes only; not published and cannot access production state |

`MOD:build.gradle.kts` passes `-DANDROID_WEAK_API_DEFS=ON` to every production/nativeTest native configuration
using the production target. `CPP:CMakeLists.txt` consumes it, links `jnigraphics`, applies hidden visibility,
and keeps unguarded-availability diagnostics as errors. This is module-local binding and prescribes no root
Gradle/plugin/wrapper configuration.

The library fixes `testBuildType = "nativeTest"`. Only nativeTest passes
`SCREEN_CAPTURE_ENGINE_NATIVE_TEST=ON`, which adds the test DSO without changing compilation of the shared
runtime object or production glue.

Production packages exactly `armeabi-v7a`, `arm64-v8a`, `x86`, and `x86_64`, with 16-KiB load alignment.
Debug/release contain only the production DSO; nativeTest contains both. Host tests package no native code. The
library manifest adds no component, permission, service, provider, or runtime feature. Static inspection owns
four-ABI, exports, weak undefined symbol/guard, direct `DT_NEEDED` on `jnigraphics`, target composition,
configuration-argument coverage, DSO isolation, and alignment evidence; connected execution proves only its
actual ABI.

## NJPEG-110 — Forbidden alternatives

- executing-ABI inference or runtime ABI-selection axis;
- stored compressor pointer, platform-library handle, dynamic symbol ownership, or compressor cleanup;
- managed carrier with Native enabled, second Session health cell, per-frame capability cache, or device allowlist;
- same-frame Framework retry, precreated Framework resources while Native is healthy, or Target/GL fallback coupling;
- result classification before all writer/JNI/adoption/ownership evidence, or compressor result outranking faults;
- raw writer token in a result, Kotlin token interpretation, native struct aliasing of owner/result blocks, or
  unknown writer-status acceptance;
- JNI from the writer callback, retained temporary segment view, grouped native segments, native-backed published
  payload, or publication outside [`STORE-050`](11-domain-encoded-storage.md#store-050--commit-immutable-payload-and-caller-copies);
- loader/bootstrap retry after a fixed result, managed fallback after load OOM/poison, or owner creation in
  static initialization/`JNI_OnLoad`;
- cancellation/closure/quarantine/termination receipt for the shared IO view, or coroutine suspension inside an
  owner Runnable;
- timeout as release, late return as active authority, duplicate free/residue release, or fabricated managed
  reclamation receipt.

## NJPEG-120 — Executable obligations

Closed packet membership is in [router §5](01-authority-router.md#5-closed-implementation-packets); runners,
shared closure/routing and test namespaces are in [Document 04](04-verification.md), and exact test paths are in
[router §7](01-authority-router.md#7-test-manifest). Exact native rows are:

| Tests | Required proof |
| --- | --- |
| `H-NL` | One process load attempt; clean pre-JNI unavailability; sticky identical-cause `LoadOome`; post-load/bootstrap poison; concurrent/late observation; weak-capability managed outcomes; no ABI input. |
| `H-RC`, `H-OS` | Legal carrier/health combinations; reuse and separate free/native-allocation/managed-allocation occurrences; exact deadline, rejection, stale, terminal, nonreturn and late-return arbitration; atomic safe disable and no same-frame retry. |
| `N-JPEG` | Eligible API 30+ production bridge black-box path; real carrier/guard/compressor/writer/adoption/decode; exact registered allocate/free; block/range rejection; writer status/precedence; production adoption OOM/throw/local-reference cleanup/residue; test-DSO runtime and C++ containment/fault/barrier rows. |
| `N-PKG` | Installed own-DSO load/bootstrap structural proof, connected-ABI production structure, production/test DSO separation, and no runtime ABI query. |
| `P-PKG` | Four production ABIs, exact exports/registered descriptors/keep rules, hidden shared runtime object, weak guard/symbol, direct `jnigraphics` dependency, exception containment source structure, configuration argument, DSO isolation and 16-KiB alignment. |
| `A-FJ`, `A-CL` | Both carrier leases through the Framework transfer seam; native/managed retirement semantics; exact nonreturn residue; independent-root and cross-Session progress. |

The production-bridge managed controls are exact:

| Controlled fact | Required evidence |
| --- | --- |
| adoption allocator throws its exact OOM | same throwable reaches Kotlin; five block fields and residue agree; `ResourceExhausted` outranks compressor result; no disable/fallback/retry/publication |
| adoption throws identity-bearing non-OOM | identical throwable reaches Kotlin; `InternalFailure` outranks OOM/result; only proven-unborrowed segments free |
| either throw leaves a pending Java exception | native performs only exception-safe evidence/local-reference cleanup; Kotlin receives and clears the original exception; a following registered call succeeds |
| zero-token non-direct/wrong-capacity/mismatched owner or result block; non-direct/short pixels; invalid count/capacity | rejection precedes compressor/adopter, preserves owners, and creates no result; an adjacent exact control succeeds; tests neither forge a nonzero token nor infer `ByteBuffer.order()` through JNI |

The test DSO exposes only this typed test inventory; each `native*` entry is owned by
`NTCPP:screen_capture_engine_native_test_jni.cpp` and accesses no production state:

| `AI:NativeTestHarness.kt` / native entry | Typed input | Receipt |
| --- | --- | --- |
| `bootstrapLocal` / `nativeBootstrapLocal` | `BootstrapBehavior`: `Success`, `BadAlloc`, `TypedUnexpected`, or `Unknown` | `BootstrapReceipt(status, bridgePublished, poisonPublished, ownerPublished)` |
| `compressLocal` / `nativeCompressLocal` | exact blocks/range/descriptor; test-local compressor; typed `WriterBehavior`, `AdopterBehavior`, and `CallbackThreadMode` (`Caller` or one joined helper) | `CompressReceipt(compressorResult, writerStatus, totalBytes, adoptedBytes, remainingBytes, remainingSegments, writerTokenPresent, callbackThreadMode, writerReceipts, adoptionReceipts, segmentReleaseReceipts)`; receipt lists are ordered and typed |
| `armCompressorReturnBarrier`, `armWriterCallbackBarrier`, `armAdopterCallBarrier` and matching native entries | exact current fixture and named boundary | `BarrierArmReceipt(armed)` |
| matching `await*Barrier` entries | already armed named barrier | `BarrierEntryReceipt(entered)` |
| matching `release*Barrier` entries | already armed named barrier | `BarrierReleaseReceipt(released)`; the worker supplies the separate eventual return |
| `releaseLocalWriterResidue` / `nativeReleaseLocalWriterResidue` | exact returned writer-owner block | `ResidueReceipt(status, tokenCleared, releasedBytes, releasedSegments)` |

`WriterBehavior` names only reachable nonthrowing callback outcomes: normal; null-nonempty, invalid-state, and
lock/link/status-publication InternalFailure; null-malloc and valid segment/cumulative nonrepresentability or
overflow OOM. `AdopterBehavior` names success, managed OOM/non-OOM failure, and test-local `std::bad_alloc`,
typed-unexpected, or unknown throw. Ordered behavior lists express monotone writer transitions without a numeric
scenario axis. The helper thread joins before `compressLocal` returns unless a named barrier is held. Holding uses
only those barriers; `finally` releases each and awaits the same eventual receipt. Production-unreachable
defensive writer exceptions remain source-inspection obligations.

Native writer tests cover zero and nonempty callbacks, ordered segments, every status transition, unknown low/high
wire values, result-block initialization, success-gated adoption, every non-success compressor result after
partial callbacks, allocation/copy/adoption faults, pending-exception identity, residue release, timeout/late
health, and cleanup in `finally`. The production DSO remains black-box; deterministic compressor/writer/adopter
faults use only test-local services around the unchanged runtime object. Defensive unreachable writer exceptions
remain static source evidence rather than fabricated production runtime cases.

For every adoption, the ordered `CompressReceipt` lists and five result words must agree with the exact
`N + M = J` pre-copy ledger, the `N + M + s = J + s <= J + Smax` transient ledger, and the post-free transfer of
`s` from native to managed ownership. A one-segment fixture reaches transient `2J`; complete success ends at
`N = 0`, `M = J`, zero remaining segments/bytes, cleared writer token, and one managed payload of exact `J`.
Failure rows assert the same prefix ledger and retain only the residue named by their ordered receipts.

Representative Native output uses the shared JPEG vector and tolerances owned by `TEST-VECTOR-JPEG-01` in
[04-verification.md](04-verification.md#test-vector-jpeg-01--shared-jpeg-64-x-48-oracle); it verifies
decodability, exact dimensions/orientation, landmarks, channel/grayscale relations, and absence of catastrophic
corruption, never byte or exact lossy-pixel equality.
An eligible API 30+ connected target is required to close real Direct-to-Native execution; otherwise that row is
reported unexecuted rather than replaced by host or package evidence.

## Official implementation references

- [Android bitmap compression](https://developer.android.com/ndk/reference/group/bitmap)
- [JNI tips](https://developer.android.com/ndk/guides/jni-tips)
- [JNI direct-buffer support](https://docs.oracle.com/en/java/javase/17/docs/specs/jni/functions.html#nio-support)
- [Android ABIs](https://developer.android.com/ndk/guides/abis)
- [Using newer NDK APIs](https://developer.android.com/ndk/guides/using-newer-apis)
- [Android 16-KiB page-size compatibility](https://developer.android.com/guide/practices/page-sizes)
- [`CoroutineDispatcher.limitedParallelism`](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/-coroutine-dispatcher/limited-parallelism.html)
