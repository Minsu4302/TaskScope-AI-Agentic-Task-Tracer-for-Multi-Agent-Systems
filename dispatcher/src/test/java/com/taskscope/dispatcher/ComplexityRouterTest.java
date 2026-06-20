package com.taskscope.dispatcher;

import com.taskscope.dispatcher.service.ComplexityRouter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ComplexityRouterTest {

    private final ComplexityRouter router = new ComplexityRouter();

    // --- base route(diffLines) ---

    @Test
    void smallDiff_routesToStandard() {
        assertThat(router.route(199)).isEqualTo("standard");
    }

    @Test
    void exactThreshold_routesToPremium() {
        assertThat(router.route(200)).isEqualTo("premium");
    }

    @Test
    void largeDiff_routesToPremium() {
        assertThat(router.route(500)).isEqualTo("premium");
    }

    // --- modelFor ---

    @Test
    void standard_mapsToHaikuModel() {
        assertThat(router.modelFor("standard")).isEqualTo("claude-haiku-4-5");
    }

    @Test
    void premium_mapsToOpusModel() {
        assertThat(router.modelFor("premium")).isEqualTo("claude-opus-4-8");
    }

    // --- Hypothesis B: test_gen lower threshold ---

    @Test
    void hypothesisB_testGen160Lines_routesToPremium() {
        // 160 lines: standard for code_review (threshold 200), premium for test_gen (threshold 150)
        assertThat(router.route(160, List.of("Foo.java"), "test_gen")).isEqualTo("premium");
        assertThat(router.route(160, List.of("Foo.java"), "code_review")).isEqualTo("standard");
    }

    @Test
    void hypothesisB_testGen149Lines_routesToStandard() {
        assertThat(router.route(149, List.of("Foo.java"), "test_gen")).isEqualTo("standard");
    }

    // --- Hypothesis A: context-heavy file extensions ---

    @Test
    void hypothesisA_shellScript_routesToPremium() {
        assertThat(router.route(10, List.of("scripts/deploy.sh"), "code_review")).isEqualTo("premium");
    }

    @Test
    void hypothesisA_yamlFile_routesToPremium() {
        assertThat(router.route(10, List.of(".github/workflows/ci.yml"), "code_review")).isEqualTo("premium");
    }

    @Test
    void hypothesisA_terraformFile_routesToPremium() {
        assertThat(router.route(10, List.of("infra/main.tf"), "code_review")).isEqualTo("premium");
    }

    @Test
    void hypothesisA_javaOnlyFiles_belowThreshold_routesToStandard() {
        assertThat(router.route(50, List.of("Foo.java", "Bar.java"), "code_review")).isEqualTo("standard");
    }

    @Test
    void hypothesisA_mixedFiles_shellTriggersPremium() {
        // mixed: one .java, one .sh → premium due to .sh
        assertThat(router.route(20, List.of("Foo.java", "run.sh"), "security")).isEqualTo("premium");
    }
}
