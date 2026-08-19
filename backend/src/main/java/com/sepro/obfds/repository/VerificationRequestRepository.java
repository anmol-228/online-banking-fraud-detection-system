package com.sepro.obfds.repository;

import com.sepro.obfds.entity.VerificationRequest;
import com.sepro.obfds.entity.VerificationStatus;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VerificationRequestRepository extends JpaRepository<VerificationRequest, Long> {

    Optional<VerificationRequest> findFirstByTransactionIdAndStatusOrderByIdDesc(
            Long transactionId, VerificationStatus status);

    Optional<VerificationRequest> findFirstByTransactionIdOrderByIdDesc(Long transactionId);

    long countByTransactionSourceAccountCustomerIdAndStatusAndCreatedAtAfter(
            Long customerId, VerificationStatus status, Instant after);
}
