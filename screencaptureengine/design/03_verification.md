# Screen Capture Engine — Verification

## 1. Purpose

This document defines how the behavior in [01 Design](01_design.md) and the implementation boundaries in
[02 Architecture](02_architecture.md) will be verified. Those documents remain the product and architecture
authority. A test checks their observable result; it does not introduce another lifecycle, fallback, timing,
memory, or platform rule.

Gate A reviews the scenarios and high-risk interleavings below for an unambiguous implementable result.
Executable tests and a short practical device smoke check follow implementation authorization.

The verification approach is deliberately small:

- deterministic unit tests for public values, controller decisions, pacing, geometry, accounting, and
  failure classification;
- controlled interleaving tests for the few races that can change a public result or resource owner;
- component tests for Android, EGL/GL, JPEG, dispatcher, memory, and cleanup boundaries;
- integration tests for representative end-to-end capture paths;
- one short post-implementation manual smoke pass on available physical devices.

Tests use fakes, barriers, and an injected engine clock where determinism matters. Correctness tests do not
depend on real sleeps, scheduler luck, log parsing, or repeated execution until a race happens.

## 2. Acceptance Scenario Map

The following 27 scenarios are the material observable contract. A scenario may be covered by several small
tests; it does not imply one large end-to-end test.

| Scenario | Required observation |
| --- | --- |
| 1. Public surface | The packaged public declarations, visibility, defaults, ordinary-class value semantics, and documented exceptions match 01 Design. Engine-produced value constructors remain internal. |
| 2. Parameter validation | Invalid scalar or structural input fails with `IllegalArgumentException` before capture work; valid boundary values are accepted without clamping. |
| 3. One-shot start | Exactly one `start` call can be accepted. A repeated or concurrent loser does not access, stop, or otherwise consume its supplied `MediaProjection`. |
| 4. Startup outcome | Successful `start` returns only after a visible `Running` state. Startup failure or caller cancellation produces the documented terminal state and exception. |
| 5. Stop | `stop()` synchronously fixes the terminal winner and closes new admission, then State publication and cleanup may finish asynchronously. Repeated calls are harmless. |
| 6. Latest-wins update | In any nonterminal `Running` state, an unequal `updateParameters` replaces the desired parameters and returns immediately. Equal desire is a no-op. Concurrent updates have one order; only the latest accepted desire must converge after input changes stop. |
| 7. Update reconciliation | Policy-only and density-only changes preserve eligible resources. A destructive change pauses output, drains and retires the old scope, builds one replacement, and resumes. Current pre-retirement denial suspends; current clean failure after retirement is terminal; stale safe results cannot publish. |
| 8. Metrics authority | The selected provider supplies positive density and the documented display association. API 24–33 use provider dimensions; API 34–37 wait for authoritative projection resize dimensions. |
| 9. Metrics loss and recovery | A missing runtime authority suspends with `CaptureUnavailable`; a later valid fact resumes. A provider failure is classified as documented, and stale geometry cannot replace the current generation. |
| 10. VirtualDisplay result | `createVirtualDisplay` null or `SecurityException` becomes `CaptureUnavailable`; a directly thrown `OutOfMemoryError` becomes `ResourceExhausted`; `IllegalStateException` and any other unexpected throwable become `InternalFailure`. Exactly one creation attempt is made for the Session. |
| 11. Geometry and pixels | Region, crop, rotation, mirror, output size, OES transform, orientation, color, and grayscale produce the documented image dimensions, orientation, and semantic transform through Full and every eligible Early Surface Downscale path. Downscaled output may contain ordinary filtering, rounding, and minor platform differences. |
| 12. Color handling | sRGB remains nominal sRGB; exact Display-P3 takes the documented conversion path; wide/HDR input follows the documented best-effort behavior and emits the required diagnostic observation. |
| 13. Fresh-frame pacing | `Auto`, `MaxFps(1..120)`, and `SampleEvery(1_001..3_600_000ms)` admit fresh work according to their separate contracts using elapsed realtime. A late wake causes at most one action and no catch-up burst. |
| 14. Static repeat | A configured repeat interval of `1_000..3_600_000ms` republishes only a valid cached JPEG, with new sequence and timestamp, without GL or JPEG work. Fresh output wins ties and `MaxFps` remains the output cap. |
| 15. Cache and Stats | A fresh encode, repeat, cached-first offer, rejected delivery, and terminal transition update cache and counters exactly as documented. Stats publish no more than once per second plus important transitions. |
| 16. Baseline output | The Full GLES2 Direct-readback plus Framework-JPEG baseline on API 24–37 produces a decodable JPEG of the exact documented dimensions and orientation and does not expose raw or partial pixels. |
| 17. Independent optimized paths | Early Surface Downscale, PBO, Native JPEG, and Display-P3 are selected only by their documented static/runtime capability checks. Eligible Downscale is automatic on API 32–37. PBO health is independent of JPEG backend health; a safe failure disables only the affected optimized path for later frames. |
| 18. Safe and unsafe fallback | A safely returned optional-path failure may drop the affected frame and at most one switchover frame, then uses the documented fallback without double encoding. Ambiguous ownership, nonreturn after possible transfer, or unsafe cleanup is terminal and quarantined. |
| 19. Consumer registration | The Session has zero or one current-or-draining frame consumer. A second registration fails synchronously until the previous subscription has successfully unsubscribed. |
| 20. Cached-first delivery | A new registration receives the current valid cached JPEG immediately when one exists, preserving its original bytes, sequence, timestamp, and `ImageSize`. This does not encode, increment `framesProduced`, or alter pacing. |
| 21. Delivery outcomes | Callback success, callback throw, and synchronous dispatcher rejection each settle the exact handoff once. Throw or rejection drops that delivery but keeps the registration usable for a later output. |
| 22. Unsubscribe | `unsubscribe()` immediately closes new delivery, is idempotent, and waits for its queued or entered callback to settle. Successful return permits replacement registration; self-unsubscribe fails fast; waiter cancellation does not fabricate settlement. |
| 23. Five-second entry deadline | An accepted callback task that has not entered within 5 seconds terminally fails the Session. A late trampoline invokes no user code but may provide its exact resolution. An entered callback has no execution watchdog. |
| 24. Memory and privacy | Checked planning admits only owned target, GL/readback, encoder, JPEG, cache, callback lease, rebuild, and quarantine storage. Allocation denial does not mutate uncommitted authority. Raw, partial, stale, or unleased data never publishes. |
| 25. Terminal cleanup | Only one terminal winner is published. Independent cleanup continues in dependency order; an unresolved resource remains rooted once in the Session quarantine instead of being treated as released or reusable. |
| 26. Diagnostics | Diagnostic events carry monotonically increasing Session-local `sequence`, `timestampEpochMillis`, `source`, `label`, short `message`, and raw nullable `cause`. Required modes, transitions, fallback, abnormal delivery, memory decisions, visibility, and terminal outcomes are attempted; diagnostics never decide lifecycle or business behavior. |
| 27. Session isolation | Multiple Sessions share no lifecycle, projection, desired parameters, cache, callback registration, counters, failure, or quarantine authority. Aggregate process pressure remains the application's responsibility. |

