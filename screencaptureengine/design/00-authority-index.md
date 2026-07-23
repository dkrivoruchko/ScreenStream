# Screen Capture Engine — Authority Index

## Activation and precedence

Exactly the nine files listed below form the activated normative authority package for Screen Capture Engine.
Each normative fact has exactly one topic owner. A link or cross-document summary is
nonnormative: it neither imports nor narrows the linked rule, and the owning document wins every conflict.
Future directions and other roadmap material are informational only and cannot override a current owner.

Removed mechanisms have no authority.

## Topic router

Start with the fact being decided, not the component that happens to consume it:

1. Is it this index's activation, precedence, baseline, or navigation rule? Stay in this file.
2. Is it caller-visible API, behavior, values, outcomes, observations, limits, or support scope? Go to
   [`01-product-contract.md`](01-product-contract.md).
3. Is it Session authority, execution roles, synchronization, reconciliation, identities, cardinality,
   failure containment, cleanup, privacy, or cohesion? Go to
   [`02-internal-architecture.md`](02-internal-architecture.md).
4. Is it a metrics source, API-band metrics read, platform geometry fact, source observation, or metrics
   close/residue rule? Go to [`03-metrics-platform.md`](03-metrics-platform.md).
5. Is it `MediaProjection`, `VirtualDisplay`, Capture-local Target ownership, EGL/GLES, transform, color,
   readback, or capture-resource release? Go to [`04-capture-rendering.md`](04-capture-rendering.md).
6. Is it Framework Bitmap/JPEG behavior, managed encoded transactions, immutable payload storage,
   `FrameStore`, or caller-copy mechanics? Go to
   [`05-framework-encoding-storage.md`](05-framework-encoding-storage.md).
7. Is it a native carrier/runtime, loader, JNI or wire surface, native result/adoption protocol, NDK/ABI,
   binary, or native package fact? Go to [`06-native-jni-package.md`](06-native-jni-package.md).
8. Is it pacing, repeat/cache use, callback delivery, subscribe/unsubscribe mechanics, or State/Stats/
   diagnostic publication? Go to [`07-delivery-observation.md`](07-delivery-observation.md).
9. Is it verification method, an exact task or source set, executable binding, acceptance/race coverage,
   an oracle, or package evidence? Go to [`08-verification.md`](08-verification.md).

Private files, helpers, types, and equivalent decomposition remain implementation choices unless an owner
explicitly marks a public, JNI/ABI, build, source-set, platform, or safety anchor. That freedom does not make
semantic ownership, ordering, cardinality, lifecycle, copy, or safety requirements optional.

## Module baseline and navigation anchors

All paths are repository-relative.

| Binding | Canonical value or anchor |
| --- | --- |
| Module | `:screencaptureengine`; `screencaptureengine/` |
| Android SDK | `minSdk` 24; `compileSdk` 37; `targetSdk` 37 |
| JVM | toolchain and bytecode 17 |
| Namespace and public package | `io.screenstream.engine` |
| Kotlin API mode | explicit API |
| Published artifact | public release AAR |
| Runtime concurrency dependency | `kotlinx-coroutines` `1.11.0` |
| Module build entry | `screencaptureengine/build.gradle.kts` |
| Public Kotlin source | `screencaptureengine/src/main/kotlin/io/screenstream/engine/` |
| Private Kotlin source | `screencaptureengine/src/main/kotlin/io/screenstream/engine/internal/` |
| Native source | `screencaptureengine/src/main/cpp/` |

Production authority follows the topic router; a path does not create a second owner. Exact verification tasks,
source sets, and package checks belong to [Verification](08-verification.md). Exact JNI names and descriptors,
DSO/build composition, ABIs, exports, alignment, and native packaging belong to
[Native/JNI/Package](06-native-jni-package.md).
