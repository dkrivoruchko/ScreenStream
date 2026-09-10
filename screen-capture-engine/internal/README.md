# ScreenStream Capture Engine internals

The public [Usage](../docs/usage.md) and [Architecture](../docs/architecture.md) are integral parts of the developer documentation. Usage owns caller behavior and integration; Architecture supplies the shared component, ownership, execution, image-flow, and lifecycle model. Read the relevant public section before changing its implementation. The [module README](../README.md) introduces the SDK and its capabilities.

These pages extend that foundation with exact algorithms, coordination rules, physical ownership and failure boundaries, native ABI details, and verification methods. They support continued SDK development without maintaining a second copy of the public contract.

## Find the rule for a change

The pages explain the rules and link to their implementation. [SessionCoordinator](../src/main/kotlin/io/screenstream/capture/internal/session/SessionCoordinator.kt) is the entry point for a change crossing owners. For the image path, start with [SessionPlanResolution](../src/main/kotlin/io/screenstream/capture/internal/session/topology/SessionPlanResolution.kt), [GLRenderer](../src/main/kotlin/io/screenstream/capture/internal/capture/GLRenderer.kt), and [ManagedEncodedTransaction](../src/main/kotlin/io/screenstream/capture/internal/encoding/ManagedEncodedTransaction.kt).

### Session

| Maintainer task | Read next |
| --- | --- |
| Change startup, admission, configuration convergence, recovery, or terminal decisions | [Session](01-session/session.md) |
| Change locking, exact request/result correlation, dispatch, publication, or observation | [Coordination](01-session/coordination.md) |
| Change read progression, pacing, cache compatibility, output identity, or statistics | [Production](01-session/production.md) |

### Capture

| Maintainer task | Read next |
| --- | --- |
| Change projection, Target, source arrival, EGL/GLES, or graphics retirement | [Capture](02-capture/capture.md) |
| Change source subscription, built-in display observation, or Metrics readiness | [Metrics](02-capture/metrics.md) |
| Change geometry, sizing, sampling, or color arithmetic | [Image pipeline](02-capture/image-pipeline.md) |

### Output

| Maintainer task | Read next |
| --- | --- |
| Change carrier loans, JPEG backend selection, transactions, or immutable encoded storage | [Encoding](03-output/encoding.md) |
| Change registered JNI, native segment transfer, wire status, or exports | [Native ABI](03-output/native-abi.md) |
| Change borrowed-frame access, handoff, consumer replacement, or unregister completion | [Delivery](03-output/delivery.md) |

### Testing

| Maintainer task | Read next |
| --- | --- |
| Choose local, host-native, device, or packaged-artifact checks and follow marker rules | [Testing](04-testing/testing.md) |
| Find a stable verification obligation and its source contributors | [Verification contracts](04-testing/verification-contracts.md) |
| Change raw-pixel, color, or decoded-JPEG fixtures and tolerances | [Image test oracles](04-testing/image-test-oracles.md) |

## Keep documentation aligned

Each exact algorithm, state table, or outcome matrix has one owning internal page. Other pages explain the dependency they need and link to that owner. Coordination owns how decisions are correlated and published; Session owns when lifecycle decisions and outcomes are selected; Production owns frame and measurement policy; physical-owner pages own the evidence and resources they can settle.

Kotlin/C++ source and build files own current declarations, descriptors, symbols, and packaging. Tests provide evidence rather than redefining behavior. Official Android, NDK, JNI, and coroutine documentation owns the external platform rules. Resolve contradictions against those sources, preserving the distinction between a promised contract, source inspection, and executed evidence.

When an approved implementation change affects caller behavior or the shared design model, update the owning public guide as well as its internal extension. For verification, follow the [contract-test rules](04-testing/testing.md#contract-test-rules) and use the smallest environment that can observe the changed contract.
