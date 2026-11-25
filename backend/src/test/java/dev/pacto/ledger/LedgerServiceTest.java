package dev.pacto.ledger;

import dev.pacto.ledger.repository.LedgerAccountRepository;
import dev.pacto.ledger.repository.LedgerEntryRepository;
import dev.pacto.ledger.repository.LedgerTransactionRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LedgerServiceTest {

    private LedgerAccountRepository accountRepository;
    private LedgerTransactionRepository transactionRepository;
    private LedgerEntryRepository entryRepository;

    private LedgerService ledgerService;

    private UUID issuanceAccountId;
    private UUID availableAccountId;
    private UUID businessOperationId;

    @BeforeEach
    void setUp() {

        accountRepository = mock(LedgerAccountRepository.class);

        transactionRepository = mock(LedgerTransactionRepository.class);

        entryRepository = mock(LedgerEntryRepository.class);

        ledgerService = new LedgerService(
                accountRepository,
                transactionRepository,
                entryRepository);

        issuanceAccountId = UUID.randomUUID();
        availableAccountId = UUID.randomUUID();
        businessOperationId = UUID.randomUUID();
    }

    @Test
    void shouldPostValidInitialGrant() {

        LedgerAccount issuanceAccount = mockAccount(
                issuanceAccountId,
                LedgerAccountType.SYSTEM_ISSUANCE);

        LedgerAccount availableAccount = mockAccount(
                availableAccountId,
                LedgerAccountType.USER_AVAILABLE);

        UUID transactionId = UUID.randomUUID();

        LedgerTransaction transaction = mock(LedgerTransaction.class);

        when(transaction.getId())
                .thenReturn(transactionId);

        when(transactionRepository.save(
                any(LedgerTransaction.class)))
                .thenReturn(transaction);

        UUID result = ledgerService.postInitialGrant(
                businessOperationId,
                validPostings());

        assertEquals(transactionId, result);

        verify(issuanceAccount).applyDelta(-100L);

        verify(availableAccount).applyDelta(100L);

        verify(transactionRepository)
                .save(any(LedgerTransaction.class));

        verify(entryRepository).saveAll(
                argThat((Iterable<LedgerEntry> entries) -> {

                    var iterator = entries.iterator();

                    if (!iterator.hasNext()) {
                        return false;
                    }

                    LedgerEntry debit = iterator.next();

                    if (!iterator.hasNext()) {
                        return false;
                    }

                    LedgerEntry credit = iterator.next();

                    return !iterator.hasNext()
                            && debit.getDelta() == -100L
                            && credit.getDelta() == 100L
                            && debit.getAccount() == issuanceAccount
                            && credit.getAccount() == availableAccount
                            && debit.getTransaction() == transaction
                            && credit.getTransaction() == transaction;
                }));
    }

    @Test
    void shouldRejectUnbalancedTransaction() {

        List<LedgerPostingLine> postings = List.of(
                new LedgerPostingLine(
                        issuanceAccountId, -100L),
                new LedgerPostingLine(
                        availableAccountId, 50L));

        assertThrows(
                IllegalArgumentException.class,
                () -> ledgerService.postInitialGrant(
                        businessOperationId, postings));

        verifyNoInteractions(
                accountRepository,
                transactionRepository,
                entryRepository);
    }

    @Test
    void shouldRejectIncorrectInitialGrantAmount() {

        List<LedgerPostingLine> postings = List.of(
                new LedgerPostingLine(
                        issuanceAccountId, -500L),
                new LedgerPostingLine(
                        availableAccountId, 500L));

        assertThrows(
                IllegalArgumentException.class,
                () -> ledgerService.postInitialGrant(
                        businessOperationId, postings));

        verifyNoInteractions(
                accountRepository,
                transactionRepository,
                entryRepository);
    }

    @Test
    void shouldRejectDuplicateAccounts() {

        List<LedgerPostingLine> postings = List.of(
                new LedgerPostingLine(
                        issuanceAccountId, -100L),
                new LedgerPostingLine(
                        issuanceAccountId, 100L));

        assertThrows(
                IllegalArgumentException.class,
                () -> ledgerService.postInitialGrant(
                        businessOperationId, postings));

        verifyNoInteractions(
                accountRepository,
                transactionRepository,
                entryRepository);
    }

    @Test
    void shouldRejectMissingAccount() {

        when(accountRepository.findById(issuanceAccountId))
                .thenReturn(Optional.empty());

        assertThrows(
                IllegalArgumentException.class,
                () -> ledgerService.postInitialGrant(
                        businessOperationId,
                        validPostings()));

        verifyNoInteractions(
                transactionRepository,
                entryRepository);
    }

    @Test
    void shouldRejectInvalidAccountTypes() {

        LedgerAccount issuanceAccount = mockAccount(
                issuanceAccountId,
                LedgerAccountType.SYSTEM_ISSUANCE);

        LedgerAccount escrowAccount = mockAccount(
                availableAccountId,
                LedgerAccountType.CHALLENGE_ESCROW);

        assertThrows(
                IllegalArgumentException.class,
                () -> ledgerService.postInitialGrant(
                        businessOperationId,
                        validPostings()));

        verify(issuanceAccount, never())
                .applyDelta(anyLong());

        verify(escrowAccount, never())
                .applyDelta(anyLong());

        verifyNoInteractions(
                transactionRepository,
                entryRepository);
    }

    @Test
    void shouldRejectReversedInitialGrant() {

        mockAccount(
                issuanceAccountId,
                LedgerAccountType.SYSTEM_ISSUANCE);

        mockAccount(
                availableAccountId,
                LedgerAccountType.USER_AVAILABLE);

        List<LedgerPostingLine> postings = List.of(
                new LedgerPostingLine(
                        issuanceAccountId, 100L),
                new LedgerPostingLine(
                        availableAccountId, -100L));

        assertThrows(
                IllegalArgumentException.class,
                () -> ledgerService.postInitialGrant(
                        businessOperationId, postings));

        verifyNoInteractions(
                transactionRepository,
                entryRepository);
    }

    @Test
    void shouldRejectDuplicateInitialGrant() {

        LedgerAccount issuanceAccount = mockAccount(
                issuanceAccountId,
                LedgerAccountType.SYSTEM_ISSUANCE);

        LedgerAccount availableAccount = mockAccount(
                availableAccountId,
                LedgerAccountType.USER_AVAILABLE);

        when(entryRepository
                .existsByAccount_IdAndTransaction_Kind(
                        availableAccountId,
                        LedgerTransactionKind.INITIAL_GRANT))
                .thenReturn(true);

        assertThrows(
                InitialGrantAlreadyPerformedException.class,
                () -> ledgerService.postInitialGrant(
                        businessOperationId,
                        validPostings()));

        verifyNoInteractions(transactionRepository);

        verify(entryRepository, never())
                .saveAll(anyList());

        verify(issuanceAccount, never())
                .applyDelta(anyLong());

        verify(availableAccount, never())
                .applyDelta(anyLong());
    }

    @Test
    void shouldRejectNullBusinessOperationId() {

        assertThrows(
                NullPointerException.class,
                () -> ledgerService.postInitialGrant(
                        null,
                        validPostings()));

        verifyNoInteractions(
                accountRepository,
                transactionRepository,
                entryRepository);
    }

    @Test
    void shouldRejectSinglePosting() {

        List<LedgerPostingLine> postings = List.of(
                new LedgerPostingLine(
                        availableAccountId, 100L));

        assertThrows(
                IllegalArgumentException.class,
                () -> ledgerService.postInitialGrant(
                        businessOperationId, postings));

        verifyNoInteractions(
                accountRepository,
                transactionRepository,
                entryRepository);
    }

    private LedgerAccount mockAccount(
            UUID accountId,
            LedgerAccountType accountType) {

        LedgerAccount account = mock(LedgerAccount.class);

        when(account.getId())
                .thenReturn(accountId);

        when(account.getType())
                .thenReturn(accountType);

        when(accountRepository.findById(accountId))
                .thenReturn(Optional.of(account));

        return account;
    }

    private List<LedgerPostingLine> validPostings() {

        return List.of(
                new LedgerPostingLine(
                        issuanceAccountId, -100L),
                new LedgerPostingLine(
                        availableAccountId, 100L));
    }
}