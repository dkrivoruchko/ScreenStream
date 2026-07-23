# Screen Capture Engine — Native, JNI, and Package Contract

## Scope and authority

This document is the sole authority for native raw carriers, the process JNI facade and loader, weak platform
JPEG capability, native compression and writer ownership, the JNI adoption protocol, the fixed native result
wire format, native cleanup, and native build/package facts.

Authority remains divided as follows:

- [Product Contract](01-product-contract.md) owns caller-visible JPEG policy, failures, drops, Stats, and
  diagnostics.
- [Internal Architecture](02-internal-architecture.md) owns `EncoderRuntime`'s carrier/backend/health
  assignment, the serial JPEG role, `EncoderCapsule`, Control currentness and fallback decisions, terminal
  transfer, and process-lifetime residue.
- [Capture and Rendering](04-capture-rendering.md) owns canonical RGBA production and the carrier lease passed
  to JPEG.
- [Framework Encoding and Encoded Storage](05-framework-encoding-storage.md) owns Framework encoding, managed
  transaction mutation and commit, immutable payloads, `FrameStore`, and caller copies.
- [Verification](08-verification.md) owns every test method, source-set responsibility, task, matrix, oracle,
  and package-inspection procedure.

The native leaf returns mechanical facts and settles only its own resources. It does not choose Session
lifecycle, currentness, publication, cache eligibility, counters, terminal priority, public outcomes, or
Delivery behavior. Private Kotlin/C++ decomposition may vary except for the exact binary, source, wire, and
package anchors frozen below.

## Process facade and source anchors

The process facade binary class is
`io.screenstream.engine.internal.jpeg.NativeJpegProcess`. It owns one process-private loader coordinator, the
exported bootstrap receiver, and the four registered runtime calls. It owns no Session state. The architecture-
assigned `EncoderRuntime` responsibility remains the sole Session owner of the selected carrier/backend product
and the monotone Native health cell.

The production anchors are:

| Concern | Canonical repository-relative anchor |
| --- | --- |
| process facade | `screencaptureengine/src/main/kotlin/io/screenstream/engine/internal/jpeg/NativeJpegProcess.kt` |
| result protocol | `screencaptureengine/src/main/kotlin/io/screenstream/engine/internal/jpeg/NativeResultProtocol.kt` |
| production JNI glue | `screencaptureengine/src/main/cpp/screen_capture_engine_jni.cpp` |
| JNI-free runtime declaration | `screencaptureengine/src/main/cpp/native_jpeg_runtime.h` |
| JNI-free runtime implementation | `screencaptureengine/src/main/cpp/native_jpeg_runtime.cpp` |
| native build | `screencaptureengine/src/main/cpp/CMakeLists.txt` |
| export map | `screencaptureengine/src/main/cpp/screencaptureengine.map.txt` |
| module build | `screencaptureengine/build.gradle.kts` |
| shrinker boundary | `screencaptureengine/consumer-rules.pro` |
| Android manifest | `screencaptureengine/src/main/AndroidManifest.xml` |

The managed adoption target is the binary method defined in the JNI table below. Native runtime state contains
no Session selector, persistent compressor handle, `JNIEnv`, Java global reference, or cross-DSO mutable owner.

## Legal carrier/backend products and Native health

The Native health values are payload-free and exactly `Enabled` and `Disabled`. Under the architecture's
`EncoderRuntime` assignment, only these three stable products are legal:

```text
NativeEnabled(NativeMallocCarrier)
FrameworkOnNativeCarrier(NativeMallocCarrier)
FrameworkOnManagedCarrier(ManagedDirectCarrier)
```

Native compression accepts only `NativeMallocCarrier`; Native enabled with `ManagedDirectCarrier` is
unrepresentable. Framework selection does not replace a compatible healthy native carrier. Clean own-DSO
unavailability may select the managed carrier. `FrameworkOnly`, weak-compressor ineligibility, or a safely
disabled Native axis retains an already healthy compatible native carrier. Native health is independent of
Target and rendering health.

For positive output dimensions `(Ow, Oh)`, the carrier byte count is checked in this order:

```text
rowSize = checked(4 * Ow)
B = checked(rowSize * Oh)
```

