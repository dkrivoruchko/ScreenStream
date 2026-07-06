# ScreenCaptureEngine Implementation Tracker

## Source Of Truth

- Design: `screen-capture-engine-design-v28.md`.
- The design file is read-only and controls implementation unless an approved deviation is recorded here.
- Main-thread user communication is Russian. Code, KDoc, comments, tests, commit text, and project documentation are English.
- Code documentation describes the current contract directly; it must not reference design document versions, implementation phases, tracker phases, roadmap, or process.

## Operating Rules

- Implementation proceeds as small vertical runtime slices that preserve design invariants.
- The current design revision remains the top-level implementation frame. Work may be regrouped into smaller semantic chunks when that improves correctness, reviewability, or context fit, but chunks must still serve the design rather than redefine it.
- Before a non-trivial slice, read the relevant current-design sections, check current official Android/Kotlin/coroutines docs for platform/library behavior, and use bounded independent sub-agents for research, implementation, review, test audit, and re-review. Close completed sub-agents promptly.
- Sub-agent briefs for Android/platform/coroutine/runtime work must explicitly require current official documentation checks for any platform/library behavior they rely on. Do not accept memory-only platform reasoning for MediaProjection, VirtualDisplay, SurfaceTexture/EGL/GL, HandlerThread/Looper, or Kotlin coroutine cancellation/dispatcher behavior.
- After non-trivial runtime refactor patches, run an IDE inspection pass across the whole `screencaptureengine` module, not only changed files. Fix actionable warnings/errors using design-aware refactoring and official docs/best practices where platform/library behavior is involved; do not silence warnings mechanically.
- Ignore Gradle/version-catalog inspection findings unless explicitly requested. Kotlin source/test inspection warnings are the actionable scope for module cleanup passes.
- Ignore IDE suggestions that conflict with durable public model decisions, especially converting/removing manual `equals`/`hashCode` on public immutable models.
- Do not invent public API, lifecycle behavior, fallback policy, error handling, integration behavior, or deviations from the current design.
- Stop for user/design-author input when implementation exposes a design gap, conflict, missing case, risky tradeoff, or repeated ambiguity.
- Design-author questions must be phrased in design/state-machine terms because the design author does not see this codebase.
- Runtime code must not publish parallel public state/flows/counters. Public state, stats, diagnostic events, frame drops, visibility changes, geometry transitions, and output-plan transitions go through `ScreenCaptureSessionCore`.
- Keep this module independent from existing app modules. Use stable/release dependency versions and check current official sources before version changes.
- Prefer compact, modern, idiomatic Kotlin. Do not add helper abstractions or data classes unless they carry real behavior, reduce meaningful duplication, or match the existing design.
- Existing internal code is not fixed just because it predates a slice. When a slice exposes mismatch, fragility, duplicated ownership, or awkward seams in existing internal owners, refactor the behavior owner so the whole runtime model becomes more coherent; do not add a parallel workaround layer merely to avoid touching older code. Stop for user/design input only when the needed refactor becomes broad, changes public API, or changes current-design behavior.
- Validate with sequential Gradle tasks for this module; avoid concurrent Gradle/Kotlin compile runs.
- Tests are for critical, complex, concurrency-sensitive, lifecycle-sensitive, or previously disputed behavior only. Do not add broad coverage, trivial constructor tests, obvious validation tests, or equality/hashCode tests during active runtime development unless they protect a real known risk.
- Current approved test setup: local JVM unit tests with explicit module-local dependencies in `screencaptureengine/build.gradle.kts`: JUnit `4.13.2`, `kotlinx-coroutines-test` `1.11.0`, and Robolectric `4.16.1`, approved as latest stable on 2026-07-06. Do not add MockK/Mockito, JUnit 5, coverage tooling, instrumentation tests, Android runner, `returnDefaultValues`, or Android resources by default.
- Current targeted JVM tests cover planner geometry/cap decisions that affect runtime allocation, session core generation/drop/commit-gate/visibility invariants, delivery coordinator latest-only/backpressure/lease/admission behavior, and MediaProjection callback stop-priority gating. Defer low-value model tests and platform/GL owner tests that require broad seams or device integration.
- Robolectric may be used for targeted local JVM tests that genuinely need Android framework behavior. With this module's JVM toolchain 17, do not rely on Robolectric SDK 36+ execution; prefer lower explicit `@Config(sdk = [...])` values for current JVM tests, or add a small internal seam when Android framework access is not the behavior under test.

