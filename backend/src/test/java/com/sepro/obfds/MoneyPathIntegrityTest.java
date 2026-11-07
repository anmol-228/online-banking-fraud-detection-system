package com.sepro.obfds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.sepro.obfds.fraud.FraudRiskService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The invariants of the money path, each checked against the database directly:
 *
 * <ul>
 *   <li>funds already promised to held transfers are never promised again, however the requests
 *       interleave (no negative available balance);</li>
 *   <li>money is conserved - what leaves one account arrives in another, exactly once;</li>
 *   <li>a fraud case is never reported as approved over a transfer that did not happen.</li>
 * </ul>
 *
 * <p>Interleaving is forced, not hoped for: a {@link CyclicBarrier} inside the risk evaluation
 * holds each request there until all have arrived, which on unserialised code guarantees they have
 * all already read the same "available balance". It is bounded, so correct code (which lets only
 * one request in at a time) is delayed by seconds rather than deadlocked.</p>
 */
class MoneyPathIntegrityTest extends AbstractCommittedApiTest {

    @MockitoSpyBean private FraudRiskService fraudRisk;

    private static final String EXTERNAL_PAYEE = "500000000009";

    private void forceOverlapInsideTheRequest(int parties) {
        CyclicBarrier inside = new CyclicBarrier(parties);
        doAnswer(invocation -> {
            try {
                inside.await(2, TimeUnit.SECONDS);
            } catch (TimeoutException | BrokenBarrierException ignored) {
                // The others are queued behind a lock, which is exactly what serialisation means.
            }
            return invocation.callRealMethod();
        }).when(fraudRisk).evaluate(any(), any(), any(), any());
    }

    private void assertNeverOverCommitted(Fixture customer) {
        BigDecimal available = balanceOf(customer.accountNumber()).subtract(heldOn(customer.accountNumber()));
        assertThat(available)
                .as("available balance (balance %s minus held %s)",
                        balanceOf(customer.accountNumber()), heldOn(customer.accountNumber()))
                .isGreaterThanOrEqualTo(BigDecimal.ZERO);
    }

    private long countStatus(List<MvcResult> results, int status) {
        return results.stream().filter(r -> r.getResponse().getStatus() == status).count();
    }

    @Test
    @DisplayName("MONEY-1 Two simultaneous MEDIUM holds that together exceed the balance: only one is accepted")
    void concurrentMediumHoldsCannotExceedTheBalance() throws Exception {
        Fixture customer = newCustomer("100000.00");
        forceOverlapInsideTheRequest(2);

        List<Callable<MvcResult>> requests = List.of(
                () -> transfer(customer.token(), customer.accountNumber(), "500000000011", "60000.00", null),
                () -> transfer(customer.token(), customer.accountNumber(), "500000000012", "60000.00", null));
        List<MvcResult> results = runTogether(requests);

        assertThat(countStatus(results, 201)).as("accepted holds").isEqualTo(1);
        assertThat(countStatus(results, 422)).as("rejected for insufficient available balance").isEqualTo(1);
        assertThat(heldOn(customer.accountNumber())).isEqualByComparingTo("60000.00");
        assertNeverOverCommitted(customer);
    }

    @Test
    @DisplayName("MONEY-2 A MEDIUM and a HIGH hold that together exceed the balance: only one is accepted")
    void concurrentMediumAndHighHoldsCannotExceedTheBalance() throws Exception {
        Fixture customer = newCustomer("100000.00");
        String freshPayee = "500000000021";
        addBeneficiary(customer, freshPayee, 0); // saved just now => "new beneficiary" rule => HIGH
        forceOverlapInsideTheRequest(2);

        List<Callable<MvcResult>> requests = List.of(
                () -> transfer(customer.token(), customer.accountNumber(), "500000000022", "60000.00", null),
                () -> transfer(customer.token(), customer.accountNumber(), freshPayee, "60000.00", null));
        List<MvcResult> results = runTogether(requests);

        assertThat(countStatus(results, 201)).as("accepted holds").isEqualTo(1);
        assertThat(countStatus(results, 422)).as("rejected for insufficient available balance").isEqualTo(1);
        assertNeverOverCommitted(customer);
    }

