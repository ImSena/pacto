package dev.pacto.ledger;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class LedgerPostingLineTest {

    @Test
    void shouldAcceptPositiveDelta() {

        UUID accountId = UUID.randomUUID();

        LedgerPostingLine posting = new LedgerPostingLine(accountId, 100L);

        assertEquals(accountId, posting.accountId());
        assertEquals(100L, posting.delta());
    }

    @Test
    void shouldAcceptNegativeDelta() {

        LedgerPostingLine posting = new LedgerPostingLine(
                UUID.randomUUID(),
                -100L);

        assertEquals(-100L, posting.delta());
    }

    @Test
    void shouldRejectZeroDelta() {

        assertThrows(
                IllegalArgumentException.class,
                () -> new LedgerPostingLine(
                        UUID.randomUUID(),
                        0L));
    }

    @Test
    void shouldRejectNullAccountId() {

        assertThrows(
                NullPointerException.class,
                () -> new LedgerPostingLine(
                        null,
                        100L));
    }
}