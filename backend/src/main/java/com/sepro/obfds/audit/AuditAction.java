package com.sepro.obfds.audit;

/**
 * The fixed set of audited action names (FR-20).
 *
 * <p>Keeping them in one place stops the same event being logged under three different spellings,
 * which would make the audit view impossible to filter.</p>
 */
public final class AuditAction {

    public static final String REGISTER = "REGISTER";
    public static final String LOGIN_SUCCESS = "LOGIN_SUCCESS";
    public static final String LOGIN_FAILURE = "LOGIN_FAILURE";
    public static final String BENEFICIARY_ADDED = "BENEFICIARY_ADDED";
    public static final String BENEFICIARY_REMOVED = "BENEFICIARY_REMOVED";
    public static final String TRANSFER_INITIATED = "TRANSFER_INITIATED";
    public static final String TRANSFER_REJECTED = "TRANSFER_REJECTED";
    public static final String RISK_EVALUATED = "RISK_EVALUATED";
    public static final String FRAUD_ALERT_RAISED = "FRAUD_ALERT_RAISED";
    public static final String VERIFICATION_REQUESTED = "VERIFICATION_REQUESTED";
    public static final String VERIFICATION_SUCCESS = "VERIFICATION_SUCCESS";
    public static final String VERIFICATION_FAILED = "VERIFICATION_FAILED";
    public static final String TRANSACTION_APPROVED = "TRANSACTION_APPROVED";
    public static final String TRANSACTION_BLOCKED = "TRANSACTION_BLOCKED";
    public static final String FRAUD_CASE_OPENED = "FRAUD_CASE_OPENED";
    public static final String FRAUD_CASE_DECIDED = "FRAUD_CASE_DECIDED";
    public static final String DISPUTE_SUBMITTED = "DISPUTE_SUBMITTED";
    public static final String DISPUTE_RESOLVED = "DISPUTE_RESOLVED";
    public static final String USER_ROLES_UPDATED = "USER_ROLES_UPDATED";
    public static final String USER_STATUS_UPDATED = "USER_STATUS_UPDATED";
    public static final String REPORT_GENERATED = "REPORT_GENERATED";

    // Fraud workflow (customer cancel / statement, verification expiry, evidence, escalation handoff).
    public static final String CUSTOMER_CANCELLED = "CUSTOMER_CANCELLED";
    public static final String CUSTOMER_RESPONSE_RECORDED = "CUSTOMER_RESPONSE_RECORDED";
    public static final String VERIFICATION_EXPIRED = "VERIFICATION_EXPIRED";
    public static final String EVIDENCE_EXPORTED = "EVIDENCE_EXPORTED";
    public static final String ESCALATION_RECORDED = "ESCALATION_RECORDED";
    public static final String ESCALATION_HANDOFF_ISSUED = "ESCALATION_HANDOFF_ISSUED";
    public static final String ESCALATION_USER_ATTESTED_SENT = "ESCALATION_USER_ATTESTED_SENT";

    private AuditAction() {
        // Constants only.
    }
}
