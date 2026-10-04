[Internals](../README.md) · [Streaming host](streaming-host.md) · [Module lifecycle](module-lifecycle.md)

# Controller coordination

[MjpegStreamingModuleController](../../src/main/kotlin/io/screenstream/mjpeg/MjpegStreamingModuleController.kt) serializes commands and effective settings for one launch. It binds its Runtime, original Service, HTTP, address monitor and capture Access once; commands/reports never find a replacement. This page describes when the controller accepts work and reconciles its owners; linked pages define each owner's resource and algorithm contracts.

## Event loop and independent shutdown

The inert controller has a lazy Default event loop, a SupervisorJob and an unlimited event channel. `startModule` starts it only if Runtime is current, otherwise requests Shutdown. Settings/address/HTTP observations enqueue events; settings collection first performs the protected NewOnModuleStart PIN regeneration transaction. HTTP events deduplicate server/consumer/fatal-cause changes rather than every statistics sample.

Only the loop mutates activity, effective/latest-observed settings, selected-address facts and the current network-loss episode. Each iteration reports processed uptime when due, checks LAN grant, calculates wait deadlines, handles one event, reads a fresh delivery snapshot, publishes UI and reports changed Status. A non-null HTTP fatal cause becomes a controller failure. Unexpected settings/discovery/delivery/loop failure reports manager failure and requests terminal Shutdown; an unexpectedly Closed/Failed monitor is not treated as recoverable discovery.

