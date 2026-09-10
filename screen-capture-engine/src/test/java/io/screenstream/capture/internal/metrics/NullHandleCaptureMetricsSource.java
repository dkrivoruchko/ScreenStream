package io.screenstream.capture.internal.metrics;

import io.screenstream.capture.CaptureMetricsSource;

public final class NullHandleCaptureMetricsSource implements CaptureMetricsSource {
    // Intentional platform-null fixture: exercises the Kotlin boundary without weakening its non-null contract.
    @SuppressWarnings({"NullableProblems", "TextBlockMigration"})
    @Override
    public AutoCloseable subscribe(Observer observer) {
        return null;
    }
}
