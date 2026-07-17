# Screen Capture Engine — Encoded Storage Domain

This file owns the checked managed transaction, immutable segmented JPEG payload, production/latest/displaced
roles, and encoded-payload leases. Framework Bitmap transfer/encode is
[FJPEG-040](09-domain-framework-jpeg.md#fjpeg-040--exact-carrier-to-bitmap-transfer) and
[FJPEG-050](09-domain-framework-jpeg.md#fjpeg-050--framework-encode-and-transaction). Native carrier, call-scoped
JNI writer, segment-transfer order, and native cleanup are
[NJPEG-030](10-domain-native-jpeg.md#njpeg-030--carrier-ownership-and-replacement),
[NJPEG-080](10-domain-native-jpeg.md#njpeg-080--writer-capsule-result-block-and-adoption), and
[NJPEG-100](10-domain-native-jpeg.md#njpeg-100--cleanup-and-late-return). Public borrowed-frame behavior is
[PROD-040](02-product-contract.md#prod-040--effective-output-and-borrowed-frame); cache/repeat use and physical
handoff are [DEL-PACE-020](12-domain-delivery-observation.md#del-pace-020--repeat-cache-eligibility-and-output-commit)
and [DEL-HO-001](12-domain-delivery-observation.md#del-ho-001--one-registration-and-one-handoff) through
[DEL-HO-040](12-domain-delivery-observation.md#del-ho-040--terminal-cutoff-and-delivery-cleanup). Shared managed
ownership, allocation, cleanup, and privacy remain `CORE-OWN-1`, `CORE-ALLOC-1`, `CORE-CLEAN-1`, `CORE-CLEAN-2`,
and `CORE-PRIV-1`.

## Navigation

- [STORE-001](#store-001--file-and-authority-boundary)
- [STORE-010](#store-010--typed-boundary)
- [STORE-020](#store-020--closed-owner-roles)
- [STORE-030](#store-030--transaction-state-and-arbitration)
- [STORE-040](#store-040--framework-producer-algorithm)
- [STORE-050](#store-050--commit-immutable-payload-and-caller-copies)
- [STORE-060](#store-060--native-segment-adoption)
- [STORE-070](#store-070--publish-cache-repeat-lease-and-retirement)
- [STORE-080](#store-080--failure-terminal-and-cleanup)
- [STORE-090](#store-090--structural-memory-and-copy-policy)
- [STORE-100](#store-100--executable-obligations)
- [STORE-110](#store-110--forbidden-alternatives)
- [Official implementation references](#official-implementation-references)

## STORE-001 — File and authority boundary

Production storage declarations and behavior are in `INT:EncodedStorageOwner.kt`. It owns the transaction
states, checked Framework `OutputStream`, native adoption sink, immutable payloads, frame metadata wrappers,
production/cache roles, and counted lease. Path aliases are defined in
[01-authority-router.md](01-authority-router.md#4-module-and-path-bindings).
The cohesive storage state machine remains in this root file; no empty `storage` package or forced split exists.

Storage declares the nested `NativeSegmentSink` and its private
`adoptNativeSegment(ByteBuffer, Int): Unit` method. `NJPEG-001` and `NJPEG-050` alone own its frozen JNI binary
target/descriptor, registration, call ordering, and keep boundary. Storage does not own `NativeJpegProcess`,
the call-scoped native capsule/segments, carrier leases, Bitmap transfer, controller currentness, pacing,
dispatcher handoff, or the public frame wrapper.

## STORE-010 — Typed boundary

| Direction | Exact typed value | Storage obligation |
| --- | --- | --- |
| inbound from `FJPEG-050` | one attached `FrameworkTransaction` and its private checked `OutputStream` | accept only validated ordered writes; commit or abort one transaction |
| inbound from `NJPEG-080` | one attached `NativeTransaction`, its exact `NativeSegmentSink`, and one synchronous exact-range invocation | validate and copy the invocation into managed transaction state; retain no native view/backing or native cleanup fact |
| inbound from `CTRL-200`, `CTRL-300` | expected role identities, positive `ImageSize`, output sequence and elapsed-realtime timestamp | perform the commanded identity-fenced role transition under `sessionGate`; never decide currentness, publication policy, or counters |
| outbound to `CTRL-200`, `CTRL-300` | `UnpublishedEncodedPayload`, `PublishedEncodedPayload`, and exact transition success/failure | expose only complete immutable payload/role facts for controller arbitration |
| outbound to `DEL-PACE-020`, `DEL-HO-001`, `DEL-HO-020` | exact published-payload metadata and one `EncodedPayloadLease` | preserve backing ownership; delivery gets only the counted lease and its copy surface |
| outbound to `CTRL-400`, `CORE-CLEAN-1`, `CORE-CLEAN-2` | exact transaction, tentative managed segments, payload role, or encoded-lease obligation | detach managed ownership only after exact producer/delivery facts permit it; claim no physical managed-free receipt |

`EncodedStorageOwner` has these closed private roles. Exact private spelling may vary, but no competing storage
state machine or payload representation may exist:

```kotlin
EncodedStorageOwner
  SegmentedTransaction -> FrameworkTransaction | NativeTransaction
  NativeSegmentSink.adoptNativeSegment(segment: ByteBuffer, byteCount: Int): Unit
  ImmutableEncodedPayload.copyTo(destination: ByteArray, destinationOffset: Int): Int
  ImmutableEncodedPayload.copyBytes(): ByteArray
  UnpublishedEncodedPayload
  PublishedEncodedPayload
  EncodedPayloadLease
```

Transactions expose only commit/abort and their backend-specific producer facade. Payload and lease references
are identity-bearing owners, not interchangeable value snapshots.

## STORE-020 — Closed owner roles

`EncodedStorageOwner` contains exactly these mutually constrained roles:

| Role | Maximum | Contents and transition |
| --- | ---: | --- |
| production | 1 | either one tentative attached transaction or its one completed unpublished payload, never payloads for two attempts |
| latest | 1 | last published immutable payload plus its image size, sequence, and timestamp |
| displaced | 1 | former latest retained only because the sole delivery lease still references it |
| delivery lease | 1 | counted one-shot lease over latest or the one displaced payload |

A transaction attaches only when the production role is empty and it is fresh `Open` with zero accepted bytes.
Commit transfers that same identity from transaction to unpublished payload. Abort detaches it. Publication,
repeat replacement, invalidation, displacement, lease attachment, and lease release compare exact expected
identities; stale or mismatched commands mutate nothing.

Persistent backing bytes belong to storage. A callback record owns only its lease. Latest and its lease may
coexist; replacing or retiring a leased latest moves it to displaced before changing latest. Because only one
lease exists, no second displaced payload is reachable. Releasing that lease drops displaced when it names the
same payload. A zero-lease retired payload is detached outside `sessionGate` after the identity transition.

## STORE-030 — Transaction state and arbitration

The sole transaction state set is:

```text
Open -> ProducerClosed -> Committed
Open ------------------> Committed
Open | ProducerClosed -> Faulted -> Aborted
Open | ProducerClosed -----------> Aborted
```

| Operation | Exact rule |
| --- | --- |
| producer write | valid only in healthy `Open`; a first fault is sticky and later writes cannot mutate bytes |
| `flush()` | allocation-free no-op only while `Open`; it does not commit or publish |
| `close()` | idempotently changes healthy `Open` to `ProducerClosed`; it performs no trim, commit, or publication |
| `commit(imageSize)` | accepts only healthy `Open`/`ProducerClosed`, positive image size, and nonzero bytes; transfers only after trim/freeze completes |
| `abort()` | allocation-free and idempotently detaches tentative owners from `Open`/`ProducerClosed`/`Faulted`; abort after `Committed` is invariant failure |
| operation after `Committed`/`Aborted` | invariant failure; it never reopens or republishes the transaction |

The first exact allocation OOM or unrepresentable cumulative managed `Int` length records
`ResourceExhausted`. Malformed state/range/evidence or an unexpected non-OOM `Exception` records
`InternalFailure`. Ambiguity or contract failure outranks OOM. An owning JPEG domain maps that sticky storage
fact into its attempt result; storage itself does not select fallback, lifecycle, Stats, or retry. Framework
storage never normalizes a non-OOM fatal JVM `Error`: the outer Framework boundary preserves its process/thread
semantics and roots the exact still-owned occurrence residue.

## STORE-040 — Framework producer algorithm

The private `OutputStream` is the sole Framework producer facade. Every write validates writable state and the
complete new cumulative `Int` total before its first mutation.

- `write(value)` appends only the low eight bits and allocates no temporary one-byte array.
- `write(source, offset, count)` first validates the entire source range with overflow-safe arithmetic. A valid
  zero count is an allocation-free no-op. Source mutation after return cannot affect accepted bytes.
- The call fills any available tail first. If bytes remain, it creates at most one new tail and copies the
  remaining source bytes once. Existing full segments are never copied.

When a new tail is required, capacity is derived, never configured:

```text
A = bytes already accepted at that point
R = positive bytes remaining in this write call
V = Int.MAX_VALUE - A
C = min(max(A, R), V)
```

The prior cumulative check proves `R <= V`, so `C >= R > 0`. The first bulk write therefore allocates exact
`R`; isolated single-byte writes derive `1, 1, 2, 4, ...`. Tail allocation, source copy, and segment-list append
belong to that write. If any fails after earlier bytes from the call were copied, the whole transaction becomes
sticky `Faulted`; no partial transaction can commit or publish.

## STORE-050 — Commit, immutable payload, and caller copies

Framework commit rejects an empty encoder success. A full final tail transfers without copying; a partially used
tail of `U > 0` is copied once into exact `ByteArray(U)`. Only after successful tail trim and immutable ordered
reference freeze does the transaction publish one `ImmutableEncodedPayload`; commit never concatenates segments.
Native transactions already hold exact sealed managed segments and freeze only their ordered references.

An immutable payload owns nonempty private `ByteArray` segments and one positive exact total `J` equal to their
checked sum. No array, mutable builder, view, segment container, or native backing escapes.

| Copy API | Exact behavior |
| --- | --- |
| `copyTo(destination, destinationOffset)` | validate the entire `[destinationOffset, destinationOffset + J)` range before copying; invalid input throws `IndexOutOfBoundsException` with zero copy; valid input traverses segments directly once and returns `J` |
| `copyBytes()` | allocate one exact caller-owned `ByteArray(J)`, perform the same ordered traversal, and return it |

Allocation failure while trim/freeze creates the sticky transaction failure and permits abort. Caller-demand
`copyBytes()` allocation failure does not corrupt, replace, or retire the engine payload. A callback-side public
copy additionally requires the thread/token validity owned by `DEL-HO-020` and `PROD-040`.

## STORE-060 — Native segment adoption

`NJPEG-080` alone decides whether, when, and in which order to invoke the sink and owns each native segment,
temporary local reference, return/throw evidence, and native release/close. At entry to one synchronous
`adoptNativeSegment` invocation, storage applies only this managed sequence:

1. require an `Open` Native transaction and positive `byteCount`;
2. require one direct view with `position == 0`, `limit == byteCount`, `remaining == byteCount`, and
   `capacity == byteCount`;
3. check `accepted + byteCount` in managed `Int` before allocation;
4. allocate exact `ByteArray(byteCount)`, copy the full range once, append it once, then publish the new total;
5. return without retaining or publishing the temporary native view.

A zero-size writer callback supplies no sink invocation under `NJPEG-080` and therefore creates no managed
segment. Framework writes and native adoption cannot mix in one transaction. Each valid nonempty sink invocation
creates one managed segment; storage never groups, coalesces, exposes, or uses it as native-backed cache storage.

If managed validation/allocation/copy throws, the transaction becomes sticky with the exact storage
classification and preserves that throwable for `NJPEG-080`/`NJPEG-090` evidence. Storage claims no JNI,
native-transfer, native-free, local-reference, capsule-close, or segment-release receipt. Its complete result is
only the managed transaction mutation (or sticky failure) and the invariant that it retained no view.

## STORE-070 — Publish, cache, repeat, lease, and retirement

Controller commits role transitions under `sessionGate`; payload-reference retirement occurs unlocked:

| Action | Storage transition |
| --- | --- |
| fresh publication | move expected unpublished payload to latest with new positive Session sequence, commit-time elapsed timestamp, and positive image size; displace an old leased latest first |
| repeat | replace expected latest metadata with a new sequence/timestamp while retaining identical immutable payload and image size; allocate/copy/encode no bytes |
| cached-first lease command | attach the sole lease only when it is fresh and names the exact current latest; `DEL-PACE-020`/`DEL-HO-001` own whether and how that lease is offered |
| stale completed success | retire only its expected unpublished payload; it cannot replace latest |
| cache invalidation/terminal | retire expected unpublished/latest; preserve any exact displaced leased payload until lease settlement |
| lease release | one atomic one-shot release, then identity-fenced owner consumption; drop displaced only when the released lease names it |

Sequence/timestamp generation, freshness, pacing, output currentness, repeat selection, and delivery admission
are `CTRL-200`/`DEL-PACE-020` decisions. Storage applies only the accepted role command and preserves the payload
identity. Published sequence starts at one and terminally fails before wrap; equal elapsed timestamps are valid.

## STORE-080 — Failure, terminal, and cleanup

No failed, partial, stale, aborted, or uncommitted transaction can publish or replace cache. `FJPEG-050` or
`NJPEG-090` owns operation classification; storage reports only its sticky fact and retains the transaction and
tentative managed owners until the producer fact permits an exact command.

| Condition | Exact storage disposition |
| --- | --- |
| Framework/native managed allocation OOM or checked cumulative nonrepresentability | sticky `ResourceExhausted`; abort all tentative managed segments; no optional fallback or same-frame retry |
| malformed range/state/metadata, non-OOM managed exception, or incompatible evidence | sticky `InternalFailure`; abort; no publication |
| encoder false/safe Native optional failure with exact storage ownership | abort bytes; the owning JPEG domain alone applies its documented frame/fallback result |
| producer timeout/nonreturn before storage use is proven complete | grant no abort or retirement fact; retain the exact transaction/tentative managed segments inside the enclosing JPEG occurrence; classification stays `FJPEG-050`/`NJPEG-090` |
| accepted late producer fact after expiry/terminal transfer | apply only the matching `CTRL-400` cleanup command to the exact transaction/payload role; never publish or replace cache |
| terminal with settled managed payload/lease | detach each exact role when its uses permit; last engine-reference drop is logical retirement only |

Cancellation, deadline, abort request, role detachment, or loss of a managed reference is not proof of physical
reclamation. `NJPEG-100` owns native nonreturn and late-return cleanup; `DEL-HO-040` owns unresolved physical handoff
and lease-release facts. `CORE-CLEAN-1`/`CORE-CLEAN-2` alone define root transfer and late reduction.

## STORE-090 — Structural memory and copy policy

The storage policy uses actual owned ranges, never predictive memory accounting or an encoded-size cap.

For Framework bytes `J > 0`, open segment capacity is at most `2J`; trimming a partial tail temporarily retains
`J + C < 2J`; persistent committed capacity is exact `J`. For one Native sink invocation of size `s`, storage
adds one exact managed `ByteArray(s)` to already adopted managed bytes only after checked cumulative acceptance.
`NJPEG-080` owns native source-segment lifetime, native/managed duplication, order, and release equations.

The combined engine structural live-range inventory is:

| Symbol | Actual range |
| --- | --- |
| `B` | one stable exact raw RGBA carrier |
| `L` | current latest encoded payload, or zero when absent |
| `O` | one displaced payload retained by its lease, or zero when absent |
| `J` | one tentative/completed-unpublished encoded result, including its native/managed split during transfer, or zero when absent |
| `Smax` | largest actual Native segment in the current encode, bounded by `0 <= Smax <= J` |

During a Native adoption of actual size `s`, the instantaneous structural range is `B + L + O + J + s`; the
encode's one-segment structural maximum is `B + L + O + J + Smax`, with absent roles contributing zero.
[`NJPEG-080`](10-domain-native-jpeg.md#njpeg-080--writer-capsule-result-block-and-adoption) solely defines the
per-adoption `N/M/s` transitions that justify this range; this section does not duplicate them. Caller
`copyBytes()` may additionally own one exact payload-length array. Opaque JVM, allocator, container, Bitmap, and
platform overhead is disclosed, not estimated, capped, pooled, or used as a runtime selector.

There is no chunk-size constant, arbitrary payload limit, pool, predictive preallocation, concatenated persistent
copy, native-backed published payload, per-payload native cleanup, or zeroization/reclamation guarantee.

## STORE-100 — Executable obligations

Closed packet membership is in [router §5](01-authority-router.md#5-closed-implementation-packets); shared
closure/routing and test namespaces are in [Document 04](04-verification.md), and exact test paths are in
[router §7](01-authority-router.md#7-test-manifest). Storage-specific proofs are:

| Test ID | Exact proof |
| --- | --- |
| `H-PS` / transaction | every legal/illegal write, flush, close, commit, committed-payload transfer, and abort sequence; attach identity; first-fault stickiness; empty commit; payload visibility and exact owner detachment |
| `H-PS` / Framework growth | symbolic inputs covering first tail, old-tail fill, new/full/partial tail, `write(int)`, mixed calls, valid/invalid/zero bulk ranges, checked limits, and source mutation; exact derived `C` and byte order with no tuning literal |
| `H-PS`, `A-FJ` | injected tail allocation, copy, list append, trim, container/freeze, and caller-flatten allocation results; exact storage classification/abortability, zero partial publication, ordered `copyTo`, persistent `J`, and symbolic managed bounds |
| `H-PS`, `H-DL` | production/latest/displaced/lease identity transitions; accepted fresh/repeat/cache/invalidating/terminal commands; exact release fact; at most one displaced payload and no duplicate detach/copy; `DEL-PACE-020`, `DEL-HO-001`, `DEL-HO-020`, and `DEL-HO-040` own physical use/handoff ordering |
| `N-JPEG` | storage-side exact positive direct-range validation, checked cumulative boundary, one managed segment per valid invocation, ordered bytes, view non-retention, and allocation/copy throwable identity; every adoption asserts the exact pre-copy, transient-copy, post-free, one-segment `2J`, and final `J` ledgers owned by `NJPEG-080`, while `NJPEG-120` owns invocation and native release/close order |
| `A-CL` | held transaction or encoded lease preserves only exact managed storage roles; accepted late facts detach only matching roles; no tentative/stale bytes or backing container escape; generic root/cross-Session mechanics remain `CORE-CLEAN-1`/`CORE-CLEAN-2` |

All checked additions, destination/source ranges, exact `Int` limits, image-size validity, sequence exhaustion, and
identity mismatches receive valid-boundary and invalid-boundary rows. Tests assert the exact `B/L/O/J/Smax`
live-role inventory, per-adoption and final-state ledgers, and call/owner counts—not heap reclamation timing,
encoded-size prediction, performance, or a numeric storage tolerance. This domain introduces no private numeric
constant or image tolerance.

## STORE-110 — Forbidden alternatives

- a second transaction machine, production slot, payload representation, cache, displaced generation, or lease;
- chunk/tuning constants, arbitrary encoded-size caps, pooling, predictive memory admission, or allocator probes;
- partial/zero/stale transaction publication, same-frame retry, or storage-selected backend fallback;
- flatten-on-commit, concatenated persistent arrays, copying existing full Framework segments, native-backed
  cache, grouped native adoption, or retained segment views;
- mixed Framework/native producers, range validation after mutation, unchecked narrowing/addition, or first-fault
  overwrite;
- treating abort, timeout, cancellation, reference drop, GC, or late result as a physical release receipt;
- exposing segments/builders/containers, diagnostics containing encoded bytes, or any authority outside exact
  owners, identities, leases, and receipts.

## Official implementation references

- [OutputStream](https://developer.android.com/reference/java/io/OutputStream)
- [ByteBuffer](https://developer.android.com/reference/java/nio/ByteBuffer)