## Current State

- Implemented:
  - public API, validation, KDoc, and manual equality/hashCode models;
  - pure output planning, capture-target pixel/byte caps, delayed encoder-info attachment, and `FrameRate.Auto -> FrameRate.MaxFps(30)` effective resolution;
  - internal session skeleton, latest-only frame delivery, bounded public snapshot leases, callback failure accounting, slow-consumer tracking, dispatcher handoff, watchdogs, diagnostic rate limiting;
  - core-owned output generation, visibility, suspension/resume, coherent encoded-result stats, and production-drop entrypoints;
  - minimal internal EGL/OES projection target owner using a real generated external OES texture and GL-backed `SurfaceTexture`, with exception-safe attempts for target, EGL, and GL-thread teardown;
  - internal `MediaProjection.Callback` adapter with serialized listener handoff and stop-priority gating for resize/visibility;
  - internal single-create `VirtualDisplay` owner with non-null target surface, resize-then-setSurface retargeting, candidate-target cleanup on failed commits, and exception-safe attempts for all owned teardown resources;
  - accepted internal startup transaction through `AuthoritativeStartupGeometryReady`, with owned metrics observation, projection freshness/consumption tracking, API 34 stop/caller-cancellation/resize/timeout arbitration, one-shot callback handoff, rollback cleanup, and fake-driven JVM coverage;
  - accepted internal pre-active runtime preparation after `AuthoritativeStartupGeometryReady`, with move-only startup resource transfer, frozen-geometry initial output planning, projection-target runtime limits, authoritative target creation/rebind on the existing `VirtualDisplay`, bounded retry/fallback, pending pre-commit resize/visibility retention, terminal-stop priority, cancellation/close rollback, and fake-driven JVM coverage;
  - accepted internal startup-to-runtime ownership handoff, with split lifecycle-owned runtime startup files, frozen non-owning `PreActiveInitialRuntimePlan`, internal `InitialRuntimeHandoffGate`, move-only `InitialRuntimeResourceOwner`, exactly-once selected-old-listener signal forwarding, pending runtime resize/density/metrics handoff plus initial captured-content visibility handoff, cleanup ownership transfer, and fake-driven JVM coverage;
  - accepted post-slice refactor/cancellation hardening: startup resources are no longer returned through a cancellable lexical coroutine-scope boundary, pre-authoritative startup uses an explicit `StartupGeometryGate`, pre-authoritative factory/resource failures are guarded by projection-stop/caller-cancellation priority checks, and `CaptureMetricsObservation` owns its internal collector `SupervisorJob` without cancelling caller/shared jobs;
  - targeted local JVM tests for critical planner, session core, delivery coordinator, and runtime callback behavior.
- Not implemented yet:
  - public `ScreenCaptureEngine.startSession(...)` runtime;
  - `RenderingPipelineReady`, `InitialActivePlanCommitted`, public `Running(Active)`, GL rendering/readback, JPEG runtime, encoder publication loop, built-in metrics-provider runtime behavior, provider fallback policy, full async teardown integration, and final lifecycle hardening;
  - broader runtime and integration test coverage.
- `CaptureMetricsProviders` factory bodies and `JpegImageEncoderProvider.createEncoder(...)` intentionally fail until their runtime slices are implemented.

## Last Validation

Passed on 2026-07-06 after production Kotlin refactor/cancellation audit and fixes:

