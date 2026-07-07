# ScreenCaptureEngine Implementation Tracker

## Source Of Truth

- Design: `screen-capture-engine-design-v32.md`.
- The design file controls implementation unless an approved deviation is recorded here.
- User-facing coordination is in Russian. Code, KDoc, comments, tests, commit text, and project documentation are in English.
- Code documentation describes the current contract directly; it must not reference design document versions, implementation phases, tracker phases, roadmap, or process.

## Operating Rules

- Main agent owns this tracker and final integration. Sub-agents do bounded work only, without forked conversation context, and each must complete at least two self-review passes.
- Main agent acts as orchestrator/facilitator for non-trivial implementation, review, audit, and validation work. Prefer delegating bounded, independent work to sub-agents and keep up to six useful active sub-agents when there is meaningful parallel work.
- Reuse an already-open sub-agent for bounded follow-up work when its context materially helps and scopes do not conflict. Fresh independent review, re-review, audit, and validation still require fresh independent sub-agents.
- Before closing a completed sub-agent, record its material result in this tracker. Do not rely on conversation context to preserve sub-agent findings across compaction.
- Close completed sub-agents after their results are recorded unless they are intentionally being reused for a bounded follow-up. Do not leave stale completed agents occupying the pool.
- Keep the tracker current before context compaction, and remove stale temporary coordination notes once they are superseded by final facts.
- Implement small coherent runtime slices that preserve design invariants. Stop for user/design-author input when implementation exposes a design gap, conflict, missing case, risky tradeoff, or repeated ambiguity.
- Before non-trivial runtime/platform work, read the relevant design sections and check current official Android/Kotlin/coroutines/Khronos documentation for behavior being relied on.
- Runtime code must not publish parallel public state/flows/counters. Public state, stats, diagnostic events, frame drops, visibility changes, geometry transitions, and output-plan transitions go through `ScreenCaptureSessionCore`.
- Prefer compact, idiomatic Kotlin. Existing internal code may be refactored when a slice exposes mismatch, fragility, duplicated ownership, or awkward seams.
- Add tests only for critical, complex, concurrency-sensitive, lifecycle-sensitive, resource-sensitive, or previously disputed behavior.
- Critical lifecycle/concurrency/resource/performance paths require independent review after implementation. Performance and memory/CPU/resource audits are part of final quality gates for critical sections, not optional polish.
- If repeated reviews find related issues in the same area, treat it as pressure on the wider design/layer and stop to reassess rather than continuing with local patches indefinitely.
- Final slice closure requires a compact tracker snapshot: current status, durable facts, validation, deferred risks, and later-slice items only. Remove obsolete sub-agent history before handoff/commit.
- Current approved JVM test setup in `screencaptureengine/build.gradle.kts`: JUnit `4.13.2`, `kotlinx-coroutines-test` `1.11.0`, and Robolectric `4.16.1`, approved as latest stable on 2026-07-06. Do not add MockK/Mockito, JUnit 5, coverage tooling, instrumentation tests, Android runner, `returnDefaultValues`, or Android resources by default.
- Validate with sequential Gradle tasks for this module; avoid concurrent Gradle/Kotlin compile runs. When final validation matters, force actual targeted test execution instead of accepting only `UP-TO-DATE` output.
- Do not touch git index/staging. The user controls staging before commits.

## Current State

