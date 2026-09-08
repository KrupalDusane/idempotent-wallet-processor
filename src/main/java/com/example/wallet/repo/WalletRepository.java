package com.example.wallet.repo;

import com.example.wallet.model.Wallet;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

public interface WalletRepository extends JpaRepository<Wallet, UUID> {

    Optional<Wallet> findByUserId(UUID userId);

    /**
     * Atomic, database-level conditional update. The balance check AND the
     * deduction happen in a single UPDATE statement while the row lock is
     * held, so concurrent debits can never drive a balance negative.
     *
     * @return 1 if the debit succeeded, 0 if there were insufficient funds.
     */
    @Modifying
    @Query("UPDATE Wallet w SET w.balance = w.balance - :amount " +
           "WHERE w.id = :id AND w.balance >= :amount")
    int debitIfSufficient(@Param("id") UUID id, @Param("amount") BigDecimal amount);

    @Modifying
    @Query("UPDATE Wallet w SET w.balance = w.balance + :amount WHERE w.id = :id")
    int credit(@Param("id") UUID id, @Param("amount") BigDecimal amount);
}
