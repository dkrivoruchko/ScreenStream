# Runtime Permissions

[Core](../README.md) · [Streaming Module Contract and Manager](module-contract-and-manager.md)

Core defines ordinary Android permissions and their checks and provides Activity-root request UI through `RuntimePermissionHost`. Screen-sharing consent is separate and belongs to [Screen Capture Access](screen-capture.md).

## Participants and relationships

- **Permission definitions.** Each `RuntimePermission` names an Android permission and when its grant is required. Context-based checks work from UI, Services and process work.
- **Activity permission host.** `RuntimePermissionHost` provides `RuntimePermissions` through `LocalRuntimePermissions` and owns explanations, Android launchers and local request state. Its lifetime follows the Activity root.
- **Modules.** UI requests missing permissions; protected work checks current availability and decides whether an operation can proceed.

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

`isGranted` answers whether a permission is available now. Protected work needs this check, but it doesn't cover other prerequisites, and the module remains responsible if authority is lost after the check.

`PermissionState` is the host's last-known UI hint: `Granted`, `Requestable`, `SettingsSuggested` or `Requesting`. The host updates it around its own requests, not on independent Android settings changes, so a hint can be stale, and a permission can already be granted while an earlier request is still open. `SettingsSuggested` is inferred from app-wide notification settings or from Android no longer recommending a rationale after it previously did; it suggests, but doesn't prove, permanent denial.

## Request lifetime and outcomes

`tryRequest` requires a resumed Activity. It returns true without UI when the permission is already available; otherwise it admits an explanation only when the single request slot is free. Rejected requests aren't queued. An admitted request belongs to the Activity root, can outlive the requesting screen and keeps its slot through Android's dialog or a settings visit, across Activity recreation.

The host settles on a dialog result, settings return, explanation dismissal or a caught launch failure, or when the Activity resumes with an explanation open and the permission now granted. It then rechecks availability. A fresh host offers notifications when unavailable; restored state preserves the prior choice. Modules request the other permissions.

## Host setup

Mount `RuntimePermissionHost` at the Activity root around content that reads `LocalRuntimePermissions`. Supply loaded `initialRationaleHistory`: Android permission names for which a rationale was previously recommended, not grants. This seeds the host's local saveable history. The host updates that history before invoking `onRationaleObserved(permissionName, observed)`: `true` records a rationale recommendation; `false` removes the name after a runtime grant is confirmed. The [host KDoc](../src/main/kotlin/io/screenstream/core/compose/RuntimePermissionHost.kt) defines seed and callback lifetime details.