- `./gradlew --no-parallel :screencaptureengine:testDebugUnitTest --tests dev.dmkr.screencaptureengine.internal.runtime.CaptureMetricsObservationTest --tests dev.dmkr.screencaptureengine.internal.runtime.ScreenCaptureStartupGeometryTest --tests dev.dmkr.screencaptureengine.internal.runtime.StartupToRuntimeHandoffTest`
- `./gradlew --no-parallel :screencaptureengine:testDebugUnitTest --tests dev.dmkr.screencaptureengine.internal.runtime.StartupProjectionCallbackRouterTest --tests dev.dmkr.screencaptureengine.internal.runtime.StartupToRuntimeHandoffTest`
- `./gradlew --no-parallel :screencaptureengine:testDebugUnitTest`
- `./gradlew --no-parallel :screencaptureengine:compileDebugKotlin`
- `./gradlew --no-parallel :screencaptureengine:lintDebug`
- `git diff --check`
- `git diff --cached --check`
- JetBrains MCP inspections on changed production/test Kotlin files reported no errors. Remaining warnings are intentional next-slice internal seams (`transferToPreActiveRuntimeOwner`, `prepareInitialActivePlan`, `transferToInitialRuntimeResourceOwner`, internal runtime-owner snapshots) until the public runtime integration consumes them.

## Current Slice Boundary

- Accepted slice status: internal startup through `AuthoritativeStartupGeometryReady`, internal pre-active authoritative output planning/rebind, startup-to-runtime ownership handoff, and production Kotlin refactor/cancellation hardening are accepted.
- The slice remains internal. It stops before public `startSession(...)`, public `Running(Active)`, `RenderingPipelineReady`, GL readback, JPEG runtime, and frame publication.
- `PreActiveInitialRuntimePlan` is an internal, non-owning pre-commit snapshot. It is consumed by `InitialRuntimeResourceOwner` but is not a public success boundary.
- Pending pre-commit resize and density/metrics signals are retained for runtime processing after the future `InitialActivePlanCommitted`; latest pre-commit visibility is retained to initialize the first public `capturedContentVisible` value and must not replay as a runtime visibility update. These signals do not mutate the frozen initial plan. Projection stop remains startup-aborting before commit.

## Next Runtime Slices

- Add the GL rendering/readback path and `ImageEncoderProvider` readiness needed for `RenderingPipelineReady`, derived from the frozen startup geometry and authoritative generation-owned projection target. `JpegImageEncoderProvider` remains the required built-in provider, but the core readiness path stays provider-based.
- Add the encoder publication loop only after those readiness resources are validated. Public frame publication must use `ScreenCaptureSessionCore.publishEncodedFrame(generation, ...)`.
- Build public `ScreenCaptureEngine.startSession(...)` / `Running(Active)` integration only after the internal startup transaction can commit the initial active plan atomically with no partial public session state.
- Keep heavy Android/GL cleanup off public `stop()`/`close()` fast paths. Public terminal state is a fast boundary; runtime cleanup is engine-owned asynchronous teardown.
- Runtime ownership direction: `InitialRuntimeResourceOwner` now owns projection callback/router, virtual display, generation target, metrics observation, cleanup, terminal stop policy, initial output plan/target snapshot, and drained pending startup-to-runtime signals. The prepared slice should extend it with immutable prepared pipeline resources only; later runtime-loop slices can attach GL/readback and encoder loops while delegating state/stats/events/delivery to `ScreenCaptureSessionCore`.
- Next-slice high-value tests should cover GL/render/readback resource preparation and rollback before `RenderingPipelineReady`, while preserving the accepted ownership handoff invariants.
- Next-slice official docs to recheck: Android MediaProjection guide and Android 14 behavior changes, `MediaProjection.Callback.onStop`, `VirtualDisplay.resize` / `setSurface`, `SurfaceTexture` / GL context requirements, and Kotlin coroutine cancellation/scope docs for any runtime-owned scope or control context.

## Prepared Next Slice

Status: prepared for the next work session under v28.

Recommended slice: internal `RenderingPipelineReady` resource preparation, with a narrow GL ownership refactor, ES2-first render/readback readiness, and isolated `ImageEncoderProvider.createEncoder(request)` preparation. Keep it internal and pre-public.

Rationale: keep `RenderingPipelineReady` as one internal transaction so the prepared GL/readback resources, prepared encoder, frozen output plan, target generation, and cleanup ownership are validated and handed off together. Implement this through sibling subsystems, not one large owner: `RenderingReadbackPreparer` and `ImageEncoderPreparer` / `ProviderPreparationContext`.

