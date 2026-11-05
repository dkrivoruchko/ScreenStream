# MJPEG verification scenarios

[Internals](../README.md) · [Testing](01-testing.md)

Find the relevant history below, then read its linked behavior owner. [Testing](01-testing.md) defines the current suite, environments and evidence limits; this catalogue does not record passed checks.

<a id="reading-a-scenario"></a>

## Keeping IDs stable

Each scenario has an ID/lowercase anchor, stimuli and observable outcomes, plus an evidence boundary where needed. Keep IDs and anchors when moving pages or tests; never reuse an ID. Add new behavior at the next free number in its area. Pages may order by topic, and fixtures may be shared, while each history keeps its distinct assertions and one owning component.

<a id="existing-test-sources"></a>

## Scenario areas

All IDs have the `MJPEG-` prefix. The test links are representative entry points; search `// Cases:` in the [source tree](../../src/test/kotlin/io/screenstream/mjpeg) for other contributors.

| Page / IDs | Behavior owners | Test entry points |
| --- | --- | --- |
| [Settings](scenarios/01-settings.md): SET-01–11 | [Settings](../01-module/04-settings.md) | [Models](../../src/test/kotlin/io/screenstream/mjpeg/settings/MjpegSettingsModelsTest.kt), [persistence](../../src/test/kotlin/io/screenstream/mjpeg/settings/MjpegSettingsPersistenceTest.kt), [edit policy](../../src/test/kotlin/io/screenstream/mjpeg/settings/MjpegEditPolicyTest.kt) |
| [Controller](scenarios/02-controller.md): CTL-01–17 | [Coordination](../01-module/03-coordination.md), [capture lifecycle](../01-module/02-module-lifecycle.md) | [Controller](../../src/test/kotlin/io/screenstream/mjpeg/MjpegStreamingModuleControllerTest.kt), [capture contracts](../../src/test/kotlin/io/screenstream/mjpeg/MjpegControllerCaptureContractsTest.kt), [statistics](../../src/test/kotlin/io/screenstream/mjpeg/MjpegControllerStatisticsTest.kt) |
| [Capture](scenarios/03-capture.md): CAP-01–14 | [Capture lifecycle](../01-module/02-module-lifecycle.md), [integration](../01-module/01-module-integration.md) | [Session](../../src/test/kotlin/io/screenstream/mjpeg/MjpegCaptureSessionTest.kt), [frames](../../src/test/kotlin/io/screenstream/mjpeg/MjpegCaptureSessionFramesTest.kt), [ownership](../../src/test/kotlin/io/screenstream/mjpeg/MjpegCaptureSessionOwnershipTest.kt), [Service](../../src/test/kotlin/io/screenstream/mjpeg/MjpegStreamingModuleServiceTest.kt) |
| [Network](scenarios/04-network.md): NET-01–13, LAN-01–05 | [Addresses](../01-module/05-network-addresses.md), [coordination](../01-module/03-coordination.md) | [Policy](../../src/test/kotlin/io/screenstream/mjpeg/networkaddress/NetworkAddressPolicyTest.kt), [source](../../src/test/kotlin/io/screenstream/mjpeg/networkaddress/AndroidNetworkAddressSourceTest.kt), [monitor](../../src/test/kotlin/io/screenstream/mjpeg/networkaddress/AndroidNetworkAddressMonitorTest.kt), [cadence](../../src/test/kotlin/io/screenstream/mjpeg/networkaddress/AndroidNetworkAddressCadenceTest.kt) |
| [HTTP runtime](scenarios/05-http-runtime.md): SRV-01–10, OBS-01–09 | [HTTP delivery](../02-delivery/01-http-delivery.md) | [Lifecycle](../../src/test/kotlin/io/screenstream/mjpeg/http/HttpDeliveryLifecycleTest.kt), [socket owners](../../src/test/kotlin/io/screenstream/mjpeg/http/HttpDeliverySocketOwnerTest.kt), [request history](../../src/test/kotlin/io/screenstream/mjpeg/http/HttpRequestHistoryTest.kt), [CIO](../../src/test/kotlin/io/screenstream/mjpeg/http/HttpDeliveryCioIntegrationTest.kt) |
| [Access](scenarios/06-access.md): ACC-01–10 | [HTTP delivery](../02-delivery/01-http-delivery.md) | [Access](../../src/test/kotlin/io/screenstream/mjpeg/http/HttpAccessTest.kt), [credential rotation](../../src/test/kotlin/io/screenstream/mjpeg/http/HttpCredentialRotationTest.kt) |
| [Media](scenarios/07-media.md): IMG-01–15 | [JPEG delivery](../02-delivery/02-jpeg-delivery.md) | [Store](../../src/test/kotlin/io/screenstream/mjpeg/http/media/JpegStoreTest.kt), [response protocol](../../src/test/kotlin/io/screenstream/mjpeg/http/media/JpegResponseProtocolTest.kt) |
| [Web protocol](scenarios/08-web-protocol.md): WEB-01–17 | [Web client](../02-delivery/03-web-client.md), [HTTP server](../../docs/server.md) | [Routes](../../src/test/kotlin/io/screenstream/mjpeg/http/web/WebRoutesProtocolTest.kt), [state JSON](../../src/test/kotlin/io/screenstream/mjpeg/http/web/WebStateContractTest.kt), [WebSocket transport](../../src/test/kotlin/io/screenstream/mjpeg/http/web/WebSocketTransportTest.kt), [CIO headers](../../src/test/kotlin/io/screenstream/mjpeg/http/web/WebRoutesDuplicateHeaderCioTest.kt) |
| [Integration](scenarios/09-integration.md): CFG-01–04 | [Module integration](../01-module/01-module-integration.md) | [Generated graph](../../src/test/kotlin/io/screenstream/mjpeg/MjpegGeneratedGraphTest.kt), [controller/CIO assembly](../../src/test/kotlin/io/screenstream/mjpeg/MjpegControllerCioAssemblyTest.kt) |

