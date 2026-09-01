# ScreenStream Capture Engine — Work State

## Rules for a future full test audit

### 1. Divide the audit into bounded parts

Inspect the whole module first, then divide it into five to eight coherent parts small enough for an expert panel to review with full local context. Partition by product contract and ownership boundary rather than by arbitrary file count. Give every part explicit scope, relevant production code, tests, documentation, dependencies, and exclusions. Audit cross-part integration separately after all parts are complete so that gaps, duplication, and inconsistent ownership are not lost between parts. Do not submit the entire engine to one context-limited panel at once.

### 2. Apply all four quality criteria

Evaluate every part against all four criteria:

1. **Completeness and minimality:** test every required behavior and meaningful boundary, omit non-discriminating cases, and verify that each test actually proves what it claims.
2. **Adequacy:** test product contracts, documented behavior, outcomes, ownership, and ordering rather than incidental implementation details. Test a maintained implementation boundary only when that boundary is itself an explicit contract.
3. **Technical correctness:** validate tests against current official Android, Kotlin/JVM, JNI/NDK, build-system, and other relevant primary documentation, platform behavior, and established best practices.
4. **Efficiency:** prefer the smallest clear oracle and the smallest representative matrix that kill the relevant defect classes. Avoid duplicate layers, Cartesian expansions, unnecessary seams, and oversized fixtures.

### 3. Preserve minimality and prevent evidence inflation

Every proposed test must have a discriminating product-level failure mode and a clearly owned verification contract. Reuse existing evidence when it already proves the same fact; do not count helpers, mocks, fixtures, markers, compilation, or broad integration tests as additional proof by themselves. Keep evidence layers explicit: JVM/Robolectric, host-native, Android instrumentation, real-device/GPU, static inspection, artifact/ABI, and external-consumer evidence are not interchangeable. A green build or compiled androidTest does not prove device execution; host-native tests do not prove Android packaging or device behavior; static inspection does not become runtime evidence. Never broaden a claim beyond the environment and oracle that actually established it.