`B` must be positive and `Int`-representable before allocation, direct-view creation, or JNI narrowing. A
stable carrier is reusable only while it remains writable, direct, exact-capacity `B`, compatible, and healthy.

### Native carrier ownership

`NativeMallocCarrier` immediately owns one positive checked `malloc` allocation, its pointer, the exact
referential direct `ByteBuffer` view over `[0, B)`, every active lease, and one one-shot free authority. The
managed view does not own or free the allocation. Only a normal return from `nativeFreeCarrier` proves that the
native allocation was freed; throw, ambiguous dispatch, or nonreturn retains the pointer, view, leases, and free
authority and forbids replacement.

Replacement is strictly sequential:

```text
settle every carrier lease and entered use
-> invoke nativeFreeCarrier once and obtain normal return
-> recheck current, nonterminal, and no extant replacement
-> invoke nativeAllocateCarrier(B) once for a distinct replacement
```

Free and allocation have separate complete entered-operation windows. A returned allocation is owned
immediately. Timely current valid success may install it; a current allocation OOM is coherent required
exhaustion. Rejection, unexpected throw, malformed evidence, expiry, nonreturn, or uncertain ownership is an
internal mechanical failure. A stale, late, or terminal returned carrier is never installed and is freed by its
own one-shot call. Safe stale OOM changes neither lifecycle nor health. Cutoff before entry invokes no JNI.

`ManagedDirectCarrier` owns one checked direct writable `ByteBuffer` and its leases. After every use settles,
retirement drops the final engine reference. Reference drop has no physical-free or GC completion meaning. A
managed replacement is separately admitted only after the old carrier is detached and current/nonterminal/no-
replacement state is rechecked. It invokes `ByteBuffer.allocateDirect(B)` once, owns any returned buffer
immediately, and uses the same current/stale/late classification without a preparation rerun, JNI free, or
inferred reclamation.

## Own-DSO load, bootstrap, and facade publication

`NativeJpegProcess` serializes all callers through one process-private synchronized coordinator and permits exactly one
direct `System.loadLibrary("screencaptureengine")` attempt. Its state tag, first-cause slot, facade-publication
slot, and result storage exist before load entry. Static initialization and `JNI_OnLoad` create no carrier,
Session resource, sink, worker, native writer, Java global reference, or registration side effect.

`JNI_OnLoad` is `noexcept` and version-only; it returns `JNI_VERSION_1_6`. After the load call returns normally,
the private receiver invokes exported `nativeBootstrap(): Int` exactly once. Bootstrap obtains the receiver
class with `GetObjectClass`, installs the fixed `RegisterNatives` table in the order below, deletes its local
class reference, and permits facade publication only for `JNI_OK`/zero with no pending Java exception.

The loader result partition is exact:

| Direct observation | Sticky process result |
| --- | --- |
| This own load throws its exact `UnsatisfiedLinkError` or direct `SecurityException` before normal load return, before Java bootstrap entry, with the facade unpublished and no observable native owner | clean unavailability; Native health is `Disabled` and the legal product is `FrameworkOnManagedCarrier` |
| The load call directly throws its first `OutOfMemoryError` | `LoadOome(firstCause)` with the identical cause and no facade |
| The load call directly throws any other `Exception` | poisoned internal failure with that first cause |
| The load call directly throws any other `Error` or non-`Exception` `Throwable` | publish poison bookkeeping and rethrow the identical object |
| Load returned, but bootstrap, class lookup, registration, pending-exception state, or result is invalid | post-entry internal-failure poison |
| Load, bootstrap, registration, and facade publication all succeed | available process facade |

`LoadOome` prevents every later load, bootstrap, carrier allocation, and managed fallback until process death;
later preparations expose coherent required exhaustion with the same first cause. Clean unavailability, poison,
and success likewise never retry. A result published after the requesting Session became stale or terminal
still governs future Sessions but cannot revise the earlier Session.

The clean-unavailability fence describes only observable Java/JVM state. It does not assert whether loader-
internal native initialization or `JNI_OnLoad` began before the outward throw. Runtime selection has no ABI
query, ABI string/enum, device allowlist, `Build.SUPPORTED_ABIS`, `Process.is64Bit()`, or comparison.

After successful facade publication, required initial native-carrier allocation failure cannot select the
managed carrier. A later registered-call failure follows that call's contract and never revises loader state.

