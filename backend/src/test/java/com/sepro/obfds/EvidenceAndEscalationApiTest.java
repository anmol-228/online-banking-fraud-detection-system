package com.sepro.obfds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Evidence export and the human-operated escalation handoff (REQ-02).
 *
 * <p>The system never sends anything to anyone. It produces a minimal, free-text-free, hashed evidence
 * file for an authorised analyst and, on request, a prepared e-mail DRAFT addressed only to a
 * mailbox the operator configured in advance. Opening that draft is not delivery, and nothing here
 * pretends it is.</p>
 */
@TestPropertySource(properties = {
    "obfds.escalation.recipients.ops=ops.team@example.invalid",
    "obfds.escalation.recipients.security=security.team@example.invalid",
    "obfds.escalation.max-per-day=3"
})
class EvidenceAndEscalationApiTest extends AbstractCommittedApiTest {

    private MvcResult evidence(String caseReference, String token) throws Exception {
        return call(get("/api/fraud-cases/" + caseReference + "/evidence"), token, null);
    }

    private String caseWithSensitiveText(Fixture customer, String payee) throws Exception {
        addBeneficiary(customer, payee, 0);
        MvcResult held = call(post("/api/transactions/transfer"), customer.token(), Map.of(
                "sourceAccountNumber", customer.accountNumber(), "destinationAccountNumber", payee,
                "amount", "60000.00",
                "description", "=HYPERLINK(\"http://evil.example\",\"click\") mail me at secret.person@example.invalid",
                "idempotencyKey", "evidence-key-" + System.nanoTime()));
        assertThat(held.getResponse().getStatus()).isEqualTo(201);
        return body(held).get("reference").asText();
    }

    @Test
    @DisplayName("EVI-1 An analyst exports a case as an attachment: JSON, simulated notice, no sniffing, no caching")
    void analystExportsACase() throws Exception {
        Fixture customer = newCustomer("100000.00");
        String reference = holdHigh(customer, "500000000501");
        String caseReference = caseReferenceFor(reference);

        MvcResult result = evidence(caseReference, analystToken());

        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(result.getResponse().getContentType()).startsWith("application/json");
        assertThat(result.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION)).contains("attachment").contains(caseReference);
        assertThat(result.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
        JsonNode doc = body(result);
        assertThat(doc.get("notice").asText()).contains("SIMULATION");
        assertThat(doc.get("case").get("reference").asText()).isEqualTo(caseReference);
        assertThat(doc.get("transaction").get("reference").asText()).isEqualTo(reference);
        assertThat(doc.get("transaction").get("amount").asText()).isEqualTo("60000.00");
        assertThat(doc.get("riskFactors").size()).isGreaterThanOrEqualTo(2);
        assertThat(doc.get("timeline").size()).isGreaterThanOrEqualTo(3);
    }

    @Test
    @DisplayName("EVI-2 The export is minimal: no e-mail, phone, address, name, full account number, key or free text")
    void exportLeaksNothingPersonal() throws Exception {
        Fixture customer = newCustomer("100000.00");
        String reference = caseWithSensitiveText(customer, "500000000511");
        String caseReference = caseReferenceFor(reference);
        // The analyst's remarks are free text and can name anyone. They embed a name, an address, a phone
        // number and an e-mail address, and the transfer's own status reason quotes them. None of it may
        // leave the system (an earlier version only masked e-mails and long numbers, which let the name and
        // the address through - a regression here must fail this test).
        String analyst = analystToken();
        call(post("/api/fraud-cases/" + caseReference + "/assign"), analyst, null);
        MvcResult decided = call(post("/api/fraud-cases/" + caseReference + "/decision"), analyst, Map.of(
                "decision", "BLOCK",
                "remarks", "Zelda Quill (zelda.quill@mail.example, 9876543210) of Rose Villa, Pune called to disown it."));
        assertThat(decided.getResponse().getStatus()).isEqualTo(200);
        assertThat(txStatusReason(reference)).as("the stored reason really does embed the remarks")
                .contains("Zelda Quill");

        String json = evidence(caseReference, analyst).getResponse().getContentAsString();

        assertThat(json)
                .doesNotContain("Zelda").doesNotContain("Quill").doesNotContain("Rose Villa")
                .doesNotContain("Pune").doesNotContain("9876543210").doesNotContain("mail.example")
                .doesNotContain("Committed Test")          // the customer's full name
                .doesNotContain("statusReason")
                .contains("\"outcomeCode\":\"BLOCKED_BY_ANALYST\"")
                .contains("\"analystRemarksOmitted\":true");
        assertThat(json)
                .doesNotContain(customer.accountNumber())
                .doesNotContain("500000000511")
                .doesNotContain(customer.username())
                .doesNotContain("@")
                .doesNotContain("HYPERLINK")
                .doesNotContain("evil.example")
                .doesNotContain("secret.person")
                .doesNotContain("evidence-key-")
                .doesNotContainIgnoringCase("passwordHash")
                .doesNotContainIgnoringCase("codeHash");
        assertThat(json).contains("****" + customer.accountNumber().substring(customer.accountNumber().length() - 4));
    }

