# Repository Guidelines

## Project Map
ScreenStream has Local (MJPEG, video only), Global (WebRTC), and RTSP modes. RTSP server mode hosts the device; client mode publishes to an external media server. `FDroid` excludes ads and WebRTC; `PlayStore` includes both.

- `app`: Compose shell, DI, and flavor integration; `common`: shared UI and utilities.
- `streaming/core`: module lifecycle, capture consent, permissions, and notifications.
- `streaming/mjpeg`, `streaming/rtsp`, `streaming/webrtc`: mode implementations.
- `streaming/webrtc-runtime`: vendored Java/JNI runtime; follow its [README](streaming/webrtc-runtime/README.md) before modifying it.
- `screen-capture-engine`: standalone screen-to-JPEG SDK with its own `AGENTS.md`.

## Sources & Verification

- Read relevant module documentation before changing behavior: [Core](streaming/core/internal/README.md), [MJPEG](streaming/mjpeg/internal/README.md), [Capture Engine](screen-capture-engine/internal/README.md). Update the owning documentation when behavior changes; investigate conflicts rather than silently rewriting a promised contract.
- Build files own SDK, dependency, and toolchain versions: root/module `build.gradle.kts`, `gradle/libs.versions.toml`, and `gradle/gradle-daemon-jvm.properties`. Do not confuse the Gradle/test JVM with the Kotlin compilation toolchain.
- Build from the repository root: `./gradlew :app:assembleFDroidDebug` or `./gradlew :app:assemblePlayStoreDebug`.
- Run focused checks using the affected module's testing guide. Distinguish compilation, executed tests, and device verification; report skipped or blocked checks.
- Follow official Kotlin style and existing formatting; keep public Kotlin APIs explicit and well typed.
- MJPEG's viewer asset is generated: edit and verify its [web-client sources](streaming/mjpeg/tools/web-client/README.md), then regenerate and commit the asset with them.

## Localization

- Android strings live in `app`, `common`, and the three mode modules. Avoid hardcoded UI text.
- Each locale must preserve its module's English `values/strings.xml` key order, blank-line placement, and formatting placeholders (such as `%1$s`).
- Use compact AOSP-style UI terms; prefer “stream”/“device”. Use standard loanwords where appropriate.

## Configuration & Tools

- Keep `local.properties` uncommitted. Never hardcode credentials or use `debug-key.jks` for release signing.
- For crashes/ANRs, fetch relevant Firebase Crashlytics issues and details when its MCP tools are available. Otherwise state the limitation and use available stack traces, logs, or reproduction evidence.
- Prefer Android CLI for Android tasks, Android Studio MCP for IDE integration, and terminal/Gradle for unsupported operations, verification, or debugging.
- Use a connected physical Android device when available. Ask before launching an emulator unless already authorized.
