package dev.pacto.ledger.repository;

import dev.pacto.ledger.LedgerAccount;
import dev.pacto.ledger.LedgerAccountType;
import org.springframework.data.repository.Repository;

import java.util.Optional;
import java.util.UUID;

public interface LedgerAccountRepository
                extends Repository<LedgerAccount, UUID> {

        <L extends LedgerAccount> L save(L account);

        Optional<LedgerAccount> findById(UUID id);

        Optional<LedgerAccount> findByTypeAndWalletIdIsNull(
                        LedgerAccountType type);

        Optional<LedgerAccount> findByWalletIdAndType(
                        UUID walletId,
                        LedgerAccountType type);
}