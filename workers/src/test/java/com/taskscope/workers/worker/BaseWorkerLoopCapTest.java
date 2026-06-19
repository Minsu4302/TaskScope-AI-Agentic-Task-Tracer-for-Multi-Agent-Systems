package com.taskscope.workers.worker;

import com.taskscope.shared.TaskMessage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class BaseWorkerLoopCapTest {

    private static final Tracer NOOP_TRACER = OpenTelemetry.noop().getTracer("test");
    private static final SimpleMeterRegistry METER_REGISTRY = new SimpleMeterRegistry();

    private static final TaskMessage SAMPLE = new TaskMessage(
            "task-1", "test", "https://github.com/example/repo",
            "abc123", "", 100, "standard", "single_commit"
    );

    @Test
    void loopCap_stopsAtMaxIterations_whenNeverFinishes() {
        AtomicInteger callCount = new AtomicInteger(0);

        BaseWorker worker = neverFinishingWorker(callCount);
        worker.process(SAMPLE);

        assertThat(callCount.get()).isEqualTo(BaseWorker.MAX_LOOP_ITERATIONS);
    }

    @Test
    void earlyFinish_stopsBeforeCapWhenDoneEarly() {
        AtomicInteger callCount = new AtomicInteger(0);

        BaseWorker worker = finishesAtIterationWorker(callCount, 2);
        worker.process(SAMPLE);

        assertThat(callCount.get()).isEqualTo(2);
    }

    @Test
    void singleIteration_finishesImmediately() {
        AtomicInteger callCount = new AtomicInteger(0);

        BaseWorker worker = finishesAtIterationWorker(callCount, 1);
        worker.process(SAMPLE);

        assertThat(callCount.get()).isEqualTo(1);
    }

    // --- helpers ---

    private BaseWorker neverFinishingWorker(AtomicInteger counter) {
        return new BaseWorker(NOOP_TRACER, METER_REGISTRY) {
            @Override protected String spanName() { return "test.never"; }
            @Override protected LlmResult invokeLlm(TaskMessage msg, int iteration) {
                counter.incrementAndGet();
                return new LlmResult(100, 50, 0.001, false);
            }
        };
    }

    private BaseWorker finishesAtIterationWorker(AtomicInteger counter, int finishAt) {
        return new BaseWorker(NOOP_TRACER, METER_REGISTRY) {
            @Override protected String spanName() { return "test.early"; }
            @Override protected LlmResult invokeLlm(TaskMessage msg, int iteration) {
                counter.incrementAndGet();
                return new LlmResult(100, 50, 0.001, iteration >= finishAt);
            }
        };
    }
}
