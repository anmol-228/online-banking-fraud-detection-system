package com.sepro.obfds.service;

import com.sepro.obfds.audit.AuditAction;
import com.sepro.obfds.audit.AuditService;
import com.sepro.obfds.dto.BeneficiaryRequest;
import com.sepro.obfds.dto.BeneficiaryResponse;
import com.sepro.obfds.entity.Beneficiary;
import com.sepro.obfds.entity.Customer;
import com.sepro.obfds.exception.BusinessRuleException;
import com.sepro.obfds.exception.ResourceNotFoundException;
import com.sepro.obfds.repository.AccountRepository;
import com.sepro.obfds.repository.BeneficiaryRepository;
import com.sepro.obfds.security.CurrentUserService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Beneficiary management (FR-08).
 *
 * <p>The moment a payee is added is recorded because a transfer to a payee added minutes ago is
 * one of the signals the fraud module uses (FR-11).</p>
 */
@Service
public class BeneficiaryService {

    private final BeneficiaryRepository beneficiaryRepository;
    private final AccountRepository accountRepository;
    private final CurrentUserService currentUserService;
    private final AuditService auditService;

    public BeneficiaryService(
            BeneficiaryRepository beneficiaryRepository,
            AccountRepository accountRepository,
            CurrentUserService currentUserService,
            AuditService auditService) {
        this.beneficiaryRepository = beneficiaryRepository;
        this.accountRepository = accountRepository;
        this.currentUserService = currentUserService;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<BeneficiaryResponse> listMyBeneficiaries() {
        Customer customer = currentUserService.requireCustomer();
        return beneficiaryRepository
                .findByCustomerIdAndActiveTrueOrderByCreatedAtDesc(customer.getId())
                .stream()
                .map(BeneficiaryService::toResponse)
                .toList();
    }

    @Transactional
    public BeneficiaryResponse addBeneficiary(BeneficiaryRequest request) {
        Customer customer = currentUserService.requireCustomer();
        String accountNumber = request.accountNumber().trim();

        // A customer sending money to their own account is a data entry mistake, not a transfer.
        boolean ownAccount = accountRepository
                .findByAccountNumberAndCustomerId(accountNumber, customer.getId())
                .isPresent();
        if (ownAccount) {
            throw new BusinessRuleException(
                    "OWN_ACCOUNT_AS_BENEFICIARY",
                    "You cannot add one of your own accounts as a beneficiary.");
        }

        if (beneficiaryRepository.existsByCustomerIdAndAccountNumber(customer.getId(), accountNumber)) {
            throw new BusinessRuleException(
                    HttpStatus.CONFLICT,
                    "BENEFICIARY_EXISTS",
                    "This account number is already saved in your beneficiary list.");
        }

        Beneficiary beneficiary = new Beneficiary();
        beneficiary.setCustomer(customer);
        beneficiary.setName(request.name().trim());
        beneficiary.setAccountNumber(accountNumber);
        beneficiary.setBankName(request.bankName().trim());
        beneficiary.setIfscCode(blankToNull(request.ifscCode()));
        beneficiary.setNickname(blankToNull(request.nickname()));
        beneficiary.setActive(true);
        Beneficiary saved = beneficiaryRepository.save(beneficiary);

        auditService.success(
                currentUserService.requireUsername(),
                currentUserService.currentAuthorities(),
                AuditAction.BENEFICIARY_ADDED,
                "Beneficiary",
                saved.getAccountNumber(),
                "Beneficiary " + saved.getName() + " added by customer " + customer.getCustomerNumber());

        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public BeneficiaryResponse getMyBeneficiary(Long id) {
        Customer customer = currentUserService.requireCustomer();
        return beneficiaryRepository
                .findByIdAndCustomerId(id, customer.getId())
                .map(BeneficiaryService::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("Beneficiary"));
    }

    /**
     * Removes a payee from the list.
     *
     * <p>The row is deactivated rather than deleted so that past transactions keep pointing at a
     * payee that still exists, which preserves referential integrity (NFR-09).</p>
     */
    @Transactional
    public void removeBeneficiary(Long id) {
        Customer customer = currentUserService.requireCustomer();
        Beneficiary beneficiary = beneficiaryRepository
                .findByIdAndCustomerId(id, customer.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Beneficiary"));

        beneficiary.setActive(false);
        beneficiaryRepository.save(beneficiary);

        auditService.success(
                currentUserService.requireUsername(),
                currentUserService.currentAuthorities(),
                AuditAction.BENEFICIARY_REMOVED,
                "Beneficiary",
                beneficiary.getAccountNumber(),
                "Beneficiary " + beneficiary.getName() + " deactivated");
    }

    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    public static BeneficiaryResponse toResponse(Beneficiary beneficiary) {
        return new BeneficiaryResponse(
                beneficiary.getId(),
                beneficiary.getName(),
                beneficiary.getAccountNumber(),
                beneficiary.getBankName(),
                beneficiary.getIfscCode(),
                beneficiary.getNickname(),
                beneficiary.isActive(),
                beneficiary.getCreatedAt());
    }
}
