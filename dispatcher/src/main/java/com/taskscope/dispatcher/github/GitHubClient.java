package com.taskscope.dispatcher.github;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * GitHub REST API 클라이언트.
 *
 * 비인증 호출은 시간당 60 req 제한으로 즉시 막히므로, PAT(Personal Access Token)로 인증.
 * PAT 인증 시 5,000 req/h 허용 → 두 repo에서 각 15개 커밋 상세 조회(31 req/repo × 2 = 62 req)에 충분.
 */
@Component
public class GitHubClient {

    private static final Logger log = LoggerFactory.getLogger(GitHubClient.class);
    private static final String GITHUB_API = "https://api.github.com";

    private final RestClient restClient;

    public GitHubClient(@Value("${github.token}") String token) {
        this.restClient = RestClient.builder()
                .baseUrl(GITHUB_API)
                .defaultHeader("Authorization", "Bearer " + token)
                .defaultHeader("Accept", "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .build();
    }

    /**
     * 커밋 목록 조회 — sha, commit.message만 포함 (stats/files 없음).
     * 반환된 sha 목록으로 {@link #getCommit}를 호출해 상세 정보를 가져와야 함.
     */
    public List<GitHubCommit> listCommits(String owner, String repo, int count) {
        log.info("GitHub: listing {} commits from {}/{}", count, owner, repo);
        return restClient.get()
                .uri("/repos/{owner}/{repo}/commits?per_page={count}", owner, repo, count)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
    }

    /**
     * 개별 커밋 상세 조회 — stats(additions/deletions/total) + files(patch) 포함.
     * 이 응답으로 diff 내용과 변경 라인 수를 얻을 수 있음.
     */
    public GitHubCommit getCommit(String owner, String repo, String sha) {
        log.debug("GitHub: fetching commit {}/{} sha={}", owner, repo, sha);
        return restClient.get()
                .uri("/repos/{owner}/{repo}/commits/{sha}", owner, repo, sha)
                .retrieve()
                .body(GitHubCommit.class);
    }
}
