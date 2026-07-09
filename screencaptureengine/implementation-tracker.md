# ScreenCaptureEngine Implementation Tracker

## Source Of Truth

- Design: `screen-capture-engine-design-v37.md`.
- Earlier design versions are superseded and must not be used for implementation decisions.
- Design files are read-only unless the user explicitly says to edit them.
- Current planned implementation scope is strictly `screencaptureengine`.
- User-facing coordination is in Russian. Code, KDoc, comments, tests, commit text, and project documentation are in English.
- Public code documentation describes the current API contract directly and must not mention design versions, tracker phases, roadmap, or process.

## Standing Rules

- Main agent is facilitator/coordinator only: owns this tracker, delegates implementation/review/validation, records material results, and does not edit production/test code unless explicitly overridden by the user.
- Direct main-agent edits are limited to this tracker.
- Sub-agents do bounded work only, start fresh without forked parent conversation context, do not spawn sub-agents, and complete at least two explicit self-review passes.
- Reuse an existing sub-agent only when its current context is directly useful for a small follow-up fix in the same owned area. Fresh agents are mandatory for independent review and validation.
- Use up to six sub-agent slots when there is independent useful work.
- Sub-agents must read current official/primary docs before implementation/review/validation. Reports must list exact official/primary URLs opened, sections/APIs used, and what decision each source supported.
- Material sub-agent results must be recorded here before closing the sub-agent.
- Do not touch git staging/index. The user controls staging.
- Runtime code must not publish parallel public state/flows/counters. Public state, stats, diagnostic events, frame drops, visibility changes, geometry transitions, and output-plan transitions go through `ScreenCaptureSessionCore`.
- Reflection is prohibited in production implementation and tests for this slice.
- Approved JVM test setup remains JUnit `4.13.2`, `kotlinx-coroutines-test` `1.11.0`, and Robolectric `4.16.1`.
- Gradle/Kotlin validation must run sequentially, never concurrently.

## Current Baseline

- Last closed slice: built-in `CaptureMetricsProviders.*` MVP.
- Status: **closed**.
- Completion level: implementation, reviews, and JVM/Robolectric module validation are complete.
- Code/test blockers: none known.
- Active sub-agents: none.

## Next Slice

- Title: `Runtime setParameters same-target transaction MVP`.
- Size: large.
- Status: **planned and ready for implementation handoff against v37**.
- Design basis: v37 normatively defines this slice as release-facing behavior.

## Next Slice Scope

- Must implement all v37 `Running(Active)` same-target update classes:
  - normalized no-op;
  - frame-rate-only lightweight transaction;
  - provider-only same-target update;
  - full same-target output-plan replacement.
- Same-target means the candidate plan reuses the current generation-owned projection target and current `VirtualDisplay` target assignment:
  - same target width, height, density, and generation semantics;
  - no `VirtualDisplay.resize(...)`;
  - no `VirtualDisplay.setSurface(...)`;
  - no `SurfaceTexture.setDefaultBufferSize(...)`;
  - no new target `Surface`, `SurfaceTexture`, OES target, or target generation replacement;
  - no live geometry replan.
- Out of scope:
  - runtime retarget/live resize/replan;
  - `Running(Suspended)` recovery;
  - repeated hard-failure policy expansion;
  - ES3/PBO;
  - native JPEG;
  - app/transport/flavor integration;
  - real-device/instrumented validation.
- Optional/stretch only:
  - extra best-effort diagnostic events beyond required result/state correctness;
  - resource reuse optimizations for full same-target replacement when shapes match.

## Required Semantics

- Normalized no-op:
  - returns `Applied`;
  - no output generation bump;
  - no state/event/resource/counter/scheduler mutation;
  - comparison is against normalized/effective plan and provider identity/configuration, not raw requested object identity.
- Frame-rate-only:
  - uses a lightweight token-checked transaction;
  - skips GL/readback/provider/encoder preparation;
  - bumps output generation for non-noop update;
  - fences old materialized work as stale;
  - resets frame pacing state.
- Provider-only same-target:
  - allowed when projection target, final output dimensions, readback shape, row stride, `maxEncodedBytes`, and `ImageEncoderInputFormat` are unchanged;
  - candidate provider/encoder is fully prepared and validated under the transaction token before commit;
  - previous active plan remains in use on preparation/validation failure, timeout, stale late result, or terminal-before-commit.
- Full same-target output-plan replacement:
  - prepares candidate GL/readback/render-transform/encoder resources before commit;
  - does not consume frames, call `updateTexImage`, encode, publish frames, mutate public state, or replace active resources before commit.
