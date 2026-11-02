# Controller admission and reconciliation

[Scenario index](../02-verification-contracts.md) · [Testing](../01-testing.md)

These scenarios exercise `MjpegStreamingModuleController`, the single coordinator of one MJPEG module instance. The controller:

- accepts or ignores Start and Stop
- applies saved settings
- turns discovery results into a listener plan
- watches the automation conditions
- reports to Core's manager

[Coordination](../../01-module/03-coordination.md) owns these rules, and [Capture lifecycle](../../01-module/02-module-lifecycle.md) owns module shutdown and cleanup. Both extend the public [Architecture](../../../docs/architecture.md) and [Usage](../../../docs/usage.md) guides and Core's [module contract](../../../../core/docs/module-contract-and-manager.md).

Two facts about the controller shape most of these histories:

- **Ordinary work runs one event at a time.** Commands, settings, discovery, HTTP changes and capture changes go through one event loop. Each turn handles one event, then reads fresh HTTP and capture state before it publishes the screen and reports.
- **Shutdown bypasses that loop.** It closes admission directly, so it works before the loop starts and while the loop is blocked.

## Test boundary

Use L1, with the real controller and these controlled boundaries:

- **Manager handle.** A `ManagerHandle` whose `isCurrent()` result the test controls, and which records reports.
- **Service.** The concrete `MjpegStreamingModuleService` under Robolectric. This also runs Core's Service base class.
- **Settings.** A real `MjpegSettings` on a temporary store.
- **Discovery and HTTP.** Fake or real `NetworkAddressMonitor` and `HttpDelivery` implementations.
- **Consent and projection.** A `ScreenCaptureAccess` that controls consent and projection outcomes. The controller builds capture sessions with the production engine factory, so engine phases are exercised in the [capture scenarios](03-capture.md).
- **Clocks.** Controlled `SystemClock` uptime and elapsed realtime.

A fresh run establishes these controller histories. Android capture, real sockets, Toast and wake-lock effects, and the app's permission UI need their own evidence.

## Module lifetime and shutdown

<a id="mjpeg-ctl-01"></a>

### MJPEG-CTL-01: One inert module lifetime

**History.**

1. Construct a controller, then call `startModule()` repeatedly with a current handle.
2. Start a second controller whose handle is no longer current.
3. Let a current handle become non-current while the loop runs.

**Expected.**

- **Construction.** Construction reads the current local-network grant and opens nothing else. It doesn't collect settings, start discovery, start HTTP or register a receiver.
- **Current start.** A current start runs one event loop, and repeated calls add nothing. The first settings observation then starts discovery and HTTP delivery once.
- **Stale start.** A start with a stale handle requests module shutdown instead of running the loop, and cleanup follows.
- **Handle goes stale.** If the handle stops being current while the loop runs, the loop ends and requests the same shutdown. It doesn't report a module failure.

<a id="mjpeg-ctl-12"></a>

### MJPEG-CTL-12: Shutdown and admission races

**History.** Request shutdown at each of these points:

- before `startModule()`
- during the first configuration, while the logo read or HTTP start is held
- while the loop is admitting a Start

Repeat the request, and submit Start, Stop and Retry afterwards.

**Expected.**

- **Shutdown acts directly, without the event queue.** It closes screen-off registration, closes foreground admission and stops the retained attempt with reason Shutdown. It then closes HTTP admission, cancels ordinary controller work, closes the queue and starts module cleanup. Each step is still attempted if an earlier one throws, and repeated requests add nothing.
- **Capture admission and shutdown share one gate.** Either the attempt exists before shutdown, so shutdown stops it and cleanup waits for it, or admission sees the shutdown and creates no session and no HTTP registration. Cleanup never misses an admitted attempt.
- **Later work is dropped.** Commands after shutdown are dropped, and `startModule()` after shutdown runs no ordinary work.
- **Entered work is awaited.** Controller work that had already started, such as a held HTTP start, is awaited by the module cleanup receipt, even after the other owners have finished.

