package com.sepro.obfds.controller;

import com.sepro.obfds.dto.CaseDecisionRequest;
import com.sepro.obfds.dto.EscalationRequest;
import com.sepro.obfds.dto.FraudCaseResponse;
import com.sepro.obfds.evidence.EscalationService;
import com.sepro.obfds.evidence.EvidenceExportService;
import com.sepro.obfds.service.FraudCaseService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Fraud case review, decisions, evidence export and the human-operated handoff (FR-15, FR-18). */
@RestController
@RequestMapping("/api/fraud-cases")
public class FraudCaseController {

    private final FraudCaseService fraudCaseService;
    private final EvidenceExportService evidenceExportService;
    private final EscalationService escalationService;

    public FraudCaseController(
            FraudCaseService fraudCaseService,
            EvidenceExportService evidenceExportService,
            EscalationService escalationService) {
        this.fraudCaseService = fraudCaseService;
        this.evidenceExportService = evidenceExportService;
        this.escalationService = escalationService;
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

    /**
     * The minimal, free-text-free, hashed evidence file for this case, served as a download. It is
     * marked non-cacheable and non-sniffable because it is sensitive and must not be reinterpreted
     * by a browser as anything but JSON.
     */
    @GetMapping("/{reference}/evidence")
    public ResponseEntity<String> evidence(@PathVariable String reference) {
        EvidenceExportService.Export export = evidenceExportService.export(reference);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("evidence-" + reference + ".json").build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.noStore())
                .body(export.json());
    }

    /** The mailboxes a handoff can be addressed to (keys, with the address partly hidden). */
    @GetMapping("/escalation-recipients")
    public ResponseEntity<List<Map<String, String>>> escalationRecipients() {
        return ResponseEntity.ok(escalationService.listRecipients());
    }

    /**
     * Prepares a mail DRAFT to a pre-approved mailbox. Nothing is sent by the system; see
     * {@link EscalationService}.
     */
    @PostMapping("/{reference}/escalation")
    public ResponseEntity<EscalationService.Handoff> escalate(
            @PathVariable String reference, @Valid @RequestBody EscalationRequest request) {
        return ResponseEntity.ok(escalationService.issueHandoff(reference, request.recipientKey()));
    }

    /** The user records that they sent the draft. A self-reported statement, not proof of delivery. */
    @PostMapping("/{reference}/escalation/attest")
    public ResponseEntity<EscalationService.Handoff> attest(@PathVariable String reference) {
        return ResponseEntity.ok(escalationService.attestSent(reference));
    }
}
