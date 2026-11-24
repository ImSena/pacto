package dev.pacto.ledger.repository;

import dev.pacto.ledger.LedgerEntry;
import org.springframework.data.repository.Repository;

import java.util.UUID;

public interface LedgerEntryRepository
                extends Repository<LedgerEntry, UUID> {

        <L extends LedgerEntry> L save(L entry);

        <L extends LedgerEntry> Iterable<L> saveAll(
                        Iterable<L> entries);
}