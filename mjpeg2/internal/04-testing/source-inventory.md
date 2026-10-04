[Running tests](testing.md) · [Scenarios](verification-contracts.md)

# MJPEG source contributors

Several sources contribute to the same product behavior. These links locate relevant scenarios; they do not prescribe a suite per file. Module metadata and wiring can be checked by inspection.

| Source | Relevant scenarios |
| --- | --- |
| [http/HttpAccess.kt](../../src/main/kotlin/io/screenstream/mjpeg/http/HttpAccess.kt) | [Access](scenarios/access.md), [Web protocol](scenarios/web-protocol.md) |
| [http/HttpDelivery.kt](../../src/main/kotlin/io/screenstream/mjpeg/http/HttpDelivery.kt) | [HTTP runtime](scenarios/http-runtime.md) |
| [http/HttpListener.kt](../../src/main/kotlin/io/screenstream/mjpeg/http/HttpListener.kt) | [HTTP runtime](scenarios/http-runtime.md), [Integration](scenarios/integration.md) |
| [http/HttpRequests.kt](../../src/main/kotlin/io/screenstream/mjpeg/http/HttpRequests.kt) | [Access](scenarios/access.md), [Media](scenarios/media.md), [HTTP runtime](scenarios/http-runtime.md) |
| [http/KtorHttpDelivery.kt](../../src/main/kotlin/io/screenstream/mjpeg/http/KtorHttpDelivery.kt) | [Network](scenarios/network.md), [HTTP runtime](scenarios/http-runtime.md), [Access](scenarios/access.md), [Media](scenarios/media.md), [Web protocol](scenarios/web-protocol.md), [Integration](scenarios/integration.md) |
| [http/media/JpegResponse.kt](../../src/main/kotlin/io/screenstream/mjpeg/http/media/JpegResponse.kt) | [Media](scenarios/media.md), [HTTP runtime](scenarios/http-runtime.md) |
| [http/media/JpegStore.kt](../../src/main/kotlin/io/screenstream/mjpeg/http/media/JpegStore.kt) | [Access](scenarios/access.md), [Media](scenarios/media.md), [Integration](scenarios/integration.md) |
| [http/web/WebRoutes.kt](../../src/main/kotlin/io/screenstream/mjpeg/http/web/WebRoutes.kt) | [Web protocol](scenarios/web-protocol.md), [Integration](scenarios/integration.md) |
| [http/web/WebState.kt](../../src/main/kotlin/io/screenstream/mjpeg/http/web/WebState.kt) | [Web protocol](scenarios/web-protocol.md) |
| [MjpegCaptureEngineAdapter.kt](../../src/main/kotlin/io/screenstream/mjpeg/MjpegCaptureEngineAdapter.kt) | [Capture](scenarios/capture.md) |
| [MjpegCaptureSession.kt](../../src/main/kotlin/io/screenstream/mjpeg/MjpegCaptureSession.kt) | [Capture](scenarios/capture.md), [Integration](scenarios/integration.md) |
| [MjpegKoinModule.kt](../../src/main/kotlin/io/screenstream/mjpeg/MjpegKoinModule.kt) | [Integration](scenarios/integration.md) |
| [MjpegStreamingModule.kt](../../src/main/kotlin/io/screenstream/mjpeg/MjpegStreamingModule.kt) | [Integration](scenarios/integration.md) |
| [MjpegStreamingModuleController.kt](../../src/main/kotlin/io/screenstream/mjpeg/MjpegStreamingModuleController.kt) | [Controller](scenarios/controller.md), [Network](scenarios/network.md), [Integration](scenarios/integration.md) |
| [MjpegStreamingModuleService.kt](../../src/main/kotlin/io/screenstream/mjpeg/MjpegStreamingModuleService.kt) | [Capture](scenarios/capture.md), [Integration](scenarios/integration.md) |
| [networkaddress/AndroidNetworkAddressMonitor.kt](../../src/main/kotlin/io/screenstream/mjpeg/networkaddress/AndroidNetworkAddressMonitor.kt) | [Network](scenarios/network.md) |
| [networkaddress/AndroidNetworkAddressSource.kt](../../src/main/kotlin/io/screenstream/mjpeg/networkaddress/AndroidNetworkAddressSource.kt) | [Network](scenarios/network.md) |
| [networkaddress/NativeInterfaceAccess.kt](../../src/main/kotlin/io/screenstream/mjpeg/networkaddress/NativeInterfaceAccess.kt) | [Network](scenarios/network.md) |
| [networkaddress/NetworkAddress.kt](../../src/main/kotlin/io/screenstream/mjpeg/networkaddress/NetworkAddress.kt) | [Network](scenarios/network.md) |
| [networkaddress/NetworkAddressMonitor.kt](../../src/main/kotlin/io/screenstream/mjpeg/networkaddress/NetworkAddressMonitor.kt) | [Network](scenarios/network.md) |
| [settings/MjpegSettings.kt](../../src/main/kotlin/io/screenstream/mjpeg/settings/MjpegSettings.kt) | [Settings](scenarios/settings.md) |
| [settings/MjpegSettingsMigrations.kt](../../src/main/kotlin/io/screenstream/mjpeg/settings/MjpegSettingsMigrations.kt) | [Settings](scenarios/settings.md#mjpeg-set-12) |
| [settings/MjpegSettingsModels.kt](../../src/main/kotlin/io/screenstream/mjpeg/settings/MjpegSettingsModels.kt) | [Settings](scenarios/settings.md) |

## Excluded UI sources

The entire UI package is outside this catalogue:

- [ui/LocalNetworkPermission.kt](../../src/main/kotlin/io/screenstream/mjpeg/ui/LocalNetworkPermission.kt)
- [ui/MjpegContent.kt](../../src/main/kotlin/io/screenstream/mjpeg/ui/MjpegContent.kt)
- [ui/MjpegNetworkFilters.kt](../../src/main/kotlin/io/screenstream/mjpeg/ui/MjpegNetworkFilters.kt)
- [ui/MjpegServerUi.kt](../../src/main/kotlin/io/screenstream/mjpeg/ui/MjpegServerUi.kt)
- [ui/MjpegUiController.kt](../../src/main/kotlin/io/screenstream/mjpeg/ui/MjpegUiController.kt)
- [ui/UiController.kt](../../src/main/kotlin/io/screenstream/mjpeg/ui/UiController.kt)
