package com.bizsim.sim;

import com.bizsim.support.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * D133: the quality-led preset carries exactly its two knobs, and the shipped economy is untouched.
 *
 * <p>The template API does not expose `elasticity` (it is a model parameter, not a builder setting), so a live
 * curl cannot confirm the half of this change that matters most. This loads both economies through the real
 * loader and asserts the numbers — which is also the guard against a later edit silently changing what
 * `standard-v1` plays.
 */
@SpringBootTest
class QualityLedPresetTest extends PostgresTestBase {

    @Autowired
    private ScenarioRepository scenarios;

    @Test
    void the_quality_led_preset_carries_its_two_knobs() {
        Scenario std = scenarios.load("standard-v1");
        Scenario ql = scenarios.load("quality-led-v1");
        System.out.printf("%nstandard-v1     qualityElasticity %s  costPerPoint %s%n",
                std.params().qualityElasticity(), std.params().qualityCostPerPoint());
        System.out.printf("quality-led-v1  qualityElasticity %s  costPerPoint %s%n",
                ql.params().qualityElasticity(), ql.params().qualityCostPerPoint());
        org.assertj.core.api.Assertions.assertThat(ql.params().qualityElasticity())
                .isEqualByComparingTo("1.30");
        org.assertj.core.api.Assertions.assertThat(ql.params().qualityCostPerPoint())
                .isEqualByComparingTo("40000000");
        org.assertj.core.api.Assertions.assertThat(std.params().qualityElasticity())
                .as("the shipped economy is untouched").isEqualByComparingTo("0.80");
    }
}
