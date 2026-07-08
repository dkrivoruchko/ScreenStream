# ScreenCaptureEngine Implementation Tracker

## Source Of Truth

- Design: `screen-capture-engine-design-v35.md`.
- Earlier design versions are superseded and must not be used for new implementation decisions.
- The design file controls implementation unless an approved deviation is recorded here.
- User-facing coordination is in Russian. Code, KDoc, comments, tests, commit text, and project documentation are in English.
- Code documentation describes the current contract directly; it must not reference design document versions, implementation phases, tracker phases, roadmap, or process.

## Standing Rules

- Work for this slice is restricted to `screencaptureengine`. Do not inspect, edit, validate, or delegate work in `app`, `common`, `mjpeg`, `rtsp`, `webrtc`, or other modules unless the user explicitly changes scope.
- Main agent acts as facilitator/coordinator only: it owns this tracker, decomposes and reviews work, delegates implementation/review/validation to fresh sub-agents, maximizes safe parallelism, and does not directly edit production or test code unless the user explicitly overrides this rule. Direct tracker updates remain the main agent's responsibility.
- Sub-agents do bounded work only, start fresh without forked parent conversation context, do not spawn sub-agents, and complete at least two explicit self-review passes.
- Default sub-agent policy: start fresh independent sub-agents for every reasonable implementation, review, and validation lane. Reuse an existing sub-agent only when that agent's already-loaded local context is directly useful for a follow-up fix or refinement in the same owned area. All rechecks and validation lanes must use fresh independent sub-agents.
- Use the available sub-agent slots aggressively but sensibly; up to six parallel sub-agents may be active when the work can be split into independent scopes.
- Every sub-agent must read the relevant current official or primary guides/best-practice sources for its domain before changing, reviewing, or validating code.
- Material sub-agent results must be recorded here before closing the sub-agent.
- Do not touch git staging/index. The user controls staging before commits.
- Runtime code must not publish parallel public state/flows/counters. Public state, stats, diagnostic events, frame drops, visibility changes, geometry transitions, and output-plan transitions go through `ScreenCaptureSessionCore`.
- Reflection is prohibited in production implementation and tests for this slice. If reflection appears necessary, stop and report it as a design/testability problem.
- Current approved JVM test setup in `screencaptureengine/build.gradle.kts`: JUnit `4.13.2`, `kotlinx-coroutines-test` `1.11.0`, and Robolectric `4.16.1`, approved as latest stable on 2026-07-06. Do not add MockK/Mockito, JUnit 5, coverage tooling, instrumentation tests, Android runner, `returnDefaultValues`, or Android resources by default.
- Validate with sequential Gradle tasks for this module; avoid concurrent Gradle/Kotlin compile runs. When final validation matters, force actual targeted test execution instead of accepting only `UP-TO-DATE` output.
- If a sub-agent or fix loop hits the same blocker or failure repeatedly, do not allow more than two or three blind retries. Step back, reassess architecture/approach, and if that does not resolve the issue, stop that path and escalate the decision to the user with the blocker and options.

## Current Slice

- Label: internal caller-supplied-metrics default-engine factory wiring.
- Status: implemented, reviewed, and validated for JVM/Robolectric/module scope.
- Scope: `screencaptureengine` only.
- No active sub-agents remain.

### Implemented Contract

