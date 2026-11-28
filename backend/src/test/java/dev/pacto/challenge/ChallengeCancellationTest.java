package dev.pacto.challenge;

import dev.pacto.challenge.repository.ChallengeRepository;
import dev.pacto.challenge.repository.ConfirmationRepository;
import dev.pacto.ledger.LedgerService;
import dev.pacto.user.repository.UserRepository;
import dev.pacto.wallet.repository.WalletRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChallengeCancellationTest {

    @Mock
    private ChallengeRepository challengeRepository;

    @Mock
    private ConfirmationRepository confirmationRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private WalletRepository walletRepository;

    @Mock
    private LedgerService ledgerService;

    @InjectMocks
    private ChallengeService challengeService;

    private UUID challengeId;
    private UUID creatorId;
    private UUID inviteeId;

    private Challenge challenge;

    @BeforeEach
    void setUp() {
        challengeId = UUID.randomUUID();
        creatorId = UUID.randomUUID();
        inviteeId = UUID.randomUUID();

        challenge = new Challenge(
                creatorId,
                inviteeId,
                "Cancellation test",
                "Challenge cancellation",
                20L);

        ReflectionTestUtils.setField(
                challenge,
                "id",
                challengeId);
    }

    private void stubChallenge() {
        when(challengeRepository.findById(challengeId))
                .thenReturn(Optional.of(challenge));
    }

    private void verifyNoFinancialInteractions() {
        verifyNoInteractions(
                ledgerService,
                walletRepository,
                confirmationRepository,
                userRepository);
    }

    @Test
    void shouldCancelOpenChallenge() {
        stubChallenge();

        Challenge result = challengeService.cancel(
                challengeId,
                creatorId);

        assertEquals(
                ChallengeStatus.CANCELLED,
                result.getStatus());

        assertEquals(challengeId, result.getId());

        assertNull(result.getAcceptedAt());
        assertNull(result.getSettledAt());

        verifyNoFinancialInteractions();
    }

    @ParameterizedTest
    @ValueSource(booleans = { true, false })
    void shouldRejectCancellationByNonCreator(
            boolean inviteeAttempts) {
        stubChallenge();

        UUID actorId = inviteeAttempts
                ? inviteeId
                : UUID.randomUUID();

        assertThrows(
                InvalidChallengeActorException.class,
                () -> challengeService.cancel(
                        challengeId,
                        actorId));

        assertEquals(
                ChallengeStatus.OPEN,
                challenge.getStatus());

        verifyNoFinancialInteractions();
    }

    @ParameterizedTest
    @EnumSource(value = ChallengeStatus.class, names = {
            "ACCEPTED",
            "AWAITING_RESULT",
            "SETTLED",
            "DISPUTED",
            "CANCELLED"
    })
    void shouldRejectCancellationOutsideOpenStatus(
            ChallengeStatus status) {
        prepareStatus(status);

        stubChallenge();

        assertThrows(
                InvalidChallengeTransitionException.class,
                () -> challengeService.cancel(
                        challengeId,
                        creatorId));

        assertEquals(status, challenge.getStatus());

        verifyNoFinancialInteractions();
    }

    @Test
    void shouldRejectUnknownChallenge() {
        when(challengeRepository.findById(challengeId))
                .thenReturn(Optional.empty());

        assertThrows(
                ChallengeNotFoundException.class,
                () -> challengeService.cancel(
                        challengeId,
                        creatorId));

        verifyNoFinancialInteractions();
    }

    private void prepareStatus(ChallengeStatus status) {
        switch (status) {
            case OPEN -> {
                // No transition required.
            }

            case CANCELLED ->
                challenge.cancel(creatorId);

            case ACCEPTED ->
                challenge.accept(inviteeId);

            case AWAITING_RESULT -> {
                challenge.accept(inviteeId);

                challenge.recordFirstConfirmation(
                        creatorId,
                        creatorId);
            }

            case SETTLED -> {
                challenge.accept(inviteeId);

                challenge.recordFirstConfirmation(
                        creatorId,
                        creatorId);

                challenge.recordSecondConfirmation(
                        inviteeId,
                        creatorId,
                        creatorId,
                        creatorId);
            }

            case DISPUTED -> {
                challenge.accept(inviteeId);

                challenge.recordFirstConfirmation(
                        creatorId,
                        creatorId);

                challenge.recordSecondConfirmation(
                        inviteeId,
                        inviteeId,
                        creatorId,
                        creatorId);
            }
        }

        assertEquals(status, challenge.getStatus());
    }
}