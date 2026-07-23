# Screen Capture Engine — Framework Encoding and Encoded Storage

## Scope and authority

This document is the sole authority for Framework Bitmap/JPEG mechanics, managed encoded transactions,
immutable encoded payloads, `FrameStore`, and caller-copy storage mechanics. Framework JPEG is the mandatory
encoder implementation. The JPEG role executes the mechanics in this document but does not choose lifecycle,
currentness, fallback, cache eligibility, counters, or caller-visible outcomes.

Authority stays divided as follows:

- [Product Contract](01-product-contract.md) owns public JPEG semantics, public failure and drop outcomes,
  frame metadata, borrowed-frame behavior, and Stats.
- [Internal Architecture](02-internal-architecture.md) owns the JPEG execution role, `EncoderCapsule`, Control
  authority, the three cross-boundary identities, global cardinality, entry-versus-cutoff arbitration,
  terminal transfer, and process-lifetime residue.
- [Native/JNI/Package](06-native-jni-package.md) owns native carriers, native compression, JNI, adoption call
  ordering, wire evidence, native memory, release, and package facts.
- [Delivery and Observation](07-delivery-observation.md) owns callback admission, borrowed-frame thread/token
  validity, handoff completion, and when an encoded lease is released.
- [Verification](08-verification.md) owns executable proof, source sets, tasks, matrices, and acceptance
  evidence.

Private classes and file layout may vary. They must not create a second encoder policy writer, transaction
family, payload representation, role store, commit path, or lease authority.

## Framework encoder boundary

The serial JPEG role accepts one immutable encode plan and the sole writable raw-carrier lease for its admitted
production. A Framework plan contains the checked positive output size `(Ow, Oh)`, exact tight-row byte count
`rowSize = 4 * Ow`, exact raw byte count `B = rowSize * Oh`, JPEG quality, the current Framework Bitmap owner,
and a fresh attached managed transaction. All arithmetic and narrowing is checked before allocation or an
outward call.

The role either returns one closed mechanical fact to Control:

- complete immutable unpublished payload;
- encoder returned false;
- coherent required-allocation exhaustion;
- malformed, inconsistent, or unexpected non-memory failure;

or it propagates an identical fatal throwable under the architecture's fatal boundary.

The role settles only its physical owners. It never publishes a payload, replaces cache, changes Native health,
increments a counter, or decides whether its result is current. It never performs a second encode for the same
production. An optional Native failure can make Framework eligible only for a later Control-admitted
production, as owned by Documents 01, 02, and 06.

## Bitmap owner, creation, and reuse

One healthy `FrameworkBitmapOwner` contains exactly:

- one mutable software sRGB `Bitmap.Config.ARGB_8888` Bitmap of the exact output shape;
- its validated actual width, height, `rowBytes`, `byteCount`, and `allocationByteCount`;
- the applicable guarded config, mutability, and color-space evidence;
- one selected `TightCopy` or `RowConversion` transfer mode;
- only the reusable width-sized row scratch required by `RowConversion`; and
- a use count that prevents recycle while transfer or compression may still access the Bitmap.

The owner is reusable only for the identical checked output shape and unchanged transfer requirements. A
compatible healthy owner retains referential identity across reconciliation and Target replacement. Reuse
allocates no Bitmap or row scratch. An incompatible or unhealthy owner must complete the retirement mechanics
below before a replacement Bitmap can be created; unresolved physical ownership forbids overlap.

At most one Framework creation is admitted for the current configuration, and no second creation begins before
that work has closed. Creation asks `Bitmap.createBitmap(Ow, Oh, ARGB_8888)` for exactly one mutable software Bitmap. The returned
reference is adopted immediately before any further work that could fail. Metadata is then read and validated,
the transfer mode is selected from actual row layout, and row scratch is allocated only if that mode requires
it. A partially constructed Bitmap owner remains rooted by the current `EncoderCapsule` until it is either
installed or retired.

### API-band metadata rules

Every supported API reads and validates actual width, height, `rowBytes`, `byteCount`,
`allocationByteCount`, config, and mutability. The common predicates are:

