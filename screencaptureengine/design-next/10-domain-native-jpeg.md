# Screen Capture Engine — Native JPEG Domain Contract

This file solely owns the Session JPEG runtime and carriers, own-DSO loader/bootstrap, weak compressor
capability, JNI and JNI-free native boundary, Native encode evidence, call-scoped writer, native cleanup, and
native build/package contract. Framework Bitmap behavior belongs to
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
- [NJPEG-020](#njpeg-020--session-runtime-jpeg-endpoint-and-legal-products)
- [NJPEG-030](#njpeg-030--carrier-ownership-and-replacement)
- [NJPEG-040](#njpeg-040--own-dso-loader-and-bootstrap)
- [NJPEG-050](#njpeg-050--registration-and-frozen-jni-surface)
- [NJPEG-060](#njpeg-060--weak-compressor-capability-and-non-ownership)
- [NJPEG-070](#njpeg-070--preparation-descriptor-and-entered-interval)
- [NJPEG-080](#njpeg-080--writer-capsule-result-block-and-adoption)
- [NJPEG-090](#njpeg-090--native-encode-result-and-fallback)
- [NJPEG-100](#njpeg-100--cleanup-and-late-return)
- [NJPEG-CONST-001](#njpeg-const-001--jpeg-entered-operation-interval)
- [NJPEG-WIRE-001](#njpeg-wire-001--fixed-native-result-codes)
- [NJPEG-PKG-001](#njpeg-pkg-001--native-build-and-artifacts)
- [NJPEG-110](#njpeg-110--safety-boundaries)
- [NJPEG-120](#njpeg-120--executable-obligations)

## NJPEG-001 — Files, binary identity, and authority

Native JPEG is one Session mode/health/carrier authority with private preparation, encode, carrier lifecycle, and
cleanup mechanics. Process loader/JNI state owns no Session decision. Exact production anchors are
`JPG:NativeJpegProcess.kt`, `CPP:screen_capture_engine_jni.cpp`, `CPP:native_jpeg_runtime.h`,
`CPP:native_jpeg_runtime.cpp`, `CPP:CMakeLists.txt`, `MOD:build.gradle.kts`, `MOD:src/main/AndroidManifest.xml`,
and `MOD:consumer-rules.pro`.

The binary class `io.screenstream.engine.internal.jpeg.NativeJpegProcess` roots the exported bootstrap and all
four registered runtime methods. The unchanged production adoption target is
`io.screenstream.engine.internal.EncodedStorageOwner$NativeSegmentSink.adoptNativeSegment(ByteBuffer, Int)`.
Both binary boundaries use their exact names and descriptors in `NJPEG-050`.

`JpegRuntimeOwner` alone mutates Session carrier mode and Native health. The loader coordinator is process-private;
the stateless JNI facade and loader own no Session state. JPEG components act only on narrow owner commands and
cannot mutate backend/health
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
| outbound to [`STORE-060`](11-domain-encoded-storage.md#store-060--native-segment-adoption), [`STORE-080`](11-domain-encoded-storage.md#store-080--failure-terminal-and-cleanup) | synchronous native-segment calls plus exact native transfer/cleanup facts | Native preserves one-segment ordering and native ownership; storage alone validates, copies, mutates, commits, or publishes managed bytes. |
| outbound to [`CORE-CLEAN-1`](03-shared-runtime.md#core-clean-1--cleanup-forest-and-dependency-graph), [`CORE-CLEAN-2`](03-shared-runtime.md#core-clean-2--quarantine-nonreturn-and-late-reduction) | unresolved occurrence, worker/result cell, carrier/transaction leases, and live call-scoped writer/segments | Transfer the intact Kotlin graph; the native stack retains its call-scoped ownership, and late return reduces only that exact child. |

A raw pointer, direct-buffer address, compressor function, ABI string, Boolean capability, or result
code is never ownership or currentness authority by itself.

## NJPEG-020 — Session runtime, JPEG endpoint, and legal products

After accepted start, each Session constructs one distinct prestarted JPEG `ThreadPoolExecutor` under
[CORE-EXEC-1](03-shared-runtime.md#core-exec-1--private-execute-endpoints). It serializes Native preparation,
Framework/native encode, Framework creation/recycle, native carrier free/allocation, and managed-direct
replacement allocation on one Session-owned thread. These operation kinds share the endpoint but never share an
occurrence or admit more than one submitted-not-fully-settled ticket.

Each Runnable carries its precreated submission, entry, return, deadline, owner cells, and exact lane ticket.
Every outward `execute` throw poisons the endpoint; direct fatal identity follows `CORE-FATAL-1`. Terminal closes
new admission and settles or transfers exact occurrences, requests one orderly shutdown after the permitted
suffix, and awaits only the real `terminated()` receipt. A nonreturn roots the occurrence, endpoint/thread,
ticket, and real owners/leases.

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

`NativeJpegProcess` contains one process-private synchronized coordinator that permits exactly one direct
`System.loadLibrary("screencaptureengine")` attempt. Its state tag, first-cause slot, facade-publication slot, and
every preparation result holder exist before load entry. Static initialization and `JNI_OnLoad` create no
carrier, sink, Session resource, worker, owner, global reference, or registration side effect.

`JNI_OnLoad` is `noexcept` and version-only: it returns `JNI_VERSION_1_6`. After normal load return,
the private `NativeJpegProcess` receiver invokes exported `nativeBootstrap(): Int` once. Bootstrap obtains the
receiver class with `GetObjectClass`, installs one fixed `RegisterNatives` table, and publishes the facade only
for zero/JNI-OK with no pending exception.

| Observation | Process result and Session disposition |
| --- | --- |
| direct exact `UnsatisfiedLinkError` from this own load attempt, or its direct `SecurityException`, while the call has not returned normally, Java bootstrap is unentered, the facade is unpublished, and zero native owner is observable | clean unavailability; preparation selects `FrameworkOnManagedCarrier` and `Disabled` |
| first `OutOfMemoryError` thrown directly by load | sticky `LoadOome(firstCause)` with the identical cause and absent facade; timely current preparation is `ResourceExhausted` |
| any other `Exception` thrown directly by load | sticky `InternalFailure` |
| every other direct `Error` or non-`Exception` throwable | identity-preserving fatal settlement and rethrow under `CORE-FATAL-1` |
| load returned, then bootstrap/class/registration/pending-exception/unexpected-result failure | sticky post-entry `InternalFailure` poison |
| successful load, bootstrap, registration, and facade publication | process JNI facade available; registered operations cannot revise loader state |

`LoadOome` prevents every later load, bootstrap, carrier allocation, and managed fallback until process death;
all later timely current preparations receive `ResourceExhausted` with the same first cause. A load OOM published
after its originating Session's deadline or terminal winner still governs future Sessions but cannot revise that
Session. Clean unavailability and poison likewise create no retry.

These are observable Kotlin/JVM fences, not a claim about whether loader-internal native initialization began or
`JNI_OnLoad` executed before the outward throw. Successful own-DSO load/bootstrap/registration is sufficient
structural proof that the process is executing a shipped library. Runtime selection has no executing-ABI query,
field, string, enum, `Build.SUPPORTED_ABIS`,
`Process.is64Bit()`, or comparison. Static package inspection separately proves all shipped ABIs.

After facade publication, initial `NativeMallocCarrier` allocation failure is required-storage
`ResourceExhausted`; it cannot select the managed carrier. A registered runtime-operation failure uses that
operation's frozen partition and cannot retroactively alter the successful loader state.

## NJPEG-050 — Registration and frozen JNI surface

Production exports exactly:

```text
JNI_OnLoad
Java_io_screenstream_engine_internal_jpeg_NativeJpegProcess_nativeBootstrap
```

`NativeJpegProcess` declares exactly five Kotlin external methods: the exported bootstrap plus the following
four methods in one `RegisterNatives` table:

| Kotlin method | JNI signature | Exact contract |
| --- | --- | --- |
| `nativeAllocateCarrier(byteCount: Long): ByteBuffer` | `(J)Ljava/nio/ByteBuffer;` | Check positive `jlong` to `size_t`; after `malloc`, ownership transfers only when `NewDirectByteBuffer` succeeds. Provably owned partial allocation is freed on failure. |
| `nativeFreeCarrier(pixels: ByteBuffer): Unit` | `(Ljava/nio/ByteBuffer;)V` | Kotlin requires exact view identity/range, zero leases and one-shot admission; JNI requires nonnull direct address and positive representable capacity, frees that address once, and normal return is the receipt. |
| `nativeHasWeakCompressor(): Boolean` | `()Z` | `Auto` preparation only; returns payload-free capability through the guarded helper. |
| `nativeCompress(pixels, pixelByteCount, width, height, stride, format, flags, dataspace, compressFormat, quality, sink, resultBlock): Unit` | `(Ljava/nio/ByteBuffer;JIIIIJIIILio/screenstream/engine/internal/EncodedStorageOwner$NativeSegmentSink;Ljava/nio/ByteBuffer;)V` | Uses this exact argument order and the two-word result/throwable protocol in `NJPEG-080`; no cross-return writer owner exists. |

The adoption upcall remains exactly
`io/screenstream/engine/internal/EncodedStorageOwner$NativeSegmentSink.adoptNativeSegment(Ljava/nio/ByteBuffer;I)V`.
`MOD:consumer-rules.pro` retains only the exact `NativeJpegProcess` bootstrap/registered members and the
adoption-sink binary method. Production JNI contains no fault selector or test hook.

Every JNI export/entry has an outer C++ containment boundary. No C++ exception crosses JNI. Bootstrap maps
`std::bad_alloc`, typed, and unknown exceptions to bootstrap `InternalFailure`; only a direct load OOM has the
load-time `ResourceExhausted` meaning. Runtime boundaries map `std::bad_alloc` to exact OOM only where their
frozen partition admits allocation OOM; other exceptions are `InternalFailure`. An already-pending Java
exception remains pending while only exception-safe evidence and local-reference cleanup proceed.

## NJPEG-060 — Weak compressor capability and non-ownership

The native module links system `jnigraphics` directly and consumes `ANDROID_WEAK_API_DEFS=ON` under the canonical
NDK in `NJPEG-PKG-001`; unguarded-availability diagnostics remain errors. The dependency itself is not weak, so
loading
the engine DSO may map `libjnigraphics.so` on API 24–29 and under `FrameworkOnly`. That opaque incremental cost
is not a runtime selector or predictive memory input.

`FrameworkOnly` loads/selects the own DSO facade and carrier but never evaluates or invokes the platform compressor.
Own-DSO/carrier preparation is not a Native compressor attempt.
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
platform handle, writer owner, lease, close obligation, or receipt for the compressor.

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

Kotlin preallocates one exact-capacity `16`-byte direct result block, applies `ByteOrder.nativeOrder()`, and writes
signed `-1L` at offsets `0` and `8` before JNI entry. Native first validates only the exact result-block direct
address and `16`-byte capacity, establishes an allocation-free nonthrowing finalization path, and immediately
arms the result channel. It then validates and caches every other required JNI lookup, pixel/sink/direct-buffer
address, range, count, narrowing, descriptor, and preflight fact. Only after all preflight succeeds may
call-scoped writer ownership exist or an outward call begin.

An invalid or missing result address/capacity leaves the channel unarmed: native creates no call-scoped writer ownership and performs
no outward work. Kotlin treats normal return with either word still `Pending` as malformed `InternalFailure`; a
pending `Exception` becomes exact-cause `InternalFailure`; every other pending `Throwable`, including
`OutOfMemoryError`, is rethrown identically. After arming, every returning path writes a complete pair: produced
count first, status last. Status-last is the semantic completion marker, not a memory fence. The same worker reads
both words into its precreated managed return cell before the existing settlement-gate publication; the
controller never reads the direct result block.

Any post-arm preflight failure creates no call-scoped writer ownership and invokes neither compressor nor adopter. Native writes
`nativeProducedByteCount = 0` followed by `InternalFailure`. A post-arm lookup throwable remains pending with that
status and follows the exhaustive `InternalFailure` throwable partition in `NJPEG-090`; it is never
`JavaThrowable`, which remains exclusive to the two adoption boundaries.

One call-scoped writer and all native segments belong exclusively to the single `nativeCompress` stack frame.
No native owner or secondary cleanup handle crosses a returning JNI boundary.

The native-only `AndroidBitmap_CompressWriteFunc` is `noexcept`, performs no JNI, and is safe without assuming
callback thread identity or serialization. It converts every C++ failure into allocation-free sticky evidence;
no C++ exception escapes.

| Callback observation | Result |
| --- | --- |
| `size == 0` in valid state | true; nullable data is accepted; no allocation, copy, ownership, or length change |
| valid nonempty data/range/state and successful exact malloc/copy/link | true; append one exact owned segment |
| allocation failure or otherwise-valid segment/cumulative length outside managed `Int` representation | sticky typed native OOM; false |
| null data for nonzero size, invalid state, ownership/publication failure, or unexpected internal fault | sticky `InternalFailure`; false |

Failure evidence is monotone, and `InternalFailure` is absorbing. After native OOM, later
callbacks perform only nonthrowing contract/state checks so malformed evidence may upgrade it, but no bytes,
length, allocation, copy, or link changes. After InternalFailure they return false immediately.

Finalization maps clean compressor success plus complete adoption to `NativeTransferComplete`, clean
`ANDROID_BITMAP_RESULT_ALLOCATION_FAILED` with no adoption to `SafeCompressorAllocationFailure`, permitted
pre-adoption native allocation/representation failure to `NativeOutOfMemory`, and every other compressor,
callback, contract, or cleanup failure to `InternalFailure` unless an adoption-boundary pending throwable
requires `JavaThrowable`.

Runtime freezes compressor and callback evidence before transfer. Only clean
`ANDROID_BITMAP_RESULT_SUCCESS` invokes the supplied adopter. Each native segment is transferred sequentially:
production creates one temporary direct local view, calls
`NativeSegmentSink.adoptNativeSegment(ByteBuffer, Int)` synchronously, and waits for its return. That return
proves the view unborrowed; native deletes the local reference, transfers that segment out of native ownership,
and frees it exactly once. Final cleanup frees only segments that remain owned. Exact
managed validation, copy, transaction
mutation, and view non-retention are solely [`STORE-060`](11-domain-encoded-storage.md#store-060--native-segment-adoption).
Non-success compressor results invoke no adopter and free every provably owned native segment before
classification.

Only `NewDirectByteBuffer` and synchronous `NativeSegmentSink.adoptNativeSegment` are adoption JNI boundaries.
Their Java throwable stays pending while native performs fixed exception-safe bookkeeping, deletes the temporary
local reference, and releases all provably owned segments. Kotlin catches the identical object.

Explicit close occurs only after the compressor returned or was never entered and no callback remains active.
It is allocation-free, idempotent, `noexcept`, releases every provably owned segment, verifies closure, writes
the result pair, and only then returns through JNI. Lexical cleanup is the safety net; no C++ exception crosses
JNI.

If `nativeCompress` does not return, there is no cleanup receipt: the writer ownership and segments remain on the live
native stack, and the exact Kotlin occurrence, worker, result block, transaction, carrier, and leases remain
quarantined. A late return performs the normal native close and lets Kotlin publish cleanup-only evidence under
the original settlement identity.

The result block contains exactly two signed 64-bit native-order fields. Native accesses each field with
field-wise `memcpy`; it never aliases the block as a struct or `int64_t*` and assumes no alignment:

```text
offset 0: nativeStatus
offset 8: nativeProducedByteCount
```

Let `P` be `nativeProducedByteCount` and `M` be `NativeTransaction.byteCount`, the sole authority for the prefix
already adopted into managed storage. `P` is the native-produced prefix and denotes a complete JPEG only for
`NativeTransferComplete`. The exact status codes are `NJPEG-WIRE-001`; every final pair obeys:

| Status | Required evidence |
| --- | --- |
| `NativeTransferComplete` | `P > 0`, `M == P`, no throwable, adoption and native cleanup complete. This proves neither Storage commit/currentness nor publication. |
| `SafeCompressorAllocationFailure` | `P` in `0..Int.MAX_VALUE`, `M == 0`, no throwable, native prefix freed. |
| `NativeOutOfMemory` | `P` in `0..Int.MAX_VALUE`, strictly `M == 0`, no throwable, native prefix freed. All allowed native allocation and checked-representation failures occur before adoption. |
| `InternalFailure` | `P` in `0..Int.MAX_VALUE`, `0 <= M <= P`; internal, malformed, or cleanup failure. A pending throwable is permitted only for an exact pre-adoption or malformed path. |
| `JavaThrowable` | `P > 0`, `0 <= M <= P`, exact pending throwable from one adoption JNI boundary, and verified native cleanup/closed state. |

`Pending` is Kotlin initialization only and is never a native final value. Unknown status, invalid count, normal
return with `Pending`, or an impossible status/throwable/`M`/`P` pairing is malformed `InternalFailure` while
preserving fatal throwable identity. There is no third cleanup or adopted-count word.

For a successful compressor result, let `J = P`, `N` be the bytes still owned in native segments, `s` the
current segment size, and `Smax` the largest actual segment in this call. Before each adoption, `N + M = J`.
While copying the current segment, `N + M + s = J + s <= J + Smax`; normal sink return followed by native free
moves `s` from `N` to `M` and removes that duplicate. A one-segment result has transient `2J`; complete transfer
leaves `N = 0`, `M = J`, and no native cache backing. These are observed ownership equations, not admission
estimates or a runtime axis.

Before classification, production evidence binds the carrier lease/range, compressor/callback and JNI
return/throw, exact two-word result and managed count, ordered segment adoption/release, sink throwable identity,
temporary-view non-retention, and verified call-scoped closure. Nonreturn has no cleanup receipt.

## NJPEG-090 — Native encode result and fallback

The encode occurrence starts its own JPEG interval immediately before the outward `nativeCompress` entry and
ends only after allocation-free complete return-cell publication. Kotlin validates the armed fact, two-word pair,
exact pending throwable, managed `M`, native closed evidence, transaction state, and ownership before result
classification. Cleanup ambiguity or malformed evidence outranks ordinary OOM normalization.

| Valid native status | Exact disposition |
| --- | --- |
| `NativeTransferComplete` | mechanically successful encode; [`STORE-050`](11-domain-encoded-storage.md#store-050--commit-immutable-payload-and-caller-copies) alone commits immutable bytes, subject to controller currentness |
| `SafeCompressorAllocationFailure` | abort bytes, record the materialized attempt once as `byFailure`, and monotonically disable Native for later work |
| `NativeOutOfMemory` | terminal `ResourceExhausted` with nullable cause; no Java OOM is fabricated |
| `InternalFailure` without a pending throwable | terminal `InternalFailure` |
| `JavaThrowable` or `InternalFailure` with a permitted pending throwable | apply the exact partition below |

All native allocation and checked-representation failures that qualify as `NativeOutOfMemory` occur before
adoption and therefore require `M == 0`. An unexpected `std::bad_alloc` after adoption begins is
`InternalFailure` with `0 <= M <= P`; RAII cleanup remains mandatory.

The pending-throwable partition is exact:

- valid `JavaThrowable` plus the identical `OutOfMemoryError` becomes `ResourceExhausted` retaining that same cause;
- valid `JavaThrowable` plus an `Exception` becomes exact-cause `InternalFailure`;
- valid `JavaThrowable` plus every other `Throwable` performs fixed allocation-free bookkeeping and rethrows the
  identical object;
- `InternalFailure`/malformed evidence plus an `Exception` becomes exact-cause `InternalFailure`;
- `InternalFailure`/malformed evidence plus every other `Throwable`, including `OutOfMemoryError`, performs fixed
  bookkeeping and rethrows the identical object.

`JavaThrowable` is valid only for `NewDirectByteBuffer` or synchronous
`NativeSegmentSink.adoptNativeSegment`. A pre-adoption `OutOfMemoryError` is not normalized to
`ResourceExhausted`. No branch creates an artificial `OutOfMemoryError`.

A safe disable atomically belongs to the still-current Native-health occurrence. A current-key attempt drops the
switching frame; a key-stale/current-health attempt also records `byFailure`, publishes no stale output or
lifecycle failure, and disables Native for the latest reconciliation. Framework allocation/work begins only for
a later frame; there is no same-frame retry. Results at `T >= D`, after expiry, after health retirement, or after
terminal transfer are cleanup-only and cannot disable Native or change lifecycle/counters.

The same call never publishes partial bytes. Unsafe nonreturn or uncertain carrier, transaction, writer, segment,
or adoption ownership is terminal `InternalFailure` and roots the exact occurrence. Native fallback never changes
Target or Direct-readback health.

Native publishes only typed diagnostic-site facts. `CTRL-300` selects source/label/cause and timeout mapping;
[`DEL-OBS-020`](12-domain-delivery-observation.md#del-obs-020--diagnostic-construction-and-emission) only constructs
and attempts emission. Neither step gives Native control authority.

## NJPEG-100 — Cleanup and late return

Native cleanup follows [CORE-CLEAN-1](03-shared-runtime.md#core-clean-1--cleanup-forest-and-dependency-graph)
without retries:

| Obligation | Real completion and blocked dependents |
| --- | --- |
| native carrier free | one normal `nativeFreeCarrier` return; throw/nonreturn retains carrier, view, occurrence and blocks replacement |
| returned stale/late carrier | its own one-shot free; no install or lifecycle result |
| encode/adoption return | mechanical JNI/adopter return plus verified call-scoped ownership close, exact segment release, and transaction abort/retirement facts |
| encode/adoption nonreturn | no native cleanup receipt; live native stack retains writer/segment ownership while the intact Kotlin occurrence, worker, result block, transaction, carrier, and leases remain rooted |
| managed carrier | last engine-reference drop after all uses; no physical receipt |

Terminal before an unentered optional encode cancels without JNI. Terminal before an admitted mandatory free
converts the same occurrence to no-watchdog cleanup; terminal after entry retires its deadline and transfers the
intact writable occurrence. A carrier free created by terminal retirement starts as one no-watchdog occurrence.
Late return can reduce only the matching cleanup child and cannot publish bytes, install a carrier, retry,
fallback, change Native health, counters, State, or terminal winner. Independent roots and another Session
continue. A nonreturn on the Session JPEG endpoint/thread roots that exact endpoint dependency; a non-owned
vendor/global runtime has no finite progress guarantee and never acquires an engine shutdown or termination
receipt.

## NJPEG-CONST-001 — JPEG entered-operation interval

`jpegEnteredOperationSafetyNanos = 15_000_000_000L` is the sole JPEG private duration. It applies independently
to complete Native preparation, Framework resource creation, Framework/Native encode, preterminal Bitmap recycle,
incompatible native-carrier free, and each native/managed replacement allocation. Terminal-origin or converted
recycle/free cleanup has no watchdog.

For every finite occurrence, [CORE-SET-3](03-shared-runtime.md#core-set-3--finite-deadline-arbitration) owns checked
deadline arithmetic, `T < D` timeliness, equality-to-expiry, terminal transfer, and late facts. Consecutive
stages receive independent intervals. This constant is implementation policy, not a public latency guarantee.

## NJPEG-WIRE-001 — Fixed native-result codes

| `nativeStatus` | Signed Kotlin/native value |
| --- | ---: |
| `Pending` | `-1` |
| `NativeTransferComplete` | `0` |
| `SafeCompressorAllocationFailure` | `1` |
| `NativeOutOfMemory` | `2` |
| `InternalFailure` | `3` |
| `JavaThrowable` | `4` |

`Pending` is Kotlin initialization only. Kotlin decodes exact signed `Long` equality, never enum ordinal or
range. Every other 64-bit value is malformed `InternalFailure` and authorizes no bytes, fallback, or
Native-health change.

## NJPEG-PKG-001 — Native build and artifacts

The module namespace is the router-owned `io.screenstream.engine`, and its canonical NDK is
`29.0.14206865` for every production and nativeTest native configuration.

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

## NJPEG-110 — Safety boundaries

Native selection uses the frozen loader/capability result, one Session health cell, a legal carrier product, and
complete writer/JNI/adoption/ownership evidence. The compressor pointer and writer remain call-scoped; managed
publication occurs only through [`STORE-050`](11-domain-encoded-storage.md#store-050--commit-immutable-payload-and-caller-copies).
The fixed result block is decoded by exact status/count truth table after native close evidence.

The runtime performs no ABI/device selection, loader retry, stored compressor ownership, same-frame Framework
retry, JNI from the writer callback, cross-return writer continuation, or unknown-status acceptance. Timeout and
late return are not release or active authority; only real free/close/endpoint-termination evidence retires their
matching obligations.

## NJPEG-120 — Executable obligations

Closed packet membership is in [router §5](01-authority-router.md#5-closed-implementation-packets); runners,
shared closure/routing and test namespaces are in [Document 04](04-verification.md), and canonical test source sets are in
[router §7](01-authority-router.md#7-test-manifest). Exact native rows are:

Every Native diagnostic row exposes only the typed site/cause fact and proves routing to `CTRL-300`; it neither
selects public vocabulary nor bypasses `DEL-OBS-020` construction/emission.

| Tests | Required proof |
| --- | --- |
| `H-NL` | One process load attempt; exact observable clean-unavailability fences for direct own-load `UnsatisfiedLinkError` and `SecurityException` without asserting whether `JNI_OnLoad` ran; sticky identical-cause `LoadOome`; every other `Exception` -> sticky InternalFailure; every other direct `Error`/custom Throwable -> identical fatal rethrow; post-load/bootstrap poison; concurrent/late observation; weak-capability managed outcomes; no ABI input. |
| `H-RC`, `H-OS` | Legal carrier/health combinations; reuse and separate free/native-allocation/managed-allocation occurrences; exact deadline, rejection, stale, terminal, nonreturn and late-return arbitration; atomic safe disable and no same-frame retry. |
| `N-JPEG` | Eligible API 30+ production-facade black-box path; real carrier/guard/compressor/adoption/decode; exact registered allocate/free; result arming and block/range rejection; fixed-offset pair, every status/truth-table row, throwable partition, RAII close, production adoption faults, late return and nonreturn; test-DSO containment/fault/barrier rows. |
| `N-PKG` | Installed own-DSO load/bootstrap structural proof, exact process-facade receiver and connected-ABI production structure, production/test DSO separation, and no runtime ABI query. |
| `P-PKG` | Namespace and NDK bindings; four production ABIs; exact two exports, five Kotlin external methods, four registered names/descriptors and sink upcall/keep rules; hidden shared runtime object, weak guard/symbol, direct `jnigraphics` dependency, containment source structure, configuration argument, DSO isolation and 16-KiB alignment. |
| `A-FJ`, `A-CL` | Both carrier leases through the Framework transfer seam; native/managed retirement semantics; exact nonreturn residue; independent-root and cross-Session progress. |

The production-facade managed controls are exact:

| Controlled fact | Required evidence |
| --- | --- |
| adoption allocator throws its exact OOM | `JavaThrowable`, identical throwable, valid `P/M`, verified close; Kotlin produces `ResourceExhausted` with that cause; no disable/fallback/retry/publication |
| adoption throws identity-bearing `Exception` | `JavaThrowable`, identical throwable, valid `P/M`, verified close; exact-cause `InternalFailure`; only proven-unborrowed segments free |
| adoption throws any other identity-bearing `Throwable` | `JavaThrowable`, fixed bookkeeping and close complete, then Kotlin rethrows the identical object |
| a pre-adoption or malformed path has a pending throwable | `Exception` becomes exact-cause `InternalFailure`; every other throwable including OOME is identically rethrown after fixed bookkeeping; a following registered call succeeds when process state remains valid |
| invalid, non-direct, missing-address, or non-`16`-byte result block | result channel remains unarmed; compressor, adopter, and call-scoped writer ownership are absent; result memory remains unchanged from `Pending` initialization; normal-return, `Exception`, and every-other-`Throwable` partitions are exact; owners are preserved; an adjacent exact control succeeds |
| armed channel followed by lookup, pixels/sink/direct-buffer, range/count, narrowing, descriptor, or other preflight failure | compressor, adopter, and call-scoped writer ownership are absent; native writes `P = 0` then `InternalFailure`; any lookup throwable remains identical and pending for the exhaustive `InternalFailure` partition; `JavaThrowable` is impossible; an adjacent exact control succeeds |

The test DSO anchor `NTCPP:screen_capture_engine_native_test_jni.cpp` accesses no production state. It provides
typed controls sufficient to select bootstrap, compressor, writer, adopter, and callback-thread outcomes; pause
the compressor-return, writer-callback, and adopter-call boundaries; and return ordered ownership/status/byte
receipts. The private Kotlin/native entry names, fixture decomposition, and receipt representation are not
architecture. Held barriers are always released and joined in cleanup. Production-unreachable defensive writer
exceptions remain source-inspection obligations.

Native writer tests cover zero and nonempty callbacks, ordered segments, both-word `Pending` initialization,
arming, exact 16-byte capacity, offsets `0`/`8`, native order, field-wise `memcpy`, count-first/status-last writes,
every final status and truth-table relation, unknown values, success-gated adoption, every non-success compressor
result after partial callbacks, allocation/copy/adoption faults, pending-exception identity/partition, explicit
RAII close, timeout/late return, nonreturn rooting, and cleanup in `finally`. The production DSO remains black-box;
deterministic compressor/writer/adopter
faults use only test-local services around the unchanged runtime object. Defensive unreachable writer exceptions
remain static source evidence rather than fabricated production runtime cases.

For every adoption, the ordered typed receipts and two result words must agree with the exact
`N + M = J` pre-copy ledger, the `N + M + s = J + s <= J + Smax` transient ledger, and the post-free transfer of
`s` from native to managed ownership. A one-segment fixture reaches transient `2J`; complete success ends at
`P = M = J`, no native segment, closed native call ownership, and one managed payload of exact `J`. Every returning failure
asserts closed native ownership; a nonreturn reports no close receipt and retains the exact live call graph.

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
- [Java 17 `ThreadPoolExecutor`](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/concurrent/ThreadPoolExecutor.html)
