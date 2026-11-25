package dev.pacto.ledger;

import dev.pacto.ledger.repository.LedgerAccountRepository;
import dev.pacto.ledger.repository.LedgerEntryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.util.Objects;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class LedgerQueryService {

    private final LedgerAccountRepository accountRepository;
    private final LedgerEntryRepository entryRepository;

    public LedgerQueryService(
            LedgerAccountRepository accountRepository,
            LedgerEntryRepository entryRepository) {
        this.accountRepository = accountRepository;
        this.entryRepository = entryRepository;
    }

    public LedgerBalance getBalance(UUID walletId) {

        Objects.requireNonNull(
                walletId,
                "Wallet ID is required");

        var balances = accountRepository.findBalancesByWalletId(walletId);

        Long availableBalance = null;
        long reservedBalance = 0L;

        for (var balance : balances) {

            switch (balance.getAccountType()) {

                case USER_AVAILABLE ->
                    availableBalance = balance.getTotal();

                case CHALLENGE_ESCROW ->
                    reservedBalance = balance.getTotal();

                case SYSTEM_ISSUANCE ->
                    throw new IllegalStateException(
                            "System issuance account cannot belong to a wallet");
            }
        }

        if (availableBalance == null) {
            throw new IllegalStateException(
                    "Available account not found for wallet: " + walletId);
        }

        return new LedgerBalance(
                availableBalance,
                reservedBalance);
    }

    public Page<LedgerStatementEntry> getStatement(
            UUID walletId,
            int page,
            int size) {

        Objects.requireNonNull(
                walletId,
                "Wallet ID is required");

        if (page < 0) {
            throw new InvalidPaginationException(
                    "Page must not be negative");
        }

        if (size < 1 || size > 100) {
            throw new InvalidPaginationException(
                    "Page size must be between 1 and 100");
        }

        if ((long) page * size > Integer.MAX_VALUE) {
            throw new InvalidPaginationException(
                    "Page offset is too large");
        }

        return entryRepository
                .findStatementByWalletId(
                        walletId,
                        PageRequest.of(page, size))
                .map(entry -> new LedgerStatementEntry(
                        entry.getEntryId(),
                        entry.getTransactionId(),
                        entry.getKind(),
                        entry.getAccountType(),
                        entry.getDelta(),
                        entry.getCreatedAt()));
    }
}