package dev.pacto.challenge;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ChallengeTest {

    private UUID creatorId;
    private UUID inviteeId;
    private UUID outsiderId;

    private Challenge challenge;

    @BeforeEach
    void setUp() {
        creatorId = UUID.randomUUID();
        inviteeId = UUID.randomUUID();
        outsiderId = UUID.randomUUID();

        challenge = new Challenge(
                creatorId,
                inviteeId,
                "Demo challenge",
                "Challenge description",
                20L);
    }

    @Test
    void shouldCreateChallengeWithOpenStatus() {
        assertEquals(ChallengeStatus.OPEN, challenge.getStatus());
        assertEquals(creatorId, challenge.getCreatorId());
        assertEquals(inviteeId, challenge.getInviteeId());
        assertEquals(20L, challenge.getStakePerParticipant());

        assertNotNull(challenge.getCreatedAt());
        assertNull(challenge.getAcceptedAt());
        assertNull(challenge.getSettledAt());
    }

    @Test
    void shouldRejectChallengeAgainstSelf() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new Challenge(
                        creatorId,
                        creatorId,
                        "Invalid challenge",
                        null,
                        20L));
    }

    @Test
    void shouldRejectNonPositiveStake() {
        for (long stake : new long[] { 0L, -1L }) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new Challenge(
                            creatorId,
                            inviteeId,
                            "Invalid challenge",
                            null,
                            stake));
        }
    }

    @Test
    void shouldAcceptChallengeByInvitee() {
        challenge.accept(inviteeId);

        assertEquals(
                ChallengeStatus.ACCEPTED,
                challenge.getStatus());

        assertNotNull(challenge.getAcceptedAt());
    }

    @Test
    void shouldRejectAcceptanceByCreator() {
        assertThrows(
                InvalidChallengeActorException.class,
                () -> challenge.accept(creatorId));

        assertEquals(ChallengeStatus.OPEN, challenge.getStatus());
        assertNull(challenge.getAcceptedAt());
    }

    @Test
    void shouldCancelOpenChallengeByCreator() {
        challenge.cancel(creatorId);

        assertEquals(
                ChallengeStatus.CANCELLED,
                challenge.getStatus());

        assertNull(challenge.getAcceptedAt());
    }

    @Test
    void shouldRejectCancellationByInvitee() {
        assertThrows(
                InvalidChallengeActorException.class,
                () -> challenge.cancel(inviteeId));

        assertEquals(ChallengeStatus.OPEN, challenge.getStatus());
    }

    @Test
    void shouldRejectCancellationAfterAcceptance() {
        challenge.accept(inviteeId);

        assertThrows(
                InvalidChallengeTransitionException.class,
                () -> challenge.cancel(creatorId));

        assertEquals(
                ChallengeStatus.ACCEPTED,
                challenge.getStatus());
    }

    @Test
    void shouldAwaitResultAfterFirstConfirmation() {
        challenge.accept(inviteeId);

        challenge.recordFirstConfirmation(
                creatorId,
                creatorId);

        assertEquals(
                ChallengeStatus.AWAITING_RESULT,
                challenge.getStatus());

        assertNull(challenge.getSettledAt());
    }

    @Test
    void shouldRejectConfirmationBeforeAcceptance() {
        assertThrows(
                InvalidChallengeTransitionException.class,
                () -> challenge.recordFirstConfirmation(
                        creatorId,
                        creatorId));

        assertEquals(ChallengeStatus.OPEN, challenge.getStatus());
    }

    @Test
    void shouldRejectConfirmationByOutsider() {
        challenge.accept(inviteeId);

        assertThrows(
                InvalidChallengeActorException.class,
                () -> challenge.recordFirstConfirmation(
                        outsiderId,
                        creatorId));

        assertEquals(
                ChallengeStatus.ACCEPTED,
                challenge.getStatus());
    }

    @Test
    void shouldRejectInvalidWinner() {
        challenge.accept(inviteeId);

        assertThrows(
                InvalidChallengeWinnerException.class,
                () -> challenge.recordFirstConfirmation(
                        creatorId,
                        outsiderId));

        assertEquals(
                ChallengeStatus.ACCEPTED,
                challenge.getStatus());
    }

    @Test
    void shouldSettleWhenParticipantsAgree() {
        challenge.accept(inviteeId);

        challenge.recordFirstConfirmation(
                creatorId,
                creatorId);

        challenge.recordSecondConfirmation(
                inviteeId,
                creatorId,
                creatorId,
                creatorId);

        assertEquals(
                ChallengeStatus.SETTLED,
                challenge.getStatus());

        assertNotNull(challenge.getSettledAt());
    }

    @Test
    void shouldDisputeWhenParticipantsDisagree() {
        challenge.accept(inviteeId);

        challenge.recordFirstConfirmation(
                creatorId,
                creatorId);

        challenge.recordSecondConfirmation(
                inviteeId,
                inviteeId,
                creatorId,
                creatorId);

        assertEquals(
                ChallengeStatus.DISPUTED,
                challenge.getStatus());

        assertNull(challenge.getSettledAt());
    }

    @Test
    void shouldRejectRepeatedConfirmationBySameParticipant() {
        challenge.accept(inviteeId);

        challenge.recordFirstConfirmation(
                creatorId,
                creatorId);

        assertThrows(
                InvalidChallengeActorException.class,
                () -> challenge.recordSecondConfirmation(
                        creatorId,
                        creatorId,
                        creatorId,
                        creatorId));

        assertEquals(
                ChallengeStatus.AWAITING_RESULT,
                challenge.getStatus());

        assertNull(challenge.getSettledAt());
    }
}