## 3. Common Test Rules

### 3.1 Time

Pacing, repeat, Stats cadence, readiness, operation deadlines, and callback entry deadlines use one injected
clock corresponding to raw `SystemClock.elapsedRealtimeNanos()`. Test cases advance that clock directly and
then run the relevant wake. They cover a fact just before, at, and after a deadline where the distinction
changes behavior.

A timer or coroutine delay is only a wake request. On wake, the engine resamples elapsed realtime and
rechecks current state and generation. Tests change wall time, producer timestamps, and delayed scheduling to
confirm that none becomes timing authority. Deep sleep is represented by advancing elapsed time without
executing work, then delivering one wake; the result is at most one current action.

The fixed externally visible timing rules are:

- callback task entry deadline: 5,000 ms;
- Stats periodic publication: no more often than every 1,000 ms;
- `MaxFps`: 1 through 120;
- `SampleEvery`: 1,001 through 3,600,000 ms;
- optional repeat: 1,000 through 3,600,000 ms.

Other readiness and entered-operation deadlines are positive finite, non-public Gate-B constants. Gate B
presents their exact values for explicit user agreement before implementation authorization. They create no
SLA or additional product timing semantics; tests use an agreed value only to verify the existing timely and
timeout dispositions.

### 3.2 Fault injection

