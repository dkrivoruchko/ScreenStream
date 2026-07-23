# Screen Capture Engine — Metrics and Platform Geometry

## Scope and authority

This document is the sole authority for Metrics source attachment, built-in Display observation, API-band
platform reads, physical geometry facts, bounded source ingress, first-positive timing evidence, exact returned
handle ownership, and Metrics-local close/residue. Caller-visible API, values, and outcomes belong to
[the product contract](01-product-contract.md). Execution roles, `sessionGate`, Control decisions, Active,
currentness, generic deadline arithmetic, terminal cutoff, and typed retirement belong to
[internal architecture](02-internal-architecture.md). Capture callback registration, `MediaProjection`, and
`VirtualDisplay` calls belong to [capture/rendering](04-capture-rendering.md).

Metrics owns facts, not their Session application. It owns no combined geometry, lifecycle, Active, State,
Stats, diagnostic selection, suspension, Session-recovery decision, reconciliation, frame admission, or
capture-source decision. Control consumes its immutable facts under the architecture contract.

## Source identity and attachment lifetime

For null configuration, each Session creates one Session-private built-in source with the default-display policy.
That policy follows the current `Display.DEFAULT_DISPLAY` object through the normalized application Context's
`DisplayManager`; after removal it may select the new current default Display object.

`CaptureMetricsSources.fromDisplay(context, display)` creates an immutable reusable built-in policy associated
with the normalized `context.applicationContext`, its `DisplayManager`, the exact supplied `Display` object, and
that object's ID. It never substitutes another Display object, including one with the same ID. Loss makes the
association unavailable; recovery tests that same object. The product contract owns construction-time validation
and the caller-visible factory behavior.

A configured custom source retains its exact object identity. The engine neither replaces it nor owns its
Activity, window, dynamic-display, or lifecycle policy. Each Session attachment calls its selected source's
`subscribe(observer)` exactly once. Repeated, concurrent, reused-source, and cross-Session attachments are
independent and inherit no value, completion, handle, fence, cache, or close result from one another.

The attachment and any later close execute asynchronously on the distinct Metrics serial blocking role defined
by internal architecture. The role is referenced here, not redefined: platform reads and custom source calls
never run on Control. `subscribe` is an outward call and may synchronously invoke the observer before returning,
invoke it reentrantly, concurrently, or later, and use arbitrary source-owned threads.

The attachment's `MetricsCapsule` is durable before dispatch. It roots the exact source, observer/fence, bounded
summary, in-progress attachment, any normally returned exact handle, and the close obligation through return,
cutoff, and late cleanup. A caller, work item, cancellation token, Boolean, or raw handle is never the sole
ownership evidence.

## Built-in observation and Display epochs

Every built-in attachment owns an independent `DisplayListener`, one non-owned
`Handler(Looper.getMainLooper())`, authority fence, selected continuous-validity epoch, sticky epoch-invalidated
bit, coalesced-refresh bit, API-30+ window-object cache, and unregister obligation.

A Main callback performs only an O(1), allocation-free fence and selected-ID check, sets the sticky invalidation
boundary for applicable add/remove events, changes the coalesced-refresh bit, and requests Metrics-role work. It
performs no Display/window read, observer call, publication, lifecycle decision, or platform mutation. Listener
registration, complete reads, and unregister execute serially on Metrics. At most one read is running and one
refresh is pending; a callback during a read marks the observation dirty, and completion admits at most one
successor read. Callback storms therefore do not create an engine event queue.

One complete read captures the exact selected Display and one local continuous-validity epoch, checks
`Display.isValid` before and after all field reads, obtains width, height, and density as one candidate, and then
checks the same source selection, Display object, epoch, validity, and sticky boundary before exposing the
candidate. Fields from different reads, Displays, or epochs are never combined. This epoch is resource-local
identity, not another cross-boundary Session generation.

| API band | Width and height read | Density read | Exact lifetime rule |
| --- | --- | --- | --- |
| 24–29 | `epochDisplay.getRealSize(Point)` on every complete read | `applicationContext.createDisplayContext(epochDisplay).resources.configuration.densityDpi` from a fresh transient display Context on every complete read | Retain neither the `Point` nor display Context. Access no API-30+ metrics/window API. |
| 30 | `epochWindowManager.maximumWindowMetrics.bounds` | The same fresh transient display-Context density read as API 24–29 | Once per epoch, create the display Context, then its WindowContext with `createWindowContext(TYPE_APPLICATION, null)`; cache only that epoch's WindowContext/`WindowManager` pair. |
| 31–37 | `epochWindowManager.maximumWindowMetrics.bounds` | The same fresh transient display-Context density read | Once per epoch, use `applicationContext.createWindowContext(epochDisplay, TYPE_APPLICATION, null)` and cache only that epoch's WindowContext/`WindowManager` pair. |

Frame production performs zero metrics platform reads. An ordinary `onDisplayChanged` for the selected ID
preserves the epoch/window pair and requests a complete reread. Removal, an add/remove boundary, detected
invalidity, or changed exact selection ends the epoch and drops the pair.