- Added `ScreenCaptureEngines.create(context): ScreenCaptureEngine`.
- The factory is lazy: it does not start capture, attach metrics, consume projection consent, create `MediaProjection`, allocate GL/encoder resources, create a virtual display, or register listeners at factory creation.
- The default engine stores application-safe context state and does not retain an Activity-lifetime context.
- The default engine supports caller-supplied `CaptureMetricsProvider` through `ScreenCaptureConfig.metricsProvider` only. Built-in `CaptureMetricsProviders.*` remain deferred to a later slice.
- `DefaultScreenCaptureEngine.startSession(...)` wires the existing startup transaction, pre-active owner, ES2 rendering/readback preparation, framework JPEG provider path, initial runtime resource owner, `RuntimeFrameLoopInstalled`, and `InitialActivePlanCommitted` return path.
- A single engine instance admits at most one startup/active session slot. Concurrent second `startSession(...)` is rejected before projection callback attachment or projection consumption with `ScreenCaptureStartException(requiresFreshProjection = false, problem.kind = EngineSessionAlreadyActive)`.
- The engine session slot is reserved before projection callback attachment/consumption, released on startup failure/caller cancellation before public commit, and released after a returned session reaches any logical terminal outcome, including external projection stop/failure.
- Sequential sessions on the same engine are allowed after the previous session reaches a logical terminal boundary while old heavy cleanup remains fenced from new startup.
- `ProviderPreparationContext` is engine-scoped, lazy, bounded-admission, and survives successful startup for late provider-result cleanup and late `ImageEncoder.close()`. It is not closed by a local successful `startSession(...) finally`.
- Provider-context idle shutdown is fenced by active/startup slot state, tracked provider work, tracked cleanup tasks, terminal cleanup fences, and quarantined/reserved workers.
- No public session is returned before `InitialActivePlanCommitted`; the commit-return path performs no GL/readback/encode work and no blocking cleanup.
- The returned public session is owner-backed through `ActiveRuntimeOwner`; public state/stats/events/accounting continue through `ScreenCaptureSessionCore`.
- Added problem kinds `EngineSessionAlreadyActive` and `ParameterUpdateUnavailable`.
- `ScreenCaptureSession.setParameters(...)` deterministically rejects with `ParameterUpdateUnavailable`, no state mutation, no partial plan, and neutral public diagnostic wording.
- Public and internal documentation was updated for the current contract: factory/session slot, caller-supplied metrics, problem kinds, provider-context lifetime, and forbidden temporal/process wording cleanup.

### Important Fixes

- Fixed provider cleanup and terminal cleanup fence lifetime so sequential sessions are admitted after logical terminal while old cleanup remains fenced.
- Fixed repeated public `stop()`/`close()` while runtime cleanup is deferred: repeated close no longer releases the terminal cleanup fence before deferred cleanup is scheduled/handed off.
- Strengthened `InitialActivationCommitBoundaryTest.terminalCleanupFenceSurvivesUntilDeferredProviderBackedCleanupIsScheduled` to repeat `session.close()` in the deferred-cleanup window.
- Removed stale `InitialRuntimeResourceOwner` internal observability accessors that had no current module-local read sites.
- Cleaned JetBrains MCP actionable warnings within `screencaptureengine`, while retaining intentional internal test seams such as `ActiveRuntimeOwner` testing hooks.
- Updated tests using real dispatchers where provider-backed preparation/cleanup must not race `runTest` virtual time.

## Final Validation

Final validation completed clean after all runtime, cleanup, and documentation updates:

- `./gradlew :screencaptureengine:testDebugUnitTest --tests 'dev.dmkr.screencaptureengine.internal.lifecycle.InitialActivationCommitBoundaryTest.terminalCleanupFenceSurvivesUntilDeferredProviderBackedCleanupIsScheduled' --rerun-tasks`: 1 test, 0 failures.
- `./gradlew :screencaptureengine:testDebugUnitTest --tests 'dev.dmkr.screencaptureengine.internal.lifecycle.*' --rerun-tasks`: 130 tests, 0 failures.
- `./gradlew :screencaptureengine:testDebugUnitTest --tests 'dev.dmkr.screencaptureengine.internal.DefaultScreenCaptureEngineTest' --rerun-tasks`: 17 tests, 0 failures.
- `./gradlew :screencaptureengine:testDebugUnitTest --rerun-tasks`: 407 tests, 0 failures.
- `./gradlew :screencaptureengine:compileDebugKotlin --rerun-tasks`: passed.
- `git diff --check --cached -- screencaptureengine`: passed.
- `git diff --check -- screencaptureengine`: passed.
- JetBrains MCP `get_file_problems(errorsOnly=false)` on recently changed files: no errors. Remaining warnings are `ActiveRuntimeOwner.kt` unused testing hook warnings retained as intentional internal test seams.
- Strict reflection scan under `screencaptureengine/src/main/java` and `screencaptureengine/src/test/java`: no prohibited reflection; remaining class literals are ordinary test assertions.
- Public wording scan under public `screencaptureengine` sources: no forbidden wording hits for `milestone`, `tracker`, `phase`, `currently`, `production-ready`, `release-ready`, `placeholder`, or `disabled`.

