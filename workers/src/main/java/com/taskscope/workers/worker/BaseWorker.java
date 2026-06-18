package com.taskscope.workers.worker;

import com.taskscope.shared.SpanAttributes;
import com.taskscope.shared.TaskMessage;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class BaseWorker {

    static final int MAX_LOOP_ITERATIONS = 5;

    private static final Logger log = LoggerFactory.getLogger(BaseWorker.class);

    private final Tracer tracer;

    protected BaseWorker(Tracer tracer) {
        this.tracer = tracer;
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

        // 루프 캡 도달 — 강제 종료 + span에 가드레일 작동 기록
        log.warn("[{}] task={} hit loop cap ({})", spanName(), message.taskId(), MAX_LOOP_ITERATIONS);
        workerSpan.setAttribute(SpanAttributes.AGENT_LOOP_CAP_HIT, true);
        workerSpan.setAttribute(SpanAttributes.GUARDRAIL_ACTION, "force_terminate");
        workerSpan.setAttribute(SpanAttributes.GUARDRAIL_REASON,
                "max_iterations_" + MAX_LOOP_ITERATIONS + "_reached");
    }

    private LlmResult callLlm(TaskMessage message, int iteration, String model) {
        Span llmSpan = tracer.spanBuilder("llm.call")
                .setAttribute(SpanAttributes.TASK_ID, message.taskId())
                .setAttribute(SpanAttributes.LLM_MODEL, model)
                .setAttribute(SpanAttributes.AGENT_LOOP_ITERATION, iteration)
                .startSpan();

        try (Scope ignored = llmSpan.makeCurrent()) {
            LlmResult result = invokeLlm(message, iteration);
            llmSpan.setAttribute(SpanAttributes.LLM_INPUT_TOKENS, result.inputTokens());
            llmSpan.setAttribute(SpanAttributes.LLM_OUTPUT_TOKENS, result.outputTokens());
            llmSpan.setAttribute(SpanAttributes.LLM_COST_USD, result.costUsd());
            return result;
        } finally {
            llmSpan.end();
        }
    }

    protected abstract String spanName();

    // 서브클래스가 실제 LLM 호출(또는 stub)을 구현
    protected abstract LlmResult invokeLlm(TaskMessage message, int iteration);

    private String resolveModel(String modelGrade) {
        return "premium".equals(modelGrade) ? "claude-opus-4-8" : "claude-haiku-4-5-20251001";
    }

    protected record LlmResult(int inputTokens, int outputTokens, double costUsd, boolean finished) {}
}
