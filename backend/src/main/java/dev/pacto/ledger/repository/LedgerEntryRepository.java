package dev.pacto.ledger.repository;

import dev.pacto.ledger.LedgerEntry;
import dev.pacto.ledger.LedgerAccountType;
import dev.pacto.ledger.LedgerTransactionKind;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

public interface LedgerEntryRepository
                extends Repository<LedgerEntry, UUID> {

        <L extends LedgerEntry> L save(L entry);

        <L extends LedgerEntry> Iterable<L> saveAll(
                        Iterable<L> entries);

        @Query(value = """
                        SELECT
                            e.id AS entryId,
                            t.id AS transactionId,
                            t.kind AS kind,
                            a.type AS accountType,
                            e.delta AS delta,
                            e.createdAt AS createdAt
                        FROM LedgerEntry e
                        JOIN e.transaction t
                        JOIN e.account a
                        WHERE a.walletId = :walletId
                        ORDER BY e.createdAt DESC, e.id ASC
                        """, countQuery = """
                        SELECT COUNT(e)
                        FROM LedgerEntry e
                        JOIN e.account a
                        WHERE a.walletId = :walletId
                        """)
        Page<StatementEntry> findStatementByWalletId(
                        @Param("walletId") UUID walletId,
                        Pageable pageable);

        interface StatementEntry {

                UUID getEntryId();

                UUID getTransactionId();

                LedgerTransactionKind getKind();

                LedgerAccountType getAccountType();

                long getDelta();

                Instant getCreatedAt();
        }

        boolean existsByAccount_IdAndTransaction_Kind(
                        UUID accountId,
                        LedgerTransactionKind kind);
}