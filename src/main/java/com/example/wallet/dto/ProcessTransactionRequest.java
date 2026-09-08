package com.example.wallet.dto;

import com.example.wallet.model.TransactionType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

public record ProcessTransactionRequest(
        @NotNull UUID transactionId,
        @NotNull UUID userId,
        @NotNull
        @DecimalMin(value = "0.01", message = "amount must be at least 0.01")
        @Digits(integer = 17, fraction = 2, message = "amount supports at most 2 decimal places")
        BigDecimal amount,
        @NotNull TransactionType type
) {
}
