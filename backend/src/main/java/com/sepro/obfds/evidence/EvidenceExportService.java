package com.sepro.obfds.evidence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.sepro.obfds.audit.AuditAction;
import com.sepro.obfds.audit.AuditService;
import com.sepro.obfds.entity.AuditLog;
import com.sepro.obfds.entity.FraudAlert;
import com.sepro.obfds.entity.FraudCase;
import com.sepro.obfds.entity.Transaction;
import com.sepro.obfds.entity.VerificationRequest;
import com.sepro.obfds.exception.ResourceNotFoundException;
import com.sepro.obfds.repository.AuditLogRepository;
import com.sepro.obfds.repository.FraudCaseRepository;
import com.sepro.obfds.repository.VerificationRequestRepository;
import com.sepro.obfds.security.CurrentUserService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Produces the canonical, minimal, tamper-evident evidence file for one fraud case.
 *
 * <p><b>Minimal.</b> The file holds what a reviewer needs to understand the decision: the case, the
 * transfer, which rules fired and how many points each added, a timeline of what happened, the
 * verification attempts and the customer's statement. It deliberately leaves out the customer's
 * name, e-mail address, telephone number and postal address, the transfer's free-text description,
 * the idempotency key, code hashes and every audit "details" text. Account numbers are masked to
 * their last four digits and the customer is identified only by customer number. <b>No free text is
 * exported at all</b>: the analyst's remarks and the status reason (which quotes them) are replaced by
 * a fixed outcome code and, for remarks, only their length. An earlier version masked e-mail
 * addresses and long numbers in those fields; an independent review rightly pointed out that this
 * cannot make prose anonymous (a name or an address survives), so the free text was removed.</p>
 *
 * <p><b>Canonical and tamper-evident.</b> Keys are sorted, money is a plain decimal string and times
 * are UTC ISO-8601, so the same case state always serialises to the same bytes. A SHA-256 over those
 * bytes (excluding the {@code integrity} block, which also carries the export time) is included in
 * the file and recorded in the audit trail. Anyone can recompute it; changing one byte breaks it.
 * The hash proves the file was not altered after export. It does not prove the underlying records
 * were true.</p>
 */
@Service
public class EvidenceExportService {

    static final String NOTICE = "SIMULATION: evidence from a training system for fraud-detection practice. "
            + "It is not a report to any bank, police force or regulator, and nothing has been sent anywhere.";

    private static final java.util.Set<String> EVIDENCE_BOOKKEEPING = java.util.Set.of(
            AuditAction.EVIDENCE_EXPORTED, AuditAction.ESCALATION_RECORDED,
            AuditAction.ESCALATION_HANDOFF_ISSUED, AuditAction.ESCALATION_USER_ATTESTED_SENT);

    private static final Pattern FACTOR =Pattern.compile("^(.*) \\(\\+(\\d+)\\)$");

    /** The result of building an export: the exact bytes to serve and their hash. */
    public record Export(String json, String sha256) {}

    private final FraudCaseRepository fraudCaseRepository;
    private final AuditLogRepository auditLogRepository;
    private final VerificationRequestRepository verificationRequestRepository;
    private final AuditService auditService;
    private final CurrentUserService currentUserService;
    private final Clock clock;
    private final ObjectMapper canonical =
            new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    public EvidenceExportService(
            FraudCaseRepository fraudCaseRepository,
            AuditLogRepository auditLogRepository,
            VerificationRequestRepository verificationRequestRepository,
            AuditService auditService,
            CurrentUserService currentUserService,
            Clock clock) {
        this.fraudCaseRepository = fraudCaseRepository;
        this.auditLogRepository = auditLogRepository;
        this.verificationRequestRepository = verificationRequestRepository;
        this.auditService = auditService;
        this.currentUserService = currentUserService;
        this.clock = clock;
    }

    /** Builds the export for a staff member and records that it was exported, with its hash. */
    @Transactional
    public Export export(String caseReference) {
        Export export = build(caseReference);
        auditService.success(
                currentUserService.requireUsername(), currentUserService.currentAuthorities(),
                AuditAction.EVIDENCE_EXPORTED, "FraudCase", caseReference,
                "Evidence exported; sha256=" + export.sha256());
        return export;
    }

