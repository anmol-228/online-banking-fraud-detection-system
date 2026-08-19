package com.sepro.obfds.repository;

import com.sepro.obfds.entity.Dispute;
import com.sepro.obfds.entity.DisputeStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DisputeRepository extends JpaRepository<Dispute, Long> {

    List<Dispute> findByCustomerIdOrderByCreatedAtDesc(Long customerId);

    List<Dispute> findAllByOrderByCreatedAtDesc();

    Optional<Dispute> findByReference(String reference);

    boolean existsByTransactionIdAndStatusIn(Long transactionId, List<DisputeStatus> statuses);

    long countByStatus(DisputeStatus status);
}
