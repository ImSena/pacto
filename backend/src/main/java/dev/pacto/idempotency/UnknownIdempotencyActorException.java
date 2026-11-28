package dev.pacto.idempotency;

public class UnknownIdempotencyActorException
        extends RuntimeException {

    public UnknownIdempotencyActorException() {

        super("Idempotency actor does not exist");
    }
}