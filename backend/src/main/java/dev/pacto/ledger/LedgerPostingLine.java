package dev.pacto.ledger;

import java.util.Objects;
import java.util.UUID;

public record LedgerPostingLine(
        UUID accountId,
        long delta) {

    public LedgerPostingLine {

        Objects.requireNonNull(
                accountId,
                "Account ID is required");

        if (delta == 0L) {
            throw new IllegalArgumentException(
                    "Posting delta must not be zero");
        }
    }
}