# Streaming Module Contract and Manager

[Core](../README.md)

`StreamingModule` describes a streaming module. `StreamingModuleManager`, an application-process singleton, selects registered modules, opens and replaces their instances, routes commands and publishes state.

A **module instance** is one opening of a registered module. The manager admits at most one instance to new work at a time. Capture Start and a normal Stop happen inside an instance; restarting or reopening a module creates a new instance.

## Participants and relationships

- **Manager.** Owns selection, opening, replacement, command routing, health observation and shutdown requests.
- **Module definition.** The `StreamingModule` descriptor that the app registers. It names the module, its priority, its `StreamingModuleService` subclass and that Service's foreground notification slot. One definition serves every opening.
- **Instance Service.** The module's `StreamingModuleService` subclass. For an admitted start it enters foreground, then calls the module's Controller factory. It hosts the instance's foreground notification.
- **Controller.** The module's `StreamingModule.Controller` for one instance. It executes commands and owns streaming, module UI, resources and cleanup. The manager installs and starts it, sends commands directly to it and requests its shutdown.
- **ManagerHandle.** The instance's connection to the manager, shared by its Service and Controller. The manager creates it before the Service start is delivered. It carries the instance identity and foreground slot, answers admission checks and accepts status and failure reports.

```mermaid
flowchart LR
    Manager[Manager]
    Service[Instance Service]
    Controller[Instance Controller]
    ManagerHandle[Instance ManagerHandle]
    Manager -->|Direct commands| Controller
    Service -->|Creates and hosts| Controller
    Service -->|Instance checks| ManagerHandle
    Controller -->|Checks / reports| ManagerHandle
    Manager <-->|Admission / reports| ManagerHandle
```

## How they collaborate

