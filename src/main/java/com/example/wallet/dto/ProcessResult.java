package com.example.wallet.dto;

/**
 * Wraps the business response plus a flag telling the controller whether this
 * was the original execution (201 Created) or a replayed duplicate
 * (200 OK with the cached original payload).
 */
public record ProcessResult(TransactionResponse response, boolean duplicate) {

    public static ProcessResult original(TransactionResponse response) {
        return new ProcessResult(response, false);
    }

    public static ProcessResult replayed(TransactionResponse response) {
        return new ProcessResult(response, true);
    }
}
