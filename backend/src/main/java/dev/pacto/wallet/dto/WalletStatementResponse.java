package dev.pacto.wallet.dto;

import java.util.List;

public record WalletStatementResponse(
        List<WalletStatementEntryResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {
}