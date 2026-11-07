package com.sepro.obfds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.fasterxml.jackson.databind.JsonNode;
import com.sepro.obfds.fraud.FraudRiskService;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MvcResult;

/**
 * An idempotency key is how a client says "this is the same request as before", so it must be
 * honoured for exactly its owner and exactly its payload (P0 finding: cross-customer disclosure).
 *
 * <p>Semantics under test:</p>
 * <ul>
 *   <li>same owner, same normalised payload: replay the original answer, never a second transfer,
 *       including when the two requests are in flight together;</li>
 *   <li>same owner, different payload: 409 (a key cannot be reused for another transfer);</li>
 *   <li>different owner: 409 and <b>nothing</b> about the other customer's transfer in the body;</li>
 *   <li>a different key is a potentially new, intentional transfer and is allowed.</li>
 * </ul>
 */
class IdempotencyApiTest extends AbstractCommittedApiTest {

    @MockitoSpyBean private FraudRiskService fraudRisk;

    private static final String EXTERNAL_PAYEE = "500000000009";

    @Test
    @DisplayName("IDEM-1 Another customer's key never discloses a transfer: 409 and no foreign data")
    void crossCustomerKeyReuseDisclosesNothing() throws Exception {
        Fixture owner = newCustomer("100000.00");
        Fixture other = newCustomer("100000.00");
        String key = "shared-" + UUID.randomUUID();

        MvcResult first = transfer(owner.token(), owner.accountNumber(), EXTERNAL_PAYEE, "1234.00", key);
        assertThat(first.getResponse().getStatus()).isEqualTo(201);

        MvcResult second = transfer(other.token(), other.accountNumber(), "500000000010", "777.00", key);

        String response = second.getResponse().getContentAsString();
        assertThat(second.getResponse().getStatus()).as("response was: " + response).isEqualTo(409);
        assertThat(response)
                .doesNotContain(owner.accountNumber())
                .doesNotContain(owner.username())
                .doesNotContain("1234")
                .doesNotContain(body(first).get("reference").asText());
        assertThat(balanceOf(other.accountNumber())).isEqualByComparingTo("100000.00");
        assertThat(rowsWithKey(key)).isEqualTo(1);
    }

    @Test
    @DisplayName("IDEM-2 The same customer reusing a key for a different payload gets 409, not the old transfer")
    void sameOwnerDifferentPayloadIsRejected() throws Exception {
        Fixture owner = newCustomer("100000.00");
        String key = "reuse-" + UUID.randomUUID();

        assertThat(transfer(owner.token(), owner.accountNumber(), EXTERNAL_PAYEE, "1000.00", key)
                .getResponse().getStatus()).isEqualTo(201);

        MvcResult different = transfer(owner.token(), owner.accountNumber(), EXTERNAL_PAYEE, "2000.00", key);

        assertThat(different.getResponse().getStatus()).isEqualTo(409);
        assertThat(balanceOf(owner.accountNumber())).isEqualByComparingTo("99000.00");
    }

    @Test
    @DisplayName("IDEM-3 Regression guard: an exact retry replays the original and moves money once")
    void exactRetryReplaysOriginal() throws Exception {
        Fixture owner = newCustomer("100000.00");
        String key = "retry-" + UUID.randomUUID();

        MvcResult first = transfer(owner.token(), owner.accountNumber(), EXTERNAL_PAYEE, "1500.00", key);
        MvcResult retry = transfer(owner.token(), owner.accountNumber(), EXTERNAL_PAYEE, "1500.00", key);

        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(retry.getResponse().getStatus()).isEqualTo(201);
        assertThat(body(retry).get("reference").asText()).isEqualTo(body(first).get("reference").asText());
        assertThat(balanceOf(owner.accountNumber())).isEqualByComparingTo("98500.00");
        assertThat(rowsWithKey(key)).isEqualTo(1);
    }

    @Test
    @DisplayName("IDEM-4 Retry after an ambiguous failure: an amount written differently is still the same payload")
    void equivalentAmountSpellingIsTheSamePayload() throws Exception {
        Fixture owner = newCustomer("100000.00");
        String key = "spelling-" + UUID.randomUUID();

        MvcResult first = transfer(owner.token(), owner.accountNumber(), EXTERNAL_PAYEE, "1500.00", key);
        MvcResult retry = transfer(owner.token(), owner.accountNumber(), EXTERNAL_PAYEE, "1500", key);

        assertThat(retry.getResponse().getStatus()).isEqualTo(201);
        assertThat(body(retry).get("reference").asText()).isEqualTo(body(first).get("reference").asText());
        assertThat(balanceOf(owner.accountNumber())).isEqualByComparingTo("98500.00");
    }

    @Test
    @DisplayName("IDEM-5 Two identical requests in flight together replay one transfer (no 409, no 500, one row)")
    void parallelIdenticalRetriesReplayExactlyOnce() throws Exception {
        Fixture owner = newCustomer("100000.00");
        String key = "parallel-" + UUID.randomUUID();
        forceOverlapInsideTheRequest(2);

        List<Callable<MvcResult>> both = List.of(
                () -> transfer(owner.token(), owner.accountNumber(), EXTERNAL_PAYEE, "2500.00", key),
                () -> transfer(owner.token(), owner.accountNumber(), EXTERNAL_PAYEE, "2500.00", key));
        List<MvcResult> results = runTogether(both);

        assertThat(results).extracting(r -> r.getResponse().getStatus()).containsExactly(201, 201);
        JsonNode a = body(results.get(0));
        JsonNode b = body(results.get(1));
        assertThat(a.get("reference").asText()).isEqualTo(b.get("reference").asText());
        assertThat(rowsWithKey(key)).isEqualTo(1);
        assertThat(balanceOf(owner.accountNumber())).isEqualByComparingTo("97500.00");
    }

    @Test
    @DisplayName("IDEM-6 A new key is a potentially new transfer: two intentional identical payments both execute")
    void newKeyMeansANewTransfer() throws Exception {
        Fixture owner = newCustomer("100000.00");

        MvcResult one = transfer(owner.token(), owner.accountNumber(), EXTERNAL_PAYEE, "1000.00", "k1-" + UUID.randomUUID());
        MvcResult two = transfer(owner.token(), owner.accountNumber(), EXTERNAL_PAYEE, "1000.00", "k2-" + UUID.randomUUID());

        assertThat(one.getResponse().getStatus()).isEqualTo(201);
        assertThat(two.getResponse().getStatus()).isEqualTo(201);
        assertThat(body(two).get("reference").asText()).isNotEqualTo(body(one).get("reference").asText());
        assertThat(balanceOf(owner.accountNumber())).isEqualByComparingTo(new BigDecimal("98000.00"));
    }

    /**
     * Holds each request inside the risk evaluation until {@code parties} requests have arrived
     * there, which on the old code guarantees they overlap. On the fixed code only one request can
     * reach this point at a time (the others wait on the account lock), so the wait is bounded
     * and the request then simply proceeds: the test must not hang on correct code.
     */
    private void forceOverlapInsideTheRequest(int parties) {
        CyclicBarrier inside = new CyclicBarrier(parties);
        doAnswer(invocation -> {
            try {
                inside.await(2, TimeUnit.SECONDS);
            } catch (TimeoutException | BrokenBarrierException ignored) {
                // Only one request got here: the system is serialising them, which is the goal.
            }
            return invocation.callRealMethod();
        }).when(fraudRisk).evaluate(any(), any(), any(), any());
    }
}