```text
width == Ow
height == Oh
config == ARGB_8888
isMutable == true
rowBytes >= rowSize
checked(rowBytes * Oh) == byteCount
byteCount <= allocationByteCount
```

Every value is nonnegative where the platform type permits it, and every multiplication/comparison uses checked
wide arithmetic before narrowing. Failure of an applicable predicate is malformed platform evidence, not a
different transfer mode.

On API 24–25, production makes zero `getColorSpace()` calls and neither reads nor compares
`Bitmap.Config.HARDWARE`. The exact factory request plus actual config and mutability is the software-sRGB
evidence for that band.

Every API-26-only access is dominated by `SDK_INT >= 26`. On API 26+, the actual color space must be exact sRGB
and actual config must not be `HARDWARE`, in addition to the common predicates. No helper may evaluate an
API-26-only symbol or method on API 24–25.

`allocationByteCount` is retained as the actual reusable Bitmap allocation `F`; it is not inferred from output
dimensions or used to predict a future allocation. `rowBytes * Oh` describes the validated row layout required
for transfer, while allocator and Bitmap implementation overhead remains opaque.

### Creation result mechanics

Only a complete valid Bitmap/mode/scratch owner may be offered to Control for installation. A direct
`OutOfMemoryError` from the one Bitmap creation or required row-scratch allocation, or a coherent checked
creation-size denial, is required-allocation exhaustion. Malformed metadata, contract rejection, or an
unexpected non-OOM `Exception` is internal mechanical failure. Ownership ambiguity takes precedence over an
otherwise visible OOM.

A safely returned result that no longer matches the current configuration is never installed. A returned
Bitmap is retired exactly once; row scratch is dropped only after all work that could access it has ended. A
result returned after terminal transfer is cleanup-only. Cutoff before role entry performs no Bitmap factory or
scratch call. If an entered factory or metadata/scratch step does not return, the `EncoderCapsule` retains the
operation's Bitmap reference cell, any adopted Bitmap, scratch, and creation inputs; no replacement is
authorized from timeout, cancellation, queue state, or reference loss.

## Raw-carrier transfer

Canonical carrier pixels are exactly `B` bytes of tight, top-down RGBA with opaque alpha. One synchronous
transfer borrows the exact writable carrier range `[0, B)`, retains no carrier reference after actual
return/throw, and uses exactly one of the installed Bitmap owner's modes.

### `TightCopy`

`TightCopy` is selected only when actual `rowBytes == rowSize`, the validated Bitmap byte layout covers the
exact `B` bytes, and the carrier lease exposes exactly `[0, B)`. The transfer prepares the leased direct buffer
for that full range and invokes `copyPixelsFromBuffer` once. Raw byte interpretation is independent of
`ByteBuffer.order()` on the supported little-endian Android ABIs.

### `RowConversion`

`RowConversion` is selected for a valid padded Bitmap row layout. For each source row in
top-down order, it converts the tight RGBA bytes into the retained `IntArray(Ow)` as opaque logical ARGB values,
then calls `Bitmap.setPixels` once for that row. Source bytes `r`, `g`, and `b` become
`0xff000000 | (r << 16) | (g << 8) | b`; carrier alpha is not allowed to create transparency. The same scratch
is reused for every row and every compatible frame.

There is no full-frame transfer array, second raw carrier, per-frame Bitmap, or per-row allocation. Mode is
selected only from the created Bitmap's actual metadata. A transfer failure cannot switch mode or retry the
frame. Bitmap use and carrier lease remain held until the transfer and any following compression can no longer
access them; nonreturn retains both in the `EncoderCapsule`.

## One Framework compression

For each admitted Framework production, the JPEG role performs this sequence exactly once:

```text
acquire one Bitmap use
-> transfer the exact carrier into that Bitmap
-> call Bitmap.compress(JPEG, quality, transaction.outputStream) once
-> close and commit, or abort, that same transaction
-> publish one closed mechanical result to Control
-> release the Bitmap use and carrier lease when actual return makes that safe
```

The transaction is attached before outward encoding begins. `flush()` or `close()` of its stream does not
commit. No tentative byte is visible to Control or Delivery.

Mechanical classification follows this precedence after ownership and contract checks:

