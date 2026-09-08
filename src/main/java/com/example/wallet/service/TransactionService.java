package com.example.wallet.service;

import com.example.wallet.dto.ProcessResult;
import com.example.wallet.dto.ProcessTransactionRequest;
import com.example.wallet.dto.TransactionResponse;
import com.example.wallet.exception.ConflictException;
import com.example.wallet.exception.DuplicateTransactionException;
import com.example.wallet.model.TransactionRecord;
import com.example.wallet.model.TransactionStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.math.RoundingMode;
import java.util.UUID;

/**
 * Orchestrator (intentionally NOT @Transactional): it coordinates two separate
 * transactional steps (claim, execute) so that a duplicate detection never
 * rolls back the wallet work.
 */
@Service
public class TransactionService {

    private static final long DUPLICATE_WAIT_TIMEOUT_MS = 5_000;
    private static final long POLL_INTERVAL_MS = 25;

    private final TransactionExecutor executor;
    private final ObjectMapper objectMapper;

    public TransactionService(TransactionExecutor executor, ObjectMapper objectMapper) {
        this.executor = executor;
        this.objectMapper = objectMapper;
    }

    public ProcessResult process(ProcessTransactionRequest rawRequest) {
        ProcessTransactionRequest request = normalize(rawRequest);

        try {
            // Step 1: claim the idempotency key in its own transaction.
            TransactionRecord record = executor.claim(request);
            // Step 2: apply the wallet mutation in a second transaction.
            TransactionResponse response = executor.execute(record, request);
            return ProcessResult.original(response);
        } catch (DuplicateTransactionException duplicate) {
            // Another thread owns this transactionId. Wait for it to finish,
            // then replay the exact original response.
            TransactionRecord completed = awaitCompletion(request.transactionId());
            return ProcessResult.replayed(toResponse(completed));
        }
    }

    /**
     * Polls until the original request leaves the PENDING state. With a 50 ms
     * arrival window this resolves in a couple of polls; the timeout only
     * protects against a crashed original request.
     */
    private TransactionRecord awaitCompletion(UUID transactionId) {
        long deadline = System.currentTimeMillis() + DUPLICATE_WAIT_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            TransactionRecord record = executor.findCompleted(transactionId);
            if (record != null && record.getStatus() != TransactionStatus.PENDING) {
                return record;
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new ConflictException(
                "Original request for transactionId " + transactionId
                        + " did not complete within " + DUPLICATE_WAIT_TIMEOUT_MS + " ms; duplicate rejected");
    }

    private TransactionResponse toResponse(TransactionRecord record) {
        if (record.getStatus() == TransactionStatus.FAILED) {
            throw new ConflictException(
                    "Original request for transactionId " + record.getTransactionId() + " failed; duplicate rejected");
        }
        try {
            return objectMapper.readValue(record.getResponsePayload(), TransactionResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize cached idempotent response", e);
        }
    }

    private ProcessTransactionRequest normalize(ProcessTransactionRequest request) {
        return new ProcessTransactionRequest(
                request.transactionId(),
                request.userId(),
                request.amount().setScale(2, RoundingMode.HALF_UP),
                request.type());
    }
}
