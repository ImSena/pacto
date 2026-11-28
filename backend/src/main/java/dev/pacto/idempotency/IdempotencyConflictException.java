package dev.pacto.idempotency;

public class IdempotencyConflictException
        extends RuntimeException {

    public IdempotencyConflictException() {

        super(
                "Idempotency-Key has already been used "
                        + "with a different payload");
    }
}