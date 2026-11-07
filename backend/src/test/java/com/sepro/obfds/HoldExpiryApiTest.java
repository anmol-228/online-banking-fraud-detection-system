package com.sepro.obfds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

/**
 * A MEDIUM-risk transfer is held until the customer enters a code. If the code expires, the money
 * must stop being promised, the transfer must end in a truthful terminal state, and the alert that
 * the hold raised must not be left open for an analyst to chase.
 *
 * <p>Codes are made to expire immediately ({@code code-validity-minutes=-1}) so the tests need no
 * clock manipulation. "Truthful" means FAILED ("verification expired, transfer not sent"), not
 * BLOCKED: nobody judged the transfer fraudulent, it simply timed out.</p>
 */
@TestPropertySource(properties = "obfds.verification.code-validity-minutes=-1")
class HoldExpiryApiTest extends AbstractCommittedApiTest {

    private static final String EXPIRY_REASON_FRAGMENT = "expired";

    /**
     * A MEDIUM hold whose code is already past its expiry. The customer-facing response already
     * reports it as FAILED (that is the point of EXP-4), so the stored status is checked instead.
     */
    private String holdThatExpiresImmediately(Fixture customer, String payee) throws Exception {
        MvcResult result = transfer(customer.token(), customer.accountNumber(), payee, "60000.00", null);
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        String reference = body(result).get("reference").asText();
        assertThat(txStatus(reference)).as("stored status before anything sweeps it").isEqualTo("PENDING_VERIFICATION");
        return reference;
    }

    @Test
    @DisplayName("EXP-1 An expired hold no longer reserves funds, so the same money can be used again")
    void expiredHoldDoesNotReserveFunds() throws Exception {
        Fixture customer = newCustomer("100000.00");
        holdThatExpiresImmediately(customer, "500000000101");

        var balance = body(call(get("/api/accounts/" + customer.accountNumber() + "/balance"), customer.token(), null));
        assertThat(balance.get("reservedAmount").decimalValue()).isEqualByComparingTo("0");
        assertThat(balance.get("availableBalance").decimalValue()).isEqualByComparingTo("100000.00");

        // 60,000 + 60,000 would exceed 100,000 if the first hold were still counted.
        MvcResult second = transfer(customer.token(), customer.accountNumber(), "500000000102", "60000.00", null);
        assertThat(second.getResponse().getStatus()).as(second.getResponse().getContentAsString()).isEqualTo(201);
    }

    @Test
    @DisplayName("EXP-2 Entering a code after expiry ends the transfer FAILED (not BLOCKED) and closes its alert")
    void lateCodeEndsTheTransferFailedAndClosesTheAlert() throws Exception {
        Fixture customer = newCustomer("100000.00");
        String reference = holdThatExpiresImmediately(customer, "500000000111");

        MvcResult late = call(post("/api/transactions/" + reference + "/verify"), customer.token(), Map.of("code", "123456"));

        assertThat(late.getResponse().getStatus()).isEqualTo(410);
        assertThat(txStatus(reference)).isEqualTo("FAILED");
        assertThat(txStatusReason(reference)).containsIgnoringCase(EXPIRY_REASON_FRAGMENT);
        assertThat(alertStatusOf(reference)).isEqualTo("CLOSED");
        assertThat(balanceOf(customer.accountNumber())).isEqualByComparingTo("100000.00");
    }

    @Test
    @DisplayName("EXP-3 A hold nobody touched is closed the next time the account is used (no scheduler needed)")
    void staleHoldIsSweptOnTheNextWriteToTheAccount() throws Exception {
        Fixture customer = newCustomer("100000.00");
        String stale = holdThatExpiresImmediately(customer, "500000000121");

        MvcResult next = transfer(customer.token(), customer.accountNumber(), "500000000122", "1000.00", null);
        assertThat(next.getResponse().getStatus()).isEqualTo(201);

        assertThat(txStatus(stale)).isEqualTo("FAILED");
        assertThat(alertStatusOf(stale)).isEqualTo("CLOSED");
        assertThat(auditRows("VERIFICATION_EXPIRED", stale)).isEqualTo(1);
    }

    @Test
    @DisplayName("EXP-4 The customer's own view agrees: an expired hold reads FAILED and releases the funds")
    void customerViewShowsTheExpiredHoldAsFailed() throws Exception {
        Fixture customer = newCustomer("100000.00");
        String reference = holdThatExpiresImmediately(customer, "500000000131");

        var view = body(call(get("/api/transactions/" + reference), customer.token(), null));

        assertThat(view.get("status").asText()).isEqualTo("FAILED");
        assertThat(view.get("verificationRequired").asBoolean()).isFalse();
    }
}
