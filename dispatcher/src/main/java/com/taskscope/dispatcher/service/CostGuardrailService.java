package com.taskscope.dispatcher.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Service
public class CostGuardrailService {

    private static final Logger log = LoggerFactory.getLogger(CostGuardrailService.class);

    private final RestClient restClient;
    private final double costThresholdUsd;

    public CostGuardrailService(
            @Value("${taskscope.guardrail.prometheus-url:http://localhost:19090}") String prometheusUrl,
            @Value("${taskscope.guardrail.cost-threshold-usd:0.05}") double costThresholdUsd
    ) {
        this.restClient = RestClient.builder().baseUrl(prometheusUrl).build();
        this.costThresholdUsd = costThresholdUsd;
    }

    /**
     * 과거 비용 데이터 기반 모델 등급 재조정.
     * Prometheus 미응답 / 데이터 없음 → fail-open (기존 등급 유지).
     */
    public GuardrailResult apply(String originalGrade, String taskType) {
        Double avgCost = queryAvgCostPerCall(taskType);
        return applyWithCost(originalGrade, taskType, avgCost);
    }

    // package-private: 단위 테스트에서 HTTP 없이 비즈니스 로직만 검증
    GuardrailResult applyWithCost(String originalGrade, String taskType, Double avgCost) {
        if (!"premium".equals(originalGrade)) {
            return GuardrailResult.noChange(originalGrade);
        }
        if (avgCost == null || avgCost <= costThresholdUsd) {
            return GuardrailResult.noChange(originalGrade);
        }

        log.warn("[guardrail] task_type={} avg_cost_per_call={:.4f} exceeds threshold={}, downgrading premium→standard",
                taskType, avgCost, costThresholdUsd);
        return GuardrailResult.downgraded(
                String.format("avg_cost_%.4f_exceeds_threshold_%.4f", avgCost, costThresholdUsd)
        );
    }

    private Double queryAvgCostPerCall(String taskType) {
        // 최근 10분간 task type별 LLM 호출 1회당 평균 비용
        String query = String.format(
                "sum(increase(llm_cost_usd_total{task_type=\"%s\"}[10m]))" +
                " / sum(increase(llm_calls_total{task_type=\"%s\"}[10m]))",
                taskType, taskType
        );

        try {
            PrometheusQueryResponse response = restClient.get()
                    .uri("/api/v1/query?query=" + URLEncoder.encode(query, StandardCharsets.UTF_8))
                    .retrieve()
                    .body(PrometheusQueryResponse.class);

            if (response == null || !"success".equals(response.status())) return null;

            List<Map<String, Object>> results = response.data().result();
            if (results == null || results.isEmpty()) return null;

            Object valueArr = results.getFirst().get("value");
            if (!(valueArr instanceof List<?> arr) || arr.size() < 2) return null;

            String valueStr = arr.get(1).toString();
            if ("NaN".equals(valueStr) || "+Inf".equals(valueStr) || "-Inf".equals(valueStr)) return null;

            return Double.parseDouble(valueStr);
        } catch (RestClientException e) {
            log.warn("[guardrail] Prometheus 미응답, 비용 체크 skip: {}", e.getMessage());
            return null;
        } catch (NumberFormatException e) {
            log.warn("[guardrail] Prometheus 응답 파싱 실패: {}", e.getMessage());
            return null;
        }
    }

    private record PrometheusQueryResponse(String status, PrometheusData data) {}
    private record PrometheusData(String resultType, List<Map<String, Object>> result) {}

    public record GuardrailResult(String effectiveGrade, boolean downgraded, String reason) {
        static GuardrailResult noChange(String grade) {
            return new GuardrailResult(grade, false, null);
        }
        static GuardrailResult downgraded(String reason) {
            return new GuardrailResult("standard", true, reason);
        }
    }
}
