package com.sepro.obfds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/**
 * A held HIGH-risk transfer is released or blocked by an analyst, never by the customer.
 *
 * <p>The customer may say "this is mine" or "this is not mine". That statement is evidence for the
 * analyst, recorded once and labelled as an unverified same-session statement. It must never move
 * money by itself - the tests below enumerate every combination to prove there is no path where
 * customer input does. A report of "not mine" outranks "mine" and prevents approval.</p>
 */
class CustomerResponseApiTest extends AbstractCommittedApiTest {

    private MvcResult respond(Fixture customer, String reference, String response) throws Exception {
        return call(post("/api/transactions/" + reference + "/response"), customer.token(), Map.of("response", response));
    }

    private JsonNode caseView(String caseReference) throws Exception {
        return body(call(get("/api/fraud-cases/" + caseReference), analystToken(), null));
    }

    @Test
    @DisplayName("RESP-1 The owner can state 'this is mine'; the analyst sees it; nothing moves")
    void ownerStatementIsEvidenceOnly() throws Exception {
        Fixture customer = newCustomer("100000.00");
        String reference = holdHigh(customer, "500000000301");
        String caseReference = caseReferenceFor(reference);

        MvcResult result = respond(customer, reference, "CONFIRMED_MINE");

        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        JsonNode view = caseView(caseReference);
        assertThat(view.get("customerResponse").asText()).isEqualTo("CONFIRMED_MINE");
        assertThat(view.get("customerRespondedAt").asText()).isNotBlank();
        assertThat(txStatus(reference)).isEqualTo("PENDING");
        assertThat(balanceOf(customer.accountNumber())).isEqualByComparingTo("100000.00");
        assertThat(auditRows("CUSTOMER_RESPONSE_RECORDED", caseReference)).isEqualTo(1);
    }

    @Test
    @DisplayName("RESP-2 The statement is write-once; 'not mine' may outrank 'mine' but never the reverse")
    void responseIsWriteOnceAndNotMineDominates() throws Exception {
        Fixture customer = newCustomer("100000.00");
        String reference = holdHigh(customer, "500000000311");

        assertThat(respond(customer, reference, "CONFIRMED_MINE").getResponse().getStatus()).isEqualTo(200);
        assertThat(respond(customer, reference, "CONFIRMED_MINE").getResponse().getStatus()).as("repeat").isEqualTo(409);
        assertThat(respond(customer, reference, "NOT_MINE").getResponse().getStatus()).as("upgrade to not-mine").isEqualTo(200);
        assertThat(respond(customer, reference, "CONFIRMED_MINE").getResponse().getStatus()).as("cannot go back").isEqualTo(409);
        assertThat(respond(customer, reference, "NOT_MINE").getResponse().getStatus()).as("repeat not-mine").isEqualTo(409);
        assertThat(caseView(caseReferenceFor(reference)).get("customerResponse").asText()).isEqualTo("NOT_MINE");
    }

    @Test
    @DisplayName("RESP-3 After 'not mine' the analyst cannot approve; blocking still works; the money never moves")
    void notMineForbidsApproval() throws Exception {
        Fixture customer = newCustomer("100000.00");
        String reference = holdHigh(customer, "500000000321");
        String caseReference = caseReferenceFor(reference);
        respond(customer, reference, "NOT_MINE");

        MvcResult approve = call(post("/api/fraud-cases/" + caseReference + "/decision"), analystToken(),
                Map.of("decision", "APPROVE", "remarks", "Release it anyway."));
        assertThat(approve.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(approve).get("code").asText()).isEqualTo("CUSTOMER_REPORTED_NOT_MINE");
        assertThat(txStatus(reference)).isEqualTo("PENDING");

        MvcResult block = call(post("/api/fraud-cases/" + caseReference + "/decision"), analystToken(),
                Map.of("decision", "BLOCK", "remarks", "Customer reports it is not theirs."));
        assertThat(block.getResponse().getStatus()).isEqualTo(200);
        assertThat(txStatus(reference)).isEqualTo("BLOCKED");
        assertThat(balanceOf(customer.accountNumber())).isEqualByComparingTo("100000.00");
    }

    @Test
    @DisplayName("RESP-4 Only the owner, only for a held HIGH-risk transfer, and only with a valid value")
    void responseIsLimitedToTheOwnersHighRiskHold() throws Exception {
        Fixture owner = newCustomer("200000.00"); // room for a HIGH and a MEDIUM hold of 60,000 together
        Fixture stranger = newCustomer("100000.00");
        String high = holdHigh(owner, "500000000331");
        String medium = holdMedium(owner, "500000000332");

        assertThat(respond(stranger, high, "CONFIRMED_MINE").getResponse().getStatus()).as("someone else's").isEqualTo(404);
        assertThat(respond(owner, medium, "CONFIRMED_MINE").getResponse().getStatus()).as("no case exists").isEqualTo(422);
        assertThat(respond(owner, high, "RELEASE_NOW").getResponse().getStatus()).as("invalid value").isEqualTo(400);
        MvcResult asAnalyst = call(post("/api/transactions/" + high + "/response"), analystToken(), Map.of("response", "CONFIRMED_MINE"));
        assertThat(asAnalyst.getResponse().getStatus()).as("staff cannot answer for the customer").isEqualTo(403);
    }

    @Test
    @DisplayName("RESP-5 No sequence of customer statements ever settles a held transfer or moves a cent")
    void noCustomerInputPathMovesMoney() throws Exception {
        List<List<String>> sequences = List.of(
                List.of(), List.of("CONFIRMED_MINE"), List.of("NOT_MINE"), List.of("CONFIRMED_MINE", "NOT_MINE"),
                List.of("CONFIRMED_MINE", "CONFIRMED_MINE"), List.of("NOT_MINE", "CONFIRMED_MINE"));
        int n = 0;
        for (List<String> sequence : sequences) {
            Fixture customer = newCustomer("100000.00");
            String reference = holdHigh(customer, "5000000004" + (10 + n++));
            for (String response : sequence) {
                respond(customer, reference, response);
            }
            assertThat(txStatus(reference)).as("status after %s", sequence).isEqualTo("PENDING");
            assertThat(balanceOf(customer.accountNumber())).as("balance after %s", sequence).isEqualByComparingTo("100000.00");
            assertThat(heldOn(customer.accountNumber())).as("hold after %s", sequence).isEqualByComparingTo("60000.00");
        }
    }

    @Test
    @DisplayName("RESP-6 A held HIGH transfer shows a review TARGET to the customer and the analyst, never an auto-release")
    void reviewTargetIsShownButNothingIsAutomatic() throws Exception {
        Fixture customer = newCustomer("100000.00");
        String reference = holdHigh(customer, "500000000341");

        JsonNode customerView = body(call(get("/api/transactions/" + reference), customer.token(), null));
        Instant due = Instant.parse(customerView.get("reviewDueAt").asText());
        assertThat(Duration.between(Instant.now(), due)).isBetween(Duration.ofHours(23), Duration.ofHours(25));

        JsonNode analystView = caseView(caseReferenceFor(reference));
        assertThat(Instant.parse(analystView.get("reviewDueAt").asText())).isEqualTo(due);
        assertThat(txStatus(reference)).isEqualTo("PENDING");
    }
}
