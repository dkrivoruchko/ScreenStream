# Repository Guidelines

## Project Structure & Module Organization
ScreenStream is an Android multi-module project. The `app` module hosts the Compose UI shell, DI wiring, and flavor-specific code (`app/src/PlayStore` adds ads & WebRTC). Shared UI, preferences, and logging live in `common`. Streaming engines sit in dedicated modules: `mjpeg` (embedded Ktor server for local HTTP streams), `rtsp` (external RTSP client/server integration), and `webrtc` (used only by the PlayStore flavor). Build artifacts land in `build/`; keep assets under each module’s `src/main/res`.

## Build, Test, and Development Commands
Use `./gradlew assembleDebug` for a quick dev build (debug signing is preconfigured).

## Coding Style & Naming Conventions
All Kotlin sources follow JetBrains 4-space indentation and explicit visibility—`kotlin { explicitApi() }` enforces it. Compose entry points use PascalCase suffixes like `*Screen` or `*Card`; lower-level helpers stay `internal` and lowerCamelCase. Keep constants in `object` holders with UPPER_SNAKE_CASE. Strings and colors belong in each module’s `res/values`; avoid hardcoded literals. Format through Android Studio’s "Reformat Code" and organize imports before committing.

## Testing Guidelines
Automated coverage is sparse today; new features must ship with targeted tests. Place JVM tests under `<module>/src/test/java` using JUnit4/5 as appropriate; prefer constructor injection so logic can be isolated. UI/permission flows that touch Android APIs belong in `<module>/src/androidTest/java` with Espresso or Compose UI testing. Document any gaps in the PR description when instrumentation is impractical, and add manual validation steps for streaming scenarios.

## Security & Configuration Tips
Treat `debug-key.jks` as development-only; production signing happens outside the repo. When touching WebRTC signaling, keep endpoint references configurable and never hardcode credentials.
