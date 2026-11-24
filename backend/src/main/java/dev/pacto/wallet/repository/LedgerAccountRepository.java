package dev.pacto.wallet.repository;

import dev.pacto.wallet.entity.LedgerAccount;
import dev.pacto.wallet.entity.LedgerAccountType;
import org.springframework.data.repository.Repository;

import java.util.Optional;
import java.util.UUID;

public interface LedgerAccountRepository
        extends Repository<LedgerAccount, UUID> {

    <L extends LedgerAccount> L save(L account);

    Optional<LedgerAccount> findById(UUID id);

    Optional<LedgerAccount> findByTypeAndWalletIsNull(
            LedgerAccountType type);

    Optional<LedgerAccount> findByWallet_IdAndType(
            UUID walletId,
            LedgerAccountType type);
}