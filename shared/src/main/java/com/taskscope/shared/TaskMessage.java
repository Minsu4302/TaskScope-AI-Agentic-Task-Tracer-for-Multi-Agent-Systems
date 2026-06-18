package com.taskscope.shared;

public record TaskMessage(
        String taskId,
        String taskType,      // "code_review" | "security" | "test_gen"
        String repoUrl,
        String prNumber,
        int diffLines,
        String modelGrade     // "standard" | "premium" — 복잡도 라우터가 결정
        // traceparent는 Spring AMQP + Micrometer가 메시지 헤더로 자동 전파
) {}
