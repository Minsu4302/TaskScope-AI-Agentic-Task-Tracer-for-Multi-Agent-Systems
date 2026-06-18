package com.taskscope.workers.worker;

import com.taskscope.shared.TaskMessage;
import com.taskscope.workers.config.RabbitMQConfig;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.trace.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class TestGenWorker extends BaseWorker {

    private static final Logger log = LoggerFactory.getLogger(TestGenWorker.class);

    public TestGenWorker(Tracer tracer, MeterRegistry meterRegistry) {
        super(tracer, meterRegistry);
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
    protected LlmResult invokeLlm(TaskMessage message, int iteration) {
        // stub: 테스트 생성은 구현 확인 포함 3회 루프
        boolean done = iteration >= 3;
        return "premium".equals(message.modelGrade())
                ? new LlmResult(2400, 720, 0.0900, done)
                : new LlmResult(2000, 480, 0.0035, done);
    }
}