- Implemented before this slice: public model/API surface and KDoc; output planning and caps; internal session core skeleton; MediaProjection callback adapter; VirtualDisplay owner; projection target owner; GL lane owner; startup transaction through `AuthoritativeStartupGeometryReady`; pre-active authoritative output planning/rebind and startup-to-runtime handoff; internal resource-preparation seams; plan-preparation-token fencing; GL-lane abandonment stabilization; lower ES2 readiness resources.
- Completed slice: internal/pre-public ES2 `RenderingPipelineReady` orchestration behind `Es2RenderingPipelinePreparer`.
- This completed slice remains internal and pre-public. It does not wire a concrete usable `ScreenCaptureEngine.startSession(...)` runtime path, initial `Running(output = Active(...))` publication, returned-session exposure, `RuntimeFrameLoopInstalled`, `InitialActivePlanCommitted`, real frame scheduling, `SurfaceTexture.updateTexImage()`, concrete runtime OES matrix acquisition, `glReadPixels(...)`, `ImageEncoder.encode(...)`, frame publication, JPEG runtime, provider fallback policy, ES3/PBO, or full async teardown integration.
- `JpegImageEncoderProvider.createEncoder(...)`, `CaptureMetricsProviders.*`, and the v32 `ScreenCaptureEngines.create(context)` concrete factory remain unimplemented/placeholder until their slices. Built-in/default factory placeholders must not ship in the design-complete public runtime.

## Slice Status

- Complete.
- Final code/resource review, final performance/memory/resource audit, focused rerun tests, full module unit tests, debug Kotlin compile, and `git diff --check` passed.

## Next Slice Planning

- Status: ready for the built-in framework JPEG provider implementation after design v32. No code was edited during planning; only this tracker was updated.
- Selected next slice remains: built-in framework JPEG provider behind `JpegImageEncoderProvider`, with no public `ScreenCaptureEngine.startSession(...)` wiring. Runtime activation is likely the following slice.
- Rationale: `ScreenCaptureParameters.defaults()` must work with the built-in JPEG provider before public startup is usable. The JPEG provider slice is bounded, leaf-like, performance-auditable, and Robolectric/JVM-testable. V32 resolves the prior semantic questions, but runtime activation remains larger because real frame-loop wiring, public commit-return boundary, projection-stop-after-commit handling, pending signal drain, session state visibility, and `setParameters(...)` behavior stay outside the JPEG slice.
- V32 resolved handoff facts:
  - `Rgba8888SrgbOpaque` is byte-exact: top-to-bottom rows, per-pixel bytes `R, G, B, A`, alpha byte must be `255`, and this is not an Android `Bitmap.Config.ARGB_8888` memory-layout alias.
  - Engine canonicalizes encoder input before `encode(...)`: `buffer.position() == 0`, `buffer.limit() >= (height - 1) * rowStrideBytes + width * 4`, and row offsets are absolute zero-based indexes. Providers should read by absolute index or their own duplicate/slice and must not depend on mutable caller-visible position/limit.
  - `EncodedImageSink` bytes are tentative for one encode attempt. If write rejection or provider `Failed(...)` happens, partial bytes are transactionally discarded by the engine/publication layer and are never publishable.
  - Built-in framework JPEG allocation/config failure maps to `AllocationFailed` for memory/resource exhaustion; non-allocation backend preparation failure maps to `EncoderUnavailable`; request/info mismatch remains `EncoderValidationFailed`.
  - Legal but tiny `maxEncodedBytes` must not be rejected by JPEG size heuristics during `createEncoder(...)`; accept structurally valid requests unless impossibility is deterministic. Actual over-cap output fails through sink rejection and the runtime encoded-size drop path.
  - The required framework JPEG baseline is correctness-first `R,G,B,A` bytes -> opaque ARGB color ints -> `Bitmap.setPixels(...)` -> `Bitmap.compress(JPEG, ...)`. Direct `copyPixelsFromBuffer`, native direct-copy, and native JPEG are later validated optimizations only.
  - `RuntimeFrameLoopInstalled` is not a fake zero-frame shell. For public release behavior it must accept real frame signals, apply frame-rate policy, schedule render/readback/encode/publication attempts, route drops/stats, drain pending geometry/density, and handle stop/projection-stop.
  - `InitialActivePlanCommitted` is the first public success boundary immediately before non-suspending return handoff. Internal prepared owners must use different names and must not expose public state/events/frames/subscriptions.
  - Public concrete construction is `ScreenCaptureEngines.create(context)`. Built-in `CaptureMetricsProviders.*` must be usable/lazy before public `startSession(...)`; buffering is bounded latest-value/conflated.