Invalidation is sticky and recovery is conflated. Consuming invalidation first retires the current epoch/window
pair and emits one unavailable fact; only a later refresh may install a new epoch, so an in-flight read cannot
erase the boundary it encountered. A refresh resolves a nonnull valid Display and, on API 30+, constructs the
new epoch pair before installation. A changed selection, invalid Display, or boundary observed during final
validation suppresses the candidate, retires the epoch, emits unavailable idempotently, and preserves one
recovery request. A boundary arriving just after successful validation remains sticky for the next pass. An
ordinary change racing a valid read may allow that coherent candidate, followed by the one pending reread.

The built-in observer exposes only a complete immutable positive tuple or unavailable. Repeated unavailable is
idempotent at this physical boundary; the full-key duplicate/recovery rule below governs its consumption. A
Display used for metrics is geometry evidence only: it neither selects nor verifies what content the accepted
projection captures.

## API-band geometry facts and the captured-resize seam

The platform supplies these exact facts; their caller-visible meaning remains in the product contract:

| API band and phase | Width/height fact | Density fact |
| --- | --- | --- |
| 24–33 | latest complete selected-source tuple | latest complete selected-source tuple |
| 34–37 before first valid captured resize | selected-source width/height, marked provisional | latest complete selected-source density |
| 34–37 after first valid captured resize | latest positive resize from the matching accepted projection owner | latest complete selected-source density |

Capture/rendering owns callback registration, fencing, and production of the captured-resize fact. This document
owns only the platform-geometry seam: captured resize replaces the source's width/height authority on API 34–37,
never its density authority, and triggers no Metrics platform read. The fact may arrive before or after the sole
`VirtualDisplay` creation result and remains bound to that same projection owner. Either order supplies facts to
Control; it creates no second display and no alternate geometry authority. Control owns matching, combination,
currentness, and application, while the product contract owns provisional frame-admission behavior and visible
outcomes.

The full authority key offered to Control contains the source and observation identity; the selected Display plus
its local continuous-validity epoch, or the matching projection owner; the availability phase; and every
authoritative field. An equal full key and repeated unavailable each consume their ingress sequence but are
duplicates: neither creates a `configRevision`, reconciliation, rebuild, State/Stats publication, or diagnostic.
First valid, an availability transition, a new local epoch or projection owner, or any authoritative-field change
is a real fact. Unavailable followed by valid fields historically equal to the last available tuple is recovery,
not a duplicate.

