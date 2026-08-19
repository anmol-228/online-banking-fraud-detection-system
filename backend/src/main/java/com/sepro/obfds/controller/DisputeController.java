package com.sepro.obfds.controller;

import com.sepro.obfds.dto.DisputeRequest;
import com.sepro.obfds.dto.DisputeResolutionRequest;
import com.sepro.obfds.dto.DisputeResponse;
import com.sepro.obfds.service.DisputeService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Complaint and dispute endpoints (FR-17).
 *
 * <p>The queue and resolve paths are restricted to operations staff in {@code SecurityConfig};
 * the remaining paths belong to the customer.</p>
 */
@RestController
@RequestMapping("/api/disputes")
public class DisputeController {

    private final DisputeService disputeService;

    public DisputeController(DisputeService disputeService) {
        this.disputeService = disputeService;
    }

    /** FR-17: a customer submits a complaint about a transaction. */
    @PostMapping
    public ResponseEntity<DisputeResponse> submit(@Valid @RequestBody DisputeRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(disputeService.submit(request));
    }

    /** FR-17: a customer views their own complaints. */
    @GetMapping
    public ResponseEntity<List<DisputeResponse>> myDisputes() {
        return ResponseEntity.ok(disputeService.myDisputes());
    }

    /** FR-17: the operations queue of all complaints. */
    @GetMapping("/queue")
    public ResponseEntity<List<DisputeResponse>> queue() {
        return ResponseEntity.ok(disputeService.queue());
    }

    /** FR-17: an operations officer records the outcome of a complaint. */
    @PostMapping("/{reference}/resolve")
    public ResponseEntity<DisputeResponse> resolve(
            @PathVariable String reference, @Valid @RequestBody DisputeResolutionRequest request) {
        return ResponseEntity.ok(disputeService.resolve(reference, request));
    }
}
