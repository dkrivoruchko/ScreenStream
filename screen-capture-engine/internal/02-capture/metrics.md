# Metrics

Metrics reports the latest width, height, and density; Session resolves capture geometry and interprets readiness, currentness, and failures. Public behavior is in [Capture size and density](../../docs/architecture.md#capture-size-and-density) and [Session configuration](../../docs/usage.md#session-configuration).

This page extends those public guides with source attachment, owner scheduling, epoch validation, and the exact relationship between readiness and close. Session remains the authority for topology and terminal decisions.

## Contents

- [Responsibility boundary](#responsibility-boundary)
- [Source attachment and identity](#source-attachment-and-identity)
- [Latest-value ingress and snapshots](#latest-value-ingress-and-snapshots)
- [Owner turns and built-in entry](#owner-turns-and-built-in-entry)
- [Built-in display observation](#built-in-display-observation)
- [Readiness and cross-component flow](#readiness-and-cross-component-flow)
- [Failure and completion mapping](#failure-and-completion-mapping)
- [Close and retirement](#close-and-retirement)
- [Implementation and verification](#implementation-and-verification)

## Responsibility boundary

One `SessionMetricsOwner` belongs to one session. It owns:

- the selected `CaptureMetricsSource` identity and its single attachment;
- callback ingress, attachment lifecycle, and the current immutable `MetricsSnapshot`;
- the exact close handle returned by the attachment;
- one queue-less, coalesced worker permit; and
- for a built-in source, the display listener, handler association, display-read epoch, and the API 31+ cached WindowContext configuration callback.

Metrics does not own the startup deadline, parameter revision, projection content, resolved `CapturePlan`, lifecycle transition, publication, or retry policy; those decisions remain with Session. Metrics never reads geometry per frame; display notifications invalidate the latest observation.

The session reads a snapshot while already holding its own gate. Metrics may briefly take its private gate for that read, but it never takes a session lock or calls outward while holding the Metrics gate. Keep this lock direction one-way; the complete gate and dispatch rules are in [Coordination](../01-session/coordination.md#session-gates).

## Source attachment and identity

Source selection is fixed when the session is created:

- With no configured source, the session creates a private built-in source that follows logical display ID [`Display.DEFAULT_DISPLAY`](https://developer.android.com/reference/android/view/Display#DEFAULT_DISPLAY).
- A custom source is retained by exact identity and subscribed at most once per session. A session stopped before attachment need not subscribe. The engine context is not passed to it.
- `CaptureMetricsSource.fromDisplay(context, display)` retains the normalized application context and the exact supplied `Display` as its read target.

The default source may follow a replacement [`Display`](https://developer.android.com/reference/android/view/Display) object with the default ID. A fixed-display source never substitutes the same-ID object returned by [`DisplayManager`](https://developer.android.com/reference/android/hardware/display/DisplayManager); that object is only evidence that the retained target is still associated and valid. This preserves the selected object identity, not proof of uninterrupted physical-display identity: Android documents that an invalid [`Display`](https://developer.android.com/reference/android/view/Display#isValid()) can become valid again on same-ID reconnection.

Every subscription is independent. A source callback may be inline with `subscribe`, reentrant, concurrent, or on a source-owned thread, so the owner roots the observer and attachment state before entering source code. A normally returned handle is adopted by exact identity before later fallible work and is closed at most once. A Java null return is failed attachment evidence, not "nothing to close."

## Latest-value ingress and snapshots

Metrics ingress is latest-value, not event-stream storage. `onMetricsChanged` replaces the current value, including replacing positive metrics with unavailable (`null`). Completion freezes the then-current availability; failure fences later callbacks and retains the exact `Throwable` only as diagnostic data.

The component stores no callback queue, sample history, source sequence, or sticky first-positive value. Ingress records immutable state immediately under the Metrics gate; outward notification and other work coalesce onto serial owner turns. A pending or entered turn absorbs further writes, and a write racing with drain release leaves one successor request. This keeps arbitrary source callback rates from becoming an unbounded work queue.

`MetricsSnapshot` contains the current nullable metrics, attachment lifecycle, handle-adoption state, completion-close settlement, and the exact optional failure reference. Meaningful lifecycle or availability changes install a new snapshot identity. Structurally duplicate metric values may retain the current identity. Session revision, readiness deadline, and combined projection geometry never enter this snapshot.

## Owner turns and built-in entry

`SessionMetricsOwner` selects at most one action per entered owner turn, in this order:

1. Notify Control of changed facts, while the owner is not retired.
2. Enter a requested close when the exact handle is available.
3. Enter the one requested source attachment, while not retired.
4. Run a pending built-in refresh, while not retired.

The action is reserved under the private gate, but source calls, close, refresh, and Control notification run outside it. This ordering lets Session see completion or failure before slow physical close work; it does not make notification a cleanup receipt. A contained action `Exception` records failure while the owner is live.

Submission and slot release are separate. `submissionInFlight` covers the dispatch call itself. If its release callback runs before dispatch returns, the owner records that release and requests any successor after it has processed the submission outcome. Otherwise release requests pending work directly. Accepted or occupied submission does not prove the selected action entered. This bookkeeping prevents a write racing with release from being stranded or creating a second overlapping worker turn.

The default source and an explicitly configured source enter differently. For the absent-config default, Session constructs and retains `BuiltInCaptureMetricsObservation`, calls `startOnMetricsOwner()`, and routes subsequent refresh work through the same Metrics owner. For any explicit source, including one returned by `fromDisplay`, attachment calls that source's public `subscribe(observer)`; the built-in public observation uses its guarded worker dispatcher. Preserve this distinction when changing scheduling or close: only the owner-created default uses `closeOnMetricsOwner()` directly; other adopted handles use `close()`.

## Built-in display observation

A built-in observation registers one [`DisplayManager.DisplayListener`](https://developer.android.com/reference/android/hardware/display/DisplayManager.DisplayListener) on the main-looper handler before its initial geometry read. The public subscription registers before dispatching its first refresh; the owner-created default registers and performs its first refresh inside the entered Metrics turn. Matching add, remove, and change callbacks only invalidate or dirty the observation; the platform geometry read and observer call run through the coalesced owner/worker turn. A removal publishes unavailable and allows a later valid association to recover.

For the built-in public `subscribe` route, the caller-visible failure split is exact:

- Listener-registration failure is reported through `Observer.onFailure`; the returned observation is already automatically closed.
- Initial refresh dispatch rejection or an ordinary dispatch `Exception` unregisters the listener and escapes `subscribe` with that exact failure.
- After `subscribe` has returned, later dispatch rejection, dispatch `Exception`, or refresh `Exception` is reported through `Observer.onFailure` and closes the observation.

These paths share the same one-attempt unregister settlement; they do not turn a later asynchronous failure into a throw from the completed `subscribe` call.

The SDK band is selected once for the observation:

- API 24–29: dimensions come from [`Display.getRealSize(Point)`](https://developer.android.com/reference/android/view/Display#getRealSize(android.graphics.Point)).
- API 30: create a display context through [`Context.createDisplayContext`](https://developer.android.com/reference/android/content/Context#createDisplayContext(android.view.Display)), then call that context's [`createWindowContext(type, options)`](https://developer.android.com/reference/android/content/Context#createWindowContext(int,android.os.Bundle)); read its WindowManager's [`getMaximumWindowMetrics()`](https://developer.android.com/reference/android/view/WindowManager#getMaximumWindowMetrics()).
- API 31+: create the window context directly through [`Context.createWindowContext(display, type, options)`](https://developer.android.com/reference/android/content/Context#createWindowContext(android.view.Display,int,android.os.Bundle)) and read its WindowManager's [`getMaximumWindowMetrics()`](https://developer.android.com/reference/android/view/WindowManager#getMaximumWindowMetrics()).

Dimensions and density are sequential reads of the same validated display identity, not an atomic Android snapshot. Density reads `resources.configuration.densityDpi` from a newly created display context on every refresh because resources from an earlier display context can be stale. If invalidation is admitted while a refresh is reading, the tuple is suppressed and a successor refresh is requested rather than publishing mixed-epoch data.

On API 31+, each cached WindowContext epoch registers its exact [`ComponentCallbacks`](https://developer.android.com/reference/android/content/ComponentCallbacks) before the first bounds read. An `onConfigurationChanged` from the current epoch marks the existing coalesced refresh dirty, so a WindowContext update can refresh bounds without another Display event. API 30 retains its existing display-context route and does not register this callback. Replaced and closed epochs have no callback authority. Epoch invalidation, replacement, and close request one exact callback-unregister settlement; a callback registration that returns late after close still performs that settlement. Ordinary callback cleanup failure does not skip Display-listener cleanup. These effects run outside semantic admission gates and add no polling, lane, or per-refresh WindowContext recreation.

A display epoch contains the exact selected Display, cached WindowContext/WindowManager when applicable, and its exact callback registration. Refresh retires an invalidated epoch and publishes unavailable before requesting a new association. It validates the selected display before epoch creation, after callback setup, and after reading dimensions/density. A changed epoch identity, newly admitted invalidation, or invalid display suppresses that read and requests a successor. No stale read can install a fresh-looking snapshot merely because its dimensions are positive.

On API 34+ width and height remain provisional until Capture reports the first valid projection resize; density still comes from Metrics. Session owns that merge; Metrics cannot infer captured content from display selection.

## Readiness and cross-component flow

A snapshot contributes to first-Active readiness when it has positive metrics, its exact handle has been adopted, and the attachment is neither failed nor retired. A positive immutable snapshot frozen by normal source completion remains usable while its completion-driven close is pending, both before and after first Active. Completion has fixed the geometry value; pending cleanup is not by itself evidence that the value is unusable.

The exact handle remains owned until its at-most-once close settles. Close failures are separately classified, and late returns retain their settlement responsibility. Snapshot storage and currentness must remain valid independently of close progress; readiness does not waive resource ownership, failure/retirement checks, or any Capture/Encoding readiness condition. Unavailable completion follows the [failure and completion mapping](#failure-and-completion-mapping), without waiting merely for cleanup.

The session applies that predicate at its readiness, publication-reservation, and post-publication settlement boundaries. Metrics stores none of those stages. A newer unavailable snapshot defeats an older positive snapshot; object identity is part of the correlation.

Metrics feeds [Capture](capture.md) through Session plan resolution, with no direct Capture or Encoding dependency.

## Failure and completion mapping

The physical owner records facts; Session offers the corresponding problem while ordinary admission remains open. A higher-priority terminal contender may supersede an offer before claim; after the fence, new evidence is cleanup-only. [Session](../01-session/session.md#stable-problem-mapping) owns that arbitration and its shared exception/cancellation boundary.

| Evidence | Session interpretation |
| --- | --- |
| Contained `Exception` from subscription, attachment/worker dispatch, refresh, exact-handle close or unregister, or outward notification; invalid/null returned handle | Offer `InternalFailure`. |
| `Observer.onFailure(cause)` | Retain the exact cause as opaque error-as-data, including an `Error`, and offer `InternalFailure`; do not rethrow merely because of the cause's runtime type. |
| Normal completion with no current positive metrics, adopted exact handle, and first Active still required | Offer `CaptureUnavailable` without waiting for completion-driven close. |
| Normal completion with positive frozen metrics | Preserve readiness if the handle is adopted and the attachment is neither Failed nor Retired, even while close is pending. |
| Normal completion with no current positive metrics after first Active | Completion alone does not select `CaptureUnavailable`; Session handles unavailable geometry under its ordinary topology rules. |
| Completion-driven close fails | Classify that failure separately as `InternalFailure`; it is not erased by prior positive readiness or normal completion. |

An actually thrown non-`Exception` follows the [shared Session boundary](../01-session/session.md#exception-and-cancellation-boundaries), unlike a Throwable delivered as observer data. A stopped/retired owner cannot revive admission by returning a late attachment, notification, refresh, or close result.

## Close and retirement

Completion, failure, or retirement closes ingress and requests the exact handle close. Close runs outside semantic state gates. For a public built-in observation, caller close and automatic failure cleanup share one unregister settlement: one actor performs the unregister work, concurrent or repeated callers observe that same result, and no actor retries. The private `closeGate` serializes this attempt, including its platform calls, so close may wait for its own unregister work or an in-progress attempt on another thread. Closing fences queued listener callbacks but does not wait for an entered observer callback or worker drain; successful return does not prove that all observation work has finished.

Retirement clears semantic notification work immediately. An attachment or close that entered and never returns keeps its owner and dependencies rooted. A real late handle return is still adopted and closed, but after retirement it cannot publish geometry or request session work. Terminal state is therefore a logical fence, not a physical cleanup receipt.

## Implementation and verification

[SessionMetricsOwner](../../src/main/kotlin/io/screenstream/capture/internal/metrics/SessionMetricsOwner.kt) owns attachment, snapshots, action order, dispatch-release bookkeeping, and exact close. [BuiltInCaptureMetricsSource](../../src/main/kotlin/io/screenstream/capture/internal/metrics/BuiltInCaptureMetricsSource.kt) selects default/fixed display behavior; [BuiltInCaptureMetricsObservation](../../src/main/kotlin/io/screenstream/capture/internal/metrics/BuiltInCaptureMetricsObservation.kt) owns listener setup, public dispatch, display epochs, refresh, and callback/listener retirement. [Session](../01-session/session.md) is the next stop when changing how these facts affect startup or Active output.

`SessionMetricsOwnerLifecycleTest` exercises inline/concurrent attachment and close readiness. `BuiltInCaptureMetricsSourceLifecycleTest`, `BuiltInCaptureMetricsPlatformDisplayTest`, and `CaptureMetricsSourceFromDisplayTest` cover dispatch entry, display/context epochs, stale reads, and cleanup. Keep the [verification catalog](../04-testing/verification-contracts.md) obligations and [testing guide](../04-testing/testing.md#contract-test-rules) marker rules attached to those behaviors when changing the implementation.
