# ScreenCaptureEngine Implementation Tracker

## Source Of Truth

- Design: `screen-capture-engine-design-v34.md`.
- The design file controls implementation unless an approved deviation is recorded here.
- User-facing coordination is in Russian. Code, KDoc, comments, tests, commit text, and project documentation are in English.
- Code documentation describes the current contract directly; it must not reference design document versions, implementation phases, tracker phases, roadmap, or process.

## Standing Rules

- Main agent owns this tracker and final integration. Sub-agents do bounded work only, start fresh without forked parent conversation context, do not spawn sub-agents, and complete at least two self-review passes.
- Material sub-agent results must be recorded here before closing the sub-agent. Keep this file compact before handoff/commit.
- Do not touch git staging/index. The user controls staging before commits.
- Before non-trivial runtime/platform/system/API/GL/coroutines/test-technique work or review, use current official or primary sources for the APIs/practices being relied on.
- Runtime code must not publish parallel public state/flows/counters. Public state, stats, diagnostic events, frame drops, visibility changes, geometry transitions, and output-plan transitions go through `ScreenCaptureSessionCore`.
- Reflection is prohibited in production implementation and tests for this slice. If reflection appears necessary, stop and report it as a design/testability problem.
- Current approved JVM test setup in `screencaptureengine/build.gradle.kts`: JUnit `4.13.2`, `kotlinx-coroutines-test` `1.11.0`, and Robolectric `4.16.1`, approved as latest stable on 2026-07-06. Do not add MockK/Mockito, JUnit 5, coverage tooling, instrumentation tests, Android runner, `returnDefaultValues`, or Android resources by default.
- Validate with sequential Gradle tasks for this module; avoid concurrent Gradle/Kotlin compile runs. When final validation matters, force actual targeted test execution instead of accepting only `UP-TO-DATE` output.

## Closed Slice Snapshot

- Status: closed. The public activation boundary with first real runtime production path is implemented and verified for the JVM/Robolectric scope.
- Out of scope at closure remained: `ScreenCaptureEngines.create(context)`, built-in `CaptureMetricsProviders.*`, full runtime `setParameters(...)` transactions, provider fallback policy beyond hard-failure threshold behavior, ES3/PBO, native JPEG, transport/app/flavor integration, and device validation as a commit blocker.
- `InitialRuntimeResourceOwner` transfers prepared resources into `ActiveRuntimeOwner`; the initial owner becomes inert after transfer.
- `RuntimeFrameLoop` is installed before commit. `InitialActivePlanCommitted` returns the public `ScreenCaptureSession` through a non-suspending handoff with no GL/readback/encode/blocking cleanup on the commit-return path.
- The first single-plan ES2 production path is implemented: `SurfaceTexture` frame signal -> `updateTexImage()`/OES matrix/timestamp -> ES2 draw -> one-slot RGBA readback lease -> `ImageEncoder.encode(...)` -> `EncodedAttemptScratch` -> transactional publish/drop through `ScreenCaptureSessionCore`.
- No frame is consumed before `InitialActivePlanCommitted`.
- Raw `MediaProjection.Callback.onStop()` ingress is fenced through `ProjectionStopArbiter`; final publication, periodic refresh publication, owner stop, failures, materialized drops, and materialization boundaries re-check the same projection-stop arbitration.
- `ScreenCaptureSessionCore` remains the only public state/stats/events/accounting owner.
- `PeriodicRefresh` before the first successful `updateTexImage()` produces no publication, no production drop, and no output suspension. Coalesced frame callbacks are latest-only input coalescing until a production opportunity is materialized.
- Pending geometry/density after commit can suspend output before the first render when runtime re-plan is out of scope.
- Materialized stale completions account exactly once as stale and do not publish or deliver. If terminal/stale wins before materialization, there is no production drop.
- Stop/close do not wait for stuck GL/encode and do not close borrowed resources concurrently. Stuck GL/readback uses `RUNTIME_GL_OPERATION_TIMEOUT_MS = 5_000`; stuck encode fences/quarantines borrowed resources and closes/discards only after encode returns or cancellation wins before start.
- `PreparedEs2RenderingReadbackResources` no longer exposes raw readback storage through metadata. `readbackBuffer` is a zero-capacity read-only sentinel; raw RGBA storage is reachable only through the exclusive `RgbaReadbackLease`.
- The former test seam blocker is closed: `InitialActivationCommitBoundaryTest` no longer retains or exposes live `PreparedEs2RenderingReadbackResources` or `RgbaReadbackLease` after active ownership transfer. Tests use narrow active-owner controls/probes and still assert public/product outcomes.
- Limited cleanup is complete: forced-readback-busy test paths reset via `finally`, and the encoder-owned readback test probe no longer performs unconditional successful-frame hot-path atomic writes. The replacement observer is internal, nullable by default, boolean-only, and does not retain resource bags or leases.
- `RuntimeBoundaryStaticGuardTest` remains supplemental source/regex protection for boundary ownership, GLES usage, and public accounting bypasses. Product/logical tests remain the primary proof.

