package dev.dmkr.screencaptureengine.internal.platform.metrics;

import dev.dmkr.screencaptureengine.CaptureMetricsState;
import java.util.Collections;
import java.util.List;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlinx.coroutines.flow.FlowCollector;
import kotlinx.coroutines.flow.StateFlow;

/** Deliberately nonconforming StateFlow used to exercise normal-completion handling. */
@SuppressWarnings({"rawtypes", "unchecked"})
final class CompletingStateFlow implements StateFlow {
    private final CaptureMetricsState value;
    private final Runnable onCollect;
    int collectCount;

    CompletingStateFlow(CaptureMetricsState value) {
        this(value, () -> {});
    }

    CompletingStateFlow(CaptureMetricsState value, Runnable onCollect) {
        this.value = value;
        this.onCollect = onCollect;
    }

    @Override
    public CaptureMetricsState getValue() {
        return value;
    }

    @Override
    public List<CaptureMetricsState> getReplayCache() {
        return Collections.singletonList(value);
    }

    StateFlow<CaptureMetricsState> typed() {
        return (StateFlow<CaptureMetricsState>) (StateFlow) this;
    }

    @Override
    public Object collect(
            FlowCollector collector,
            Continuation continuation) {
        collectCount++;
        onCollect.run();
        return Unit.INSTANCE;
    }
}
