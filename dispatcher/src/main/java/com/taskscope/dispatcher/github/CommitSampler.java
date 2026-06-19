package com.taskscope.dispatcher.github;

import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 커밋 목록에서 변경 라인 수 기준 소/중/대 크기 분류 후 다양성 있는 샘플을 추출.
 *
 * 알고리즘: 3개 버킷(SMALL/MEDIUM/LARGE) 간 라운드로빈으로 총 sampleSize개 선택.
 * 두 repo에서 고르게 뽑히도록 각 버킷 내부를 셔플 후 진행.
 */
@Component
public class CommitSampler {

    public enum Size { SMALL, MEDIUM, LARGE }

    private static final int SMALL_MAX_LINES = 50;
    private static final int MEDIUM_MAX_LINES = 300;

    public record SampledCommit(
            String repoFullName,
            String sha,
            String message,
            int diffLines,
            String diff,
            Size size
    ) {}

    /**
     * candidates 중에서 sampleSize개를 소/중/대 라운드로빈으로 선택해 반환.
     * candidates가 sampleSize보다 적으면 전체 반환.
     */
    public List<SampledCommit> sample(List<SampledCommit> candidates, int sampleSize) {
        if (candidates.isEmpty()) return List.of();

        Map<Size, List<SampledCommit>> bySize = candidates.stream()
                .collect(Collectors.groupingBy(c -> sizeOf(c.diffLines())));

        // 각 버킷 내부 셔플 — 동일 버킷 내에서도 두 repo가 섞이도록
        bySize.values().forEach(list -> Collections.shuffle(list, new Random()));

        List<SampledCommit> result = new ArrayList<>();
        List<Size> order = List.of(Size.SMALL, Size.MEDIUM, Size.LARGE);
        int[] idx = new int[order.size()];

        while (result.size() < sampleSize) {
            boolean anyAdded = false;
            for (int i = 0; i < order.size() && result.size() < sampleSize; i++) {
                List<SampledCommit> bucket = bySize.getOrDefault(order.get(i), List.of());
                if (idx[i] < bucket.size()) {
                    result.add(bucket.get(idx[i]++));
                    anyAdded = true;
                }
            }
            if (!anyAdded) break;
        }
        return result;
    }

    public static Size sizeOf(int diffLines) {
        if (diffLines < SMALL_MAX_LINES) return Size.SMALL;
        if (diffLines < MEDIUM_MAX_LINES) return Size.MEDIUM;
        return Size.LARGE;
    }
}