    /** Builds the export without recording anything (used to quote its hash in an escalation draft). */
    @Transactional(readOnly = true)
    public Export build(String caseReference) {
        FraudCase fraudCase = fraudCaseRepository
                .findByReference(caseReference)
                .orElseThrow(() -> new ResourceNotFoundException("Fraud case", caseReference));
        FraudAlert alert = fraudCase.getAlert();
        Transaction transaction = alert.getTransaction();

        Map<String, Object> document = new TreeMap<>();
        document.put("notice", NOTICE);
        document.put("case", caseSection(fraudCase));
        document.put("customer", Map.of("customerNumber", transaction.getSourceAccount().getCustomer().getCustomerNumber()));
        document.put("transaction", transactionSection(transaction));
        document.put("riskFactors", riskFactors(transaction.getRiskReason()));
        verificationSection(transaction).ifPresent(section -> document.put("verification", section));
        if (fraudCase.getCustomerResponse() != null) {
            Map<String, Object> statement = new TreeMap<>();
            statement.put("value", fraudCase.getCustomerResponse());
            put(statement, "recordedAt", fraudCase.getCustomerRespondedAt());
            statement.put("label", "Unverified same-session statement by the account holder; "
                    + "evidence only, not independent identity verification");
            document.put("customerStatement", statement);
        }
        document.put("timeline", timeline(fraudCase, alert, transaction));

        try {
            String body = canonical.writeValueAsString(document);
            String hash = sha256(body);
            Map<String, Object> integrity = new TreeMap<>();
            integrity.put("algorithm", "SHA-256");
            integrity.put("sha256", hash);
            integrity.put("exportedAt", clock.instant().truncatedTo(ChronoUnit.SECONDS).toString());
            integrity.put("scope", "Hash covers every other top-level field, keys sorted, compact JSON.");
            document.put("integrity", integrity);
            return new Export(canonical.writeValueAsString(document), hash);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("The evidence document could not be serialised.", ex);
        }
    }

    private Map<String, Object> caseSection(FraudCase fraudCase) {
        Map<String, Object> section = new TreeMap<>();
        section.put("reference", fraudCase.getReference());
        section.put("status", fraudCase.getStatus().name());
        put(section, "openedAt", fraudCase.getCreatedAt());
        put(section, "closedAt", fraudCase.getClosedAt());
        put(section, "reviewDueAt", fraudCase.getReviewDueAt());
        if (fraudCase.getAssignedTo() != null) {
            section.put("assignedTo", fraudCase.getAssignedTo());
        }
        if (fraudCase.getDecidedBy() != null) {
            section.put("decidedBy", fraudCase.getDecidedBy());
        }
        if (fraudCase.getRemarks() != null) {
            // The analyst's words are free text and can name or describe anyone. They are NOT exported:
            // masking e-mail addresses and long numbers cannot make prose anonymous. The file says that
            // remarks exist and how long they are; a reviewer reads them in the case screen.
            section.put("analystRemarksOmitted", true);
            section.put("analystRemarkCharacters", fraudCase.getRemarks().length());
        }
        return section;
    }

    /**
     * What happened to the transfer, as a fixed code. The stored status reason is sentence text, and for a
     * review decision it embeds the analyst's own remarks ("Released by fraud review: ..."), so the sentence
     * is never exported - only which kind of outcome it describes.
     */
    static String outcomeCode(Transaction transaction) {
        String reason = transaction.getStatusReason() == null ? "" : transaction.getStatusReason();
        if (reason.startsWith("Released by fraud review")) {
            return "RELEASED_BY_ANALYST";
        }
        if (reason.startsWith("Blocked by fraud review")) {
            return "BLOCKED_BY_ANALYST";
        }
        if (reason.startsWith("Cancelled by customer")) {
            return "CANCELLED_BY_CUSTOMER";
        }
        if (reason.startsWith("Verification code expired")) {
            return "VERIFICATION_EXPIRED";
        }
        if (reason.startsWith("Verification failed")) {
            return "VERIFICATION_FAILED";
        }
        if (reason.startsWith("Held for fraud review")) {
            return "HELD_FOR_REVIEW";
        }
        if (reason.startsWith("Additional verification")) {
            return "AWAITING_VERIFICATION";
        }
        if (reason.startsWith("Released after successful additional verification")) {
            return "RELEASED_AFTER_VERIFICATION";
        }
        if (reason.startsWith("Balance was no longer sufficient")) {
            return "INSUFFICIENT_FUNDS_AT_RELEASE";
        }
        if (reason.startsWith("Low risk")) {
            return "AUTO_APPROVED";
        }
        return "OTHER";
    }

