package com.taskscope.workers.worker;

import com.taskscope.shared.TaskMessage;
import com.taskscope.workers.config.RabbitMQConfig;
import io.opentelemetry.api.trace.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class CodeReviewWorker extends BaseWorker {

    private static final Logger log = LoggerFactory.getLogger(CodeReviewWorker.class);

    public CodeReviewWorker(Tracer tracer) {
        super(tracer);
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
    protected LlmResult invokeLlm(TaskMessage message, int iteration) {
        // stub: 2회 반복 후 완료 (코드리뷰는 보통 1~2회 루프)
        boolean done = iteration >= 2;
        return "premium".equals(message.modelGrade())
                ? new LlmResult(1800, 480, 0.0630, done)   // Opus
                : new LlmResult(1500, 320, 0.0021, done);  // Haiku
    }
}
