package com.sepro.obfds;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

/**
 * Account and balance tests.
 *
 * <p>Covers TC-04 and the customer isolation part of TC-03.</p>
 */
class AccountApiTest extends AbstractApiTest {

    @Test
    @DisplayName("TC-04 A customer can read the balance of their own account (FR-06)")
    void tc04BalanceEnquiry() throws Exception {
        mockMvc.perform(get("/api/accounts/" + CUSTOMER_ACCOUNT + "/balance")
                        .header(HttpHeaders.AUTHORIZATION, customerToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountNumber").value(CUSTOMER_ACCOUNT))
                .andExpect(jsonPath("$.balance").value(150000.00))
                .andExpect(jsonPath("$.availableBalance").value(150000.00))
                .andExpect(jsonPath("$.reservedAmount").value(0))
                .andExpect(jsonPath("$.currency").value("INR"));
    }

    @Test
    @DisplayName("FR-05 A customer can list their own accounts")
    void listsOwnAccounts() throws Exception {
        mockMvc.perform(get("/api/accounts").header(HttpHeaders.AUTHORIZATION, customerToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].accountNumber").value(CUSTOMER_ACCOUNT))
                .andExpect(jsonPath("$[0].accountType").value("SAVINGS"));
    }

    @Test
    @DisplayName("TC-03 A customer cannot read the balance of another customer account (NFR-08)")
    void tc03CannotReadOtherCustomerBalance() throws Exception {
        mockMvc.perform(get("/api/accounts/" + SECOND_CUSTOMER_ACCOUNT + "/balance")
                        .header(HttpHeaders.AUTHORIZATION, customerToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("FR-05 Requesting an account that does not exist returns 404")
    void unknownAccountReturnsNotFound() throws Exception {
        mockMvc.perform(get("/api/accounts/999999/balance")
                        .header(HttpHeaders.AUTHORIZATION, customerToken()))
                .andExpect(status().isNotFound());
    }
}