Planned scope:

- Refactor the existing GL ownership boundary rather than adding rendering/readback as another inline step in `PreActiveRuntimeOwner` or a monolithic extension of `ProjectionTargetOwner`.
- Introduce a cohesive `GlLaneContextOwner` behind the projection target code. It owns the `HandlerThread`/GL execution lane, EGL display/context/pbuffer surface, `makeCurrent`, GL limit queries, and serialized suspendable/async GL execution.
- Keep `ProjectionTargetOwner` focused on generation-owned projection targets: OES texture, `SurfaceTexture`, projection `Surface`, default buffer size, target close/retire, and generation validity.
- Add a separate `RenderingReadbackPreparer` for shaders, programs, uniforms/attributes, OES sampling configuration, output FBO/texture or renderbuffer state, viewport/readback configuration, and ES2 CPU readback buffer ownership.
- Add a restricted GL-only target/frame-source access path for rendering/readback code without exposing raw `SurfaceTexture` through the generic projection-target handle.
- Prepare ES2 rendering/readback resources for the frozen initial output plan and current projection target generation: EGL context-current validation, shader compile/program link checks, required uniform/attribute checks, OES transform path configuration, output FBO/renderbuffer or texture allocation, GL size-limit checks, FBO completeness checks, and checked byte arithmetic for all pixel/readback allocations.
- Allocate and validate the ES2 CPU readback buffer for the selected output dimensions. Validate `glReadPixels` format/type/alignment assumptions by configuration; do not call `glReadPixels(...)` as part of mandatory startup readiness.
- Add a dedicated internal rendering pipeline preparer after frozen planning and authoritative target rebind. It should coordinate the GL/readback and encoder sibling preparers and produce a move-only immutable `PreparedRenderingPipelineResources` bag consumed by `InitialRuntimeResourceOwner`; do not turn `InitialRuntimeResourceOwner` into the mutable render-loop owner yet.
- Keep `ScreenCaptureOutputPlanner` pure. Reuse the `ScreenCaptureOutputPlan.encoderRequest` produced from final output width/height, readback row stride, `maxEncodedBytes`, and `Rgba8888SrgbOpaque`.
- Add an `ImageEncoderPreparer` as a sibling to GL/readback preparation, not nested inside GL code. It uses `ScreenCaptureOutputPlan.encoderRequest`, validates `providerId == provider.id`, `outputFormat == provider.outputFormat`, output-format/request compatibility, cap compatibility, and closes invalid returned encoders.
- Add isolated provider-preparation execution for `createEncoder(request)`: engine-owned `ProviderPreparationContext`, `ExecutorService`/`Future` worker admission, a coroutine-facing result bridge that does not own the blocking worker, stale-token fencing by startup/parameter transaction token, best-effort `Future.cancel(true)` / interruption, late-result quarantine, and isolated late close/discard.
- Carry the selected `ImageEncoderProvider` with the pre-active candidate/prepared plan without making the pure planner own provider lifecycle.
- Treat frame-available handling as out of scope unless the implementation needs inert pre-commit signal plumbing. If added, it must only enqueue/conflate non-GL signals; no scheduling, no `updateTexImage()`, no first-frame dependency, and no public callbacks.

Out of scope for this slice:

- public `ScreenCaptureEngine.startSession(...)`;
- public `Running(Active)` / `InitialActivePlanCommitted`;
- real frame scheduling, `updateTexImage()` consumption loop, real readback/encode/publication loop, and frame callbacks;
- runtime resize / `setParameters(...)` transactions;
- ES3/PBO implementation, probing, validation, fallback, or public mode changes;
- synthetic `ImageEncoder.encode(...)` calls for app-provided providers;
- active-session `setParameters(...)` provider-preparation integration; use the same eventual `ProviderPreparationContext`, but keep the next slice focused on startup readiness;
- actual built-in JPEG backend implementation unless needed to make encoder readiness tests meaningful; fake providers are sufficient for provider lifecycle/control-flow coverage;
- broad `ScreenCaptureFrameDeliveryCoordinator`, `ScreenCaptureSessionCore`, or public model refactors.

