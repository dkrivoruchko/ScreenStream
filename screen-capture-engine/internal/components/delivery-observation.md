# Delivery and observation

Delivery performs one physical callback handoff at a time; Observation publishes Session-selected values. Publication requires no callback-return or collector acknowledgment, but inline collectors can delay assignment; see [Observation responsibility](#observation-responsibility). Public behavior is in [Frame ownership and bounded delivery](../../docs/architecture.md#frame-ownership-and-bounded-delivery) and [Observation model](../../docs/architecture.md#observation-model).

## Delivery responsibility and owned roots

`DeliveryOwner` is the sole physical callback authority. It owns one queue-less handoff, its non-inline task, callback, borrowed public facade, entry/return state, and the immutable `PublishedFrame` roots required while that borrow can still be used. There is at most one admitted or executing handoff. A busy consumer drops a later delivery at the session policy layer; Delivery never queues it.

The session owns consumer registration admission, cached-first eligibility, semantic offer admission, drop accounting, and terminal selection. Each registration also owns a small gated completion record. Session Delivery reserves the exact `DeliveryHandoffToken` and installs its completion endpoint before any unlocked physical offer; the record retains only that token and callback-lifetime evidence, not callback or frame roots. The registration retains its own completion responsibility across Session termination and cancellation of individual waits. `SessionDeliveryLink` carries bounded identity correlation between one pre-minted token, its offer return, and physical facts. Delivery reports facts but never decides whether they remain current for Session accounting or which public counter changes.

Offer admission installs the complete handoff under Delivery's private gate before dispatch. Dispatch is non-inline, but acceptance proves neither entry nor progress. Rejection revokes the unopened borrow and clears the handoff without fabricating callback or closure facts. Pause and reconfiguration stop new semantic offers but do not revoke an already-admitted immutable frame.

## Borrowed frame lifetime

On callback entry, Delivery records the exact executing thread and opens one `EncodedImageFrame` borrow over the retained immutable frame. For normal or caught-`Exception` callback completion, it revokes that borrow, clears the callback and marks the exact handoff `Returned` with its callback thread cleared, then records durable callback-exit evidence before any fallible fact publication. An escaping callback `Error` still revokes the borrow but does not reach this ordinary proof suffix. Access checks, copying, and caller-owned lifetime follow [Frame ownership and delivery](../contracts/frame-ownership-and-delivery.md#borrowed-callback-frame).

Callback `Exception`s produce at most one callback-failure fact after borrow revocation, subject to the [boundary-specific cancellation rule](../contracts/failures-and-terminal-semantics.md#cancellation-and-operation-outcomes). Uncontained throwables still revoke the borrow but do not fabricate task release or closure. A callback that never returns keeps its callback, frame, payload, and serial occupancy rooted; elapsed time and terminal state are not return evidence. After an actual abnormal exit, unresolved physical occupancy is distinct from payload retention: neither continued payload retention nor reclamation timing is promised once the borrow is revoked.

## Closure handoff and queue-less progress

After application code returns, Delivery records one immutable `Closed` outcome. For Session accounting, serial-task release stages that exact fact in `SessionDeliveryLink` before clearing the physical `current`; the fact becomes ready for Control only after that handoff is released. Control can consume only ready closure.

This two-phase stage/ready protocol prevents the session from admitting a successor while Delivery still physically owns the previous handoff. A staging failure retains the current handoff. A ready/wake failure may leave the physical slot free while the staged fact remains rooted; neither path retries or invents a receipt. Session-facing retirement must not discard the only callback-exit evidence required by the registration's independent completion. Establishing that completion does not require reopening a frozen Session Link or admitting successor work.

## Consumer replacement and unregister

`unregister()` closes new delivery and remains a cancellable wait for the exact registration's callback completion, independently of Session terminal state. An app may need this completion before closing a socket, encoder, or buffer still used by the callback. Session stop or failure does not itself end that wait exceptionally or make an entered callback count as returned. Successful completion proves that no callback for the registration can newly enter and that any entered callback has exited. Semantic registration detach may happen as soon as exact cutoff or callback-exit proof arrives, while the physical handoff remains occupied until its existing stage/release/ready path. Neither `stop()` nor terminal State waits for this completion.

Cancelling a caller's wait does not reopen delivery, cancel the underlying completion record, or prove successful drain. A later wait can still observe actual completion. A nonreturning callback can keep that wait pending without synthetic success. Completion ownership survives Session freeze without retaining unrelated roots or reviving Session work.

Only one unresolved registration exists per session. While the session is nonterminal, registering a replacement is legal after the prior registration's unregister settlement, not merely after clearing an app reference. The session may ask Delivery to cut off the exact registration's handoff and interprets only identity-matched evidence:

| Evidence | What it proves |
| --- | --- |
| `NoHandoff` | No matching physical handoff was visible at that instant; an offer call may still be in flight. |
| `CutoffBeforeEntry` | The queued callback cannot enter; task release may still be pending. |
| `Entered` | Callback entry occurred; return remains unproved. |
| ready `Closed` | The exact handoff reached its physical return suffix. |

Unregister may complete from exact pre-entry cutoff or exact callback-exit evidence. If cutoff observes no handoff while the offer call is unlocked, completion waits for that offer return and any required identity-matched cutoff. An entered callback is never interrupted. Self-unregister is recognized from the exact entered callback thread and rejected before mutation so it cannot wait on itself.

If unregister begins while ordinary publication is in flight, Session defers the physical action and requests Control work. The next admitted `runControlTurn` calls `executePendingUnregisterAction`, which claims at most one exact action from `SessionDelivery.claimPendingUnregisterAction`: either complete immediately when no offer remains or request cutoff for the exact outstanding offer.

Before terminal claim, Session calls Delivery's idempotent retirement fence outside session locks. Normal return prevents queued callback entry. Claim freezes Session-facing correlation and admission while retaining registration completion; it waits for neither callback return, task release, nor `Closed`. A real late return or exact queued cutoff may settle the durable completion record without reopening admission or changing frozen State/Stats. See [Terminal is not cleanup](../contracts/failures-and-terminal-semantics.md#terminal-is-not-cleanup).

The [next-run boundary](../architecture/runtime.md#starting-another-run) permits callbacks from different sessions to overlap. Each new session has its own registration, and the host coordinates application-owned resources shared by those callbacks.

## Observation responsibility

`SessionObservationPublisher` constructs the public [`StateFlow`](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/-state-flow/) facades for `ScreenCaptureState` and `ScreenCaptureStats` and the [`SharedFlow`](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/-shared-flow/) facade for `ScreenCaptureDiagnosticEvent` once and retains their identity for the session lifetime. Session supplies complete immutable state and statistics values. Observation does not derive fields, accumulate counters, read revisions, choose transitions, or coordinate publication transactions.

State and Stats are separate equality-conflated flows. Each assigned value is coherent, but their latest values do not form a cross-flow atomic snapshot and collector resumption is not a publication receipt. Collector presence, speed, cancellation, or reentrancy cannot authorize session work.

[`StateFlow`](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/-state-flow/) resumes collectors outside its own lock, but an [unconfined dispatcher](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/-dispatchers/-unconfined.html) can execute collector code on the assigning thread. Blocking there can delay publication settlement, callback admission, and cleanup behind it. Keep collection handlers nonblocking, and do not synchronously block on Session work that needs the assigning operation to finish. Ordinary suspending reentry is distinct from such blocking; its safety depends on the operation's reservation and continuation path, not merely on Flow assignment being outside locks.

Session production and delivery policy accumulate Stats, including which exact Delivery facts increment busy or callback-failure drops. Observation assigns the complete snapshot.

## Diagnostics and terminal publication

Diagnostics use a stable hot [`SharedFlow`](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/-shared-flow/) with no replay, bounded extra capacity, and oldest-item loss under overflow. A short private gate reserves the next positive session-local sequence, while wall-clock sampling, immutable event construction, and [`MutableSharedFlow.tryEmit`](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/-mutable-shared-flow/try-emit.html) occur outside that gate. Optional absence, loss with no subscriber (`replay = 0`), oldest-item eviction, silent sequence exhaustion, or a caught ordinary `Exception` are best-effort outcomes: they do not affect State or cleanup and are not retried. `Error` and other non-`Exception` throwables follow the ordinary propagation boundary.

After irreversible terminal claim, Observation receives already-built final Stats, an optional diagnostic request, and terminal State. It invokes them in this order:

```text
final Stats -> best-effort terminal diagnostic -> terminal State
```

An ordinary diagnostic `Exception` cannot block terminal State. The ordering is assignment invocation order only; it does not promise cross-flow collector order or collector progress. Observation owns no Flow close, terminal waiter, alternate publisher, or cleanup continuation.

## Cross-component interaction

See [Frame ownership and delivery](../contracts/frame-ownership-and-delivery.md) for payload and borrow invariants, [Concurrency and liveness](../contracts/concurrency-and-liveness.md) for scheduling and progress, and [Runtime flows](../architecture/runtime.md#fresh-production-and-delivery) for the end-to-end handoff.
