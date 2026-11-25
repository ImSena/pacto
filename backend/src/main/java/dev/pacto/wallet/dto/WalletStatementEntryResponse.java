package dev.pacto.wallet.dto;

import java.time.Instant;
import java.util.UUID;

public record WalletStatementEntryResponse(
        UUID entryId,
        UUID transactionId,
        String kind,
        String accountType,
        long delta,
        Instant createdAt) {
}