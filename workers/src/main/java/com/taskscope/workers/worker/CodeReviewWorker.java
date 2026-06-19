package com.taskscope.workers.worker;

import com.taskscope.shared.TaskMessage;
import com.taskscope.workers.config.RabbitMQConfig;
import com.taskscope.workers.llm.AnthropicLlmClient;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.trace.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class CodeReviewWorker extends BaseWorker {

    private static final Logger log = LoggerFactory.getLogger(CodeReviewWorker.class);

    public CodeReviewWorker(Tracer tracer, MeterRegistry meterRegistry, AnthropicLlmClient anthropicLlmClient) {
        super(tracer, meterRegistry, anthropicLlmClient);
    }

    @RabbitListener(queues = RabbitMQConfig.QUEUE_CODE_REVIEW)
    public void onMessage(TaskMessage message) {
        log.info("Code review task received: {}", message.taskId());
        process(message);
    }

    @Override
    protected String spanName() {
        return "worker.code_review";
    }

    @Override
    protected String systemPrompt() {
        return """
                You are a code review expert. Analyze the provided git diff for code quality, \
                potential bugs, and adherence to best practices.

                Review the diff for:
                1. Logic errors or bugs
                2. Code style and convention violations
                3. Security vulnerabilities
                4. Performance concerns
                5. Missing error handling

                Provide a concise review with specific line references and actionable feedback. \
                Be direct and prioritize the most impactful issues.""";
    }
}