    @Test
    @DisplayName("MONEY-3 Many simultaneous small transfers from one account all settle exactly once and conserve money")
    void parallelTransfersAreSerialisedAndConserveMoney() throws Exception {
        Fixture source = newCustomer("100000.00");
        Fixture sink = newCustomer("0.00");
        // An established payee scores 0, so even the velocity rule (+20) keeps every transfer LOW
        // and therefore settled immediately, whatever order the requests arrive in.
        addBeneficiary(source, sink.accountNumber(), 48);
        BigDecimal total = balanceOf(source.accountNumber()).add(balanceOf(sink.accountNumber()));

        int parallel = 8;
        List<Callable<MvcResult>> requests = new ArrayList<>();
        for (int i = 0; i < parallel; i++) {
            // Each carries its own key: these are eight intentional payments. (Identical keyless
            // requests inside 60 seconds are, by design, rejected as accidental duplicates.)
            String key = "parallel-" + i + "-" + java.util.UUID.randomUUID();
            requests.add(() -> transfer(source.token(), source.accountNumber(), sink.accountNumber(), "1000.00", key));
        }
        List<MvcResult> results = runTogether(requests);

        assertThat(results).extracting(r -> r.getResponse().getStatus())
                .as("every request is answered with 201 (settled or held); none is lost to a lock conflict")
                .containsOnly(201);
        assertThat(balanceOf(source.accountNumber()).add(balanceOf(sink.accountNumber()))).isEqualByComparingTo(total);
        assertThat(balanceOf(source.accountNumber())).isEqualByComparingTo("92000.00");
        assertThat(balanceOf(sink.accountNumber())).isEqualByComparingTo("8000.00");
        assertThat(heldOn(source.accountNumber())).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("MONEY-4 A case approved over an unaffordable transfer is NOT reported as approved")
    void approvedCaseCannotSitOverAFailedTransfer() throws Exception {
        Fixture customer = newCustomer("100000.00");
        String freshPayee = "500000000031";
        addBeneficiary(customer, freshPayee, 0);

        MvcResult held = transfer(customer.token(), customer.accountNumber(), freshPayee, "60000.00", null);
        assertThat(held.getResponse().getStatus()).isEqualTo(201);
        assertThat(body(held).get("status").asText()).isEqualTo("PENDING");
        String transactionReference = body(held).get("reference").asText();

        // Simulate the aftermath of an over-reservation: the money is no longer there when the
        // analyst gets to the case.
        jdbc.update("update account set balance = 10000.00 where account_number = ?", customer.accountNumber());

        String analyst = login("analyst.fraud", "Analyst@123");
        String caseReference = caseFor(analyst, transactionReference);
        MvcResult decision = mockMvc
                .perform(post("/api/fraud-cases/" + caseReference + "/decision")
                        .header(HttpHeaders.AUTHORIZATION, analyst)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("decision", "APPROVE", "remarks", "Customer confirmed the payee."))))
                .andReturn();

        String transactionStatus = jdbc.queryForObject(
                "select status from bank_transaction where reference = ?", String.class, transactionReference);
        String caseStatus = jdbc.queryForObject(
                "select status from fraud_case where reference = ?", String.class, caseReference);

        assertThat(transactionStatus).as("the transfer could not be funded").isEqualTo("FAILED");
        assertThat(caseStatus)
                .as("a case may be RESOLVED_APPROVED only if its transfer was APPROVED (decision response: %s)",
                        decision.getResponse().getContentAsString())
                .isNotEqualTo("RESOLVED_APPROVED")
                .isEqualTo("RESOLVED_BLOCKED");
        assertThat(balanceOf(customer.accountNumber())).isEqualByComparingTo("10000.00");
    }

    @Test
    @DisplayName("MONEY-5 Regression guard: an affordable approval still releases the funds and approves the case")
    void affordableApprovalStillWorks() throws Exception {
        Fixture customer = newCustomer("100000.00");
        String freshPayee = "500000000041";
        addBeneficiary(customer, freshPayee, 0);
        MvcResult held = transfer(customer.token(), customer.accountNumber(), freshPayee, "60000.00", null);
        String transactionReference = body(held).get("reference").asText();

        String analyst = login("analyst.fraud", "Analyst@123");
        String caseReference = caseFor(analyst, transactionReference);
        mockMvc.perform(post("/api/fraud-cases/" + caseReference + "/decision")
                        .header(HttpHeaders.AUTHORIZATION, analyst)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("decision", "APPROVE", "remarks", "Verified."))))
                .andReturn();

        assertThat(jdbc.queryForObject("select status from bank_transaction where reference = ?", String.class,
                transactionReference)).isEqualTo("APPROVED");
        assertThat(jdbc.queryForObject("select status from fraud_case where reference = ?", String.class,
                caseReference)).isEqualTo("RESOLVED_APPROVED");
        assertThat(balanceOf(customer.accountNumber())).isEqualByComparingTo("40000.00");
    }

    private String caseFor(String analystToken, String transactionReference) throws Exception {
        String response = mockMvc
                .perform(get("/api/fraud-cases").header(HttpHeaders.AUTHORIZATION, analystToken))
                .andReturn().getResponse().getContentAsString();
        for (JsonNode node : objectMapper.readTree(response)) {
            if (transactionReference.equals(node.get("transactionReference").asText())) {
                return node.get("reference").asText();
            }
        }
        throw new AssertionError("no case for " + transactionReference + " in " + response);
    }
}
