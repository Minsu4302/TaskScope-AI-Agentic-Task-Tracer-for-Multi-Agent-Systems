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
public class TestGenWorker extends BaseWorker {

    private static final Logger log = LoggerFactory.getLogger(TestGenWorker.class);

    public TestGenWorker(Tracer tracer, MeterRegistry meterRegistry, AnthropicLlmClient anthropicLlmClient) {
        super(tracer, meterRegistry, anthropicLlmClient);
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
                You are a test generation expert. Based on the provided git diff, \
                suggest unit test cases for the changed code.

                Generate:
                1. Happy path test cases
                2. Edge case and boundary condition tests
                3. Error/exception handling tests

                Provide concrete JUnit 5 test method stubs with clear @DisplayName annotations \
                describing what each test validates. Focus on the most critical behavior changes \
                introduced by the diff.""";
    }
}
