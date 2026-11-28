package dev.pacto.idempotency;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import dev.pacto.idempotency.repository.IdempotencyRecordRepository;
import dev.pacto.user.repository.UserRepository;

@Service
public class IdempotencyService {

    private static final Pattern VALID_HASH_PATTERN = Pattern.compile("[0-9a-f]{64}");

    private final IdempotencyRecordRepository repository;

    private final UserRepository userRepository;

    public IdempotencyService(
            IdempotencyRecordRepository repository,
            UserRepository userRepository) {

        this.repository = repository;
        this.userRepository = userRepository;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 15)
    public IdempotencyResponse execute(
            UUID actorId,
            IdempotencyOperation operation,
            String idempotencyKey,
            String payloadHash,
            Supplier<IdempotencyResponse> command) {

        Objects.requireNonNull(
                actorId, "Actor ID is required");

        Objects.requireNonNull(
                operation, "Operation is required");

        Objects.requireNonNull(
                command, "Command is required");

        IdempotencyKeyValidator.validate(idempotencyKey);

        validatePayloadHash(payloadHash);

        validateActor(actorId);

        repository.configureLockTimeout();

        boolean inserted = repository.tryInsert(
                actorId,
                operation,
                idempotencyKey,
                payloadHash);

        IdempotencyRecord record = repository.findForUpdate(
                actorId,
                operation,
                idempotencyKey)
                .orElseThrow(() -> new IllegalStateException(
                        "Idempotency record not found "
                                + "after acquisition"));

        if (!record.matchesPayload(payloadHash)) {

            throw new IdempotencyConflictException();
        }

        if (!inserted) {

            return replay(record);
        }

        if (record.state() != IdempotencyState.IN_PROGRESS) {

            throw new IllegalStateException(
                    "New idempotency record must be in progress");
        }

        IdempotencyResponse response = Objects.requireNonNull(
                command.get(),
                "Command response is required");

        repository.complete(
                record.id(),
                response.httpStatus(),
                response.responseBody());

        return response;
    }

    private IdempotencyResponse replay(
            IdempotencyRecord record) {

        if (!record.isCompleted()) {

            throw new IllegalStateException(
                    "Existing idempotency record "
                            + "has no completed response");
        }

        return new IdempotencyResponse(
                record.httpStatus(),
                record.responseBody());
    }

    private void validateActor(UUID actorId) {

        if (userRepository.findById(actorId).isEmpty()) {

            throw new UnknownIdempotencyActorException();
        }
    }

    private void validatePayloadHash(String payloadHash) {

        if (payloadHash == null
                || !VALID_HASH_PATTERN
                        .matcher(payloadHash)
                        .matches()) {

            throw new IllegalArgumentException(
                    "Payload hash must be a valid SHA-256 hex string");
        }
    }
}