package dev.pacto.ledger;

import java.util.UUID;

public class InsufficientBalanceException
        extends RuntimeException {

    private final UUID accountId;
    private final long currentBalance;
    private final long attemptedDelta;

    public InsufficientBalanceException(
            UUID accountId,
            long currentBalance,
            long attemptedDelta) {
        super("Insufficient balance");

        this.accountId = accountId;
        this.currentBalance = currentBalance;
        this.attemptedDelta = attemptedDelta;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public long getCurrentBalance() {
        return currentBalance;
    }

    public long getAttemptedDelta() {
        return attemptedDelta;
    }
}