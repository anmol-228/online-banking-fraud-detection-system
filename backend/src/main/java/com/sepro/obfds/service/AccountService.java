package com.sepro.obfds.service;

import com.sepro.obfds.dto.AccountResponse;
import com.sepro.obfds.dto.BalanceResponse;
import com.sepro.obfds.entity.Account;
import com.sepro.obfds.entity.Customer;
import com.sepro.obfds.exception.ResourceNotFoundException;
import com.sepro.obfds.repository.AccountRepository;
import com.sepro.obfds.repository.TransactionRepository;
import com.sepro.obfds.security.CurrentUserService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The customer account module (FR-05, FR-06).
 *
 * <p>Every lookup is scoped to the customer resolved from the security context, never to an
 * identifier supplied by the caller. That is what prevents one customer from reading the account
 * of another customer (NFR-08, TC-03).</p>
 */
@Service
public class AccountService {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final CurrentUserService currentUserService;

    public AccountService(
            AccountRepository accountRepository,
            TransactionRepository transactionRepository,
            CurrentUserService currentUserService) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.currentUserService = currentUserService;
    }

    /** All accounts belonging to the caller (FR-05). */
    @Transactional(readOnly = true)
    public List<AccountResponse> listMyAccounts() {
        Customer customer = currentUserService.requireCustomer();
        return accountRepository.findByCustomerIdOrderByIdAsc(customer.getId()).stream()
                .map(this::toResponse)
                .toList();
    }

    /** One account belonging to the caller (FR-05). */
    @Transactional(readOnly = true)
    public AccountResponse getMyAccount(Long accountId) {
        Customer customer = currentUserService.requireCustomer();
        Account account = accountRepository
                .findByIdAndCustomerId(accountId, customer.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Account"));
        return toResponse(account);
    }

    /** Balance enquiry for one account belonging to the caller (FR-06). */
    @Transactional(readOnly = true)
    public BalanceResponse getMyBalance(String accountNumber) {
        Customer customer = currentUserService.requireCustomer();
        Account account = accountRepository
                .findByAccountNumberAndCustomerId(accountNumber, customer.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Account", accountNumber));

        BigDecimal reserved = reservedAmount(account);
        return new BalanceResponse(
                account.getAccountNumber(),
                account.getBalance(),
                account.getBalance().subtract(reserved),
                reserved,
                account.getCurrency(),
                Instant.now());
    }

    /**
     * The amount currently tied up by transfers that have been accepted but have not yet
     * completed.
     *
     * <p>Money is only moved when a transfer is approved. Without this figure a customer could
     * start two large transfers that each pass the balance check on their own but together
     * exceed the balance (NFR-09).</p>
     */
    @Transactional(readOnly = true)
    public BigDecimal reservedAmount(Account account) {
        BigDecimal reserved = transactionRepository.sumReservedAmount(account.getId());
        return reserved == null ? BigDecimal.ZERO : reserved;
    }

    /** Balance minus the amount reserved by pending transfers. */
    @Transactional(readOnly = true)
    public BigDecimal availableBalance(Account account) {
        return account.getBalance().subtract(reservedAmount(account));
    }

    public AccountResponse toResponse(Account account) {
        return new AccountResponse(
                account.getId(),
                account.getAccountNumber(),
                account.getAccountType().name(),
                account.getBalance(),
                availableBalance(account),
                account.getCurrency(),
                account.getStatus().name(),
                account.getOpenedAt());
    }
}
