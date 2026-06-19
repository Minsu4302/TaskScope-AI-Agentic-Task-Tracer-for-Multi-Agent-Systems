package com.taskscope.workers.worker;

import com.anthropic.models.messages.Message;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskscope.shared.SpanAttributes;
import com.taskscope.shared.TaskMessage;
import com.taskscope.workers.github.GitHubFileClient;
import com.taskscope.workers.llm.AnthropicLlmClient;
import com.taskscope.workers.llm.LlmResponse;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public abstract class BaseWorker {

    static final int MAX_LOOP_ITERATIONS = 5;

    private static final Logger log = LoggerFactory.getLogger(BaseWorker.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final Pattern JSON_BLOCK_PATTERN = Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)```");

    private static final double HAIKU_INPUT_COST  = 1.0;
    private static final double HAIKU_OUTPUT_COST = 5.0;
    private static final double SONNET_INPUT_COST  = 3.0;
    private static final double SONNET_OUTPUT_COST = 15.0;

    private final Tracer tracer;
    private final MeterRegistry meterRegistry;
    private final AnthropicLlmClient anthropicLlmClient;
    private final GitHubFileClient gitHubFileClient;

    // 루프 반복 간 추가 컨텍스트 전달 — 스레드별 격리 (AMQP 스레드 1개/큐)
    private final ThreadLocal<String> extraContextHolder = ThreadLocal.withInitial(() -> null);

    /** Spring DI 생성자 */
    protected BaseWorker(Tracer tracer, MeterRegistry meterRegistry,
                         AnthropicLlmClient anthropicLlmClient, GitHubFileClient gitHubFileClient) {
        this.tracer = tracer;
        this.meterRegistry = meterRegistry;
        this.anthropicLlmClient = anthropicLlmClient;
        this.gitHubFileClient = gitHubFileClient;
    }

    /** 테스트용 생성자 — invokeLlm()을 오버라이드하는 서브클래스에서만 사용 */
    protected BaseWorker(Tracer tracer, MeterRegistry meterRegistry) {
        this(tracer, meterRegistry, null, null);
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
        try {
            for (int i = 1; i <= MAX_LOOP_ITERATIONS; i++) {
                workerSpan.setAttribute(SpanAttributes.AGENT_LOOP_ITERATION, i);

                LlmResult result = callLlm(message, i, model);
                if (result.finished()) {
                    log.info("[{}] task={} finished at iteration {} (reason={})",
                            spanName(), message.taskId(), i, result.loopReason());
                    return;
                }

                // need_context: 요청된 파일을 GitHub에서 가져와 다음 iteration에 전달
                if ("need_context".equals(result.loopReason()) && !result.requestedFiles().isEmpty()) {
                    log.info("[{}] task={} need_context → fetching {} files",
                            spanName(), message.taskId(), result.requestedFiles().size());
                    String fetchedContext = gitHubFileClient.fetchFiles(
                            message.repoUrl(), message.commitSha(), result.requestedFiles());
                    extraContextHolder.set(fetchedContext);
                }
                // retry: 동일 컨텍스트로 재시도 (extraContextHolder 유지)
            }

            log.warn("[{}] task={} hit loop cap ({})", spanName(), message.taskId(), MAX_LOOP_ITERATIONS);
            workerSpan.setAttribute(SpanAttributes.AGENT_LOOP_CAP_HIT, true);
            workerSpan.setAttribute(SpanAttributes.GUARDRAIL_ACTION, "force_terminate");
            workerSpan.setAttribute(SpanAttributes.GUARDRAIL_REASON,
                    "max_iterations_" + MAX_LOOP_ITERATIONS + "_reached");
        } finally {
            extraContextHolder.remove();
        }
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
            llmSpan.setAttribute(SpanAttributes.AGENT_LOOP_REASON, result.loopReason());

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
     * Claude API 단일 호출 + JSON 응답 파싱.
     * extraContextHolder에서 추가 컨텍스트를 읽어 user message에 삽입.
     * 테스트 서브클래스는 이 메서드를 오버라이드해 stub 반환 가능.
     */
    protected LlmResult invokeLlm(TaskMessage message, int iteration) {
        String model = resolveModel(message.modelGrade());
        String extraContext = extraContextHolder.get();
        String userMessage = buildUserMessage(message, extraContext);

        Message response = anthropicLlmClient.call(model, systemPrompt(), userMessage);

        int inputTokens  = (int) response.usage().inputTokens();
        int outputTokens = (int) response.usage().outputTokens();
        double costUsd   = calculateCost(model, inputTokens, outputTokens);

        LlmResponse parsed = parseResponse(response);
        log.info("[{}] task={} iter={} model={} in={} out={} cost=${} status={}",
                spanName(), message.taskId(), iteration, model, inputTokens, outputTokens,
                String.format("%.6f", costUsd), parsed.status());

        return new LlmResult(inputTokens, outputTokens, costUsd,
                parsed.isComplete(), parsed.status(), parsed.requestedFiles());
    }

    // --- private helpers ---

    private LlmResponse parseResponse(Message response) {
        String text = response.content().stream()
                .flatMap(block -> block.text().stream())
                .map(com.anthropic.models.messages.TextBlock::text)
                .collect(java.util.stream.Collectors.joining());

        try {
            String json = extractJson(text);
            LlmResponse parsed = OBJECT_MAPPER.readValue(json, LlmResponse.class);
            if (parsed.status() == null) return LlmResponse.parseError(text);
            return parsed;
        } catch (Exception e) {
            log.warn("[{}] JSON parse failed, treating as complete: {}", spanName(), e.getMessage());
            return LlmResponse.parseError(text);
        }
    }

    private String extractJson(String text) {
        // ```json ... ``` 블록 우선
        Matcher matcher = JSON_BLOCK_PATTERN.matcher(text);
        if (matcher.find()) return matcher.group(1).trim();

        // 첫 { ~ 마지막 } 추출
        int start = text.indexOf('{');
        int end   = text.lastIndexOf('}');
        if (start >= 0 && end > start) return text.substring(start, end + 1);

        return text;
    }

    private String buildUserMessage(TaskMessage message, String extraContext) {
        String diff = (message.commitDiff() == null || message.commitDiff().isBlank())
                ? "(diff 없음)" : message.commitDiff();

        StringBuilder sb = new StringBuilder();
        sb.append("""
                Task ID   : %s
                Task Type : %s
                Repository: %s
                Commit SHA: %s
                Diff Lines: %d

                --- DIFF ---
                %s
                """.formatted(message.taskId(), message.taskType(), message.repoUrl(),
                message.commitSha(), message.diffLines(), diff));

        if (extraContext != null && !extraContext.isBlank()) {
            sb.append("\n--- ADDITIONAL CONTEXT ---\n").append(extraContext);
        }

        return sb.toString();
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

    protected record LlmResult(int inputTokens, int outputTokens, double costUsd,
                               boolean finished, String loopReason, List<String> requestedFiles) {
        /** 테스트 호환용 4-arg 생성자 */
        LlmResult(int inputTokens, int outputTokens, double costUsd, boolean finished) {
            this(inputTokens, outputTokens, costUsd, finished, "complete", List.of());
        }
    }
}