- Implementation scope for the next slice:
  - Keep ownership in `EncoderApi.kt` around `JpegImageEncoderProvider.createEncoder(...)`; update stale KDoc that says JPEG runtime is unavailable.
  - `Auto` and `FrameworkOnly` select `JpegEncoderBackend.FrameworkBitmapCompress`; `NdkAndroidBitmapCompress` remains inactive/unadvertised.
  - Return plan-scoped encoder info with `providerId = "jpeg"`, `outputFormat = EncodedImageFormats.Jpeg`, and `backendName = "FrameworkBitmapCompress"`.
  - Accept canonical top-to-bottom `Rgba8888SrgbOpaque` input: absolute zero-based rows from `buffer.position() == 0`, `limit >= requiredBytes`, byte order `R,G,B,A`, `rowStrideBytes >= width * 4`, and padded bytes ignored. Framework JPEG builds opaque ARGB from R/G/B and does not preserve alpha; engine-produced alpha is contractually `255`.
  - Use explicit conversion to opaque ARGB color ints (`0xFFRRGGBB`) and `Bitmap.setPixels(...)`; do not use `Bitmap.copyPixelsFromBuffer`, `IntBuffer` views, native JPEG, or direct-copy fast paths in this slice.
  - Honor `JpegImageEncoderProvider.quality` by passing it to `Bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)`; keep the existing public `0..100` validation.
  - For API 24 compatibility, use `Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)` plus `setHasAlpha(false)`, not API 26 `createBitmap(..., hasAlpha)`.
  - Retain plan-scoped bitmap and reusable ARGB scratch storage plus a small sink-backed `OutputStream` adapter; do not allocate a bitmap, full encoded byte array, or `ByteArrayOutputStream` per frame. A full-frame `IntArray(width * height)` is acceptable if deliberate; row/tile scratch may be used if it keeps code clear and memory lower.
  - Write encoded bytes directly to `EncodedImageSink`; any sink `false`, sink throw, stream failure, or success-after-rejection path must poison the attempt and return `ImageEncodeResult.Failed`. Success requires `Bitmap.compress(...) == true` and no adapter/sink failure.
  - Use checked `Long` arithmetic for pixel count, row bytes, required buffer span, retained sizes, and cap comparisons. Map built-in JPEG plan-scoped memory/resource allocation failure to `AllocationFailed`; map non-allocation backend preparation failure to `EncoderUnavailable`; keep request/info mismatch as `EncoderValidationFailed`.
  - Current provider-preparation plumbing maps ordinary `createEncoder(...)` throws to `EncoderUnavailable`, and `RenderingPipelinePreparationFailure.AllowedKinds` does not currently include `AllocationFailed`. If framework bitmap/scratch allocation happens during `createEncoder(...)`, add a narrow internal typed built-in allocation-failure path through provider/image-encoder preparation and allow `AllocationFailed` at rendering-preparation failure boundaries.
  - Accept structurally valid legal tiny `maxEncodedBytes` values, including the existing lower bound `1024`; do not reject by JPEG size heuristics. Let real over-cap output fail through `EncodedImageSink` rejection unless impossibility is deterministic.
  - Noncanonical direct calls with `buffer.position() != 0` are invalid input for the built-in provider and should fail cleanly without mutating caller-visible buffer state. Canonical row offsets are always absolute from index `0`.
  - Guard `encode(...)` and `close()` with a private lock; `close()` is idempotent, recycles bitmap resources once, and encode-after-close/mismatched-input fails cleanly.
