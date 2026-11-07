package com.sepro.obfds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/**
 * A customer can cancel a transfer that is still held. Cancelling is not a fraud finding: the
 * transfer ends FAILED with an explicit reason, the funds are released, and a related case is
 * closed with a remark saying so - it must never read as "fraud confirmed".
 */
class CustomerCancelApiTest extends AbstractCommittedApiTest {

    private static final String CANCEL_REASON = "Cancelled by customer";

    private MvcResult cancel(Fixture customer, String reference) throws Exception {
        return call(post("/api/transactions/" + reference + "/cancel"), customer.token(), null);
    }

    @Test
    @DisplayName("CAN-1 A MEDIUM hold can be cancelled: FAILED with a clear reason, funds released, alert closed")
    void customerCancelsAMediumHold() throws Exception {
        Fixture customer = newCustomer("100000.00");
        String reference = holdMedium(customer, "500000000201");

        MvcResult result = cancel(customer, reference);

        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(body(result).get("status").asText()).isEqualTo("FAILED");
        assertThat(txStatus(reference)).isEqualTo("FAILED");
        assertThat(txStatusReason(reference)).startsWith(CANCEL_REASON);
        assertThat(alertStatusOf(reference)).isEqualTo("CLOSED");
        assertThat(heldOn(customer.accountNumber())).isEqualByComparingTo("0");
        assertThat(balanceOf(customer.accountNumber())).isEqualByComparingTo("100000.00");
        assertThat(auditRows("CUSTOMER_CANCELLED", reference)).isEqualTo(1);
    }

    @Test
    @DisplayName("CAN-2 Cancelling a HIGH hold closes its case and says plainly that this is not a fraud finding")
    void customerCancelsAHighHoldAndTheCaseIsClosedWithoutAccusation() throws Exception {
        Fixture customer = newCustomer("100000.00");
        String reference = holdHigh(customer, "500000000211");
        String caseReference = caseReferenceFor(reference);

        assertThat(cancel(customer, reference).getResponse().getStatus()).isEqualTo(200);

        assertThat(txStatus(reference)).isEqualTo("FAILED");
        assertThat(caseStatus(caseReference)).isEqualTo("RESOLVED_BLOCKED");
        String remarks = jdbc.queryForObject("select remarks from fraud_case where reference = ?", String.class, caseReference);
        assertThat(remarks).containsIgnoringCase("cancelled by the customer").containsIgnoringCase("not a fraud finding");
        assertThat(balanceOf(customer.accountNumber())).isEqualByComparingTo("100000.00");
    }

    @Test
    @DisplayName("CAN-3 An analyst cannot approve a case after the customer cancelled; the money never moves")
    void approvalAfterCancellationIsRefused() throws Exception {
        Fixture customer = newCustomer("100000.00");
        String reference = holdHigh(customer, "500000000221");
        String caseReference = caseReferenceFor(reference);
        cancel(customer, reference);

        MvcResult approve = call(post("/api/fraud-cases/" + caseReference + "/decision"), analystToken(),
                Map.of("decision", "APPROVE", "remarks", "Looks fine."));

        assertThat(approve.getResponse().getStatus()).as("the case is already resolved").isIn(409, 422);
        assertThat(balanceOf(customer.accountNumber())).isEqualByComparingTo("100000.00");
    }

    @Test
    @DisplayName("CAN-4 Final transfers and other customers' transfers cannot be cancelled")
    void cancellationIsLimitedToTheOwnersOpenHolds() throws Exception {
        Fixture owner = newCustomer("100000.00");
        Fixture stranger = newCustomer("100000.00");
        String held = holdMedium(owner, "500000000231");

        assertThat(cancel(stranger, held).getResponse().getStatus()).as("someone else's transfer").isEqualTo(404);

        MvcResult settled = transfer(owner.token(), owner.accountNumber(), "500000000232", "500.00", null);
        String settledReference = body(settled).get("reference").asText();
        assertThat(txStatus(settledReference)).isEqualTo("APPROVED");
        assertThat(cancel(owner, settledReference).getResponse().getStatus()).as("an approved transfer").isEqualTo(422);
        assertThat(cancel(owner, "TXN-00000000-NOPE00").getResponse().getStatus()).as("unknown reference").isEqualTo(404);
    }

    @Test
    @DisplayName("CAN-5 Cancelling twice is refused the second time and records one cancellation")
    void secondCancellationIsRefused() throws Exception {
        Fixture customer = newCustomer("100000.00");
        String reference = holdMedium(customer, "500000000241");

        assertThat(cancel(customer, reference).getResponse().getStatus()).isEqualTo(200);
        assertThat(cancel(customer, reference).getResponse().getStatus()).isEqualTo(422);
        assertThat(auditRows("CUSTOMER_CANCELLED", reference)).isEqualTo(1);
    }

    @RepeatedTest(5)
    @DisplayName("CAN-6 RACE: cancel and analyst approval at the same moment have exactly one winner and a consistent ledger")
    void cancelAndApproveRaceHasOneConsistentWinner() throws Exception {
        Fixture customer = newCustomer("100000.00");
        String reference = holdHigh(customer, "500000000251");
        String caseReference = caseReferenceFor(reference);
        String analyst = analystToken();

        List<Callable<MvcResult>> both = new ArrayList<>();
        both.add(() -> cancel(customer, reference));
        both.add(() -> call(post("/api/fraud-cases/" + caseReference + "/decision"), analyst,
                Map.of("decision", "APPROVE", "remarks", "Verified with the customer.")));
        List<MvcResult> results = runTogether(both);

        int cancelStatus = results.get(0).getResponse().getStatus();
        int approveStatus = results.get(1).getResponse().getStatus();
        assertThat(List.of(cancelStatus, approveStatus)).as("no 5xx, no lost update").allMatch(status -> status < 500);

        boolean approved = "APPROVED".equals(txStatus(reference));
        if (approved) {
            assertThat(cancelStatus).isIn(409, 422);
            assertThat(caseStatus(caseReference)).isEqualTo("RESOLVED_APPROVED");
            assertThat(balanceOf(customer.accountNumber())).isEqualByComparingTo("40000.00");
        } else {
            assertThat(cancelStatus).isEqualTo(200);
            assertThat(approveStatus).isIn(409, 422);
            assertThat(txStatus(reference)).isEqualTo("FAILED");
            assertThat(caseStatus(caseReference)).isEqualTo("RESOLVED_BLOCKED");
            assertThat(balanceOf(customer.accountNumber())).isEqualByComparingTo("100000.00");
        }
        assertThat(heldOn(customer.accountNumber())).isEqualByComparingTo("0");
    }
}