1. **Opening.** The app selects a module. The manager resolves the choice, creates the instance identity and handle, and starts the Service with ordinary `Context.startService`.
2. **Waiting foreground.** For an admitted start, the Service enters **waiting foreground** ([foreground presentation](notifications.md#foreground-presentation)), with an Exit action, then calls the Controller factory. If the instance is still admitted, the manager installs and starts the Controller. The first accepted status completes startup and publishes `Running`, or `Unresponsive` if its heartbeat is already stale.
3. **Capture.** An accepted Start begins a **capture attempt**. The module checks [runtime permissions](runtime-permissions.md) and obtains [screen-sharing consent](screen-capture.md). After consent and before acquiring the `MediaProjection`, the same Service switches to capture foreground, with microphone use when the module requires it ([foreground presentation](notifications.md#foreground-presentation)). The module then prepares its media work and starts capture.
4. **Stop or closure.** A normal Stop cleans up the attempt and returns the Service to waiting foreground for another Start. Replacement, restart, Exit and terminal failure close the instance to new work and request Controller shutdown.

A start delivered after Exit, or after startup admission expired, neither promotes the Service nor creates a Controller. The unowned Service stops unless a newer start for it is pending. Because the manager uses `startService` rather than `startForegroundService`, a rejected delivery leaves no pending obligation to call `startForeground`.

## Identities and lifetimes

| Identity | Meaning |
| --- | --- |
| `StreamingModule.Id` | The registered module, stable across openings. Registered IDs are nonblank and unique. |
| `StreamingModule.InstanceId` | One opening of that module. Restart and reopening create a new one. |
| `StreamingModule.CaptureAttemptId` | One accepted Start within an instance. |

The Controller and handle belong to one instance until its cleanup ends. After admission closes, the handle still carries that instance's identity, and its reports are ignored, so callbacks from an old instance never affect its replacement.

The manager routes Start, but the Controller decides readiness and owns capture admission. A Service admits one attempt at a time to capture foreground; the module decides how it serializes the rest of an attempt.

### Closing a module instance

Closing stops new work and asks the Controller to clean up. Shutdown can be requested before `startModule` runs or while the control loop is stalled.

The Controller finishes capture cleanup, then stops its original Service with `stopAndAwaitDestruction` and waits for its destruction. If the Service is destroyed for another reason, it notifies the same Controller, which closes admission and keeps cleanup ownership. An Android foreground-service timeout or a failed foreground call makes the Service report instance failure and request Controller shutdown.

```mermaid
sequenceDiagram
    participant M as Manager
    participant C as Instance Controller
    participant S as Instance Service
    M->>C: Close new work and request shutdown
    Note over C: Cleanup has its own lifetime
    M->>C: Wait for cleanup
    Note over M: Bounded transition wait
    C->>S: Stop Service after capture cleanup
    S-->>C: Service destroyed
    C-->>M: Cleanup outcome if the manager is still waiting
```

Replacement and Exit wait a bounded time for the previous instance's cleanup. The manager records one shutdown deadline per instance, and later waits reuse it. Repeated requests therefore don't extend the wait, and restarting an instance that failed earlier may find its deadline already past. When the wait ends, the manager moves on: old cleanup can continue after `Closed` or alongside a replacement, and older resources may still be releasing. Cleanup completion follows the guarantees of the module's resource owners.

A replacement that uses a different Service component can open while the old Service is still alive. One that reuses the same component needs the old Service destroyed first: a start delivered to a Service that still hosts a Controller fails the new instance, and the old Controller keeps its Service.

## Manager commands

Commands are requests. The manager drops unknown, stale and ill-timed requests. Observe `state` for lifecycle outcomes.

### Selection and restart

`selectModule(id)` is a suspending main-thread call. An explicit registered ID selects that module. A first request without an ID uses the saved module if it is still registered, otherwise the highest-priority module. Later requests without an ID use the in-memory choice. The manager saves the choice asynchronously on the first opening when no ID is stored and when an explicit selection changes the automatic choice.

The first selection may suspend until settings are ready. Competing requests can wait for the same load and check admission when they resume. A request that resumes while a transition is still active, or after an Exit was accepted during its wait, returns `false`. The Exit case applies even when a later selection has already reopened a module. Cancelling the caller during the wait abandons its request. Settings loading and a claimed opening continue independently of the caller.

The result only tells the app whether to offer its module chooser. It is `true` only when a request without an ID claims the first opening and there is no stored ID. A saved ID for an unavailable module still counts as a stored ID. Other selections return `false`, including accepted explicit choices, remembered choices, and ignored requests. The result doesn't report acceptance or startup, so observe `state` for those.

Selecting the current module keeps its instance, even a failed one. `restartModule` replaces that exact instance with a new opening of the same module. An active transition, `Opening` or `Closing`, blocks selection and restart. In `Closed`, only a request without an ID claims a new opening, of the remembered module. Explicit IDs and restart are ignored.

Android can reject the ordinary Service start or the later waiting-foreground promotion, for example when the app leaves the foreground during preparation. A request from UI doesn't guarantee permission at either later call, and leaving the foreground doesn't always cause rejection. Any startup failure, a missing first status, or a start delivered to a Service that still hosts the previous Controller publishes generic `Failed`. The manager doesn't retry: return to foreground UI and restart that exact failed instance.

### Capture Start, Stop, and recovery

The manager forwards these commands only while the target is the current admitted instance, in `Running` or `Unresponsive`, and no transition is active.

- **Start** is forwarded asynchronously. The Controller decides readiness and owns the attempt that follows.
- **Stop** targets one capture attempt and is forwarded synchronously on the main thread. The Controller, not the manager, matches the attempt, because reported status can lag behind the notification that offered Stop. A normal Stop leaves the instance open.
- **Recovery** asks the module to handle an operation error in place. The Controller validates the error token; a module without recovery ignores it.

### Exit

Exit closes new work, cancels a pending opening and returns while shutdown proceeds. It can also supersede a selection still waiting for settings. `exit()` applies regardless of the current instance, including before any module has opened. `exit(instanceId)` applies only while that instance is the current admitted instance, including during startup before its first status. Stale, replaced, closing and failed identities are ignored.

`exit` returns `true` only for the request that accepts Exit. Ignored exact requests and any request during `Closing` or `Closed` return `false` without starting another Exit.

When nothing remains to wait for, because no instance is current, its cleanup is already reported or its shutdown deadline has passed, the manager publishes `Closed` before `exit` returns, without `Closing`. Otherwise it publishes `Closing` until cleanup is reported or the instance's existing shutdown deadline passes, then `Closed`. Later requests don't extend that deadline. During `Closing`, selection, restart and commands are dropped. `Closed` means only that the bounded wait has ended and no instance is current. Cleanup can continue. `Closed` stays published until `selectModule()` without an ID claims `Opening`.

The host app owns any UI or task changes that follow an accepted Exit. Task removal and media cleanup complete independently.

## Observable process and instance health

`StreamingModuleManager.state` publishes one snapshot of the manager lifecycle and the current instance's control health, in one of seven states.

| State | Meaning |
| --- | --- |
| `NoModule` | No opening is claimed. This initial state differs from `Closed`. A first selection may already be waiting for settings. |
| `Opening` | A selection or restart has claimed an opening. It may be waiting for the previous instance's bounded shutdown or the new instance's first status, and ends in `Running`, `Unresponsive` or `Failed` unless Exit supersedes it. |
| `Running` | The current Controller reports fresh progress. |
| `Unresponsive` | Progress is stale; the last accepted status is retained. |
| `Failed` | Terminal failure. Admission is closed and cleanup proceeds. Remains until another module is selected, a restart or Exit. |
| `Closing` | Exit has closed admission and waits, until the instance's shutdown deadline at most, for its cleanup. |
| `Closed` | Exit's bounded wait has ended and no instance is current. Earlier cleanup may continue. Remains until a selection without an ID claims `Opening`. |

`Opening` and `Closing` are the active transitions, and at most one is active. Exit supersedes `Opening`. Nothing supersedes `Closing`, which always ends in `Closed`. Only Exit leads to `Closing` or `Closed`, and only `selectModule()` without an ID leaves `Closed`.

The manager sets its internal transition before synchronous callbacks run, such as a Controller's shutdown request. Reentrant calls therefore follow that transition's admission rules even before the published snapshot changes. Collectors see only the latest snapshot and can skip intermediate states. An Exit with nothing to wait for publishes `Closed` without `Closing`, and a selection can replace `Closed` with `Opening` before a collector runs.

Health shows whether Controller work is progressing, not whether capture or the network is ready; an idle Controller is healthy while it keeps reporting. `Running` and `Unresponsive` refer to the same instance, commands reach both, and a fresh report restores `Running`.

Each report carries a `StreamingModule.Status`: whether capture is streaming, whether a consumer is present, the current capture attempt and any operation error. Streaming and consumer presence change independently. An operation error leaves the instance usable; terminal failure is reported separately through `reportFailed`.

## Module UI and notifications

The Controller owns its module UI. `InstanceContent` supplies that UI, its sections and separate capture button, to the app's layout inside the current admitted Controller's composition. When the requested instance is unavailable, the manager composes the app's fallback. Rendering changes are independent of the Controller's work and cleanup.

Modules supply `Ui.sections` in display order and give each section a stable ID within its module instance. `Overview` covers connection details, operating mode, errors and statistics. `SettingsSearch` and `Settings` identify the search field and configuration sections. The app uses these roles to place sections; in its side-by-side layout, the current app keeps the leading `Overview` sections and the capture button in the main pane, shows the remaining sections in the side pane and pins a `SettingsSearch` section that comes first there.

The app binds a host for each pane with `Ui.ModalHost.Provide`, and section content reads that host through `Ui.ModalHost.current`. The app supplies the modal window and any title chrome. The module owns the content, visibility and actions, including what to do when the user requests dismissal. Modal content receives bounded height and owns its own padding and scrolling. A null title gives untitled content such as a QR preview. Removing a section does not request dismissal.

Keep drafts and scroll positions that must survive a switch between sheet and dialog in the modal caller.

[Streaming Module Notifications](notifications.md) describes how the Service, the manager and the Controller each present their notifications through the app's provider.