## New Files

- New module files in this slice:
  - `screencaptureengine/src/main/java/dev/dmkr/screencaptureengine/ScreenCaptureEngines.kt`
  - `screencaptureengine/src/main/java/dev/dmkr/screencaptureengine/internal/DefaultScreenCaptureEngine.kt`
  - `screencaptureengine/src/test/java/dev/dmkr/screencaptureengine/internal/DefaultScreenCaptureEngineTest.kt`

## Deferred Risks

- JVM/Robolectric/unit validation does not prove real-device GLES/OES driver behavior, actual `SurfaceTexture` runtime behavior, real `MediaProjection` resize/stop behavior, real readback/encode/frame publication, release variants, instrumentation tests, lint, app flavor builds, or memory pressure under realistic dimensions.
- Built-in non-throwing `CaptureMetricsProviders.*` remain deferred and are required before design-complete public release.
- Full runtime `setParameters(...)` prepare/validate/commit/rollback transactions remain deferred.
- Runtime geometry/density re-plan, `VirtualDisplay.resize(...)` replacement transactions, provider/backend fallback policy beyond the current first path, ES3/PBO, native JPEG, transport/app/flavor integration, and real-device validation remain out of scope for this slice.
- Provider reservation before ES2/direct-buffer allocation remains deferred. A true reservation needs an internal reservation contract with release semantics across ES2 failure, transform failure, timeout, cancellation, and stale token cases.
- Cleanup executors currently rely on single-thread executor queues if cleanup itself blocks. This is off caller-critical paths and acceptable for this slice, but high-churn runtime integration needs an ownership/backpressure policy.
- Retained framework JPEG memory is high by design for the correctness-first baseline: retained `Bitmap` ARGB_8888 plus retained `IntArray` scratch, about 8 bytes/pixel before overhead.

## Later Slices

- Built-in `CaptureMetricsProviders.*`: non-throwing, lazy, lifecycle-safe, Activity/window/display-aware where appropriate, latest-value/conflated rather than event-queue based.
- Full runtime `setParameters(...)` transactions.
- Provider/backend fallback policy after repeated runtime failures and fallback exhaustion behavior beyond the first ES2/JPEG path.
- Native JPEG provider path.
- PBO/ES3 readback path.
- Real-device smoke validation before app/transport integration treats the default engine as stable.
- Full real-device/instrumented validation matrix before design-complete public release.

## Next Slice Planning

### Selected Next Slice

- Slice: built-in `CaptureMetricsProviders.*` MVP.
- Size: medium.
- Status: planned after design v35 update and two independent read-only technical checks.
- Rationale: this is the direct next API gap after the caller-supplied-metrics factory slice. The default engine can start only when callers provide their own metrics provider; the built-in factory methods still need real non-throwing, lazy, latest-valid implementations.
- Design boundary: this slice makes built-in metrics-provider factories usable. It does not make the default engine release-complete.

### V35 Decisions For This Slice