The one-second heartbeat wakes and runs in the real loop; the timer does not synthesize health. The next wait is the minimum of heartbeat and empty-selection deadline. Shutdown does not enqueue an event: it closes owner admission and begins independent cleanup even if the loop is blocked or never started. Ordinary enqueue ignores work after the terminal signal. Full shutdown mechanics belong to [Module lifecycle](module-lifecycle.md#controller-shutdown-and-complete-module-cleanup).

## Activity and command admission

| Controller activity | Start/setup boundary | Current screen action |
| --- | --- | --- |
| NotStarted | Settings/configuration not yet established; LiveOnly | Busy after loop publication; initial UI is disabled Start. |
| Idle | Setup edits allowed; Start also needs current listener readiness | Start enabled only when configuration/listener checks pass. |
| Capturing | Attempt owns consent through cleanup; LiveOnly | Stop after successful startup or in Suspended, otherwise Busy. |
| BlockedCleanup | Last exact attempt retained; LiveOnly | Disabled Start. |
| Closing | No new work | Busy and cleared rows. |

Action is advisory, not command authority. Start checks effective settings, Idle and current available listener when handled. It creates a new attempt UUID, registers HTTP capture under the shared capture-admission gate, constructs/runs the session and retains it for cleanup. Shutdown cannot pass that gate and miss a new owner.

Stop checks the exact session ID in current Capturing activity, requests the chosen reason and processes the latest capture session state; a stale attempt is ignored. Exact Stop can be handled during consent/startup/cleanup despite Busy. Retry delegates its server UUID to HTTP, which validates the current Failed incarnation. No command return confirms acceptance or completion.

CaptureChanged identifies an attempt but causes a fresh state read; event payload does not carry a second snapshot. New capture/cleanup failure references are logged/notified once as processed. Null cleanup keeps Capturing, true becomes Idle, false becomes BlockedCleanup. The processed status goes to HTTP. Successful cleanup reapplies the latest observed preferences. Module status uses processed capture facts and fresh sampled HTTP consumer presence; the blocked attempt remains identified until shutdown.

## Effective settings and saves

The controller retains the latest saved observation and effective settings: the values accepted under the current edit policy. Each observation derives a new accepted value. LiveOnly freezes backend, HTTP port and all access settings from the previous effective value, while accepting image dynamics, filter, page and behavior fields. This prevents late saved setup edits from changing a busy attempt. Exact schema/validation/write rules belong to [Settings](../02-configuration/settings.md).

Changed image values update the capture session's desired parameters. Changed filters update discovery and create/clear the LAN request identity. Changed network values update the HTTP plan. First settings move NotStarted to Idle. Initial HTTP configuration reads the logo once on IO and transfers the bytes to HTTP; subsequent changes update access/page/stopped-image policy. A rejected HTTP configuration is a module failure, not successful convergence.

PIN command admission is checked in the loop. Set requires six ASCII digits and current Permanent policy; regeneration works with either policy. Both require setup edits. Admitted save jobs join the previous save, and the transaction rechecks Permanent before Set. They do not form an atomic transaction with HTTP or delay a following Start, which uses currently applied access. An entered non-cancellable preference save may finish after Shutdown; complete controller cleanup joins it without reopening the module.

## Discovery plan and Start readiness

The controller trusts the monitor's complete selected list rather than repeating discovery filtering. It accepts an Observed list only when its filter matches effective settings. While Loading or waiting for a new filter result it retains the last ready selection, immediately filtering that retained plan by current permission. HTTP owns its last accepted ready plan; Start separately checks the monitor's freshest observation.

Start readiness requires open/current runtime, effective network settings, a fresh matching Observed filter, a Listening server at the applied port, matching address identity in that observation, and a permitted retained selected address. UI adds Idle and applied configuration. A stale visible listening row, unmatched filter, arbitrary selected IP or successful HTTP activation alone cannot admit Start.

HTTP planned UUID and discovery address ID are independent. A grant change can remove/reintroduce a server with a new HTTP UUID/bind budget while preserving its discovery row. [Network addresses](../02-configuration/network-addresses.md) owns uncertain reads/identity; [HTTP delivery](../03-delivery/http-delivery.md) owns plan/incarnation/bind behavior.

## Network-loss deadline

Only Capturing with a matching Observed **empty selected-address list** starts an episode. Consent/startup/Active/Reconfiguring/Suspended can participate; Stopping/Stopped/Failed cannot. The episode belongs to exact attempt and filter and records elapsed realtime at first empty observation.

The configured timeout adds to that original time. Editing it does not restart loss. Any selected address, absent/mismatching observation, terminal phase or changed attempt/filter clears the episode. LAN denial or bind failure while selected addresses exist does not count as this loss. A timeout cannot start a later capture automatically.

At expiry, the controller rereads `addressMonitor.state.value` before exact System Stop. Recovery already published but still queued in the loop wins. Current null/mismatched/nonempty state clears the episode instead. [Module lifecycle](module-lifecycle.md#stop-and-suspension) owns Stop outcome; timeout never claims cleanup completed.

## LAN permission request identity

The current SDK/target check requires `ACCESS_LOCAL_NETWORK` on SDK 37+ and target 37+. Discovery rows classify whether they require the module's check: non-loopback Wi-Fi/Ethernet/Other do, Mobile/VPN do not. The filter's requirement reflects its selected interface/category groups. These are module admission rules, not an assertion that every selected route is reachable; see [Android local-network protections](https://developer.android.com/privacy-and-security/local-network-permission).

Changed accepted filter creates a `LocalNetworkPermissionRequest` only when needed. The object preserves one automatic claim budget and a callback bound to its original module. Eligibility requires exact current request identity, no Shutdown, current Runtime and fresh missing grant. A→B→A creates another request; publication/recomposition does not replenish an existing object's budget. Manual claim does not consume the automatic budget.

Compose owns launcher/Activity/lifecycle and claims before launching only while resumed. Claim and the Android call are not atomic with Shutdown. The launcher stays registered while no request is displayed. Result/Activity resume sends CheckLocalNetworkPermission; heartbeat invokes the same grant recheck directly. A stale result can still request a harmless fresh check for the new selection, but it cannot grant old request launch admission. Denial preserves filters/rows and excludes only addresses requiring grant from HTTP. Grant refresh updates the plan without automatic capture Start.

## Native state projection and current UI

[MjpegUiController](../../src/main/kotlin/io/screenstream/mjpeg/ui/MjpegUiController.kt) synchronously maps controller inputs to its StateFlow and forwards commands to the original loop. It has no mirror activity, input collector or resource ownership. Collection does not start HTTP/capture. Window size comes from the host; the current content does not yet use it for an adaptive layout.

UI state includes ready Action, handled suspension problem, selected address rows, current permission request and edit policy. [MjpegServerUi](../../src/main/kotlin/io/screenstream/mjpeg/ui/MjpegServerUi.kt) maps rows using discovery ID plus applied port and translates listener failure. Missing required permission yields PermissionDenied with no HTTP server UUID, Retry or Copy URL. Portable applied links require Listening, permission and applied access; IPv6 scope is removed and link/site-local IPv6 URLs are excluded. HTTP Info and applied access token remain separate reads, not a global transaction.

[MjpegContent](../../src/main/kotlin/io/screenstream/mjpeg/ui/MjpegContent.kt) renders Start/Stop, filters, server status/Retry/Copy URL and LAN permission. Copy's coroutine rereads current State and matches discovery ID, HTTP UUID and Listening before putting a sensitive URL in clipboard. It does not retain a remembered credential across server replacement. Filter edits preserve a nonempty choice in each group and transform the latest stored value atomically, so concurrent changes do not empty it. Detailed filter/save rules belong to [Settings](../02-configuration/settings.md).

Shutdown and event-loop finally synchronously clear UI to Busy, empty rows and LiveOnly; scope cancellation does not cancel that clear. Full settings/preview/PIN/viewer/stats UI, shown suspension reasons and capture Retry remain unfinished.