Each external boundary is exercised with the small set of outcomes that can change disposition:

1. normal return;
2. documented returned failure or throwable;
3. nonreturn after entry where ownership may have changed;
4. a late result after its state, generation, or operation is no longer current.

Safe returned faults use the documented rejection, suspension, fallback, or terminal classification. A
nonreturn is never treated as implicit cancellation or release. A late safe result can clean up only its own
resources; it cannot publish output, revive the Session, or replace a current decision.

### 3.3 Resource observation

Test doubles expose acquisition, ownership transfer, detach, release return, and outstanding owners. A
reservation is not a release receipt. Tests assert that each admitted object is either:

- still owned by the active current scope;
- released after the matching operation returned;
- transferred to the new owner; or
- retained once by the Session quarantine because safe release is unknown.

This ownership observation is recorded locally by the component test.

### 3.4 Image oracle

Image tests use a few asymmetric synthetic scenes: distinct colored corners, odd dimensions, a one-sided
marker, horizontal and vertical gradients, and grayscale ramps. These scenes make channel swaps, row/stride
errors, upside-down output, wrong rotation/mirror/crop, unexpected bars, grayscale mistakes, and catastrophic
JPEG corruption visible with a small test set.

For raw geometry stages, the oracle checks output dimensions, mapped landmarks, bounds, and orientation. For
JPEG, the oracle requires successful decode, exact dimensions and orientation, expected landmark placement,
recognizable color/grayscale relationships, and absence of catastrophic corruption. It does not require
byte equality or exact decoded-pixel equality between lossy encoders. Gate B supplies a small fixed set of
vectors and finite numeric tolerances for automated image checks.

## 4. Public Contract And Lifecycle Tests

### 4.1 API and values

One table-driven suite checks the public inventory and every parameter default/range in 01 Design. It also
checks these architectural surface rules:

- caller-created input types have only the documented public constructors;
- engine-produced State, Stats, effective output, `ImageSize`, frame, diagnostic event, and exception values
  cannot be constructed by callers;
- public models are ordinary classes, with manual equality/hash/text only where 01 Design promises it;
- public signatures expose no implementation types or unintended data-class `copy`/`componentN` surface;
- `EncodedImageFrame` exposes `ImageSize`, not `android.util.Size`;
- `updateParameters` is synchronous and returns `Unit`;
- `FrameSubscription` exposes suspending `unsubscribe()` and no cancellation alias.

Constructor and update local validation cover all lower and upper scalar/structural boundaries, non-finite
scale, nonpositive dimensions, nonnegative crop-inset boundaries, invalid frame-rate/repeat ranges, JPEG
quality, Kotlin non-null contracts, and integer-overflow-prone local combinations. Locally invalid values create
no Session work. Whether locally valid crop insets leave a nonempty source is geometry-dependent feasibility;
startup and asynchronous reconciliation test that outcome below rather than treating it as local validation.

### 4.2 Start, update, and stop

Controlled barriers select both orders of concurrent `start` calls. The winner alone registers callbacks and
touches its projection. Startup tests then cross valid metrics, missing metrics, projection stop, caller
cancellation, and each VirtualDisplay outcome in Scenario 10.

A startup geometry-feasibility case supplies locally valid nonnegative crop insets that consume the selected
source under the authoritative geometry. Parameter construction succeeds; startup then becomes
`Failed(InvalidRequest)` and `start` throws `ScreenCaptureException(InvalidRequest)` rather than rejecting the
crop as a local validation error.

