# Work tracker

Updated 2026-11-06. Open work, pending decisions and persistent constraints.

## Future work and decisions

`TASK`: pending work. `QUESTION`: assessment or scope decision still open. A marker does not authorize implementation.

1. [TASK] **Google Play Policy:** check the app and store declarations before release (`play-policy-insights`).
2. [TASK] **Foreground service:** prepare the Play Console `specialUse` justification before release.
3. [QUESTION] **MediaQuery:** assess compatibility, stability and practical benefit before migration. [Guide](https://developer.android.com/develop/adaptive-apps/guides/mediaquery).
4. [QUESTION] **Compose Grid:** assess its value for MJPEG cards. [Reference](https://www.youtube.com/watch?v=SGTkTKX7qq4&list=PLQZJH-fZcZcc&index=7).
5. [QUESTION] **R8:** measure benefit before narrowing broad rules; preserve Navigation3 restoration and JNI boundaries. Jetpack Keep alpha is optional.
6. [QUESTION] **PIN interception:** assess replay, offline guessing, browser compatibility and HTTP limits. A one-use challenge/PIN-derived response remains an unselected option.
7. [QUESTION] **MJPEG connections:** decide whether to introduce a global connection limit and choose its policy.
8. [QUESTION] **Product screenshots:** agree scope, then add useful real screenshots.
9. [QUESTION] **Baseline Profile:** evaluate an app-specific profile for MJPEG entry, settings/statistics expansion and scrolling. Account for existing dependency profiles; release R8 alone does not settle this decision.
10. [QUESTION] **Core tests:** future scope is new suites for notification policy/receiver/permissions/remaining Service/settings. Credit inherited MJPEG Service coverage; no new missing-component suites authorized yet.

## Persistent constraints

1. Do not document MJPEG legacy settings/import/migration or SET-12; the omission is intentional. Preserve unrelated Android legacy-consent behavior.
2. Documentation records current behavior, not work history. Implementation details and test contracts belong to their owning module docs, not this tracker.
3. Newly added dependencies must be latest stable. Production test seams/API changes require explicit scope approval.
4. No Git inspection/operations without an explicit request. Native Android Studio UI remains disallowed; authorized MCP/CLI is allowed.
5. Export evidence before deleting physical-phone test APKs; confirm removal and preserve production apps/data.
6. Scrutinize test-written explicit reflection/private-member or string-named reflective dispatch. Reflection internal to supported libraries is accepted; keep documented Robolectric helpers when appropriate. Production changes require explicit agreement.
7. Core and MJPEG production callers must emit logs through the standard Core logging extension functions; direct Android Log and XLog are bypasses outside the logging backend itself. Backend/flavor routing changes need separate scope agreement.
8. Use Expert Consensus for material findings/design decisions and final verification; do not trust a lone agent. Independent complete outcomes must be reconciled by evidence, with explicit acceptance or preserved disagreement.
9. Call canonical logging directly: no diagnostic-only try/catch/runCatching and no redundant qualified receiver when the implicit receiver is identical. Business tests must not initialize or assert logging or reference an external logging backend; silence the existing facade centrally in test environments.
