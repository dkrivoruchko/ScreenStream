# Repository Guidelines

## Purpose & Modes
ScreenStream streams Android screen + audio. Modes: Local (MJPEG), Global (WebRTC), and RTSP. RTSP has two sub‑modes: server (default) hosts this device; client connects to an external RTSP media server. F‑Droid builds are ad‑free and exclude WebRTC; PlayStore builds include ads + WebRTC.

## Project Structure & Modules
- `app`: Compose UI shell, DI wiring, and flavors (`app/src/PlayStore` adds ads/WebRTC; `app/src/FDroid` strips them).
- `common`: shared UI, preferences helpers, logging, utilities.
- `mjpeg`: embedded Ktor HTTP server for local MJPEG streaming.
- `rtsp`: RTSP server/client implementation and settings.
- `webrtc`: WebRTC streaming (PlayStore flavor only).

## Build & SDK Baseline
- Kotlin `explicitApi()` with JVM toolchain 17.
- SDKs: min 23, target/compile 36; build tools 36.0.0; NDK 28.2.13676358.
- `./gradlew :app:assembleFDroidDebug` — build the F-Droid debug APK.
- `./gradlew :app:assemblePlayStoreDebug` — build the Play Store debug APK.
- Build types: release enables minify/shrink + Crashlytics mapping upload.
- Flavors: `FDroid`, `PlayStore`. PlayStore reads ads from `local.properties` (`ad.pubId`, `ad.unitIds`).

## Coding Style & Naming Conventions
Kotlin follows the official style (`kotlin.code.style=official`) and uses explicit API mode in the app module; keep public APIs explicit and well-typed.
Prefer 4-space indentation and Android Studio formatting.
Product flavors are PascalCase (`FDroid`, `PlayStore`), and module names are lowercase (`common`, `mjpeg`, `rtsp`, `webrtc`).

## Localization Rules (Strings)
- English `values/strings.xml` is the source of truth for **key order and blank lines**.
- Every locale file must mirror English key order **and** blank-line placement between `<string>` tags exactly.
- Strings live in module `res/values` (app, mjpeg, rtsp, webrtc). Avoid hardcoded literals.
- Use compact AOSP‑style UI terms; prefer “stream”/“device”. Use loanwords when standard, otherwise localize.

## Commit & Pull Request Guidelines
Recent commits use short, sentence-style summaries (e.g., “Readme update”, “Minor updates”).
Follow that pattern and keep messages scoped to the change.

## Security & Configuration Tips
`local.properties` is read for Play Store ad identifiers; keep it uncommitted.
The debug keystore is checked in for convenience; do not use it for release builds.
Keep WebRTC signaling endpoints configurable; never hardcode credentials.