## Validation

- Full validation passed before the final limited cleanup: targeted critical suites, full `:screencaptureengine:testDebugUnitTest --rerun-tasks`, `:screencaptureengine:compileDebugKotlin --rerun-tasks`, `git diff --check`, reflection scan, and Android Studio MCP `build_project`/`get_file_problems` over changed/new Kotlin files.
- `RuntimeEs2FrameProductionTest` passed in later independent runs; earlier `NoClassDefFoundError` / Gradle result-store failures are classified as local Gradle/Robolectric flakiness, not a product/test dependency blocker.
- Limited post-cleanup validation passed:
  - `./gradlew :screencaptureengine:testDebugUnitTest --tests 'dev.dmkr.screencaptureengine.internal.lifecycle.InitialActivationCommitBoundaryTest' --rerun-tasks`
  - `./gradlew :screencaptureengine:testDebugUnitTest --tests 'dev.dmkr.screencaptureengine.internal.lifecycle.RuntimeBoundaryStaticGuardTest' --rerun-tasks`
  - `./gradlew :screencaptureengine:compileDebugKotlin --rerun-tasks`
  - focused reflection scan over `screencaptureengine/src/main/java` and `screencaptureengine/src/test/java`
  - `git diff --check`
- No prohibited Java/Kotlin member reflection remains in production or tests. Remaining class literals/callable references are ordinary JUnit/Robolectric/assertion/static-guard usage.
- No active sub-agents remain for this slice.

## Deferred Risks

- JVM/Robolectric/unit validation does not prove real-device GLES/OES driver behavior, actual `SurfaceTexture` runtime behavior, real `MediaProjection` resize/stop behavior, real readback/encode/frame publication, release variants, instrumentation tests, lint, app flavor builds, or memory pressure under realistic dimensions.
- Provider reservation before ES2/direct-buffer allocation remains deferred. A non-binding capacity check would be racy; a true reservation requires a new internal reservation contract with release semantics across ES2 failure, transform failure, timeout, cancellation, and stale token cases.
- Provider-context ownership is still an integration contract. The future engine/session owner that creates `ProviderPreparationContext` must outlive all `PreparedImageEncoderResources` that may use it for cleanup, and must close it after those resources are closed.
- Cleanup executors currently rely on single-thread executor queues if cleanup itself blocks. This is off caller-critical paths and acceptable for current pre-public slices, but high-churn runtime integration needs an ownership/backpressure policy.
- Retained framework JPEG memory is high by design for the correctness-first baseline: retained `Bitmap` ARGB_8888 plus retained `IntArray` scratch, about 8 bytes/pixel before overhead.

## Later Slices

- Lazy non-throwing built-in `CaptureMetricsProviders.*` after the internal caller-supplied-metrics factory milestone.
- Full runtime `setParameters(...)` prepare/validate/commit/rollback transactions.
- Provider/backend fallback policy after repeated runtime failures and fallback exhaustion behavior beyond the first ES2/JPEG path.
- Native JPEG provider path.
- PBO/ES3 readback path.
- Device/emulator validation for the runtime production path: real `SurfaceTexture`, EGL/OES sampling, `glReadPixels`, MediaProjection resize/stop behavior, orientation, stop races, framework JPEG output, and memory pressure under realistic dimensions.

