package dev.pacto.idempotency;

public enum IdempotencyOperation {

    CREATE_CHALLENGE,
    CANCEL_CHALLENGE,
    ACCEPT_CHALLENGE,
    CONFIRM_RESULT
}