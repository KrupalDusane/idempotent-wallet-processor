package com.example.wallet.repo;

import com.example.wallet.model.TransactionRecord;
import com.example.wallet.model.TransactionStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface TransactionRecordRepository extends JpaRepository<TransactionRecord, UUID> {

    long countByStatus(TransactionStatus status);
}
