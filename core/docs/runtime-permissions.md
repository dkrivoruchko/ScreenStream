# Runtime Permissions

[Core](../README.md) · [Streaming Module Contract and Manager](module-contract-and-manager.md)

Runtime Permissions coordinates permission checks and request UI for streaming modules. The app's Activity root hosts the request UI. The module decides which permissions its operation needs and what to do after a request.

This component covers ordinary Android permissions. [Screen Capture Access](screen-capture.md) coordinates screen-sharing consent. Each approval establishes authority for its respective operation.

## Participants and relationships

- **Permission definitions.** `RuntimePermission` identifies an Android permission and the conditions under which it is available. Module UI, Android Services, and process work share these definitions.

- **Activity permission host.** The app creates a `RuntimePermissions` implementation at the Activity root and supplies it through `LocalRuntimePermissions`. It owns explanation UI, Android launchers, request state, and rationale history. Its lifetime follows the Activity root, separately from module instances.

- **Module UI.** The screen consumes that capability to offer requests and display permission UI state. The Activity-root host retains request ownership when the originating module screen leaves.

- **Android Service and process work.** These callers use Context-based checks for current authorization. The request capability stays with the Activity host. The Controller owns readiness and protected work for its [module instance](module-contract-and-manager.md#identities-and-lifetimes).

## How they collaborate

1. The module checks current permission before protected work.
2. When permission is missing, a user action in module UI can request an explanation from the Activity host.
3. If the host can accept the request, it presents the explanation. User confirmation can proceed to Android's permission dialog or settings.
4. When the owned request finishes, the host checks permission again and updates its UI state.
5. The module evaluates current authority and whether the original action is still relevant before acting.

Admission to request UI, Android approval, and readiness to perform the action are separate outcomes. The capability owns permission UI state. The module owns its intended action and decides whether to proceed after approval.

## Permission meanings

Permission definitions map directly to the applicable Android grant. Availability depends on the permission and the device/app configuration. The combined audio explanation and the microphone/device-audio explanations share one underlying Android permission, grant, and permission UI state.

Notification permission checks include app-wide notification availability. Channel-aware delivery and settings navigation belong to [Streaming Module Notifications](notifications.md#delivery-and-permissions).

## Current authorization versus UI state

Current authorization answers whether the relevant permission is available now. A permission check is a current observation; the module remains responsible for an operation that loses authority after the check and for the other prerequisites of its work.

`PermissionState` is the host's last-known UI hint. It can indicate availability, a possible request, a suggested settings route, or a request already in progress. Screens use those hints to explain availability and offer the next permission action. The host updates hints around its permission requests.

Unrelated Android settings changes can leave a previous hint visible, and a fresh grant can exist while an earlier UI request is still pending. Settings suggestions express a route through permission UI inferred from notification availability and rationale history. Permission checks establish current authority for protected work.

## Request lifetime and outcomes

Activity eligibility and a free permission UI slot govern UI admission. A rejected request requires a fresh request later. An admitted explanation belongs to the Activity root and can outlive the module screen that requested it. The request retains its UI slot through Android dialogs and settings visits across the host's supported lifecycle changes.

Confirmation can open Android UI or settings. Dismissing the explanation settles the request at that stage. Completion, cancellation, and launch failure end the app's request; the module checks current permission afterward. Returning from settings also triggers a permission check.

The app owns explanation presentation, rationale history, and when permission UI is offered. The module uses current authority, request outcomes, and module admission to decide whether its intended action remains relevant and can proceed.
