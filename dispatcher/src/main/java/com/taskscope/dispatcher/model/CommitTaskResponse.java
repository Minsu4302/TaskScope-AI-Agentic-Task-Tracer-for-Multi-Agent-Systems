package com.taskscope.dispatcher.model;

import java.util.List;

public record CommitTaskResponse(
        List<DispatchedCommit> dispatched,
        int totalLlmTasks,    // dispatched.size() × taskTypes.size()
        int sampledCommits
) {
    public record DispatchedCommit(
            String taskId,
            String repo,
            String commitSha,
            String commitMessage,
            int diffLines,
            String size,       // "SMALL" | "MEDIUM" | "LARGE"
            String modelGrade, // "standard" | "premium"
            List<String> taskTypes
    ) {}
}
