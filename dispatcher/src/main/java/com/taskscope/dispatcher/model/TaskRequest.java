package com.taskscope.dispatcher.model;

import java.util.List;

public record TaskRequest(
        String repoUrl,
        String prNumber,
        int diffLines,
        List<String> taskTypes  // ["code_review", "security", "test_gen"]
) {}
