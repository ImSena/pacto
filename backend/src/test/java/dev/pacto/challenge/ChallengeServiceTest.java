package dev.pacto.challenge;

import dev.pacto.challenge.repository.ChallengeRepository;
import dev.pacto.ledger.LedgerService;
import dev.pacto.ledger.InsufficientBalanceException;
import dev.pacto.user.entity.User;
import dev.pacto.user.repository.UserRepository;
import dev.pacto.wallet.entity.Wallet;
import dev.pacto.wallet.repository.WalletRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChallengeServiceTest {

    @Mock
    private ChallengeRepository challengeRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private WalletRepository walletRepository;

    @Mock
    private LedgerService ledgerService;

    @InjectMocks
    private ChallengeService challengeService;

    private UUID creatorId;
    private UUID inviteeId;
    private UUID challengeId;

    private UUID creatorWalletId;
    private UUID inviteeWalletId;

    private Challenge challenge;

    @BeforeEach
    void setUp() {
        creatorId = UUID.randomUUID();
        inviteeId = UUID.randomUUID();
        challengeId = UUID.randomUUID();

        creatorWalletId = UUID.randomUUID();
        inviteeWalletId = UUID.randomUUID();

        challenge = new Challenge(
                creatorId,
                inviteeId,
                "Demo challenge",
                "Description",
                20L);

        ReflectionTestUtils.setField(
                challenge,
                "id",
                challengeId);
    }

    private void stubDemoUsers() {
        User bruno = mock(User.class);
        User lucas = mock(User.class);

        when(bruno.getId()).thenReturn(creatorId);
        when(lucas.getId()).thenReturn(inviteeId);

        when(userRepository.findByDisplayName("Bruno"))
                .thenReturn(Optional.of(bruno));

        when(userRepository.findByDisplayName("Lucas"))
                .thenReturn(Optional.of(lucas));
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

    @Test
    void shouldCreateChallengeWithoutReservingCredits() {
        stubDemoUsers();

        when(challengeRepository.save(any(Challenge.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Challenge result = challengeService.create(
                creatorId,
                inviteeId,
                "Demo challenge",
                "Description",
                20L);

        assertEquals(ChallengeStatus.OPEN, result.getStatus());
        assertEquals(creatorId, result.getCreatorId());
        assertEquals(inviteeId, result.getInviteeId());
        assertEquals(20L, result.getStakePerParticipant());

        verify(challengeRepository)
                .save(any(Challenge.class));

        verifyNoInteractions(
                walletRepository,
                ledgerService);
    }

    @Test
    void shouldRejectNonDemoParticipant() {
        stubDemoUsers();

        assertThrows(
                InvalidChallengeActorException.class,
                () -> challengeService.create(
                        creatorId,
                        UUID.randomUUID(),
                        "Invalid challenge",
                        null,
                        20L));

        verifyNoInteractions(
                challengeRepository,
                walletRepository,
                ledgerService);
    }

    @Test
    void shouldAcceptChallengeAndReserveBothContributions() {
        stubDemoUsers();
        stubWallets();

        when(challengeRepository.findById(challengeId))
                .thenReturn(Optional.of(challenge));

        Challenge result = challengeService.accept(
                challengeId,
                inviteeId);

        assertEquals(
                ChallengeStatus.ACCEPTED,
                result.getStatus());

        assertNotNull(result.getAcceptedAt());

        verify(ledgerService).reserve(
                any(UUID.class),
                eq(challengeId),
                eq(creatorWalletId),
                eq(inviteeWalletId),
                eq(20L));
    }

    @Test
    void shouldRejectAcceptanceByCreator() {
        when(challengeRepository.findById(challengeId))
                .thenReturn(Optional.of(challenge));

        assertThrows(
                InvalidChallengeActorException.class,
                () -> challengeService.accept(
                        challengeId,
                        creatorId));

        assertEquals(
                ChallengeStatus.OPEN,
                challenge.getStatus());

        verifyNoInteractions(
                userRepository,
                walletRepository,
                ledgerService);
    }

    @Test
    void shouldRejectAlreadyAcceptedChallenge() {
        challenge.accept(inviteeId);

        when(challengeRepository.findById(challengeId))
                .thenReturn(Optional.of(challenge));

        assertThrows(
                InvalidChallengeTransitionException.class,
                () -> challengeService.accept(
                        challengeId,
                        inviteeId));

        verifyNoInteractions(
                userRepository,
                walletRepository,
                ledgerService);
    }

    @Test
    void shouldRejectNonexistentChallenge() {
        when(challengeRepository.findById(challengeId))
                .thenReturn(Optional.empty());

        assertThrows(
                ChallengeNotFoundException.class,
                () -> challengeService.accept(
                        challengeId,
                        inviteeId));

        verifyNoInteractions(
                userRepository,
                walletRepository,
                ledgerService);
    }

    @Test
    void shouldPropagateInsufficientBalanceException() {
        stubDemoUsers();
        stubWallets();

        when(challengeRepository.findById(challengeId))
                .thenReturn(Optional.of(challenge));

        InsufficientBalanceException exception = new InsufficientBalanceException(
                UUID.randomUUID(),
                10L,
                -20L);

        when(ledgerService.reserve(
                any(UUID.class),
                eq(challengeId),
                eq(creatorWalletId),
                eq(inviteeWalletId),
                eq(20L))).thenThrow(exception);

        InsufficientBalanceException thrown = assertThrows(
                InsufficientBalanceException.class,
                () -> challengeService.accept(
                        challengeId,
                        inviteeId));

        assertSame(exception, thrown);
    }
}