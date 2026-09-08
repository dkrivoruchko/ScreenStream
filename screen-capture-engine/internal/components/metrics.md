# Metrics

Metrics reports the latest width, height, and density; Session resolves capture geometry and interprets readiness, currentness, and failures. Public behavior is in [Capture size and density](../../docs/architecture.md#capture-size-and-density) and [Session configuration](../../docs/usage.md#configure-session-wide-behavior).

## Responsibility boundary

One `SessionMetricsOwner` belongs to one session. It owns:

- the selected `CaptureMetricsSource` identity and its single attachment;
- callback ingress, attachment lifecycle, and the current immutable `MetricsSnapshot`;
- the exact close handle returned by the attachment;
- one queue-less, coalesced worker permit; and
- for a built-in source, the display listener, handler association, display-read epoch, and the API 31+ cached WindowContext configuration callback.

Metrics does not own the startup deadline, parameter revision, projection content, resolved `CapturePlan`, lifecycle transition, publication, or retry policy; those decisions remain with Session. Metrics never reads geometry per frame; display notifications invalidate the latest observation.

The session reads a snapshot while already holding its own gate. Metrics may briefly take its private gate for that read, but it never takes a session lock or calls outward while holding the Metrics gate. Keep this lock direction one-way.

## Source selection and identity

Source selection is fixed when the session is created:

- With no configured source, the session creates a private built-in source that follows logical display ID [`Display.DEFAULT_DISPLAY`](https://developer.android.com/reference/android/view/Display#DEFAULT_DISPLAY).
- A custom source is retained by exact identity and subscribed at most once per session. A session stopped before attachment need not subscribe. The engine context is not passed to it.
- `CaptureMetricsSource.fromDisplay(context, display)` retains the normalized application context and the exact supplied `Display` as its read target.

The default source may follow a replacement [`Display`](https://developer.android.com/reference/android/view/Display) object with the default ID. A fixed-display source never substitutes the same-ID object returned by [`DisplayManager`](https://developer.android.com/reference/android/hardware/display/DisplayManager); that object is only evidence that the retained target is still associated and valid. This preserves the selected object identity, not proof of uninterrupted physical-display identity: Android documents that an invalid [`Display`](https://developer.android.com/reference/android/view/Display#isValid()) can become valid again on same-ID reconnection.

Every subscription is independent. A source callback may be inline with `subscribe`, reentrant, concurrent, or on a source-owned thread, so the owner roots the observer and attachment state before entering source code. A normally returned handle is adopted by exact identity before later fallible work and is closed at most once. A Java null return is failed attachment evidence, not "nothing to close."

## Latest-value ingress and snapshots

Metrics ingress is latest-value, not event-stream storage. `onMetricsChanged` replaces the current value, including replacing positive metrics with unavailable (`null`). Completion freezes the then-current availability; failure fences later callbacks and retains the exact `Throwable` only as diagnostic data.

The component stores no callback queue, sample history, source sequence, or sticky first-positive value. Writes coalesce onto one serial owner turn. A pending or entered turn absorbs further writes, and a write racing with drain release leaves one successor request. This keeps arbitrary source callback rates from becoming an unbounded work queue.

`MetricsSnapshot` contains the current nullable metrics, attachment lifecycle, handle-adoption state, completion-close settlement, and the exact optional failure reference. Meaningful lifecycle or availability changes install a new snapshot identity. Structurally duplicate metric values may retain the current identity. Session revision, readiness deadline, and combined projection geometry never enter this snapshot.

## Built-in display observation

A built-in observation registers one [`DisplayManager.DisplayListener`](https://developer.android.com/reference/android/hardware/display/DisplayManager.DisplayListener) on the main-looper handler before its initial refresh is dispatched. Matching add, remove, and change callbacks only invalidate or dirty the observation; the platform geometry read and observer call run through the coalesced owner/worker turn. A removal publishes unavailable and allows a later valid association to recover.

The caller-visible failure split is exact:

- Listener-registration failure is reported through `Observer.onFailure`; the returned observation is already automatically closed.
- Initial refresh dispatch rejection or an ordinary dispatch `Exception` unregisters the listener and escapes `subscribe` with that exact failure.
- After `subscribe` has returned, later dispatch rejection, dispatch `Exception`, or refresh `Exception` is reported through `Observer.onFailure` and closes the observation.

These paths share the same one-attempt unregister settlement; they do not turn a later asynchronous failure into a throw from the completed `subscribe` call.

The SDK band is selected once for the observation:

| API level | Dimension read for the selected display | Density read |
| --- | --- | --- |
| 24–29 | [`Display.getRealSize(Point)`](https://developer.android.com/reference/android/view/Display#getRealSize(android.graphics.Point)) | configuration from a fresh display context |
| 30 | [`WindowManager.getMaximumWindowMetrics`](https://developer.android.com/reference/android/view/WindowManager#getMaximumWindowMetrics()) from a window context created by [`Context.createDisplayContext`](https://developer.android.com/reference/android/content/Context#createDisplayContext(android.view.Display)), then that display context's [`createWindowContext(type, options)`](https://developer.android.com/reference/android/content/Context#createWindowContext(int,android.os.Bundle)) | configuration from a fresh display context |
| 31+ | [`WindowManager.getMaximumWindowMetrics`](https://developer.android.com/reference/android/view/WindowManager#getMaximumWindowMetrics()) from [`Context.createWindowContext`](https://developer.android.com/reference/android/content/Context#createWindowContext(android.view.Display,int,android.os.Bundle)) | configuration from a fresh display context |

Dimensions and density are sequential reads of the same validated display identity, not an atomic Android snapshot. Density uses a newly created display context on every refresh because resources from an earlier display context can be stale. If invalidation is admitted while a refresh is reading, the tuple is suppressed and a successor refresh is requested rather than publishing mixed-epoch data.

On API 31+, each cached WindowContext epoch registers its exact [`ComponentCallbacks`](https://developer.android.com/reference/android/content/ComponentCallbacks) before the first bounds read. An `onConfigurationChanged` from the current epoch marks the existing coalesced refresh dirty, so a WindowContext update can refresh bounds without another Display event. API 30 retains its existing display-context route and does not register this callback. Replaced and closed epochs have no callback authority. Epoch invalidation, replacement, and close request one exact callback-unregister settlement; a callback registration that returns late after close still performs that settlement. Ordinary callback cleanup failure does not skip Display-listener cleanup. These effects run outside semantic admission gates and add no polling, lane, or per-refresh WindowContext recreation.

On API 34+ width and height remain provisional until Capture reports the first valid projection resize; density still comes from Metrics. Session owns that merge; Metrics cannot infer captured content from display selection.

## Readiness and cross-component flow

A snapshot contributes to first-Active readiness when it has positive metrics, its exact handle has been adopted, and the attachment is neither failed nor retired. A positive immutable snapshot frozen by normal source completion remains usable while its completion-driven close is pending, both before and after first Active. Completion has fixed the geometry value; pending cleanup is not by itself evidence that the value is unusable.

The exact handle remains owned until its at-most-once close settles. Close failures are separately classified, and late returns retain their settlement responsibility. Snapshot storage and currentness must remain valid independently of close progress; readiness does not waive resource ownership, failure/retirement checks, or any Capture/Encoding readiness condition. Unavailable completion follows the [normative failure mapping](../contracts/failures-and-terminal-semantics.md#metrics-failure-and-completion-mapping), without waiting merely for cleanup.

The session applies that predicate at its readiness, publication-reservation, and post-publication settlement boundaries. Metrics stores none of those stages. A newer unavailable snapshot defeats an older positive snapshot; object identity is part of the correlation.

Metrics feeds [Capture](capture.md) through Session plan resolution, with no direct Capture or Encoding dependency.

## Failure containment and retirement

Contained source, attachment, invalid-or-null-handle, subscription, dispatch, refresh, close/unregister, or notification failure follows the [Metrics failure and completion mapping](../contracts/failures-and-terminal-semantics.md#metrics-failure-and-completion-mapping). `Observer.onFailure` preserves even an `Error` as opaque error-as-data. Coroutine cancellation and uncontained throwables follow the boundary rules in that contract.

Completion, failure, or retirement closes ingress and requests the exact handle close. Close runs outside semantic state gates. For a public built-in observation, caller close and automatic failure cleanup share one unregister settlement: one actor enters the unregister call, concurrent or repeated callers observe that same result, and no actor retries. Closing fences queued listener callbacks but does not wait for an entered observer callback, worker drain, or wider platform cleanup.

Retirement clears semantic notification work immediately. An attachment or close that entered and never returns keeps its owner and dependencies rooted. A real late handle return is still adopted and closed, but after retirement it cannot publish geometry or request session work. Terminal state is therefore a logical fence, not a physical cleanup receipt.
