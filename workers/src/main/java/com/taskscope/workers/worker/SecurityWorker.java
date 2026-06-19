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
public class SecurityWorker extends BaseWorker {

    private static final Logger log = LoggerFactory.getLogger(SecurityWorker.class);

    public SecurityWorker(Tracer tracer, MeterRegistry meterRegistry,
                          AnthropicLlmClient anthropicLlmClient, GitHubFileClient gitHubFileClient) {
        super(tracer, meterRegistry, anthropicLlmClient, gitHubFileClient);
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
                You are a security analysis expert specializing in vulnerability detection.

                IMPORTANT: You MUST respond with ONLY valid JSON in this exact format (no other text):
                {
                  "status": "complete",
                  "reasoning": "brief explanation of your decision",
                  "result": "your full security analysis here",
                  "context_request": null
                }

                Status rules:
                - "complete": you have enough information to assess all security concerns
                - "need_context": you need to see related files to trace a vulnerability; set context_request.files
                - "retry": your analysis was cut off or incomplete; explain in reasoning

                Security analysis focus:
                1. Hardcoded secrets, tokens, passwords, or API keys
                2. SQL injection vulnerabilities
                3. Command injection risks
                4. Authentication and authorization flaws
                5. Insecure cryptographic practices
                6. Sensitive data exposure

                For each finding in "result": severity (CRITICAL/HIGH/MEDIUM/LOW), location, recommended fix.""";
    }
}
