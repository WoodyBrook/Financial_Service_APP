package com.bank.aml.demo;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

/**
 * The demo profile must silence the nightly cron so no background run can race the stage,
 * while the default profile keeps it — asserted in two separate test contexts.
 */
@SpringBootTest
@ActiveProfiles("demo")
class DemoSchedulingConfigTest {

    @Autowired private Environment environment;

    @Test
    @DisplayName("demo profile disables the scheduled monitoring cron")
    void demoCronIsDisabled() {
        assertThat(environment.getProperty("aml.monitoring.cron")).isEqualTo("-");
    }

    @Test
    @DisplayName("demo profile still exposes the scenario API beans")
    void demoBeansArePresent(@Autowired com.bank.aml.service.demo.DemoScenarioService scenario) {
        assertThat(scenario).isNotNull();
    }
}
