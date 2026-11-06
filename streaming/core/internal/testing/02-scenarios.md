# Core verification scenarios

[Core internals](../README.md) · [Testing](01-testing.md)

This catalogue lists the manager and consent histories that Core verification must cover. Each scenario continues a documented owner: the owner states a rule, and the scenario describes a history that would show the rule holds, including the races and failures most likely to break it. Scenarios describe required behavior; they say nothing about whether a test exists or has passed. [Testing](01-testing.md) explains the environment, the scope and the evidence rules.

## Reading a scenario

Every scenario has:

- an **ID** such as `CORE-MGR-05`, with a matching lowercase anchor (`#core-mgr-05`)
- an **owner**: the guide section or KDoc that defines the rule
- a **history**: the starting condition and stimuli, including races or failures to inject
- **expected outcomes** that a collaborator outside the component can observe
- where needed, an **evidence boundary**: what a local run can't establish

IDs are permanent. Keep every ID and anchor when you reorganize pages or tests, give new behavior the next free number in its area, and never reuse an ID. `CORE-CAP-11` is retired. Related scenarios can share fixtures, but each keeps its own failure, race and boundary assertions. Numeric limits, such as deadline lengths, stay in the owner's KDoc; scenarios refer to them by name.

## Manager test boundary

Run the real `StreamingModuleManagerImpl` on a test-owned dispatcher under Robolectric, with a real `StreamingSettingsStore` backed by a preferences file for that test. A test controls:

- **Catalog.** Test `StreamingModule` definitions with chosen IDs, priorities, Service classes and foreground slots.
- **Service.** A test `StreamingModuleService` subclass, started through Robolectric with the Intent the manager asked Android to start. Its Controller factory can return a Controller, throw, or return a Controller with another identity.
- **Controller.** A fake Controller that records `startModule`, commands and `requestModuleShutdown`, reports through its `ManagerHandle`, and holds `awaitCleanup` behind a test gate that can return true, return false or throw.
- **Notification provider.** A fake `StreamingModuleNotifications`. [CORE-MGR-14](#core-mgr-14) and [CORE-MGR-15](#core-mgr-15) need one that records error updates, can throw and can reenter the manager.
- **Clocks.** Robolectric's `SystemClock`, advanced together or with simulated deep sleep, and the scheduler of the manager's dispatcher.

A fake Controller shows how the manager responds to that Controller's behavior. It doesn't show that any real module meets the Controller contract.

## Manager: catalog and selection

<a id="core-mgr-01"></a>

### CORE-MGR-01: Catalog validation and order

**Owner.** [StreamingModuleManager.modules](../../src/main/kotlin/io/screenstream/core/StreamingModuleManager.kt) and [StreamingModule.foregroundSlot](../../src/main/kotlin/io/screenstream/core/module/StreamingModule.kt).

**History.** Construct the manager with a valid catalog of mixed priorities. Separately, construct it with each invalid catalog: empty, duplicate module IDs, a foreground slot ID that isn't positive, a slot equal to the module error slot, one Service class with two different slots, and two Service classes sharing a slot. Also construct it with two definitions that share one Service class and one slot.

**Expected.**

- **Order.** `modules` lists definitions by descending priority and can't be modified through the returned list. Don't assert an order between equal priorities.
- **Rejection.** Each invalid catalog fails construction.
- **Shared Service.** Definitions that share a Service class and its slot are accepted.

<a id="core-mgr-02"></a>

### CORE-MGR-02: First selection and the remembered choice

**Owner.** [Selection and restart](../../docs/module-contract-and-manager.md#selection-and-restart) and [StreamingModuleManager.selectModule](../../src/main/kotlin/io/screenstream/core/StreamingModuleManager.kt).

**History.** In separate runs, make the first request `selectModule(null)` with no saved choice, with a saved registered ID and with a saved ID that isn't in the catalog. Then make the first request an explicit ID that differs from the saved one. In each run, deliver the Service start and report a first status. Finally, select a different module explicitly, Exit, and request `selectModule(null)` again.

**Expected.**

- **Resolution.** A saved registered ID opens that module. No saved choice, or an unavailable one, opens the first catalog entry. An explicit ID opens that module.
- **Transition.** State is `Switching` while settings initialize and until the first status arrives, then `Running` with an instance of the resolved module.
- **Service.** The manager asks Android to start the resolved module's Service class.
- **Persistence.** After its write settles, the store holds a changed explicit choice. The request returns before that write is confirmed.
- **Later null requests.** After Exit, `selectModule(null)` opens the module selected last in this process.

<a id="core-mgr-03"></a>

### CORE-MGR-03: Dropped selection requests

**Owner.** [Selection and restart](../../docs/module-contract-and-manager.md#selection-and-restart).

**History.** Request selection with an unknown ID. Request selection while settings initialize, while an opening awaits its first status and during a replacement. With a module running, select it again by ID and with null. Fail that instance, then select the same module again. After the transition ends, select another module.

**Expected.**

- **Ignored requests.** Unknown IDs and requests during initialization or a transition change nothing: state and the current instance stay the same, and no Service start is requested.
- **Same module.** Selecting the current module keeps its instance, including a `Failed` instance, which stays `Failed`.
- **After the transition.** A later selection of another module opens it.

<a id="core-mgr-04"></a>

### CORE-MGR-04: Restart identity

**Owner.** [Selection and restart](../../docs/module-contract-and-manager.md#selection-and-restart), [Identities and lifetimes](../../docs/module-contract-and-manager.md#identities-and-lifetimes) and [StreamingModuleManager.restartModule](../../src/main/kotlin/io/screenstream/core/StreamingModuleManager.kt).

**History.** Restart a `Running` instance by its exact identity. Separately, restart a `Failed` instance. Then issue restarts with a stale identity from an earlier instance, during startup before the first status, during a transition and before any selection.

**Expected.**

- **Replacement.** Restarting a live or failed current instance closes it and opens a new instance of the same module. The new identity differs from the old one by equality, and the old `ManagerHandle` keeps reporting its original identity.
- **Ignored restarts.** Stale identities, startup, active transitions and an unselected manager leave state and the current instance unchanged.

## Manager: startup and deadlines

<a id="core-mgr-05"></a>

### CORE-MGR-05: Admitted startup

**Owner.** [How they collaborate](../../docs/module-contract-and-manager.md#how-they-collaborate), [StreamingModuleService.createController](../../src/main/kotlin/io/screenstream/core/module/StreamingModuleService.kt) and [StreamingModule.Controller](../../src/main/kotlin/io/screenstream/core/module/StreamingModule.kt).

**History.** Select a module, record which Android start API the manager calls, and deliver the started Intent to the test Service. In the factory, record the handle and the Service's foreground state. Report a first status from the Controller. Then deliver the same Intent again, and deliver an Intent recorded from an earlier instance.

**Expected.**

- **Ordinary dispatch.** The manager calls `Context.startService`, without falling back to `startForegroundService`.
- **Foreground first.** After admission is validated and before the factory runs, the Service enters foreground using the definition's foreground slot ID.
- **Handle.** The handle carries the new instance identity and the definition's foreground slot, and `isCurrent()` is true.
- **Install then start.** `startModule` runs once, after the factory returns.
- **First status.** State becomes `Running` with that instance identity and the reported status.
- **Redelivery.** A repeated or stale start delivery doesn't invoke the factory again and leaves the instance unchanged.

**Evidence boundary.** A recorded API call and Robolectric foreground state do not establish Android's permission to start or promote the Service on a device.

<a id="core-mgr-06"></a>

### CORE-MGR-06: Startup and factory failures

**Owner.** [StreamingModuleService.createController](../../src/main/kotlin/io/screenstream/core/module/StreamingModuleService.kt), [ManagerHandle.reportFailed](../../src/main/kotlin/io/screenstream/core/module/StreamingModule.kt) and the `onServiceStart` KDoc in [StreamingModuleManagerImpl](../../src/main/kotlin/io/screenstream/core/manager/StreamingModuleManagerImpl.kt).

**History.** In separate runs:

- the factory throws
- the factory returns a Controller whose identity differs from the handle's
- the started Intent reaches a Service of a different class (defensive)
- the Controller reports failure with a message resource after `startModule` and before its first status
- `startModule` throws
- a `Context` wrapper supplies an exception from the ordinary `startService` request
- waiting-foreground notification construction or promotion throws before the Controller factory runs

After a supplied Android startup rejection, permit the next request and restart the exact failed instance. Platform qualification also moves the app into the background before dispatch or before promotion and records whether Android permits each operation.

**Expected.**

- **Generic failure.** Each failed opening ends in `Failed` for that instance with no message resource, because startup failure uses generic presentation.
- **No start.** When dispatch, waiting-foreground setup or the factory fails, the factory returns the wrong identity, or the Service class doesn't match, `startModule` is never called.
- **Promotion failure.** A failed waiting-foreground promotion does not invoke the Controller factory or retry through `startForegroundService`.
- **Adopted results are cleaned up.** A Controller that the factory returned, including one with the wrong identity, receives `requestModuleShutdown`.
- **Admission.** `isCurrent()` is false once the failure is reported.
- **Transition ends.** Supplied startup failures settle without waiting for the first-status deadline. Returning to foreground UI and restarting that exact failed instance can open a new instance when Android permits it. There is no automatic retry; a further failure requires the new failed identity.
- **Permitted startup.** If Android permits startup and promotion after a background transition, the admitted instance follows [CORE-MGR-05](#core-mgr-05); the transition alone does not force failure.

**Evidence boundary.** A wrapper or shadow supplies a rejection; it does not prove a real background-start or promotion restriction. UI origin does not guarantee later authority, and a background transition does not require rejection.

<a id="core-mgr-07"></a>

### CORE-MGR-07: First-status deadline

**Owner.** Class KDoc of [StreamingModuleManagerImpl](../../src/main/kotlin/io/screenstream/core/manager/StreamingModuleManagerImpl.kt) and [ManagerHandle.reportRunning](../../src/main/kotlin/io/screenstream/core/module/StreamingModule.kt).

**History.** Open a module and in separate runs:

- send no status, then advance both clocks past the first-status deadline
- report the first status and let the manager apply it by the deadline
- simulate deep sleep past the deadline, so only elapsed realtime advances
- advance the clock past the deadline, then report a status before the scheduler observes the deadline
- report a status before the deadline, then let the deadline pass before the manager applies it
- advance past the deadline before the Service start is delivered

**Expected.**

- **Missed deadline.** Without an applied status, the instance becomes `Failed` with generic presentation, `isCurrent()` is false, and a Controller that exists receives `requestModuleShutdown`.
- **In time.** A first status that the manager applies by the deadline produces `Running`.
- **Deep sleep counts.** The startup deadline uses elapsed realtime, so deep sleep alone can expire it.
- **Late status.** A status reported after the deadline, or reported before it but applied after it, doesn't produce `Running`.
- **Late delivery.** A fresh start delivered after the deadline neither promotes to foreground nor invokes the factory. The unowned Service stops if it has no newer pending start; the instance stays failed.

**Evidence boundary.** Robolectric's clock stands in for real deep sleep and scheduling delay. Real ordinary dispatch followed by delayed delivery and Service teardown needs platform qualification; local absence of promotion does not prove process survival on Android.

## Manager: health and commands

<a id="core-mgr-08"></a>

### CORE-MGR-08: Heartbeat health

**Owner.** [Observable process and instance health](../../docs/module-contract-and-manager.md#observable-process-and-instance-health) and [ManagerHandle.reportRunning](../../src/main/kotlin/io/screenstream/core/module/StreamingModule.kt).

**History.** From `Running`, stop reporting and advance uptime past the heartbeat limit. Then report fresh progress. Separately, report a heartbeat older than the last applied one, report a heartbeat that is already stale when it arrives, and simulate deep sleep without advancing uptime.

**Expected.**

- **Stale.** State becomes `Unresponsive` with the same instance identity and the last accepted status.
- **Restored.** Fresh progress returns the same instance to `Running`.
- **Ordering.** An older heartbeat is ignored, and state keeps the newer status.
- **Stale on arrival.** A report whose heartbeat is already stale is applied as `Unresponsive`.
- **Deep sleep.** Simulated deep sleep alone doesn't make control unresponsive, because heartbeat age uses uptime.

<a id="core-mgr-09"></a>

### CORE-MGR-09: Command routing

**Owner.** [Capture Start, Stop, and recovery](../../docs/module-contract-and-manager.md#capture-start-stop-and-recovery) and [StreamingModuleManager](../../src/main/kotlin/io/screenstream/core/StreamingModuleManager.kt).

**History.** Send Start, Stop and recovery to a `Running` instance, to an `Unresponsive` instance, and to a Controller whose command methods throw. Then send each command with a stale identity, before the first status, during a replacement, after failure and after Exit.

**Expected.**

- **Delivered.** The Controller receives Start and recovery after the manager's dispatcher runs, with the original error token. It receives Stop before `requestStreamStop` returns, with the original attempt and `fromNotification` value.
- **Unresponsive is eligible.** Commands reach an `Unresponsive` instance.
- **Dropped.** Stale identities, startup without a status, transitions, `Failed` and `Exiting` deliver nothing to any Controller.
- **Isolated failure.** A throwing Controller command leaves manager state unchanged, and later commands still route.
- **Controller decides.** The manager doesn't validate the attempt or error token beyond the instance; the Controller does.

## Manager: failure, replacement and Exit

<a id="core-mgr-10"></a>

### CORE-MGR-10: Terminal failure report

**Owner.** [ManagerHandle.reportFailed](../../src/main/kotlin/io/screenstream/core/module/StreamingModule.kt) and [Observable process and instance health](../../docs/module-contract-and-manager.md#observable-process-and-instance-health).

**History.** From `Running`, report failure with a message resource while holding cleanup. Then report another failure, report a status and send commands. Release cleanup. Select the same module, then restart it.

**Expected.**

- **Failure.** State becomes `Failed` with that instance and message resource. `isCurrent()` is false when `reportFailed` returns.
- **Shutdown.** The Controller receives `requestModuleShutdown`.
- **First failure wins.** Later failures and status reports don't change state, and commands are dropped.
- **Retained.** `Failed` stays visible after cleanup settles and after selecting the same module; restart replaces it.

<a id="core-mgr-11"></a>

### CORE-MGR-11: Replacement while old cleanup continues

**Owner.** [Closing a module instance](../../docs/module-contract-and-manager.md#closing-a-module-instance) and the class KDoc of [StreamingModuleManagerImpl](../../src/main/kotlin/io/screenstream/core/manager/StreamingModuleManagerImpl.kt).

**History.** From a `Running` instance A whose cleanup is held, select another module or restart A. In separate runs:

- release A's cleanup before the shutdown deadline
- keep it held and advance both clocks past the deadline
- after the replacement B opens, report status and failure through A's handle, then let A's cleanup finish
- restart the same module while A's Service still hosts A's Controller, and deliver B's start to that same Service object

**Expected.**

- **Closed at once.** When the request returns, A's `isCurrent()` is false, A's Controller has received `requestModuleShutdown`, and state is `Switching`.
- **Waits for cleanup or deadline.** B's Service start is requested after A's cleanup settles, or after the shutdown deadline if cleanup is still held, whichever comes first.
- **Old reports are scoped.** A's late reports and cleanup completion don't change B's state.
- **Existing Controller keeps its Service.** A start for B delivered to a Service that still hosts A's Controller fails B with generic presentation. A's Controller keeps the Service and its cleanup.

**Evidence boundary.** Android delivers starts for a component to its existing Service object. Robolectric shows the manager's response when a test delivers there; device timing of Service destruction needs platform qualification.

<a id="core-mgr-12"></a>

### CORE-MGR-12: Exit

**Owner.** [Exit](../../docs/module-contract-and-manager.md#exit) and [StreamingModuleManager.exit](../../src/main/kotlin/io/screenstream/core/StreamingModuleManager.kt).

**History.** In separate runs, Exit with no identity:

- from `Running`, with cleanup held
- while settings initialize
- during a replacement, before the new instance starts
- after ordinary Service dispatch but before fresh Service delivery, then deliver that start

Exit with the exact identity during startup, and with a stale identity or that of a `Failed` instance. During a held Exit wait, repeat Exit, select a module and send Start. Then let the wait end, and select a module.

**Expected.**

- **Closed at once.** State is `Exiting` when the request returns, `isCurrent()` is false, and any installed Controller receives `requestModuleShutdown`.
- **Opening cancelled.** Exit before dispatch requests no new Service start. A start already handed to Android can still arrive; a fresh delivery after closure neither promotes nor invokes the factory, and conditionally stops the unowned Service.
- **Instance-scoped Exit.** The exact identity applies during startup. A stale or failed identity changes nothing.
- **Shared wait.** A repeated Exit doesn't extend the wait. Selection and Start during the wait are dropped.
- **Afterward.** `Exiting` stays visible after the wait ends, with cleanup still held. A later selection opens a new instance.

<a id="core-mgr-13"></a>

### CORE-MGR-13: Cleanup outcomes and shutdown before startup

**Owner.** [Closing a module instance](../../docs/module-contract-and-manager.md#closing-a-module-instance) and [Controller.requestModuleShutdown and awaitCleanup](../../src/main/kotlin/io/screenstream/core/module/StreamingModule.kt).

**History.** Replace or exit an instance whose cleanup returns true, returns false, throws, or whose `requestModuleShutdown` throws. Separately, Exit after the ordinary Service start is requested but before it is delivered, then deliver it. Repeat a rejected fresh delivery with a newer start pending for the same unowned Service. Finally, call Exit from inside the factory, before it returns its Controller.

**Expected.**

- **Any outcome settles.** Once `awaitCleanup` returns true or false, or throws, the manager's wait ends without waiting for the deadline, and the next opening or the end of Exit follows.
- **Throwing shutdown request.** A throwing `requestModuleShutdown` doesn't end the wait early; the manager still waits for `awaitCleanup` or the deadline.
- **No start after closure.** A fresh start delivered after Exit neither promotes nor invokes the factory. The unowned Service is stopped only if that delivery is the final outstanding start.
- **Newer start retained.** When the conditional stop returns false, the Service stays up with foreground admission unchanged, and a newer admitted delivery promotes and creates its Controller. A Service whose foreground admission an earlier failure closed can't promote again.
- **Shutdown wins the race.** A Controller created after admission closed receives `requestModuleShutdown` and never `startModule`.

**Evidence boundary.** Fake cleanup completion shows the manager's wait semantics. Physical cleanup, restart and ownership controls need a Controller that stops and awaits its original Service, or a real module run.

## Manager: reentrancy and problem notifications

<a id="core-mgr-14"></a>

### CORE-MGR-14: Reentrant reports and publications

**Owner.** [ManagerHandle](../../src/main/kotlin/io/screenstream/core/module/StreamingModule.kt), [Error presentation](../../docs/notifications.md#error-presentation) and [StreamingModuleManager](../../src/main/kotlin/io/screenstream/core/StreamingModuleManager.kt).

**History.** In separate runs, a fake reenters the manager synchronously:

- the Controller reports a status from inside `startModule`
- the Controller reports failure from inside `startModule`
- the Controller reports status and failure from inside `requestModuleShutdown`
- the notification provider calls `exit()` from inside an error update

**Expected.**

- **Reports inside start apply.** A status reported inside `startModule` produces `Running`. A failure produces `Failed` with generic presentation, and the Controller receives `requestModuleShutdown`, possibly while still inside `startModule`.
- **Reports after closure are ignored.** Reports made inside `requestModuleShutdown` change nothing.
- **Provider reentrancy settles.** The final state is `Exiting`, and the provider's last call clears the problem presentation.
- **Completion.** Every call returns without deadlock.

<a id="core-mgr-15"></a>

### CORE-MGR-15: Generic problem notifications

**Owner.** [Error presentation](../../docs/notifications.md#error-presentation) and [ModuleErrorNotificationCoordinator](../../src/main/kotlin/io/screenstream/core/manager/ModuleErrorNotificationCoordinator.kt).

**History.** For one instance, report an operation error, change its message, let control become unresponsive, restore progress without an error, then report a new error. Fail the instance with a message resource. Separately, make the provider throw on a warning, then report the same episode again. Replace the instance and Exit.

**Expected.**

- **One attempt per episode.** The first error delivers one warning to the module error slot. Message changes and unresponsive health within the same episode deliver nothing more.
- **Clearing.** Responsive control without an error clears the presentation, and a new error starts a new episode.
- **Terminal failure.** `Failed` makes a new update attempt that replaces the warning, carrying the failure's message resource.
- **Provider failure.** A throwing provider leaves manager state unchanged and isn't retried within the episode.
- **Transitions.** Replacement and Exit clear the presentation.

**Evidence boundary.** The fake provider shows the manager's requests. Android delivery and visibility belong to the provider and platform.

## Consent test boundary

Run the real `ScreenCaptureAccessImpl` under Robolectric. A test controls:

- **Hosts.** Host tokens and the calls that `ScreenCaptureConsentHost` makes: resumed, inactive, submit, education cancel, abandonment, result delivery and launch failure.
- **Attempt.** A capture attempt identity and its `Job`, with `requestConsent` called in a child of that job.
- **Foreground callback.** A callback that returns true or false, throws or cancels the attempt.
- **Activity results.** The result code and data delivered for a request key.
- **Platform acquisition.** A test-defined Robolectric shadow of `MediaProjectionManager` that supplies the consent Intent and returns a projection or throws.
- **SDK level.** Robolectric runs on each side of the version boundaries named in [Testing](01-testing.md#choosing-an-environment).

The coordinator is constructed with an inert `ConsentRequestObserver`. Its calls are not asserted.

Driving the host calls directly doesn't verify the Compose host's lifecycle wiring, education dialog or launcher; those are excluded UI. Android's consent dialog and real projections need platform qualification.

## Consent: host and admission

<a id="core-cap-01"></a>

### CORE-CAP-01: Host availability and selection

**Owner.** [Host and request ownership](../../docs/screen-capture.md#host-and-request-ownership) and [ScreenCaptureAccess.requestConsent](../../src/main/kotlin/io/screenstream/core/capture/ScreenCaptureAccess.kt).

**History.** Request consent with no resumed host and no saved consent. Resume hosts H1 and then H2, and request again. Before submission, make H2 inactive while H1 stays resumed, then request again. Separately, resume H1 again while an unsubmitted request belongs to H2.

**Expected.**

- **No host.** The request returns `NotGranted(HostUnavailable)` at once, and nothing stays pending.
- **Latest host selected.** With both resumed, only H2 can submit.
- **Selection change ends the request.** A change of the selected host before submission ends the request with `HostUnavailable`.
- **Fallback.** The next request is presented to the remaining resumed host, H1.

<a id="core-cap-02"></a>

### CORE-CAP-02: Busy admission and an inactive attempt

**Owner.** [ScreenCaptureAccess.requestConsent](../../src/main/kotlin/io/screenstream/core/capture/ScreenCaptureAccess.kt) and [Projection creation and handoff](../../docs/screen-capture.md#projection-creation-and-handoff).

**History.** While one request is pending, request consent for another attempt. While a projection-creation guard is held, request consent. Request consent with an attempt job that is already cancelled.

**Expected.**

- **Busy.** Both overlapping requests return `NotGranted(Busy)` at once, and the pending request or guard is unaffected.
- **Inactive attempt.** The call ends with cancellation, and nothing becomes pending.

<a id="core-cap-03"></a>

### CORE-CAP-03: Education declined

**Owner.** [Host and request ownership](../../docs/screen-capture.md#host-and-request-ownership) and [NoGrantReason.EducationDeclined](../../src/main/kotlin/io/screenstream/core/capture/ScreenCaptureAccess.kt).

**History.** Before submission, cancel education from the selected host. Separately, cancel from a host that isn't selected, cancel with another request's key, and cancel after submission.

**Expected.**

- **Declined.** Cancellation by the selected host before submission returns `NotGranted(EducationDeclined)`.
- **Ignored.** The other cancellations leave the request pending.

## Consent: submission and results

<a id="core-cap-04"></a>

### CORE-CAP-04: Single submission and result correlation

**Owner.** [Host and request ownership](../../docs/screen-capture.md#host-and-request-ownership) and [ScreenCaptureConsentHost](../../src/main/kotlin/io/screenstream/core/compose/ScreenCaptureConsentHost.kt).

**History.** Submit from the selected host. Submit again, and submit from a new host token after recreation. After submission, make every host inactive, then resume a different host. Deliver a result with another key, then with the matching key, then deliver the matching key again.

**Expected.**

- **One launch.** The first submission returns the Android Intent; later submissions return nothing.
- **Host changes after submission.** The request stays pending across host pause, change and recreation.
- **Correlation.** A result with another key changes nothing. The matching result settles the request even with no resumed host. A repeated result is ignored.

<a id="core-cap-05"></a>

### CORE-CAP-05: Result outcomes

**Owner.** [ScreenCaptureAccess.requestConsent](../../src/main/kotlin/io/screenstream/core/capture/ScreenCaptureAccess.kt) and [How they collaborate](../../docs/screen-capture.md#how-they-collaborate).

**History.** After submission, deliver in separate runs:

- a successful result with data, while the foreground callback returns true, returns false or throws
- a non-success result
- a successful result without data
- a result for a request that hasn't been submitted
- a launch failure for the request, with an ordinary cause and with a cancellation cause, and a launch failure with another key

**Expected.**

- **Granted.** The callback runs once on the main thread after the result. True returns `Granted`, false returns `NotGranted(ForegroundRejected)`, and an exception reaches the caller.
- **Declined.** A non-success result returns `NotGranted(Declined)` without calling the callback.
- **Technical failures.** Missing data and a result before submission end the request with an exception, without calling the callback.
- **Launch failure.** An ordinary cause reaches the caller as `ConsentLaunchException` with that cause. A cancellation cause ends the request with cancellation. A launch failure with another key isn't claimed, and the request stays pending.

<a id="core-cap-06"></a>

### CORE-CAP-06: Cancellation

**Owner.** [Host and request ownership](../../docs/screen-capture.md#host-and-request-ownership) and [ScreenCaptureAccess.requestConsent](../../src/main/kotlin/io/screenstream/core/capture/ScreenCaptureAccess.kt).

**History.** Cancel the attempt while the request awaits submission, then try to submit. Cancel after submission, then deliver a successful result. Separately, cancel the attempt from inside the foreground callback and return true. After each, request consent for a new attempt.

**Expected.**

- **Request removed.** Cancellation ends the caller with cancellation and removes the request: a late submission returns nothing, and a late result doesn't call the callback.
- **Cancellation after promotion.** The callback ran and returned true, but the caller ends with cancellation instead of `Granted`. Foreground rollback stays with the attempt.
- **Next request admitted.** A new attempt isn't `Busy`.

**Evidence boundary.** Android's consent dialog can remain visible after cancellation; a local run can't observe it.

## Consent: projection acquisition

<a id="core-cap-07"></a>

### CORE-CAP-07: Single-use grant and creation guard

**Owner.** [Projection creation and handoff](../../docs/screen-capture.md#projection-creation-and-handoff) and [ScreenCaptureAccess.createProjection and finishProjectionCreation](../../src/main/kotlin/io/screenstream/core/capture/ScreenCaptureAccess.kt).

**History.** With a `Granted` result, acquire a projection on a worker thread. Acquire again with the same grant, with a grant from another coordinator and with a grant whose attempt job was cancelled. While the guard is held, request consent and acquire with another grant. Finish creation for another attempt, then for the matching attempt, then again.

**Expected.**

- **Single use.** A grant admits one acquisition. Reuse and a grant from another coordinator throw.
- **Inactive attempt.** A cancelled attempt job ends acquisition with cancellation.
- **Guard.** While the guard is held, consent requests return `Busy` and another acquisition throws, including after the platform call fails.
- **Finish.** Finishing another attempt keeps the guard; finishing the matching attempt releases it, and a repeated finish changes nothing.

**Evidence boundary.** The test shadow supplies the acquisition results, so a run shows how Core responds to them. Real projection creation needs platform qualification.

<a id="core-cap-08"></a>

### CORE-CAP-08: Acquisition retry after a recent foreground start

**Owner.** [ScreenCaptureAccess.createProjection](../../src/main/kotlin/io/screenstream/core/capture/ScreenCaptureAccess.kt), which also defines the retry delay and window.

**History.** Make the platform acquisition throw `SecurityException`. Vary the retry flag, the SDK level before and from SDK 34, and the time since foreground preparation inside and outside the retry window.

**Expected.**

- **One retry.** With the flag on, before SDK 34 and inside the window, acquisition retries once after the delay and uses the same grant and guard.
- **No retry.** Otherwise the first `SecurityException` reaches the caller, after a single platform call.

**Evidence boundary.** The test shadow supplies the `SecurityException`. Whether Android raises it shortly after a foreground start needs platform qualification.

## Consent: legacy reuse

<a id="core-cap-09"></a>

### CORE-CAP-09: Legacy consent reuse on SDK 24–33

**Owner.** [Legacy reuse and cleanup](../../docs/screen-capture.md#legacy-reuse-and-cleanup) and [ScreenCaptureAccess.saveConsentForReuse](../../src/main/kotlin/io/screenstream/core/capture/ScreenCaptureAccess.kt).

**History.** On SDK 24–33, obtain a fresh grant, acquire with it, finish creation and save the grant for reuse. With no resumed host, request consent for a new attempt and complete it. Request again without saving. Separately:

- let the foreground callback reject on the saved path
- save a grant that was never used for acquisition
- save an older attempt's grant after a newer attempt was admitted
- save after the attempt job was cancelled, then save the same grant again
- create a new coordinator after saving

**Expected.**

- **Reuse.** The next request needs no host. The foreground callback runs, and the result is `Granted`. That grant needs its own acquisition.
- **Consumed when admitted.** The following request needs fresh consent, including after a rejected foreground callback.
- **Eligibility.** An unused grant and an older attempt's grant leave no saved consent. A cancelled attempt job doesn't prevent saving, and a grant is saved at most once.
- **Process-local.** A new coordinator starts without saved consent.

<a id="core-cap-10"></a>

### CORE-CAP-10: Fresh consent from SDK 34

**Owner.** [Legacy reuse and cleanup](../../docs/screen-capture.md#legacy-reuse-and-cleanup) and [ScreenCaptureAccess.saveConsentForReuse](../../src/main/kotlin/io/screenstream/core/capture/ScreenCaptureAccess.kt).

**History.** On SDK 34 or later, repeat the successful history of [CORE-CAP-09](#core-cap-09): save a used grant, then request consent with no host and with a resumed host.

**Expected.**

- **No reuse.** Without a host, the request returns `HostUnavailable`. With a host, it waits for a fresh submission.

## Consent: host loss after submission

<a id="core-cap-12"></a>

### CORE-CAP-12: Submitted request abandoned by a finishing Activity

**Owner.** [Host and request ownership](../../docs/screen-capture.md#host-and-request-ownership) and [NoGrantReason.HostUnavailable](../../src/main/kotlin/io/screenstream/core/capture/ScreenCaptureAccess.kt).

**History.** Submit a request, make its host inactive and resume another host. Report abandonment for the submitted key twice, then deliver a successful result for it. Separately:

- report abandonment for an unknown key and for a request that hasn't been submitted
- abandon a request, submit a newer one, then deliver abandonment, a successful result and a launch failure with the old key
- deliver a successful result, then report abandonment for the same key
- cancel the attempt just before and just after abandonment

After each, request consent for a new attempt.

**Expected.**

- **Settled.** Abandonment returns `NotGranted(HostUnavailable)` without calling the foreground callback, and the attempt job stays active. The repeat and the late result change nothing.
- **Ignored.** An unknown or unsubmitted key leaves the request pending and submittable.
- **Newer request unaffected.** Calls with the old key settle nothing; the newer request later completes normally.
- **Result first.** A result delivered before abandonment keeps its grant and foreground preparation.
- **Cancellation.** An attempt cancelled before its caller resumes ends with cancellation in either order, and nothing stays pending.
- **Next request admitted.** A new attempt can request fresh consent.

**Evidence boundary.** A local run calls the coordinator's abandonment method directly. Whether the Compose host calls it only when the submitting launcher is disposed while its Activity is finishing is excluded UI wiring, and real Activity finishing and task loss need platform qualification.

## Outside this catalogue

The deferred Core suites and the exclusions are listed in [Testing](01-testing.md#scope). Streaming modules verify their own use of Core in their own catalogues, for example MJPEG's [capture scenarios](../../../mjpeg/internal/03-testing/scenarios/03-capture.md).