| Evidence | Encoding/storage disposition |
| --- | --- |
| `compress` returns `true` and commit succeeds | Return one complete immutable unpublished payload. Control alone applies currentness and publication. |
| `compress` returns `false` | Abort all tentative bytes and return encoder-false. The installed Framework owner remains healthy for later productions. |
| Direct `OutOfMemoryError` from `Bitmap.compress`, an entered transaction write/allocation, exact-size normalization, or immutable-reference freeze; or checked cumulative managed length cannot be represented as positive `Int` | Abort when producer access is proven ended and return required-allocation exhaustion with the exact available cause. |
| Malformed sink range/count/length, invalid transaction or payload evidence, transfer/encoder contract violation, or unexpected non-OOM `Exception` | Abort when safe and return internal mechanical failure with the exact available cause. |
| Entered transfer, compress, or sink does not return, or ownership remains uncertain | Produce no closed result; retain the exact carrier use, Bitmap use, transaction, and tentative storage in `EncoderCapsule`. |

Malformed, ambiguous, or contradictory evidence outranks OOM evidence; coherent OOM evidence outranks the
returned Boolean. A transfer-side or other non-enumerated `OutOfMemoryError`, and every other direct non-
`Exception` throwable, is not normalized here. The fixed outer JPEG-role boundary performs only the
allocation-free ownership steps that are provably safe and propagates the identical throwable as required by
Document 02.

A mechanically successful result that Control finds stale is still a complete immutable unpublished payload;
Control can command its identity-fenced retirement but cannot publish it. A mechanical failure returned after
terminal transfer is cleanup-only. This document does not redefine the public drop, failure, Stats, diagnostic,
or terminal effects owned by Document 01.

## Managed encoded transaction

There is one transaction family for Framework writes and Native managed adoption. A transaction has one stable
identity, one producer kind, private tentative segments, one checked cumulative byte count, and exactly one of
these states:

```text
Open -> ProducerClosed -> Committed
  |          |
  +-------> Faulted -> Aborted
  +-----------------> Aborted
```

`Open` is healthy and writable. `ProducerClosed` is healthy but no longer writable. `Faulted` stores the first
authoritative storage fault and permits only abort. `Committed` has transferred ownership to one immutable
payload. `Aborted` has detached tentative owners. No final state reopens.

The production role accepts a transaction only while empty, with the transaction fresh `Open`, its accepted
byte count zero, and its producer kind fixed. Framework writes and Native adoption never mix. The same identity
moves from attached transaction to completed unpublished payload on commit; abort detaches that identity.

| Operation | Exact state rule |
| --- | --- |
| producer write/adoption | Legal only in healthy `Open`; validate completely before mutation. The first fault is sticky. |
| `flush()` | Allocation-free no-op only in `Open`; it neither closes nor commits. |
| producer `close()` | Idempotently changes healthy `Open` to `ProducerClosed`; performs no trim, freeze, commit, or publication. |
| `commit(imageSize)` | Legal only in healthy `Open` or `ProducerClosed`; requires positive image dimensions and a positive checked byte total. Ownership transfers only after exact-size normalization and immutable freeze both succeed. |
| `abort()` | Allocation-free and idempotent from `Open`, `ProducerClosed`, or `Faulted`; detaches tentative managed owners. Abort after `Committed` is an invariant failure. |
| operation after `Committed` or `Aborted` | Invariant failure with no mutation, reopen, or publication. |

An exact managed allocation OOM or unrepresentable cumulative positive `Int` length records storage
exhaustion. Malformed state, range, metadata, or payload evidence and unexpected non-OOM exceptions record
internal storage failure. Ambiguity outranks exhaustion. The transaction preserves the exact relevant throwable
for the owning encoder result; storage itself chooses no public failure, fallback, lifecycle, or counter.

## Checked Framework `OutputStream`

The transaction's private `OutputStream` is the sole Framework producer facade. It never exposes its segments
or mutable builder.

`write(value)` appends only the low eight bits and allocates no temporary one-byte array.

`write(source, offset, count)` validates the entire source range with overflow-safe arithmetic and validates the
new cumulative positive-`Int` bound before its first mutation. A valid zero count is an allocation-free no-op.
Invalid input changes no transaction byte. Mutation of `source` after a successful return cannot change bytes
already accepted by the transaction.

