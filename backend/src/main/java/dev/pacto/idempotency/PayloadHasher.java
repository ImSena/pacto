package dev.pacto.idempotency;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

public final class PayloadHasher {

    private static final String FORMAT_VERSION = "PACTO_COMMAND_V1";

    private static final String HASH_ALGORITHM = "SHA-256";

    private PayloadHasher() {
    }

    public static String hashCreate(
            UUID inviteeId,
            String title,
            String description,
            long stakePerParticipant) {

        Objects.requireNonNull(inviteeId, "Invitee ID is required");
        Objects.requireNonNull(title, "Title is required");

        return hash(
                IdempotencyOperation.CREATE_CHALLENGE,
                output -> {
                    writeUuid(output, inviteeId);
                    writeString(output, title.strip());
                    writeString(output, description);
                    output.writeLong(stakePerParticipant);
                });
    }

    public static String hashAccept(UUID challengeId) {

        Objects.requireNonNull(challengeId, "Challenge ID is required");

        return hash(
                IdempotencyOperation.ACCEPT_CHALLENGE,
                output -> writeUuid(output, challengeId));
    }

    public static String hashCancel(UUID challengeId) {

        Objects.requireNonNull(challengeId, "Challenge ID is required");

        return hash(
                IdempotencyOperation.CANCEL_CHALLENGE,
                output -> writeUuid(output, challengeId));
    }

    public static String hashConfirm(
            UUID challengeId,
            UUID winnerId) {

        Objects.requireNonNull(challengeId, "Challenge ID is required");
        Objects.requireNonNull(winnerId, "Winner ID is required");

        return hash(
                IdempotencyOperation.CONFIRM_RESULT,
                output -> {
                    writeUuid(output, challengeId);
                    writeUuid(output, winnerId);
                });
    }

    private static String hash(
            IdempotencyOperation operation,
            PayloadWriter writer) {

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        try (DataOutputStream output = new DataOutputStream(buffer)) {

            writeString(output, FORMAT_VERSION);
            writeString(output, operation.name());

            writer.write(output);

            output.flush();

            MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);

            byte[] hash = digest.digest(buffer.toByteArray());

            return HexFormat.of().formatHex(hash);

        } catch (IOException | NoSuchAlgorithmException exception) {

            throw new IllegalStateException(
                    "Failed to calculate payload hash",
                    exception);
        }
    }

    private static void writeUuid(
            DataOutputStream output,
            UUID value) throws IOException {

        writeString(output, value.toString());
    }

    private static void writeString(
            DataOutputStream output,
            String value) throws IOException {

        if (value == null) {
            output.writeInt(-1);
            return;
        }

        output.writeInt(value.length());

        output.writeChars(value);
    }

    @FunctionalInterface
    private interface PayloadWriter {

        void write(DataOutputStream output)
                throws IOException;
    }
}