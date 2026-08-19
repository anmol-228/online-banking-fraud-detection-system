package com.sepro.obfds.controller;

import com.sepro.obfds.dto.PageResponse;
import com.sepro.obfds.dto.TransactionResponse;
import com.sepro.obfds.dto.TransferRequest;
import com.sepro.obfds.dto.VerificationRequestDto;
import com.sepro.obfds.dto.VerificationStatusResponse;
import com.sepro.obfds.service.TransactionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Fund transfer, transaction history and additional verification (FR-07, FR-10, FR-14). */
@RestController
@RequestMapping("/api/transactions")
public class TransactionController {

    private final TransactionService transactionService;

    public TransactionController(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    /**
     * FR-07: initiate a fund transfer.
     *
     * <p>Returns 201 with the created transaction. The status field tells the caller what
     * happened: APPROVED for a low risk transfer, PENDING_VERIFICATION when a code is needed, or
     * PENDING when the transfer was held for fraud review.</p>
     */
    @PostMapping("/transfer")
    public ResponseEntity<TransactionResponse> transfer(@Valid @RequestBody TransferRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(transactionService.transfer(request));
    }

    /** FR-10: view transaction history. */
    @GetMapping
    public ResponseEntity<PageResponse<TransactionResponse>> history(
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(transactionService.myTransactions(page, size));
    }

    /** FR-22: transaction details and current status. */
    @GetMapping("/{reference}")
    public ResponseEntity<TransactionResponse> details(@PathVariable String reference) {
        return ResponseEntity.ok(transactionService.myTransaction(reference));
    }

    /** FR-14: state of the outstanding verification challenge. */
    @GetMapping("/{reference}/verification")
    public ResponseEntity<VerificationStatusResponse> verificationStatus(
            @PathVariable String reference) {
        return ResponseEntity.ok(transactionService.getVerificationStatus(reference));
    }

    /** FR-14, FR-15: submit the verification code to release a held transfer. */
    @PostMapping("/{reference}/verify")
    public ResponseEntity<TransactionResponse> verify(
            @PathVariable String reference, @Valid @RequestBody VerificationRequestDto request) {
        return ResponseEntity.ok(transactionService.submitVerification(reference, request.code()));
    }
}