A valid write first fills any current tail. If bytes remain, the existing capacity is then fully accepted; the
transaction allocates at most one new backing range sized from that accepted capacity and the exact remaining
count, copies the remaining source bytes once, and appends the range once. The first backing range is exactly
the first nonempty remaining count. Later range capacity is `max(fullAcceptedCapacity, remainingCount)`.
Consequently:

- every accepted source byte is copied into tentative managed storage exactly once;
- no accepted full prefix is recopied during growth;
- a single write allocates at most one new backing range;
- capacity is derived from actual accepted and remaining bytes, not a chunk-size or payload-limit constant; and
- for accepted length `J > 0`, open backing capacity `C` satisfies `J <= C < 2J`.

An allocation, append, or copy failure makes the whole transaction sticky-failed. A write never leaves a
partially accepted logical result that can commit.

## Commit and immutable payload

Framework commit rejects empty encoder success. Fully used backing ranges transfer without copying. If the
final range has `U > 0` accepted bytes below its capacity, commit allocates exactly one `ByteArray(U)` and copies
that used suffix once. It then freezes an immutable ordered reference container. Ownership transfers only after
both steps succeed; until then abort remains possible and roots all old and new ranges.

Commit never concatenates ranges. The resulting `ImmutableEncodedPayload` contains only nonempty private
`ByteArray` segments and one positive exact total `J`, equal to their overflow-checked sum. Its segment order and
byte content never change. No array, segment container, mutable builder, buffer view, Native backing, or cleanup
handle escapes.

Native transactions arrive at commit with exact managed segments, so commit validates the positive total and
freezes their ordered references without flattening or coalescing them.

### Caller-copy storage mechanics