## Next Slice Contract

- Selected slice label: Internal caller-supplied-metrics default-engine factory wiring milestone.
- Source of truth: v34. Prior planning questions are answered and should not be reopened unless implementation exposes a new contradiction.
- Goal: add `ScreenCaptureEngines.create(context)` and a concrete default `ScreenCaptureEngine.startSession(...)` that wires the existing startup transaction, pre-active owner, ES2 rendering/readback preparation, framework JPEG provider path, initial runtime resource owner, `RuntimeFrameLoopInstalled`, and `InitialActivePlanCommitted` return path.
- Status boundary: this is an internal milestone, not release/design-complete default engine behavior.

### In Scope

- Public factory object/function: `ScreenCaptureEngines.create(context): ScreenCaptureEngine`.
- Store only application-safe context state, preferably application context. Do not retain an `Activity`-lifetime object.
- Factory creation must be lazy: no capture start, metrics attach, projection consent, `MediaProjection` creation, GL allocation, encoder allocation, virtual display work, or listener registration.
- Default engine supports caller-supplied `CaptureMetricsProvider` only.
- One default `ScreenCaptureEngine` instance admits at most one non-terminal returned session.
- A second `startSession(...)` while the same engine already has a non-terminal returned session must fail before projection attachment/consumption with `ScreenCaptureStartException(requiresFreshProjection = false, problem.kind = EngineSessionAlreadyActive)`.
- Implementation must reserve the engine session slot before any projection callback attachment or projection consumption, so a concurrent startup-in-progress also blocks another `startSession(...)`. The slot is released on startup failure/caller cancellation before public commit, and after a returned session reaches any logical terminal outcome, including external projection stop/failure, not only owner `stop()`/`close()`.
- Sequential sessions on the same engine are allowed after the previous session reaches a logical terminal boundary. Heavy asynchronous cleanup may still run, but old resources must remain fenced from new startup.
- Add/implement v34 problem kinds:
  - `EngineSessionAlreadyActive`
  - `ParameterUpdateUnavailable`
- Temporary `setParameters(...)` behavior for this milestone: deterministic `ScreenCaptureParameterUpdateResult.Rejected(problem.kind = ParameterUpdateUnavailable)`, no state mutation, no partial plan, diagnostic text clearly says parameter updates are disabled in this internal milestone.
- Provider preparation/cleanup ownership: `ProviderPreparationContext` is an engine-scoped service with session-scoped records. It must be lazily created, bounded-admission, per-engine guarded, and must not be closed by a local `startSession(...) finally` after successful startup.
- Provider-preparation service may internally idle-shutdown only when there is no active session, no pending provider work, no cleanup task, and no quarantined worker. No public engine `close()` is added; tests may use internal close hooks.
- Preserve startup/commit semantics: no public session before `InitialActivePlanCommitted`; no suspension, dispatcher hop, GL/readback/encode work, or blocking cleanup on the commit-return path.
- Return the owner-backed public session from `ActiveRuntimeOwner`, not raw `ScreenCaptureSessionCore`.
- Keep public state, stats, diagnostic events, frame drops, visibility, output changes, and parameter-update results centralized through `ScreenCaptureSessionCore`.

### Out Of Scope

- Built-in `CaptureMetricsProviders.*`; they remain a separate next slice and are required before design-complete public release.
- Full runtime `setParameters(...)` prepare/validate/commit/rollback transactions.
- Runtime geometry/density re-plan, `VirtualDisplay.resize(...)` replacement transactions, provider/backend fallback policy beyond current first path, ES3/PBO, native JPEG, transport/app/flavor integration, and real-device validation as a commit blocker.
- Public engine `close()` API.
- Marketing/documenting this milestone as default-engine complete, production default engine, or release-ready factory.

### Implementation Notes

