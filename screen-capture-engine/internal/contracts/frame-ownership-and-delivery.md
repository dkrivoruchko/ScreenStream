# Frame ownership and delivery contract

This contract joins immutable Storage values to Delivery's borrowed callback view, separating byte ownership from callback progress and Session admission. The [image pipeline](image-pipeline.md) defines the represented image; the [native ABI](native-abi.md) defines Native transfer.

## Immutable segmented storage

An encoded payload is a positive byte count backed by one or more nonempty managed `ByteArray` segments. Encoding's commit seam adopts the exact segment graph; after successful transfer, producer references and mutable views are gone. Storage validates nonempty segments, checked cumulative length, and exact equality between segment sum and `byteCount`. It does not flatten, pool, mutate, expose an iterator, or promise reclamation timing.

`PublishedFrame` retains one immutable payload, one immutable effective-parameter snapshot, a positive Session-local sequence, and a nonnegative elapsed-realtime timestamp. Cached-first retains that exact frame and metadata. A repeat creates new sequence/timestamp metadata over the same payload and performs no capture, encode, or payload copy. Delivery retains the exact published frame while its callback borrow can still be used, including an entered callback that never returns.

## Borrowed callback frame

Delivery creates one facade over the retained frame. At callback entry it records the callback thread and opens the borrow. Every property read and every copy operation rechecks both the open interval and exact thread before reaching the immutable frame/payload. The borrow is revoked on every actual callback exit, before fallible fact or release work. Wrong-thread and post-callback access therefore fail with `IllegalStateException`; a nonreturning callback keeps its frame roots and has no timeout-based release. After an abnormal callback exit revokes the borrow, physical occupancy may remain unresolved, but this contract promises neither continued payload retention nor reclamation timing.

Within the open borrow, `byteCount`, `sequence`, `timestampElapsedRealtimeNanos`, and `effectiveParameters` are metadata reads. `copyTo(destination, offset)` checks the complete destination range before its first write, then copies segments in order and returns the exact byte count. Invalid ranges leave the destination unchanged and throw `IndexOutOfBoundsException`. `toByteArray()` creates one exact caller-owned array. JPEG bytes retained beyond the callback must be caller-owned copies. Metadata values read during the callback are immutable; retaining those values does not extend access to the borrowed facade. Do not overwrite or reuse a copied destination until its asynchronous consumers have finished with it.

## One occupancy and the delivery seam

The physical Delivery owner has one queue-less occupancy: at most one admitted or executing handoff exists. Admission installs the callback, immutable frame, borrow, and token atomically before dispatch. Dispatch acceptance proves neither entry nor return; a task may enter promptly after acceptance resolves on its worker, later, or never, but never reentrantly on the submitting thread. Definite dispatch rejection revokes the borrow and clears the handoff without synthetic callback, retry, inline execution, or a second occupancy record.

Session correlates physical tokens and facts by exact registration/handoff identity. A queued handoff cut off before entry becomes inert; an entered handoff is never interrupted. Physical Delivery creates callback-failure and closed facts but does not interpret their Session consequences. Session admits only exact-current facts for accounting, diagnostics, delivery admission, and terminal selection. The [registration completion contract](../components/delivery-observation.md#consumer-replacement-and-unregister) retains callback-exit evidence independently of Session termination without weakening frame-borrow or exact-return requirements.

Registration replacement, drop accounting, pause admission, and terminal retirement belong to [Delivery and observation](../components/delivery-observation.md).

## Related contracts

- [Public frame handling](../../docs/usage.md#work-with-jpeg-frames)
- [Public bounded delivery](../../docs/architecture.md#frame-ownership-and-bounded-delivery)
- [Delivery and observation component](../components/delivery-observation.md)