Technical constraints from official docs and current design:

- `SurfaceTexture.updateTexImage()` must run on the thread with the owning current GL context; frame callbacks must not do GL work directly.
- Avoid a shared-context model unless deliberately designed and tested. The first implementation should prefer the same GL thread/context that owns the projection target.
- Do not add more synchronous `CountDownLatch`-style GL proxy calls. New GL preparation APIs should be suspendable at the caller boundary or invoked only from an engine-owned runtime/control context that cannot be caller/Main.
- Pre-commit failures after `createVirtualDisplay(...)` consumption remain startup failures requiring a fresh projection for retry.
- Startup cancellation before the final commit-return handoff must logically abandon startup, retire candidate render/readback/encoder resources, and keep heavy cleanup off caller/public fast paths.
- `RenderingPipelineReady` must not require a first real frame, public frame publication, or public session identity.
- The first readiness implementation may always select `ReadbackMode.Es2`; no startup failure is allowed merely because PBO is absent or unimplemented.
- App-provided `ImageEncoderProvider` readiness is configuration/resource validation only. Do not invoke provider `encode(...)` with synthetic input before `InitialActivePlanCommitted`.
- `ImageEncoderProvider.createEncoder(request)` is synchronous app/provider code. It must not run on the caller thread, Main, control lane, GL lane, MediaProjection callback handler, frame-delivery coordinator, frame callback dispatcher, active runtime encoder lane, or any singleton critical engine lane.
- Startup provider creation uses internal `ENCODER_CREATE_TIMEOUT_MS = 3_000`. Timeout maps to `ScreenCaptureProblemKind.EncoderUnavailable` with a diagnostic message; in the normal post-`createVirtualDisplay(...)` startup path it reports `requiresFreshProjection = true`.
- `Future.cancel(true)` and coroutine interruption are best-effort only. Timeout/cancellation/projection-stop handling must treat provider work as logically abandoned, not guaranteed stopped.
- Caller cancellation or projection stop while provider creation is running must commit logical rollback without waiting for provider code. Mark the prepare record stale, attempt best-effort cancellation/interruption, retire startup resources, and keep late results fenced away from startup success.
- If provider creation returns after timeout/rollback/stop, close/discard the encoder asynchronously on isolated provider cleanup and do not replace the primary startup outcome. Late exceptions are internal diagnostics only.
- Permanently stuck provider workers may be abandoned/quarantined for the session. They must not block rollback, `stop()`, or `close()`. Bound quarantined provider workers globally, recommended internal default `MAX_QUARANTINED_PROVIDER_WORKERS = 2`; if exhausted, fail provider-preparation admission with `EncoderUnavailable`.
- Startup failure from encoder readiness after `createVirtualDisplay(...)` consumption requires a fresh projection when no safe fallback exists.
- Detached pending geometry/density must be drained by runtime before the first normal render scheduling tick after `InitialActivePlanCommitted`; this is not part of the non-cancellable commit-return path.
- Initial captured-content visibility is visible through the first public state only. Do not emit a separate diagnostic event for that initial value.
- Public KDoc should be updated when this slice lands: `ImageEncoderProvider.createEncoder(...)` may run on an isolated bounded provider-preparation context and late results may be discarded/closed; `ImageEncoder.close()` should return promptly; startup readiness includes provider preparation/failure.

Expected tests:

- JVM/fake tests for resource-preparation success, generation matching, rollback on GL/readback preparation failure, encoder creation/validation failure, invalid encoder cleanup, no synthetic app-provider encode before commit, projection-stop priority, caller cancellation, provider-create timeout, projection stop while create is pending, caller cancellation while create is pending, late success close/discard, late failure suppressed from primary outcome, quarantine/admission exhaustion, and cleanup ownership.
- Add a guard test or review check that new GL/readback preparation does not use synchronous GL waits on caller/Main and provider creation does not run on caller/Main/control/GL/MediaProjection callback/frame-delivery/runtime encode lanes.
- Split test fixtures before adding the new cases: keep startup/projection/VD/target fakes separate from pipeline-readiness fakes and provider-preparation fakes. Do not grow `ScreenCaptureStartupTestFixtures.kt` into a universal fixture.
- Targeted Robolectric tests only where Android framework threading behavior is genuinely needed.
- Real EGL/OES/`glReadPixels` correctness can be deferred to device/integration validation; JVM tests should use seams/fakes for control-flow ownership.

