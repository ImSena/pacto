package dev.pacto.challenge;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ConfirmationTest {

    @Test
    void shouldCreateValidConfirmation() {
        UUID challengeId = UUID.randomUUID();
        UUID participantId = UUID.randomUUID();
        UUID winnerId = UUID.randomUUID();

        Confirmation confirmation = new Confirmation(
                challengeId,
                participantId,
                winnerId);

        assertEquals(
                challengeId,
                confirmation.getChallengeId());

        assertEquals(
                participantId,
                confirmation.getParticipantId());

        assertEquals(
                winnerId,
                confirmation.getWinnerId());

        assertNotNull(confirmation.getCreatedAt());
    }

    @Test
    void shouldAllowParticipantToDeclareHimselfWinner() {
        UUID participantId = UUID.randomUUID();

        Confirmation confirmation = new Confirmation(
                UUID.randomUUID(),
                participantId,
                participantId);

        assertEquals(
                confirmation.getParticipantId(),
                confirmation.getWinnerId());
    }

    @Test
    void shouldRejectMissingChallengeId() {
        assertThrows(
                NullPointerException.class,
                () -> new Confirmation(
                        null,
                        UUID.randomUUID(),
                        UUID.randomUUID()));
    }

    @Test
    void shouldRejectMissingParticipantId() {
        assertThrows(
                NullPointerException.class,
                () -> new Confirmation(
                        UUID.randomUUID(),
                        null,
                        UUID.randomUUID()));
    }

    @Test
    void shouldRejectMissingWinnerId() {
        assertThrows(
                NullPointerException.class,
                () -> new Confirmation(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        null));
    }
}