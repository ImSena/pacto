package dev.pacto.ledger;

import dev.pacto.ledger.repository.LedgerAccountRepository;
import dev.pacto.ledger.repository.LedgerTransactionRepository;
import dev.pacto.ledger.repository.LedgerEntryRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LedgerSettlementTest {

    @Mock
    private LedgerAccountRepository accountRepository;

    @Mock
    private LedgerTransactionRepository transactionRepository;

    @Mock
    private LedgerEntryRepository entryRepository;

    @InjectMocks
    private LedgerService ledgerService;

    @Captor
    private ArgumentCaptor<Iterable<LedgerEntry>> entriesCaptor;

    private UUID challengeId;
    private UUID operationId;
    private UUID creatorWalletId;
    private UUID inviteeWalletId;

    private LedgerAccount creatorEscrow;
    private LedgerAccount inviteeEscrow;

    private LedgerAccount creatorAvailable;
    private LedgerAccount inviteeAvailable;

    @BeforeEach
    void setUp() {
        challengeId = UUID.randomUUID();
        operationId = UUID.randomUUID();

        creatorWalletId = UUID.randomUUID();
        inviteeWalletId = UUID.randomUUID();

        creatorEscrow = prepareAccount(
                LedgerAccount.escrow(
                        creatorWalletId,
                        challengeId),
                20L);

        inviteeEscrow = prepareAccount(
                LedgerAccount.escrow(
                        inviteeWalletId,
                        challengeId),
                20L);

        creatorAvailable = prepareAccount(
                LedgerAccount.available(creatorWalletId),
                80L);

        inviteeAvailable = prepareAccount(
                LedgerAccount.available(inviteeWalletId),
                80L);
    }

    private LedgerAccount prepareAccount(
            LedgerAccount account,
            long balance) {
        ReflectionTestUtils.setField(
                account,
                "id",
                UUID.randomUUID());

        if (balance > 0) {
            account.applyDelta(balance);
        }

        return account;
    }

    private void stubEscrowAccounts() {
        when(accountRepository
                .findByChallengeIdAndWalletIdAndType(
                        challengeId,
                        creatorWalletId,
                        LedgerAccountType.CHALLENGE_ESCROW))
                .thenReturn(Optional.of(creatorEscrow));

        when(accountRepository
                .findByChallengeIdAndWalletIdAndType(
                        challengeId,
                        inviteeWalletId,
                        LedgerAccountType.CHALLENGE_ESCROW))
                .thenReturn(Optional.of(inviteeEscrow));
    }

    private UUID stubTransactionSave() {
        UUID transactionId = UUID.randomUUID();

        when(transactionRepository.save(
                any(LedgerTransaction.class))).thenAnswer(invocation -> {
                    LedgerTransaction transaction = invocation.getArgument(0);

                    ReflectionTestUtils.setField(
                            transaction,
                            "id",
                            transactionId);

                    return transaction;
                });

        return transactionId;
    }

    @ParameterizedTest
    @ValueSource(booleans = { true, false })
    void shouldSettleCreditsToWinner(
            boolean creatorWins) {
        stubEscrowAccounts();

        UUID winnerWalletId = creatorWins
                ? creatorWalletId
                : inviteeWalletId;

        LedgerAccount winnerAvailable = creatorWins
                ? creatorAvailable
                : inviteeAvailable;

        when(accountRepository.findByWalletIdAndType(
                winnerWalletId,
                LedgerAccountType.USER_AVAILABLE)).thenReturn(Optional.of(winnerAvailable));

        UUID transactionId = stubTransactionSave();

        UUID result = ledgerService.settle(
                operationId,
                challengeId,
                creatorWalletId,
                inviteeWalletId,
                winnerWalletId,
                20L);

        assertEquals(transactionId, result);

        assertEquals(0L, creatorEscrow.getBalance());
        assertEquals(0L, inviteeEscrow.getBalance());

        assertEquals(
                creatorWins ? 120L : 80L,
                creatorAvailable.getBalance());

        assertEquals(
                creatorWins ? 80L : 120L,
                inviteeAvailable.getBalance());

        verify(accountRepository, never())
                .save(any(LedgerAccount.class));

        verify(transactionRepository)
                .save(any(LedgerTransaction.class));

        verify(entryRepository)
                .saveAll(entriesCaptor.capture());

        Map<UUID, Long> deltas = new HashMap<>();

        long total = 0L;
        int entryCount = 0;

        for (LedgerEntry entry : entriesCaptor.getValue()) {
            deltas.put(
                    entry.getAccount().getId(),
                    entry.getDelta());

            assertEquals(
                    LedgerTransactionKind.SETTLE,
                    entry.getTransaction().getKind());

            assertEquals(
                    challengeId,
                    entry.getTransaction().getChallengeId());

            assertEquals(
                    operationId,
                    entry.getTransaction()
                            .getBusinessOperationId());

            total += entry.getDelta();
            entryCount++;
        }

        assertEquals(3, entryCount);
        assertEquals(0L, total);

        assertEquals(
                Map.of(
                        creatorEscrow.getId(), -20L,
                        inviteeEscrow.getId(), -20L,
                        winnerAvailable.getId(), 40L),
                deltas);
    }

    @ParameterizedTest
    @ValueSource(longs = { 19L, 21L })
    void shouldRejectInvalidEscrowBalance(
            long invalidBalance) {
        inviteeEscrow.applyDelta(
                invalidBalance - 20L);

        stubEscrowAccounts();

        assertThrows(
                IllegalStateException.class,
                () -> ledgerService.settle(
                        operationId,
                        challengeId,
                        creatorWalletId,
                        inviteeWalletId,
                        creatorWalletId,
                        20L));

        assertEquals(20L, creatorEscrow.getBalance());

        assertEquals(
                invalidBalance,
                inviteeEscrow.getBalance());

        assertEquals(80L, creatorAvailable.getBalance());
        assertEquals(80L, inviteeAvailable.getBalance());

        verifyNoInteractions(
                transactionRepository,
                entryRepository);
    }

    @Test
    void shouldRejectWinnerOutsideChallenge() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ledgerService.settle(
                        operationId,
                        challengeId,
                        creatorWalletId,
                        inviteeWalletId,
                        UUID.randomUUID(),
                        20L));

        verifyNoInteractions(
                accountRepository,
                transactionRepository,
                entryRepository);
    }

    @Test
    void shouldRejectMissingEscrowAccount() {
        when(accountRepository
                .findByChallengeIdAndWalletIdAndType(
                        challengeId,
                        creatorWalletId,
                        LedgerAccountType.CHALLENGE_ESCROW))
                .thenReturn(Optional.empty());

        assertThrows(
                IllegalStateException.class,
                () -> ledgerService.settle(
                        operationId,
                        challengeId,
                        creatorWalletId,
                        inviteeWalletId,
                        creatorWalletId,
                        20L));

        verifyNoInteractions(
                transactionRepository,
                entryRepository);
    }

    @Test
    void shouldRejectAlreadySettledEscrow() {
        creatorEscrow.applyDelta(-20L);
        inviteeEscrow.applyDelta(-20L);

        stubEscrowAccounts();

        assertThrows(
                IllegalStateException.class,
                () -> ledgerService.settle(
                        operationId,
                        challengeId,
                        creatorWalletId,
                        inviteeWalletId,
                        creatorWalletId,
                        20L));

        assertEquals(0L, creatorEscrow.getBalance());
        assertEquals(0L, inviteeEscrow.getBalance());

        verifyNoInteractions(
                transactionRepository,
                entryRepository);
    }

    @Test
    void shouldRejectNonPositiveStake() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ledgerService.settle(
                        operationId,
                        challengeId,
                        creatorWalletId,
                        inviteeWalletId,
                        creatorWalletId,
                        0L));

        verifyNoInteractions(
                accountRepository,
                transactionRepository,
                entryRepository);
    }

    @Test
    void shouldRejectSameParticipantWallet() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ledgerService.settle(
                        operationId,
                        challengeId,
                        creatorWalletId,
                        creatorWalletId,
                        creatorWalletId,
                        20L));

        verifyNoInteractions(
                accountRepository,
                transactionRepository,
                entryRepository);
    }
}