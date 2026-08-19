package com.sepro.obfds.repository;

import com.sepro.obfds.entity.AlertStatus;
import com.sepro.obfds.entity.FraudAlert;
import com.sepro.obfds.entity.RiskLevel;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FraudAlertRepository extends JpaRepository<FraudAlert, Long> {

    List<FraudAlert> findAllByOrderByCreatedAtDesc();

    List<FraudAlert> findByStatusOrderByCreatedAtDesc(AlertStatus status);

    Optional<FraudAlert> findByReference(String reference);

    Optional<FraudAlert> findFirstByTransactionIdOrderByIdDesc(Long transactionId);

    long countByRiskLevel(RiskLevel riskLevel);

    long countByStatus(AlertStatus status);
}
