package com.taskscope.dispatcher.service;

import com.taskscope.dispatcher.config.RabbitMQConfig;
import com.taskscope.dispatcher.model.TaskRequest;
import com.taskscope.dispatcher.model.TaskResponse;
import com.taskscope.shared.SpanAttributes;
import com.taskscope.shared.TaskMessage;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import org.springframework.amqp.core.AmqpTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

@Service
public class TaskService {

    private static final Map<String, String> TASK_TYPE_TO_QUEUE = Map.of(
            "code_review", RabbitMQConfig.QUEUE_CODE_REVIEW,
            "security",    RabbitMQConfig.QUEUE_SECURITY,
            "test_gen",    RabbitMQConfig.QUEUE_TEST_GEN
    );

    private final AmqpTemplate amqpTemplate;
    private final ComplexityRouter complexityRouter;
    private final CostGuardrailService costGuardrailService;
    private final Tracer tracer;

    public TaskService(AmqpTemplate amqpTemplate, ComplexityRouter complexityRouter,
                       CostGuardrailService costGuardrailService, Tracer tracer) {
        this.amqpTemplate = amqpTemplate;
        this.complexityRouter = complexityRouter;
        this.costGuardrailService = costGuardrailService;
        this.tracer = tracer;
    }

    public TaskResponse dispatch(TaskRequest request) {
        String taskId = UUID.randomUUID().toString();
        String staticGrade = complexityRouter.route(request.diffLines());

        Span taskSpan = tracer.spanBuilder("task.dispatch")
                .setAttribute(SpanAttributes.TASK_ID, taskId)
                .setAttribute(SpanAttributes.TASK_COMPLEXITY, staticGrade)
                .startSpan();

        try (Scope ignored = taskSpan.makeCurrent()) {
            for (String taskType : request.taskTypes()) {
                String queueName = TASK_TYPE_TO_QUEUE.get(taskType);
                if (queueName == null) continue;

                // 과거 비용 집계 기반 모델 등급 재조정 (premium→standard 강등 가능)
                CostGuardrailService.GuardrailResult guardrail =
                        costGuardrailService.apply(staticGrade, taskType);
                String effectiveGrade = guardrail.effectiveGrade();

                taskSpan.setAttribute(SpanAttributes.TASK_TYPE, taskType);
                taskSpan.setAttribute(SpanAttributes.LLM_MODEL, complexityRouter.modelFor(effectiveGrade));

                if (guardrail.downgraded()) {
                    taskSpan.setAttribute(SpanAttributes.GUARDRAIL_ACTION, "model_downgrade");
                    taskSpan.setAttribute(SpanAttributes.GUARDRAIL_REASON, guardrail.reason());
                }

                TaskMessage message = new TaskMessage(
                        taskId, taskType,
                        request.repoUrl(), request.prNumber(),
                        "",                   // commitDiff: 구 엔드포인트는 실제 diff 없음
                        request.diffLines(), effectiveGrade,
                        "single_commit"
                );
                amqpTemplate.convertAndSend(RabbitMQConfig.EXCHANGE_NAME, queueName, message);
            }
        } finally {
            taskSpan.end();
        }

        return new TaskResponse(taskId, "QUEUED", staticGrade, request.taskTypes());
    }
}
