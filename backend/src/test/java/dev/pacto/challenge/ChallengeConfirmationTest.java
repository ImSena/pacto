package dev.pacto.challenge;

import dev.pacto.challenge.repository.ChallengeRepository;
import dev.pacto.challenge.repository.ConfirmationRepository;
import dev.pacto.ledger.LedgerService;
import dev.pacto.user.repository.UserRepository;
import dev.pacto.wallet.entity.Wallet;
import dev.pacto.wallet.repository.WalletRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChallengeConfirmationTest {

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

    private UUID creatorWalletId;
    private UUID inviteeWalletId;

    private Challenge challenge;

    @BeforeEach
    void setUp() {
        challengeId = UUID.randomUUID();
        creatorId = UUID.randomUUID();
        inviteeId = UUID.randomUUID();

        creatorWalletId = UUID.randomUUID();
        inviteeWalletId = UUID.randomUUID();

        challenge = createOpenChallenge();
        challenge.accept(inviteeId);
    }

    private Challenge createOpenChallenge() {
        Challenge result = new Challenge(
                creatorId,
                inviteeId,
                "Confirmation test",
                "Challenge resolution",
                20L);

        ReflectionTestUtils.setField(
                result,
                "id",
                challengeId);

        return result;
    }

    private void stubChallenge() {
        when(challengeRepository.findById(challengeId))
                .thenReturn(Optional.of(challenge));
    }

    private void stubConfirmations(
            List<Confirmation> confirmations) {
        when(confirmationRepository
                .findByChallengeId(challengeId))
                .thenReturn(confirmations);
    }

    private void stubWallets() {
        Wallet creatorWallet = mock(Wallet.class);
        Wallet inviteeWallet = mock(Wallet.class);

        when(creatorWallet.getId())
                .thenReturn(creatorWalletId);

        when(inviteeWallet.getId())
                .thenReturn(inviteeWalletId);

        when(walletRepository.findByUser_Id(creatorId))
                .thenReturn(Optional.of(creatorWallet));

        when(walletRepository.findByUser_Id(inviteeId))
                .thenReturn(Optional.of(inviteeWallet));
    }

    private Confirmation prepareFirstConfirmation(
            UUID participantId,
            UUID winnerId) {
        challenge.recordFirstConfirmation(
                participantId,
                winnerId);

        return new Confirmation(
                challengeId,
                participantId,
                winnerId);
    }

    private void assertSavedConfirmation(
            UUID participantId,
            UUID winnerId) {
        ArgumentCaptor<Confirmation> captor = ArgumentCaptor.forClass(Confirmation.class);

        verify(confirmationRepository)
                .save(captor.capture());

        Confirmation saved = captor.getValue();

        assertEquals(challengeId, saved.getChallengeId());
        assertEquals(participantId, saved.getParticipantId());
        assertEquals(winnerId, saved.getWinnerId());
    }

    @ParameterizedTest
    @ValueSource(booleans = { true, false })
    void shouldRegisterFirstConfirmationWithoutSettlement(
            boolean creatorConfirms) {
        stubChallenge();
        stubConfirmations(List.of());

        UUID actorId = creatorConfirms
                ? creatorId
                : inviteeId;

        Challenge result = challengeService.confirm(
                challengeId,
                actorId,
                creatorId);

        assertEquals(
                ChallengeStatus.AWAITING_RESULT,
                result.getStatus());

        assertNull(result.getSettledAt());

        assertSavedConfirmation(actorId, creatorId);

        verifyNoInteractions(
                walletRepository,
                ledgerService);
    }

    @ParameterizedTest
    @ValueSource(booleans = { true, false })
    void shouldSettleWhenParticipantsAgree(
            boolean creatorWins) {
        UUID winnerId = creatorWins
                ? creatorId
                : inviteeId;

        UUID winnerWalletId = creatorWins
                ? creatorWalletId
                : inviteeWalletId;

        Confirmation first = prepareFirstConfirmation(
                creatorId,
                winnerId);

        stubChallenge();
        stubConfirmations(List.of(first));
        stubWallets();

        Challenge result = challengeService.confirm(
                challengeId,
                inviteeId,
                winnerId);

        assertEquals(
                ChallengeStatus.SETTLED,
                result.getStatus());

        assertNotNull(result.getSettledAt());

        assertSavedConfirmation(
                inviteeId,
                winnerId);

        verify(ledgerService).settle(
                any(UUID.class),
                eq(challengeId),
                eq(creatorWalletId),
                eq(inviteeWalletId),
                eq(winnerWalletId),
                eq(20L));
    }

    @Test
    void shouldDisputeWhenParticipantsDisagree() {
        Confirmation first = prepareFirstConfirmation(
                creatorId,
                creatorId);

        stubChallenge();
        stubConfirmations(List.of(first));

        Challenge result = challengeService.confirm(
                challengeId,
                inviteeId,
                inviteeId);

        assertEquals(
                ChallengeStatus.DISPUTED,
                result.getStatus());

        assertNull(result.getSettledAt());

        assertSavedConfirmation(
                inviteeId,
                inviteeId);

        verifyNoInteractions(
                walletRepository,
                ledgerService);
    }

    @Test
    void shouldRejectDuplicateConfirmation() {
        Confirmation first = prepareFirstConfirmation(
                creatorId,
                creatorId);

        stubChallenge();
        stubConfirmations(List.of(first));

        assertThrows(
                DuplicateConfirmationException.class,
                () -> challengeService.confirm(
                        challengeId,
                        creatorId,
                        inviteeId));

        assertEquals(
                ChallengeStatus.AWAITING_RESULT,
                challenge.getStatus());

        verify(confirmationRepository, never())
                .save(any(Confirmation.class));

        verifyNoInteractions(
                walletRepository,
                ledgerService);
    }

    @Test
    void shouldRejectConfirmationFromOutsider() {
        stubChallenge();

        assertThrows(
                InvalidChallengeActorException.class,
                () -> challengeService.confirm(
                        challengeId,
                        UUID.randomUUID(),
                        creatorId));

        assertEquals(
                ChallengeStatus.ACCEPTED,
                challenge.getStatus());

        verifyNoInteractions(
                confirmationRepository,
                walletRepository,
                ledgerService);
    }

    @Test
    void shouldRejectWinnerOutsideChallenge() {
        stubChallenge();

        assertThrows(
                InvalidChallengeWinnerException.class,
                () -> challengeService.confirm(
                        challengeId,
                        creatorId,
                        UUID.randomUUID()));

        assertEquals(
                ChallengeStatus.ACCEPTED,
                challenge.getStatus());

        verifyNoInteractions(
                confirmationRepository,
                walletRepository,
                ledgerService);
    }

    @ParameterizedTest
    @ValueSource(booleans = { true, false })
    void shouldRejectConfirmationBeforeAcceptance(
            boolean cancelled) {
        challenge = createOpenChallenge();

        if (cancelled) {
            challenge.cancel(creatorId);
        }

        stubChallenge();
        stubConfirmations(List.of());

        assertThrows(
                InvalidChallengeTransitionException.class,
                () -> challengeService.confirm(
                        challengeId,
                        creatorId,
                        creatorId));

        assertEquals(
                cancelled
                        ? ChallengeStatus.CANCELLED
                        : ChallengeStatus.OPEN,
                challenge.getStatus());

        verify(confirmationRepository, never())
                .save(any(Confirmation.class));

        verifyNoInteractions(
                walletRepository,
                ledgerService);
    }

    @Test
    void shouldRejectMissingFirstConfirmation() {
        challenge.recordFirstConfirmation(
                creatorId,
                creatorId);

        stubChallenge();
        stubConfirmations(List.of());

        assertThrows(
                IllegalStateException.class,
                () -> challengeService.confirm(
                        challengeId,
                        inviteeId,
                        creatorId));

        verify(confirmationRepository, never())
                .save(any(Confirmation.class));

        verifyNoInteractions(
                walletRepository,
                ledgerService);
    }

    @Test
    void shouldPropagateSettlementFailure() {
        Confirmation first = prepareFirstConfirmation(
                creatorId,
                creatorId);

        stubChallenge();
        stubConfirmations(List.of(first));
        stubWallets();

        IllegalStateException failure = new IllegalStateException(
                "Invalid escrow balance");

        when(ledgerService.settle(
                any(UUID.class),
                eq(challengeId),
                eq(creatorWalletId),
                eq(inviteeWalletId),
                eq(creatorWalletId),
                eq(20L))).thenThrow(failure);

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> challengeService.confirm(
                        challengeId,
                        inviteeId,
                        creatorId));

        assertSame(failure, thrown);

        assertSavedConfirmation(
                inviteeId,
                creatorId);
    }

    @Test
    void shouldRejectUnknownChallenge() {
        when(challengeRepository.findById(challengeId))
                .thenReturn(Optional.empty());

        assertThrows(
                ChallengeNotFoundException.class,
                () -> challengeService.confirm(
                        challengeId,
                        creatorId,
                        creatorId));

        verifyNoInteractions(
                confirmationRepository,
                walletRepository,
                ledgerService);
    }
}