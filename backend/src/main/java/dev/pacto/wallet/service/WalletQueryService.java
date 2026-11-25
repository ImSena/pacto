package dev.pacto.wallet.service;

import dev.pacto.ledger.LedgerBalance;
import dev.pacto.ledger.LedgerQueryService;
import dev.pacto.user.repository.UserRepository;
import dev.pacto.wallet.dto.WalletBalanceResponse;
import dev.pacto.wallet.dto.WalletStatementEntryResponse;
import dev.pacto.wallet.dto.WalletStatementResponse;
import dev.pacto.wallet.entity.Wallet;
import dev.pacto.wallet.repository.WalletRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class WalletQueryService {

    private final UserRepository userRepository;
    private final WalletRepository walletRepository;
    private final LedgerQueryService ledgerQueryService;

    public WalletQueryService(
            UserRepository userRepository,
            WalletRepository walletRepository,
            LedgerQueryService ledgerQueryService) {
        this.userRepository = userRepository;
        this.walletRepository = walletRepository;
        this.ledgerQueryService = ledgerQueryService;
    }

    public WalletBalanceResponse getBalance(UUID userId) {

        Wallet wallet = findWallet(userId);

        LedgerBalance balance = ledgerQueryService.getBalance(wallet.getId());

        return new WalletBalanceResponse(
                userId,
                wallet.getId(),
                balance.availableBalance(),
                balance.reservedBalance(),
                balance.totalBalance());
    }

    public WalletStatementResponse getStatement(
            UUID userId,
            int page,
            int size) {

        Wallet wallet = findWallet(userId);

        var statement = ledgerQueryService.getStatement(
                wallet.getId(),
                page,
                size);

        var content = statement.getContent()
                .stream()
                .map(entry -> new WalletStatementEntryResponse(
                        entry.entryId(),
                        entry.transactionId(),
                        entry.kind().name(),
                        entry.accountType().name(),
                        entry.delta(),
                        entry.createdAt()))
                .toList();

        return new WalletStatementResponse(
                content,
                statement.getNumber(),
                statement.getSize(),
                statement.getTotalElements(),
                statement.getTotalPages());
    }

    private Wallet findWallet(UUID userId) {

        Objects.requireNonNull(
                userId,
                "User ID is required");

        userRepository.findById(userId)
                .orElseThrow(
                        () -> new UserNotFoundException(userId));

        return walletRepository
                .findByUser_Id(userId)
                .orElseThrow(
                        () -> new IllegalStateException(
                                "Wallet not found for user: " + userId));
    }
}