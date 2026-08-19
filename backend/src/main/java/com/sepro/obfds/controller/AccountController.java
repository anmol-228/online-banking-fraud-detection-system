package com.sepro.obfds.controller;

import com.sepro.obfds.dto.AccountResponse;
import com.sepro.obfds.dto.BalanceResponse;
import com.sepro.obfds.service.AccountService;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Account details and balance enquiry (FR-05, FR-06). */
@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    /** FR-05: view account details. */
    @GetMapping
    public ResponseEntity<List<AccountResponse>> myAccounts() {
        return ResponseEntity.ok(accountService.listMyAccounts());
    }

    /** FR-05: view one account. */
    @GetMapping("/{accountId}")
    public ResponseEntity<AccountResponse> account(@PathVariable Long accountId) {
        return ResponseEntity.ok(accountService.getMyAccount(accountId));
    }

    /** FR-06: check the current balance. */
    @GetMapping("/{accountNumber}/balance")
    public ResponseEntity<BalanceResponse> balance(@PathVariable String accountNumber) {
        return ResponseEntity.ok(accountService.getMyBalance(accountNumber));
    }
}
