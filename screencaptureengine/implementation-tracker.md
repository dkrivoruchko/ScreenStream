# ScreenCaptureEngine Implementation Tracker

## Document Policy

- Write current implementation status, next steps, open questions, blockers, bugs, design gaps, agreed deviations, and decisions that affect future implementation.
- Do not write historical noise, cancelled ideas, transient dependency checks, obvious facts duplicated from Gradle files, or completed micro-steps that no longer affect future work.
- Keep this document compact and update it by replacing stale information, not by appending a long changelog.

## Source Of Truth

- Design: `screen-capture-engine-design-v16.md`.
- The design file is read-only.
- The design is the primary and controlling source of truth for implementation.
- Design compliance is mandatory by default.
- Approved deviations are allowed only when implementation exposes a real conflict, gap, platform issue, or better-supported approach; each deviation must be explicitly agreed with the user and recorded in `Design Deviations`.

## Work Agreement

- At the start of every phase, first prepare a phase implementation proposal for user approval. The proposal must include scope, intended files/areas, implementation direction, official research summary, validation plan, risks, and questions.
- Do not start code changes for a phase until the user approves that phase proposal.
- After reading the design and before writing code, perform focused research using current official Android, AndroidX, Kotlin, Coroutines, Gradle, AGP, NDK, and Build Tools sources relevant to that phase.
- Stop and ask the user for an explicit decision when implementation exposes a design gap, conflict, missing case, unexpected platform behavior, unclear tradeoff, failed assumption, or unspecified behavior.
- Do not invent public API, fallback policy, lifecycle behavior, error handling, integration behavior, or deviations from the design.
- Record every approved deviation from the design in the `Design Deviations` section.
- Keep this module independent from existing project modules and app-specific code.
- Before adding or updating Android, AndroidX, Kotlin, Coroutines, Gradle, AGP, NDK, or Build Tools versions, check current official sources online.
- Use release/stable versions in this module's Gradle configuration.
- Treat the main user-facing thread as the coordinator: it keeps context, asks the user for decisions, plans phases, and delegates bounded work to sub-agents.
- Communicate with the user in the main thread only in Russian. Keep all code, KDoc, comments, tests, commit text if any, and project documentation in English.
- Code documentation must describe the current contract directly and must not reference the design document, design document version names, implementation phases, tracker phases, roadmap, or process.
- Use sub-agents for implementation, audits, focused research, and independent reviews when useful. At most six sub-agents may be active at the same time; finished agents may be closed and replaced with fresh ones.
- Prefer compact, modern, idiomatic Kotlin/Coroutines/Android code, but do not trade clarity or design compliance for cleverness.
- Review every code change with at least one self-review plus two independent reviews before considering the phase complete.
- Run Gradle validation tasks sequentially for this module; concurrent Gradle/Kotlin compile invocations can corrupt or invalidate Kotlin incremental caches.
- Split the work into explicit phases in this file as implementation starts or when the current phase changes.

## Status

- Setup is complete.
- Phase 1 is complete: public API type signatures, defaults, constructor validation, and public KDoc are implemented and aligned with design-v16.
- Phase 1 validation passed with `:screencaptureengine:compileDebugKotlin`, `:screencaptureengine:lintDebug`, JetBrains IDE error inspection, self-review, and two independent documentation audits.
- Phase 2 implementation is complete: pure internal output planning now resolves source/crop, rotation, output sizing, checked pixel/byte arithmetic, injected runtime caps, conservative capture-target downscale, encoder request shape, and delayed effective-parameter construction after encoder info is known.
- Phase 2 tests were intentionally not added by user request. Validation used `:screencaptureengine:compileDebugKotlin`, `:screencaptureengine:lintDebug`, JetBrains IDE inspection, self-review, targeted early-downscale research, and independent sub-agent design/engineering/documentation audits.
- Runtime implementation has not started. `CaptureMetricsProviders` factory bodies and `JpegImageEncoderProvider.createEncoder(...)` intentionally fail until their runtime phases are designed/implemented.

## Phases

0. Setup Lock
   - Goal: lock module boundaries, versions, dependencies, package layout, and progress-file rules.
   - Gate: no dependency on existing project modules; only stable/release Gradle values.
