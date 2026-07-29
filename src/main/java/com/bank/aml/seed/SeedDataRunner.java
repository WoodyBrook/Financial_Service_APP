package com.bank.aml.seed;

import com.bank.aml.repo.CustomerRepository;
import com.bank.aml.service.SanctionsScreeningService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application-start entry point. All actual data construction lives in
 * {@link DemoDataSeedService}; this runner only decides whether seeding is needed at boot.
 */
@Component
@Order(100)
@RequiredArgsConstructor
@Slf4j
public class SeedDataRunner implements ApplicationRunner {
    /** Anchored to the live calendar so the data set never drifts away from demo day. */
    public static final LocalDate DEMO_DAY = LocalDate.now(ZoneOffset.UTC);
    public static final Instant DEMO_NOW = DEMO_DAY.atTime(10, 0).toInstant(ZoneOffset.UTC);

    private final CustomerRepository customerRepository;
    private final SanctionsScreeningService sanctionsScreeningService;
    private final DemoDataSeedService demoDataSeedService;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        sanctionsScreeningService.ensureV1Loaded();
        if (customerRepository.count() > 0) {
            log.info("Seed skipped — customers already present");
            return;
        }
        seedAll();
    }

    /** Builds the whole demo data set. Safe to call again after {@code DemoResetService} truncates. */
    @Transactional
    public Map<String, Long> seedAll() {
        return demoDataSeedService.resetToPresentationReady();
    }
}
