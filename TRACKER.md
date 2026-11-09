# Work tracker

Updated 2026-11-09. Open work, pending decisions and standing working rules.

## Future work and decisions

`TASK`: pending work. `QUESTION`: assessment or scope decision still open. Questions remain open for investigation; an entry alone neither authorizes implementation nor expands the current task scope. Explicit user instructions and approvals take precedence over tracker notes and remain valid until changed.

1. [TASK] **Google Play Policy:** check the app and store declarations before release (`play-policy-insights`).
2. [TASK] **Foreground service:** prepare the Play Console `specialUse` justification before release.
3. [QUESTION] **MediaQuery:** assess compatibility, stability and practical benefit before migration. [Guide](https://developer.android.com/develop/adaptive-apps/guides/mediaquery).
4. [QUESTION] **Compose Grid:** assess its value for MJPEG cards. [Reference](https://www.youtube.com/watch?v=SGTkTKX7qq4&list=PLQZJH-fZcZcc&index=7).
5. [QUESTION] **PIN interception:** assess replay, offline guessing, browser compatibility and HTTP limits. A one-use challenge/PIN-derived response remains an unselected option.
6. [QUESTION] **MJPEG connections:** decide whether to introduce a global connection limit and choose its policy.
7. [QUESTION] **Product screenshots:** agree scope, then add useful real screenshots.

## Standing working rules

1. **Scope and approval.** Production changes, including test seams and API changes, require explicit user agreement. This does not prohibit research or proposals. Proceed within scope already approved; do not request the same approval again.
2. **Dependencies and test setup.** Research current official guidance and present the rationale for dependency, framework or test-configuration changes before implementation. New dependencies use the latest stable release unless the user chooses otherwise. Prior decisions do not prohibit reassessment.
3. **Tests.** Assert observable contracts, business logic and functionality. Avoid incidental allocation/format assumptions and test-written private-member or string-based reflective dispatch. Supported libraries' internal reflection is accepted. Simplify or extend tests where useful, using current applicable official guidance.
4. **Module ownership and documentation.** Tests and test docs stay in their owning module, without cross-test-module dependencies, references or mentions; necessary production contracts may be used. Keep docs compact and current, without migration history or future-work narratives. Source links are navigation, not substitutes for contract and evidence descriptions. Keep open proposals in this tracker.
5. **Documentation workflow.** Module Markdown and code documentation use the default Opus through claude-code, with self-checks followed by an independent expert pair's technical review. The orchestrator alone maintains this tracker and removes completed work.
6. **Technical review.** Use Expert Consensus for substantive technical findings, design decisions and final implementation verification. Reconcile independent results by evidence, preserving unresolved disagreement. Routine tracker maintenance belongs to the orchestrator.
7. **Logging.** Core and MJPEG callers use the standard Core logging extensions directly; Android Log/XLog belong only in the backend. Avoid diagnostic-only try/catch/runCatching and redundant receiver qualification. Business tests neither initialize/assert logging nor reference a backend; silence the existing facade centrally.
8. **Tools and device cleanup.** No Git inspection/operations without an explicit request. Native Android Studio UI remains disallowed; authorized MCP/CLI is allowed. Before removing physical-device test APKs, preserve needed run evidence; verify removal and preserve production apps/data.
9. **Intentional documentation omission.** Do not document MJPEG legacy settings/import/migration or SET-12; the omission is intentional.
