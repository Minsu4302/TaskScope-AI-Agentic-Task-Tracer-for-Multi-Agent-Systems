package com.taskscope.workers.github;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Workers에서 need_context 처리 시 특정 파일 내용을 GitHub API로 가져오는 클라이언트.
 * dispatcher의 GitHubClient와 별개로 workers 모듈에 격리.
 */
@Component
public class GitHubFileClient {

    private static final Logger log = LoggerFactory.getLogger(GitHubFileClient.class);
    private static final int MAX_FILES_PER_REQUEST = 3;

    private final RestClient restClient;

    public GitHubFileClient(@Value("${github.token:}") String token) {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl("https://api.github.com")
                .defaultHeader("Accept", "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28");

        if (!token.isBlank()) {
            builder.defaultHeader("Authorization", "Bearer " + token);
        }

        this.restClient = builder.build();
        log.info("[github-file] client initialized (auth={})", token.isBlank() ? "none" : "PAT");
    }

    /**
     * 요청된 파일 목록을 GitHub에서 가져와 하나의 문자열로 반환.
     * @param repoUrl  "https://github.com/owner/repo"
     * @param sha      커밋 SHA (파일 버전 고정)
     * @param paths    가져올 파일 경로 목록 (최대 3개)
     */
    public String fetchFiles(String repoUrl, String sha, List<String> paths) {
        String[] ownerRepo = parseRepo(repoUrl);
        String owner = ownerRepo[0];
        String repo  = ownerRepo[1];
        String ref   = sha.contains(",") ? sha.split(",")[0].trim() : sha;  // 그룹 커밋이면 첫 SHA

        StringBuilder sb = new StringBuilder();
        paths.stream().limit(MAX_FILES_PER_REQUEST).forEach(path -> {
            try {
                String content = fetchFile(owner, repo, path.trim(), ref);
                sb.append("\n=== FILE: ").append(path).append(" ===\n")
                  .append(content).append("\n");
                log.info("[github-file] fetched {}/{} @ {} ({} chars)", owner + "/" + repo, path, ref.substring(0, 7), content.length());
            } catch (Exception e) {
                sb.append("\n=== FILE: ").append(path).append(" (fetch failed: ").append(e.getMessage()).append(") ===\n");
                log.warn("[github-file] failed to fetch {}: {}", path, e.getMessage());
            }
        });

        return sb.toString();
    }

    private String fetchFile(String owner, String repo, String path, String ref) {
        GitHubContent response = restClient.get()
                .uri(b -> b.path("/repos/{owner}/{repo}/contents/{path}")
                           .queryParam("ref", "{ref}")
                           .build(Map.of("owner", owner, "repo", repo, "path", path, "ref", ref)))
                .retrieve()
                .body(GitHubContent.class);

        if (response == null || response.content() == null) return "(empty)";

        String base64 = response.content().replaceAll("\\s", "");
        return new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8);
    }

    private String[] parseRepo(String repoUrl) {
        // "https://github.com/owner/repo" → ["owner", "repo"]
        String stripped = repoUrl.replaceFirst("https?://github\\.com/", "");
        String[] parts = stripped.split("/", 2);
        if (parts.length != 2) throw new IllegalArgumentException("Cannot parse repoUrl: " + repoUrl);
        return parts;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GitHubContent(String content) {}
}
