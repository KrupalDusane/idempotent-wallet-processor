package com.example.wallet.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "wallets")
public class Wallet {

    @Id
    private UUID id;

    @Column(name = "user_id", unique = true, nullable = false)
    private UUID userId;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal balance;

    protected Wallet() {
        // for JPA
    }

    public Wallet(UUID userId, BigDecimal balance) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.balance = balance;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public void setBalance(BigDecimal balance) {
        this.balance = balance;
    }
}
