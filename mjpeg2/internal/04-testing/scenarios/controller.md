[Scenario index](../verification-contracts.md) · [Running tests](../testing.md)

# Controller admission and reconciliation

Behavior: [maintained contract](../../01-lifecycle/coordination.md). Use L1 with a real test Service and supplied runtime, discovery, delivery and consent inputs. Observe processed checkpoints and arrange Android elapsed time explicitly. Supplied permission plans do not establish actual foreground/capture, Service destruction or socket enforcement; use L2 for those boundaries.

These business histories share the same contract across supported API 24–37. The controller's only SDK gate is LAN admission: runtime 24–36 bypasses the grant check; API 37 checks it only for target 37+. Use focused fixtures at runtime 24/36 and API 37 target 36/37, including grant/revocation rechecks; supplied discovery and listeners do not enforce Android sockets. [Network scenarios](network.md#mjpeg-lan-01) distinguish this gate from actual platform enforcement.

## Module admission and terminal shutdown

<a id="mjpeg-ctl-01"></a>

- **Inert/current module startup.** Construct the controller, then request module start once, repeatedly or after its bound runtime becomes stale. Construction does not admit capture/listener work. A current start begins ordinary configuration/discovery; stale start closes admission and enters cleanup. No second module lifetime is created by repeated start.

<a id="mjpeg-ctl-12"></a>

- **Shutdown before start and admission race.** Shutdown is requested before first start, during configuration, or while capture admission competes with it. Issue repeated shutdown and then later Start/Stop/settings events. Admission closes independent of event-loop progress; accepted owners remain accounted for cleanup; no later ordinary work starts; UI clearing is excluded from the verdict. Existing cleanup continues despite caller cancellation.

A synchronously entered delivery start remains admitted controller work: even after other cleanup owners settle, module cleanup waits until that call returns; later capture admission stays closed.

## Start readiness

<a id="mjpeg-ctl-02"></a>

Request stream Start while configuration, fresh discovery inventory, permitted selected address or Listening server is independently missing/stale, then supply a current ready combination. Only idle/current/configured capture with an address selected by the effective filter in both fresh and retained selections and a matching Listening server is admitted. Pending/Failed/wrong-port/unpermitted/stale-runtime facts cannot admit Start; duplicate Start cannot create another attempt.

## Exact Stop and replacement

<a id="mjpeg-ctl-03"></a>

A capture attempt exists; a previous/foreign attempt ID is also available. Issue stale Stop, exact Stop, repeated Stop and then Start after proved cleanup. Stale Stop cannot end current capture; exact Stop closes that capture without shutting HTTP/module down; successful cleanup permits a fresh attempt. Old late capture events cannot update its successor.

## Capture and consumer reports

<a id="mjpeg-ctl-04"></a>

Capture is idle/starting/confirmed active/suspended/stopping/failed; delivery independently has active media rows or none. Process boundary updates and heartbeat turns. Runtime reports isStreaming only after confirmed startup and before accepted Stop/failure; suspended confirmed capture still counts. hasConsumer is independent; captureAttempt persists through pending/failed cleanup and clears after successful cleanup. Reports use uptime and recur subject to scheduling delays.

## Saved versus applied setup

<a id="mjpeg-ctl-05"></a>

A capture is waiting, active, stopping, or blocked after failed cleanup; latest observed settings differ in restricted and live fields. Observe settings; complete cleanup successfully or fail it. Live image/filter/page/behavior changes apply while backend/port/access retain current applied values. Successful cleanup reapplies latest observed snapshot; failure preserves restriction and prevents replacement. Save completion alone proves neither observation nor application.

## Module-start PIN policy

<a id="mjpeg-ctl-06"></a>

Protected settings use NewOnModuleStart or Permanent; module then starts. An initially open module later enables protection with absent or existing saved PIN. Observe activation, capture Start/Stop, and same-module background continuity. Protected NewOnModuleStart generates/saves once at module activation; Permanent retains the PIN. Capture restarts do not regenerate it. PIN validity/application is the oracle, not random-value inequality. Initial open preparation is still once-only: later protection enabling generates only a missing PIN and preserves an existing one, without a second module-start regeneration. Verify saved and applied access settings in both open-to-protected histories after exact-attempt Stop and confirmed adapter cleanup.

## PIN settings and application

<a id="mjpeg-ctl-07"></a>

Typed PIN edits and regeneration use the Access settings group's ordinary update path. Exercise editable/locked UI, Permanent/NewOnModuleStart policy and policy changes during a held prior save. Typed edits require six ASCII digits and Permanent in the latest transaction; regeneration works under either policy when setup edits are allowed. Saves use the shared settings mutex; Start need not wait for a save. A late saved observation while busy cannot change applied credentials, and successful cleanup makes the latest saved access settings eligible for application. Verify save failures through the existing access-settings Snackbar; cancellation is not a save error.

## Retained discovery plan

<a id="mjpeg-ctl-08"></a>

A ready selection has been applied; discovery becomes Loading or the effective filter changes. Loading retains the prior ready selection. An accepted filter change selects immediately from the processed inventory without a discovery acknowledgement; a later Observed inventory updates that selection. Independently revoke permission and verify immediate filtering of the retained plan. UI availability requires separate UI verification.

## Network-loss episode and recovery

<a id="mjpeg-ctl-09"></a>

- **Network-loss episode and timeout edits.** An exact capture exists and matching Observed addresses becomes empty; bind failure with nonempty addresses is a separate variant. Advance a faithful elapsed-time arrangement; shorten/lengthen timeout within the same episode. Empty selected discovery starts one loss episode; both shortening and lengthening the timeout retain the original loss time; return of addresses resets/cancels it; pending/mismatched discovery is not confirmed loss. Bind failures alone do not trigger this Stop.

<a id="mjpeg-ctl-10"></a>

- **Recovery already present at loss deadline.** At the loss deadline, recovery may be queued or already present in monitor.state; stale attempt/filter variants also matter. The decision must select from the current observed inventory with the effective filter. Current recovery prevents network-loss Stop even if its event is unprocessed; only the still-current empty-selection episode can stop its exact capture. Stop/failure/cleanup clears the episode; old deadlines do not stop a replacement.

## Component and configuration failure

<a id="mjpeg-ctl-11"></a>

Settings/monitor observations fail, monitor unexpectedly becomes Failed/Closed, HTTP publishes fatalCause, or original Service is destroyed. Initial logo loading fails or HTTP rejects initial/subsequent configuration in separate variants. Deliver each independent failure through its maintained boundary. The module reports failure and closes admission/owners; fatal delivery does not leave an apparently usable module. Original-Service loss cannot redirect work into another instance; exceptions do not skip terminal shutdown. Asset/configuration failure reports module failure and begins independent shutdown rather than advertising successful configuration.

## Independent module cleanup

<a id="mjpeg-ctl-13"></a>

Hold or fail HTTP, monitor and platform/capture/Service cleanup independently. Request shutdown, cancel one cleanup waiter and settle the remaining obligations. All owners are requested/awaited even when another fails. Success requires HTTP+monitor+capture/platform cleanup, admitted controller work and original Service destruction; failure returns false. One cancelled waiter cannot abandon owners or permit replacement.

Complete module cleanup is described in [module lifecycle](../../01-lifecycle/module-lifecycle.md#controller-shutdown-and-complete-module-cleanup).
