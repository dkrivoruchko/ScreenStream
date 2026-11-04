# MJPEG verification scenarios

[Internals](../README.md) · [Testing](01-testing.md)

This catalogue lists the behavior histories that verification of MJPEG mode must cover. Each scenario continues a public or internal owner: the owner states a rule, and the scenario describes a history that would show the rule holds, including the races and failures most likely to break it. Scenarios describe required behavior. They say nothing about whether a test exists or has passed. Where a scenario has an evidence boundary, the part beyond it stays deferred until platform qualification records it. [Testing](01-testing.md) explains environments, the current state of the test sources, and the evidence rules to apply before you report a scenario as verified.

## Reading a scenario

Every scenario has:

- an **ID** such as `MJPEG-CTL-02`, with a matching lowercase anchor (`#mjpeg-ctl-02`)
- a **history**: the starting condition and stimuli, including the races or failures to inject
- **expected outcomes** that an observer outside the component can check
- where needed, an **evidence boundary**: what the history can't establish, and where that evidence comes from

Each scenario page starts with its test boundary: the real component under test, the inputs a test controls, and what those controlled inputs can't prove.

## Keeping IDs stable

- **IDs are permanent.** IDs identify behavior across implementations and test methods. Keep every ID and anchor when you reorganize pages or tests.
- **Pages group by topic.** Within a page, scenarios are ordered by topic rather than by number.
- **New behavior gets the next free number** in its area. Never reuse an ID.
- **Shared fixtures are fine.** Related scenarios can share fixtures, but each keeps its own distinct failure, race and boundary assertions.
- **Each history has one home.** When behavior crosses components, the scenario lives with the component that makes the decision, and the others link to it.

## Scenario areas

| Page | IDs | What it protects | Owning guides |
| --- | --- | --- | --- |
| [Settings](scenarios/01-settings.md) | SET-01 to 12 | Saved compatibility, transactions, legacy import and edit policy | [Settings](../01-module/04-settings.md) |
| [Controller](scenarios/02-controller.md) | CTL-01 to 17 | Module lifetime, Start and Stop admission, applying settings, address loss, automation and statistics | [Coordination](../01-module/03-coordination.md), [Capture lifecycle](../01-module/02-module-lifecycle.md) |
| [Capture](scenarios/03-capture.md) | CAP-01 to 14 | One attempt's startup, parameters, borrowed output and cleanup | [Capture lifecycle](../01-module/02-module-lifecycle.md), [Module integration](../01-module/01-module-integration.md) |
| [Network](scenarios/04-network.md) | NET-01 to 13, LAN-01 to 05 | Address selection, evidence freshness, discovery cleanup and local-network gating | [Network addresses](../01-module/05-network-addresses.md), [Coordination](../01-module/03-coordination.md) |
| [HTTP runtime](scenarios/05-http-runtime.md) | SRV-01 to 10, OBS-01 to 09 | Listener continuity and retirement, request history, totals and consumer presence | [HTTP delivery](../02-delivery/01-http-delivery.md) |
| [Access](scenarios/06-access.md) | ACC-01 to 10 | Credentials, rotation and PIN blocks per connecting IP address | [HTTP delivery](../02-delivery/01-http-delivery.md) |
| [Media](scenarios/07-media.md) | IMG-01 to 15 | Image selection, immutable acquired bytes, wire payloads and accounting | [JPEG delivery](../02-delivery/02-jpeg-delivery.md) |
| [Web protocol](scenarios/08-web-protocol.md) | WEB-01 to 17 | Host and Origin checks, CORS and read-only WebSocket state | [Web client](../02-delivery/03-web-client.md), [HTTP server](../../docs/server.md) |
| [Integration](scenarios/09-integration.md) | CFG-01 to 04 | Declarations, dependency graph and packaged-app smoke | [Module integration](../01-module/01-module-integration.md) |

Every ID carries the `MJPEG-` prefix, for example `MJPEG-SET-01`.

## Cross-layer histories

Some behavior involves several components. The scenario that owns the decision holds the full history, and the related scenarios check only their own part:

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

## Existing test sources

