package com.taskscope.dispatcher;

import com.taskscope.dispatcher.service.ComplexityRouter;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ComplexityRouterTest {

    private final ComplexityRouter router = new ComplexityRouter();

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

    @Test
    void standard_mapsToHaikuModel() {
        assertThat(router.modelFor("standard")).isEqualTo("claude-haiku-4-5-20251001");
    }

    @Test
    void premium_mapsToOpusModel() {
        assertThat(router.modelFor("premium")).isEqualTo("claude-opus-4-8");
    }
}