A startup-metrics invalidation case first supplies a valid tuple, then supplies nonpositive geometry or density
before initial `Running(Active)` commits. The Session becomes `Failed(CaptureUnavailable)` and `start` throws
`ScreenCaptureException(CaptureUnavailable)`. The invalid fact attempts source `MetricsProvider`, label
`CapabilityCheck`, message `Required capture metrics became unavailable during startup`, and the raw nullable
boundary cause. The cause-free injected invalid-value case expects null `cause`. Terminal commit also attempts
source `Session`, label `SessionTerminal`, message
`Session failed: CaptureUnavailable; last active modes: none`, and null `cause`.

Update tests begin in every nonterminal `Running` variant. They cover:

- desire equal to current desire;
- desire equal to effective parameters but different from current desire;
- multiple concurrent unequal calls with a recorded acceptance order;
- geometry or lifecycle change while reconciliation is running;
- invalid or unavailable latest desire retained across later availability;
- a locally valid crop that consumes the selected source under current geometry, which is accepted as desire,
  reconciles to `Running(Suspended(InvalidRequest))`, and is retried after a new desire or geometry fact;
- clean denial before retirement and clean failure after retirement;
- stale safe completion and stale ownership ambiguity;
- desired-revision exhaustion, which terminally fails with `InternalFailure`;
- a JPEG-quality-only change, which invalidates the old cache/repeat source without changing unrelated
  pacing or healthy backend selection;
- quiescence after a burst of updates.

The final assertion is simple: after desired parameters, geometry, lifecycle, and relevant availability stop
changing, the latest accepted desire reaches `Active`, an exact `Suspended` problem, or terminal failure.
Intermediate desires need not be applied.
When irreversible retirement has completed but no current replacement has committed, the documented
`Reconfiguring` suspension is observable only for that real gap; it is not an update result or a queued-update
state.

Stop tests pause work before and after its admission/commit points. After `stop()` returns, no new start,
update, frame acquisition, encode, repeat, cached offer, or callback entry can be admitted. A callback that
already entered may still return later. Final Stats precedes terminal State publication.

## 5. Android Capture And Metrics Tests

### 5.1 Platform branches

Branch tests cover the actual API splits:

| API range | Geometry and display observation |
| --- | --- |
| 24–29 | `fromUiContext` requires an Activity-backed/unwrappable context; explicit display helpers remain available. The provider supplies width, height, and density. |
| 30–33 | Window/display association follows the documented provider helper. The provider supplies authoritative width, height, and density. |
| 34–37 | Provider density remains required. Projection `onCapturedContentResize` supplies authoritative width and height; no frame is admitted before the first valid resize. Visibility is informational only. |

For each branch, tests supply valid values, null/invalid values, provider completion before first valid value,
provider failure before and after readiness, density-only change, dimension change, display change, and stale
callback facts. API 34–37 additionally verify provisional startup target handling, first-resize timeout,
matching-Full retention, conditional retarget, and resize after Running.

An API-surface check confirms that every call introduced after API 24 is guarded by its documented runtime
branch. It covers MediaProjection callback registration/order, resize and visibility availability, foreground-
service owner obligations exposed to the caller, current projection-reuse restrictions, native 16-KiB page
compatibility, and the documented Android 17 process-memory limitation. These checks validate the supported
API 24–37 contract without requiring a separate device for every API level.

The default provider test verifies that the documented `Display.DEFAULT_DISPLAY` choice and its public caveat
are honored. Explicit `fromDisplay`, `fromUiContext`, and `fromActivityDisplay` helpers are checked against
their documented association and rejection rules.

### 5.2 MediaProjection, VirtualDisplay, and target lifetime

Component tests record all callback registration, VirtualDisplay creation/mutation, Surface attachment, and
cleanup calls. They verify:

- projection callback registration precedes the sole `createVirtualDisplay` call;
- the sole `createVirtualDisplay` call passes a null `VirtualDisplay.Callback` and null callback Handler;
  `MediaProjection.Callback.onStop` is the sole platform callback authority for `CaptureEnded`, while explicit
  `VirtualDisplay.release` remains part of cleanup;
- the VirtualDisplay always uses authoritative logical `W x H` and density. On API 32–37, an eligible Early
  Surface Downscale changes only the smaller exact-aspect Surface target and relies on the official uniform,
  aspect-preserving fit-and-center behavior;
