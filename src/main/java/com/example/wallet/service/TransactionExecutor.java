package com.example.wallet.service;

import com.example.wallet.dto.ProcessTransactionRequest;
import com.example.wallet.dto.TransactionResponse;
import com.example.wallet.exception.DuplicateTransactionException;
import com.example.wallet.model.TransactionRecord;
import com.example.wallet.model.TransactionStatus;
import com.example.wallet.model.TransactionType;
import com.example.wallet.model.Wallet;
import com.example.wallet.repo.TransactionRecordRepository;
import com.example.wallet.repo.WalletRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Holds the two transactional units of work. Kept separate from
 * {@link TransactionService} on purpose: a duplicate request must be detected
 * in its OWN committed transaction (the unique-constraint violation must not
 * poison the wallet transaction), so each public method here is invoked
 * through the Spring proxy from a non-transactional orchestrator.
 */
@Component
public class TransactionExecutor {

    private final WalletRepository walletRepository;
    private final TransactionRecordRepository recordRepository;
    private final ObjectMapper objectMapper;
    private final EntityManager entityManager;

    public TransactionExecutor(WalletRepository walletRepository,
                               TransactionRecordRepository recordRepository,
                               ObjectMapper objectMapper,
                               EntityManager entityManager) {
        this.walletRepository = walletRepository;
        this.recordRepository = recordRepository;
        this.objectMapper = objectMapper;
        this.entityManager = entityManager;
    }

    /**
     * Claims the transactionId by inserting a PENDING row. The PK unique
     * constraint guarantees exactly one winner even if N requests arrive
     * within the same millisecond. Losers get DuplicateTransactionException.
     */
    @Transactional
    public TransactionRecord claim(ProcessTransactionRequest request) {
        TransactionRecord record = new TransactionRecord(
                request.transactionId(), request.userId(), request.amount(), request.type());
        try {
            return recordRepository.saveAndFlush(record);
        } catch (DataIntegrityViolationException e) {
            throw new DuplicateTransactionException(request.transactionId());
        }
    }

    /**
     * Applies the wallet mutation atomically and stores the final response
     * JSON on the record so later duplicates can replay it byte-for-byte.
     */
    @Transactional
    public TransactionResponse execute(TransactionRecord record, ProcessTransactionRequest request) {
        Wallet wallet = walletRepository.findByUserId(request.userId())
                .orElseGet(() -> createWalletSafely(request.userId()));

        int updated = request.type() == TransactionType.DEBIT
                ? walletRepository.debitIfSufficient(wallet.getId(), request.amount())
                : walletRepository.credit(wallet.getId(), request.amount());

        TransactionResponse response;
        if (updated == 0) {
            // Only possible for DEBIT: balance stayed untouched.
            record.setStatus(TransactionStatus.INSUFFICIENT_FUNDS);
            response = new TransactionResponse(
                    request.transactionId(), request.userId(), request.type(),
                    TransactionStatus.INSUFFICIENT_FUNDS, request.amount(), wallet.getBalance(),
                    "Insufficient funds: cannot debit " + request.amount());
        } else {
            // The JPQL UPDATE bypasses the persistence context, so refresh the
            // managed entity to report the true balance-after value.
            entityManager.refresh(wallet);
            record.setStatus(TransactionStatus.PROCESSED);
            response = new TransactionResponse(
                    request.transactionId(), request.userId(), request.type(),
                    TransactionStatus.PROCESSED, request.amount(), wallet.getBalance(),
                    "Transaction processed successfully");
        }

        record.setResponsePayload(writeJson(response));
        recordRepository.save(record);
        return response;
    }

    @Transactional(readOnly = true)
    public TransactionRecord findCompleted(UUID transactionId) {
        return recordRepository.findById(transactionId).orElse(null);
    }

    private Wallet createWalletSafely(UUID userId) {
        try {
            return walletRepository.saveAndFlush(new Wallet(userId, new BigDecimal("0.00")));
        } catch (DataIntegrityViolationException e) {
            // Concurrent wallet creation: clear the poisoned session and re-read.
            entityManager.clear();
            return walletRepository.findByUserId(userId).orElseThrow();
        }
    }

    private String writeJson(TransactionResponse response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize response for idempotency replay", e);
        }
    }
}