## Frozen JNI surface

### Exports

The production DSO exports exactly these two ELF symbols:

```text
JNI_OnLoad
Java_io_screenstream_engine_internal_jpeg_NativeJpegProcess_nativeBootstrap
```

The bootstrap external has JVM descriptor `()I`. `NativeJpegProcess` declares exactly five Kotlin `external`
methods: that exported bootstrap plus these four entries in this exact `RegisterNatives` order:

| Registered method | JVM descriptor | Native contract |
| --- | --- | --- |
| `nativeAllocateCarrier` | `(J)Ljava/nio/ByteBuffer;` | Require a positive `jlong` representable as `size_t`. After `malloc`, ownership transfers to the returned carrier only if `NewDirectByteBuffer` succeeds; otherwise the provably owned allocation is freed. |
| `nativeFreeCarrier` | `(Ljava/nio/ByteBuffer;)V` | Managed code admits the exact view with zero leases and one-shot authority. Native requires a nonnull direct address and positive representable capacity, frees that address once, and uses normal return as the sole free evidence. |
| `nativeHasWeakCompressor` | `()Z` | Return only the guarded weak-function capability; no handle or owner crosses JNI. |
| `nativeCompress` | `(Ljava/nio/ByteBuffer;JIIIIJIIILio/screenstream/engine/internal/EncodedStorageOwner$NativeSegmentSink;Ljava/nio/ByteBuffer;)V` | Preserve the exact argument order specified by the frame descriptor and result protocol below. |

The adoption upcall is exactly:

```text
class:  io/screenstream/engine/internal/EncodedStorageOwner$NativeSegmentSink
method: adoptNativeSegment
desc:   (Ljava/nio/ByteBuffer;I)V
```

The registered compression arguments are exactly, in order:

```text
nativeCompress(
    pixels, pixelByteCount, width, height, stride, format, flags,
    dataspace, compressFormat, quality, sink, resultBlock
)
```

`consumer-rules.pro` retains only the process-facade class name and its five native members, plus the adoption-
sink class name and that binary method. Production JNI contains no test hook, fault selector, alternate
receiver, reflective discovery, or additional registered entry.

### C++ and pending-throwable containment

Every exported or registered entry has an outer `noexcept` containment boundary; no C++ exception crosses JNI.
Bootstrap converts `std::bad_alloc`, typed C++ exceptions, and unknown C++ exceptions to bootstrap
`InternalFailure` rather than load-time exhaustion. Runtime entries convert `std::bad_alloc` to native OOM only
at a boundary whose contract expressly admits native allocation OOM; every other C++ failure becomes
`InternalFailure`.

Once a Java throwable is pending, native code never clears, replaces, wraps, or describes it. Only fixed
exception-safe bookkeeping, local-reference deletion, provably safe segment release, result finalization, and
call-scoped close may proceed. Managed code observes the identical throwable object.

## Weak compressor capability

The DSO directly links `jnigraphics`; only the API-30 compressor definition is weak. Capability and every real
compression resolve the same function within the same guarded scope:

```cpp
if (__builtin_available(android 30, *)) {
    auto compressor = &AndroidBitmap_compress;
    if (compressor != nullptr) {
        // use only within this native call
    }
}
```

API 24–29 never enter that scope or invoke the compressor. `FrameworkOnly` runs own-DSO/carrier preparation and,
when available, selects `FrameworkOnNativeCarrier` with `Disabled`; it never evaluates or invokes the
compressor. Under `Auto`,
null on API 30+ during preparation is clean static Native ineligibility: retain the healthy native carrier,
expose `Disabled`, and use
the legal Framework-on-native-carrier product before a Native frame is assigned. A true capability result
exposes only `Enabled`.

Each enabled `nativeCompress` re-resolves the function. An inconsistent null is a pre-invocation internal
mechanical failure with no compressor or adopter call and no fallback decision inside JNI. The typed pointer is
passed by value to the JNI-free runtime for that call and discarded on return. It never persists in Kotlin,
Session state, global/native writable state, or another call. There is no `dlopen`, `dlsym`, `dlclose`, platform
handle, stored compressor owner, close obligation, or release signal for it.

The direct `jnigraphics` dependency may be mapped even under `FrameworkOnly` and on API 24–29. Its opaque
incremental cost is not a runtime selector or predictive memory input.

