package com.sepro.obfds.controller;

import com.sepro.obfds.dto.FraudReportResponse;
import com.sepro.obfds.dto.OperationalReportResponse;
import com.sepro.obfds.service.ReportService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Basic operational and fraud reports (FR-21). */
@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    @GetMapping("/operational")
    public ResponseEntity<OperationalReportResponse> operational() {
        return ResponseEntity.ok(reportService.operationalReport());
    }

    @GetMapping("/fraud")
    public ResponseEntity<FraudReportResponse> fraud() {
        return ResponseEntity.ok(reportService.fraudReport());
    }
}
