# ScreenStream Capture Engine module

These instructions apply under `screen-capture-engine/`.

## Product and public API

- This module is a standalone, embeddable Android screen-to-JPEG SDK.
- Kotlin consumers are primary. Keep the public Kotlin API explicit and well typed.
- Add Java convenience only when it is simple and useful on a best-effort basis; do not add compatibility promises solely for Java.

## Documentation and authority

- Start with the [internal documentation index](internal/README.md), follow its task-oriented path, and read only the relevant public and internal sections.
- Source and build files describe the actual implementation. Public documentation defines promised behavior.
- Investigate and report conflicts between implementation and promised behavior; do not silently change documentation to fit the code.
- Get user agreement before intentionally changing a public contract unless the approved scope already authorizes the change.
- Update the affected owning documentation and verification evidence with an implementation change.
- Keep KDoc and code comments self-contained and grounded in source behavior. Do not link them to repository Markdown; Kotlin symbol references are allowed.

## Build and verification

- Use `build.gradle.kts` as the authority for current module settings. Do not maintain a duplicate version table or copy the parent application's baseline.
- Follow the [canonical contract-test rules](internal/04-testing/testing.md#contract-test-rules) when creating or changing tests.
- Run the smallest checks that establish the affected behavior, then broaden only when the change or a failure justifies it.
- Report what was inspected, assembled, executed, or skipped as distinct evidence. State explicitly whether a physical-device or other runtime check actually ran.
