package com.bank.aml.demo;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;

/**
 * Outside the demo profile nothing changes: the cron stays on the 02:00 schedule and the
 * demo-only beans simply do not exist — the scenario can never truncate or advance anything
 * in a normal run.
 */
@SpringBootTest
class ProductionSchedulingConfigTest {

    @Autowired private Environment environment;
    @Autowired private ApplicationContext context;

    @Test
    @DisplayName("default profile keeps the nightly 02:00 monitoring cron")
    void productionCronIsUntouched() {
        String cron = environment.getProperty("aml.monitoring.cron");
        // The property is only defined by application-demo.yml; everywhere else the
        // @Scheduled default (0 0 2 * * *) applies.
        assertThat(cron).isNull();
    }

    @Test
    @DisplayName("demo-only beans are not registered outside the demo profile")
    void demoBeansAreAbsent() {
        org.junit.jupiter.api.Assertions.assertThrows(NoSuchBeanDefinitionException.class,
                () -> context.getBean(com.bank.aml.service.demo.DemoScenarioService.class));
        org.junit.jupiter.api.Assertions.assertThrows(NoSuchBeanDefinitionException.class,
                () -> context.getBean(com.bank.aml.service.DemoResetService.class));
        org.junit.jupiter.api.Assertions.assertThrows(NoSuchBeanDefinitionException.class,
                () -> context.getBean(com.bank.aml.web.DemoScenarioController.class));
    }
}
