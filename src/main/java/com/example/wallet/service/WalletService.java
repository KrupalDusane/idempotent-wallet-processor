package com.example.wallet.service;

import com.example.wallet.dto.CreateWalletRequest;
import com.example.wallet.dto.WalletResponse;
import com.example.wallet.model.Wallet;
import com.example.wallet.repo.WalletRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Service
public class WalletService {

    private final WalletRepository walletRepository;

    public WalletService(WalletRepository walletRepository) {
        this.walletRepository = walletRepository;
    }

    @Transactional
    public WalletResponse createOrReset(CreateWalletRequest request) {
        Wallet wallet = walletRepository.findByUserId(request.userId())
                .map(existing -> {
                    existing.setBalance(request.balance());
                    return existing;
                })
                .orElseGet(() -> new Wallet(request.userId(), request.balance()));
        Wallet saved = walletRepository.save(wallet);
        return new WalletResponse(saved.getUserId(), saved.getBalance());
    }

    @Transactional(readOnly = true)
    public WalletResponse getByUserId(UUID userId) {
        return walletRepository.findByUserId(userId)
                .map(w -> new WalletResponse(w.getUserId(), w.getBalance()))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Wallet not found for user " + userId));
    }
}