## Current Design Decisions For Next Slice

- `RenderingPipelineReady` requires mandatory resource/config validation only: current EGL context, valid projection `SurfaceTexture`/OES generation, shader compile, program link, required uniforms/attributes, OES transform path, output FBO/renderbuffer/texture allocation, FBO completeness, GL limits, checked byte-size arithmetic, selected readback backend setup, ES2 CPU readback buffer allocation, and encoder request/instance/info compatibility.
- Mandatory readiness must not consume a real projected frame, call `SurfaceTexture.updateTexImage()`, perform first real render, call `glReadPixels(...)`, call `ImageEncoder.encode(...)`, or publish an encoded frame.
- Optional bounded internal synthetic GL/readback self-tests are allowed only if they do not become a hidden dependency on first real frame consumption. Synthetic encode through app-provided providers is not allowed before commit.
- First implementation may be ES2-only. Effective `readbackMode` is `Es2`; PBO-specific implementation/tests are deferred to the PBO slice.
- If future ES3/PBO startup validation fails while ES2 validates, degrade one-way to ES2 and continue. No public event is emitted for pre-public fallback; initial effective parameters expose the selected mode.
- `ImageEncoderProvider.createEncoder(request)` is untrusted synchronous provider code.
- Provider creation runs only on an isolated engine-owned provider-preparation context. It must not run on caller/Main, control, GL, MediaProjection callback, frame-delivery coordinator, frame callback dispatcher, or active runtime encoder lanes.
- Startup provider creation uses internal/test-tunable `ENCODER_CREATE_TIMEOUT_MS = 3_000`.
- Startup timeout maps to `ScreenCaptureProblemKind.EncoderUnavailable` with a diagnostic message such as `"ImageEncoderProvider.createEncoder timed out after 3000 ms"`. Do not add a public `EncoderCreateTimedOut` enum value.
- Because startup provider creation normally happens after `createVirtualDisplay(...)` entry, timeout/unavailable/validation failure requires a fresh projection.
- Encoder readiness validates `ImageEncoder.info`, `providerId == provider.id`, `outputFormat == provider.outputFormat`, output-format/request compatibility, and request/cap compatibility. Do not imply a new public provider capability API. `ImageEncoderUnavailableException`, timeout, admission failure, and provider creation failure map to `EncoderUnavailable`; invalid or mismatched returned state maps to `EncoderValidationFailed`.
- If caller cancellation or projection stop wins while provider creation is running, startup commits logical rollback immediately, marks the provider-prepare record stale, attempts best-effort interruption/cancellation, and does not wait for provider code.
- Late provider success after timeout/rollback/stop is stale. Close/discard the returned encoder asynchronously on isolated provider cleanup, do not validate it into startup success, and do not replace the primary startup outcome.
- Late provider exception after timeout/rollback/stop is internal diagnostic information only and must not replace the primary startup outcome.
- `ImageEncoder.close()` is also provider code and may block; late close/discard must use isolated provider-cleanup execution, not control/GL/Main/runtime encoder lanes.
- Permanently stuck provider workers may be abandoned/quarantined for the session. Attempt interruption, never reuse the lost worker for engine work, do not wait for it in rollback/stop/close, and keep all results fenced by transaction token.
- Bound quarantined provider workers globally. Recommended internal default: `MAX_QUARANTINED_PROVIDER_WORKERS = 2`; if exhausted, fail future provider-preparation admission with `EncoderUnavailable`.
- Do not implement provider preparation as a structured child coroutine that rollback must join. Use an isolated executor/future or equivalent abandonable worker plus transaction-token fencing.
- `setParameters(...)` must eventually use the same provider-preparation context. For a running session, timeout rejects the parameter update with `EncoderUnavailable`, keeps the current active plan, and closes/discards any late encoder.
- First real readback/encode failure after `InitialActivePlanCommitted` is always runtime behavior, not startup failure. Single transient failures drop one production opportunity. Repeated hard failures may fallback, suspend, fail, or end capture according to runtime safety and cause.
- Real encoded output exceeding `maxEncodedBytes` after commit is a production drop with `EncodedSizeLimitExceeded` diagnostics/stat accounting and no automatic suspension. Startup failure is possible only if cap impossibility is deterministically known before first real encode.
- Pending geometry/density detached at commit must be processed before the first normal render scheduling tick after `InitialActivePlanCommitted`, while preserving the no-suspension/no-hop commit-return invariant.
- No diagnostic event is emitted only to restate initial captured-content visibility; the initial value is part of the first public state.

