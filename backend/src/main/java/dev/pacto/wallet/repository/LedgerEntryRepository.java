package dev.pacto.wallet.repository;

import dev.pacto.wallet.entity.LedgerEntry;
import org.springframework.data.repository.Repository;

import java.util.UUID;

public interface LedgerEntryRepository
        extends Repository<LedgerEntry, UUID> {

    <L extends LedgerEntry> L save(L entry);

    <L extends LedgerEntry> Iterable<L> saveAll(
            Iterable<L> entries);
}