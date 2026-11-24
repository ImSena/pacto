package dev.pacto.ledger.repository;

import dev.pacto.ledger.LedgerTransaction;
import org.springframework.data.repository.Repository;

import java.util.Optional;
import java.util.UUID;

public interface LedgerTransactionRepository
                extends Repository<LedgerTransaction, UUID> {

        <L extends LedgerTransaction> L save(L transaction);

        Optional<LedgerTransaction> findByBusinessOperationId(
                        UUID businessOperationId);

        boolean existsByBusinessOperationId(
                        UUID businessOperationId);
}