package io.screenstream.capture.internal.metrics;

import io.screenstream.capture.CaptureMetricsSource;

public final class NullHandleCaptureMetricsSource implements CaptureMetricsSource {
    // Intentional platform-null fixture: exercises the Kotlin boundary without weakening its non-null contract.
    // TextBlockMigration only suppresses Quail's false style hint on the inspection-ID string itself.
    @SuppressWarnings({"NullableProblems", "TextBlockMigration"})
    @Override
    public AutoCloseable subscribe(Observer observer) {
        return null;
    }
}