## Refactor Notes

- Startup/handoff runtime files are split by lifecycle ownership. Keep this structure unless the GL/readback slice exposes a concrete ownership/testability problem.
- `ScreenCaptureStartupTransaction` should continue to end at `AuthoritativeStartupGeometryReady`. Do not extend it with GL/readback/encoder readiness.
- `PreActiveRuntimeOwner` should stay the coordinator for frozen planning and authoritative target rebind. Do not add GL/readback/encoder implementation details inline.
- `ProjectionTargetOwner` is acceptable for the accepted internal slice, but the GL/readback slice should refactor its GL ownership before adding rendering/readback logic. Keep target lifecycle/generation ownership there; move GL lane execution and rendering/readback resource ownership into cohesive collaborators.
- `ProjectionTargetOwner` currently proxies GL work synchronously through its GL thread. Do not expand this pattern. Before public `startSession(...)`, ensure those calls run on an engine-owned runtime/control context or become suspendable so caller/main threads are not blocked.
- Keep `ProjectionTargetHandle` surface-only. Any `SurfaceTexture`/OES texture access for rendering must be restricted to a GL-only generation-checked callback or token that is valid only on the owning current GL context.
- Add separate fakes/seams for rendering/readback preparation, encoder preparation, prepared encoder cleanup, and prepared pipeline cleanup. Do not keep growing the existing startup test fixture into a universal runtime fixture.
- `ScreenCaptureFrameDeliveryCoordinator`, `ScreenCaptureSessionCore`, and `Models.kt` are large older files. Defer mechanical splitting until a functional slice touches those areas or a targeted refactor can be protected by the existing delivery/session tests.
- `ScreenCaptureFrameDeliveryDispatcher` low-level dispatcher bridge is defensible under current coroutine docs, but should get focused tests if delivery dispatch behavior is next changed.

## Open Decisions And Deferred Work

- None blocking for the accepted internal slice.
- PBO implementation remains deferred. When it is implemented, startup validation failure should degrade one-way to ES2 if ES2 validates.
- Before public `startSession(...)`, projection-target GL proxy work must not block caller/main threads; either run it from an engine-owned runtime/control context or make the relevant boundary suspendable.
- During encoder-loop integration, decide whether encoded-but-dropped results keep using `publishEncodedFrame(...)` or need a narrower core entrypoint so encoded/drop counters update atomically.

## Durable Decisions

- Runtime placeholder public APIs fail with ordinary exceptions, not `Error` types.
- Public immutable value/state models use manual `equals`/`hashCode`; do not convert them to `data class` by default.
- Keep validation constants private/local unless actual drift or repeated update burden justifies a shared abstraction.
- Kotlin has no standard checked integer arithmetic equivalent to JVM `Math.addExact` / `Math.multiplyExact`; keep those APIs where checked overflow is required unless a clearer project-local abstraction becomes justified.
- Configured caller-owned dispatchers are reached through an engine-owned callback bridge so custom direct/immediate dispatchers cannot run callbacks on the single delivery coordinator worker or producer-critical callers.
- Subscription stats snapshots from the delivery coordinator are versioned; the session core ignores older snapshots so races cannot roll public counters backward.

## Design Deviations

- None.
