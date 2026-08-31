# Metrics

Metrics supplies the width, height, and density from which a session resolves capture geometry. It is an observation component, not a geometry-policy component: it reports the latest source facts, while the session decides whether those facts are ready, current, recoverable, or terminal. See the public descriptions of [capture size and density](../../docs/architecture.md#capture-size-and-density) and [session configuration](../../docs/usage.md#configure-session-wide-behavior). The shared ownership model lives in the [internal architecture overview](../architecture/overview.md), and the cross-cutting callback/settlement rules live in [Concurrency and liveness](../contracts/concurrency-and-liveness.md).

## Responsibility boundary

One `SessionMetricsOwner` belongs to one session. It owns:

- the selected `CaptureMetricsSource` identity and its single attachment;
- callback ingress, attachment lifecycle, and the current immutable `MetricsSnapshot`;
- the exact close handle returned by the attachment;
- one queue-less, coalesced worker permit; and
- for a built-in source, the display listener, handler association, and display-read epoch.

It does not own the startup deadline, parameter revision, projection content, resolved `CapturePlan`, lifecycle transition, state publication, or retry policy. Those decisions remain with the session. Metrics also never reads geometry per frame; display notifications only invalidate the latest observation.

The session reads a snapshot while already holding its own gate. Metrics may briefly take its private gate for that read, but it never takes a session lock or calls outward while holding the Metrics gate. Keep this lock direction one-way.

## Source selection and identity

Source selection is fixed when the session is created:

- With no configured source, the session creates a private built-in source that follows logical display ID [`Display.DEFAULT_DISPLAY`](https://developer.android.com/reference/android/view/Display#DEFAULT_DISPLAY).
- A custom source is retained by exact identity and subscribed exactly once. The engine context is not passed to it.
- `CaptureMetricsSource.fromDisplay(context, display)` retains the normalized application context and the exact supplied `Display` as its read target.

The default source may follow a replacement [`Display`](https://developer.android.com/reference/android/view/Display) object with the default ID. A fixed-display source never substitutes the same-ID object returned by [`DisplayManager`](https://developer.android.com/reference/android/hardware/display/DisplayManager); that object is only evidence that the retained target is still associated and valid. This distinction prevents a fixed selection from silently changing identity.

Every subscription is independent. A source callback may be inline with `subscribe`, reentrant, concurrent, or on a source-owned thread, so the owner roots the observer and attachment state before entering source code. A normally returned handle is adopted by exact identity before later fallible work and is closed at most once. A Java null return is failed attachment evidence, not "nothing to close."

## Latest-value ingress and snapshots

Metrics ingress is latest-value, not event-stream storage. `onMetricsChanged` replaces the current value, including replacing positive metrics with unavailable (`null`). Completion freezes the then-current availability; failure fences later callbacks and retains the exact `Throwable` only as diagnostic data.

The component stores no callback queue, sample history, source sequence, or sticky first-positive value. Writes coalesce onto one serial owner turn. A pending or entered turn absorbs further writes, and a write racing with drain release leaves one successor request. This keeps arbitrary source callback rates from becoming an unbounded work queue.

`MetricsSnapshot` contains the current nullable metrics, attachment lifecycle, handle-adoption state, completion-close settlement, and the exact optional failure reference. Meaningful lifecycle or availability changes install a new snapshot identity. Structurally duplicate metric values may retain the current identity. Session revision, readiness deadline, and combined projection geometry never enter this snapshot.

## Built-in display observation

A built-in observation registers one [`DisplayManager.DisplayListener`](https://developer.android.com/reference/android/hardware/display/DisplayManager.DisplayListener) on the main-looper handler before its initial refresh is dispatched. Matching add, remove, and change callbacks only invalidate or dirty the observation; the platform geometry read and observer call run through the coalesced owner/worker turn. A removal publishes unavailable and allows a later valid association to recover.

The SDK band is selected once for the observation:

| API level | Dimension read for the selected display | Density read |
| --- | --- | --- |
| 24–29 | [`Display.getRealSize(Point)`](https://developer.android.com/reference/android/view/Display#getRealSize(android.graphics.Point)) | configuration from a fresh display context |
| 30 | [`WindowManager.getMaximumWindowMetrics`](https://developer.android.com/reference/android/view/WindowManager#getMaximumWindowMetrics()) from a window context created by [`Context.createDisplayContext`](https://developer.android.com/reference/android/content/Context#createDisplayContext(android.view.Display)), then that display context's [`createWindowContext(type, options)`](https://developer.android.com/reference/android/content/Context#createWindowContext(int,android.os.Bundle)) | configuration from a fresh display context |
| 31–37 | [`WindowManager.getMaximumWindowMetrics`](https://developer.android.com/reference/android/view/WindowManager#getMaximumWindowMetrics()) from [`Context.createWindowContext`](https://developer.android.com/reference/android/content/Context#createWindowContext(android.view.Display,int,android.os.Bundle)) | configuration from a fresh display context |

Dimensions and density are sequential reads of the same validated display identity, not an atomic Android snapshot. Density uses a newly created display context on every refresh because resources from an earlier display context can be stale. If invalidation is admitted while a refresh is reading, the tuple is suppressed and a successor refresh is requested rather than publishing mixed-epoch data.

On API 34–37 the width and height remain provisional until Capture reports the first valid projection resize; density continues to come from Metrics. The session owns that merge. This is why Metrics must not infer captured content from display selection.

## Readiness and cross-component flow

A snapshot contributes to first-Active readiness only when it has positive metrics, its exact handle has been adopted, and the attachment is neither failed nor retired. If a source completed before first Active, its completion-driven close must also have settled normally. After first Active, a completed positive snapshot remains usable while close is settling because completion has frozen the value.

The session applies that predicate at its readiness, publication-reservation, and post-publication settlement boundaries. Metrics stores none of those stages. A newer unavailable snapshot defeats an older positive snapshot; object identity is part of the correlation.

Metrics feeds [capture](capture.md) only through session-owned plan resolution. It has no direct Capture or Encoding dependency. Lifecycle, revision, and terminal interpretation are documented with the session architecture rather than duplicated here.

## Failure containment and retirement

Metrics does not independently select public failure categories. Its contained source, attachment, invalid-or-null-handle, subscription, dispatch, refresh, close/unregister, or notification failure follows the [normative Metrics failure and completion mapping](../contracts/failures-and-terminal-semantics.md#metrics-failure-and-completion-mapping): it is offered as `InternalFailure` while ordinary admission remains open, while `Observer.onFailure` preserves even an `Error` as opaque error-as-data. Normal completion without current positive metrics can be `CaptureUnavailable` only under that contract's first-Active admission and close-settlement conditions. Uncontained throwables retain the host-safety behavior described by the same contract.

Completion, failure, or retirement closes ingress and requests the exact handle close. Close runs outside semantic state gates. For a public built-in observation, caller close and automatic failure cleanup share one unregister settlement: one actor enters the unregister call, concurrent or repeated callers observe that same result, and no actor retries. Closing fences queued listener callbacks but does not wait for an entered observer callback, worker drain, or wider platform cleanup.

Retirement clears semantic notification work immediately. An attachment or close that entered and never returns keeps its owner and dependencies rooted. A real late handle return is still adopted and closed, but after retirement it cannot publish geometry or request session work. Terminal state is therefore a logical fence, not a physical cleanup receipt.