- API 32–37 Downscaled is selected automatically only when every closed eligibility condition and exact-aspect
  size calculation in 02 Architecture succeeds; otherwise the API 24–37 Full baseline is selected;
- a safely returned Downscaled incompatibility disables that target path and permits the single documented
  rebuild to Full; uncertain target ownership is terminal;
- density-only change uses the documented pause, drain, resize, cache invalidation, and resume path;
- dimension or target-health change uses the destructive rebuild order;
- after resume, the first available producer buffer may be used; target generations still reject stale
  engine work and results;
- SurfaceTexture-listener generation fencing, listener detachment, `setSurface(null)`, Surface return, SurfaceTexture release,
  and GL destruction occur in the documented dependency order.

Platform failure classification is asserted directly:

| `createVirtualDisplay` observation | Problem |
| --- | --- |
| null return | `CaptureUnavailable` |
| `SecurityException` | `CaptureUnavailable` |
| directly thrown `OutOfMemoryError` | `ResourceExhausted` |
| `IllegalStateException` | `InternalFailure` |
| any other unexpected throwable | `InternalFailure` |

No test infers recovery from a new Session after ambiguous process-level ownership. A caller may create a
new Session with fresh consent; that new attempt makes no claim that old unresolved resources were repaired.

## 6. Frame Production And Image Tests

### 6.1 Geometry, GL, and color

The asymmetric scenes in Section 3.4 cross the material geometry choices that can produce different visible
behavior. The suite includes:

- one Full source with odd dimensions and nonzero crop;
- one half-region case;
- each rotation with one asymmetric marker;
- horizontal and vertical mirror;
- `ScaleFactor` and `TargetSize` with each content mode where behavior differs;
- one eligible exact-aspect Early Surface Downscale case and one ineligible Full case;
- sRGB color, exact Display-P3 conversion, grayscale, and one unsupported wide/HDR classification.

For each case the test checks final `ImageSize`, landmark placement, orientation, active-content bounds,
expected bars or absence of bars, opaque output, and diagnostic color action where required. Shader precision
selection checks highp first and mediump fallback, and records the selected result diagnostically.

Baseline GL tests check texture/FBO completeness, FBO-only readback, tight RGBA layout, row direction, alpha,
and cleanup. Framework Bitmap tests exercise both direct tight-row copy and the reusable row-conversion path.

### 6.2 JPEG and encoded ownership

The Framework baseline and every enabled Native product use the image oracle in Section 3.4. The test checks
quality forwarding, transactional output, immutable published bytes, exact dimensions/orientation, and
recognizable content. Partial, stale, failed, or over-budget output is discarded and never replaces the
valid cache.

Framework outcome tests keep the result partition small: `Bitmap.compress` false is one frame `byFailure` and
later frames continue; Bitmap/sink memory exhaustion is terminal `ResourceExhausted`; an unexpected exception
or malformed sink result is terminal `InternalFailure`; and ambiguous ownership takes the terminal quarantine
path. No failure publishes partial bytes or retries the same frame.

Native capability tests verify only the documented API/ABI/page-size/symbol/probe inputs. A safe native
failure drops the affected frame, disables Native for later frames, and leaves Direct/PBO selection
unchanged. Native nonreturn or ambiguous output ownership is terminal.

### 6.3 Pacing, repeat, cache, and Stats

A fake source and fake clock exercise fresh signals just before, at, and after each selected pacing boundary.
`Auto`, `MaxFps`, and `SampleEvery` are tested separately. Bursts collapse to the documented latest-pending
work; PBO may overlap the documented GPU work with the prior encode but never creates a second stable CPU
pixel carrier or a third materialized attempt.
Static or blank source content does not create a fabricated first-frame timeout.

Repeat tests cover absent cache, valid cache, cache invalidated by pause/rebuild, a fresh-frame tie, a delayed
wake, and `MaxFps` interaction. A repeat reuses immutable JPEG bytes while producing new frame metadata.

Stats assertions distinguish successful encodes, produced outputs including repeats, and every documented
frame- and delivery-drop reason. Counter saturation is safe. Periodic dirty Stats are published at most once
per second, while lifecycle, fallback, rebuild, and terminal facts request immediate publication.

