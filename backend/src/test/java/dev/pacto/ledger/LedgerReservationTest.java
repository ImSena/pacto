package dev.pacto.ledger;

import dev.pacto.ledger.repository.LedgerAccountRepository;
import dev.pacto.ledger.repository.LedgerTransactionRepository;
import dev.pacto.ledger.repository.LedgerEntryRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LedgerReservationTest {

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

    private UUID creatorWalletId;
    private UUID inviteeWalletId;
    private UUID challengeId;
    private UUID operationId;

    private LedgerAccount creatorAvailable;
    private LedgerAccount inviteeAvailable;

    @BeforeEach
    void setUp() {
        creatorWalletId = UUID.randomUUID();
        inviteeWalletId = UUID.randomUUID();
        challengeId = UUID.randomUUID();
        operationId = UUID.randomUUID();

        creatorAvailable = createAvailable(
                creatorWalletId, 100L);

        inviteeAvailable = createAvailable(
                inviteeWalletId, 100L);
    }

    private LedgerAccount createAvailable(
            UUID walletId,
            long balance) {
        LedgerAccount account = LedgerAccount.available(walletId);

        ReflectionTestUtils.setField(
                account,
                "id",
                UUID.randomUUID());

        if (balance > 0) {
            account.applyDelta(balance);
        }

        return account;
    }

    private void stubAvailableAccounts() {
        when(accountRepository.findByWalletIdAndType(
                creatorWalletId,
                LedgerAccountType.USER_AVAILABLE)).thenReturn(Optional.of(creatorAvailable));

        when(accountRepository.findByWalletIdAndType(
                inviteeWalletId,
                LedgerAccountType.USER_AVAILABLE)).thenReturn(Optional.of(inviteeAvailable));
    }

    @Test
    void shouldReserveCreditsForBothParticipants() {
        stubAvailableAccounts();

        List<LedgerAccount> escrows = new ArrayList<>();

        when(accountRepository.save(any(LedgerAccount.class)))
                .thenAnswer(invocation -> {
                    LedgerAccount account = invocation.getArgument(0);

                    ReflectionTestUtils.setField(
                            account,
                            "id",
                            UUID.randomUUID());

                    escrows.add(account);

                    return account;
                });

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

        UUID result = ledgerService.reserve(
                operationId,
                challengeId,
                creatorWalletId,
                inviteeWalletId,
                20L);

        assertEquals(transactionId, result);

        assertEquals(80L, creatorAvailable.getBalance());
        assertEquals(80L, inviteeAvailable.getBalance());

        assertEquals(2, escrows.size());

        LedgerAccount creatorEscrow = escrows.get(0);
        LedgerAccount inviteeEscrow = escrows.get(1);

        assertEquals(20L, creatorEscrow.getBalance());
        assertEquals(20L, inviteeEscrow.getBalance());

        assertEquals(
                creatorWalletId,
                creatorEscrow.getWalletId());

        assertEquals(
                inviteeWalletId,
                inviteeEscrow.getWalletId());

        assertEquals(
                challengeId,
                creatorEscrow.getChallengeId());

        assertEquals(
                challengeId,
                inviteeEscrow.getChallengeId());

        verify(accountRepository, times(2))
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
                    LedgerTransactionKind.RESERVE,
                    entry.getTransaction().getKind());

            assertEquals(
                    challengeId,
                    entry.getTransaction().getChallengeId());

            assertEquals(
                    operationId,
                    entry.getTransaction().getBusinessOperationId());

            total += entry.getDelta();
            entryCount++;
        }

        assertEquals(4, entryCount);

        assertEquals(
                Map.of(
                        creatorAvailable.getId(), -20L,
                        creatorEscrow.getId(), 20L,
                        inviteeAvailable.getId(), -20L,
                        inviteeEscrow.getId(), 20L),
                deltas);

        assertEquals(0L, total);
    }

    @Test
    void shouldRejectInsufficientCreatorBalance() {
        creatorAvailable.applyDelta(-90L);

        stubAvailableAccounts();

        assertThrows(
                InsufficientBalanceException.class,
                () -> ledgerService.reserve(
                        operationId,
                        challengeId,
                        creatorWalletId,
                        inviteeWalletId,
                        20L));

        assertEquals(10L, creatorAvailable.getBalance());
        assertEquals(100L, inviteeAvailable.getBalance());

        verify(accountRepository, never())
                .save(any(LedgerAccount.class));

        verifyNoInteractions(
                transactionRepository,
                entryRepository);
    }

    @Test
    void shouldRejectInsufficientInviteeBalance() {
        inviteeAvailable.applyDelta(-90L);

        stubAvailableAccounts();

        assertThrows(
                InsufficientBalanceException.class,
                () -> ledgerService.reserve(
                        operationId,
                        challengeId,
                        creatorWalletId,
                        inviteeWalletId,
                        20L));

        assertEquals(100L, creatorAvailable.getBalance());
        assertEquals(10L, inviteeAvailable.getBalance());

        verify(accountRepository, never())
                .save(any(LedgerAccount.class));

        verifyNoInteractions(
                transactionRepository,
                entryRepository);
    }

    @Test
    void shouldRejectReservationUsingSameWallet() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ledgerService.reserve(
                        operationId,
                        challengeId,
                        creatorWalletId,
                        creatorWalletId,
                        20L));

        verifyNoInteractions(
                accountRepository,
                transactionRepository,
                entryRepository);
    }

    @Test
    void shouldRejectNonPositiveStake() {
        for (long stake : new long[] { 0L, -1L }) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> ledgerService.reserve(
                            operationId,
                            challengeId,
                            creatorWalletId,
                            inviteeWalletId,
                            stake));
        }

        verifyNoInteractions(
                accountRepository,
                transactionRepository,
                entryRepository);
    }
}