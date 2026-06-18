package com.taskscope.workers.worker;

import com.taskscope.shared.SpanAttributes;
import com.taskscope.shared.TaskMessage;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class BaseWorker {

    static final int MAX_LOOP_ITERATIONS = 5;

    private static final Logger log = LoggerFactory.getLogger(BaseWorker.class);

    private final Tracer tracer;
    private final MeterRegistry meterRegistry;

    protected BaseWorker(Tracer tracer, MeterRegistry meterRegistry) {
        this.tracer = tracer;
        this.meterRegistry = meterRegistry;
    }

    protected void process(TaskMessage message) {
        String model = resolveModel(message.modelGrade());

        Span workerSpan = tracer.spanBuilder(spanName())
                .setAttribute(SpanAttributes.TASK_ID, message.taskId())
                .setAttribute(SpanAttributes.TASK_TYPE, message.taskType())
                .setAttribute(SpanAttributes.LLM_MODEL, model)
                .startSpan();

        try (Scope ignored = workerSpan.makeCurrent()) {
            runAgentLoop(message, workerSpan, model);
        } finally {
            workerSpan.end();
        }
    }

    private void runAgentLoop(TaskMessage message, Span workerSpan, String model) {
        for (int i = 1; i <= MAX_LOOP_ITERATIONS; i++) {
            workerSpan.setAttribute(SpanAttributes.AGENT_LOOP_ITERATION, i);

            LlmResult result = callLlm(message, i, model);
            if (result.finished()) {
                log.info("[{}] task={} finished at iteration {}", spanName(), message.taskId(), i);
                return;
            }
        }

        log.warn("[{}] task={} hit loop cap ({})", spanName(), message.taskId(), MAX_LOOP_ITERATIONS);
        workerSpan.setAttribute(SpanAttributes.AGENT_LOOP_CAP_HIT, true);
        workerSpan.setAttribute(SpanAttributes.GUARDRAIL_ACTION, "force_terminate");
        workerSpan.setAttribute(SpanAttributes.GUARDRAIL_REASON,
                "max_iterations_" + MAX_LOOP_ITERATIONS + "_reached");
    }

    private LlmResult callLlm(TaskMessage message, int iteration, String model) {
        Span llmSpan = tracer.spanBuilder("llm.call")
                .setAttribute(SpanAttributes.TASK_ID, message.taskId())
                .setAttribute(SpanAttributes.TASK_TYPE, message.taskType())   // spanmetrics 레이블용
                .setAttribute(SpanAttributes.LLM_MODEL, model)
                .setAttribute(SpanAttributes.AGENT_LOOP_ITERATION, iteration)
                .startSpan();

        try (Scope ignored = llmSpan.makeCurrent()) {
            LlmResult result = invokeLlm(message, iteration);
            llmSpan.setAttribute(SpanAttributes.LLM_INPUT_TOKENS, result.inputTokens());
            llmSpan.setAttribute(SpanAttributes.LLM_OUTPUT_TOKENS, result.outputTokens());
            llmSpan.setAttribute(SpanAttributes.LLM_COST_USD, result.costUsd());

            // Micrometer 카운터 — Prometheus에서 집계 가능한 비용/토큰 메트릭
            String taskType = message.taskType();
            meterRegistry.counter("llm.calls",
                    "task_type", taskType, "llm_model", model, "task_complexity", message.modelGrade()
            ).increment();
            meterRegistry.counter("llm.cost.usd",
                    "task_type", taskType, "llm_model", model
            ).increment(result.costUsd());
            meterRegistry.counter("llm.input.tokens",
                    "task_type", taskType, "llm_model", model
            ).increment(result.inputTokens());
            meterRegistry.counter("llm.output.tokens",
                    "task_type", taskType, "llm_model", model
            ).increment(result.outputTokens());

            return result;
        } finally {
            llmSpan.end();
        }
    }

    protected abstract String spanName();

    protected abstract LlmResult invokeLlm(TaskMessage message, int iteration);

    private String resolveModel(String modelGrade) {
        return "premium".equals(modelGrade) ? "claude-opus-4-8" : "claude-haiku-4-5-20251001";
    }

    protected record LlmResult(int inputTokens, int outputTokens, double costUsd, boolean finished) {}
}
