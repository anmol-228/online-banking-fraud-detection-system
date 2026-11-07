package com.sepro.obfds;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * More simultaneous requests than the database pool has connections must queue, not fail.
 *
 * <p>Root cause being guarded against: every audit write runs in its own transaction, so a request
 * holds its business connection <em>and</em> waits for a second one. With a worker thread per
 * request and more threads than half the pool, all connections end up held by requests that are
 * each waiting for another connection; nothing completes until the pool timeout, and every waiting
 * request fails with HTTP 500 (observed on MySQL at pool size 8 with 8 or more simultaneous
 * transfers: 100 percent 500 after about 22 seconds).</p>
 *
 * <p>This uses the real servlet container and real HTTP, because the failure lives in the thread
 * and connection accounting that MockMvc bypasses. The connection timeout is lowered only so a
 * regression fails in seconds rather than half a minute.</p>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.hikari.connection-timeout=5000")
class PoolStarvationApiTest extends AbstractCommittedApiTest {

    @LocalServerPort private int port;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private int send(HttpRequest.Builder request) throws Exception {
        return http.send(request.timeout(Duration.ofSeconds(60)).build(), HttpResponse.BodyHandlers.ofString())
                .statusCode();
    }

    private HttpRequest.Builder post(String path, String token, String json) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        if (token != null) {
            builder.header("Authorization", token);
        }
        return builder;
    }

    @Test
    @DisplayName("PERF-1 pool+4 simultaneous transfers, bad logins and health checks: zero 5xx and every transfer settles")
    void burstLargerThanThePoolNeverProducesServerErrors() throws Exception {
        int pool = 10; // the default configured in application.yml
        int transfers = pool + 4;

        List<Fixture> customers = new ArrayList<>();
        for (int i = 0; i < transfers; i++) {
            customers.add(newCustomer("50000.00"));
        }

        List<Callable<int[]>> work = new ArrayList<>();
        for (Fixture customer : customers) {
            work.add(() -> new int[] {0, send(post("/api/transactions/transfer", customer.token(),
                    objectMapper.writeValueAsString(transferBody(
                            customer.accountNumber(), "500000000009", "800.00", null))))});
        }
        for (int i = 0; i < 6; i++) {
            String user = customers.get(i).username();
            work.add(() -> new int[] {1, send(post("/api/auth/login", null,
                    "{\"username\":\"" + user + "\",\"password\":\"Wrong@Password1\"}"))});
        }
        for (int i = 0; i < 4; i++) {
            work.add(() -> new int[] {2, send(HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + port + "/actuator/health")).GET())});
        }

        long started = System.nanoTime();
        List<int[]> results = runTogether(work);
        long seconds = Duration.ofNanos(System.nanoTime() - started).toSeconds();

        assertThat(results).filteredOn(r -> r[1] >= 500)
                .as("no request may fail with a server error (HTTP 5xx)").isEmpty();
        assertThat(results).filteredOn(r -> r[0] == 0).extracting(r -> r[1])
                .as("every transfer settles").containsOnly(201);
        assertThat(results).filteredOn(r -> r[0] == 1).extracting(r -> r[1])
                .as("bad logins are plain 401s").containsOnly(401);
        assertThat(seconds).as("the burst completes promptly instead of waiting for a pool timeout")
                .isLessThan(20);
        for (Fixture customer : customers) {
            assertThat(balanceOf(customer.accountNumber())).isEqualByComparingTo("49200.00");
        }
    }
}
