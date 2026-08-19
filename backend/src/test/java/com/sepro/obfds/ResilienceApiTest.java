package com.sepro.obfds;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sepro.obfds.repository.TransactionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Failure-handling tests. Covers TC-19 (NFR-02, NFR-07).
 *
 * <p>A persistence failure is simulated by replacing one repository with a mock that throws the
 * exception Spring raises when the database cannot be reached. The point of the test is that the
 * caller receives a clean, non-technical response rather than a stack trace (NFR-01).</p>
 */
class ResilienceApiTest extends AbstractApiTest {

    @MockitoBean private TransactionRepository transactionRepository;

    @Test
    @DisplayName("TC-19 A database failure returns 503 and no internal detail (NFR-01, NFR-02)")
    void tc19DatabaseFailureIsHandled() throws Exception {
        String token = customerToken();

        when(transactionRepository.sumReservedAmount(anyLong()))
                .thenThrow(new DataAccessResourceFailureException("Simulated database outage"));

        mockMvc.perform(get("/api/accounts/" + CUSTOMER_ACCOUNT + "/balance")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.message")
                        .value("The service is temporarily unable to process this request. Please try again shortly."))
                // The simulated technical detail must not reach the caller.
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("Simulated database outage"))));
    }

    @Test
    @DisplayName("TC-19 An unknown address returns 404 rather than a server error (NFR-07)")
    void tc19UnknownAddressReturnsNotFound() throws Exception {
        mockMvc.perform(get("/api/does-not-exist").header(HttpHeaders.AUTHORIZATION, customerToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("TC-19 The health endpoint is reachable without authentication (NFR-03)")
    void tc19HealthEndpointIsPublic() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @DisplayName("TC-19 The error body always carries a timestamp, a code and the path (NFR-07)")
    void tc19ErrorShapeIsConsistent() throws Exception {
        String token = customerToken();

        when(transactionRepository.sumReservedAmount(anyLong()))
                .thenThrow(new DataAccessResourceFailureException("Simulated database outage"));

        mockMvc.perform(get("/api/accounts/" + CUSTOMER_ACCOUNT + "/balance")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.path").value("/api/accounts/" + CUSTOMER_ACCOUNT + "/balance"));
    }
}
