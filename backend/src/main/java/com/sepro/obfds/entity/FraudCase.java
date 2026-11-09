package com.sepro.obfds.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * An investigation record opened from a {@link FraudAlert} and worked on by a fraud analyst
 * (FR-15, FR-18).
 */
@Entity
@Table(name = "fraud_case")
public class FraudCase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "reference", nullable = false, unique = true, length = 30)
    private String reference;

    @OneToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "alert_id", nullable = false, unique = true)
    private FraudAlert alert;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private CaseStatus status = CaseStatus.OPEN;

    @Column(name = "assigned_to", length = 60)
    private String assignedTo;

    @Column(name = "remarks", length = 500)
    private String remarks;

    @Column(name = "decided_by", length = 60)
    private String decidedBy;

    /**
     * What the transfer's owner said about the held transfer: CONFIRMED_MINE or NOT_MINE. Written
     * once. It is evidence for the analyst and never releases or blocks money on its own.
     */
    @Column(name = "customer_response", length = 16)
    private String customerResponse;

    @Column(name = "customer_responded_at")
    private Instant customerRespondedAt;

    /** The time by which an analyst is expected to review this case - a target, not an enforced deadline. */
    @Column(name = "review_due_at")
    private Instant reviewDueAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getReference() {
        return reference;
    }

    public void setReference(String reference) {
        this.reference = reference;
    }

    public FraudAlert getAlert() {
        return alert;
    }

    public void setAlert(FraudAlert alert) {
        this.alert = alert;
    }

    public CaseStatus getStatus() {
        return status;
    }

    public void setStatus(CaseStatus status) {
        this.status = status;
    }

    public String getAssignedTo() {
        return assignedTo;
    }

    public void setAssignedTo(String assignedTo) {
        this.assignedTo = assignedTo;
    }

    public String getRemarks() {
        return remarks;
    }

    public void setRemarks(String remarks) {
        this.remarks = remarks;
    }

    public String getDecidedBy() {
        return decidedBy;
    }

    public void setDecidedBy(String decidedBy) {
        this.decidedBy = decidedBy;
    }

    public String getCustomerResponse() {
        return customerResponse;
    }

    public void setCustomerResponse(String customerResponse) {
        this.customerResponse = customerResponse;
    }

    public Instant getCustomerRespondedAt() {
        return customerRespondedAt;
    }

    public void setCustomerRespondedAt(Instant customerRespondedAt) {
        this.customerRespondedAt = customerRespondedAt;
    }

    public Instant getReviewDueAt() {
        return reviewDueAt;
    }

    public void setReviewDueAt(Instant reviewDueAt) {
        this.reviewDueAt = reviewDueAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    public void setClosedAt(Instant closedAt) {
        this.closedAt = closedAt;
    }
}
