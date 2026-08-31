# ScreenStream Capture Engine internals

This directory is the maintainer-facing index for the internal contracts that make the public engine behavior repeatable. It explains boundaries and invariants that are easy to lose when changing the implementation; it is not another public API reference. Caller-visible behavior is in the public [module README](../README.md), [usage guide](../docs/usage.md), and [architecture guide](../docs/architecture.md).

## How to navigate

Start with the [architecture overview](architecture/overview.md), then use the component document for the physical or semantic owner of a change and the focused contract pages for cross-component rules:

| Area | Maintainer entry points |
| --- | --- |
| Capture, rendering, and source arrival | [capture component](components/capture.md), [image pipeline](contracts/image-pipeline.md) |
| Session coordination and publication | [runtime architecture](architecture/runtime.md), [concurrency and liveness](contracts/concurrency-and-liveness.md), [failures and terminal semantics](contracts/failures-and-terminal-semantics.md) |
| Encoding and immutable storage | [encoding component](components/encoding.md), [frame ownership and delivery](contracts/frame-ownership-and-delivery.md) |
| Metrics, delivery, and observation | [metrics component](components/metrics.md), [delivery and observation component](components/delivery-observation.md) |
| Native JPEG boundary | [encoding component](components/encoding.md), [native ABI](contracts/native-abi.md) |
| Testing and verification | [testing and verification](testing.md) |

## Documentation roles

Public README, usage, and architecture docs own caller-visible behavior. These internal pages own maintained boundaries and invariants. Kotlin/C++ source and the build mechanically own declarations, descriptors, symbols, and packaging behavior; the testing pages define required evidence and reusable oracles; tests and retained results provide the executable evidence. Official Android, NDK, and JNI documentation owns platform facts. Contradictions between these sources require explicit resolution.

Each maintained rule has one normative documentation owner. Other pages may summarize the dependency they need and link to that owner, but must not copy its algorithm, state table, or outcome matrix.