## Native frame descriptor

Each admitted Native frame uses the stable exact carrier range `[0, B)` and this descriptor:

| Descriptor part | Exact value and order |
| --- | --- |
| pixels/count | stable tight top-down opaque-RGBA carrier address; byte count `B` |
| `AndroidBitmapInfo` | fields in declaration order: `width=Ow`, `height=Oh`, `stride=4*Ow`, `format=ANDROID_BITMAP_FORMAT_RGBA_8888`, `flags=ANDROID_BITMAP_FLAGS_ALPHA_OPAQUE` with the hardware bit clear |
| remaining fields | `dataspace=ADATASPACE_SRGB`, `compressFormat=ANDROID_BITMAP_COMPRESS_FORMAT_JPEG`, requested quality, pixels, nonnull writer function, exact transaction as writer context |

Managed preflight checks positive dimensions, stride and byte count, quality in `0..100`, the exact direct pixel
capacity/range, and every signed/unsigned conversion. Width, height, and stride narrow to `uint32_t`; nonnegative
flags narrow from `jlong` to `uint32_t`; pixel bytes narrow to `size_t`; enum and quality values narrow to their
signed fields. The carrier and managed transaction remain borrowed for the whole outward call and cannot be
reused concurrently.

## Result block and arming protocol

Managed code preallocates one direct result block of exact capacity `16` bytes, applies
`ByteOrder.nativeOrder()`, and writes signed `-1L` to both fields before JNI entry. The fields are:

```text
offset 0: nativeStatus
offset 8: nativeProducedByteCount
```

Native accesses each field with field-wise `memcpy`. It never aliases the block as a C++ struct or
`int64_t*` and assumes no alignment.

Native first validates only the result block's direct address and exact capacity and establishes an allocation-
free nonthrowing finalization path. A valid block immediately arms the channel. Native then validates and caches
every other required lookup, pixel/sink/direct-buffer address, range, count, narrowing, descriptor, and
preflight fact. Only complete preflight permits writer ownership or an outward call.

An invalid, missing, nondirect, missing-address, or nonexact-capacity block remains unarmed. Native creates no
writer, invokes neither compressor nor adopter, and leaves both initialized words unchanged. On managed return,
either word still equal to `Pending` is malformed. A pending `Exception` on this unarmed path is internal
mechanical failure; every other pending `Throwable`, including `OutOfMemoryError`, is rethrown identically.

After arming, every returning path writes a complete pair: `nativeProducedByteCount` first and `nativeStatus`
last. Any post-arm preflight failure creates no writer and invokes neither compressor nor adopter; it writes
produced count zero followed by `InternalFailure`. A lookup throwable remains pending and follows the malformed/
internal throwable partition below; it cannot produce `JavaThrowable`.

Status-last is the semantic completion marker, not a memory fence. The same serial JPEG task reads both words
into its managed result before publishing its closed mechanical fact. Control never reads the direct block.
There is no third cleanup, adopted-count, arming, or closure word.

## Call-scoped writer

One writer and all of its native segments belong exclusively to one `nativeCompress` stack frame. The
`AndroidBitmap_CompressWriteFunc` is `noexcept`, makes no JNI call, and is correct without assuming callback
thread identity or callback serialization. Every C++ failure becomes allocation-free sticky writer evidence.

| Callback observation | Exact transition |
| --- | --- |
| `size == 0` in valid accepting state | Return true. Null data is allowed; no allocation, copy, segment, ownership, or length changes. |
| Valid nonempty data/range/state | Allocate an exact segment node and exact byte range with `malloc`, copy once, link once in callback order, advance the checked cumulative length, and return true. |
| Allocation failure, segment size outside managed `Int`, or cumulative length outside positive managed `Int` | Record sticky `NativeOutOfMemory` and return false. |
| Null data with nonzero size, invalid writer state, ownership/link failure, or unexpected internal fault | Record sticky `InternalFailure` and return false. |

`InternalFailure` is absorbing. After native OOM, later callbacks perform only nonthrowing state and contract
checks so malformed evidence may upgrade the fault to `InternalFailure`; they make no allocation, copy, link,
length, or byte change. After `InternalFailure`, callbacks return false immediately.

