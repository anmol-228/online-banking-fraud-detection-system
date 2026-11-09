package com.sepro.obfds.evidence;

import com.sepro.obfds.audit.AuditAction;
import com.sepro.obfds.audit.AuditService;
import com.sepro.obfds.config.AppProperties;
import com.sepro.obfds.exception.ApiException;
import com.sepro.obfds.exception.BusinessRuleException;
import com.sepro.obfds.exception.ResourceNotFoundException;
import com.sepro.obfds.repository.AuditLogRepository;
import com.sepro.obfds.repository.FraudCaseRepository;
import com.sepro.obfds.security.CurrentUserService;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Prepares a HUMAN-operated handoff of a fraud case to a pre-approved mailbox.
 *
 * <h2>What this does, and what it deliberately does not</h2>
 * <p>It builds a {@code mailto:} link - a draft e-mail - addressed to one mailbox from a short list
 * the operator configured in advance. A person opens the draft in their own mail program, attaches
 * the exported evidence file and decides whether to send it. <b>This system sends nothing.</b>
 * There is no mail client, SMTP, HTTP or messaging code in the application, so opening a draft is
 * not delivery and the service never claims it is. The only record of a send is the user's own
 * attestation, labelled as self-reported.</p>
 *
 * <h2>Why it is safe</h2>
 * <ul>
 *   <li>The request carries a <i>key</i> ({@code ops}), never an address. Addresses come only from
 *       configuration, so a caller cannot choose where the draft goes.</li>
 *   <li>The key must match a strict pattern and exist in the allow-list; anything else is refused
 *       before any record is written (header-injection attempts never reach the link).</li>
 *   <li>Subject and body are fixed by the server and contain only the case reference and the
 *       evidence hash - no customer data, no free text.</li>
 *   <li>Configured mailboxes are validated at start-up, and addresses of authorities, regulators
 *       and banks are refused: this is a training system and must never be wired to a real report.</li>
 *   <li>Each staff user can prepare a limited number of drafts per day.</li>
 *   <li>Nothing happens automatically: no hold, expiry, block or SLA creates a handoff.</li>
 * </ul>
 */
@Service
public class EscalationService {

    static final String HANDOFF_STATE = "ESCALATION_HANDOFF_ISSUED";
    static final String ATTESTED_STATE = "ESCALATION_USER_ATTESTED_SENT";

    private static final Pattern KEY = Pattern.compile("^[a-z0-9_-]{1,30}$");
    private static final Pattern ADDRESS = Pattern.compile("^[A-Za-z0-9._%+\\-]{1,64}@[A-Za-z0-9\\-]+(\\.[A-Za-z0-9\\-]+)*\\.[A-Za-z]{2,}$");
    /** Domains of authorities, regulators and banks. A simulation must never be pointed at them. */
    private static final List<String> FORBIDDEN_DOMAIN_PARTS = List.of(
            ".gov", ".mil", ".nic.in", "police", "rbi.org", "npci", "cert-in", "cybercrime", "sebi.gov", "interpol");

    /** What a draft response tells the user. */
    public record Handoff(String state, String recipientKey, String mailto, String evidenceSha256, String notice) {}

    private final Map<String, String> recipients;
    private final int maxPerDay;
    private final EvidenceExportService evidenceExportService;
    private final FraudCaseRepository fraudCaseRepository;
    private final AuditLogRepository auditLogRepository;
    private final AuditService auditService;
    private final CurrentUserService currentUserService;
    private final Clock clock;

    public EscalationService(
            AppProperties properties,
            EvidenceExportService evidenceExportService,
            FraudCaseRepository fraudCaseRepository,
            AuditLogRepository auditLogRepository,
            AuditService auditService,
            CurrentUserService currentUserService,
            Clock clock) {
        this.recipients = validated(properties.getEscalation().getRecipients());
        this.maxPerDay = Math.max(1, properties.getEscalation().getMaxPerDay());
        this.evidenceExportService = evidenceExportService;
        this.fraudCaseRepository = fraudCaseRepository;
        this.auditLogRepository = auditLogRepository;
        this.auditService = auditService;
        this.currentUserService = currentUserService;
        this.clock = clock;
    }

