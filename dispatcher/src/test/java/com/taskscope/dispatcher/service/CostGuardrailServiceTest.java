package com.taskscope.dispatcher.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CostGuardrailServiceTest {

    private CostGuardrailService service;

    @BeforeEach
    void setUp() {
        service = new CostGuardrailService("http://localhost:19090", 0.05);
    }

    @Test
    void premium_aboveThreshold_downgradeToStandard() {
        CostGuardrailService.GuardrailResult result =
                service.applyWithCost("premium", "code_review", 0.063);

        assertThat(result.downgraded()).isTrue();
        assertThat(result.effectiveGrade()).isEqualTo("standard");
        assertThat(result.reason()).contains("0.0630");
    }

    @Test
    void premium_belowThreshold_noChange() {
        CostGuardrailService.GuardrailResult result =
                service.applyWithCost("premium", "code_review", 0.002);

        assertThat(result.downgraded()).isFalse();
        assertThat(result.effectiveGrade()).isEqualTo("premium");
    }

    @Test
    void premium_prometheusUnavailable_failOpen() {
        // null = Prometheus 미응답
        CostGuardrailService.GuardrailResult result =
                service.applyWithCost("premium", "security", null);

        assertThat(result.downgraded()).isFalse();
        assertThat(result.effectiveGrade()).isEqualTo("premium");
    }

    @Test
    void standard_aboveThreshold_noChange() {
        // standard는 가드레일 대상 아님
        CostGuardrailService.GuardrailResult result =
                service.applyWithCost("standard", "code_review", 0.999);

        assertThat(result.downgraded()).isFalse();
        assertThat(result.effectiveGrade()).isEqualTo("standard");
    }

    @Test
    void premium_exactlyAtThreshold_noChange() {
        // 임계값과 동일: 미만 조건이므로 강등 안 됨
        CostGuardrailService.GuardrailResult result =
                service.applyWithCost("premium", "test_gen", 0.05);

        assertThat(result.downgraded()).isFalse();
        assertThat(result.effectiveGrade()).isEqualTo("premium");
    }
}