- Valid retarget/live-replan/suspended-recovery requests return `Rejected(ParameterUpdateUnavailable)`.
- Invalid logical requests return precise planning/validation problems such as `OutputPlanInvalid`, `OutputLimitsExceeded`, `EncodedSizeLimitExceeded`, or `EncoderValidationFailed`.
- `Running(Suspended)` updates return `ParameterUpdateUnavailable`; no ad hoc suspended recovery in this slice.
- `ScreenCaptureParameterUpdateResult.Rejected(problem)` is the mandatory public rejection signal. `OutputPlanRejected` is best-effort only for useful non-trivial preparation/rollback/runtime diagnostics.
- If commit wins and public active state is published, caller receives `Applied` even if stop/projection-stop/fatal terminal transition arrives immediately afterward.
- Old work materialized under the previous output generation and completing after a non-noop commit is stale-generation work, normally `droppedFrames.byStaleGeneration`; it is not `byOutputSuspended`, transient failure, readback failure, encode failure, or hard failure.

## Implementation Handoff

- First task for the next implementation agent:
  - perform lock-order audit/refactor and add focused race/deadlock tests around `setParameters` commit vs `stop()`/`close()`/projection stop;
  - do this before enabling any real parameter-update behavior.
- Commit/lock ordering:
  - public `setParameters` serialization must become a short transaction-slot reservation, not a lock held across preparation;
  - heavy work runs outside `parameterMutex`, `ScreenCaptureSessionCore.sessionGate`, and `ActiveRuntimeOwner.lock`;
  - canonical commit order is `ActiveRuntimeOwner.lock -> ScreenCaptureSessionCore` gate;
  - no code path should acquire owner lock from inside a core-held gate;
  - commit bridge is runtime-specific, non-suspending, and performs no provider code, GL work, platform retarget calls, encoder close, cleanup, dispatcher hop, blocking call, or cancellable suspension after commit wins.
- Runtime transaction ownership:
  - active committed owner: current requested/effective parameters, current `ScreenCaptureOutputPlan`, output generation, active prepared resources, encoded scratch, retained periodic-frame state;
  - candidate transaction owner: `PlanPreparationToken`, base target/output generations, candidate plan, candidate resources or lightweight pacing policy, candidate scratch if shape/cap changes;
  - retired owner: old active resources closed asynchronously after materialized render/readback/encode leases drain or are fenced stale.
- Provider-only resource ownership:
  - provider-only transactions must have a first-class encoder-only candidate/retired owner;
  - provider-only updates must not allocate, replace, or close GL/readback/transform resources;
  - render/readback/transform ownership stays installed while candidate encoder/provider is prepared and validated;
  - old encoder resources retire only after active encode ownership is fenced/drained or safely abandoned.
- Preparation/refactor:
  - introduce or expose neutral v37 concepts: `OutputPlanPreparation`, `PlanRenderingAccess`, `RenderTransformPackage(planGeneration)`, and `OutputPlanPrepared`-style resource bag;
  - keep startup wrappers/adapters so startup flow is not rewritten broadly;
  - do not build a parallel runtime ES2 stack.
- Classification:
  - plan requested parameters against current active capture geometry;
  - planner failure returns the precise planning problem;
  - successful plan that violates same-target predicate returns `ParameterUpdateUnavailable`.
- Commit bridge:
  - prepare outside locks;
  - acquire owner lock;
  - pause producer admission before new old-generation work can materialize;
  - enter core commit gate;
  - recheck terminal/current generation/current target;
  - install candidate resources or lightweight policy;
  - bump output generation for non-noop;
  - update stored requested/effective parameters and output plan;
  - clear retained periodic/latest assumptions for non-noop;
  - reset frame-rate admit state for non-noop;
  - publish `Running(Active)` through `ScreenCaptureSessionCore`;
  - emit only non-blocking best-effort diagnostics;
  - release gates;
  - enable scheduling for the new generation;
  - retire old resources asynchronously.
- Rollback:
  - candidate token invalidates before rollback/terminal cleanup;
  - candidate resources are closed/discarded without mutating public state;
  - late provider/resource successes are routed to stale cleanup;
  - previous active plan remains usable unless terminal cleanup wins.
- Production pause/admission:
  - checked before draining/admitting latest frame signals; pause must block `admitLatestFrameSignal()` so conflated source signals are not consumed and lost during a transaction;
  - checked before consuming retained periodic-refresh frames or publishing retained periodic frames;
  - checked before admitting latest source frame into materialized production;
  - checked in scheduler/admission paths;
  - terminal, geometry, and visibility signals must still be drained/observed while production is paused.
