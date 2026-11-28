package dev.pacto.idempotency;

import java.time.Instant;
import java.util.UUID;

public record IdempotencyRecord(
        UUID id,
        UUID actorId,
        IdempotencyOperation operation,
        String idempotencyKey,
        String payloadHash,
        IdempotencyState state,
        Integer httpStatus,
        String responseBody,
        Instant createdAt,
        Instant completedAt) {

    public boolean isCompleted() {

        return state == IdempotencyState.COMPLETED;
    }

    public boolean matchesPayload(String hash) {

        return payloadHash.equals(hash);
    }
}