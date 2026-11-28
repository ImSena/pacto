package dev.pacto.ledger.repository;

import dev.pacto.ledger.LedgerAccount;
import dev.pacto.ledger.LedgerAccountType;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;
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

        @Query("""
                        SELECT
                            a.type AS accountType,
                            SUM(a.balance) AS total
                        FROM LedgerAccount a
                        WHERE a.walletId = :walletId
                        GROUP BY a.type
                        """)
        List<BalanceByType> findBalancesByWalletId(
                        @Param("walletId") UUID walletId);

        interface BalanceByType {

                LedgerAccountType getAccountType();

                Long getTotal();
        }

        Optional<LedgerAccount> findByChallengeIdAndWalletIdAndType(
                        UUID challengeId,
                        UUID walletId,
                        LedgerAccountType type);
}