package com.example.wallet.dto;

import com.example.wallet.model.TransactionStatus;
import com.example.wallet.model.TransactionType;

import java.math.BigDecimal;
import java.util.UUID;

public record TransactionResponse(
        UUID transactionId,
        UUID userId,
        TransactionType type,
        TransactionStatus status,
        BigDecimal amount,
        BigDecimal balanceAfter,
        String message
) {
}
