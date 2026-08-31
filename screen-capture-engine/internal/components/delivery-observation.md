# Delivery and observation

Delivery and Observation are separate per-session components. Delivery performs one physical application-callback handoff at a time; Observation publishes already-selected lifecycle, statistics, and diagnostic values. Callback progress never gates Flow publication, and collector progress never gates callback admission or cleanup. Public behavior is described in [Frame ownership and bounded delivery](../../docs/architecture.md#frame-ownership-and-bounded-delivery) and [Observation model](../../docs/architecture.md#observation-model). The exact payload/borrow contract is in [Frame ownership and delivery](../contracts/frame-ownership-and-delivery.md), and the surrounding component graph is in the [internal architecture overview](../architecture/overview.md).

## Delivery responsibility and owned roots

`DeliveryOwner` is the sole physical callback authority. It owns one queue-less handoff, its non-inline task, callback, immutable `PublishedFrame`, borrowed public facade, entry/return state, and every root required by a late or nonreturning callback. There is at most one admitted or executing handoff. A busy consumer drops a later delivery at the session policy layer; Delivery never queues it.

The session owns consumer registration, cached-first eligibility, semantic offer admission, unregister waiters, drop accounting, and terminal settlement. `SessionDeliveryLink` carries bounded identity correlation between one pre-minted `DeliveryHandoffToken`, its offer return, and physical facts. Delivery reports facts but never decides whether they remain current or which public counter changes.

Offer admission installs the complete handoff under Delivery's private gate before dispatch. Dispatch is non-inline, but acceptance proves neither entry nor progress. Rejection revokes the unopened borrow and clears the handoff without fabricating callback or closure facts. Pause and reconfiguration stop new semantic offers but do not revoke an already-admitted immutable frame.

## Borrowed frame lifetime

On callback entry, Delivery records the exact executing thread and opens one `EncodedImageFrame` borrow over the retained immutable frame. It revokes that borrow in a nonthrowing suffix on every actual callback exit, before fallible fact publication. Property access, copy behavior, wrong-thread rejection, and caller-owned lifetime are defined by [Frame ownership and delivery](../contracts/frame-ownership-and-delivery.md#borrowed-callback-frame) and the public [copy guidance](../../docs/usage.md#choose-how-to-copy-jpeg-data).

Callback `Exception`s produce at most one callback-failure fact after borrow revocation. Uncontained throwables still revoke the borrow but do not fabricate task release or closure. A callback that never returns keeps its callback, frame, payload, and serial occupancy rooted; elapsed time and terminal state are not return evidence.

## Closure handoff and queue-less progress

After application code returns, Delivery records one immutable `Closed` outcome. When the serial task releases, Delivery first stages that exact fact in `SessionDeliveryLink`. Only a normal stage return allows it to clear the physical `current`; it then marks the fact ready and requests Control work. Control can consume only ready closure.

This two-phase stage/ready protocol prevents the session from admitting a successor while Delivery still physically owns the previous handoff. A staging failure retains the current handoff. A ready/wake failure may leave the physical slot free while the staged fact remains rooted; neither path retries or invents a receipt.

## Consumer replacement and unregister

Only one registration exists at a time. Registering a replacement is legal after the prior registration's unregister settlement, not merely after clearing an app reference. The session may ask Delivery to cut off the exact registration's handoff and interprets only identity-matched evidence:

| Evidence | What it proves |
| --- | --- |
| `NoHandoff` | No matching physical handoff was visible at that instant; an offer call may still be in flight. |
| `CutoffBeforeEntry` | The queued callback cannot enter; task release may still be pending. |
| `Entered` | Callback entry occurred; return remains unproved. |
| ready `Closed` | The exact handoff reached its physical return suffix. |

An ordinary unregister may complete from exact pre-entry cutoff or exact closure. If cutoff observes no handoff while the offer call is unlocked, session state waits for that offer return and may issue one identity-matched successor cutoff. An entered callback is never interrupted. Self-unregister is recognized from the exact entered callback thread so session policy can avoid waiting on itself while retaining authoritative settlement.

Terminal handling uses a stronger entry fence and a weaker completion promise. Before terminal claim, Session calls Delivery's idempotent retirement fence outside session locks. Normal return prevents any queued callback from later entering. Terminal claim then freezes link correlation and logically detaches the registration, but terminal publication does not wait for callback return, task release, or `Closed`. Any real late return is owner-local cleanup only and cannot reopen registration or delivery admission. The shared terminal distinction is defined in [Failures and terminal semantics](../contracts/failures-and-terminal-semantics.md).

## Observation responsibility

`SessionObservationPublisher` constructs the public [`StateFlow`](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/-state-flow/) facades for `ScreenCaptureState` and `ScreenCaptureStats` and the [`SharedFlow`](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/-shared-flow/) facade for `ScreenCaptureDiagnosticEvent` once and retains their identity for the session lifetime. Session supplies complete immutable state and statistics values. Observation does not derive fields, accumulate counters, read revisions, choose transitions, or coordinate publication transactions.

State and Stats are separate equality-conflated flows. Each assigned value is coherent, but their latest values do not form a cross-flow atomic snapshot and collector resumption is not a publication receipt. Collector presence, speed, cancellation, or reentrancy cannot authorize session work.

Statistics are accumulated by session production and delivery policy, then published as complete snapshots. In particular, Delivery reports physical callback facts; Session decides whether the exact fact increments busy or callback-failure drops. Observation only assigns the resulting `ScreenCaptureStats`.

## Diagnostics and terminal publication

Diagnostics use a stable hot [`SharedFlow`](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/-shared-flow/) with no replay, bounded extra capacity, and oldest-item loss under overflow. A short private gate reserves the next positive session-local sequence, while wall-clock sampling, immutable event construction, and [`MutableSharedFlow.tryEmit`](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/-mutable-shared-flow/try-emit.html) occur outside that gate. Optional absence, loss with no subscriber (`replay = 0`), oldest-item eviction, silent sequence exhaustion, or a caught ordinary `Exception` are best-effort outcomes: they do not affect State or cleanup and are not retried. `Error` and other non-`Exception` throwables follow the ordinary propagation boundary.

After irreversible terminal claim, Observation receives already-built final Stats, an optional diagnostic request, and terminal State. It invokes them in this order:

```text
final Stats -> best-effort terminal diagnostic -> terminal State
```

An ordinary diagnostic `Exception` cannot block terminal State. The ordering is assignment invocation order only; it does not promise cross-flow collector order or collector progress. Observation owns no Flow close, terminal waiter, alternate publisher, or cleanup continuation.

## Cross-component interaction

Session supplies Delivery one exact immutable `PublishedFrame` and interprets its identity-matched physical facts; Delivery neither mutates frame storage nor performs Stats or lifecycle policy. Observation independently publishes the complete values Session selects. Payload lifetime and callback ownership are canonical in [Frame ownership and delivery](../contracts/frame-ownership-and-delivery.md), while queue-less scheduling, accepted-but-not-returned liveness, and the end-to-end sequence are in [Concurrency and liveness](../contracts/concurrency-and-liveness.md) and [Runtime flows](../architecture/runtime.md#fresh-production-and-delivery).