The generated-graph test uses a supplied host; controller/CIO assembly controls discovery, consent and the engine. Neither establishes the real app graph or projection. CFG-03/04 separate packaged inspection and device qualification.

## Cross-layer histories

The decision owner holds the history; related scenarios check their own boundary:

| Behavior | Decided in | Related parts |
| --- | --- | --- |
| Whether Start is accepted | [CTL-02](scenarios/02-controller.md#mjpeg-ctl-02) | Permission policy in [LAN-02](scenarios/04-network.md#mjpeg-lan-02) and [LAN-03](scenarios/04-network.md#mjpeg-lan-03). Listener incarnations and bind failures in [SRV-03](scenarios/05-http-runtime.md#mjpeg-srv-03) and [SRV-04](scenarios/05-http-runtime.md#mjpeg-srv-04). |
| Stopping after address loss | [CTL-09](scenarios/02-controller.md#mjpeg-ctl-09), [CTL-10](scenarios/02-controller.md#mjpeg-ctl-10) | Permission denial is not loss: [LAN-04](scenarios/04-network.md#mjpeg-lan-04) |
| Preparing a PIN | [CTL-06](scenarios/02-controller.md#mjpeg-ctl-06) (when the mode opens) | [SET-06](scenarios/01-settings.md#mjpeg-set-06) (when protection is enabled) |
| Stopping when the screen turns off | [CTL-14](scenarios/02-controller.md#mjpeg-ctl-14) | Saving the preference: [SET-10](scenarios/01-settings.md#mjpeg-set-10) |
| Publishing captured JPEGs | [CAP-08](scenarios/03-capture.md#mjpeg-cap-08) | The store applying status and generation, stale offers and copy failures: [IMG-02](scenarios/07-media.md#mjpeg-img-02), [IMG-04](scenarios/07-media.md#mjpeg-img-04), [IMG-05](scenarios/07-media.md#mjpeg-img-05) |
| Capture cleanup and its consequences | [CAP-09](scenarios/03-capture.md#mjpeg-cap-09), [CAP-10](scenarios/03-capture.md#mjpeg-cap-10) (attempt) | Module consequences: [CTL-05](scenarios/02-controller.md#mjpeg-ctl-05), [CTL-13](scenarios/02-controller.md#mjpeg-ctl-13) |
| Consumer presence in manager reports | [CTL-04](scenarios/02-controller.md#mjpeg-ctl-04) | Presence itself: [OBS-01](scenarios/05-http-runtime.md#mjpeg-obs-01) |
| Rates, frame rate and history | [CTL-15](scenarios/02-controller.md#mjpeg-ctl-15), [CTL-16](scenarios/02-controller.md#mjpeg-ctl-16) | HTTP sampling: [OBS-08](scenarios/05-http-runtime.md#mjpeg-obs-08) |
| Credentials across Stop, edits and reopening | [ACC-03](scenarios/06-access.md#mjpeg-acc-03), [OBS-06](scenarios/05-http-runtime.md#mjpeg-obs-06) | On a device: [CFG-04](scenarios/09-integration.md#mjpeg-cfg-04) |

## Outside this catalogue

1. Viewer build, Node fixtures/owner tests and browser smoke: [web client tools](../../tools/web-client/README.md).
2. Core manager/consent: [Core scenarios](../../../core/internal/testing/02-scenarios.md). Core's Service, permission/consent hosts, notifications and the Capture Engine retain their own contracts and verification.
3. MJPEG analytics and Compose UI histories. The [local UI suite](01-testing.md#what-the-local-suite-covers) is outside this scenario catalogue; SET-10 remains persistence-only. MJPEG owns its composables; Core owns the shared consent host, and the app owns its actual Activity-root placement and wiring.
4. Device qualification records: keep them with the change or release that produced them. The [library pipeline test](01-testing.md#choosing-an-environment) has a narrower boundary than packaged-app qualification.
