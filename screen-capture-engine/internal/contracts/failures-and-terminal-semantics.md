# Failures and terminal semantics

`ScreenCaptureProblem` defines stable failure semantics; throwable details and diagnostics are optional context. See [Failure and recovery guidance](../../docs/usage.md#handle-failures-and-recovery) for caller handling and [Runtime flows](../architecture/runtime.md#stop-and-terminal-transition) for terminal transitions.

## Cancellation and operation outcomes

If normal owner stop or projection stop wins before first Active, the pending start operation completes with `CancellationException`, without inventing a `ScreenCaptureProblem`. A real startup failure completes with `ScreenCaptureException` carrying its stable problem. Cancellation of the calling coroutine propagates as cancellation and is not converted to an engine failure. The stopped-operation case may throw cancellation while the caller's Job is active, as also occurs when [`Deferred.await()`](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/-deferred/await.html) observes a cancelled operation. Selected terminal State remains authoritative under the priority below.

At an engine coroutine cancellation boundary, preserve cancellation control flow and the cleanup responsibility already acquired. An unrelated plain consumer or Metrics-source callback throwing `CancellationException` is not by its type alone evidence that the engine or waiting caller's Job was cancelled; apply that callback boundary's exception policy without swallowing cancellation of an actual owning coroutine. `Observer.onFailure(cause)` remains opaque error-as-data. Catch and settlement follow the actual coroutine owner. The [`suspendCancellableCoroutine` cancellation and resource guidance](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/suspend-cancellable-coroutine.html) requires accounting for cancellation racing completion; it does not return an already-owned projection to the caller.

Session stop or failure does not cancel or fail independent [registration completion](../components/delivery-observation.md#consumer-replacement-and-unregister) merely because the Session ended. Cancelling a caller's wait is not successful callback drain.

## Stable problem mapping

| Problem | Internal semantic boundary |
| --- | --- |
| `InvalidRequest` | Current geometry and requested parameters cannot resolve a valid output plan. |
| `CaptureUnavailable` | Required Metrics or projection authority is unavailable, explicit source-unavailability evidence is current, or first-Active startup expiry is reached by an entered current arbitration. Mere absence of a source frame is not unavailability. |
| `ResourceExhausted` | A named deterministic capacity or required creation/allocation boundary safely denies the request. |
| `InternalFailure` | Platform, render/JPEG, dispatch, ownership, identity, arithmetic, or cleanup evidence is unsafe or inconsistent. |
| `UnsupportedColorSpace` | On API 33+, a current read reports exact Display P3 dataspace, rejected by the supported-input policy. This classification does not establish universal SDR/sRGB conversion or fidelity for other inputs. |

The mapping occurs at the owner that understands the typed outcome. Coordinator folds that result into current Session semantics after exact Link correlation; Runtime does not add a generic classifier or catch-and-relay path. Throwable type, message, identity, cause graph, and diagnostic delivery are not stable failure semantics.

## Metrics failure and completion mapping

A contained `Exception` from source subscription, attachment or worker dispatch, refresh, exact-handle close or unregister, or outward notification, as well as an invalid or null handle, is offered as `InternalFailure` while ordinary admission remains open; a higher-priority terminal contender may supersede that offer before claim, and later evidence is cleanup-only.

`Observer.onFailure(cause)` preserves `cause` as opaque error-as-data, including an `Error`, and follows the same offer rule without rethrowing solely from its runtime type.

Normal source completion with no current positive metrics is offered as `CaptureUnavailable` while ordinary admission remains open, first Active is still required, and the exact handle is adopted. Completion-driven close is not a prerequisite: unavailable geometry is already established independently of that cleanup. After first Active, completion does not by itself select that terminal problem. Close failure remains separately classified under the rule above, and terminal priority governs competing offers. Positive frozen readiness is owned by [Metrics](../components/metrics.md#readiness-and-cross-component-flow).

## Recoverable and terminal outcomes

`Suspended` is available only after at least one `Active` commitment. Its recoverable problems are:

- `InvalidRequest` for the current desire and geometry;
- `CaptureUnavailable` while required current Metrics or explicit source-unavailability evidence reports unavailable; and
- `ResourceExhausted` for a checked RGBA buffer-addressability rejection before buffer allocation or physical resource mutation, or for a deterministic Target-candidate denial when complete rollback leaves the prior Target healthy and intact.

The absence of a currently waiting source frame is normal best-effort capture behavior. It is neither a timeout nor unavailability evidence and does not suspend the Session.

Startup never suspends. Startup failure, unsafe or ambiguous rollback, owner-invalidating Capture failure, operation or ownership inconsistency, unsupported color space, and other required unsafe allocation/capacity outcomes are terminal.

Suspension gates both plan resolution and convergence dispatch for its configuration revision. A settled `ResourceDenied` result does not trigger another Apply of the same suspended demand. An unequal desired request or a relevant geometry or availability revision clears suspension and re-enters the normal convergence path; an equal request is a no-op. Suspended demand has no automatic retry loop or timer. Recovery returns to `Active` only after current Capture and Encoding readiness is re-established.

A stale operation-local failure is cleanup-only. A settled Capture Open failure, or Apply/Read failure marked `OwnerInvalidated`, is terminal while ordinary admission remains open even if the request's configuration revision is now stale. Owner health is physical evidence and cannot be inferred from revision currentness.

## Terminal contenders, priority, and claim

Lifecycle keeps one upgradeable terminal contender with permanent priority:

```text
ProjectionStopped > Requested > first Failed(problem)
```

Only a current `MediaProjection.Callback.onStop()` identity can offer `ProjectionStopped`; Android defines `onStop()` as notification that the projection has stopped and become invalid in its [Media projection resource-recovery guidance](https://developer.android.com/media/grow/media-projection#resource-recovery). `Requested` comes from owner stop, including cancellation observed by an entered start call. The first contained terminal failure fixes the Failed problem unless a higher-priority stop reason arrives before claim.

This priority selects the final outcome. A selected `Stopped` value is not proof that no fatal evidence was offered earlier; a later higher-priority stop may have superseded it.

The first accepted contender immediately closes ordinary admission and production, but it does not freeze priority, final accounting, or public State. Before the irreversible claim, a higher-priority contender can replace it and already-admitted callback failure or real Capture read evidence can still affect final accounting.

Coordinator claims terminal publication only when no ordinary publication is in flight and after the physical Delivery entry fence and eligible bounded facts have been handled. In one `publicationGate -> sessionGate` transaction it prepares and revalidates all four semantic-owner candidates, prevalidates Capture/Encoding freeze, freezes Session-facing Link correlation, commits the owners, invalidates active topology, and records the sole claim. Preparation does no Android call, callback, clock read, dispatch, Flow assignment, payload copy, I/O, cleanup, or wait.

After claim, no contender or late fact can revise State, Stats, delivery admission, cache, pacing, Session encoding readiness, or diagnostics. Independently retained callback-exit evidence may still settle registration completion; it cannot reopen Session authority. Final Stats precedes terminal State in the unlocked suffix, but State and Stats remain separate Flows and do not form an atomic public pair.

## Terminal is not cleanup

Terminal State ends Session authority and admission. It is not evidence that:

- a queued or entered callback returned;
- an accepted task entered or released its slot;
- a Capture read or Encoding call returned;
- projection, virtual display, Surface, EGL/GLES, carrier, Bitmap, or native resources were physically released; or
- Control or Capture threads terminated.

The terminal suffix requests cleanup in dependency order and asks Control to quit safely, without waiting. A late leaf or callback return performs reachable owner-local settlement, may establish registration completion, and cannot publish a Session successor. A nonreturning call retains its exact roots until process death; terminal State and reference loss never fabricate a receipt. See the public [run outcome and resource release](../../docs/architecture.md#run-outcome-and-resource-release) contract.

## Capture and backend containment

Capture distinguishes operation-local failure from owner invalidation. Current rollback-safe Target resource denial may suspend; failed rollback or an invalidated Capture owner terminates. Open is one-shot: definite dispatch rejection or returned failure does not re-arm it. An exact current Display P3 read fails with `UnsupportedColorSpace`; a stale operation-local result is discarded.

Encoding preserves these complete-frame and backend-health invariants:

- `FrameworkOnly` makes no Native calls.
- With `Auto`, an exact `UnsatisfiedLinkError` or `SecurityException` from the `System.loadLibrary` boundary is sticky clean unavailability; a normally returned unsupported capability likewise selects Framework before production. Other load `Error` types (including `UnsatisfiedLinkError` subclasses) and later JNI errors are not clean unavailability. Contained unsafe selection failure is `InternalFailure`; uncontained throwables propagate normally. See [Encoding's backend-selection owner](../components/encoding.md#backend-selection-and-fallback).
- Framework compression returning false settles the exact transaction, publishes no partial bytes, and counts one frame failure while Framework remains available.
- A safely classified Native compression rejection settles and drops that frame, monotonically disables Native for the Session, invalidates Native-dependent readiness/cache, and allows Framework only for a later frame. There is no same-frame retry.
- A stale production result may still contribute its settled frame accounting and Session-wide monotone Native-health transition. Staleness alone cannot produce `Suspended` or terminal failure.
- JNI contains C++ exceptions at the ABI. A pending Java throwable remains a distinct Java propagation path and is not cleared or replaced by native code.

Encoding settles carrier ownership and transaction state before reporting a result. Only complete immutable payloads reach Storage; Coordinator revalidates them before publication.

## Uncontained failures and diagnostics

An owner catches `Exception` only at a boundary where it can preserve its owned invariants and return an ordinary typed outcome. The [cancellation rule](#cancellation-and-operation-outcomes) distinguishes an owning coroutine's cancellation from an unrelated callback exception. Only specifically named safe exhaustion classifiers may convert their exact allocation failure, including a named `OutOfMemoryError`, to `ResourceExhausted`.

Other `Error` and non-`Exception` throwables propagate unchanged through ordinary Kotlin/JVM behavior. A minimal nonthrowing revoke, fence, or quarantine may protect an acquired resource or borrow from escaping, but it is not full settlement and cannot retry, fall back, publish, account, or authorize successor work. Runtime hosting may route an uncontained throwable to a thread's uncaught-exception path or retain it in a Future; neither behavior creates a Session failure or cleanup receipt.

Diagnostics are best-effort and non-authoritative. Optional absence, no-subscriber or overflow loss, silent sequence exhaustion, and a caught ordinary `Exception` cannot block final Stats or terminal State. `Error` and other non-`Exception` throwables follow the general propagation rule above; a diagnostic is never evidence of semantic or physical settlement.
