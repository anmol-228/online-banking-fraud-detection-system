package com.sepro.obfds.controller;

import com.sepro.obfds.dto.FraudAlertResponse;
import com.sepro.obfds.service.FraudAlertService;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Fraud alert dashboard for analysts (FR-13, FR-18). */
@RestController
@RequestMapping("/api/alerts")
public class FraudAlertController {

    private final FraudAlertService fraudAlertService;

    public FraudAlertController(FraudAlertService fraudAlertService) {
        this.fraudAlertService = fraudAlertService;
    }

    /** FR-13: all alerts, optionally filtered by OPEN, UNDER_REVIEW or CLOSED. */
    @GetMapping
    public ResponseEntity<List<FraudAlertResponse>> alerts(
            @RequestParam(required = false) String status) {
        return ResponseEntity.ok(fraudAlertService.listAlerts(status));
    }

    /** FR-18: details of one suspicious transaction. */
    @GetMapping("/{reference}")
    public ResponseEntity<FraudAlertResponse> alert(@PathVariable String reference) {
        return ResponseEntity.ok(fraudAlertService.getAlert(reference));
    }
}
