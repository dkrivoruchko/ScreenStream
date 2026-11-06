# Capture adapter and independent cleanup

[Scenario index](../02-verification-contracts.md) · [Testing](../01-testing.md)

`MjpegCaptureSession` runs one capture attempt: it obtains consent and a projection through Core, hands the projection to a Capture Engine session, copies borrowed JPEGs into HTTP delivery and cleans up everything it acquired. These scenarios exercise that attempt from admission to its cleanup receipt. [Capture lifecycle](../../01-module/02-module-lifecycle.md) owns the rules, extending the public [startup flow](../../../docs/architecture.md#starting-capture) and [Stop behavior](../../../docs/architecture.md#stop); the [Capture Engine usage guide](../../../../../screen-capture-engine/docs/usage.md) and Core's [Screen Capture Access](../../../../core/docs/screen-capture.md) own the external contracts.

## Test boundary

Use L1 with a real session. A test controls these inputs:

- **Consent and projection.** A `ScreenCaptureAccess` that controls consent results, the projection, finishing projection creation, and saving consent for reuse.
- **Engine.** An engine factory that returns a controllable engine session, with its current state, state and statistics flows, start, stop and consumer registration.
- **HTTP.** A fake or real `HttpDelivery`.
- **Foreground.** The two foreground callbacks.
- **Scope.** The scope that hosts the attempt.

Real projection, revocation and foreground admission need [platform qualification](09-integration.md#mjpeg-cfg-04).

The session keeps its first capture failure and its first cleanup failure as diagnostic stages, which tests assert at the session boundary. The manager only sees a generic error.

| Stage | Recorded when |
| --- | --- |
| `Startup.consent` | Core's consent request fails before foreground preparation starts |
| `Startup.foreground` | The consent request fails after foreground preparation has started |
| `Startup.projection` | Projection acquisition fails |
| `Engine.creation` | The engine factory throws. The raw projection is still MJPEG's. |
| `Startup.projectionHandoff` | Finishing Core's projection creation fails after handoff |
| `Engine.registration` | Registering the JPEG consumer fails |
| `Engine.start` | Engine startup fails |
| `Engine.state` | The Engine reports Failed |
| `Engine.stateObservation` | Collecting Engine state throws |
| `Output.frame` | Reading, offering or copying a borrowed JPEG fails |
| `Cleanup.consumer`, `Cleanup.media`, `Cleanup.projectionCreation`, `Cleanup.consentReuse`, `Cleanup.foreground` | The matching cleanup step fails |
| `Cleanup.capture` | Cleanup throws unexpectedly, or the capture coroutine ends without a cleanup outcome |

## Startup

<a id="mjpeg-cap-01"></a>

### MJPEG-CAP-01: Inert, single-use attempt

**History.**

1. Construct a session and collect its state.
2. Call `start()` and refuse consent.
3. Call `start()` again.

**Expected.**

- **Inert until started.** Construction and collection start no work. The initial status is WaitingForPermission, with the initial capture generation.
- **Start proves nothing yet.** `start()` launches the attempt and returns. That return proves neither consent, startup nor an image.
- **Refusal.**
  - Refusal records reason User and no failure, and acquires no projection or engine.
  - Cleanup still settles: it finishes Core's projection creation and attempts foreground rollback, which tolerates a Service that was never promoted.
  - The final status is Stopped, with successful cleanup.
- **Single use.** The second `start()` throws `IllegalStateException` and creates nothing.

<a id="mjpeg-cap-02"></a>

### MJPEG-CAP-02: Early Stop and cancellation

**History.**

- Request Stop before the capture coroutine body runs.
- Request Stop while consent is held.
- Request Stop again with a different reason.
- Cancel one `awaitCleanup()` waiter.

**Expected.**

- **Stop before entry.** No Core call is made and nothing is acquired, and cleanup completes successfully without resources.
- **Stop during consent.** The consent request is cancelled, and cleanup continues without cancellation. It attempts foreground rollback.
- **Publication stops at once.** Stop revokes publication immediately: HTTP receives Stopping with no generation.
- **First reason wins.** The first accepted Stop reason is kept.
- **Waiters are passive.** `awaitCleanup()` neither starts nor stops capture. Cancelling one waiter leaves cleanup running and its result unchanged.

<a id="mjpeg-cap-03"></a>

### MJPEG-CAP-03: Foreground before projection

**History.** Grant consent in three variants: foreground preparation succeeds, Core reports that it failed, or it throws. Separately, cancel the attempt after promotion and before the grant arrives.

**Expected.**

- **Order.** MJPEG asks Core for the projection only after Core delivers the grant, which follows completed foreground preparation.
- **Failure after preparation began.** If the consent request throws after preparation started, the attempt records `Startup.foreground`.
- **Refusal result.** If Core returns a not-granted result, the attempt follows the refusal path without an error, whatever stage ended it.
- **Rollback.** Foreground rollback is attempted during cleanup.
- **Cancellation after promotion.** The attempt still owns the foreground rollback, as [Core's request ownership](../../../../core/docs/screen-capture.md#host-and-request-ownership) describes.

<a id="mjpeg-cap-04"></a>

### MJPEG-CAP-04: Creation and handoff failures

**History.** Fail each step independently:

- projection acquisition
- the engine factory
- finishing projection creation at handoff
- consumer registration
- Engine start

Then race Stop against each of them.

**Expected.**

| Failing step | Recorded stage | Who stops the projection |
| --- | --- | --- |
| Projection acquisition | `Startup.projection` | Nothing reached MJPEG; cleanup still finishes Core's projection creation |
| Engine factory | `Engine.creation` | MJPEG stops the raw projection |
| Finishing creation at handoff | `Startup.projectionHandoff` | The Engine session (MJPEG stops it) |
| Consumer registration | `Engine.registration` | The Engine session; no consumer to remove |
| Engine start, genuine failure | `Engine.start` | The Engine session |

- **Normal stop during startup.** When Engine start ends with cancellation because of a normal stop, such as the projection stopping, no failure is recorded. The exception is an Engine already in Failed at that moment, which records `Engine.state`.
- **Projection returned after Stop.** A projection that Core returns after Stop is still handed off and then stopped through the Engine session. Every owner that was acquired is settled.

**Evidence boundary.** A registration rejection supplied by a test doesn't prove Android revocation.

## Parameters and state

<a id="mjpeg-cap-05"></a>

### MJPEG-CAP-05: Initial image mapping

**History.** Supply independently chosen image settings and compare the initial Engine parameters and session configuration.

**Expected.**

- **Crop.** Enabled crop forwards all four insets. Disabled crop submits `CropInsetsPx.ZERO` and keeps the saved insets.
- **Scale mode.** A scale of 37.5% becomes `ScaleFactor(0.375)`.
- **Target mode.** Target mode submits the saved width, height and content mode, and keeps the dormant scale.
- **Unchanged values.** Region, rotation, mirror, color, frame rate and JPEG quality pass through unchanged.
- **Session configuration.** The configuration carries only the JPEG backend policy, fixed for the whole attempt. No metrics source is configured, so the Engine follows the default display.
- **Engine ownership.** Geometry resolution and the resulting pixels belong to the Engine.

<a id="mjpeg-cap-06"></a>

### MJPEG-CAP-06: Latest parameters through startup

**History.**

- Change image settings during consent and the early startup steps, and propose an encoder change.
- Hold Engine `start()` and change settings while it is held.
- Change settings during reconfiguration and suspension.
- Hold a parameter submission while the Engine publishes a terminal state.
- Make a submission throw `IllegalStateException`.

**Expected.**

- **Start parameters.** Engine `start()` receives the desired value that the session reads as it enters Engine startup. That read comes after consumer registration, before the session launches its observers and calls `start()`. Edits made during consent and the earlier startup steps are included.
- **Edits while start is held.** An edit made while `start()` is pending doesn't change the parameters of that call. It stays the desired value and is submitted once the session status is Active, Reconfiguring or Suspended. That can happen before `start()` returns, because the Engine's running phase can begin first.
- **Submission rules.** Later changes are submitted only while the session is Active, Reconfiguring or Suspended. A change is submitted only when it differs from the last value submitted, which at first is the start value.
- **No equal resubmission.** Equal values are never resubmitted, so MJPEG never triggers the Engine's equal-value retry. The encoder stays fixed.
- **Stop is final.** After Stop is accepted, updates are ignored and can't revive capture.
- **Independent state collection.** A held submission doesn't block the separate state collector, which still accepts the terminal state and begins cleanup.
- **Closed admission.** A submission rejected because Engine admission has closed is logged. It leaves the attempt unchanged, the value stays unsubmitted, and MJPEG adds no retry loop.

<a id="mjpeg-cap-07"></a>

### MJPEG-CAP-07: Confirmed startup and state

**History.** Publish each Engine phase before and after Engine start returns. Combine them with a retained failure, an accepted Stop, and successful or failed cleanup.

**Expected.** The status follows this order of precedence:

| Condition | Status |
| --- | --- |
| A capture or cleanup failure is retained, cleanup failed, or the Engine is Failed | Failed |
| Cleanup succeeded | Stopped |
| Stop accepted, cleanup pending | Stopping |
| No Engine observation yet | WaitingForPermission |
| Engine NotStarted or Starting | Starting |
| Engine Active, Reconfiguring or Suspended | The matching status |
| Engine Stopped, cleanup pending | Stopping |

- **Suspended problems.** Suspended reports `InvalidRequest`, `CaptureUnavailable` and `ResourceExhausted` as separate problems.
- **Engine failure.** An Engine Failed records `Engine.state`. Later successful cleanup keeps the status Failed.
- **Confirmed startup.** Streaming requires a returned Engine start and no accepted Stop. An Active state seen before start returned isn't enough, and a confirmed session stays streaming while Suspended.
- **First image is separate.** Confirmed startup can come before the first JPEG, so check image availability separately ([Engine startup](../../../../../screen-capture-engine/docs/usage.md#startup-details)).

## Borrowed output

<a id="mjpeg-cap-08"></a>

### MJPEG-CAP-08: Borrowed output and generation fencing

**History.**

1. Deliver borrowed JPEGs that HTTP accepts, ignores or fails.
2. Reuse the source storage after the callback returns.
3. Change phase or generation while an offer is held.
4. Use a recognizable six-byte JPEG.

**Expected for generations.**

- **When generations change.** Active keeps the current generation or creates one. WaitingForPermission, Starting and Reconfiguring keep whatever generation exists, possibly none. Suspended, Stopping, Stopped and Failed revoke it.
- **HTTP sees every change.** HTTP receives every change of status or generation, in commit order, while the session's state gate is held.
- **Fresh sampling in the callback.** Each callback reads fresh Engine state before it samples the generation, so a fresh Active can reopen publication before the controller catches up.

**Expected for copying.**

- **Exact bytes.** Copied bytes are exactly six bytes with the fixture's payload, and any spare capacity in a reused buffer stays outside that prefix.
- **Pairing.** The copied JPEG stays paired with the generation that admitted it.
- **Stale offers.** No offer is made without a generation. A stale offer never invokes the copier and can't revive output.
- **Callback lifetime.** All access to the borrowed frame happens on the callback's thread, within that callback ([Engine frame rules](../../../../../screen-capture-engine/docs/usage.md#handle-jpeg-frames)).
- **Copy failure.** Inject an exception after the copier is entered with sufficient destination capacity. No owned output is published. The session retains the exact cause at `Output.frame`, requests an Error Stop and still cleans up successfully; cleanup does not replace the capture failure. The Engine owns destination-size validation.

**Defensive inputs.** A conforming Engine and HTTP store never produce these, so label them as defensive:

- **Wrong copy count.** Make a copier that advertises six bytes return five or seven. The session records `IllegalStateException("Incomplete JPEG copy")` at `Output.frame`.
- **Unreadable metadata.** When reading the byte count or metadata fails, the session records that original cause rather than inventing a frame.

## Cleanup

Cleanup starts unregistering the consumer and stopping media together. It then:

1. refreshes final Engine facts and statistics
2. finishes Core's projection creation
3. waits for the consumer to complete
4. offers consent for reuse if eligible
5. releases the capture foreground, if media stopped and no cleanup step failed

Cleanup runs without cancellation once it has started. In these scenarios, **foreground release** means `leaveCaptureForeground`: it ends this attempt's foreground ownership and restores waiting foreground for a surviving admitted instance. It never removes foreground ([CAP-13](#mjpeg-cap-13)).

<a id="mjpeg-cap-09"></a>

### MJPEG-CAP-09: Capture failure versus cleanup failure

**History.** Record a capture failure. Then fail media stop, foreground release or finishing projection creation, each independently.

**Expected.**

- **Both kept.** The first capture failure and the first cleanup failure both stay observable, and successful cleanup doesn't erase a capture failure.
- **Any required failure counts.** A failed required cleanup step makes cleanup unsuccessful.
- **Module consequence.** The controller turns unsuccessful cleanup into terminal module shutdown ([CTL-05](02-controller.md#mjpeg-ctl-05)).

<a id="mjpeg-cap-10"></a>

### MJPEG-CAP-10: Independent media and consumer receipts

**History.** Hold consumer completion and media stop. Release them in either order, and cancel one cleanup waiter.

**Expected.**

- **Both receipts.** Cleanup waits for both receipts. Foreground release waits for the consumer and depends on the media result.
- **No substitutes.** A cancelled waiter or a terminal Engine state can't stand in for consumer completion.
- **Unregister waits.** An ordinary unregister is a wait, not a step that normally fails ([consumer removal](../../../../../screen-capture-engine/docs/usage.md#replace-or-remove-the-consumer)).
- **Logical completion only.** Media stop confirms logical completion and the projection stop. Physical release follows the [Engine's stop contract](../../../../../screen-capture-engine/docs/usage.md#stop-a-capture-run).

<a id="mjpeg-cap-11"></a>

### MJPEG-CAP-11: Rollback and remaining obligations

**History.** Fail the raw projection stop, foreground release or finishing projection creation, both before and after Engine handoff. Hold another cleanup step at the same time.

**Expected.**

- **Remaining steps continue.** The remaining applicable steps are still attempted, and the first cleanup failure is kept.
- **Media failure blocks release.** A failed media or raw projection stop suppresses foreground release.
- **Guard failure blocks release.** A failure to finish projection creation suppresses both consent reuse and foreground release.
- **Exact attempt.** Finishing projection creation always targets this attempt and never another one.

<a id="mjpeg-cap-12"></a>

### MJPEG-CAP-12: Consent reuse eligibility

**History.** Contrast a fully successful User or Shutdown stop with these cases:

- a System stop, such as screen-off, address loss or Android ending sharing
- an Error stop
- a startup failure
- refused consent
- a cleanup step that fails before reuse

**Expected.**

- **When consent is offered.** MJPEG offers consent back to Core only if all of these hold: startup succeeded, there is no capture or cleanup failure, the Stop reason is User or Shutdown, and the Engine ended in `Stopped(Requested)`. Android ending the projection gives `ProjectionStopped` and isn't eligible.
- **Failed offer.** A failed offer records `Cleanup.consentReuse` and makes cleanup unsuccessful.
- **Foreground after the offer.** Foreground release comes after the offer, so a later foreground failure can fail cleanup after consent was already saved.
- **Core decides reuse.** Whether consent is actually reused, and on which Android versions, belongs to Core's [legacy reuse model](../../../../core/docs/screen-capture.md#legacy-reuse-and-cleanup).

## Service host and defensive inputs

<a id="mjpeg-cap-13"></a>

### MJPEG-CAP-13: Real MJPEG Service adapter

**History.** Run an attempt through a real `MjpegStreamingModuleService`, which inherits Core's `StreamingModuleService`. Simulate these cases:

- promotion that succeeds, is rejected or throws
- Service destruction during the attempt
- leaving capture foreground after media cleanup, once with the instance still admitted and once after shutdown has closed foreground admission
- a platform failure while restoring waiting foreground
- terminal Service stop after module shutdown

Run on SDK levels before 29, from 29 to 33, and 34 or later.

**Expected.**

- **Promotion request.** MJPEG requests capture foreground for its exact attempt, without the microphone type, inside Core's consent callback.
- **No false success.** A rejected or failed promotion never counts as success, and startup ends as Core reports it ([CAP-03](#mjpeg-cap-03)).
- **Notification Stop.** Confirmed startup updates the notification so that Stop targets that exact attempt. Before confirmation, the notification offers Exit.
- **Leaving capture foreground.** `leaveCaptureForeground` targets the same attempt and follows media cleanup when [CAP-11](#mjpeg-cap-11) allows it. It doesn't remove foreground: for a surviving admitted instance, Core restores waiting foreground with Exit. When shutdown has closed foreground admission and the Service still exists, restoration is skipped without a failure. Core clears the attempt's foreground ownership in both cases.
- **Restoration failure.** When the platform promotion call throws, Core closes foreground admission, reports failure to the manager and requests module shutdown, then rethrows. The session then records `Cleanup.foreground`, and cleanup is unsuccessful.
- **Terminal Service stop.** Removing foreground isn't a capture cleanup step. After the attempt's cleanup settles, module cleanup calls `stopAndAwaitDestruction()`, and Android removes foreground and the notification when the Service stops ([Core's foreground lifecycle](../../../../core/docs/notifications.md#foreground-presentation)). A failed stop request or an Android timeout fails module cleanup and doesn't change the attempt's recorded stages.
- **Service destruction.** Destroying the Service reports module failure and starts shutdown ([CTL-11](02-controller.md#mjpeg-ctl-11)).

**Evidence boundary.** Core's Service base class implements the promotion calls for each SDK level; a Robolectric run through the MJPEG subclass shows only how MJPEG uses them. Actual system admission, foreground-service restrictions and visible notifications need L2.

<a id="mjpeg-cap-14"></a>

### MJPEG-CAP-14: Defensive observation failures

**History.** Where a faithful stimulus exists, fail Engine state observation and statistics observation separately. Also supply a Suspended state with a problem that the Engine contract doesn't allow.

**Expected.**

- **State observation failure.** It records `Engine.stateObservation`, ends capture with an Error stop and cleans up.
- **Statistics observation failure.** It is only logged. No values are invented, and capture success is unchanged.
- **Undocumented Suspended problem.** The Engine allows only three Suspended problems ([lifecycle states](../../../../../screen-capture-engine/docs/usage.md#lifecycle-states)). Any other value makes the session's suspension problem throw when the controller reads it, which fails the module rather than one attempt.

**Evidence boundary.** These are defensive inputs ([evidence rule 6](../01-testing.md#evidence-rules)).