- Prefer a small internal `DefaultScreenCaptureEngine` rather than a public builder or parallel engine framework.
- Keep orchestration sequential and ownership-transfer based: startup resources -> pre-active owner -> prepared initial active plan -> initial runtime resource owner -> active runtime owner -> returned owner-backed session.
- Use narrow injectable internal seams for tests instead of reflection or new mocking frameworks.
- Calls from engine-owned contexts must fail fast where needed to avoid deadlocks.
- Existing `CaptureMetricsProviders.*` throwing placeholders are acceptable only because this is an internal milestone; design-complete public artifacts must not expose unimplemented built-in provider stubs.

### Required Tests And Validation

- Product/logical tests:
  - factory is lazy and stores application-safe context;
  - successful public `startSession(...)` handoff reaches initial `Running(Active)`;
  - caller-supplied metrics are observed per session;
  - concurrent second `startSession(...)` on the same engine rejects before projection attachment/consumption with `EngineSessionAlreadyActive` and `requiresFreshProjection = false`, including while the first startup is still in progress;
  - sequential sessions after terminal boundary are admitted while old cleanup remains fenced;
  - engine session slot is released on startup failure/cancellation and on every returned-session terminal path, including external projection stop;
  - `ProviderPreparationContext` survives successful startup and remains available for late provider result cleanup / late `ImageEncoder.close()`;
  - milestone `setParameters(...)` rejection uses `ParameterUpdateUnavailable` and does not mutate state;
  - cancellation/failure rollback mapping and `requiresFreshProjection` semantics remain intact;
  - post-commit return semantics and idempotent session `stop()`/`close()` remain intact.
- Impacted existing suites: startup geometry, startup-to-runtime handoff, initial activation boundary, ES2 pipeline/runtime frame production, encoder provider preparation, JPEG Robolectric path, session core, runtime/rendering static guards.
- Static/source guards remain supplemental only: no reflection, no pre-commit frame consumption/GL readback/encode, no public state bypass, no accidental built-in metrics dependency.
- Final validation should include focused new tests, impacted targeted suites, full `:screencaptureengine:testDebugUnitTest --rerun-tasks`, `:screencaptureengine:compileDebugKotlin --rerun-tasks`, `git diff --check`, reflection scan, and Android Studio MCP file-problem/build checks.

### Later Slices After This Milestone

- Built-in `CaptureMetricsProviders.*`: non-throwing, lazy, lifecycle-safe, Activity/window/display-aware where appropriate, latest-value/conflated rather than event-queue based.
- Full runtime `setParameters(...)` transactions.
- Real-device smoke validation before app/transport integration treats the default engine as stable.
- Full real-device/instrumented validation matrix before design-complete public release.

### Planning Verification

- v34 diff and designer answers were reviewed against the current code. No further designer questions remain for this slice.
- Control review confirmed the selected slice is handoff-ready after these clarifications: source of truth is v34, old designer-question draft is obsolete, `EngineSessionAlreadyActive` and `ParameterUpdateUnavailable` are required code additions, disabled `setParameters(...)` must stop using `OutputPlanInvalid`, and the engine session slot must cover startup-in-progress as an implementation guard.

## Durable Decisions

- Runtime placeholder public APIs fail with ordinary exceptions, not `Error` types.
- Public immutable value/state models use manual `equals`/`hashCode`; do not convert them to `data class` by default.
- Keep validation constants private/local unless actual drift or repeated update burden justifies a shared abstraction.
- Kotlin has no standard checked integer arithmetic equivalent to JVM `Math.addExact` / `Math.multiplyExact`; keep those APIs where checked overflow is required unless a clearer project-local abstraction becomes justified.
- Configured caller-owned dispatchers are reached through an engine-owned callback bridge so custom direct/immediate dispatchers cannot run callbacks on the single delivery coordinator worker or producer-critical callers.
- Subscription stats snapshots from the delivery coordinator are versioned; the session core ignores older snapshots so races cannot roll public counters backward.
- `ImageEncoder` remains an internal/pre-public contract until the next public activation slice exposes a stable runtime surface.
- `JpegImageEncoderProvider` built-in framework implementation is the v34 correctness-first baseline; native JPEG is not active yet.

## Design Deviations

- None.
