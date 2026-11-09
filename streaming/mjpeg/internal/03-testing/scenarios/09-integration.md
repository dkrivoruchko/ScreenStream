# Module integration tests

[Scenario index](../02-verification-contracts.md) · [Testing](../01-testing.md)

The local suites check MJPEG declarations and a supplied module graph. Existing instrumentation exercises device integration in the library's own test APK. [Architecture](../../../docs/architecture.md#hosting-and-coordination) and [Module integration](../../01-module/01-module-integration.md) define the module behavior under test.

<a id="mjpeg-cfg-01"></a>

## MJPEG-CFG-01: Inert metadata and declared Service

**History.** Inspect `MjpegStreamingModule`, its string resources and the module manifest. Then list the descriptor the way the app's module catalog does.

**Expected.**

- The descriptor identifies the selectable mode:
  - ID `MJPEG` and priority 40
  - name, description and details resources
  - `MjpegStreamingModuleService` as its Service class
  - foreground notification slot 400
- The descriptor is a process-wide singleton bound as `StreamingModule`. Listing or reading it creates no controller, HTTP delivery, discovery or capture work.
- The manifest declares the Service as not exported. It has the foreground types `specialUse|mediaProjection` and a special-use subtype that describes the local HTTP server.
- The manifest requests these permissions: `INTERNET`, `ACCESS_LOCAL_NETWORK`, `ACCESS_NETWORK_STATE`, `WAKE_LOCK`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` and `FOREGROUND_SERVICE_MEDIA_PROJECTION`.

**Evidence boundary.** Source declarations and the local tests do not establish the shipped app's merged manifest or APK.

<a id="mjpeg-cfg-02"></a>

## MJPEG-CFG-02: DI and exact ManagerHandle installation

**History.** Inspect the Koin annotations. Then, in a graph the test supplies:

1. Let the real Service create the real controller with its `ManagerHandle`.
2. Resolve a second controller for another instance.
3. Route commands, reports and cleanup through both.

**Expected.**

- **Bindings.**
  - `MjpegKoinModule` is a component-scanned configuration.
  - The descriptor is a singleton.
  - The controller is a factory whose `ManagerHandle` and Service are injected parameters.
  - `MjpegSettings` is a singleton.
  - `HttpDelivery` and `NetworkAddressMonitor` are created per resolution.
- **Per-instance owners.** Each module instance therefore gets its own HTTP delivery and discovery, and all instances share one saved-settings store.
- **Exact handle and Service.** The Service passes its exact handle and itself, and the controller's `instanceId` equals the handle's.
- **Inert construction.** Construction stays inert until `startModule()` ([CTL-01](02-controller.md#mjpeg-ctl-01)).
- **Retained targets.** Commands, status reports, failure reports and cleanup all target that original handle and Service, including after the handle stops being current.

**Evidence boundary.** A supplied graph establishes the fixture wiring, not resolution of the shipped application graph.

<a id="mjpeg-cfg-03"></a>

## MJPEG-CFG-03: Build and host boundaries

The [module build](../../../build.gradle.kts) declares production dependencies, test dependencies and the library test APK target SDK. The [generated-graph test](../../../src/test/kotlin/io/screenstream/mjpeg/MjpegKoinGraphTest.kt) resolves module-owned settings, HTTP delivery, discovery and controllers with a supplied context, analytics and capture-access collaborators.

**Evidence boundary.** Local graph resolution does not establish the shipped app's merged manifest, runtime classpath, Activity-root permission/consent hosts or notification presentation. These are not asserted by this module's JVM suite.

<a id="mjpeg-cfg-04"></a>

## MJPEG-CFG-04: Device integration sources

The existing [instrumentation suite](../01-testing.md#running-device-tests) uses the library's own test APK:

1. Capture pipeline: changing decoded JPEG/multipart targets, live and stopped statistics, restart and Exit resource receipts.
2. Lifecycle: declined consent, late Android approval after Exit and system projection stop; an API 35 assumption skip of the chip history is not system-stop evidence.
3. Reconfiguration: invalid-crop suspension, recovery on the same attempt and actual display rotation with Activity recreation.
4. LAN permission: a selected native endpoint, denied/granted external-peer placeholder receipts and continuing loopback traffic.

**Evidence boundary.** These sources combine production module owners with test-owned UI, notification, analytics and permission-request collaborators. They do not qualify the packaged app, full protected-viewer reopening, physical LAN/kernel enforcement or the complete image-transform/backend matrix. A source file or local JVM pass is not device execution evidence; retain the actual build, runtime, grants, results and reached artifacts with each run.
