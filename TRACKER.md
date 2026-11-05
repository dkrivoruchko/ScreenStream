# Work tracker

Updated 2026-11-05. No active work. Backlog entries require scope selection before implementation. Root alone maintains this file; repository and module instructions apply.

## Future work and decisions

1. **Google Play Policy:** check the app and store declarations before release (`play-policy-insights`).
2. **Foreground service:** prepare the Play Console `specialUse` justification before release.
3. **MediaQuery:** assess compatibility, stability and practical benefit before migration. [Guide](https://developer.android.com/develop/adaptive-apps/guides/mediaquery).
4. **Compose Grid:** assess its value for MJPEG cards. [Reference](https://www.youtube.com/watch?v=SGTkTKX7qq4&list=PLQZJH-fZcZcc&index=7).
5. **R8:** measure benefit before narrowing broad rules; preserve Navigation3 restoration and JNI boundaries. Jetpack Keep alpha is optional.
6. **PIN interception:** assess replay, offline guessing, browser compatibility and HTTP limits. A one-use challenge/PIN-derived response remains an unselected option.
7. **MJPEG connections:** decide whether to introduce a global connection limit and choose its policy.
8. **Product screenshots:** agree scope, then add useful real screenshots.
9. **Baseline Profile:** evaluate an app-specific profile for MJPEG entry, settings/statistics expansion and scrolling. Account for existing dependency profiles; release R8 alone does not settle this decision.
10. **Core documentation:** confirm acceptance or identify required revisions.
11. **Core tests:** select scope, crediting existing MJPEG superclass tests. Proposed order: notification episodes/exact action routing; permission predicates/settings intents; missing Service timeout, microphone, refresh and destruction histories. Settings later.

## Deferred verification limits — no additional work selected

1. **MJPEG:** observed settings-flow failure during active capture and exact HTTP-writer revision/acquire/subscription windows remain incompletely tested. Surrounding histories are covered; both expert pairs recommend no new seams or extra tests now. No observed defect is recorded.
2. **Engine:** first affected Android firmware/platform path, producer allocation/performance, API32/33/current-code API35 and other OEM/ABI/app-window/mediump qualification remain unestablished. Historical passes do not qualify the current code on an untested platform.
3. **LAN:** emulator-console tests establish module gating, not physical-LAN reachability or independent kernel enforcement.

## Persistent constraints

1. Do not document MJPEG legacy settings/import/migration or SET-12; the omission is intentional. Preserve unrelated Android legacy-consent behavior.
2. Documentation records current behavior, not work history. Implementation details and test contracts belong to their owning module docs, not this tracker.
3. Newly added dependencies must be latest stable. Production test seams/API changes require explicit scope approval.
4. No Git inspection/operations without an explicit request. Native Android Studio UI remains disallowed; authorized MCP/CLI is allowed.
5. Export evidence before deleting physical-phone test APKs; confirm removal and preserve production apps/data.
