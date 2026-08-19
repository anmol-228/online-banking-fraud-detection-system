package com.sepro.obfds.repository;

import com.sepro.obfds.entity.CaseStatus;
import com.sepro.obfds.entity.FraudCase;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FraudCaseRepository extends JpaRepository<FraudCase, Long> {

    List<FraudCase> findAllByOrderByCreatedAtDesc();

    Optional<FraudCase> findByReference(String reference);

    Optional<FraudCase> findByAlertId(Long alertId);

    long countByStatus(CaseStatus status);
}