- Out of scope for the next slice: public startup wiring, `ScreenCaptureEngines.create(context)`, `CaptureMetricsProviders.*`, runtime frame loop, real frame scheduling, `SurfaceTexture.updateTexImage()`, OES matrix acquisition, `glReadPixels(...)`, frame publication, transactional publication discard enforcement, runtime encoded-size stats/drop accounting, metrics provider factories, native JPEG, ES3/PBO, and runtime parameter transactions.
- Test plan for the next slice:
  - Add focused Robolectric/JVM tests for `Auto`/`FrameworkOnly` metadata, configured quality propagation, defaults provider through `ImageEncoderPreparer` without calling encode, tight-row decodable JPEG, padded-row input with poisoned padding, byte-exact RGBA channel order, alpha ignored/forced opaque, canonical absolute-offset buffer behavior, noncanonical `position != 0` clean failure, insufficient `limit` failure, buffer position/limit preservation, sink false/throw/cap rejection and success-after-rejection prevention, legal tiny cap `1024` accepted at `createEncoder`, close idempotence, encode-after-close behavior, checked allocation-size/invalid request boundaries, and `AllocationFailed` classification propagation.
  - Validate with targeted `--rerun-tasks`, full `:screencaptureengine:testDebugUnitTest`, `:screencaptureengine:compileDebugKotlin`, and `git diff --check`.
- Remaining design questions for the next JPEG provider slice: none. Remaining implementation risk: allocation-failure classification may require provider-preparation/rendering-preparation plumbing changes.

## Current Slice Facts

- `Es2RenderingPipelinePreparer` sequence is: token check, cheap static preflight, exactly one `Es2ReadbackSpec`, `StartupRenderingGlAccess.withCurrentStartupRenderingTarget`, selected ES2 color-mode readiness, token check, transform package from the same spec and metadata, token check, encoder prepare with the same token/provider/exact `outputPlan.encoderRequest`, token check, success.
- First stale token returns non-failure `RenderingPipelinePreparationResult.LifecycleStale` with no GL/direct-buffer/provider/encoder work.
- Provider admission happens exactly once and only after ES2 plus transform success. Provider reservation before ES2/direct-buffer allocation is intentionally deferred unless the design author approves a new reservation contract.
- `Es2RenderingPipelinePreparer` requires explicit `ImageEncoderPrepareOperation` injection. It does not create or own a hidden `ProviderPreparationContext`, and it does not close provider context.
- Provider stale success, stale validation failure, stale provider failure, and abandon-first paths terminally complete awaiters. Stale successful encoders close exactly once asynchronously.
- Late failure diagnostics are best-effort, run off the provider worker after stale completion and provider-worker slot release, swallow diagnostics exceptions, and allow only one in-flight diagnostic so a blocked sink cannot queue unbounded work.
- Cleanup contract: transform failure closes ES2 and skips provider; encoder failure closes ES2; stale after ES2/transform/encoder success closes owned resources; cleanup exceptions are suppressed so they do not replace the primary classification.
- Caller cancellation during suspended encoder prepare after ES2 acquisition cleans ES2 and preserves cancellation.
- Startup GL target owner/generation/closed/current-target invariant failures are mapped to `GlInvariantViolation` without message parsing, and internal startup access exceptions are sanitized before public problem construction.
- Timeout classification uses an atomic startup-stage reference: acquisition/currenting timeout maps to `GlInitializationFailed`; after ES2 preparation begins it maps to `GlResourceFailure`. Timeout invalidates the token, abandons the GL lane, and relies on startup GL access cancellation cleanup for late ES2 success.
- Lower ES2 readiness supports original and grayscale external-OES program variants, validates program status, uses explicit metadata-to-first-plan binding, moves direct readback buffer allocation after shader/program/FBO readiness, and rolls back GL objects on late allocation failure.
- Static boundary guard allowlists only `Es2RenderingPipelinePreparer.kt`; it strips comments/strings while preserving executable string-template expressions and forbids runtime frame/update/readback/encode/publication/public-state/ES3/PBO/direct-GLES paths.

## Final Validation

