package com.sepro.obfds.repository;

import com.sepro.obfds.entity.Beneficiary;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BeneficiaryRepository extends JpaRepository<Beneficiary, Long> {

    List<Beneficiary> findByCustomerIdAndActiveTrueOrderByCreatedAtDesc(Long customerId);

    Optional<Beneficiary> findByIdAndCustomerId(Long id, Long customerId);

    Optional<Beneficiary> findByCustomerIdAndAccountNumber(Long customerId, String accountNumber);

    boolean existsByCustomerIdAndAccountNumber(Long customerId, String accountNumber);
}
