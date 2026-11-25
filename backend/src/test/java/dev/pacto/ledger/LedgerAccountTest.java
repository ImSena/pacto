package dev.pacto.ledger;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class LedgerAccountTest {

    @Test
    void shouldCreateAvailableAccountWithZeroBalance() {

        UUID walletId = UUID.randomUUID();

        LedgerAccount account = LedgerAccount.available(walletId);

        assertEquals(0L, account.getBalance());
        assertEquals(walletId, account.getWalletId());

        assertEquals(
                LedgerAccountType.USER_AVAILABLE,
                account.getType());
    }

    @Test
    void shouldCreditAvailableAccount() {

        LedgerAccount account = LedgerAccount.available(UUID.randomUUID());

        account.applyDelta(100L);

        assertEquals(100L, account.getBalance());
    }

    @Test
    void shouldDebitAvailableAccount() {

        LedgerAccount account = LedgerAccount.available(UUID.randomUUID());

        account.applyDelta(100L);
        account.applyDelta(-30L);

        assertEquals(70L, account.getBalance());
    }

    @Test
    void shouldAllowDebitOfEntireBalance() {

        LedgerAccount account = LedgerAccount.available(UUID.randomUUID());

        account.applyDelta(100L);
        account.applyDelta(-100L);

        assertEquals(0L, account.getBalance());
    }

    @Test
    void shouldRejectInsufficientBalance() {

        LedgerAccount account = LedgerAccount.available(UUID.randomUUID());

        account.applyDelta(100L);

        InsufficientBalanceException exception = assertThrows(
                InsufficientBalanceException.class,
                () -> account.applyDelta(-150L));

        assertEquals(
                100L,
                exception.getCurrentBalance());

        assertEquals(
                -150L,
                exception.getAttemptedDelta());

        assertEquals(100L, account.getBalance());
    }

    @Test
    void shouldRejectZeroDelta() {

        LedgerAccount account = LedgerAccount.available(UUID.randomUUID());

        assertThrows(
                IllegalArgumentException.class,
                () -> account.applyDelta(0L));

        assertEquals(0L, account.getBalance());
    }

    @Test
    void shouldAllowNegativeSystemIssuanceBalance() {

        LedgerAccount account = LedgerAccount.systemIssuance();

        account.applyDelta(-200L);

        assertEquals(-200L, account.getBalance());
    }

    @Test
    void shouldRejectBalanceOverflow() {

        LedgerAccount account = LedgerAccount.available(UUID.randomUUID());

        account.applyDelta(Long.MAX_VALUE);

        assertThrows(
                ArithmeticException.class,
                () -> account.applyDelta(1L));

        assertEquals(
                Long.MAX_VALUE,
                account.getBalance());
    }

    @Test
    void shouldRejectSystemIssuanceUnderflow() {

        LedgerAccount account = LedgerAccount.systemIssuance();

        account.applyDelta(Long.MIN_VALUE);

        assertThrows(
                ArithmeticException.class,
                () -> account.applyDelta(-1L));

        assertEquals(
                Long.MIN_VALUE,
                account.getBalance());
    }
}