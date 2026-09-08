package com.example.wallet.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record WalletResponse(UUID userId, BigDecimal balance) {
}