    private Map<String, Object> transactionSection(Transaction transaction) {
        Map<String, Object> section = new TreeMap<>();
        section.put("reference", transaction.getReference());
        section.put("amount", transaction.getAmount().toPlainString());
        section.put("currency", transaction.getCurrency());
        section.put("sourceAccount", Redactor.maskAccount(transaction.getSourceAccount().getAccountNumber()));
        section.put("destinationAccount", Redactor.maskAccount(transaction.getDestinationAccountNumber()));
        section.put("status", transaction.getStatus().name());
        section.put("outcomeCode", outcomeCode(transaction));
        section.put("riskLevel", transaction.getRiskLevel().name());
        section.put("riskScore", transaction.getRiskScore());
        put(section, "createdAt", transaction.getCreatedAt());
        put(section, "completedAt", transaction.getCompletedAt());
        return section;
    }

    /** The stored reason is "description (+points); description (+points)"; turn it back into data. */
    private List<Object> riskFactors(String reason) {
        List<Object> factors = new ArrayList<>();
        if (reason == null) {
            return factors;
        }
        for (String part : reason.split("; ")) {
            Matcher matcher = FACTOR.matcher(part);
            Map<String, Object> factor = new TreeMap<>();
            if (matcher.matches()) {
                factor.put("description", matcher.group(1));
                factor.put("points", Integer.parseInt(matcher.group(2)));
                factors.add(factor);
            }
        }
        return factors;
    }

    private java.util.Optional<Map<String, Object>> verificationSection(Transaction transaction) {
        return verificationRequestRepository.findFirstByTransactionIdOrderByIdDesc(transaction.getId()).map(this::verification);
    }

    private Map<String, Object> verification(VerificationRequest request) {
        Map<String, Object> section = new TreeMap<>();
        section.put("status", request.getStatus().name());
        section.put("attempts", request.getAttempts());
        section.put("maxAttempts", request.getMaxAttempts());
        put(section, "issuedAt", request.getCreatedAt());
        put(section, "expiresAt", request.getExpiresAt());
        return section;
    }

    /**
     * What happened, in order. Actions and outcomes only: the audit "details" text is not exported. The
     * export, mail-draft and "I sent it" rows are left out so that the file's hash does not depend on how
     * many times it has been exported.
     */
    private List<Object> timeline(FraudCase fraudCase, FraudAlert alert, Transaction transaction) {
        List<Object> events = new ArrayList<>();
        for (AuditLog entry : auditLogRepository.findByEntityReferenceInOrderByOccurredAtAscIdAsc(
                List.of(fraudCase.getReference(), alert.getReference(), transaction.getReference()))) {
            if (EVIDENCE_BOOKKEEPING.contains(entry.getAction())) {
                // Exporting or drafting is bookkeeping ABOUT the evidence, not something that happened to the
                // case. If it were listed, every export would change the next export's hash, and the hash quoted
                // in a mail draft would never match the file the analyst attaches. The rows stay in the audit log.
                continue;
            }
            Map<String, Object> event = new TreeMap<>();
            event.put("at", entry.getOccurredAt().truncatedTo(ChronoUnit.SECONDS).toString());
            event.put("action", entry.getAction());
            event.put("outcome", entry.getOutcome().name());
            event.put("actor", actorLabel(entry));
            events.add(event);
        }
        return events;
    }

    /** Staff are named (it is their decision to answer for); customers are only "CUSTOMER". */
    private static String actorLabel(AuditLog entry) {
        String roles = entry.getRoles();
        if (roles == null || roles.contains("ROLE_CUSTOMER")) {
            return roles == null ? "SYSTEM" : "CUSTOMER";
        }
        return entry.getUsername();
    }

    private static void put(Map<String, Object> target, String key, java.time.Instant value) {
        if (value != null) {
            target.put(key, value.truncatedTo(ChronoUnit.SECONDS).toString());
        }
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required by the Java platform.", ex);
        }
    }
}
