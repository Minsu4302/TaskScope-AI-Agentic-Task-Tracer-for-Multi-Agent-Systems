package com.taskscope.workers.worker;

import com.taskscope.shared.TaskMessage;
import com.taskscope.workers.config.RabbitMQConfig;
import com.taskscope.workers.github.GitHubFileClient;
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

    public CodeReviewWorker(Tracer tracer, MeterRegistry meterRegistry,
                            AnthropicLlmClient anthropicLlmClient, GitHubFileClient gitHubFileClient) {
        super(tracer, meterRegistry, anthropicLlmClient, gitHubFileClient);
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
                You are a code review expert. Analyze the provided git diff for quality and correctness.

                IMPORTANT: You MUST respond with ONLY valid JSON in this exact format (no other text):
                {
                  "status": "complete",
                  "reasoning": "brief explanation of your decision",
                  "result": "your full review here",
                  "context_request": null
                }

                Status rules:
                - "complete": you have enough information to provide a full review
                - "need_context": you need to see specific files to give a proper review; set context_request.files to an array of file paths
                - "retry": your analysis was incomplete or cut off; explain in reasoning

                Review focus:
                1. Logic errors or bugs
                2. Code style and convention violations
                3. Security vulnerabilities
                4. Performance concerns
                5. Missing error handling

                Provide specific line references and actionable feedback in "result".""";
    }
}
