package com.sepro.obfds.controller;

import com.sepro.obfds.dto.BeneficiaryRequest;
import com.sepro.obfds.dto.BeneficiaryResponse;
import com.sepro.obfds.dto.MessageResponse;
import com.sepro.obfds.service.BeneficiaryService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Beneficiary management (FR-08). */
@RestController
@RequestMapping("/api/beneficiaries")
public class BeneficiaryController {

    private final BeneficiaryService beneficiaryService;

    public BeneficiaryController(BeneficiaryService beneficiaryService) {
        this.beneficiaryService = beneficiaryService;
    }

    @GetMapping
    public ResponseEntity<List<BeneficiaryResponse>> myBeneficiaries() {
        return ResponseEntity.ok(beneficiaryService.listMyBeneficiaries());
    }

    @PostMapping
    public ResponseEntity<BeneficiaryResponse> add(@Valid @RequestBody BeneficiaryRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(beneficiaryService.addBeneficiary(request));
    }

    @GetMapping("/{id}")
    public ResponseEntity<BeneficiaryResponse> get(@PathVariable Long id) {
        return ResponseEntity.ok(beneficiaryService.getMyBeneficiary(id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<MessageResponse> remove(@PathVariable Long id) {
        beneficiaryService.removeBeneficiary(id);
        return ResponseEntity.ok(new MessageResponse("Beneficiary removed from your list."));
    }
}