<a id="mjpeg-ctl-11"></a>

### MJPEG-CTL-11: Component failures and structured errors

**History.** Fail each of these independently:

- the settings flow, including the PIN save when the mode opens
- the discovery monitor, by reaching Failed or Closed while the controller is open
- the logo asset read during the first configuration
- HTTP start or configuration rejection, or an exception while configuring listeners
- an HTTP fatal runtime cause
- an uncaught controller exception
- destruction of the original Service

Separately, inject controlled capture failures at startup, in the Engine or during output, and make screen-off registration fail. In another run, make the real Service's capture-foreground promotion fail at the platform call. Also hold the cleanup of an accepted Stop past three seconds, both with and without a retained failure.

**Expected.**

- **Module failures.** Each failure in the first list reports `reportFailed()` to the manager and starts terminal shutdown ([CTL-12](#mjpeg-ctl-12), [CTL-13](#mjpeg-ctl-13)). The controller never goes on presenting usable configuration. An HTTP fatal cause isn't logged a second time.
- **Recoverable failures.** Controlled capture failures at startup, in the Engine or during output, and screen-off failures leave the module open while the instance is still admitting ordinary work:
  - The manager status carries a `Status.error` with no message. The app shows its generic error with **Restart mode**.
  - Start returns after successful cleanup, provided no terminal failure or shutdown has begun meanwhile.
  - A later attempt that confirms startup clears the error only if it is still the failure that existed before that attempt was admitted. A newer failure recorded during the attempt stays.
- **Terminal failures win.** If a failure from the first list or any shutdown begins before the controller processes a successful capture cleanup, the instance closes and Start never returns in that lifetime.
- **Foreground-host failure.** A platform failure while the real Service enters capture foreground is terminal. Core's Service closes foreground admission, reports `reportFailed()` and requests module shutdown. The attempt still cleans up. Start never becomes available again in that lifetime ([CTL-12](#mjpeg-ctl-12), [CTL-13](#mjpeg-ctl-13)).
- **Slow Stop.** Suppose an accepted Stop has waited three seconds without completed cleanup, and no retained failure is present. `Status.error` then carries the localized stop-delay message until cleanup completes. A retained capture or screen-off failure takes precedence, so the error stays generic. The attempt's cleanup receipt, not the message, is the evidence of cleanup progress.
- **Structured fields.** Assert structured status fields and manager state. Localized text, card styling and the app's Restart presentation need their own UI checks ([Pauses and failures](../../../docs/architecture.md#pauses-and-failures)).

<a id="mjpeg-ctl-13"></a>

### MJPEG-CTL-13: All module cleanup obligations

**History.** Hold or fail each of these separately:

- HTTP stop
- discovery close
- screen-off receiver cleanup
- capture cleanup
- the Service's `stopAndAwaitDestruction()`

Also cancel one `awaitCleanup()` waiter, and hold a piece of controller work.

**Expected.**

- **Three branches start independently.** HTTP and discovery cleanup proceed while capture cleanup hangs. The platform branch waits for the capture result, then stops the Service even when capture cleanup returned false or threw. A hung capture cleanup therefore also keeps the Service stop pending.
- **Controller work is joined.** The receipt waits for admitted controller work. That includes the capture attempt's own coroutine, which runs in controller scope.
- **One result for all.** The receipt is true only if every obligation succeeded. Screen-off cleanup counts as failed if any receiver removal failed earlier in the lifetime.
- **Waiters don't own cleanup.** Cancelling one waiter neither cancels cleanup nor changes its result.
- **No replacement work.** A failed obligation gives an unsuccessful receipt. No new work is admitted in that lifetime, because admission is already closed.
- **No deadline.** Core's manager waits only for a bounded time ([Closing a module instance](../../../../core/docs/module-contract-and-manager.md#closing-a-module-instance)). MJPEG's receipt can stay pending after that wait ends.

## Start, Stop and reports

<a id="mjpeg-ctl-02"></a>

### MJPEG-CTL-02: Current Start readiness

**History.** From a ready module, remove or make stale one input at a time, then restore it:

- the accepted settings
- idle capture
- the handle's `isCurrent()` result
- the monitor's current `Observed` state
- a Listening server on the applied port
- a matching address ID in both the current and the processed selection
- local-network eligibility

Change one address's fresh interface type from Mobile or VPN to Wi-Fi while the grant is missing. Send duplicate Start commands.

**Expected.**

- **All checks pass at handling time.** Start creates an attempt only if all of these hold:
  - the controller has accepted settings and is idle
  - the handle is current
  - a Listening server on the applied port matches an address ID that is in both the monitor's current selection and the controller's processed selection
  - both matching rows are eligible under the current grant
- **Fresh classification counts.** If the fresh row now requires the grant, Start is rejected even though the older Mobile or VPN row didn't need it.
- **No stale evidence.** An older listening row, a permission hint, or an enabled Start button on the screen is not enough. The screen's Start state uses the same check, but handling the command checks again.
- **One attempt at a time.** Duplicate Starts create one attempt. A Start while busy is ignored without an error.
- **Screen-off registration.** When **Stop when screen turns off** is on, Start also needs it ([CTL-14](#mjpeg-ctl-14)).

<a id="mjpeg-ctl-03"></a>

### MJPEG-CTL-03: Exact Stop and replacement

**History.**

1. With one attempt active, send Stop for another attempt ID, then the exact Stop, then repeat it. Do this during consent, startup and running capture.
2. After successful cleanup, start a second attempt.
3. Deliver late capture changes, Stops and screen-off events from the first attempt.

**Expected.**

- **Exact matching.** A Stop for another attempt ID is ignored. The exact Stop records reason User (the first accepted reason wins) and begins cleanup. Repeating it adds nothing.
- **Stop before confirmation.** The controller handles an exact Stop command during consent and startup, while the screen shows Busy. Neither the screen nor the notification offers Stop before capture is confirmed (the notification still offers Exit). This history therefore injects the command directly, as `requestStreamStop` with the attempt ID.
- **Successor only after cleanup.** A new Start is refused until the first attempt's successful cleanup has been processed.
- **Successor is isolated.** Late Stops and events from the first attempt can't affect its successor.
- **HTTP stays open.** An ordinary successful Stop leaves HTTP delivery, listeners, credentials and connected viewers in place.

<a id="mjpeg-ctl-04"></a>

### MJPEG-CTL-04: Independent capture and consumer reports

**History.** Move capture through consent, startup, Active, Suspended, Stopping and cleaned up. Vary HTTP consumer presence independently. Observe reports at processed checkpoints, including while capture is idle.

**Expected.**

- **`isStreaming`** is true from confirmed startup until Stop is accepted, including while capture is Suspended. Confirmed startup means the Engine's `start()` has returned.
- **`hasConsumer`** follows HTTP's sampled media rows, independently of capture.
- **`captureAttempt`** names the attempt from admission until its cleanup succeeds.
- **Heartbeat.** While the loop's turns progress, it reports on a roughly one-second heartbeat, whether or not capture is active. A blocked or delayed turn delays the heartbeat. A status change is reported in the same turn.
- **Separate facts.** Consumer presence, attempt cleanup and control progress are separate facts. Core's [health model](../../../../core/docs/module-contract-and-manager.md#observable-process-and-instance-health) defines how the manager uses them.

## Settings and access

<a id="mjpeg-ctl-05"></a>

### MJPEG-CTL-05: Saved versus applied setup

**History.** While an attempt is pending (consent, capture or Stopping), save mixed edits:

- live values: image, network filter, web page, automation and hide-PIN
- restricted values: JPEG encoder, HTTP port and access

Then let cleanup succeed. In a separate run, let cleanup fail.

**Expected.**

- **During the attempt.**
  - Live values apply: image settings reach the session, the filter changes the plan, and web-page and inactive-image settings reach HTTP.
  - Restricted values keep their previous effective value, and the edit policy is LiveOnly.
- **After successful cleanup.** The controller reapplies the latest observed settings:
  - a new port produces a new listener plan
  - access changes reach HTTP, rotating credentials where the rules require it
  - the encoder applies at the next Start
- **After failed cleanup.** The controller reports module failure and shuts down. Start is never enabled again in that lifetime, and setup stays locked.
- **Saving isn't applying.** Saving a value proves neither that the controller observed it nor that its owner applied it.

<a id="mjpeg-ctl-06"></a>

### MJPEG-CTL-06: Once-only module PIN preparation

**History.**

1. Open the mode with protection on and **New PIN on open**, then start and stop capture several times.
2. Open it with protection on and **Permanent PIN**.
3. Open it with protection off, then enable protection, once with no saved PIN and once with an existing saved PIN.

**Expected.**

- **New PIN on open.** Opening generates and saves one new PIN before the first observed settings reach HTTP, so the first applied credentials already use it. Capture restarts keep it.
- **Permanent PIN.** Opening leaves the PIN unchanged.
- **Protection off at open.** Opening generates no PIN. Enabling protection without a PIN generates one in the same save ([SET-06](01-settings.md#mjpeg-set-06)). An existing PIN is reused.
- **Never again in the lifetime.** No case generates another PIN at a later Start.
- **Failure.** A failed PIN save while opening is a settings failure ([CTL-11](#mjpeg-ctl-11)).
- **Assertions.** Assert that viewers can authorize with the expected PIN, not that random values differ.

<a id="mjpeg-ctl-07"></a>

### MJPEG-CTL-07: PIN edits and application boundary

**History.** Use the real Access settings update path, with editable (All) and locked (LiveOnly) policies and both PIN options:

- type a PIN
- generate a new PIN
- toggle **Hide PIN when starting a stream**
- hold a save while the policy changes
- fail a save
- cancel the caller

**Expected.**

- **Checked inside the transaction.** Each save runs its policy check inside the transaction, against the latest saved data and the latest policy. A forbidden change saves nothing.
- **Typed PINs.** A typed PIN is saved only with **Permanent PIN** and a valid six-digit value.
- **Generated PINs.** Generating works with either option whenever setup is editable.
- **Ordering.** Saves run in the order they were admitted.
- **Failure and cancellation.** A failed save produces the save-error presentation. Cancelling the caller is not an error and doesn't abort a save that was already admitted.
- **Busy controller.** While capture is pending, a restricted credential change that lands late stays deferred until successful cleanup ([CTL-05](#mjpeg-ctl-05)).

**Evidence boundary.** How the error appears on screen needs a separate Android UI check.

## Addresses and address loss

<a id="mjpeg-ctl-08"></a>

### MJPEG-CTL-08: Retained discovery and permission plans

**History.** After a ready selection:

1. Edit the filter while the processed discovery state is `Observed`.
2. Supply `Loading` as a controller input, then edit the filter.
3. Revoke the local-network grant and process a heartbeat.
4. Grant it again.

**Expected.**

- **Filter edit with an inventory.** The controller selects again from its processed inventory and sends the new plan to HTTP in the same turn. Discovery doesn't restart, and the controller doesn't wait for it to acknowledge anything.
- **No inventory.** Without an `Observed` state, the controller keeps its last selection. A filter edit then doesn't select again until the next `Observed` state arrives. The production monitor publishes `Loading` only before its first completed scan, so a `Loading` after `Observed` is a boundary input.
- **Revoked grant.** The heartbeat check removes grant-dependent addresses (non-loopback Wi-Fi, Ethernet and Other) from the plan. Discovery rows and the selection stay, and Mobile, VPN and loopback listeners remain.
- **Restored grant.** Granting again restores the removed listeners.
- **Denial isn't loss.** Revoking the grant starts no address-loss episode ([LAN-04](04-network.md#mjpeg-lan-04)).

<a id="mjpeg-ctl-09"></a>

### MJPEG-CTL-09: One network-loss episode

**History.** During one attempt, which may be in consent, startup, Active, Reconfiguring or Suspended:

1. Publish an `Observed` state whose selection is empty.
2. Shorten and lengthen the timeout.
3. Change the filter.
4. Restore addresses.

Separately, keep the selection nonempty while denying the grant or failing every bind.

**Expected.**

- **Onset.** An episode begins when the controller first finds the processed selection empty. It is timed with elapsed realtime, which includes deep sleep.
- **Timeout edits.** Changing the timeout keeps the original onset, so a shorter timeout can expire at the next evaluation.
- **Filter changes.** A filter change starts a new episode if the selection is still empty.
- **Clearing.** A nonempty selection, an accepted Stop or finished cleanup clears the episode. A later loss starts a new episode.
- **Not loss.** Bind failures and permission denial with a nonempty selection are not address loss.
- **Timing.** The deadline is measured in elapsed realtime, but the controller evaluates it only when the loop runs a turn. A blocked turn, a scheduling delay or device sleep can make the Stop happen later than the deadline, and no minimum evaluation rate is guaranteed. A late evaluation still rereads the current inventory before stopping ([CTL-10](#mjpeg-ctl-10)).

<a id="mjpeg-ctl-10"></a>

### MJPEG-CTL-10: Recovery at the loss deadline

**History.**

- Reach the deadline while the monitor already holds recovered addresses but the recovery event is still queued.
- Repeat with a monitor state that is not `Observed`, and with one that is still empty.
- Replace the attempt, or change the filter, before an old deadline.

**Expected.**

- **Fresh recheck.** At expiry the controller reads the monitor's current state again and applies the episode's filter.
  - Recovered or absent evidence clears the episode without a Stop, whatever the queue order.
  - Only a still-empty selection stops the episode's own attempt, with reason System.
- **Episodes belong to attempts.** A replacement attempt has its own episode, so an obsolete deadline can't stop it.

## Automation

<a id="mjpeg-ctl-14"></a>

### MJPEG-CTL-14: Screen-off subscription and exact attempt

**History.** With **Stop when screen turns off** on, run these cases:

- Start with the screen already off.
- Turn the screen off between the interactivity checks of registration.
- Turn the screen off during consent, startup, capture and suspension.
- Enable the preference during an attempt, with the screen on and with it off.
- Disable the preference, and accept a Stop.
- Deliver a late event from an old registration or an old attempt.
- Make registration throw, and make receiver removal throw.

**Expected.**

- **Already off.** Start with the screen off creates no attempt and no HTTP registration. Sleep detected during registration counts as already off.
- **Exact match.** Only an event from the current registration for the current attempt, with the preference still on, stops capture (reason System).
- **Enabling during capture.** Enabling the preference during an attempt registers for that attempt. If the screen is already off, it stops capture with reason System. A registration failure is reported and stops capture with reason Error.
- **Retiring the subscription.** Disabling the preference, Stop and cleanup remove the registration.
- **Failure at Start.** A registration failure at Start creates no attempt and reports a `Status.error`.
- **Failure at removal.**
  - A removal failure is reported.
  - The observer then refuses further registrations for the rest of the lifetime, so a later Start with the preference on fails with an error.
  - Module cleanup ends unsuccessful ([CTL-13](#mjpeg-ctl-13)).

**Evidence boundary.** Controlled observer races show the controller's response. Real device sleep needs L2. [SET-10](01-settings.md#mjpeg-set-10) covers saving the preference.

<a id="mjpeg-ctl-17"></a>

### MJPEG-CTL-17: Keep-awake and slow-viewer preferences

**History.** During confirmed capture:

- Toggle keep-awake and the skipped-frame warning.
- Vary slow viewers, suspension and Stop.
- For warnings, supply these samples:
  - a stale first sample
  - recovery
  - repeated slow samples
  - a first slow sample inside the cooldown, followed by a newer slow sample after the cooldown with no recovery in between
  - a warning attempt whose final check suppresses the Toast

**Expected for keep-awake.**

- The screen wake lock is held only while the preference is on and capture is confirmed and not stopping. Reconfiguring and Suspended count as confirmed.
- Accepting Stop releases the lock before media teardown, and stale controller work can't take it again afterwards.
- Failures to acquire or release the lock are only logged. They change neither capture nor cleanup outcomes.

**Expected for the slow-viewer warning.**

- **Eligibility.** A warning is possible only while the preference is on, capture is confirmed and freshly Active, and the module isn't shutting down.
- **Fresh samples only.** On becoming eligible, the controller records the current time and accepts only HTTP samples that are strictly newer.
- **Episodes.** A sample with no slow viewers ends the current episode. A slow sample attempts one Toast only if it is at least 30 seconds after the previous warning attempt.
- **Delayed eligibility.** A slow sample inside the cooldown leaves the episode's warning unused. A newer slow sample after the cooldown, with no recovery in between, still warns once.
- **One attempt per episode.** After a warning attempt, continued slowness doesn't warn again. A new episode after recovery is again subject to the cooldown.
- **Leaving eligibility.** Turning the preference off, leaving Active or stopping clears the episode but keeps the cooldown for the module's lifetime. Turning the preference back on doesn't replay an old sample.
- **Final check.** A warning attempt uses up the episode's warning before it runs. A final fresh check on the main thread can then suppress the Toast. A suppressed attempt still uses up the episode and starts the cooldown.
- **Independence.** HTTP counters and slow-reader marking continue whether or not warnings are enabled.

**Evidence boundary.** Actual wakefulness, Toast presentation and Android's projection restrictions need platform evidence.

## Statistics

<a id="mjpeg-ctl-15"></a>

### MJPEG-CTL-15: Approximate statistics and baselines

**History.** Supply HTTP samples that cover these cases:

- the initial sample with time zero
- a first real sample
- a repeated timestamp
- a new timestamp
- changed client IDs
- a non-positive interval
- a decreasing counter

**Expected.**

- **Aggregate rate.** The outgoing rate needs two distinct samples, a positive interval and a counter that didn't decrease. Otherwise it is unavailable.
- **Client rates.** A client's rate exists only for an ID present in both samples. A new client has no invented earlier baseline.
- **Repeated timestamps.** A repeated timestamp keeps the previous rates.
- **Stop and Start.** Totals and the sample baseline survive Stop and Start.
- **What to compare.** Compare against the documented totals and rates, not browser receipt or exact instantaneous bandwidth.

**Evidence boundary.** HTTP sampling itself belongs to [OBS-08](05-http-runtime.md#mjpeg-obs-08). How the screen displays the figures needs a separate UI check.

<a id="mjpeg-ctl-16"></a>

### MJPEG-CTL-16: FPS and history lifetime

**History.**

1. Supply Engine statistics while the session moves between Active, Reconfiguring, Suspended and Stopped, across Stop and Start in one module. Then create a fresh module.
2. Advance elapsed time across second boundaries and past 60 seconds.
3. Keep capture Active without new statistics.

**Expected.**

- **Frame rate.** The session computes the frame rate from produced-frame deltas over the elapsed time between collector receipts. It needs a prior sample, a count that didn't decrease and a positive interval.
- **Shown only while Active.** The frame rate is shown only while the session is freshly Active. While Active, the last value stays until another sample arrives, with no expiry timer.
- **Engine statistics.** Engine statistics from the last attempt, refreshed with final totals during cleanup, stay visible after Stop until a new attempt replaces them. The frame rate doesn't stay.
- **History points.** History keeps at most one point per elapsed second, replacing it when the values change within that second. Points older than 60 seconds are dropped, with at most 60 points. A delayed loop skips seconds rather than filling them in.
- **History lifetime.** History spans capture restarts within one module and starts empty in a new module.

**Evidence boundary.** Don't add a freshness guarantee.