    @Test
    @DisplayName("EVI-3 The integrity hash matches the document, and changing one byte breaks the match")
    void integrityHashIsVerifiable() throws Exception {
        Fixture customer = newCustomer("100000.00");
        String caseReference = caseReferenceFor(holdHigh(customer, "500000000521"));

        JsonNode doc = body(evidence(caseReference, analystToken()));
        String claimed = doc.get("integrity").get("sha256").asText();
        assertThat(doc.get("integrity").get("algorithm").asText()).isEqualTo("SHA-256");

        ObjectMapper canonical = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        com.fasterxml.jackson.databind.node.ObjectNode withoutIntegrity = ((com.fasterxml.jackson.databind.node.ObjectNode) doc).deepCopy();
        withoutIntegrity.remove("integrity");
        String recomputed = sha256(canonical.writeValueAsString(canonical.treeToValue(withoutIntegrity, Object.class)));
        assertThat(recomputed).isEqualTo(claimed);

        withoutIntegrity.put("notice", withoutIntegrity.get("notice").asText() + " ");
        assertThat(sha256(canonical.writeValueAsString(canonical.treeToValue(withoutIntegrity, Object.class)))).isNotEqualTo(claimed);
        assertThat(jdbc.queryForObject(
                "select count(*) from audit_log where action = 'EVIDENCE_EXPORTED' and entity_reference = ? and details like ?",
                Integer.class, caseReference, "%" + claimed + "%")).isEqualTo(1);
    }

