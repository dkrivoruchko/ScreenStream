[Scenarios](verification-contracts.md) · [Source inventory](source-inventory.md)

# Testing MJPEG

Test maintained product behavior: saved compatibility, viewing authority, capture admission, selected images, immutable acquired bytes, listener continuity, network recovery, payload accounting and completed cleanup. Related scenarios can share fixtures and histories. Assert observable results with independent expectations; generated getters/hash codes, private fields and incidental call counts are not product behavior.

## Scope

The subjects are MJPEG's non-UI Kotlin sources. [The inventory](source-inventory.md) maps contributing files to behavior pages. UI/Compose, clipboard, the native permission-button UI and the shared app permission host/launcher, browser JavaScript, shared streaming-core/capture-engine internals and patched Ktor network internals are outside this scope. Boundary fixtures can supply their inputs without establishing their internal coverage.

## Environments

Use the lowest environment that faithfully exercises the behavior. Test size and environment are separate choices.

| Layer | Runs | Establishes |
| --- | --- | --- |
| L0 — pure unit | Host JVM, no Android execution | Callable values/policies, access decisions, store readers and independent saved/wire fixtures. |
| L1 — Robolectric | Host JVM with simulated Android | Preferences, assets, Service adapters and qualified combined fixtures. In-process Ktor routes use no socket; configured CIO loopback exercises host sockets. |
| L2 — Android instrumentation | Physical device or an emulator | Actual foreground/projection, native interfaces, permission enforcement and Android transport on the tested platform. |

L0/L1 sources live in `src/test/kotlin`; L2 belongs in `src/androidTest/kotlin`. Host success cannot establish Android/OEM behavior. API-37 local-network checks need the artifact's target SDK, actual grant state and an external LAN client; a local bind alone does not establish viewer reachability.

## Supported Android versions

The current [root build](../../../build.gradle.kts) sets min SDK 24 and compile/target SDK 37. Every scenario applies across API 24–37 unless its platform prerequisite narrows the range. Settings, authorization, saved/wire values, image ownership and HTTP lifetime contracts have no MJPEG SDK branch; their independent unit histories need not run once per SDK. Representative Android fixtures establish their stated boundary only, not execution on every supported version.

| Platform boundary | Distinct supported behavior |
| --- | --- |
| [Network callbacks](scenarios/network.md#mjpeg-net-09) | API 24–25 use fallback reads; 26–29 use registered callback metadata; 30–35 also clear request capabilities through the public builder; 36–37 additionally observe public tethering events. |
| [LAN admission](scenarios/network.md#mjpeg-lan-01) | Runtime 24–36 bypasses the grant check; runtime 37 requires it only when target SDK is also at least 37. |
| [Service adapter](scenarios/capture.md#mjpeg-cap-13) | Compat promotion uses the untyped call on 24–28, typed call on 29–33 and current type rules on 34–37. Notification fixtures use the compat builder across the supported range. |
| [Real capture and networking](scenarios/integration.md#mjpeg-cfg-04) | Platform permissions, projection, downstream engine SDK branches and Android transport require qualified L2 evidence; host fixtures do not enforce these system rules. |

Use focused SDK configurations for these transitions, while retaining invariant histories at a representative SDK. Android's [Robolectric guidance](https://developer.android.com/training/testing/local-tests/robolectric) and [Robolectric limitations](https://robolectric.org/best-practices/) explain simulated API coverage and Android/OpenJDK differences.

## Running local tests

[The module build](../../build.gradle.kts) owns dependencies, resource configuration and the test JDK/access flags. L0/L1 tests use JDK 21; production uses toolchain 17. The CIO independent-bind fixture requires configured host IPv4 (`127.0.0.1`) and IPv6 (`::1`) loopbacks. Run the debug suite from the repository root:

```shell
./gradlew :mjpeg2:testDebugUnitTest
```

Use `--tests 'fully.qualified.TestClass'` to select a class. Results appear in `mjpeg2/build/reports/tests/testDebugUnitTest/` and `mjpeg2/build/test-results/testDebugUnitTest/`; a successful build without test results is not test execution. L2 is not currently configured with a module instrumentation runner.

## Assertions and concrete limits

Use independent expected values and recognizable byte fixtures; parse JSON and multipart output independently of production helpers. Secret redaction and actual authorization are useful checks; random credential inequality, entropy or constant-time behavior are not promised results.

Observe stable StateFlow checkpoints, because emissions can conflate. For races, use real entry/completion barriers and check the permitted outcome. Test coroutine time controls only linked dispatchers: Android clocks, `System.nanoTime`, hard IO/Default dispatchers and socket work require their own arrangement.

Saving, observation and application are distinct. Capture Stop, reader cutoff, request cancellation and physical completion are also distinct. Assert settled ownership/cleanup and valid acquired bytes at the applicable boundary. Zero-progress registry fixtures cannot establish nonzero writer accounting or physical request settlement; payload counts do not establish peer receipt.

Controlled MJPEG Engine inputs can verify parameter mapping, startup/state transitions, borrowed output and independent media/consumer settlement. Keep real factory rejection and unstarted-session rollback separate from controlled Engine admission. Registration rejection after supplied ownership transfer does not establish Android projection revocation. Broken-frame inputs verify defensive handling without claiming that the real Engine produces those failures. Ordinary exact consumer unregister does not naturally fail; state/statistics StateFlows do not naturally throw observation exceptions.

Controlled native inputs can verify fallback/absence evidence, selection and identity, stale-scan reconciliation and scan/close settlement. They do not establish actual Android native discovery or LAN reachability. Use separate faithful barriers and transport fixtures for deadline arbitration, held bind/cleanup, fatal runtime faults, WebSocket queue/backpressure and incoming-frame enforcement. Application output faults and PIN-body read failures need their own boundary inputs. A supplied controller graph does not establish actual Koin resolution. PIN save validation and error presentation require the real Access settings group update path; controller fixtures establish effective-settings application separately.

The [scenario index](verification-contracts.md) links related test sources as entry points. A link does not establish coverage of every listed variant. Fresh test reports establish execution; retain execution results and platform/device qualifications with the change or release being verified, outside this reference.

[Android testing strategies](https://developer.android.com/training/testing/fundamentals/strategies), [coroutine test scheduling](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-test/) and [Ktor's socket-free test engine](https://ktor.io/docs/server-testing.html) explain the relevant environment limits.
