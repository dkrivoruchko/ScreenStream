# Native JPEG ABI contract

The Native JPEG boundary is a process-lifetime packet between Kotlin, JNI, and C++. This page records the durable descriptor, status, ownership, export, and error rules. Backend policy and public failure meanings remain in the [public JPEG backend seam](../../docs/architecture.md#jpeg-backend-seam) and [failure/recovery guidance](../../docs/usage.md#handle-failures-and-recovery). Maintained policy and physical boundaries are in the [encoding component](../components/encoding.md#backend-selection-and-fallback) and [failures and terminal semantics](failures-and-terminal-semantics.md). This page does not duplicate build-tool inventories or a verification matrix. The [image pipeline](image-pipeline.md) defines the RGBA input semantics and the [frame ownership and delivery contract](frame-ownership-and-delivery.md) defines the immutable payload after commit. Component direction is in the [architecture overview](../architecture/overview.md).

## Kotlin/JNI/C++ packet

The sole managed facade is `io.screenstream.capture.internal.encoding.NativeJpegProcess`; its logical library name is `screen_capture_engine`, packaged as `libscreen_capture_engine.so`. That exact library and the direct weak [`AndroidBitmap_compress`](https://developer.android.com/ndk/reference/group/bitmap) platform function are the sole Native route. `System.loadLibrary` is the sole authoritative load operation; direct JNI wrappers require already-published availability rather than triggering a load. Alternate DSO names, reflective loading, unload, `dlopen`, `dlsym`, and retained platform-compressor function pointers are forbidden.

JNI registers these private natives in this order:

1. `nativeAllocateCarrier(J)Ljava/nio/ByteBuffer;`
2. `nativeFreeCarrier(Ljava/nio/ByteBuffer;)V`
3. `nativeHasWeakCompressor()Z`
4. `nativeCompress(Ljava/nio/ByteBuffer;JIIIIJIIILio/screenstream/capture/internal/encoding/NativeSegmentSink;Ljava/nio/ByteBuffer;)V`

The compress arguments are, in order: direct carrier, pixel byte count, width, height, stride, format, flags, dataspace, JPEG format, quality, sink, and result block. Kotlin supplies tight top-down opaque RGBA (`stride = 4W`, `ANDROID_BITMAP_FORMAT_RGBA_8888`, opaque alpha, sRGB, JPEG, quality `0..100`). The sink callback is `adoptNativeSegment(Ljava/nio/ByteBuffer;I)V` and is called synchronously for each frozen native segment.

The result block is a direct writable native-order buffer with exactly 16 bytes. Offset 0 is a signed 64-bit produced byte count; offset 8 is a signed 64-bit wire status. Both words begin at `-1` (`Pending`). A returning native path writes the produced count first and status last. The status values are `0` complete transfer, `1` safe compressor rejection, `2` native out-of-memory, `3` internal failure, and `4` pending Java throwable. Field-wise `memcpy` is used for the native words; the block is not a C++ struct or an aliasing cast. Status-last is a same-task completion marker, not a cross-thread fence.

## Ownership and transfer

The managed carrier is an exact direct writable range of `B` bytes. Native allocation returns a direct view over one malloc allocation; native free validates the direct range and frees it once after the managed loan is settled. The call-scoped C++ writer owns a private singly linked native segment chain. Compression callbacks append atomically per callback; preparation failure frees its temporary chain and leaves previously accepted bytes/count/links intact.

After freeze, JNI exposes only the current segment as a temporary direct `ByteBuffer`, synchronously invokes the sink, deletes the local reference, and frees exactly that front node. Managed adoption copies each segment once into Encoding-owned transaction storage. A clean transfer requires positive matching produced/adopted byte counts and no pending Java throwable. Any partial or contradictory transfer is internal failure; only a committed immutable payload can leave Encoding.

Native views never escape their JNI call, and the process-lifetime facade retains no Session or payload state.

## Export contract

`JNI_OnLoad` is the sole exported ELF symbol. It obtains [`JNIEnv`](https://docs.oracle.com/en/java/javase/17/docs/specs/jni/functions.html) for `JNI_VERSION_1_6`, finds the exact facade class, and registers the four methods above; lookup, pending-exception, or registration failure returns `JNI_ERR`. The linker version map, visibility, Kotlin declarations, registration table, and writer/descriptor types must remain aligned with this packet. The ABI is not mirrored in a second facade or compatibility packet.

## Error boundaries

Every JNI entry point is `noexcept` and contains C++ exceptions before they can cross JNI. Only `nativeCompress` communicates a wire status through the 16-byte result block. `nativeAllocateCarrier` returns a direct buffer or uses its declared Java exception paths (`IllegalArgumentException`, `OutOfMemoryError`, or `IllegalStateException`); `nativeFreeCarrier` uses its declared Java argument/internal exceptions and returns `void`; and `nativeHasWeakCompressor` returns a boolean or its declared Java internal exception. These entry points do not invent wire statuses. Native validates nulls, direct addresses, capacities, ranges, narrowing, and descriptors before compressor entry. C++ `bad_alloc` or another contained native exception is converted to the declared Java exception for allocation/free/capability, or to a typed internal wire result for compression, after owned cleanup where that cleanup is proved.

An uncontained Java `Throwable` already pending on `JNIEnv` is not cleared, described, or replaced; native may only nonthrowingly release provably owned call-local references/segments before returning Java propagation. Wire words never convert a thrown invocation into a normal managed return.

`SafeCompressorRejection` is emitted only for a normally returned AOSP NDK compressor rejection (`JNI_EXCEPTION` or `ALLOCATION_FAILED`) after writer freeze and successful writer close, with no writer fault or pending Java throwable and before managed adoption. It therefore permits the managed owner to abort cleanly and disable Native for later frames. Adopted bytes, a writer or close contradiction, `BAD_PARAMETER`, unknown status, or unsafe settlement is internal failure. Native OOM and the separately named managed adoption/capacity boundary map to resource exhaustion only after their required ownership settlement is proved.

## Related contracts

- [Public JPEG backend seam](../../docs/architecture.md#jpeg-backend-seam)
- [Public failure and recovery guidance](../../docs/usage.md#handle-failures-and-recovery)
- [Encoding component](../components/encoding.md#backend-selection-and-fallback)
- [Failures and terminal semantics](failures-and-terminal-semantics.md)
