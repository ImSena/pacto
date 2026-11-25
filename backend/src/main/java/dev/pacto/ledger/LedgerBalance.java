package dev.pacto.ledger;

public record LedgerBalance(
        long availableBalance,
        long reservedBalance) {
    public long totalBalance() {
        return Math.addExact(availableBalance, reservedBalance);
    }
}
