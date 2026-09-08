# ScreenStream Capture Engine internals

These maintainer docs define internal boundaries and invariants. Caller-visible behavior is in the public [module README](../README.md), [usage guide](../docs/usage.md), and [architecture guide](../docs/architecture.md).

## How to navigate

Start with the [architecture overview](architecture/overview.md), then follow the component owner and cross-component contracts relevant to a change:

| Area | Maintainer entry points |
| --- | --- |
| Capture, rendering, and source arrival | [capture component](components/capture.md), [image pipeline](contracts/image-pipeline.md) |
| Projection ownership, run boundaries, and publication | [runtime architecture](architecture/runtime.md), [concurrency and liveness](contracts/concurrency-and-liveness.md), [failures and terminal semantics](contracts/failures-and-terminal-semantics.md) |
| Encoding and immutable storage | [encoding component](components/encoding.md), [frame ownership and delivery](contracts/frame-ownership-and-delivery.md) |
| Metrics readiness, registration completion, and observation | [metrics component](components/metrics.md), [delivery and observation component](components/delivery-observation.md) |
| Native JPEG boundary | [encoding component](components/encoding.md), [native ABI](contracts/native-abi.md) |
| Testing and verification | [testing and verification](testing.md) |

## Documentation roles

Public README, usage, and architecture docs own caller-visible behavior. These internal pages own maintained boundaries and invariants. Kotlin/C++ source and the build mechanically own declarations, descriptors, symbols, and packaging behavior; the testing pages define required evidence and reusable oracles; tests and retained results provide the executable evidence. Official Android, NDK, and JNI documentation owns platform facts. Contradictions between these sources require explicit resolution.

Each maintained rule has one normative documentation owner. Other pages may summarize the dependency they need and link to that owner, but must not copy its algorithm, state table, or outcome matrix.
