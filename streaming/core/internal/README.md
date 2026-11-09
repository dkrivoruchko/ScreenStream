# Core internals

[Core](../README.md) · [Testing](testing/01-testing.md) · [Verification scenarios](testing/02-scenarios.md)

These pages are for developers who maintain Core and verify its contracts. They don't restate those contracts: the [Core overview](../README.md) and its component guides describe what host apps and modules can rely on, and KDoc defines exact signatures, obligations and numeric limits. Read the [Streaming Module Contract and Manager](../docs/module-contract-and-manager.md) guide first; it defines **module instance**, **capture attempt**, **Controller**, **ManagerHandle** and the manager's **state**.

## Contract owners

Each row names the authoritative source for one concept. Link to it rather than copying its rules into tests, scenarios or other guides.

| Concept | Behavior owner | Exact contract (KDoc) |
| --- | --- | --- |
| Module selection, restart, Exit, command routing and manager state | [Manager commands](../docs/module-contract-and-manager.md#manager-commands), [Observable health](../docs/module-contract-and-manager.md#observable-process-and-instance-health) | [StreamingModuleManager](../src/main/kotlin/io/screenstream/core/StreamingModuleManager.kt) |
| Startup, heartbeat and shutdown deadlines and their clocks | [Closing a module instance](../docs/module-contract-and-manager.md#closing-a-module-instance) | Class KDoc of [StreamingModuleManagerImpl](../src/main/kotlin/io/screenstream/core/manager/StreamingModuleManagerImpl.kt) |
| Module identities, Controller and `ManagerHandle` obligations, status reports | [Identities and lifetimes](../docs/module-contract-and-manager.md#identities-and-lifetimes) | [StreamingModule](../src/main/kotlin/io/screenstream/core/module/StreamingModule.kt) |
| Service hosting, waiting and capture foreground, Service stop | [Foreground presentation](../docs/notifications.md#foreground-presentation) | [StreamingModuleService](../src/main/kotlin/io/screenstream/core/module/StreamingModuleService.kt) |
| Generic problem notifications owned by the manager | [Error presentation](../docs/notifications.md#error-presentation) | [ModuleErrorNotificationCoordinator](../src/main/kotlin/io/screenstream/core/manager/ModuleErrorNotificationCoordinator.kt), [StreamingModuleNotifications](../src/main/kotlin/io/screenstream/core/module/StreamingModuleNotifications.kt) |
| Notification Exit, Stop and recovery actions | [Notification actions](../docs/notifications.md#notification-actions) | [StreamingModuleActionReceiver](../src/main/kotlin/io/screenstream/core/manager/StreamingModuleActionReceiver.kt) |
| Consent requests, host selection, grants, projection acquisition and legacy reuse | [Screen Capture Access](../docs/screen-capture.md) | [ScreenCaptureAccess](../src/main/kotlin/io/screenstream/core/capture/ScreenCaptureAccess.kt) |
| Activity consent host and education | [Host and request ownership](../docs/screen-capture.md#host-and-request-ownership) | [ScreenCaptureConsentHost](../src/main/kotlin/io/screenstream/core/compose/ScreenCaptureConsentHost.kt) |
| Consent-source observations | [Consent observations](../docs/screen-capture.md#consent-observations) | [ConsentRequestObserver](../src/main/kotlin/io/screenstream/core/capture/ConsentRequestObserver.kt) |
| Saved module choice and education acceptance | [Selection and restart](../docs/module-contract-and-manager.md#selection-and-restart), [Host and request ownership](../docs/screen-capture.md#host-and-request-ownership) | [StreamingSettingsStore](../src/main/kotlin/io/screenstream/core/settings/StreamingSettingsStore.kt) |
| Runtime permission checks and the Activity permission host | [Runtime Permissions](../docs/runtime-permissions.md) | [RuntimePermissions](../src/main/kotlin/io/screenstream/core/permission/RuntimePermissions.kt), [LocalRuntimePermissions](../src/main/kotlin/io/screenstream/core/compose/LocalRuntimePermissions.kt) |
| Dependency wiring | [Host app integration](../README.md#host-app-integration) | [StreamingKoinModule](../src/main/kotlin/io/screenstream/core/StreamingKoinModule.kt) |
| Log output | [Host app integration](../README.md#host-app-integration) | [Logging](../src/main/kotlin/io/screenstream/core/Logging.kt) |

The manager implementation, the settings store and the error-notification coordinator are internal declarations. Their KDoc binds Core maintainers; modules and host apps rely only on the public guides and public KDoc.

## Verification

- [Testing](testing/01-testing.md) explains the verification scope, how to run Core's local tests, how to control Android clocks and coroutine scheduling, and what counts as evidence.
- [Verification scenarios](testing/02-scenarios.md) maps manager, consent, Service, actions, permissions, settings and Compose histories to permanent IDs.

Streaming modules verify their own use of Core.

## Keep a change reviewable

1. When observable behavior changes, update its public guide or KDoc first, then the affected scenarios.
2. Keep required behavior, test source, fresh execution and platform qualification as separate claims. Store run reports with the change or release, not in these pages.
