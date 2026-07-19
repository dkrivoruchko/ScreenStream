# Screen Capture Engine — Framework JPEG Domain

This file owns Framework Bitmap creation, raw-pixel transfer, Framework JPEG encoding, Bitmap lifetime, and
their domain-specific failures and cleanup. Shared ownership, settlement, deadline, cleanup, and privacy rules
remain in [03-shared-runtime.md](03-shared-runtime.md); controller, carrier, storage, and public behavior are
linked rather than repeated.

## Navigation

- [FJPEG-001](#fjpeg-001--source-and-file-boundary)
- [FJPEG-010](#fjpeg-010--typed-domain-boundary)
- [FJPEG-020](#fjpeg-020--installed-resources-and-reuse)
- [FJPEG-030](#fjpeg-030--combined-resource-creation)
- [FJPEG-031](#fjpeg-031--metadata-and-creation-outcome)
- [FJPEG-040](#fjpeg-040--exact-carrier-to-bitmap-transfer)
- [FJPEG-050](#fjpeg-050--framework-encode-and-transaction)
- [FJPEG-060](#fjpeg-060--bitmap-retirement-and-recycle)
- [FJPEG-070](#fjpeg-070--cleanup-cancellation-and-lane-ownership)
- [FJPEG-080](#fjpeg-080--efficiency-privacy-and-diagnostics)
- [FJPEG-090](#fjpeg-090--exact-operation-deadline-policy)
- [FJPEG-100](#fjpeg-100--executable-obligations)
- [FJPEG-110](#fjpeg-110--forbidden-alternatives)
- [Official platform references](#official-platform-references)

## FJPEG-001 — Source and file boundary

Canonical paths are in [router §6](01-authority-router.md#6-source-manifest).
`JPG:FrameworkJpegContracts.kt` owns Framework occurrences and evidence;
`JPG:FrameworkBitmapOwner.kt` owns cohesive Bitmap/scratch state and uses;
`JPG:FrameworkJpegExecution.kt` owns transfer/compress execution;
`JPG:FrameworkJpegCleanup.kt` owns recycle and returned-resource cleanup; and
`JPG:FrameworkJpegOwner.kt` is the sole mutable Framework facade. It consumes carrier interfaces from
`JPG:JpegRuntimeCore.kt` and the transaction interface from `INT:EncodedStorageOwner.kt`; those declarations do
not become Framework authority.

`FrameworkJpegOwner` contains the installed Bitmap owner, optional row scratch, actual Bitmap metadata and
transfer mode, one combined `FrameworkResourceCreationOccurrence`, one Framework encode occurrence per fresh
attempt, and one recycle occurrence per retired Bitmap. Subordinate files cannot mutate backend selection or
health independently and add no second settlement or resource-lifetime machine.

## FJPEG-010 — Typed domain boundary

| Direction | Typed fact or owner | Authority and use |
| --- | --- | --- |
| inbound | current nonterminal topology key, selected Framework backend, Native `Disabled`, checked `(Ow, Oh, R, B)`, and install/currentness decision | `CTRL-100`, `CTRL-110`, `CTRL-130`, `CTRL-300`; eligibility and final arbitration only |
| inbound | exact writable direct RGBA carrier lease over `[0, B)` and its Native/Managed carrier identity | `NJPEG-020`, `NJPEG-030`; the carrier remains owned by the JPEG runtime |
| inbound | one storage transaction with checked `OutputStream`, commit, abort, and immutable-payload result | `STORE-030`, `STORE-040`, `STORE-050`; Framework never publishes or owns a second encoded representation |
| inbound | requested JPEG quality, output image contract, State/Stats/drop and diagnostic vocabulary | `PROD-040`, `PROD-060`, `PROD-070`, `PROD-080` |
| inbound | generic occurrence, return cell, deadline, owner-bag, cleanup and quarantine mechanics | `CORE-OWN-1`, `CORE-SET-1` through `CORE-SET-3`, `CORE-CLEAN-1`, `CORE-CLEAN-2` |
| outbound | complete healthy `FrameworkJpegOwner` identity with exact shape, Bitmap, optional scratch, metadata, and transfer mode | `CTRL-110`, `CTRL-130`, `CTRL-300` may install or retain only this complete owner |
| outbound | typed creation, encode, transaction, and recycle facts plus their exact owners/residue | `CTRL-130`, `CTRL-300`, `CTRL-400`, `STORE-080`, `CORE-CLEAN-1`, `CORE-CLEAN-2` consume them without reinterpretation |
| outbound | mechanically committed immutable JPEG payload or one closed failure outcome | `STORE-050`/`STORE-070` own payload/role transitions; `CTRL-200`/`CTRL-300` alone own currentness and publication authority |

No raw carrier reference, Bitmap, scratch, transaction, or tentative bytes cross this boundary without their
owner/lease/occurrence. Native JPEG health and carrier allocation remain `NJPEG-020`, `NJPEG-030`, and
`NJPEG-090`; payload segmentation, transaction state, and copy ownership remain `STORE-030` through `STORE-090`.

## FJPEG-020 — Installed resources and reuse

Framework JPEG is the mandatory backend. One healthy installed owner holds exactly:

- one mutable, software, sRGB `Bitmap.Config.ARGB_8888` Bitmap of `(Ow, Oh)`;
- actual `width`, `height`, `rowBytes`, `byteCount`, config, mutability, and applicable color-space facts;
- the selected `TightCopy` or `RowConversion` mode;
- exactly one reusable `IntArray(Ow)` only for `RowConversion`;
- use/lease accounting that prevents recycle while copy or compress can still access the Bitmap.

Canonical carrier pixels are tight top-down RGBA with opaque alpha. `R = 4 * Ow` and `B = R * Oh` are checked
before creation or transfer. A healthy exact-shape owner, including its scratch when required by its installed
mode, retains referential identity across reconciliation. A shape-incompatible or unhealthy owner follows
`FJPEG-060`; replacement cannot overlap its unresolved physical Bitmap ownership. Target replacement alone
retains this owner when its output shape and requirements remain exact-compatible.

## FJPEG-030 — Combined resource creation

Exactly one `FrameworkResourceCreationOccurrence` may exist for a topology key. It is eligible only when all are
true: Session nonterminal; key current; Framework selected; Native `Disabled`; checked fixed shape available; no
healthy compatible installed owner; and no extant creation occurrence. `FrameworkOnly` and clean Native
ineligibility reach this point after carrier/backend selection. A safe runtime Native disable does not: its
switching frame ends once as `byFailure`, performs no Framework allocation or retry, and later reconciliation
stays `Running(Suspended(Reconfiguring))` until a complete Framework owner is installed.

Before submission, the occurrence owns its generic cells, `FJPEG-090` deadline, immutable key/backend/shape,
fixed metadata/mode evidence, partial owner bag, and recycle authority. The bag has precreated Bitmap and scratch
slots. One Runnable on the prestarted Session-owned JPEG endpoint in
[CORE-EXEC-1](03-shared-runtime.md#core-exec-1--private-execute-endpoints) performs this exact sequence:

1. enter once and call `Bitmap.createBitmap(Ow, Oh, Bitmap.Config.ARGB_8888)` exactly once;
2. adopt the returned Bitmap immediately, including the interval before its bag slot is published;
3. inspect and validate the applicable actual metadata under `FJPEG-031`;
4. select transfer mode from actual metadata and the fixed carrier shape;
5. create exactly one `IntArray(Ow)` only when `RowConversion` is selected;
6. publish the complete result using only precreated fields and references.

The one finite interval begins immediately before `Bitmap.createBitmap` and spans immediate adoption, metadata
inspection, optional scratch construction, and complete allocation-free return publication. Creation is separate
from Native preparation, encode, and recycle; none shares or extends another occurrence's deadline.

### FJPEG-031 — Metadata and creation outcome

All supported APIs inspect actual width, height, `rowBytes`, `byteCount`, config, and mutability. On API 24–25,
production performs zero `getColorSpace` calls and does not read or compare `Bitmap.Config.HARDWARE`; the exact
factory contract plus actual config/mutability supplies the legacy sRGB/software proof. Every API-26-only access
is dominated by `SDK_INT >= 26`; API 26+ also requires actual sRGB and rejects actual hardware storage. Applicable
metadata inconsistent with the requested mutable software sRGB `ARGB_8888` Bitmap is malformed evidence.

| Settled creation result | Exact disposition |
| --- | --- |
| timely, current, complete valid Bitmap/mode/scratch | atomically move every owner/fact from the bag into one complete `FrameworkJpegOwner`, clear moved bag slots, then permit controller installation/`Active` |
| current direct Bitmap or scratch allocation `OutOfMemoryError`, or checked creation-size denial | terminal `ResourceExhausted`; creation itself records no frame drop |
| current malformed evidence, unexpected non-OOM exception, rejection, expiry, or ownership uncertainty | terminal `InternalFailure` |
| timely safe stale success | install nothing; recycle its Bitmap once after settlement and logically drop scratch |
| timely safe stale failure or coherent allocation OOM | change no lifecycle result; settle only the occurrence's exact owners |
| late result after expiry/retirement/terminal transfer | cleanup-only; it cannot install, publish, start another frame, or change backend health |
| terminal before entry | cancel safely with zero factory/scratch call |
| terminal after entry or nonreturn | transfer the intact occurrence, worker, writable cells, and partial bag; no new creation is originated |

Ownership ambiguity remains `InternalFailure` even when the key became stale. A returned but noninstalled Bitmap
enters exactly one `FJPEG-060` recycle after creation work and Bitmap uses settle. The final scratch reference is
logically dropped only after creation work and occurrence settlement; it has no physical-free receipt.

## FJPEG-040 — Exact carrier-to-Bitmap transfer

One private synchronous function accepts either legal carrier mode through the same exact `[0, B)` lease. It
retains no carrier reference and creates no adapter owner, availability decision, operation state, cleanup root,
or return resource. The caller resets the view to `position = 0` and `limit = B`; on normal return or throw it
releases the carrier lease in `finally`. If the call does not return, that lease remains with the exact encode
occurrence because carrier use has not mechanically ended. Bitmap use remains held through encode settlement.

| Selected installed mode | Required calls and predicates |
| --- | --- |
| `TightCopy` | Require checked `bitmap.rowBytes == R`, `bitmap.byteCount == B`, and view `position == 0`, `limit == B`, `remaining == B`; call `copyPixelsFromBuffer` exactly once. Raw byte order is independent of `ByteBuffer.order()` on the supported Android little-endian ABIs; no `IntBuffer` view exists. |
| `RowConversion` | Reuse the installed `IntArray(Ow)`; for each row, pack each RGBA pixel as logical Kotlin `0xFFRRGGBB`, then call `setPixels(row, 0, Ow, 0, y, Ow, 1)` exactly once. There is no full-frame temporary array. |

Mode is selected from the built Bitmap's actual metadata, not predicted from requested shape. The mandatory
diagnostic records actual `rowBytes`, expected `R`, and selected mode; an actual mode change emits the
`FrameworkJpeg` `RuntimeModeChanged` observation defined by `PROD-080`. Transfer failure cannot select another
mode or retry the frame.

## FJPEG-050 — Framework encode and transaction

Each admitted fresh Framework attempt precreates one finite encode occurrence with the current key, owner/use
facts, exact carrier lease, transaction/tentative-byte owner, fixed return evidence, and cleanup bag. Its one
non-suspending JPEG-IO Runnable enters once, transfers under `FJPEG-040`, calls
`Bitmap.compress(Bitmap.CompressFormat.JPEG, quality, transaction.outputStream)` exactly once, settles the transaction, and publishes
only precreated result fields. The Framework owner and Bitmap use remain held until the entered operation can no
longer access them. Partial or failed bytes never publish or replace cache; no outcome retries the same frame.

| First authoritative evidence after ownership/contract checks | Exact disposition |
| --- | --- |
| `compress` true and checked transaction commit succeeds | mechanically successful encode; offer the immutable payload to the ordinary controller currentness fence |
| `compress` false | abort tentative bytes; end this fresh attempt once as `byFailure`; keep Framework healthy for later frames |
| direct `OutOfMemoryError` from `Bitmap.compress` or an entered sink operation, or unrepresentable checked cumulative sink length | abort; record this attempt once as `byFailure`; terminal `ResourceExhausted` |
| malformed sink offset/count/cumulative length or transaction evidence, transfer/encoder contract violation, or unexpected non-OOM exception | abort; record once as `byFailure`; terminal `InternalFailure` |
| entered transfer/compress/sink nonreturn or uncertain pixel/sink ownership | record once as `byFailure`; terminal `InternalFailure`; retain the exact occurrence and still-possibly-used owners/tentative bytes |
| successful safe stale mechanical result | retire its unpublished payload through `STORE-070`; count the mechanical encode as specified by `PROD-070`, but publish no frame |
| failure or result after deadline/terminal transfer | cleanup-only; it cannot change Stats, lifecycle, cache, backend health, or publication |

Malformed/ambiguous evidence precedes OOM evidence, which precedes the returned Boolean. `OutOfMemoryError` has
ordinary `ResourceExhausted` meaning only at the enumerated creation, scratch, compress, and entered-sink
boundaries above. A non-enumerated OOM or any other fatal JVM `Error` from creation, transfer, encode, transaction,
or recycle is not converted into a frame failure or fallback: outer runtime handling preserves its fatal
process/thread semantics and roots whatever exact occurrence residue remains.

## FJPEG-060 — Bitmap retirement and recycle

Same-shape healthy reuse creates neither creation nor recycle work. Before incompatible replacement or terminal
retirement, every Bitmap copy/compress use and lease must mechanically settle. The exact Bitmap owner then moves
once into one generic recycle occurrence on the JPEG endpoint; that occurrence calls `Bitmap.recycle()` exactly
once.

| Recycle boundary | Required result |
| --- | --- |
| incompatible preterminal owner | use the finite `FJPEG-090` interval; only timely normal return is the physical recycle receipt; then drop the reference, freshly recheck nonterminal/current eligibility, and only then permit one replacement creation |
| terminal-origin or terminal-converted owner | use the same occurrence and owner bag without a watchdog; normal return permits reference drop |
| scheduler rejection, unexpected non-OOM exception, expiry, or nonreturn | retain the exact Bitmap and occurrence under cleanup/quarantine; authorize no replacement, duplicate call, or retry |
| late normal return | reduce only the matching cleanup child; never revive Session or authorize active replacement |

Scratch retirement is a post-settlement logical reference drop, not a physical receipt. Recycle has no
Bitmap-specific state machine, second executor, or private constant.

## FJPEG-070 — Cleanup, cancellation, and lane ownership

Creation, encode, and recycle use the single prestarted per-Session JPEG endpoint owned by `NJPEG-020`. Each is a
separate Runnable with its own precreated occurrence and cells and obeys the one-outstanding-ticket rule.
Terminal closes active admission; the endpoint then follows one orderly shutdown and actual `terminated()`
receipt under `CORE-EXEC-1`.

| Unresolved work | Exact retained cleanup state |
| --- | --- |
| creation | worker, writable cells, key/backend/shape, partial Bitmap/scratch bag, mode metadata, recycle authority |
| transfer/encode | exact operation, still-live carrier/Bitmap uses, transaction and tentative bytes; any lease already mechanically released is absent |
| recycle | exact Bitmap owner, one-shot call authority, writable return cell and dependencies |

A nonreturn roots only the Session-owned occurrence, JPEG endpoint/thread, ticket, and resources it may still use,
not an unrelated runtime. Terminal conversion retires a finite deadline without inventing a return. Late facts reduce only matching
residue under `CORE-CLEAN-2`; independent cleanup roots and other Sessions continue.

## FJPEG-080 — Efficiency, privacy, and diagnostics

At most one installed or creation-owned exact-shape Bitmap exists, plus one width-sized scratch only for the
portable path. There is no full-frame transfer array, second raw-frame-sized copy, predictive Bitmap/allocator
accounting, speculative owner, or second healthy Framework topology. Opaque Bitmap/JVM/platform overhead is
disclosed but not estimated. Actual creation and storage allocation results decide feasibility.

Raw carrier pixels, mutable Bitmap content, and tentative JPEG bytes remain behind their exact owners and leases.
Only a committed immutable `STORE-050` payload transferred under `STORE-070` can reach delivery; stale, partial,
failed, or unleased data cannot.

Initial Running includes the actual Framework transfer mode in `RuntimeProfile`. Actual transfer-mode change uses
`RuntimeModeChanged`; creation, encode, preterminal recycle, and managed-carrier-allocation timeouts use
`CapabilityCheck` source `FrameworkJpeg`, and a winning timeout's `SessionTerminal` reuses the same cause identity.
Diagnostics never control retry, fallback, timeout, cleanup, or State.

## FJPEG-090 — Exact operation deadline policy

Combined creation, each Framework encode, and each preterminal Bitmap recycle independently use the sole JPEG
duration owned by [NJPEG-CONST-001](10-domain-native-jpeg.md#njpeg-const-001--jpeg-entered-operation-interval).
Consecutive stages do not form a compound deadline or public SLA.
Terminal-origin/converted recycle has no watchdog. Expiry is `InternalFailure` for the active operation; a late
fact is cleanup-only, subject to prior terminal arbitration.

All deadline arithmetic, equality-at-expiry, return publication, rejection, terminal transfer, and late reduction
follow `CORE-SET-1` through `CORE-SET-3`. No additional Framework timeout, row-size tuning value, allocation
prediction, or retry count exists.

## FJPEG-100 — Executable obligations

Closed packet membership is in [router §5](01-authority-router.md#5-closed-implementation-packets); shared
closure/routing and test namespaces are in [Document 04](04-verification.md). The representative Framework
procedure uses real Direct readback, this domain's transfer/encoder, the `STORE-030` transaction, and real decode
against the sole shared
[`TEST-VECTOR-JPEG-01`](04-verification.md#test-vector-jpeg-01--shared-jpeg-64-x-48-oracle). Quality forwarding is
exact; Framework and Native output are not compared, and encoded size, quality monotonicity, performance, and
image score are not assertions. Exact Framework matrices remain below without restating common vector values.

| Test ID | Framework-specific obligations |
| --- | --- |
| `H-RC` | exact eligibility, complete-owner installation before Active, compatible identity reuse, recycle-to-fresh-recheck replacement, target-retention, A-to-B-to-A and safe Native-disable sequencing |
| `H-OS` | creation/encode/recycle occurrences at rejection, inline entry/return, `D-1`/`D`/`D+1`, empty-at-`D`, gate pauses, commit-before-signal, terminal transfer, late return, OOM/throw/malformed/nonreturn, and exact owner-bag cleanup |
| `H-PS` | transaction abort/commit, partial-byte nonpublication, storage allocation/copy failures, mechanical-success and currentness/Stats partition |
| `A-FJ` | real Bitmap/API-band metadata, both carrier modes, exact fast/portable call shapes, real `Bitmap.compress`, transaction, decode, quality forwarding and the shared JPEG oracle |
| `A-CL` | creation partial owners, held uses preventing recycle, nonreturn/quarantine isolation, logical scratch drop, exact late reduction and cross-Session progress |
| `H-OB` | Framework diagnostic source/label/cause identity and noncontrol behavior |

The creation matrix crosses Bitmap return with optional-scratch success/OOM/throw/malformed/nonreturn for
current, stale, expired, late, and terminal results. API cases are exactly 24, 25, 26, and 37: API 24–25 prove
zero API-26 metadata-helper invocation; API 26+ exercises the guarded color-space/HARDWARE branch. Transfer tests
assert one fast copy or exactly `Oh` row calls, never both, and both Native/Managed carrier leases. Recycle tests
hold each use open, require one normal receipt before replacement, and cover return-versus-terminal transfer.

## FJPEG-110 — Forbidden alternatives

- Framework Bitmap/scratch creation while Native remains `Enabled`, or on the safe switching frame.
- Same-frame Native-to-Framework retry, encode retry, creation retry, or recycle retry.
- A separate transfer adapter owner/lifecycle, carrier copy owner, full-frame `IntArray`, `IntBuffer`, or second
  raw-frame-sized buffer.
- Shape-predicted transfer mode, byte-order-based mode selection, or a device allowlist.
- Bitmap reuse after unresolved use, expiry, failed recycle, or ambiguous ownership.
- Treating cancellation, timeout, request success, managed reference drop, or GC timing as a recycle receipt.
- Partial/stale byte publication, fallback after mandatory Framework failure, or diagnostics as control authority.
- Predictive memory accounting, Bitmap-specific settlement state, extra runtime axes, or a new numeric constant.

## Official platform references

- [Bitmap](https://developer.android.com/reference/android/graphics/Bitmap)
- [Bitmap.Config](https://developer.android.com/reference/android/graphics/Bitmap.Config)
- [Bitmap.copyPixelsFromBuffer](https://developer.android.com/reference/android/graphics/Bitmap#copyPixelsFromBuffer(java.nio.Buffer))
- [Bitmap.setPixels](<https://developer.android.com/reference/android/graphics/Bitmap#setPixels(int[],int,int,int,int,int,int)>)
- [ColorSpace](https://developer.android.com/reference/android/graphics/ColorSpace)
