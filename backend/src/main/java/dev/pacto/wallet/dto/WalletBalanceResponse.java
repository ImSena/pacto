package dev.pacto.wallet.dto;

import java.util.UUID;

public record WalletBalanceResponse(
        UUID userId,
        UUID walletId,
        long availableBalance,
        long reservedBalance,
        long totalBalance) {
}
