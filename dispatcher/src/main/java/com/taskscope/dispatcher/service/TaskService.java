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
    private final Tracer tracer;

    public TaskService(AmqpTemplate amqpTemplate, ComplexityRouter complexityRouter, Tracer tracer) {
        this.amqpTemplate = amqpTemplate;
        this.complexityRouter = complexityRouter;
        this.tracer = tracer;
    }

    public TaskResponse dispatch(TaskRequest request) {
        String taskId = UUID.randomUUID().toString();
        String modelGrade = complexityRouter.route(request.diffLines());
        String model = complexityRouter.modelFor(modelGrade);

        // task.dispatch span: 모든 워커 publish의 부모 — 같은 trace에 묶이게 됨
        Span taskSpan = tracer.spanBuilder("task.dispatch")
                .setAttribute(SpanAttributes.TASK_ID, taskId)
                .setAttribute(SpanAttributes.TASK_COMPLEXITY, modelGrade)
                .setAttribute(SpanAttributes.LLM_MODEL, model)
                .startSpan();

        try (Scope ignored = taskSpan.makeCurrent()) {
            for (String taskType : request.taskTypes()) {
                String queueName = TASK_TYPE_TO_QUEUE.get(taskType);
                if (queueName == null) continue;

                taskSpan.setAttribute(SpanAttributes.TASK_TYPE, taskType);

                TaskMessage message = new TaskMessage(
                        taskId, taskType,
                        request.repoUrl(), request.prNumber(),
                        request.diffLines(), modelGrade
                );
                // Micrometer Tracing이 traceparent를 AMQP 메시지 헤더에 자동 주입
                amqpTemplate.convertAndSend(RabbitMQConfig.EXCHANGE_NAME, queueName, message);
            }
        } finally {
            taskSpan.end();
        }

        return new TaskResponse(taskId, "QUEUED", modelGrade, request.taskTypes());
    }
}
