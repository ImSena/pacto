package dev.pacto.ledger;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class LedgerReservePreparationTest {

    @Test
    void shouldCreateEmptyEscrowAccount() {
        UUID walletId = UUID.randomUUID();
        UUID challengeId = UUID.randomUUID();

        LedgerAccount account = LedgerAccount.escrow(
                walletId,
                challengeId);

        assertEquals(
                LedgerAccountType.CHALLENGE_ESCROW,
                account.getType());

        assertEquals(walletId, account.getWalletId());
        assertEquals(challengeId, account.getChallengeId());
        assertEquals(0L, account.getBalance());
        assertNotNull(account.getCreatedAt());
    }

    @Test
    void shouldRejectEscrowWithoutWallet() {
        assertThrows(
                NullPointerException.class,
                () -> LedgerAccount.escrow(
                        null,
                        UUID.randomUUID()));
    }

    @Test
    void shouldRejectEscrowWithoutChallenge() {
        assertThrows(
                NullPointerException.class,
                () -> LedgerAccount.escrow(
                        UUID.randomUUID(),
                        null));
    }

    @Test
    void shouldCreateReserveTransaction() {
        UUID operationId = UUID.randomUUID();
        UUID challengeId = UUID.randomUUID();

        LedgerTransaction transaction = LedgerTransaction.reserve(
                operationId,
                challengeId);

        assertEquals(
                LedgerTransactionKind.RESERVE,
                transaction.getKind());

        assertEquals(
                operationId,
                transaction.getBusinessOperationId());

        assertEquals(
                challengeId,
                transaction.getChallengeId());
    }

    @Test
    void shouldRejectReserveWithoutChallenge() {
        assertThrows(
                NullPointerException.class,
                () -> LedgerTransaction.reserve(
                        UUID.randomUUID(),
                        null));
    }
}