# ScreenCaptureEngine Implementation Tracker

## Source Of Truth

- Design: `screen-capture-engine-design-v21.md`.
- The design file is read-only and controls implementation unless an approved deviation is recorded here.
- Main-thread user communication is Russian. Code, KDoc, comments, tests, commit text, and project documentation are English.
- Code documentation describes the current contract directly; it must not reference design document versions, implementation phases, tracker phases, roadmap, or process.

## Work Rules

- Start each new phase with a proposal for user approval: scope, files/areas, direction, official research summary, validation, risks, and questions.
- Before code changes for a phase, read the design and perform focused research using current official sources relevant to that phase.
- Do not invent public API, lifecycle behavior, fallback policy, error handling, integration behavior, or deviations from the design.
- Stop for user/design-author input when implementation exposes a design gap, conflict, missing case, risky tradeoff, or repeated ambiguity.
- Design-author questions must be phrased in design/state-machine terms because the design author does not see this codebase.
- For non-trivial design, architecture, concurrency, or contract choices, validate the idea first with at least two independent sub-agents when practical. Use a
  third independent review for disputed or high-risk findings. Do not rush into the first plausible solution.
- Use sub-agents for bounded implementation, audits, focused research, and reviews when useful; close finished agents promptly. Sub-agent briefs must instruct
  them to use current official online sources for platform/library behavior and best practices instead of relying on memory, including Kotlin 2.4 language/API
  behavior when Kotlin choices are relevant.
- Read-only audits should also look for simpler, more compact, equally reliable implementation options. Record confirmed problems separately from optional
  simplification proposals, with evidence and tradeoffs.
- Keep this module independent from existing app modules. Use stable/release dependency versions and check current official sources before version changes.
- Prefer compact, modern, idiomatic Kotlin. Do not add helper abstractions or data classes unless they carry real behavior, reduce meaningful duplication, or match the existing design.
- Prefer compact Kotlin formatting within the project line width; avoid early line breaks when a direct expression remains readable.
- Do not add tests unless the user explicitly approves them for the current phase/task. This is a process constraint; the design test matrix still applies before final completion.
- Validate with sequential Gradle tasks for this module; avoid concurrent Gradle/Kotlin compile runs.

## Current State

- Setup and Phases 1-3 are complete against v21.
- Implemented: public API/validation/KDoc/manual equality, pure output planning, capture-target pixel/byte caps, delayed encoder-info attachment, internal session skeleton, latest-only frame delivery, bounded public snapshot leases, callback failure accounting, slow-consumer tracking, dispatcher handoff, watchdogs, and diagnostic rate limiting.
- Delivery/stop semantics match v21; there are no known blocking findings.
- Current validation baseline: Gradle Kotlin compile, Gradle lint, JetBrains reformat, targeted JetBrains inspections, self-review, and independent sub-agent audits.
- Not implemented yet: MediaProjection, VirtualDisplay, GL/readback, JPEG runtime, built-in metrics-provider runtime behavior, provider fallback policy, final lifecycle hardening, and tests.
- `CaptureMetricsProviders` factory bodies and `JpegImageEncoderProvider.createEncoder(...)` intentionally fail until their runtime phases are designed and implemented.

## Phase Map

- 0. Setup Lock: complete.
- 1. Public API And Validation: complete.
- 2. Pure Geometry And Planning Core: complete.
- 3. Session Control And Delivery Skeleton: complete for non-runtime scope.
- 4. MediaProjection And VirtualDisplay Runtime: next phase.
- 5. GL ES2 Rendering And Readback: future.
- 6. Encoding And Latest Publication: future.
- 7. Runtime Reconfiguration And Failure Hardening: future.
- 8. Native JPEG And ES3/PBO Accelerators: future.
- 9. Final Compliance And Matrix: future.

## Next

- Prepare the Phase 4 proposal: MediaProjection And VirtualDisplay Runtime. Runtime implementation must route public state, stats, diagnostic events, frame drops,
  visibility changes, geometry transitions, and output-plan transitions through `ScreenCaptureSessionCore` instead of publishing parallel state from runtime code.

## Phase 4 Preparation

- Do not start Phase 4 implementation before presenting a proposal and getting user approval.
- Read `screen-capture-engine-design-v21.md` first, especially MediaProjection ownership, virtual display lifecycle, stop/failure boundaries, metrics-provider ownership,
  captured-content resize/visibility callbacks, output suspension/resume, and fresh-projection requirements.
- Use current official Android documentation for MediaProjection and VirtualDisplay behavior; use current Kotlin/coroutines documentation for lifecycle and concurrency
  decisions.
- Use at least two independent sub-agents for Phase 4 research/proposal review. Their briefs must be fresh and bounded; do not fork/replay the parent conversation.
- Keep runtime state owned by `ScreenCaptureSessionCore`. Add narrow internal entrypoints there when runtime needs to publish lifecycle/output/stats/events, instead of
  letting runtime code mutate public flows or counters directly.
- Preserve existing delivery semantics: latest-only publication, bounded public snapshot slots, callback exceptions contained as delivery drops, and terminal stop as
  an immediate publication/delivery boundary.
- Do not implement GL/readback, JPEG runtime, ES3/PBO, or tests in Phase 4 unless the user explicitly expands scope.
- Proposed validation for Phase 4 should include module Kotlin compile, module lint, JetBrains reformat/inspections, self-review, and sub-agent audit. Tests still
  require explicit user approval.

## Open Decisions

- `FrameRate.Auto` is implementation-defined by the design; choose and document the bounded policy before Phase 4 implementation.
- ES3/PBO validation details are implementation-defined by the design; choose them during the ES3/PBO phase inside the required fallback/lifecycle rules.
- Deferred: publishing stats once per encoded-frame result needs an explicit observable-stats policy decision.
- Deferred: replacing internal `AtomicReference` state with `@Volatile` needs targeted lifecycle race tests.
- Deferred: early snapshot-copy skip when all subscriptions are busy/stale changes delivery decision timing and needs design approval.
- Deferred: lease issuing/refcount API refactor needs targeted lease tests.

## Durable Decisions

- Runtime placeholder public APIs fail with ordinary exceptions, not `Error` types.
- Public immutable value/state models use manual `equals`/`hashCode`; do not convert them to `data class` by default.
- Keep validation constants private/local unless actual drift or repeated update burden justifies a shared abstraction.
- Kotlin has no standard checked integer arithmetic equivalent to JVM `Math.addExact` / `Math.multiplyExact`; keep those APIs where checked overflow is required unless a clearer project-local abstraction becomes justified.
- Configured caller-owned dispatchers are reached through an engine-owned callback bridge so custom direct/immediate dispatchers cannot run callbacks on the single delivery coordinator worker or producer-critical callers.
- Subscription stats snapshots from the delivery coordinator are versioned; the session core ignores older snapshots so races cannot roll public counters backward.

## Design Deviations

- None.
