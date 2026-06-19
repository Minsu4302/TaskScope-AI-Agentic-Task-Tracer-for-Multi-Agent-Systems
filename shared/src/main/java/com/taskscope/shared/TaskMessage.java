package com.taskscope.shared;

public record TaskMessage(
        String taskId,
        String taskType,      // "code_review" | "security" | "test_gen"
        String repoUrl,
        String commitSha,     // 단일 커밋 SHA, 또는 그룹의 경우 "sha1,sha2,sha3"
        String commitDiff,    // 실제 diff 내용 — Phase 2에서 LLM 입력으로 사용
        int diffLines,
        String modelGrade,    // "standard" | "premium" — 복잡도 라우터가 결정
        String taskUnit       // "single_commit" | "commit_group"
        // traceparent는 Spring AMQP + Micrometer가 메시지 헤더로 자동 전파
) {}
