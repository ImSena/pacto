package dev.pacto.idempotency;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IdempotencyKeyValidatorTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "a",
            "accept-001",
            "ABC-123",
            "abc-123",
            "pacto:create:001",
            "550e8400-e29b-41d4-a716-446655440000"
    })
    void shouldAcceptValidKeys(String key) {

        assertDoesNotThrow(
                () -> IdempotencyKeyValidator.validate(key));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            " ",
            "   ",
            "\t",
            "\n"
    })
    void shouldRejectMissingOrBlankKeys(String key) {

        InvalidIdempotencyKeyException exception = assertThrows(
                InvalidIdempotencyKeyException.class,
                () -> IdempotencyKeyValidator.validate(key));

        assertEquals(
                "Idempotency-Key is required",
                exception.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "accept 001",
            " accept-001",
            "accept-001 ",
            "accept\t001",
            "accept\n001",
            "accept\r001",
            "aceitação",
            "key\u0000value",
            "key\u007Fvalue"
    })
    void shouldRejectInvalidCharacters(String key) {

        InvalidIdempotencyKeyException exception = assertThrows(
                InvalidIdempotencyKeyException.class,
                () -> IdempotencyKeyValidator.validate(key));

        assertEquals(
                "Idempotency-Key must contain only visible ASCII characters",
                exception.getMessage());
    }

    @Test
    void shouldAcceptMaximumLengthKey() {

        String key = "a".repeat(255);

        assertDoesNotThrow(
                () -> IdempotencyKeyValidator.validate(key));
    }

    @Test
    void shouldRejectKeyExceedingMaximumLength() {

        String key = "a".repeat(256);

        InvalidIdempotencyKeyException exception = assertThrows(
                InvalidIdempotencyKeyException.class,
                () -> IdempotencyKeyValidator.validate(key));

        assertEquals(
                "Idempotency-Key must not exceed 255 characters",
                exception.getMessage());
    }
}