1. Public API And Validation
   - Goal: implement the design-v16 public API surface, defaults, and constructor validation.
   - Gate: API matches the design document; no unsanctioned fields or behavior.
2. Pure Geometry And Planning Core
   - Goal: implement deterministic non-platform planning: metrics abstractions, source/crop math, rotation, mirror, output sizing, checked arithmetic, caps, and capture target planning.
   - Gate: stop for any ambiguity in rounding, caps, rejection, or suspension behavior.
3. Session Control And Delivery Skeleton
   - Goal: implement lifecycle/control flow without real capture: serialized session operations, state/stats/events, subscriptions, snapshot slots, and latest-only delivery.
   - Gate: no callbacks on producer-critical contexts; delivery and drop semantics match the design.
4. MediaProjection And VirtualDisplay Runtime
   - Goal: implement fresh projection ownership, single `VirtualDisplay`, callback registration, API 34+ first-resize startup wait, generation model, resize/surface ordering, and projection stop handling.
   - Gate: stop for any platform behavior that conflicts with or is not covered by the design.
5. GL ES2 Rendering And Readback
   - Goal: implement the required baseline pipeline: `SurfaceTexture`/OES, EGL/GL thread, transform matrix, crop/rotate/mirror/resize/grayscale, framebuffer, `glReadPixels`, and stale-generation drops.
   - Gate: no raw RGBA exposure to normal consumers; rendering matches public coordinate rules.
6. Encoding And Latest Publication
   - Goal: implement framework JPEG, synchronous encoder execution, sink caps, immutable snapshots, borrowed encoded frames, publication stats, and delivery drops.
   - Gate: encoded-size limits are enforced; slow consumers cannot block producer work; callback failures are contained.
7. Runtime Reconfiguration And Failure Hardening
   - Goal: implement atomic parameter updates, geometry changes, suspend/resume, provider changes, trim memory, repeated-failure thresholds, terminal/recoverable mapping, and cleanup.
   - Gate: lifecycle transitions and problem kinds match the design; no invented fallback policy.
8. Native JPEG And ES3/PBO Accelerators
   - Goal: implement NDK JPEG and ES3/PBO as optional performance paths with validation and fallback to framework JPEG/ES2.
   - Gate: old-device load safety and backend failures are handled exactly as designed.
9. Final Compliance And Matrix
   - Goal: complete design compliance audit, review pass, platform matrix planning/validation, and standalone harness only if needed inside `screencaptureengine`.
   - Gate: self-review plus two independent reviews; unresolved gaps are recorded and escalated.

## Next

- Prepare Phase 3 proposal: Session Control And Delivery Skeleton.

## Open Questions

- Deferred: built-in `CaptureMetricsProviders` need a live update strategy for `StateFlow<CaptureMetrics>`, including lifecycle/cleanup ownership. Do not implement provider runtime behavior until this is resolved. Session `stop()` / `close()` can clean engine-owned registrations, but they are not by themselves a complete lifecycle model for callbacks/listeners tied to `Activity`, `Service`, `Context`, `Display`, or window ownership. Decide whether providers are lifecycle-bound, explicitly closeable, session-owned, owner-owned, or some combination before implementing these factories.

## Decisions

- Public config defaults are constructor default values only, not extra public constants.
- Built-in JPEG identifiers: provider id `jpeg`, format name `JPEG`, MIME type `image/jpeg`.
- Phase 2 uses the design early-downscale model with conservative integer quantization: capture-target dimensions never exceed logical capture and are adjusted so the effective target scale does not fall below the required scale.
- Phase 2 planner does not create encoders or fabricate encoder backend info; it builds `ImageEncoderRequest`, and later runtime encoder creation supplies `ImageEncoderInfo` before public effective parameters are built.
- Java interop remains a non-goal. `@Throws` annotations are not added to the encoder SPI unless a future approved design change adds Java-facing API requirements.
- Do not add tests unless the user explicitly approves them for the current phase or task.
- Runtime placeholder public APIs should fail with ordinary exceptions, not `Error` types, while runtime behavior is intentionally unavailable.
- Keep validation constants private and local for now instead of adding a shared constants/helper abstraction. Revisit only if actual drift or repeated update burden appears.

## Design Deviations

- None.
