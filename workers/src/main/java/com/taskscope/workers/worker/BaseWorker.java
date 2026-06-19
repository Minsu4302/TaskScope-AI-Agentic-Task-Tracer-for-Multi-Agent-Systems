package com.taskscope.workers.worker;

import com.anthropic.models.messages.Message;
import com.taskscope.shared.SpanAttributes;
import com.taskscope.shared.TaskMessage;
import com.taskscope.workers.llm.AnthropicLlmClient;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class BaseWorker {

    static final int MAX_LOOP_ITERATIONS = 5;

    private static final Logger log = LoggerFactory.getLogger(BaseWorker.class);

    // 비용: $/MTok — Haiku 4.5, Sonnet 4.6
    private static final double HAIKU_INPUT_COST  = 1.0;
    private static final double HAIKU_OUTPUT_COST = 5.0;
    private static final double SONNET_INPUT_COST  = 3.0;
    private static final double SONNET_OUTPUT_COST = 15.0;

    private final Tracer tracer;
    private final MeterRegistry meterRegistry;
    private final AnthropicLlmClient anthropicLlmClient;

    /** Spring DI 생성자 */
    protected BaseWorker(Tracer tracer, MeterRegistry meterRegistry, AnthropicLlmClient anthropicLlmClient) {
        this.tracer = tracer;
        this.meterRegistry = meterRegistry;
        this.anthropicLlmClient = anthropicLlmClient;
    }

    /** 테스트용 생성자 — invokeLlm()을 오버라이드하는 서브클래스에서만 사용 */
    protected BaseWorker(Tracer tracer, MeterRegistry meterRegistry) {
        this(tracer, meterRegistry, null);
    }

    protected void process(TaskMessage message) {
        String model = resolveModel(message.modelGrade());

        Span workerSpan = tracer.spanBuilder(spanName())
                .setAttribute(SpanAttributes.TASK_ID, message.taskId())
                .setAttribute(SpanAttributes.TASK_TYPE, message.taskType())
                .setAttribute(SpanAttributes.LLM_MODEL, model)
                .setAttribute(SpanAttributes.COMMIT_SHA, message.commitSha())
                .setAttribute(SpanAttributes.TASK_UNIT, message.taskUnit())
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
                .setAttribute(SpanAttributes.TASK_TYPE, message.taskType())
                .setAttribute(SpanAttributes.LLM_MODEL, model)
                .setAttribute(SpanAttributes.AGENT_LOOP_ITERATION, iteration)
                .startSpan();

        try (Scope ignored = llmSpan.makeCurrent()) {
            LlmResult result = invokeLlm(message, iteration);
            llmSpan.setAttribute(SpanAttributes.LLM_INPUT_TOKENS, result.inputTokens());
            llmSpan.setAttribute(SpanAttributes.LLM_OUTPUT_TOKENS, result.outputTokens());
            llmSpan.setAttribute(SpanAttributes.LLM_COST_USD, result.costUsd());

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

    protected abstract String systemPrompt();

    /**
     * 실제 Claude API 호출. Phase 2에서는 항상 finished=true.
     * 테스트 서브클래스는 이 메서드를 오버라이드해 stub 반환 가능.
     */
    protected LlmResult invokeLlm(TaskMessage message, int iteration) {
        String model = resolveModel(message.modelGrade());
        String userMessage = buildUserMessage(message);

        Message response = anthropicLlmClient.call(model, systemPrompt(), userMessage);

        int inputTokens  = (int) response.usage().inputTokens();
        int outputTokens = (int) response.usage().outputTokens();
        double costUsd   = calculateCost(model, inputTokens, outputTokens);

        log.info("[{}] task={} model={} in={} out={} cost=${}",
                spanName(), message.taskId(), model, inputTokens, outputTokens,
                String.format("%.6f", costUsd));

        return new LlmResult(inputTokens, outputTokens, costUsd, true);  // Phase 2: 단일 호출 후 완료
    }

    private String buildUserMessage(TaskMessage message) {
        String diff = message.commitDiff() == null || message.commitDiff().isBlank()
                ? "(diff 없음)"
                : message.commitDiff();
        return """
                Task ID   : %s
                Task Type : %s
                Repository: %s
                Commit SHA: %s
                Diff Lines: %d

                --- DIFF ---
                %s
                """.formatted(
                message.taskId(), message.taskType(), message.repoUrl(),
                message.commitSha(), message.diffLines(), diff);
    }

    private double calculateCost(String model, int inputTokens, int outputTokens) {
        boolean isHaiku = model.contains("haiku");
        double inputPrice  = isHaiku ? HAIKU_INPUT_COST  : SONNET_INPUT_COST;
        double outputPrice = isHaiku ? HAIKU_OUTPUT_COST : SONNET_OUTPUT_COST;
        return (inputTokens / 1_000_000.0 * inputPrice)
             + (outputTokens / 1_000_000.0 * outputPrice);
    }

    protected String resolveModel(String modelGrade) {
        return "premium".equals(modelGrade) ? "claude-sonnet-4-6" : "claude-haiku-4-5";
    }

    protected record LlmResult(int inputTokens, int outputTokens, double costUsd, boolean finished) {}
}
