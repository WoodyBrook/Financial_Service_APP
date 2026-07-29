package com.bank.aml.repo;

import com.bank.aml.domain.DemoScenarioStateEntity;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface DemoScenarioStateRepository extends JpaRepository<DemoScenarioStateEntity, String> {

    /** Every state transition reads the row through this lock inside its outer transaction. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<DemoScenarioStateEntity> findWithLockByScenarioKey(String scenarioKey);
}
