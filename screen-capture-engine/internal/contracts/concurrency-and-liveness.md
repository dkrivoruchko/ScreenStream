# Concurrency and liveness

The engine separates semantic serialization from physical execution. This contract explains what may run where, which evidence establishes currentness or completion, and where progress is intentionally best effort. Component ownership is summarized in [Internal architecture](../architecture/overview.md); representative transitions are in [Runtime flows](../architecture/runtime.md).

## Execution lanes and ownership

- Caller threads perform bounded validation, admission, and snapshot access. Heavy Capture, Encoding, callback, and cleanup work does not run on the caller thread.
- Bootstrap's potentially blocking lane-start and Looper-acquisition prefix runs on the shared non-inline worker.
- The Control `Handler` lane enters Coordinator turns. `SessionControlExecutor` owns only Handler mechanics; it owns no lifecycle, currentness, plan, schedule, Stats, or publication state.
- The Capture `Handler` lane serializes projection, target, EGL/GLES, readback, and Capture retirement work.
- Encoding and Delivery use the shared non-inline worker through leaf-owned queue-less `SerialTaskSlot` instances.
- Delayed startup, pacing, and repeat tasks are scheduling mechanisms. Their semantic identities remain owned by Lifecycle or Production.

`MediaProjection.Callback` is hosted on the Control Handler but remains a Capture platform callback. Its entry is not a Coordinator turn and cannot directly change Session semantics; it emits typed Capture evidence instead.

## Session gates

Coordinator owns two private locks with one allowed nesting order:

```text
publicationGate -> sessionGate -> Metrics scoped snapshot gate
```

`publicationGate` orders operation admission and publication reservations. `sessionGate` protects shared admission, currentness, terminal contenders, the coalesced Control request, and Link correlation slots. Metrics never acquires a Session lock or calls Session while holding its private gate. Coordinator may nest only Metrics' bounded immutable snapshot read under `sessionGate`.

No path holding `sessionGate` may acquire `publicationGate`. Neither Session gate encloses a clock read, Android or codec call, callback, dispatch or Handler post, payload copy, Flow assignment, cleanup, blocking, or waiting. JVM monitor release/acquire provides visibility between the locked snapshot/commit and unlocked effect phases.

Ordinary State and Stats assignment uses one exact reservation. Coordinator commits semantic state and installs the reservation under both gates, assigns the complete value unlocked, then reacquires the same lock order to settle the exact reservation. At most one ordinary publication is in flight, so later publications cannot overtake it. Final Stats and terminal State use the lock-free terminal suffix only after the irreversible terminal claim.

## Currentness

Completion is not currentness. A returned leaf result must first match its exact Link request or token; Coordinator then compares all semantic evidence required by that operation:

- owning component identity;
- exact request, return-port, snapshot, production-record, or handoff-token identity;
- `configRevision` and, for delivery, `registrationId`;
- current desired/applied plan, owner health, and admission state; and
- the candidate generation or reservation where applicable.

Candidates are immutable provisional evidence, not authority. A commit revalidates them immediately before changing owner state. Equal values do not substitute for object identity at request/settlement boundaries. A stale result can still contribute only the explicitly defined settled accounting or Session-wide health evidence; otherwise it is discarded without successor work.

## Bounded ingress and coalesced Control

Every ingress that requires deferred reconciliation first makes its bounded latest fact or intent durable, marks Coordinator dirty, and requests the sole immediate Control wake after releasing synchronization. A pending or entered wake absorbs further writes. Each Control turn consumes a bounded snapshot; a racing ingress is either included or leaves exactly one successor dirty.

The engine does not build a general event queue. Examples of bounded state include:

- latest desired parameters rather than one work item per update;
- one current immutable Metrics snapshot and attachment lifecycle rather than a queue of source callbacks;
- latest captured-content resize and visibility facts;
- one source-opportunity bit rather than a queue of source frames;
- one pending/fact slot for each bounded Capture or Encoding operation;
- one callback-failure fact and one staged/ready `Closed` fact for the current handoff;
- one current pacing wake and one current repeat wake; and
- one in-flight ordinary publication.

The `Closed` delivery fact has two physical phases. The Link stages the exact fact before it is consumable. After Delivery releases its `current` handoff, the retained stage marks that same fact ready and requests Control. Terminal freeze may detach a staged fact; a late `ready()` then becomes cleanup-only and emits no wake.

## One-production and delivery backpressure

Only one fresh production may be materialized across record allocation, read construction, in-flight Capture read, Encoding input loan and transaction, and unpublished encoded output. The source opportunity remains latched to that production until it clears. No timeout, cancellation, terminal State, or reference drop can fabricate a Capture return or reuse the loan.

Delivery has one unresolved semantic offer and one physical handoff. A later eligible delivery while either is busy is counted rather than queued. Callback entry may race the offer call's return because the handoff token and correlation are installed before the outward submission. The same exact token carries callback failure and closure facts.

Unregister closes new delivery first. `CutoffBeforeEntry` proves the callback cannot enter and can settle semantic unregister without waiting for the inert task to release. `Entered` requires the exact `Closed` fact unless terminal resolution wins. `NoHandoff` is not completion evidence because an unlocked offer call may still be in progress. Self-unregister is rejected before mutation.

## Dispatch, callbacks, and settlement

A Runtime or Handler submission result distinguishes only accepted from definitely rejected. Android's [`Handler.post()`](https://developer.android.com/reference/kotlin/android/os/Handler#post) contract does not make queue acceptance a task-entry or progress receipt. The exact runnable/root is installed before submission can expose entry. Definite rejection releases only the exact never-entered request; accepted work remains rooted until real entry and owner-defined release.

`SerialTaskSlot` allows one leaf-owned attempt. Same-thread reentrant dispatch is a contract violation. A normally returning task releases the exact slot and may trigger its release callback. A throwing or nonreturning task does not fabricate release or authorize a successor.

Leaf owners settle their private resources and release private synchronization before invoking typed Session return ports. Delivery revokes a borrowed frame on every real callback exit, then stages closure. Capture read and Encoding loan settlement preserve exact identity even across terminal detach. Queue state, elapsed time, cancellation, terminal State, and garbage collection are never substitutes for a real return.

## Progress limits

Capture and delivery are best effort, as described in the public [practical limits](../../README.md#practical-limits). The internal liveness model is intentionally no stronger:

- accepted dispatch or Handler work may enter later or never enter;
- an entered platform, codec, callback, or cleanup call may return later or never return;
- startup expiry is selected only when a current arbitration actually enters and samples the deadline;
- delayed scheduling failure or exceptional completion does not invent task entry; and
- [`HandlerThread.quitSafely()`](https://developer.android.com/reference/kotlin/android/os/HandlerThread#quitsafely) is a quit request, not a join, thread-termination receipt, or resource-cleanup receipt.

There is no watchdog, polling loop, inline Control fallback, replacement lane, emergency publisher, callback interruption, or fabricated timeout settlement. If the Control lane or its accepted wake never enters, startup, terminal publication, or waiter completion may remain stranded. These limits preserve ownership truth: lack of progress never becomes false evidence that work completed.
