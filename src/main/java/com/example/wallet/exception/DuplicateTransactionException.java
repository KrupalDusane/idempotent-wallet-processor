package com.example.wallet.exception;

import java.util.UUID;

/**
 * Internal signal: another thread already claimed this transactionId.
 * Not exposed to the HTTP layer directly.
 */
public class DuplicateTransactionException extends RuntimeException {

    private final UUID transactionId;

    public DuplicateTransactionException(UUID transactionId) {
        super("Transaction already claimed: " + transactionId);
        this.transactionId = transactionId;
    }

    public UUID getTransactionId() {
        return transactionId;
    }
}