The table lists representative entry points for each area, not every test class; the [test source tree](../../src/test/kotlin/io/screenstream/mjpeg) holds the rest. Tests target current production behavior and name the scenarios they address in `// Cases:` comments, so search for an ID to find every test that names it. Presence of a test or a `// Cases:` comment is not coverage ([Testing](01-testing.md#current-state-of-the-test-sources)).

| Area | Representative test sources |
| --- | --- |
| Settings | [models](../../src/test/kotlin/io/screenstream/mjpeg/settings/MjpegSettingsModelsTest.kt), [persistence](../../src/test/kotlin/io/screenstream/mjpeg/settings/MjpegSettingsPersistenceTest.kt), [migration](../../src/test/kotlin/io/screenstream/mjpeg/settings/MjpegSettingsMigrationTest.kt), [device title](../../src/test/kotlin/io/screenstream/mjpeg/settings/WebPageSettingsDeviceTitleTest.kt), [edit policy](../../src/test/kotlin/io/screenstream/mjpeg/settings/MjpegEditPolicyTest.kt) |
| Controller | [controller](../../src/test/kotlin/io/screenstream/mjpeg/MjpegStreamingModuleControllerTest.kt), [capture contracts](../../src/test/kotlin/io/screenstream/mjpeg/MjpegControllerCaptureContractsTest.kt), [statistics](../../src/test/kotlin/io/screenstream/mjpeg/MjpegControllerStatisticsTest.kt), [screen-off observer](../../src/test/kotlin/io/screenstream/mjpeg/AndroidScreenOffObserverTest.kt) |
| Capture | [session](../../src/test/kotlin/io/screenstream/mjpeg/MjpegCaptureSessionTest.kt), [contracts](../../src/test/kotlin/io/screenstream/mjpeg/MjpegCaptureSessionContractsTest.kt), [states](../../src/test/kotlin/io/screenstream/mjpeg/MjpegCaptureSessionStatesTest.kt), [frames](../../src/test/kotlin/io/screenstream/mjpeg/MjpegCaptureSessionFramesTest.kt), [ownership](../../src/test/kotlin/io/screenstream/mjpeg/MjpegCaptureSessionOwnershipTest.kt), [Service](../../src/test/kotlin/io/screenstream/mjpeg/MjpegStreamingModuleServiceTest.kt) |
| Network | [policy](../../src/test/kotlin/io/screenstream/mjpeg/networkaddress/NetworkAddressPolicyTest.kt), [evidence](../../src/test/kotlin/io/screenstream/mjpeg/networkaddress/NetworkAddressEvidenceTest.kt), [source](../../src/test/kotlin/io/screenstream/mjpeg/networkaddress/AndroidNetworkAddressSourceTest.kt), [platform baseline](../../src/test/kotlin/io/screenstream/mjpeg/networkaddress/AndroidNetworkAddressPlatformBaselineTest.kt), [monitor](../../src/test/kotlin/io/screenstream/mjpeg/networkaddress/AndroidNetworkAddressMonitorTest.kt), [refresh cadence](../../src/test/kotlin/io/screenstream/mjpeg/networkaddress/AndroidNetworkAddressCadenceTest.kt) |
| HTTP runtime | [lifecycle](../../src/test/kotlin/io/screenstream/mjpeg/http/HttpDeliveryLifecycleTest.kt), [socket owners](../../src/test/kotlin/io/screenstream/mjpeg/http/HttpDeliverySocketOwnerTest.kt), [request history](../../src/test/kotlin/io/screenstream/mjpeg/http/HttpRequestHistoryTest.kt), [CIO integration](../../src/test/kotlin/io/screenstream/mjpeg/http/HttpDeliveryCioIntegrationTest.kt) |
| Access | [access](../../src/test/kotlin/io/screenstream/mjpeg/http/HttpAccessTest.kt), [credential rotation](../../src/test/kotlin/io/screenstream/mjpeg/http/HttpCredentialRotationTest.kt) |
| Media | [store](../../src/test/kotlin/io/screenstream/mjpeg/http/media/JpegStoreTest.kt), [response protocol](../../src/test/kotlin/io/screenstream/mjpeg/http/media/JpegResponseProtocolTest.kt) |
| Web protocol | [routes](../../src/test/kotlin/io/screenstream/mjpeg/http/web/WebRoutesProtocolTest.kt), [state JSON](../../src/test/kotlin/io/screenstream/mjpeg/http/web/WebStateContractTest.kt), [WebSocket transport](../../src/test/kotlin/io/screenstream/mjpeg/http/web/WebSocketTransportTest.kt), [repeated header fields over CIO](../../src/test/kotlin/io/screenstream/mjpeg/http/web/WebRoutesDuplicateHeaderCioTest.kt), [CIO integration](../../src/test/kotlin/io/screenstream/mjpeg/http/HttpDeliveryCioIntegrationTest.kt) |
| Integration | [generated graph](../../src/test/kotlin/io/screenstream/mjpeg/MjpegGeneratedGraphTest.kt), for the descriptor and the module's DI graph in a test-supplied host; [controller/CIO assembly](../../src/test/kotlin/io/screenstream/mjpeg/MjpegControllerCioAssemblyTest.kt), for the running controller and HTTP delivery with controlled discovery, consent and capture engine. Also inspect the [descriptor](../../src/main/kotlin/io/screenstream/mjpeg/MjpegStreamingModule.kt), [DI configuration](../../src/main/kotlin/io/screenstream/mjpeg/MjpegKoinModule.kt) and [manifest](../../src/main/AndroidManifest.xml), then qualify on a device. |

## Outside this catalogue

- **Viewer page.** The page build, the desktop preview harness with its Node fixture checks, and manual browser smoke belong to the [web client tools guide](../../tools/web-client/README.md). Automated viewer JavaScript tests are a separate discussion.
- **Shared components.** Core's manager and consent coordination have their own [Core scenarios](../../../core/internal/testing/02-scenarios.md). Core's Service base class, permission host and notifications, and the Capture Engine, keep their own verification against their public contracts.
- **Analytics and Compose UI.** MJPEG's analytics observations and its Compose screens are outside this catalogue.
- **Device runs.** Records of device qualification belong with the release or change that produced them.
