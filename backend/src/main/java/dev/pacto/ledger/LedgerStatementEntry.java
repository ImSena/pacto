package dev.pacto.ledger;

import java.time.Instant;
import java.util.UUID;

public record LedgerStatementEntry(
        UUID entryId,
        UUID transactionId,
        LedgerTransactionKind kind,
        LedgerAccountType accountType,
        long delta,
        Instant createdAt) {

}