The epoch or projection owner in that key is resource-local evidence only. It is not a fourth cross-boundary
Session identity and neither replaces nor supplements `configRevision`, `productionId`, or `registrationId`.
[ACC-09](08-verification.md#requirement-scenario-test-traceability) owns executable proof of the bounded
readiness, duplicate, and recovery contract.

## Bounded observer ingress

Before the one observation-start call, the attachment installs its fence and timing evidence so an inline
callback cannot outrun ownership. Each accepted callback performs only an O(1) fenced update under
`sessionGate`, receives one nonreused observation-local semantic sequence, makes the update durable before
requesting the coalesced Control wake, and returns. Sequence exhaustion fails before reuse; the sequence is not a
cross-boundary Session identity.

The observation retains only:

- the earliest timed positive value;
- sticky first loss after a positive value and before first Active;
- the latest nullable value;
- the first terminal callback and whether joint readiness had committed;
- the normally returned exact close handle and its adoption state.

There is no per-callback queue. Summary cells preserve semantic sequence order rather than callback arrival,
worker-drain, or timestamp order. The first terminal callback closes ingress and wins permanently; later values
or terminal callbacks change nothing. A reported `onFailure(cause)` retains that exact raw `Throwable` reference
as source data and closes ingress. In particular, an `Error`, including `OutOfMemoryError`, delivered as the
`onFailure` argument is reported data; it is not a direct engine throw.

Source ingress and first Active are ordered by `sessionGate`. Metrics supplies the bounded cells and their order;
Control alone folds them, determines duplicates, combines geometry, and selects lifecycle or publication.

## First-positive safety and joint readiness evidence

`firstMetricsPositiveSafetyNanos` is exactly `5_000_000_000L`. It applies once to the attachment's first usable
positive sample. After Metrics-role entry and immediately before `subscribe` or the built-in attach/register
call, the engine samples elapsed realtime `S`, constructs `D = Math.addExact(S,
firstMetricsPositiveSafetyNanos)`, makes `S,D` durable under `sessionGate`, releases the gate, and only then calls
outward. Overflow fails before the call.

A positive callback samples elapsed realtime `T` in the same gated section that durably records the earliest
positive tuple. Only `T < D` is timely; `T == D` is expired. Mechanical joint-readiness evidence exists only when
that timely earliest positive tuple and the same attachment's normally returned, nonnull, adopted exact close
handle coexist before expiry and before any earlier loss or terminal callback. Handle return may follow the
inline positive sample. Full return of the attachment call may therefore establish readiness from staged data;
staged callbacks cannot establish readiness while `subscribe` has not returned.

Expiry emits the attachment's first-positive-expired fact and precreated raw boundary evidence. The product
contract owns its visible classification, and Control owns application and diagnostic selection. A real late
positive or handle is cleanup-only and cannot revise the expired fact. The interval is not a `subscribe`
cancellation, return, or release receipt; it is not restarted by loss or recovery and is not a watchdog for the
long-lived observation or `close`. Queued-before-entry work, a nonreturning `subscribe`, and close have no Metrics
watchdog.

## Exact operation and result facts

Metrics emits only the following physical facts and ownership changes. Product maps them to caller-visible
outcomes; Control applies them.

| Operation result | Exact Metrics fact or ownership action |
| --- | --- |
| timely earliest positive plus normally returned nonnull exact handle, before loss/terminal/expiry | adopt the handle and offer joint-readiness evidence with the complete tuple; retain the live observation |
| normal `onComplete` before joint readiness, including positive-before-handle | close ingress and offer completion-before-readiness; if `subscribe` later returns a nonnull handle, adopt it solely for exact close |
| first-positive expiry | offer first-positive-expired with the fixed first-positive interval instance and raw evidence; retain physical observation ownership |
| `subscribe` throws `Exception` before joint readiness | retain the exact exception and offer observation-start failure; it outranks all staged callbacks while readiness is unresolved |
| `subscribe` returns a null/invalid interop handle before joint readiness | offer invalid-handle observation-start failure; it outranks all staged callbacks while readiness is unresolved |
| observer calls `onFailure(cause)` | retain the exact reported cause and readiness phase; first terminal wins |
| normal `onComplete` after joint readiness, including before first Active | close ingress, retain the last valid tuple, and offer exactly one completion-after-readiness fact for this observation lifetime |
| positive value after readiness while ingress is open | offer one complete source tuple |
| null or unusable value while ingress is open | offer availability loss with the exact readiness/Active phase |
| result after terminal cutoff | mutate only the same Metrics capsule/residue; offer no geometry, lifecycle, or publication fact |

A direct `Error` or other non-`Exception` throwable escaping `subscribe`, a built-in platform call, unregister, or
`close` is preserved as the identical raw fatal throwable for the architecture's fatal boundary. Metrics defines
no ordinary allocation-OOM boundary: a directly thrown `OutOfMemoryError` from those calls is therefore raw
fatal, not a Metrics `ResourceExhausted` fact. This is distinct from an `OutOfMemoryError` supplied to
`onFailure`, which remains reported source data as described above. Ordinary platform/custom `Exception`
families are contained by the Metrics boundary and retain their exact raw cause in the matching Metrics failure
fact.

## Handle adoption, close, and local residue

A normal nonnull return from `subscribe` is adopted immediately into the same `MetricsCapsule`, even when return
is late, readiness has already failed, a pre-readiness completion already closed ingress, or terminal cutoff has
won. Adoption creates one exact close obligation; it never revives semantic work. A null return proves no handle
only after the attachment call has actually returned and its result is durably recorded. Nonreturn, rejection,
or cancellation does not prove that no handle exists or that the source performed no work.

Completion, terminal cutoff, or another valid close trigger fences observer ingress and records close requested.
The exact `close()` call runs at most once, asynchronously on Metrics and outside all gates, only after the
attachment call has returned and its exact handle is adopted. It never runs inline on Control, never uses a
replacement role, and is never retried. Normal return is the sole exact close receipt. A returned `Exception`
retains the exact cause and unresolved handle/close ownership; a direct fatal or nonreturn retains the same exact
obligation. Timeout, cancellation, work-queue state, or role availability is not a close receipt.

Built-in close first fences Main-callback authority and clears refresh admission, then invokes
`unregisterDisplayListener(listener)` on Metrics. If registration failed after it may have escaped, the matching
unregister is still required. Normal unregister is the built-in close receipt. Throw or nonreturn preserves the
listener and the dependencies needed to keep its ownership truthful. A Main callback already present in the
framework queue may arrive after unregister or after terminal cutoff/attachment closure; the fence makes it
inert, with zero refresh, read, publication, or lifecycle action.

At terminal cutoff, unresolved attachment or close ownership stays in the one exact Metrics capsule. Its local
residue is limited to the selected source, observer/fence and bounded summary, possible future handle, close
obligation, and—only for a built-in attachment—the listener, `DisplayManager`, Main Handler reference,
epoch/window state, pending refresh, and dependencies needed by entered work. It excludes Capture's Handler
lane, every other observation, and every other Session. A real late return may reduce only that same typed
residue under internal architecture; it cannot publish metrics or change the terminal result.

## Negative boundary

Metrics introduces no lifecycle/currentness authority, geometry generation, event stream, per-callback queue,
polling loop, source replacement, second attachment, duplicate close, close watchdog, synchronous Control call,
Control-side blocking read, private executor/pool abstraction, thread-termination receipt, generic operation
framework, or cleanup graph. It never predicts memory availability, treats timeout/cancellation as release,
combines source dimensions with projection dimensions, or claims that a metrics Display identifies captured
content.
