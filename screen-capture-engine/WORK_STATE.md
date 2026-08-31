# ScreenStream Capture Engine — Work State

This file keeps only unresolved decisions, blockers, and continuation points that must survive restarts. Stable contracts and completed work belong in source, tests, `README.md`, `docs/`, and `internal/`.

## Pending release evidence

- Select and implement the supported acquisition or publication route before documenting dependency setup.
- Before claiming release readiness, compile a representative external Kotlin consumer and retain honest candidate-specific API, artifact, host, device, GPU, ABI, and 16-KiB-page evidence. None of that external integration evidence is currently proved.

## Deferred API compatibility evidence

- Public API-dump work is deferred. JetBrains issue KT-78025 still states that Android Gradle projects lack correct built-in ABI-validation support and has no planned or available fix; the current AGP 9.3.2 / Gradle 9.7.1 / Kotlin 2.4.10 combination is also outside KGP 2.4.x's documented matrix. Do not add a generator, baseline, dependency, or lifecycle gate until official Android support and a compatible matrix exist or the developer explicitly reopens the decision.