## 7. Optional Paths And Fallback Tests

The optional-path suite is organized by four independent axes:

| Axis | Enabled observation | Safe failure | Unsafe failure |
| --- | --- | --- | --- |
| Early Surface Downscale | Closed eligibility, exact-aspect target, and automatic API 32–37 selection | Disable Downscaled and rebuild once to Full | Terminal and quarantine |
| PBO readback | Documented ES3/config/call capability checks | Destroy fence/PBO, disable PBO, use Direct later | Terminal and quarantine |
| Native JPEG | Documented API/ABI/page-size/symbol/probe checks | Disable Native, use Framework later | Terminal and quarantine |
| Display-P3 color | Exact dataspace classification | Use the documented best-effort color action | Terminal only when the ordinary pipeline itself is unsafe |

Each axis is tested once enabled, once ineligible, once with a safe returned runtime fault, and once with an
ownership-ambiguous fault. A small integration set crosses the four reachable readback/JPEG products:
Direct+Framework, Direct+Native, PBO+Framework, and PBO+Native. It verifies decodable output and independence:
a PBO fallback does not disable Native, and a Native fallback does not disable PBO.
The PBO component cases cover every documented fence-wait result plus map, unmap, context-loss, and destruction
outcomes; they preserve the same small public safe/unsafe classification rather than creating product modes.

Downscaled integration checks the public output dimensions and orientation, the expected semantic transform,
active-content coverage, and the single Full fallback after a detected runtime target-path failure. Ordinary
filtering, rounding, and minor platform differences are accepted and Full-path pixel identity is not an
oracle. Runtime selection uses the closed eligibility and runtime-failure rules.

## 8. Frame Consumer Tests

Registration tests cover no cache, a valid cache, an invalidated cache, and registration concurrent with a
fresh output commit. Exactly one of the valid current cached image or the later fresh image is offered first
according to the documented serialization; neither is duplicated. Cached-first uses the ordinary sole
handoff and lease.

Dispatcher/callback tests independently exercise:

- synchronous dispatch rejection;
- dispatch call nonreturn;
- trampoline entry followed by a late dispatch throw, where entry owns settlement and no
  `byDispatchFailure` is recorded;
- accepted task entering before 5 seconds;
- accepted task still queued at 5 seconds;
- late trampoline after terminal timeout;
- callback normal return;
- callback throw;
- entered callback nonreturn.

Detachment is also raced while `dispatcher.dispatch` is still in-call. If unsubscribe or stop detaches the
record and dispatch later returns normally, that return arms the same existing five-second
entry/self-rejection deadline. A later trampoline entry resolves the detached record without invoking user
code. No second timer or deadline occurrence is created.

Only the accepted-but-not-entered deadline is fixed at five seconds. Synchronous rejection and callback throw
settle one delivery and permit a later output. Dispatch nonreturn and entered callback nonreturn retain their
exact record/lease when resolution is unknown. A late trampoline after the five-second terminal failure is
fenced from user code and may resolve only its own record.

Unsubscribe tests start from idle, prepared, dispatching, accepted-queued, entered, resolved, failed, and
stopped conditions. They assert immediate closure, idempotent waiters, waiter cancellation, self-call
rejection, terminal exception mapping, and the requirement that replacement registration begins only after
successful unsubscribe. Old and replacement callbacks never overlap.

Frame-lease tests verify successful property access, `copyTo`, and `copyBytes` during the callback on its
callback thread. An invalid `copyTo` destination range throws `IndexOutOfBoundsException` without modifying
the destination. Wrong-thread and post-return property or copy access throws `IllegalStateException`.

## 9. Memory, Diagnostics, Cleanup, And Privacy Tests

### 9.1 Memory admission

Memory tests use small synthetic budgets and checked arithmetic. They separately account for the selected
target, RenderTarget, sole CPU carrier, optional PBO, Framework Bitmap/row storage, transactional JPEG,
immutable cache, single callback lease, rebuilding overlap, and already-known quarantine.

