package dev.pacto.idempotency;

public record IdempotencyResponse(
        int httpStatus,
        String responseBody) {

    public IdempotencyResponse {

        if (httpStatus != 200 && httpStatus != 201) {

            throw new IllegalArgumentException(
                    "Unsupported idempotency response status");
        }

        if (responseBody == null || responseBody.isBlank()) {

            throw new IllegalArgumentException(
                    "Response body is required");
        }
    }
}