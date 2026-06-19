package com.taskscope.dispatcher.github;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * GitHub API의 개별 커밋 응답 DTO.
 * 커밋 목록 엔드포인트(stats/files=null)와 단일 커밋 엔드포인트(전체 필드) 양쪽에서 재사용.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubCommit(
        String sha,
        @JsonProperty("commit") CommitDetail commit,
        @JsonProperty("stats") Stats stats,
        @JsonProperty("files") List<FileChange> files
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CommitDetail(String message) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Stats(int additions, int deletions, int total) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FileChange(
            String filename,
            int additions,
            int deletions,
            int changes,
            String patch
    ) {}

    /** 변경 총 라인 수 (additions + deletions). stats가 없으면 0 반환. */
    public int diffLines() {
        return stats != null ? stats.total() : 0;
    }

    /** 커밋 메시지 첫 줄. */
    public String message() {
        return commit != null ? commit.message() : "";
    }

    /**
     * 파일별 헤더 + patch를 이어 붙인 유니파이드 diff 문자열.
     * 바이너리 파일(patch=null)은 헤더만 포함.
     */
    public String buildDiff() {
        if (files == null || files.isEmpty()) return "";
        var sb = new StringBuilder();
        for (FileChange f : files) {
            sb.append("--- a/").append(f.filename()).append("\n");
            sb.append("+++ b/").append(f.filename()).append("\n");
            if (f.patch() != null) {
                sb.append(f.patch()).append("\n");
            }
        }
        return sb.toString();
    }
}