Each allocation boundary is tested for admission denial, successful allocation, directly returned OOM,
partial construction with safe return, and nonreturn after possible ownership transfer. A clean denial before
retirement leaves the old pipeline parked and suspends. A clean current failure after retirement is terminal.
Physical charge falls only after the corresponding release operation returns; a planner permit or refund is
not physical-release evidence.

Privacy assertions verify that raw pixel storage, mutable encoder input, partial JPEG bytes, stale results,
and borrowed frame wrappers never outlive their owner or cross the callback lease. Caller-copied JPEG bytes
remain caller-owned. Crop and source-region selection are not treated as security redaction.

### 9.2 Diagnostics

Tests subscribe before start because the SharedFlow has no replay. They verify `replay = 0`, buffer 128, and
drop-oldest behavior without treating delivery as reliable. Every attempted event receives the next
Session-local `sequence`; a collector may observe gaps when events are dropped. `timestampEpochMillis` is the
documented system timestamp for correlation with other logs, not lifecycle or ordering authority.
The first attempted event has sequence 1, and every later attempt advances sequence before `tryEmit`, so
overflow remains visible as gaps. `timestampEpochMillis` samples wall clock when the event is created; repeated,
forward, or backward wall-clock values do not affect sequence ordering or engine control.

The single diagnostics table in 01 Design is the required source/label coverage list. Tests trigger each
material mode, capability result, geometry/visibility change, memory decision, rebuild, fallback,
consumer change/problem, color action, quarantine change, Stats abnormality, and terminal outcome, then
check `source`, `label`, a short useful `message`, and the original nullable `cause`. They do not parse
messages as a business protocol, require compound event IDs, or require routine per-frame diagnostics.

### 9.3 Cleanup and quarantine

Terminal tests trigger owner stop, capture end, startup failure, callback-entry timeout, safe terminal
resource failure, and unsafe ownership. Exactly one terminal winner closes all admission. Final Stats is
assigned before terminal State; diagnostic delivery remains best effort.

Cleanup component tests hold one dependency indefinitely while allowing unrelated cleanup roots to finish.
The Android subchain remains serial; target, GL, JPEG, callback, memory, and diagnostics roots follow their
documented local order. An unresolved owner is recorded once in `SessionQuarantineRoot`; late return may
release only that exact owner and cannot change terminal outcome or make the Session reusable.
For `Surface.release`, normal return supplies the Surface-return receipt and permits dependent target cleanup;
a returned throw is recorded and leaves the Surface unresolved while unrelated roots continue; nonreturn
roots the call and blocks only its physical dependents.

## 10. High-Risk Paper Traces

These seven traces are reviewed before implementation. Executable tests later reproduce both relevant
orders with barriers. They are ordinary readable traces, not formal proofs.

### 10.1 Concurrent start

1. Start A and start B arrive at the Session gate.
2. A linearizes first and changes `NotStarted` to `Starting`.
3. B observes `Starting`, fails, and its projection has zero recorded access.
4. A alone registers platform callbacks and reaches Running or the single terminal startup outcome.

Reversing steps 2–3 changes only which caller wins.

### 10.2 Latest desire versus stale rebuild

1. Desire A begins a destructive rebuild for key `(A, geometry 1, lifecycle 1)`.
2. Desire B replaces the desired cell before A completes.
3. A returns safely; its key is stale, so it publishes nothing and releases its local result.
4. Reconciliation runs for B and may alone commit the next effective output.

If A returns with ambiguous ownership, safety wins and the Session fails even though A is stale.

### 10.3 Geometry change during startup

1. API 34+ starts with provisional target geometry but frame admission is closed.
2. The first valid projection resize supplies authoritative width and height.
3. If Full already matches exactly, the target is retained; otherwise it is retired and rebuilt.
4. Only the authoritative current target may enter Running and produce a frame.

If the readiness deadline wins first, startup fails with `CaptureUnavailable`; a later resize cannot revive it.

### 10.4 Safe PBO failure while Native remains healthy

1. PBO readback for one frame returns a safely classified error.
2. That frame is discarded; fresh admission pauses for the PBO scope.
3. Fence and PBO destruction return, then PBO becomes disabled.
4. Later frames use Direct readback and still use Native JPEG.

