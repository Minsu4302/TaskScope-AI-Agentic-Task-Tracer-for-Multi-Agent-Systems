package com.taskscope.workers.worker;

import com.taskscope.shared.TaskMessage;
import com.taskscope.workers.config.RabbitMQConfig;
import io.opentelemetry.api.trace.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class SecurityWorker extends BaseWorker {

    private static final Logger log = LoggerFactory.getLogger(SecurityWorker.class);

    public SecurityWorker(Tracer tracer) {
        super(tracer);
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
    protected LlmResult invokeLlm(TaskMessage message, int iteration) {
        // stub: 보안 분석은 취약점 재확인 포함 2~3회 루프
        boolean done = iteration >= 3;
        return "premium".equals(message.modelGrade())
                ? new LlmResult(2100, 560, 0.0735, done)
                : new LlmResult(1700, 380, 0.0029, done);
    }
}
