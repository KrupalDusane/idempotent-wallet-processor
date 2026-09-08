package com.example.wallet.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One row per unique transactionId. The primary key IS the idempotency key:
 * the database unique constraint guarantees that only one request can ever
 * claim a transactionId, even under concurrent load.
 */
@Entity
@Table(name = "transaction_records")
public class TransactionRecord {

    @Id
    @Column(name = "transaction_id")
    private UUID transactionId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TransactionType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private TransactionStatus status;

    /**
     * The exact JSON response returned to the original caller. Duplicates
     * replay this payload instead of re-executing the transaction.
     */
    @Column(name = "response_payload", length = 4000)
    private String responsePayload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected TransactionRecord() {
        // for JPA
    }

    public TransactionRecord(UUID transactionId, UUID userId, BigDecimal amount, TransactionType type) {
        this.transactionId = transactionId;
        this.userId = userId;
        this.amount = amount;
        this.type = type;
        this.status = TransactionStatus.PENDING;
        this.createdAt = Instant.now();
    }

    public UUID getTransactionId() {
        return transactionId;
    }

    public UUID getUserId() {
        return userId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public TransactionType getType() {
        return type;
    }

    public TransactionStatus getStatus() {
        return status;
    }

    public void setStatus(TransactionStatus status) {
        this.status = status;
    }

    public String getResponsePayload() {
        return responsePayload;
    }

    public void setResponsePayload(String responsePayload) {
        this.responsePayload = responsePayload;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
