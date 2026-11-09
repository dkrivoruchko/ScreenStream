# Runtime Permissions

[Core](../README.md) · [Streaming Module Contract and Manager](module-contract-and-manager.md)

Runtime Permissions covers the ordinary Android permissions that streaming modules use. Core defines the permissions and their checks; the app hosts request UI at the Activity root. Screen-sharing consent is separate and belongs to [Screen Capture Access](screen-capture.md).

## Participants and relationships

- **Permission definitions.** Each `RuntimePermission` entry names an Android permission and when its runtime grant is required. Its Context-based checks work from module UI, Services and process work.
- **Activity permission host.** The app implements `RuntimePermissions` at the Activity root and provides it through `LocalRuntimePermissions`. It owns explanation UI, Android launchers, rationale history and permission UI state. Its lifetime follows the Activity root, not module instances.
- **Module UI.** Reads permission UI state and asks the host for requests. The host keeps an admitted request after the requesting screen leaves.
- **Module work.** The Controller decides which permissions an operation needs, checks them before protected work and decides what to do after a request.

## How they collaborate

1. The module checks current availability before protected work.
2. When a permission is missing, a user action in module UI calls `tryRequest`.
3. If the host admits the request, it shows its explanation. Confirmation opens Android's permission dialog or settings.
4. When the request ends, the host rechecks the permission and updates its UI state.
5. The module checks availability again and decides whether the original action still applies.

Admission to request UI, Android's approval and the module's readiness to act are separate outcomes. A `true` result from `tryRequest` isn't authorization.

## Permission meanings

| Entry | Android permission | Runtime grant required |
| --- | --- | --- |
| `Notifications` | `POST_NOTIFICATIONS` | On API 33+. Availability also requires notifications to be enabled app-wide, on every API. |
| `LocalNetwork` | `ACCESS_LOCAL_NETWORK` | When both the device and the app's target SDK are API 37+. |
| `RecordAudio`, `Microphone`, `DeviceAudio` | `RECORD_AUDIO` | Always. |

The three audio entries differ only in their explanation; they share one grant, one `PermissionState` and one request. Checks cover the runtime grant, plus app-wide enablement for notifications. Channel settings, manifest declarations and other prerequisites aren't checked; channel-aware delivery belongs to [Streaming Module Notifications](notifications.md#delivery-and-permissions).

## Current authorization versus UI state

`isGranted` answers whether a permission is available now. Only this check authorizes protected work, and the module remains responsible if authority is lost after the check.

`PermissionState` is the host's last-known UI hint: `Granted`, `Requestable`, `SettingsSuggested` or `Requesting`. The host updates it around its own requests, not on independent Android settings changes, so a hint can be stale, and a permission can already be granted while an earlier request is still open. `SettingsSuggested` is inferred from app-wide notification settings or from a rationale that Android showed before and no longer recommends; it suggests, but doesn't prove, permanent denial.

## Request lifetime and outcomes

`tryRequest` admits a request only while the Activity is resumed and the host's request slot is free; for a permission that is already available it returns true without UI. Rejected requests aren't queued. An admitted request belongs to the Activity root, can outlive the requesting screen and keeps its slot through Android's dialog or a settings visit, across Activity recreation.

The host settles the request on a dialog result, a return from settings, dismissal of the explanation or a launch failure, then rechecks availability. The app decides when to explain and offer requests and keeps rationale history; the module decides whether its action proceeds.
