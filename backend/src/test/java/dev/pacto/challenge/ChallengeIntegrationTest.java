package dev.pacto.challenge;

import dev.pacto.demo.DemoDataService;
import dev.pacto.ledger.LedgerService;
import dev.pacto.ledger.InsufficientBalanceException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;

import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class ChallengeIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17")
            .withDatabaseName("pacto_challenge_test");

    @Autowired
    private ChallengeService challengeService;

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private DemoDataService demoDataService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID creatorId;
    private UUID inviteeId;

    @BeforeEach
    void setUp() {
        String database = jdbcTemplate.queryForObject(
                "SELECT current_database()",
                String.class);

        assertEquals("pacto_challenge_test", database);

        jdbcTemplate.execute("""
                TRUNCATE TABLE
                    idempotency_records,
                    confirmations,
                    ledger_entries,
                    ledger_transactions,
                    ledger_accounts,
                    challenges,
                    wallets,
                    users
                """);

        demoDataService.initialize();

        creatorId = findUserId("Bruno");
        inviteeId = findUserId("Lucas");
    }

    private UUID findUserId(String displayName) {
        return jdbcTemplate.queryForObject(
                """
                        SELECT id
                        FROM users
                        WHERE display_name = ?
                        """,
                UUID.class,
                displayName);
    }

    private UUID findWalletId(UUID userId) {
        return jdbcTemplate.queryForObject(
                """
                        SELECT id
                        FROM wallets
                        WHERE user_id = ?
                        """,
                UUID.class,
                userId);
    }

    private Challenge createChallenge(long stake) {
        return challengeService.create(
                creatorId,
                inviteeId,
                "Integration challenge",
                "Challenge integration test",
                stake);
    }

    private long number(String sql, Object... args) {
        return jdbcTemplate.queryForObject(
                sql,
                Long.class,
                args);
    }

    private long availableBalance(UUID userId) {
        return number(
                """
                        SELECT la.balance
                        FROM ledger_accounts la
                        JOIN wallets w ON w.id = la.wallet_id
                        WHERE w.user_id = ?
                          AND la.type = 'USER_AVAILABLE'
                        """,
                userId);
    }

    private long reservedBalance(UUID userId) {
        return number(
                """
                        SELECT COALESCE(SUM(la.balance), 0)
                        FROM ledger_accounts la
                        JOIN wallets w ON w.id = la.wallet_id
                        WHERE w.user_id = ?
                          AND la.type = 'CHALLENGE_ESCROW'
                        """,
                userId);
    }

    private String persistedStatus(UUID challengeId) {
        return jdbcTemplate.queryForObject(
                """
                        SELECT status
                        FROM challenges
                        WHERE id = ?
                        """,
                String.class,
                challengeId);
    }

    private void assertOpen(UUID challengeId) {
        assertEquals(
                "OPEN",
                persistedStatus(challengeId));

        assertEquals(
                1L,
                number("""
                        SELECT COUNT(*)
                        FROM challenges
                        WHERE id = ?
                          AND accepted_at IS NULL
                          AND settled_at IS NULL
                        """,
                        challengeId));
    }

    private void assertLedgerIntegrity() {
        assertEquals(
                0L,
                number("""
                        SELECT COUNT(*)
                        FROM (
                            SELECT lt.id
                            FROM ledger_transactions lt
                            LEFT JOIN ledger_entries le
                                ON le.transaction_id = lt.id
                            GROUP BY lt.id
                            HAVING COUNT(le.id) < 2
                                OR COALESCE(SUM(le.delta), 0) <> 0
                        ) AS invalid_transactions
                        """));

        assertEquals(
                0L,
                number("""
                        SELECT COUNT(*)
                        FROM (
                            SELECT la.id
                            FROM ledger_accounts la
                            LEFT JOIN ledger_entries le
                                ON le.account_id = la.id
                            GROUP BY la.id, la.balance
                            HAVING la.balance
                                <> COALESCE(SUM(le.delta), 0)
                        ) AS inconsistent_accounts
                        """));

        assertEquals(
                0L,
                number("""
                        SELECT COUNT(*)
                        FROM ledger_accounts
                        WHERE type <> 'SYSTEM_ISSUANCE'
                          AND balance < 0
                        """));

        assertEquals(
                200L,
                number("""
                        SELECT SUM(balance)
                        FROM ledger_accounts
                        WHERE type <> 'SYSTEM_ISSUANCE'
                        """));

        assertEquals(
                -200L,
                number("""
                        SELECT balance
                        FROM ledger_accounts
                        WHERE type = 'SYSTEM_ISSUANCE'
                        """));

        assertEquals(
                0L,
                number("""
                        SELECT SUM(balance)
                        FROM ledger_accounts
                        """));
    }

    private void assertInitialFinancialState() {
        assertEquals(100L, availableBalance(creatorId));
        assertEquals(100L, availableBalance(inviteeId));

        assertEquals(0L, reservedBalance(creatorId));
        assertEquals(0L, reservedBalance(inviteeId));

        assertEquals(
                3L,
                number("SELECT COUNT(*) FROM ledger_accounts"));

        assertEquals(
                2L,
                number("SELECT COUNT(*) FROM ledger_transactions"));

        assertEquals(
                4L,
                number("SELECT COUNT(*) FROM ledger_entries"));

        assertLedgerIntegrity();
    }

    private void assertReservedFinancialState(UUID challengeId) {
        assertEquals(80L, availableBalance(creatorId));
        assertEquals(80L, availableBalance(inviteeId));

        assertEquals(20L, reservedBalance(creatorId));
        assertEquals(20L, reservedBalance(inviteeId));

        assertEquals(
                5L,
                number("SELECT COUNT(*) FROM ledger_accounts"));

        assertEquals(
                3L,
                number("SELECT COUNT(*) FROM ledger_transactions"));

        assertEquals(
                8L,
                number("SELECT COUNT(*) FROM ledger_entries"));

        assertEquals(
                2L,
                number("""
                        SELECT COUNT(*)
                        FROM ledger_accounts
                        WHERE challenge_id = ?
                          AND type = 'CHALLENGE_ESCROW'
                          AND balance = 20
                        """,
                        challengeId));

        assertEquals(
                1L,
                number("""
                        SELECT COUNT(*)
                        FROM ledger_transactions
                        WHERE challenge_id = ?
                          AND kind = 'RESERVE'
                        """,
                        challengeId));

        assertEquals(
                4L,
                number("""
                        SELECT COUNT(*)
                        FROM ledger_entries le
                        JOIN ledger_transactions lt
                            ON lt.id = le.transaction_id
                        WHERE lt.challenge_id = ?
                          AND lt.kind = 'RESERVE'
                        """,
                        challengeId));

        for (UUID userId : new UUID[] { creatorId, inviteeId }) {
            assertEquals(
                    2L,
                    number("""
                            SELECT COUNT(*)
                            FROM ledger_entries le
                            JOIN ledger_transactions lt
                                ON lt.id = le.transaction_id
                            JOIN ledger_accounts la
                                ON la.id = le.account_id
                            JOIN wallets w
                                ON w.id = la.wallet_id
                            WHERE lt.challenge_id = ?
                              AND lt.kind = 'RESERVE'
                              AND w.user_id = ?
                              AND (
                                  (la.type = 'USER_AVAILABLE'
                                      AND le.delta = -20)
                                  OR
                                  (la.type = 'CHALLENGE_ESCROW'
                                      AND la.challenge_id = ?
                                      AND le.delta = 20)
                              )
                            """,
                            challengeId,
                            userId,
                            challengeId));
        }

        assertLedgerIntegrity();
    }

    @Test
    void shouldCreateChallengeWithoutMovingCredits() {
        Challenge challenge = createChallenge(20L);

        assertNotNull(challenge.getId());

        assertOpen(challenge.getId());

        assertEquals(
                1L,
                number("SELECT COUNT(*) FROM challenges"));

        assertInitialFinancialState();
    }

    @Test
    void shouldAcceptChallengeAndReserveBothContributions() {
        Challenge challenge = createChallenge(20L);

        Challenge accepted = challengeService.accept(
                challenge.getId(),
                inviteeId);

        assertEquals(
                ChallengeStatus.ACCEPTED,
                accepted.getStatus());

        assertEquals(
                "ACCEPTED",
                persistedStatus(challenge.getId()));

        assertEquals(
                1L,
                number("""
                        SELECT COUNT(*)
                        FROM challenges
                        WHERE id = ?
                          AND accepted_at IS NOT NULL
                          AND settled_at IS NULL
                        """,
                        challenge.getId()));

        assertReservedFinancialState(challenge.getId());
    }

    @Test
    void shouldRejectDuplicateAcceptanceWithoutMovingCredits() {
        Challenge challenge = createChallenge(20L);

        challengeService.accept(
                challenge.getId(),
                inviteeId);

        assertReservedFinancialState(challenge.getId());

        assertThrows(
                InvalidChallengeTransitionException.class,
                () -> challengeService.accept(
                        challenge.getId(),
                        inviteeId));

        assertEquals(
                "ACCEPTED",
                persistedStatus(challenge.getId()));

        assertReservedFinancialState(challenge.getId());
    }

    @Test
    void shouldRollbackAcceptanceWhenBalanceIsInsufficient() {
        Challenge challenge = createChallenge(101L);

        assertThrows(
                InsufficientBalanceException.class,
                () -> challengeService.accept(
                        challenge.getId(),
                        inviteeId));

        assertOpen(challenge.getId());

        assertInitialFinancialState();
    }

    @Test
    void shouldRollbackEntireAcceptanceWhenCommitFails() {
        Challenge challenge = createChallenge(20L);

        jdbcTemplate.execute("""
                CREATE FUNCTION pacto_test_fail_reservation()
                RETURNS TRIGGER
                LANGUAGE plpgsql
                AS $$
                BEGIN
                    IF NEW.kind = 'RESERVE' THEN

                        IF (
                            SELECT COUNT(*)
                            FROM challenges
                            WHERE id = NEW.challenge_id
                              AND status = 'ACCEPTED'
                              AND accepted_at IS NOT NULL
                        ) <> 1 THEN
                            RAISE EXCEPTION
                                'PACTO_TEST_INVALID_CHALLENGE_STATE';
                        END IF;

                        IF (
                            SELECT COUNT(*)
                            FROM ledger_entries
                            WHERE transaction_id = NEW.id
                        ) <> 4 THEN
                            RAISE EXCEPTION
                                'PACTO_TEST_INVALID_ENTRY_COUNT';
                        END IF;

                        IF (
                            SELECT COUNT(*)
                            FROM ledger_accounts
                            WHERE challenge_id = NEW.challenge_id
                              AND type = 'CHALLENGE_ESCROW'
                              AND balance = 20
                        ) <> 2 THEN
                            RAISE EXCEPTION
                                'PACTO_TEST_INVALID_ESCROW_STATE';
                        END IF;

                        IF (
                            SELECT COUNT(*)
                            FROM ledger_accounts
                            WHERE type = 'USER_AVAILABLE'
                              AND balance = 80
                        ) <> 2 THEN
                            RAISE EXCEPTION
                                'PACTO_TEST_INVALID_AVAILABLE_BALANCE';
                        END IF;

                        RAISE EXCEPTION
                            'PACTO_TEST_FORCED_RESERVATION_ROLLBACK';

                    END IF;

                    RETURN NEW;
                END;
                $$;
                """);

        try {
            jdbcTemplate.execute("""
                    CREATE CONSTRAINT TRIGGER
                        pacto_test_reservation_failure
                    AFTER INSERT ON ledger_transactions
                    DEFERRABLE INITIALLY DEFERRED
                    FOR EACH ROW
                    EXECUTE FUNCTION
                        pacto_test_fail_reservation()
                    """);

            RuntimeException exception = assertThrows(
                    RuntimeException.class,
                    () -> challengeService.accept(
                            challenge.getId(),
                            inviteeId));

            assertTrue(
                    containsExceptionMessage(
                            exception,
                            "PACTO_TEST_FORCED_RESERVATION_ROLLBACK"),
                    "Expected the intentional commit failure");

            assertOpen(challenge.getId());

            assertInitialFinancialState();

        } finally {
            jdbcTemplate.execute("""
                    DROP TRIGGER IF EXISTS
                        pacto_test_reservation_failure
                    ON ledger_transactions
                    """);

            jdbcTemplate.execute("""
                    DROP FUNCTION IF EXISTS
                        pacto_test_fail_reservation()
                    """);
        }
    }

    private boolean containsExceptionMessage(
            Throwable exception,
            String expectedMessage) {
        Throwable current = exception;

        while (current != null) {
            if (current.getMessage() != null
                    && current.getMessage()
                            .contains(expectedMessage)) {
                return true;
            }

            current = current.getCause();
        }

        return false;
    }

    @Test
    void shouldRejectReservationWithoutExistingTransaction() {
        Challenge challenge = createChallenge(20L);

        UUID creatorWalletId = findWalletId(creatorId);
        UUID inviteeWalletId = findWalletId(inviteeId);

        assertThrows(
                IllegalTransactionStateException.class,
                () -> ledgerService.reserve(
                        UUID.randomUUID(),
                        challenge.getId(),
                        creatorWalletId,
                        inviteeWalletId,
                        20L));

        assertOpen(challenge.getId());

        assertInitialFinancialState();
    }
}