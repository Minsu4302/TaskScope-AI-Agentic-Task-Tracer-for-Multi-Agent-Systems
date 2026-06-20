package com.taskscope.dispatcher.service;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

@Component
public class ComplexityRouter {

    private static final int PREMIUM_THRESHOLD = 200;
    // Hypothesis B: test_gen produces longer output → route to premium at lower diffLines
    private static final int PREMIUM_THRESHOLD_TEST_GEN = 150;
    // Hypothesis A: infra/config files often reference external scripts not visible in the diff
    private static final Set<String> CONTEXT_HEAVY_EXTENSIONS = Set.of(".sh", ".yml", ".yaml", ".tf", "Dockerfile");

    // Legacy: called from TaskService (no file list available) and dispatchGroups base grade
    public String route(int diffLines) {
        return diffLines >= PREMIUM_THRESHOLD ? "premium" : "standard";
    }

    // A+B: called from CommitTaskService per-taskType with file list from diff
    public String route(int diffLines, List<String> changedFiles, String taskType) {
        // Hypothesis B: lower threshold for test_gen (output-length-sensitive worker)
        int threshold = "test_gen".equals(taskType) ? PREMIUM_THRESHOLD_TEST_GEN : PREMIUM_THRESHOLD;
        if (diffLines >= threshold) return "premium";

        // Hypothesis A: infra/config file types boost to premium regardless of diffLines
        boolean hasContextHeavyFile = changedFiles.stream()
                .anyMatch(f -> CONTEXT_HEAVY_EXTENSIONS.stream().anyMatch(f::endsWith));
        if (hasContextHeavyFile) return "premium";

        return "standard";
    }

    public String modelFor(String modelGrade) {
        return "premium".equals(modelGrade) ? "claude-sonnet-4-6" : "claude-haiku-4-5";
    }
}