- Final validation passed with actual targeted rerun execution: `./gradlew --no-parallel --rerun-tasks :screencaptureengine:testDebugUnitTest --tests dev.dmkr.screencaptureengine.internal.runtime.ImageEncoderPreparerTest --tests dev.dmkr.screencaptureengine.internal.runtime.Es2RenderingPipelinePreparerTest --tests dev.dmkr.screencaptureengine.internal.runtime.RenderingPipelineBoundaryStaticGuardTest`.
- Full module unit test passed: `./gradlew --no-parallel :screencaptureengine:testDebugUnitTest`.
- Debug Kotlin compile passed: `./gradlew --no-parallel :screencaptureengine:compileDebugKotlin`.
- Whitespace/diff check passed: `git diff --check`.
- Final independent code/resource review found no correctness, cleanup, or regression issues in provider race fixes, diagnostics handling, hidden provider-context ownership shape, tests, ES2 ordering, or ownership boundaries.
- Final independent performance/memory/resource audit found no fix-now issues. Late diagnostics does not retain provider admission or worker threads; the one-in-flight diagnostics guard prevents unbounded queueing while blocked; provider double-work remains guarded; ES2 orchestration still defers provider work until after ES2 readiness plus transform; direct-buffer/GL cleanup still captures scalar handles rather than the readback buffer/resource bag.

## Deferred Risks

- Provider reservation before ES2/direct-buffer allocation remains deferred. A non-binding capacity check would be racy; a true reservation requires a new internal reservation contract with release semantics across ES2 failure, transform failure, timeout, cancellation, and stale token cases.
- Provider-context ownership is still an integration contract. The future engine/session owner that creates `ProviderPreparationContext` must outlive all `PreparedImageEncoderResources` that may use it for cleanup, and must close it after those resources are closed.
- Cleanup executors currently rely on single-thread executor queues if cleanup itself blocks. This is off caller-critical paths and acceptable for this pre-public slice, but high-churn runtime integration needs an ownership/backpressure policy.
- JVM/unit validation does not prove real-device GLES/OES driver behavior, actual `SurfaceTexture` runtime behavior, real readback/encode/frame publication, release variants, instrumentation tests, lint, or app flavor builds.

## Later Slices

- After the next JPEG-provider slice, runtime activation should likely be the following slice.
- Runtime activation must implement the v32 real `RuntimeFrameLoopInstalled` duties: accept real frame signals, apply frame-rate policy, schedule render/readback/encode/publication attempts, route production drops/stats, drain pending geometry/density, and handle stop/projection-stop. A zero-frame shell alone is not enough for public release behavior.
- Concrete usable `ScreenCaptureEngine.startSession(...)` implementation remains deferred until `RuntimeFrameLoopInstalled`, public `InitialActivePlanCommitted`, and initial `Running(output = Active(...))` publication / returned-session exposure can be reached. Internal prepared owners must not be named or treated as `InitialActivePlanCommitted`.
- The encoded-attempt transactional discard/drop path must be implemented before frame publication is exposed.
- `ScreenCaptureEngines.create(context)` and lazy non-throwing built-in `CaptureMetricsProviders.*` remain deferred public-default slices.
- PBO/ES3 remains deferred.
- Runtime parameter transactions remain deferred.
- Real frame scheduling/readback/encode/publication remains deferred.
- Public encoded-size-cap drop entrypoint remains deferred to the encoder-loop/publication slice.
- Future compactness work only if pressure appears: static-guard file discovery, `outputRenderbufferId`, shader detach/delete-after-link if measured retention matters, private sealed builder/file split if orchestration creates real pressure, and ES2 fixture extraction only after reuse exists.

## Durable Decisions

- Runtime placeholder public APIs fail with ordinary exceptions, not `Error` types.
- Public immutable value/state models use manual `equals`/`hashCode`; do not convert them to `data class` by default.
- Keep validation constants private/local unless actual drift or repeated update burden justifies a shared abstraction.
- Kotlin has no standard checked integer arithmetic equivalent to JVM `Math.addExact` / `Math.multiplyExact`; keep those APIs where checked overflow is required unless a clearer project-local abstraction becomes justified.
- Configured caller-owned dispatchers are reached through an engine-owned callback bridge so custom direct/immediate dispatchers cannot run callbacks on the single delivery coordinator worker or producer-critical callers.
- Subscription stats snapshots from the delivery coordinator are versioned; the session core ignores older snapshots so races cannot roll public counters backward.

## Design Deviations

- None.
