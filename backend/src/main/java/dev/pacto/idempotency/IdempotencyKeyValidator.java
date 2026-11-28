package dev.pacto.idempotency;

import java.util.regex.Pattern;

public final class IdempotencyKeyValidator {

    public static final int MAX_KEY_LENGTH = 255;

    private static final Pattern VALID_KEY_PATTERN = Pattern.compile("[!-~]+");

    private IdempotencyKeyValidator() {
    }

    public static void validate(String key) {

        if (key == null || key.isBlank()) {
            throw new InvalidIdempotencyKeyException(
                    "Idempotency-Key is required");
        }

        if (key.length() > MAX_KEY_LENGTH) {
            throw new InvalidIdempotencyKeyException(
                    "Idempotency-Key must not exceed 255 characters");
        }

        if (!VALID_KEY_PATTERN.matcher(key).matches()) {
            throw new InvalidIdempotencyKeyException(
                    "Idempotency-Key must contain only visible ASCII characters");
        }
    }
}