When the compressor returns, the runtime freezes compressor result, writer fault, segment order, and produced
count before any adoption. Clean `ANDROID_BITMAP_RESULT_SUCCESS` with a positive coherent prefix is the only
case that may adopt. Clean `ANDROID_BITMAP_RESULT_ALLOCATION_FAILED` with no adoption becomes
`SafeCompressorAllocationFailure`. A permitted pre-adoption native allocation/representation fault becomes
`NativeOutOfMemory`. Every other compressor, callback, contract, or cleanup fault becomes `InternalFailure`
unless an adoption-boundary Java throwable requires `JavaThrowable`.

## Sequential native-to-managed adoption

Only a clean successful compressor result invokes the supplied sink. Native processes segments in original
callback order, exactly once each:

```text
construct one temporary exact-range direct local ByteBuffer view
-> call adoptNativeSegment(view, size) synchronously
-> wait for return
-> delete the local reference
-> transfer that segment out of native ownership
-> free its bytes and node exactly once
```

A normal sink return proves that the temporary view is no longer borrowed. The managed sink's validation, one
exact managed allocation/copy, append, cumulative count mutation, and non-retention contract belong solely to
[Document 05](05-framework-encoding-storage.md#native-to-managed-adoption-boundary).

Only `NewDirectByteBuffer` and the synchronous `adoptNativeSegment` call are adoption JNI boundaries. If either
leaves a Java throwable pending, native preserves its identity while it deletes any temporary local reference,
updates only exception-safe ownership state, and frees every segment still provably native-owned. The managed
caller catches the identical object. A non-success compressor result calls no adopter and frees every provably
owned segment before final classification.

The writer closes explicitly only after the compressor returned or was never entered and no callback can remain
active. Close is allocation-free, idempotent, and `noexcept`; it releases every provably owned segment and
verifies the empty closed state. Lexical RAII cleanup is the safety net. Native writes the complete result pair
only after that close, then returns through JNI.

Therefore every recognized final status on a real JNI return asserts call-scoped native closure: no writer,
segment, or temporary local view remains owned. If closure cannot be verified, the result is internally failed;
if ownership is uncertain, managed code treats the evidence as malformed and retains the enclosing architecture
owner rather than fabricating release.

If `nativeCompress` does not return, there is no close evidence. The live native stack retains the writer and
its remaining segments, while `EncoderCapsule` retains the managed transaction, result block, carrier use, and
lease that the call may still access. Timeout, cancellation, terminal State, or reference drop cannot release or
reuse them. A real later return performs the normal close and may only settle that exact retained ownership as
allowed by Document 02; it cannot publish output or change active policy by itself.

## Fixed status values and complete truth table

The wire values are signed 64-bit values decoded by exact equality:

| `nativeStatus` | Value |
| --- | ---: |
| `Pending` | `-1` |
| `NativeTransferComplete` | `0` |
| `SafeCompressorAllocationFailure` | `1` |
| `NativeOutOfMemory` | `2` |
| `InternalFailure` | `3` |
| `JavaThrowable` | `4` |

`Pending` is managed initialization only and is never a native final value. Let `P` be
`nativeProducedByteCount` and `M` be the managed transaction's checked adopted byte count. `P` is the native-
produced prefix and denotes a complete JPEG only for `NativeTransferComplete`. Every valid final
status is observed only after actual JNI return, carrier-use return/closure, and verified call-scoped native close:

| Final status | `P` and `M` | Pending Java throwable | Additional truth |
| --- | --- | --- | --- |
| `NativeTransferComplete` | `P > 0`; `M == P` | none | Ordered adoption and native segment cleanup are complete. This is mechanical encode success only; managed commit/currentness/publication are outside this document. |
| `SafeCompressorAllocationFailure` | `P` in `0..Int.MAX_VALUE`; `M == 0` | none | No adopter call; every native prefix byte is freed. This is the sole safe optional compressor-allocation failure fact. |
| `NativeOutOfMemory` | `P` in `0..Int.MAX_VALUE`; strictly `M == 0` | none | No adopter call; every native prefix byte is freed. Every permitted native allocation/representation failure for this status occurs before adoption. |
| `InternalFailure` | `P` in `0..Int.MAX_VALUE`; `0 <= M <= P` | none, or an exact throwable only on a permitted pre-adoption/malformed path | Internal, malformed, contradictory, unexpected post-adoption allocation, or cleanup evidence; no partial bytes are authorized. |
| `JavaThrowable` | `P > 0`; `0 <= M <= P` | exactly one throwable from an adoption JNI boundary | Temporary references are deleted and every provably native-owned segment is freed before return. |

Unknown status, invalid count, normal return with either `Pending` word, impossible status/throwable/count/closed
combination, or contradictory transaction evidence is malformed `InternalFailure`. Malformed or ambiguous
ownership outranks OOM normalization; coherent OOM evidence outranks an ordinary compressor result. Unknown or
malformed evidence authorizes no bytes and no Native-health change.

The pending-throwable decoder is exact:

- valid `JavaThrowable` with the identical `OutOfMemoryError` yields coherent required-allocation exhaustion
  with that same cause;
- valid `JavaThrowable` with an `Exception` yields internal mechanical failure with that exact cause;
- valid `JavaThrowable` with every other `Throwable` performs fixed allocation-free bookkeeping and rethrows
  the identical object;
- `InternalFailure` or malformed evidence with an `Exception` yields internal mechanical failure with that
  exact cause; and
- `InternalFailure` or malformed evidence with every other `Throwable`, including `OutOfMemoryError`, performs
  fixed allocation-free bookkeeping and rethrows the identical object.

`JavaThrowable` is valid only for temporary-view construction or the synchronous sink call. A pre-adoption OOM
on any other path is not normalized through `JavaThrowable`, and no branch fabricates an `OutOfMemoryError`.
Document 01 alone maps the resulting mechanical classification to caller-visible state, exception, drops, and
Stats.

The safe optional compressor-allocation fact is the only native result that may authorize the architecture to
move the still-current Native health cell from `Enabled` to `Disabled`. A configuration-stale result may still
disable when it names the current health cell, but a retired-health, expired, terminal, nonreturning, malformed,
or ownership-uncertain result cannot. Framework work is eligible only after later architecture reconciliation;
there is no same-frame retry. Native failure never changes Target or rendering health.

## Native ownership equations

For a clean successful compressor result, define:

```text
J = P, the exact complete native-produced JPEG byte count
N = bytes still owned in native segments
M = bytes already adopted into managed transaction segments
s = size of the segment currently being copied by the managed sink
Smax = largest actual segment size in this call
```

Immediately before each adoption:

```text
N + M = J
```

While the managed sink copies the current segment and native still owns it:

```text
N + M + s = J + s <= J + Smax
```

Normal sink return followed by native free transfers `s` from `N` to `M` and removes the duplicate. A one-
segment result therefore reaches transient `2J`. Complete transfer ends with:

```text
N = 0
M = J
transient native-plus-managed JPEG bytes <= J + Smax <= 2J
```

These equations describe actual simultaneous ownership; they are neither encoded-size prediction nor admission
policy. Managed payload/storage roles and their inventory remain solely in Document 05.

## Entered-operation duration and cleanup boundary

`jpegEnteredOperationSafetyNanos = 15_000_000_000L` is the sole JPEG-private finite duration. It applies
independently to complete Native preparation, Framework resource creation, Framework encode, Native encode,
preterminal Bitmap recycle, incompatible native-carrier free, native replacement allocation, and managed
replacement allocation. Consecutive stages receive independent windows. Terminal-origin cleanup, or cleanup
converted after terminal transfer, has no watchdog.

Document 02 owns checked deadline construction, elapsed-realtime comparison, equality-to-expiry, entry versus
cutoff, result currentness, terminal transfer, and residue. The duration is implementation policy, not a public
latency guarantee. Timeout is never native return, writer close, carrier free, ownership release, or permission
to replace.

Returning cleanup uses only actual evidence:

- native carrier storage is released only by the one normal free return;
- native encode storage is released only after actual compressor/adopter return, temporary-reference deletion,
  segment release, and verified writer close; and
- managed carrier retirement is only final engine-reference drop after every use, with no physical completion
  claim.

Nonreturn preserves the exact live native and managed owners described above. Native code exposes no alternate
cleanup handle, background continuation, retry, or fabricated close fact across the JNI return boundary.

## Native build, DSOs, and package

The module namespace is `io.screenstream.engine`. Every production and native-test native configuration uses
NDK `29.0.14206865`.

The JNI-free production runtime compiles once as the hidden position-independent CMake OBJECT target
`screencaptureengine_runtime` from `native_jpeg_runtime.cpp`. Both JNI glue units include
`native_jpeg_runtime.h` and link the unchanged object code:

| Build target | Sources and packaged binary |
| --- | --- |
| production target `screencaptureengine` in main/debug/release/nativeTest | hook-free production JNI glue plus the runtime object; produces `libscreencaptureengine.so`, loaded as `screencaptureengine` |
| nativeTest-only target `screencaptureengine_native_test` | `screencaptureengine/src/nativeTest/cpp/CMakeLists.txt` and `screen_capture_engine_native_test_jni.cpp` plus the same runtime object; produces `libscreencaptureengine_native_test.so` |

The native-test DSO is test-local, is not published, cannot access production DSO state, and cannot alter the
production glue or runtime compilation. This paragraph defines binary composition only; verification controls
and methods remain in Document 08.

`build.gradle.kts` passes `-DANDROID_WEAK_API_DEFS=ON` to every production/nativeTest native configuration that
uses the production target. The runtime object sets `POSITION_INDEPENDENT_CODE ON`,
`CXX_VISIBILITY_PRESET hidden`, and `VISIBILITY_INLINES_HIDDEN YES`; both DSOs retain hidden C++/inline
visibility. The native build directly links `jnigraphics`, treats `-Wunguarded-availability` under `-Werror`,
and links production with `-Wl,--no-undefined` plus the named version script.
`AndroidBitmap_compress` remains a guarded undefined weak symbol, while
`libjnigraphics.so` remains a direct `DT_NEEDED` dependency.

The library fixes `testBuildType = "nativeTest"`. Only nativeTest passes
`SCREEN_CAPTURE_ENGINE_NATIVE_TEST=ON`, adding the test DSO without changing the production target or shared
runtime object.

Production packages exactly these ABIs:

```text
armeabi-v7a
arm64-v8a
x86
x86_64
```

Every packaged native DSO has 16-KiB load-segment alignment; the production link uses
`-Wl,-z,max-page-size=16384`. The version script exposes only the two production exports listed above and hides
every other symbol. Debug and release packages contain only the production DSO. nativeTest contains the
production and native-test DSOs. Host tests package no native code. The library manifest remains exactly empty:
it adds no component, permission, service, provider, or runtime feature.

## Negative boundary

The Native/JNI/package implementation contains none of the following:

- Session lifecycle, currentness, publication, cache, counter, terminal, Delivery, or public-outcome authority;
- runtime ABI/device selection, ABI query, device allowlist, benchmark/image-score choice, or memory prediction;
- loader retry, alternate library name, extra export, reflective JNI discovery, or mutable registration table;
- a stored compressor pointer, `dlopen` family, persistent writer, Java global sink reference, or native owner
  that crosses a returning compression call;
- Native compression from a managed carrier, replacement of a healthy compatible native carrier solely because
  Framework is selected, or same-frame Native-to-Framework retry;
- JNI from the writer callback, callback-thread identity assumptions, cross-return writer continuation, or
  adoption on a non-success compressor result;
- result-block struct aliasing, assumed alignment, enum-ordinal/range decoding, unknown-status acceptance, or a
  third wire word;
- partial/native-backed publication, segment reordering/coalescing, retained temporary direct view, or managed
  inference that native storage was freed;
- release inferred from timeout, cancellation, stale identity, terminal State, queue state, reference loss, or
  a native call that has not actually returned; or
- a second raw carrier, overlapping replacement, native payload cache, generic native pool, arbitrary encoded-
  size cap, runtime free-memory gate, or zeroization guarantee.

## Official platform references

- [Android bitmap compression](https://developer.android.com/ndk/reference/group/bitmap)
- [JNI tips](https://developer.android.com/ndk/guides/jni-tips)
- [JNI direct-buffer support](https://docs.oracle.com/en/java/javase/17/docs/specs/jni/functions.html#nio-support)
- [Android ABIs](https://developer.android.com/ndk/guides/abis)
- [Using newer NDK APIs](https://developer.android.com/ndk/guides/using-newer-apis)
- [Android 16-KiB page-size compatibility](https://developer.android.com/guide/practices/page-sizes)