    @Test
    @DisplayName("EVI-4 Customers cannot export; unknown cases are 404")
    void exportIsStaffOnly() throws Exception {
        Fixture customer = newCustomer("100000.00");
        String caseReference = caseReferenceFor(holdHigh(customer, "500000000531"));

        assertThat(evidence(caseReference, customer.token()).getResponse().getStatus()).isEqualTo(403);
        assertThat(evidence("CSE-00000000-NOPE00", analystToken()).getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("EVI-5 Exporting or drafting does not change the evidence: the draft quotes the hash of the file the analyst exported")
    void exportAndHandoffDoNotChangeTheHash() throws Exception {
        Fixture customer = newCustomer("100000.00");
        String caseReference = caseReferenceFor(holdHigh(customer, "500000000561"));
        String analyst = analystToken();

        String first = body(evidence(caseReference, analyst)).get("integrity").get("sha256").asText();
        String second = body(evidence(caseReference, analyst)).get("integrity").get("sha256").asText();
        assertThat(second).as("the export's own audit row must not enter the next export").isEqualTo(first);

        JsonNode draft = body(call(post("/api/fraud-cases/" + caseReference + "/escalation"), analyst,
                Map.of("recipientKey", "ops")));
        assertThat(draft.get("evidenceSha256").asText())
                .as("the hash quoted in the draft must verify against the file the analyst attaches").isEqualTo(first);
        String third = body(evidence(caseReference, analyst)).get("integrity").get("sha256").asText();
        assertThat(third).as("the draft's own audit rows must not enter the next export").isEqualTo(first);
    }

    @Test
    @DisplayName("ESC-1 The handoff is a mailto: draft to an allow-listed mailbox only; subject and body are fixed")
    void handoffUsesOnlyTheAllowListedRecipient() throws Exception {
        Fixture customer = newCustomer("100000.00");
        String caseReference = caseReferenceFor(holdHigh(customer, "500000000541"));

        MvcResult result = call(post("/api/fraud-cases/" + caseReference + "/escalation"), analystToken(),
                Map.of("recipientKey", "ops"));

        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        JsonNode view = body(result);
        assertThat(view.get("state").asText()).isEqualTo("ESCALATION_HANDOFF_ISSUED");
        String mailto = view.get("mailto").asText();
        assertThat(mailto).startsWith("mailto:ops.team@example.invalid?subject=");
        assertThat(java.net.URLDecoder.decode(mailto, StandardCharsets.UTF_8))
                .contains("[SIMULATION] OBFDS case " + caseReference)
                .doesNotContain("cc=").doesNotContain("bcc=")
                .doesNotContain(customer.accountNumber()).doesNotContain(customer.username());
        assertThat(view.get("notice").asText()).containsIgnoringCase("not delivery");
        assertThat(auditRows("ESCALATION_RECORDED", caseReference)).isEqualTo(1);
        assertThat(auditRows("ESCALATION_HANDOFF_ISSUED", caseReference)).isEqualTo(1);
    }

    @Test
    @DisplayName("ESC-2 Addresses, unknown keys and header-injection attempts are refused and recorded nowhere")
    void injectionAndUnlistedRecipientsAreRefused() throws Exception {
        Fixture customer = newCustomer("100000.00");
        String caseReference = caseReferenceFor(holdHigh(customer, "500000000551"));

        for (String key : new String[] {
            "someone@example.invalid", "unknown", "ops\r\nBcc: attacker@example.invalid", "ops,attacker", "ops;x", "", "  "}) {
            MvcResult result = call(post("/api/fraud-cases/" + caseReference + "/escalation"), analystToken(),
                    Map.of("recipientKey", key));
            assertThat(result.getResponse().getStatus()).as("key [%s]", key.replace("\r", "\\r").replace("\n", "\\n"))
                    .isIn(400, 422);
        }
        assertThat(auditRows("ESCALATION_HANDOFF_ISSUED", caseReference)).isZero();
    }

    @Test
    @DisplayName("ESC-3 At most N handoffs per staff user per day; the next one is 429")
    void handoffsAreRateLimited() throws Exception {
        Fixture customer = newCustomer("100000.00");
        String caseReference = caseReferenceFor(holdHigh(customer, "500000000561"));
        String analyst = newAnalystToken(); // the limit is per staff user, so this test owns its counter
        for (int i = 0; i < 3; i++) {
            assertThat(call(post("/api/fraud-cases/" + caseReference + "/escalation"), analyst, Map.of("recipientKey", "ops"))
                    .getResponse().getStatus()).as("handoff %d of 3", i + 1).isEqualTo(200);
        }
        assertThat(call(post("/api/fraud-cases/" + caseReference + "/escalation"), analyst, Map.of("recipientKey", "ops"))
                .getResponse().getStatus()).isEqualTo(429);
    }

    @Test
    @DisplayName("ESC-4 Customers cannot create a handoff; the sender is attested, never assumed")
    void staffOnlyAndSelfAttestedOnly() throws Exception {
        Fixture customer = newCustomer("100000.00");
        String caseReference = caseReferenceFor(holdHigh(customer, "500000000571"));

        assertThat(call(post("/api/fraud-cases/" + caseReference + "/escalation"), customer.token(), Map.of("recipientKey", "ops"))
                .getResponse().getStatus()).isEqualTo(403);

        String analyst = analystToken();
        assertThat(call(post("/api/fraud-cases/" + caseReference + "/escalation/attest"), analyst, null)
                .getResponse().getStatus()).as("attesting before any handoff exists").isEqualTo(422);
        call(post("/api/fraud-cases/" + caseReference + "/escalation"), analyst, Map.of("recipientKey", "security"));
        MvcResult attested = call(post("/api/fraud-cases/" + caseReference + "/escalation/attest"), analyst, null);
        assertThat(attested.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(attested).get("state").asText()).isEqualTo("ESCALATION_USER_ATTESTED_SENT");
        assertThat(body(attested).get("notice").asText()).containsIgnoringCase("self-reported");
    }

    @Test
    @DisplayName("ESC-5 Nothing is ever sent automatically: holds, expiries and blocks create no escalation record")
    void nothingIsEscalatedAutomatically() throws Exception {
        Fixture customer = newCustomer("100000.00");
        String reference = holdHigh(customer, "500000000581");
        String caseReference = caseReferenceFor(reference);
        call(post("/api/fraud-cases/" + caseReference + "/decision"), analystToken(),
                Map.of("decision", "BLOCK", "remarks", "Blocked after review."));

        assertThat(auditRows("ESCALATION_RECORDED", caseReference)).isZero();
        assertThat(auditRows("ESCALATION_HANDOFF_ISSUED", caseReference)).isZero();
    }

    private static String sha256(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }
}