- Built-in factory calls are non-throwing convenience constructors and must not register long-lived listeners at construction.
- `CaptureMetrics` has no unavailable state. Built-ins must not emit fake `0x0`, `1x1`, hidden sentinel, or invalid metrics.
- Providers emit only positive valid metrics, suppress invalid or unavailable runtime observations, and keep the last valid metrics.
- Session startup fails with `MetricsUnavailableOrInvalid` before projection consumption when no valid current or allowed fallback metrics exists.
- `fromActivity(activity)` uses current Activity window bounds. It is Activity/window scoped, may strongly retain the Activity, and must be documented as not safe to keep as an app-lifetime object.
- `fromUiContext(context)` uses current UI/window-context bounds when the context is visual/window-associated; non-UI contexts delegate to `bestEffort(context)` and document lower precision.
- `fromDisplay(baseContext, display)` uses maximum/display-area bounds for that display, may fallback to display-context resource metrics for that display, and must not fallback to an unrelated default display.
- If a `fromDisplay(...)` target display is removed or invalid during an active session, the provider keeps the last valid metrics and suppresses invalid changes. Future startup for the removed/invalid display fails with `MetricsUnavailableOrInvalid`.
- `bestEffort(context)` uses maximum/default-display bounds when available and falls back to application/resource metrics with documented lower precision.
- Valid runtime geometry/density changes are authoritative and should be emitted immediately. If live runtime replan is unavailable, the engine may transition to `Running(output = Suspended(...))`.
- Required public documentation wording:
  `Built-in CaptureMetricsProviders are available as lazy convenience sources for bootstrap size and density. They do not by themselves make the default engine release-complete. Runtime setParameters transactions, live resize/replan, backend fallback policy, transport integration, and real-device validation remain separate completion gates.`

### Recommended Scope

In scope:

- Replace throwing built-in `CaptureMetricsProviders.*` stubs with non-throwing providers for:
  - `fromActivity(activity)`
  - `fromUiContext(context)`
  - `fromDisplay(baseContext, display)`
  - `bestEffort(context)`
- Provider construction stays lazy and listener-free.
- Platform listeners attach only through the existing engine/provider attachment hook and detach when the last session attachment is disposed.
- Providers expose latest-value/conflated `StateFlow<CaptureMetrics>`, publish only valid positive metrics, and suppress invalid platform snapshots while keeping the last valid value.
- Add source-specific attach-time validation so stale Activity/display cases cannot reuse an old `metrics.value` for a future startup.
- Add a metrics-change wake bridge so built-in provider changes schedule runtime processing even when no frame, resize, or periodic signal arrives.
- Update KDoc to describe factory source, lifetime, fallback, and release-completion boundaries.
- Add focused JVM/Robolectric tests for construction, initial metrics, attach/detach, listener ref-counting, latest-value updates, invalid snapshot handling, observation wakeup, startup failure, runtime suspension, static guards, and public wording.

Out of scope:

- Runtime `setParameters(...)` transactions.
- `VirtualDisplay.resize(...)`, `setSurface(...)`, runtime target replacement, output resume/replan, or geometry-suspension recovery.
- Runtime hard-failure/fallback counters.
- Native JPEG, ES3/PBO, backend selection.
- App/transport/flavor integration.
- Instrumented/real-device validation beyond a documented later validation need.

### Proposed Technical Shape

- Add internal provider implementation under `internal/platform/metrics`, likely:
  - `AndroidCaptureMetricsProvider : EngineAttachableCaptureMetricsProvider`
  - `MetricsSource` with `readCurrent(): CaptureMetrics?` and `attach(onChanged: () -> Unit): DisposableHandle`
  - source implementations for Activity/window, UI context, display context, best-effort context, and resource fallback.
- Use AndroidX WindowManager already available in the module for `WindowMetricsCalculator` where possible, to avoid a broad API 24-37 platform branch matrix.
- Use application context strongly where possible. `fromActivity(activity)` is intentionally Activity/window scoped and may strongly retain the Activity.
- Use `DisplayManager.DisplayListener` and `ComponentCallbacks` only while attached. Prefer main-looper callback delivery and fast, non-blocking recomputation.
- Data flow: platform callback -> metrics source read -> positive-metrics validation -> provider `MutableStateFlow` update -> `CaptureMetricsObservation` latest snapshot -> runtime wake callback -> current runtime geometry handling.
- Runtime behavior for changed metrics remains the current no-replan behavior: it may suspend output when geometry/density changes require reconfiguration.
- `CaptureMetricsObservation` must pass a real metrics-change callback to built-in attachment; the current empty callback shape is insufficient.
- Static boundary guards must classify any new internal metrics files without allowing runtime/default-engine internals to depend on public convenience factories.
- `InvalidMetricsIgnored` emission for suppressed invalid source snapshots needs an internal diagnostic route when a public session exists; it must not require invalid public `CaptureMetrics` values.

