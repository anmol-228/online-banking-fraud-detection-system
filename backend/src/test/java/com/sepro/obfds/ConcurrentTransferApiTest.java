package com.sepro.obfds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Proves that two genuinely concurrent transfers against the same account cannot both settle
 * (NFR-02, NFR-09) — the evidence gap named in the project's optimistic-locking design.
 *
 * <p>This class deliberately does <strong>not</strong> extend {@link AbstractApiTest}. The race
 * only exists if both requests reach the database before either one commits, so the account
 * balance change has to be a real commit, not a change hidden inside a test-managed transaction
 * that gets rolled back afterwards. For the same reason this test permanently spends part of the
 * seeded {@code ravi.kumar} balance and must not run in the same JVM as tests that assume that
 * balance is untouched — see the {@code concurrency} tag below and {@code pom.xml}, which excludes
 * this tag from the default execution of {@code mvn test}: it runs in the separate
 * {@code committed-data-tests} execution (own JVM). To run only this class:</p>
 *
 * <pre>mvn test -Dtest=ConcurrentTransferApiTest -Dsurefire.failIfNoSpecifiedTests=false</pre>
 *
 * <p>Two coordination choices matter: threads are released together with a {@link CyclicBarrier},
 * never {@code Thread.sleep}; and the two transfer amounts are chosen so that both cannot succeed
 * (60,000 available, two requests of 35,000 and 35,500).</p>
 *
 * <p>What a correct outcome is changed when transfers became serialised per account. Previously the
 * loser of the race was stopped only by an optimistic-version conflict at commit (HTTP 409). Now the
 * loser waits for the winner's account lock, then reads the reduced balance and is refused for
 * insufficient funds (HTTP 422); a 409 or 503 here would mean the transfers are no longer being
 * serialised. The stronger, forced-interleaving versions of this check - including over-reservation
 * by held transfers - are in {@link MoneyPathIntegrityTest}.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Tag("concurrency")
class ConcurrentTransferApiTest {

    private static final String CUSTOMER_USERNAME = "ravi.kumar";
    private static final String CUSTOMER_PASSWORD = "Customer@123";

    /** ravi.kumar's CURRENT account — seeded with 60,000.00 and untouched by any other test. */
    private static final String SOURCE_ACCOUNT = "900000000002";

    /** An established beneficiary of ravi.kumar, backdated by the seeder so it scores 0 risk points. */
    private static final String DESTINATION_ACCOUNT = "900000000003";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    private String login() throws Exception {
        String body = objectMapper.writeValueAsString(
                Map.of("username", CUSTOMER_USERNAME, "password", CUSTOMER_PASSWORD));
        String response = mockMvc
                .perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return "Bearer " + objectMapper.readTree(response).get("token").asText();
    }

    private Map<String, Object> transferPayload(String amount) {
        return Map.of(
                "sourceAccountNumber", SOURCE_ACCOUNT,
                "destinationAccountNumber", DESTINATION_ACCOUNT,
                "amount", amount,
                "description", "Concurrency test transfer");
    }

    @Test
    @Tag("concurrency")
    @DisplayName(
            "NFR-02, NFR-09: two simultaneous transfers against one account settle exactly once, "
                    + "the other is rejected for insufficient funds")
    void onlyOneOfTwoSimultaneousTransfersSettles() throws Exception {
        String token = login();

        // Confirms the starting point this test's arithmetic depends on, so a failure here points
        // straight at a seeding change rather than showing up as a confusing race-test failure.
        JsonNode startingBalance = balance(token, SOURCE_ACCOUNT);
        assertThat(startingBalance.get("balance").asDouble()).isEqualTo(60000.00);
        assertThat(startingBalance.get("reservedAmount").asDouble()).isZero();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier startTogether = new CyclicBarrier(2);
        try {
            Callable<MvcResult> requestA = raceTransfer(token, "35000.00", startTogether);
            Callable<MvcResult> requestB = raceTransfer(token, "35500.00", startTogether);

            Future<MvcResult> futureA = pool.submit(requestA);
            Future<MvcResult> futureB = pool.submit(requestB);

            int statusA = futureA.get(10, TimeUnit.SECONDS).getResponse().getStatus();
            int statusB = futureB.get(10, TimeUnit.SECONDS).getResponse().getStatus();
            List<Integer> statuses = List.of(statusA, statusB);

            assertThat(statuses)
                    .as("exactly one transfer settles (201) and the other, which waited for the "
                            + "account lock and then saw the reduced balance, is rejected for "
                            + "insufficient funds (422). Two 201s would be a double spend; a 409 or "
                            + "503 would mean the requests are not being serialised")
                    .containsExactlyInAnyOrder(201, 422);
        } finally {
            pool.shutdownNow();
        }

        // The account moved by exactly one of the two amounts — never zero, never both.
        JsonNode finalBalance = balance(token, SOURCE_ACCOUNT);
        double remaining = finalBalance.get("balance").asDouble();
        assertThat(remaining)
                .as("balance must reflect exactly one settled transfer, not zero and not both")
                .isIn(25000.00, 24500.00);
    }

    private Callable<MvcResult> raceTransfer(String token, String amount, CyclicBarrier startTogether) {
        return () -> {
            startTogether.await(10, TimeUnit.SECONDS);
            return mockMvc
                    .perform(post("/api/transactions/transfer")
                            .header(HttpHeaders.AUTHORIZATION, token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(transferPayload(amount))))
                    .andReturn();
        };
    }

    private JsonNode balance(String token, String accountNumber) throws Exception {
        String response = mockMvc
                .perform(get("/api/accounts/" + accountNumber + "/balance")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response);
    }
}
