# Frame ownership and delivery contract

This contract joins Storage's immutable values to Delivery's borrowed callback view. It deliberately separates byte ownership from callback progress and Session's semantic admission. Public callback rules remain in the [frame guidance](../../docs/usage.md#work-with-jpeg-frames) and [bounded-delivery architecture](../../docs/architecture.md#frame-ownership-and-bounded-delivery). The maintained physical seam is in the [delivery and observation component](../components/delivery-observation.md). The [image pipeline](image-pipeline.md) defines the immutable image represented by a frame, and Native transfer details are in the [native ABI](native-abi.md).

## Immutable segmented storage

An encoded payload is a positive byte count backed by one or more nonempty managed `ByteArray` segments. Encoding's commit seam adopts the exact segment graph; after successful transfer, producer references and mutable views are gone. Storage validates nonempty segments, checked cumulative length, and exact equality between segment sum and `byteCount`. It does not flatten, pool, mutate, expose an iterator, or promise reclamation timing.

`PublishedFrame` retains one immutable payload, one immutable effective-parameter snapshot, a positive Session-local sequence, and a nonnegative elapsed-realtime timestamp. Cached-first retains that exact frame and metadata. A repeat creates new sequence/timestamp metadata over the same payload and performs no capture, encode, or payload copy. Delivery retains the exact published frame for the whole unresolved handoff, including terminal or replacement races.

## Borrowed callback frame

Delivery creates one facade over the retained frame. At callback entry it records the callback thread and opens the borrow. Every property read and every copy operation rechecks both the open interval and exact thread before reaching the immutable frame/payload. The borrow is revoked on every actual callback exit, before fallible fact or release work. Wrong-thread and post-callback access therefore fail with `IllegalStateException`; a nonreturning callback keeps its frame roots and has no timeout-based release.

Within the open borrow, `byteCount`, `sequence`, `timestampElapsedRealtimeNanos`, and `effectiveParameters` are metadata reads. `copyTo(destination, offset)` checks the complete destination range before its first write, then copies segments in order and returns the exact byte count. Invalid ranges leave the destination unchanged and throw `IndexOutOfBoundsException`. `toByteArray()` creates one exact caller-owned array. Only those caller-owned copies may outlive the callback; a reusable destination must not be handed to asynchronous work until that work is finished.

## One occupancy and the delivery seam

The physical Delivery owner has one queue-less occupancy: at most one admitted or executing handoff exists. Admission installs the callback, immutable frame, borrow, and token atomically before dispatch. Dispatch acceptance proves neither entry nor return; a task may enter promptly after acceptance resolves on its worker, later, or never, but never reentrantly on the submitting thread. Definite dispatch rejection revokes the borrow and clears the handoff without synthetic callback, retry, inline execution, or a second occupancy record.

Session correlates physical tokens and facts by exact registration/handoff identity. A queued handoff cut off before entry becomes inert; an entered handoff is never interrupted. Physical Delivery creates callback-failure and closed facts but does not interpret them. Session admits only exact-current facts and owns their accounting, diagnostic, registration, unregister-settlement, and terminal consequences.

Registration replacement, unregister settlement, backpressure/drop accounting, pause admission, terminal cutoff, and late-return policy are Session decisions described with the maintained physical seam in the [delivery and observation component](../components/delivery-observation.md). This contract preserves only the cross-component invariants that the immutable frame and borrow must satisfy.

## Related contracts

- [Public frame handling](../../docs/usage.md#work-with-jpeg-frames)
- [Public bounded delivery](../../docs/architecture.md#frame-ownership-and-bounded-delivery)
- [Delivery and observation component](../components/delivery-observation.md)