- Old in-flight work vs new generation:
  - implementation must either make readback/encode in-flight leases generation-scoped, or keep new producer admission paused until old global `readbackInFlight` / `encoderInFlight` work clears;
  - a new-generation tick must not be counted as `byReadbackBusy` or `byEncoderBusy` solely because old-generation work is still in flight;
  - old-generation completion after commit is exactly stale-generation work.
- Core transaction API:
  - replace whole-updater `parameterMutex` holding with explicit reservation/commit API;
  - reserve a single-flight transaction slot briefly;
  - release public serializer for planning/preparation;
  - commit through the non-suspending bridge after rechecking token, terminal, current generation, and target.
- No-op identity:
  - normalized no-op requires same normalized/effective plan and same provider identity/configuration;
  - same effective plan with a different provider instance or config is not no-op and must take the provider-only path or reject.
- Public docs:
  - update `ScreenCaptureSession` and `ScreenCaptureParameters` KDoc; they must no longer describe all runtime updates as unavailable.

## Likely File Owners

- Runtime transaction/commit/resource retirement:
  - `internal/lifecycle/ActiveRuntimeOwner.kt`
- Public serialization/gates/state/stats:
  - `internal/session/core/ScreenCaptureSessionCore.kt`
- Planning/classification:
  - `internal/planning/ScreenCaptureOutputPlanner.kt`
- Plan-preparation/resource ownership:
  - `internal/rendering/pipeline/*`
  - `internal/rendering/es2/Es2RenderingPipelinePreparer.kt`
  - `internal/lifecycle/PlanPreparationToken.kt`
- Provider/encoder preparation and stale late cleanup:
  - `internal/encoding/provider/ImageEncoderPreparer.kt`
  - `internal/encoding/provider/ProviderPreparationContext.kt`
- Public KDoc:
  - `ScreenCaptureSession.kt`
  - `ScreenCaptureParameters.kt`
- Tests:
  - `DefaultScreenCaptureEngineTest.kt`
  - `InitialActivationCommitBoundaryTest.kt`
  - `ScreenCaptureSessionCoreTest.kt`
  - `Es2RenderingPipelinePreparerTest.kt`
  - static guard tests.

## Required Validation For Next Slice

- Unit/Robolectric tests for:
  - normalized no-op returns `Applied` and performs no generation/state/event/resource mutation;
  - frame-rate-only lightweight transaction bumps generation and does no GL/readback/provider work;
  - provider-only same-target success;
  - provider-only failure/timeout/stale late success preserving previous plan;
  - full same-target output-plan replacement;
  - valid retarget/live-replan request rejected as `ParameterUpdateUnavailable`;
  - invalid crop/caps request rejected with precise planner problem;
  - `Running(Suspended)` update rejected as `ParameterUpdateUnavailable`;
  - caller cancellation during preparation and late candidate cleanup;
  - terminal-before-commit rejected;
  - commit-before-terminal returns `Applied`;
  - lock-order/deadlock race between `setParameters` commit and `stop()`/`close()`/projection stop;
  - old encode/readback blocked, non-noop commit completes, new frame arrives, no new-generation `byEncoderBusy`/`byReadbackBusy` drop due only to old work, and old completion counts exactly one stale-generation drop;
  - paused transaction with pending source frame and pending periodic refresh does not consume/drop conflated signals and still drains terminal/geometry/visibility work;
  - serialized multiple updates;
  - in-flight old-generation render/readback/encode completion counted stale;
  - no new-generation frame before public state exposes matching effective parameters;
  - no `VirtualDisplay.resize`, `setSurface`, target replacement, or `SurfaceTexture.setDefaultBufferSize` from v37 parameter path;
  - old universal-rejection tests updated.
- Race tests that cross real executor, scheduled executor, encoder executor, or provider-worker boundaries must use deterministic latches, test hooks, or fake schedulers. Do not rely on `runTest` virtual time alone for those paths.
- Static guards should enforce:
  - `ScreenCaptureSessionCore` remains sole public state/stats/events owner;
  - no retarget platform calls from v37 parameter path;
  - no provider/GL/cleanup/suspending calls inside commit bridge;
  - commit bridge body allowlist: no suspend calls, provider preparation/cleanup, encoder close, GL access, `VirtualDisplayOwner.bindTarget`, `VirtualDisplay.resize/setSurface`, `SurfaceTexture.setDefaultBufferSize`, dispatcher hops, blocking waits, or heavy cleanup;
  - startup-only GL access is not used directly by active runtime replacement except through neutral plan-preparation facade;
  - no reflection.
- Required independent review passes after implementation:
  - concurrency/lifecycle/lock-order review;
  - resource ownership/cleanup/performance review;
  - tests/static/KDoc review;
  - full sequential JVM/Robolectric module validation.
