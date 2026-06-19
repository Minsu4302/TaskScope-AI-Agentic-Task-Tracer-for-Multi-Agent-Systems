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
public class SecurityWorker extends BaseWorker {

    private static final Logger log = LoggerFactory.getLogger(SecurityWorker.class);

    public SecurityWorker(Tracer tracer, MeterRegistry meterRegistry, AnthropicLlmClient anthropicLlmClient) {
        super(tracer, meterRegistry, anthropicLlmClient);
    }

    @RabbitListener(queues = RabbitMQConfig.QUEUE_SECURITY)
    public void onMessage(TaskMessage message) {
        log.info("Security analysis task received: {}", message.taskId());
        process(message);
    }

    @Override
    protected String spanName() {
        return "worker.security";
    }

    @Override
    protected String systemPrompt() {
        return """
                You are a security analysis expert specializing in code vulnerability detection.

                Analyze the provided git diff for:
                1. Hardcoded secrets, tokens, passwords, or API keys
                2. SQL injection vulnerabilities
                3. Command injection risks
                4. Authentication and authorization flaws
                5. Insecure cryptographic practices
                6. Sensitive data exposure

                For each finding report: severity (CRITICAL/HIGH/MEDIUM/LOW), \
                exact location, and recommended fix. If no issues found, state that explicitly.""";
    }
}