A nonreturn or uncertain fence/buffer ownership takes the unsafe terminal path instead.

### 10.5 Unsubscribe versus queued callback

1. One callback task is accepted by the dispatcher but has not entered.
2. `unsubscribe()` closes the registration and waits for that exact record.
3. The trampoline enters before the five-second deadline, sees the closed generation, invokes no user code,
   and resolves the record.
4. `unsubscribe()` returns successfully; only now can a replacement register.

If the entry deadline wins, the Session fails and replacement remains forbidden until the documented terminal
mapping; timeout alone does not fabricate record release.

If unsubscribe instead detaches while `dispatcher.dispatch` remains in-call, a later normal dispatch return
arms the same existing five-second entry/self-rejection deadline. Trampoline entry resolves that detached
record without user-code entry. This is another ordering of the same handoff occurrence, not a new timer or
trace.

### 10.6 Stop versus callback entry

1. A dispatched trampoline and `stop()` race for their respective commit points.
2. If callback entry commits first, user code may run and return after `stop()` returns.
3. If stop commits first, the trampoline is fenced and invokes no user code.
4. In both orders, no later frame delivery is admitted and the callback lease settles or remains quarantined.

### 10.7 Memory denial around retirement

1. Reconciliation calculates the complete replacement admission requirement.
2. If clean admission is denied before old-pipeline retirement, the old scope is parked and State becomes
   `Suspended(ResourceExhausted)` with latest desire retained.
3. If preflight succeeded, the old scope retires before replacement ownership is admitted.
4. A current clean replacement allocation failure after that point is terminal `ResourceExhausted`.

No branch silently restores a resource already destroyed or keeps two healthy complete pipelines.

## 11. Coverage Map

This map is used to check that simplification did not remove a material behavior. It is not a second test
taxonomy.

| Concern | Acceptance scenarios | Primary executable sections | Paper trace |
| --- | --- | --- | --- |
| Public API and values | 1–2 | 4.1 | — |
| Lifecycle and latest-wins updates | 3–7 | 4.2 | 10.1, 10.2, 10.6 |
| Metrics and Android capture | 8–10 | 5 | 10.3 |
| Geometry, color, JPEG | 11–12, 16 | 6.1–6.2 | — |
| Pacing, repeat, cache, Stats | 13–15 | 6.3 | — |
| Optional paths and fallback | 17–18 | 7 | 10.4 |
| Consumer, frame lease, and callback deadline | 19–24 | 8, 9.1 | 10.5, 10.6 |
| Memory, cleanup, privacy | 24–25 | 9.1, 9.3 | 10.7 |
| Diagnostics | 26 | 9.2 | — |
| Independent Sessions | 27 | 4.2, 9 | — |

## 12. Post-Implementation Manual Smoke Check

Before release, run a short manual smoke check on an available physical device. Brief test notes are
sufficient. Confirm consent and start, visible JPEG output, rotation/resize, a parameter update, consumer
unsubscribe/re-register, stop, and absence of an obvious crash, leak, black frame, wrong orientation, or
broken color. Exercise an optional path only when the device naturally enables it.

This smoke check is outside Gate A. It supplements automated tests for real MediaProjection, Surface,
BufferQueue, GPU/driver, and native behavior; it does not select runtime behavior or create a device allowlist.

## 13. Gate-A Verification Readiness

The verification design is ready for Gate A when:

- every scenario in Section 2 has the unambiguous expected result stated here or in 01 Design;
- the component boundaries and failure classifications needed to implement those scenarios are fixed in
  02 Architecture;
- the seven high-risk traces have no missing owner, contradictory winner, or hidden product decision;
- the automated JPEG checks use the finite oracle in Section 3.4, while the practical device smoke check
  remains a post-implementation activity;
- a fresh independent review reports no unresolved material product or architecture contradiction in the
  five-document package.

Gate-A status and the review decision are recorded in [04 Gate A](04_gate_a.md). Exact implementation
constants, concrete image vectors/tolerances, and executable test organization are completed in
[05 Gate-B Inputs](05_gate_b_inputs.md) before Kotlin implementation begins.