    /** Fails start-up if a configured mailbox is malformed or belongs to an authority or bank. */
    private static Map<String, String> validated(Map<String, String> configured) {
        Map<String, String> accepted = new LinkedHashMap<>();
        configured.forEach((key, address) -> {
            String normalisedKey = key == null ? "" : key.toLowerCase(Locale.ROOT);
            String value = address == null ? "" : address.trim();
            if (!KEY.matcher(normalisedKey).matches() || !ADDRESS.matcher(value).matches()) {
                throw new IllegalStateException("OBFDS startup safety guard refused to start: escalation recipient '"
                        + key + "' is not a plain key with a single plain mailbox address.");
            }
            String lower = value.toLowerCase(Locale.ROOT);
            for (String forbidden : FORBIDDEN_DOMAIN_PARTS) {
                if (lower.contains(forbidden)) {
                    throw new IllegalStateException("OBFDS startup safety guard refused to start: escalation recipient '"
                            + key + "' looks like an authority, regulator or bank mailbox. This simulation must "
                            + "never be configured to contact one; use your own team mailbox.");
                }
            }
            accepted.put(normalisedKey, value);
        });
        return Map.copyOf(accepted);
    }

    /** The keys a staff user may choose from, with the mailbox partly hidden. */
    public List<Map<String, String>> listRecipients() {
        return recipients.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> Map.of("key", entry.getKey(), "mailbox", hide(entry.getValue())))
                .toList();
    }

    @Transactional
    public Handoff issueHandoff(String caseReference, String recipientKey) {
        String key = recipientKey == null ? "" : recipientKey.trim();
        if (!KEY.matcher(key).matches()) {
            throw new BusinessRuleException(
                    HttpStatus.BAD_REQUEST, "INVALID_RECIPIENT_KEY",
                    "Choose one of the configured recipients by its key.");
        }
        String address = recipients.get(key);
        if (address == null) {
            throw new BusinessRuleException("UNKNOWN_RECIPIENT", "That recipient is not configured.");
        }
        fraudCaseRepository.findByReference(caseReference)
                .orElseThrow(() -> new ResourceNotFoundException("Fraud case", caseReference));

        String username = currentUserService.requireUsername();
        List<String> authorities = currentUserService.currentAuthorities();
        long today = auditLogRepository.countByUsernameAndActionAndOccurredAtAfter(
                username, AuditAction.ESCALATION_HANDOFF_ISSUED, clock.instant().minus(Duration.ofDays(1)));
        if (today >= maxPerDay) {
            throw new ApiException(
                    HttpStatus.TOO_MANY_REQUESTS, "ESCALATION_LIMIT",
                    "You have prepared the maximum number of handoffs for today (" + maxPerDay + ").");
        }

        String hash = evidenceExportService.build(caseReference).sha256();
        String subject = "[SIMULATION] OBFDS case " + caseReference;
        String body = "OBFDS simulation - case " + caseReference + "\r\n"
                + "Evidence SHA-256: " + hash + "\r\n"
                + "Attach the exported evidence file yourself. This draft was prepared by a training system; "
                + "nothing has been sent.";
        String mailto = "mailto:" + address + "?subject=" + encode(subject) + "&body=" + encode(body);

        auditService.success(username, authorities, AuditAction.ESCALATION_RECORDED, "FraudCase", caseReference,
                "Handoff requested for recipient key '" + key + "'");
        auditService.success(username, authorities, AuditAction.ESCALATION_HANDOFF_ISSUED, "FraudCase", caseReference,
                "Mail draft prepared for recipient key '" + key + "'; evidence sha256=" + hash);

        return new Handoff(HANDOFF_STATE, key, mailto, hash,
                "This is a draft, not delivery. Opening it sends nothing: this system never sends mail. "
                        + "Send it from your own mail program and confirm with the recipient that it arrived.");
    }

    /** Records that the user says they sent the draft. This is their statement, not proof of delivery. */
    @Transactional
    public Handoff attestSent(String caseReference) {
        fraudCaseRepository.findByReference(caseReference)
                .orElseThrow(() -> new ResourceNotFoundException("Fraud case", caseReference));
        if (!auditLogRepository.existsByActionAndEntityReference(AuditAction.ESCALATION_HANDOFF_ISSUED, caseReference)) {
            throw new BusinessRuleException("NO_HANDOFF", "No handoff has been prepared for this case yet.");
        }
        String username = currentUserService.requireUsername();
        auditService.success(username, currentUserService.currentAuthorities(), AuditAction.ESCALATION_USER_ATTESTED_SENT,
                "FraudCase", caseReference, "User states they sent the prepared draft (self-reported; delivery not verified)");
        return new Handoff(ATTESTED_STATE, null, null, null,
                "Recorded as self-reported: you say you sent it. The system cannot see your mailbox and does not "
                        + "know whether it was delivered.");
    }

    private static String encode(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String hide(String address) {
        int at = address.indexOf('@');
        return at <= 1 ? "***" + address.substring(Math.max(at, 0)) : address.charAt(0) + "***" + address.substring(at);
    }
}
