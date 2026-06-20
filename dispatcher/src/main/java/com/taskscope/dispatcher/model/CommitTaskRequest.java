package com.taskscope.dispatcher.model;

import java.util.List;

public record CommitTaskRequest(
        List<String> repos,         // ["Minsu4302/Harness_Infra", "Minsu4302/bitepick-back"]
        List<String> shas,          // 지정 시 listCommits 생략하고 해당 SHA만 조회 (Phase 6 정밀 비교용)
        Integer commitsPerRepo,     // repo당 가져올 커밋 수 (기본 15)
        Integer sampleSize,         // 최종 샘플 커밋 수 (기본 5)
        String taskUnit,            // "single_commit" | "commit_group" (기본 "single_commit")
        Integer commitGroupSize,    // commit_group 모드에서 묶을 커밋 수 (기본 3)
        List<String> taskTypes      // ["code_review", "security", "test_gen"]
) {
    public int effectiveCommitsPerRepo() { return commitsPerRepo != null && commitsPerRepo > 0 ? commitsPerRepo : 15; }
    public int effectiveSampleSize()     { return sampleSize != null && sampleSize > 0 ? sampleSize : 5; }
    public String effectiveTaskUnit()    { return taskUnit != null ? taskUnit : "single_commit"; }
    public int effectiveCommitGroupSize(){ return commitGroupSize != null && commitGroupSize > 0 ? commitGroupSize : 3; }
    public List<String> effectiveTaskTypes() {
        return taskTypes != null && !taskTypes.isEmpty()
                ? taskTypes
                : List.of("code_review", "security", "test_gen");
    }
}