The payload provides the storage half of the two public copy operations. Delivery owns callback-thread and
borrow validity checks and must validate them before invoking this surface. [Product Contract §6](01-product-contract.md#6-effective-output-and-borrowed-frame)
owns every caller-visible exception, return value, allocation outcome, and lifetime rule.

`copyTo(destination, destinationOffset)` validates the complete range
`[destinationOffset, destinationOffset + J)` with overflow-safe arithmetic before copying any byte. An invalid
range copies zero bytes and modifies no destination byte. A valid range traverses the private segments directly
in order exactly once and copies exactly `J` bytes.

`copyBytes()` performs exactly one caller-array allocation of length `J`, then performs the same single ordered
segment traversal and copies exactly `J` bytes into that array. Neither operation mutates, replaces, or retires
the engine payload.

## Native-to-managed adoption boundary

This document owns only the managed storage side of Native segment adoption. Document 06 owns the JNI method
identity and descriptor, native producer, segment order, invocation count, direct-view construction, native
return/throw classification, and native release.

Each synchronous adoption call requires a healthy open Native transaction, a positive size, a direct buffer
whose exact accessible range matches that size, and a representable new managed cumulative total. All state and
range checks precede mutation. Storage then allocates one exact `ByteArray(size)`, copies that range once,
appends that one managed segment once, and only then advances the transaction total. Each successful nonempty
call creates exactly one segment; storage never groups, coalesces, or reorders calls.

The managed sink retains no `ByteBuffer`, address, native owner, or native-release fact. If validation,
allocation, copy, or append fails, the transaction records its exact sticky storage classification and preserves
the throwable identity needed by Document 06. A failed call publishes neither a partial managed segment nor an
advanced byte count. Native nonreturn and transient native duplication remain entirely owned and bounded by
Document 06; this document does not restate its native ownership equations.

## `FrameStore` roles and transitions

`FrameStore` is Control-confined logical ownership over one immutable segmented payload representation. Only
Control mutates its roles. The admitted JPEG task receives exclusive producer access to the attached
transaction; Control performs no competing transaction operation until the task returns a closed fact. It owns
no lifecycle, currentness, pacing, output sequence allocation, timestamp selection, callback policy, or public
counter. Its closed roles are:

| Role | Maximum | Exact contents |
| --- | ---: | --- |
| production | 1 | One attached tentative transaction or the same identity's completed unpublished payload, never two attempts. |
| latest | 1 | One published immutable payload plus its exact effective descriptor, positive sequence, and elapsed-realtime timestamp. |
| displaced | 1 | A former latest payload retained only because the sole delivery lease still names it. |
| delivery lease | 1 | One counted one-shot lease naming exactly latest or displaced backing. |

Persistent bytes belong to `FrameStore`; a Delivery record owns only its lease. Every transition compares the
expected payload/transaction identity and applicable production or registration identity supplied by Control.
A mismatch or stale command mutates nothing.

### Production and publication

- Attach accepts only a fresh zero-byte transaction while production is empty.
- Commit replaces that exact attached transaction with its one complete unpublished payload.
- Abort or stale-result retirement empties only the matching production role.
- Fresh publication moves the expected unpublished payload to latest with the metadata already selected by
  Control. If a different old latest is leased, it moves to displaced first; an unleased old latest is detached.
- A stale completed success can only retire its expected unpublished payload. It cannot replace latest.
- A Control-commanded invalidation or terminal retirement detaches matching unpublished/latest references, but
  preserves an exact payload still named by the delivery lease as displaced.

### Repeat, cache, and lease

- Repeat creates no transaction, encoding, payload allocation, or byte copy. It retains the identical immutable
  payload and replaces only latest metadata with Control-supplied sequence, timestamp, and current effective
  descriptor.
- Cached-first attaches the sole fresh lease only when it names the exact expected current latest. Storage does
  not decide whether the cache is eligible or whether Delivery may offer it.
- Fresh delivery likewise attaches only the sole lease authorized by Control. No second lease or waiting lease
  record exists.
- Replacing or retiring a leased latest with different backing first moves that payload to displaced. Because
  only one lease exists, a second displaced payload is unreachable.
- Repeating identical backing while it is leased does not duplicate or displace that backing; the lease and
  latest may name the same immutable payload.
- Lease release is one-shot. Control consumes the exact returned release fact and drops displaced only when that
  lease names it. A stale or duplicate release changes nothing.

Reference detachment follows the identity transition and occurs without copying or clearing payload bytes.
Delivery owns callback completion and production/cache policy; `FrameStore` only applies an already-authorized
role transition.

## Cleanup and nonreturn mechanics

No failed, partial, stale, aborted, uncommitted, or native-backed byte range can publish or replace latest.
Cleanup is exact owner detachment after the last possible use; it is not byte zeroization or proof of physical
managed reclamation.

### Bitmap retirement

An installed Bitmap is marked retiring before replacement. New uses then stop, and every accepted transfer or
compress use must actually finish. Only after the use count reaches zero may the serial JPEG role invoke
`Bitmap.recycle()` exactly once for that Bitmap identity. A normal return from that call permits the Bitmap
reference to be dropped and, if Control still requires it, a replacement creation to be considered from fresh
current state.

Cancellation, timeout, terminal publication, loss of a Java reference, or GC timing does not prove recycle and
cannot authorize replacement. If recycle throws, dispatch cannot prove no entry, or the call does not return,
the exact Bitmap, one-shot call authority, and writable result state remain in `EncoderCapsule`; the call is not
repeated and no overlapping replacement is created. Terminal-origin retirement has no progress
deadline. A late normal return only releases the matching retained Bitmap and cannot revive the Session or
authorize active replacement. Row scratch is dropped after its last possible use; there is no physical-free
signal for it.

### Transactions, payloads, and leases

Abort may detach tentative managed ranges only after the producer has returned/thrown or otherwise proven it
cannot access the transaction again. An entered Framework or Native producer that does not return keeps its
transaction and all tentative segments rooted by `EncoderCapsule`; timeout or terminal transfer does not grant
abort authority. A late closed producer fact may only commit the already-selected safe cleanup action for that
exact identity and can never publish.

An immutable payload is logically retired only when absent from production/latest and neither the delivery
lease nor displaced names it. A callback that does not return retains its exact lease and can therefore retain
one displaced payload while capture and later encodes continue within the existing architecture bound. Delivery
owns the physical handoff result; this document consumes only its exact one-shot lease-release fact.

At terminal transfer, `EncoderCapsule` retains exactly the encoder operation, carrier and Bitmap uses,
transaction, tentative segments, writable result fields, and partial Bitmap owner that the entered work may
still touch. The architecture's Encoder retirement field owns whether that capsule later closes or remains until
process death. A real later return may detach only resources proven unused by that same capsule. It cannot
change terminal State, final Stats, cache, backend health, or publication.

## Managed memory and copy bounds

These symbols describe actual currently owned ranges used to state the Framework/managed-storage contribution;
they are not global admission maxima or predictive accounting. `B` names the carrier's exact range regardless
of its backing mode, whose allocation and release remain outside this document:

```text
B = exact stable raw-carrier bytes
F = current Bitmap allocationByteCount, or zero
R = current reusable row-scratch element bytes, or zero
C = actual open Framework encoded backing capacity, or zero
J = exact logical tentative/completed-unpublished JPEG byte count, or zero
L = current latest payload bytes, or zero
O = displaced payload bytes retained by the sole lease, or zero
```

The architecture-wide role cardinalities and structural maxima remain solely in Document 02. Within those roles:

- an open Framework transaction has structural inventory `B + F + R + C + L + O`, with
  `J <= C < 2J` for `J > 0`;
- exact-size normalization temporarily retains less than `2J` production backing in total;
- a committed unpublished Framework payload replaces open `C` with exact persistent `J`, giving
  `B + F + R + J + L + O` while those roles coexist;
- after publication/retirement removes the production role, its `J` contribution is zero;
- one caller-requested `copyBytes()` may additionally own exactly the selected payload length; and
- `copyTo()` adds no engine-owned flattened payload.

For Native production, the managed contribution is the checked sum of exact adopted `ByteArray` segments and
becomes the same exact `J` payload after commit. Document 06 alone defines the simultaneous native ranges,
transient segment duplication, and native-to-managed ownership equations. This document neither duplicates
those equations nor uses them to predict admission.

Bitmap, JVM, array-header, container, allocator, driver, texture, gralloc, and platform overhead is opaque. It
is disclosed or measured, never estimated into a runtime selector or promised bound.

## Negative boundary

The Framework and storage implementation contains none of the following:

- a per-frame Bitmap, full-frame conversion array, second raw carrier, or speculative Framework owner;
- a same-frame Native-to-Framework retry or fallback decision made by the JPEG role;
- a chunk-size tuning constant, arbitrary encoded-size cap, predictive preallocation, or free-memory gate;
- prefix recopy during `OutputStream` growth or more than one new backing range for one write;
- empty, partial, failed, stale, uncommitted, mutable, or native-backed publication;
- a concatenated persistent JPEG copy, generic buffer pool, second payload representation, or per-payload native
  cleanup handle;
- a second production/latest/displaced/lease store or Delivery-owned payload backing;
- byte-content logging, diagnostic exposure, zeroization guarantee, GC-timing guarantee, or managed physical-
  reclamation claim; or
- replacement or reuse inferred from cancellation, timeout, terminal state, queue state, reference drop, or a
  producer/recycle call that has not actually returned.

## Official platform references

- [Bitmap](https://developer.android.com/reference/android/graphics/Bitmap)
- [Bitmap.Config](https://developer.android.com/reference/android/graphics/Bitmap.Config)
- [Bitmap.createBitmap](https://developer.android.com/reference/android/graphics/Bitmap#createBitmap(int,int,android.graphics.Bitmap.Config))
- [Bitmap.copyPixelsFromBuffer](https://developer.android.com/reference/android/graphics/Bitmap#copyPixelsFromBuffer(java.nio.Buffer))
- [Bitmap.setPixels](<https://developer.android.com/reference/android/graphics/Bitmap#setPixels(int[],int,int,int,int,int,int)>)
- [Bitmap.compress](https://developer.android.com/reference/android/graphics/Bitmap#compress(android.graphics.Bitmap.CompressFormat,int,java.io.OutputStream))
- [OutputStream](https://developer.android.com/reference/java/io/OutputStream)
- [ByteBuffer](https://developer.android.com/reference/java/nio/ByteBuffer)
