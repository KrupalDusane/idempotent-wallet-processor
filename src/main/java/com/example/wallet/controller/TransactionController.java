package com.example.wallet.controller;

import com.example.wallet.dto.ProcessResult;
import com.example.wallet.dto.ProcessTransactionRequest;
import com.example.wallet.dto.TransactionResponse;
import com.example.wallet.model.TransactionStatus;
import com.example.wallet.service.TransactionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/transactions")
public class TransactionController {

    private final TransactionService transactionService;

    public TransactionController(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    @PostMapping("/process")
    public ResponseEntity<TransactionResponse> process(@Valid @RequestBody ProcessTransactionRequest request) {
        ProcessResult result = transactionService.process(request);

        HttpStatus status;
        if (result.duplicate()) {
            // Idempotent replay: return the cached original response.
            status = HttpStatus.OK;
        } else if (result.response().status() == TransactionStatus.INSUFFICIENT_FUNDS) {
            status = HttpStatus.UNPROCESSABLE_ENTITY;
        } else {
            status = HttpStatus.CREATED;
        }
        return ResponseEntity.status(status).body(result.response());
    }
}
