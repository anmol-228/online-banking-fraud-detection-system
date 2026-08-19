package com.sepro.obfds.controller;

import com.sepro.obfds.dto.CaseDecisionRequest;
import com.sepro.obfds.dto.FraudCaseResponse;
import com.sepro.obfds.service.FraudCaseService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Fraud case review and decisions (FR-15, FR-18). */
@RestController
@RequestMapping("/api/fraud-cases")
public class FraudCaseController {

    private final FraudCaseService fraudCaseService;

    public FraudCaseController(FraudCaseService fraudCaseService) {
        this.fraudCaseService = fraudCaseService;
    }

    @GetMapping
    public ResponseEntity<List<FraudCaseResponse>> cases() {
        return ResponseEntity.ok(fraudCaseService.listCases());
    }

    @GetMapping("/{reference}")
    public ResponseEntity<FraudCaseResponse> details(@PathVariable String reference) {
        return ResponseEntity.ok(fraudCaseService.getCase(reference));
    }

    /** FR-18: the analyst takes ownership of a case before working on it. */
    @PostMapping("/{reference}/assign")
    public ResponseEntity<FraudCaseResponse> assign(@PathVariable String reference) {
        return ResponseEntity.ok(fraudCaseService.assignToMe(reference));
    }

    /** FR-15: approve or block the held transaction behind this case. */
    @PostMapping("/{reference}/decision")
    public ResponseEntity<FraudCaseResponse> decide(
            @PathVariable String reference, @Valid @RequestBody CaseDecisionRequest request) {
        return ResponseEntity.ok(fraudCaseService.decide(reference, request));
    }
}
