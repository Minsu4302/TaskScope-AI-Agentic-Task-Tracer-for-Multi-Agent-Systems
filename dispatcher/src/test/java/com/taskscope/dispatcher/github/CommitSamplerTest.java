package com.taskscope.dispatcher.github;

import com.taskscope.dispatcher.github.CommitSampler.SampledCommit;
import com.taskscope.dispatcher.github.CommitSampler.Size;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CommitSamplerTest {

    private final CommitSampler sampler = new CommitSampler();

    @Test
    void sample_distributesAcrossAllThreeSizeBuckets() {
        // SMALL ×3, MEDIUM ×3, LARGE ×3 = 9개 후보
        List<SampledCommit> candidates = new ArrayList<>();
        for (int i = 0; i < 3; i++) candidates.add(commit("repo-a", "s" + i, 20 + i));   // SMALL
        for (int i = 0; i < 3; i++) candidates.add(commit("repo-b", "m" + i, 80 + i));   // MEDIUM
        for (int i = 0; i < 3; i++) candidates.add(commit("repo-a", "l" + i, 400 + i));  // LARGE

        List<SampledCommit> result = sampler.sample(candidates, 6);

        assertThat(result).hasSize(6);
        assertThat(countBySize(result, Size.SMALL)).isEqualTo(2);
        assertThat(countBySize(result, Size.MEDIUM)).isEqualTo(2);
        assertThat(countBySize(result, Size.LARGE)).isEqualTo(2);
    }

    @Test
    void sample_returnsAllWhenCandidatesFewerThanSampleSize() {
        List<SampledCommit> candidates = List.of(
                commit("repo-a", "sha1", 10),
                commit("repo-b", "sha2", 100)
        );
        List<SampledCommit> result = sampler.sample(candidates, 10);
        assertThat(result).hasSize(2);
    }

    @Test
    void sample_returnsEmptyForEmptyCandidates() {
        assertThat(sampler.sample(List.of(), 5)).isEmpty();
    }

    @Test
    void sample_handlesOnlyOneBucketFilled() {
        // MEDIUM 버킷만 존재, sampleSize=5 요청 → 3개만 반환
        List<SampledCommit> candidates = List.of(
                commit("repo-a", "m1", 100),
                commit("repo-b", "m2", 150),
                commit("repo-a", "m3", 200)
        );
        List<SampledCommit> result = sampler.sample(candidates, 5);
        assertThat(result).hasSize(3);
        assertThat(countBySize(result, Size.MEDIUM)).isEqualTo(3);
    }

    @Test
    void sample_doesNotExceedSampleSize() {
        List<SampledCommit> candidates = new ArrayList<>();
        for (int i = 0; i < 20; i++) candidates.add(commit("repo-a", "sha" + i, i * 20));

        List<SampledCommit> result = sampler.sample(candidates, 5);
        assertThat(result).hasSize(5);
    }

    @Test
    void sizeOf_classifiesCorrectly() {
        assertThat(CommitSampler.sizeOf(0)).isEqualTo(Size.SMALL);
        assertThat(CommitSampler.sizeOf(49)).isEqualTo(Size.SMALL);
        assertThat(CommitSampler.sizeOf(50)).isEqualTo(Size.MEDIUM);
        assertThat(CommitSampler.sizeOf(299)).isEqualTo(Size.MEDIUM);
        assertThat(CommitSampler.sizeOf(300)).isEqualTo(Size.LARGE);
        assertThat(CommitSampler.sizeOf(1000)).isEqualTo(Size.LARGE);
    }

    // --- helpers ---

    private SampledCommit commit(String repo, String sha, int diffLines) {
        return new SampledCommit(repo, sha, "msg:" + sha, diffLines, "diff", CommitSampler.sizeOf(diffLines));
    }

    private long countBySize(List<SampledCommit> list, Size size) {
        return list.stream().filter(c -> c.size() == size).count();
    }
}
