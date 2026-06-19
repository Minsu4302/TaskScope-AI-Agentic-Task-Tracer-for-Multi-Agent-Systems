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
public class TestGenWorker extends BaseWorker {

    private static final Logger log = LoggerFactory.getLogger(TestGenWorker.class);

    public TestGenWorker(Tracer tracer, MeterRegistry meterRegistry,
                         AnthropicLlmClient anthropicLlmClient, GitHubFileClient gitHubFileClient) {
        super(tracer, meterRegistry, anthropicLlmClient, gitHubFileClient);
    }

    @RabbitListener(queues = RabbitMQConfig.QUEUE_TEST_GEN)
    public void onMessage(TaskMessage message) {
        log.info("Test generation task received: {}", message.taskId());
        process(message);
    }

    @Override
    protected String spanName() {
        return "worker.test_gen";
    }

    @Override
    protected String systemPrompt() {
        return """
                You are a test generation expert. Suggest unit tests for the changed code in the diff.

                IMPORTANT: You MUST respond with ONLY valid JSON in this exact format (no other text):
                {
                  "status": "complete",
                  "reasoning": "brief explanation of your decision",
                  "result": "your JUnit 5 test suggestions here",
                  "context_request": null
                }

                Status rules:
                - "complete": you have enough context to suggest meaningful tests
                - "need_context": you need to see existing test files or source files to avoid duplication; set context_request.files
                - "retry": your output was incomplete; explain in reasoning

                Test generation focus:
                1. Happy path test cases for the changed behavior
                2. Edge case and boundary condition tests
                3. Error/exception handling tests

                In "result", provide JUnit 5 test method stubs with @DisplayName annotations.""";
    }
}