### Validation Plan

- New/updated provider tests for all factories.
- `CaptureMetricsObservationTest` for attachment disposal, update callback/wakeup, collection cancellation, and late-update ignore.
- Startup tests for `MetricsUnavailableOrInvalid` before projection consumption when no valid current or allowed fallback metrics exists.
- Lifecycle tests around stale Activity/display validation and runtime pending metrics suspension.
- Default-engine smoke tests using built-in provider factories.
- Static guard tests for new internal metrics files.
- Full `:screencaptureengine:testDebugUnitTest`.
- `:screencaptureengine:compileDebugKotlin`.
- `git diff --check -- screencaptureengine`.
- Public KDoc/wording scan for stale unavailable wording.

### Risks, Blockers, And Follow-Up

- No known design blocker remains for the selected slice except the `StateFlow<CaptureMetrics>` initial-value edge below.
- `CaptureMetricsProvider.metrics` is `StateFlow<CaptureMetrics>`, while factory construction is non-throwing and fake metrics are forbidden. Implementation must either obtain a valid positive initial snapshot from the factory-specific source or an allowed fallback, or this exact edge must be clarified before code changes.
- Follow-up question for design if initial snapshot cannot be guaranteed: for `fromActivity(activity)`, what should the initial `StateFlow<CaptureMetrics>` contain when current Activity/window metrics are unavailable at construction, the factory must be non-throwing, fake metrics are forbidden, and fallback to unrelated app/default-display metrics is not allowed?
- Robolectric can validate construction, lifecycle, source policy, and conflation behavior, but not real multi-window, foldable, external-display, Android 14+ app-window projection, or real-device display invalidation behavior.
- `SessionFailed` was added by v35 as a diagnostic event type for runtime hard-failure exhaustion. It is a separate later-slice design drift and must not be bundled into the metrics-provider MVP unless implementation touches that diagnostics surface.

### Independent Read-Only Planning Checks

- Two fresh sub-agents independently checked v35, the designer answers, and current `screencaptureengine` code. Neither edited files or git state.
- Both confirmed the selected next slice remains built-in `CaptureMetricsProviders.*` MVP, with a wider scope than just replacing throwing stubs: source policy, attach-time current-source validation, runtime metrics wakeup, KDoc cleanup, static guard updates, and focused tests are required.
- Both found the same primary technical integration gap: current built-in attachment callback handling does not wake runtime on metrics changes.
- Both identified the same public-contract edge: `StateFlow<CaptureMetrics>` always requires an initial valid value, but v35 forbids throwing, fake metrics, and invalid/unavailable `CaptureMetrics`.

## Durable Decisions

- Runtime not-yet-implemented public APIs fail with ordinary exceptions, not `Error` types.
- Public immutable value/state models use manual `equals`/`hashCode`; do not convert them to `data class` by default.
- Keep validation constants private/local unless actual drift or repeated update burden justifies a shared abstraction.
- Kotlin has no standard checked integer arithmetic equivalent to JVM `Math.addExact` / `Math.multiplyExact`; keep those APIs where checked overflow is required unless a clearer project-local abstraction becomes justified.
- Configured caller-owned dispatchers are reached through an engine-owned callback bridge so custom direct/immediate dispatchers cannot run callbacks on the single delivery coordinator worker or producer-critical callers.
- Subscription stats snapshots from the delivery coordinator are versioned; the session core ignores older snapshots so races cannot roll public counters backward.
- `ImageEncoder` remains an internal/pre-public contract until a later public activation slice exposes a stable runtime surface.
- `JpegImageEncoderProvider` built-in framework implementation is the correctness-first baseline; native JPEG is not active yet.

## Design Deviations

- None.
