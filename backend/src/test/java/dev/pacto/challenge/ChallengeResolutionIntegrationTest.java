package dev.pacto.challenge;

import dev.pacto.demo.DemoDataService;
import dev.pacto.ledger.LedgerService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
class ChallengeResolutionIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17")
            .withDatabaseName("pacto_resolution_test");

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
        assertEquals(
                "pacto_resolution_test",
                jdbcTemplate.queryForObject(
                        "SELECT current_database()",
                        String.class));

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

        assertBalances(100L, 100L, 0L, 0L);
    }

    private UUID findUserId(String name) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE display_name = ?",
                UUID.class,
                name);
    }

    private UUID findWalletId(UUID userId) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM wallets WHERE user_id = ?",
                UUID.class,
                userId);
    }

    private UUID createAcceptedChallenge(long stake) {
        Challenge challenge = challengeService.create(
                creatorId,
                inviteeId,
                "Resolution integration test",
                "PostgreSQL integration test",
                stake);

        challengeService.accept(
                challenge.getId(),
                inviteeId);

        return challenge.getId();
    }

    private long number(String sql, Object... args) {
        return jdbcTemplate.queryForObject(
                sql,
                Long.class,
                args);
    }

    private String persistedStatus(UUID challengeId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM challenges WHERE id = ?",
                String.class,
                challengeId);
    }

    private long confirmationCount(UUID challengeId) {
        return number("""
                SELECT COUNT(*)
                FROM confirmations
                WHERE challenge_id = ?
                """, challengeId);
    }

    private long settlementCount(UUID challengeId) {
        return number("""
                SELECT COUNT(*)
                FROM ledger_transactions
                WHERE challenge_id = ?
                  AND kind = 'SETTLE'
                """, challengeId);
    }

    private long balance(UUID userId, String accountType) {
        return number("""
                SELECT COALESCE(SUM(la.balance), 0)
                FROM ledger_accounts la
                JOIN wallets w ON w.id = la.wallet_id
                WHERE w.user_id = ?
                  AND la.type = ?
                """, userId, accountType);
    }

    private long escrowBalance(
            UUID challengeId,
            UUID userId) {
        return number("""
                SELECT balance
                FROM ledger_accounts
                WHERE challenge_id = ?
                  AND wallet_id = ?
                  AND type = 'CHALLENGE_ESCROW'
                """, challengeId, findWalletId(userId));
    }

    private void assertBalances(
            long creatorAvailable,
            long inviteeAvailable,
            long creatorReserved,
            long inviteeReserved) {
        assertEquals(
                creatorAvailable,
                balance(creatorId, "USER_AVAILABLE"));

        assertEquals(
                inviteeAvailable,
                balance(inviteeId, "USER_AVAILABLE"));

        assertEquals(
                creatorReserved,
                balance(creatorId, "CHALLENGE_ESCROW"));

        assertEquals(
                inviteeReserved,
                balance(inviteeId, "CHALLENGE_ESCROW"));
    }

    private void assertLedgerIntegrity() {

        assertEquals(0L, number("""
                SELECT COUNT(*)
                FROM (
                    SELECT lt.id
                    FROM ledger_transactions lt
                    LEFT JOIN ledger_entries le
                        ON le.transaction_id = lt.id
                    GROUP BY lt.id
                    HAVING COUNT(le.id) < 2
                        OR COALESCE(SUM(le.delta), 0) <> 0
                ) invalid_transactions
                """));

        assertEquals(0L, number("""
                SELECT COUNT(*)
                FROM (
                    SELECT la.id
                    FROM ledger_accounts la
                    LEFT JOIN ledger_entries le
                        ON le.account_id = la.id
                    GROUP BY la.id, la.balance
                    HAVING la.balance <>
                        COALESCE(SUM(le.delta), 0)
                ) invalid_accounts
                """));

        assertEquals(0L, number("""
                SELECT COUNT(*)
                FROM ledger_accounts
                WHERE type <> 'SYSTEM_ISSUANCE'
                  AND balance < 0
                """));

        assertEquals(200L, number("""
                SELECT COALESCE(SUM(balance), 0)
                FROM ledger_accounts
                WHERE type IN (
                    'USER_AVAILABLE',
                    'CHALLENGE_ESCROW'
                )
                """));

        assertEquals(0L, number("""
                SELECT COALESCE(SUM(balance), 0)
                FROM ledger_accounts
                """));
    }

    @Test
    void shouldPersistFirstConfirmationWithoutSettlement() {
        UUID challengeId = createAcceptedChallenge(20L);

        Challenge result = challengeService.confirm(
                challengeId,
                creatorId,
                creatorId);

        assertEquals(
                ChallengeStatus.AWAITING_RESULT,
                result.getStatus());

        assertEquals(
                "AWAITING_RESULT",
                persistedStatus(challengeId));

        assertEquals(1L, confirmationCount(challengeId));
        assertEquals(0L, settlementCount(challengeId));

        UUID persistedWinner = jdbcTemplate.queryForObject(
                """
                        SELECT winner_id
                        FROM confirmations
                        WHERE challenge_id = ?
                          AND participant_id = ?
                        """,
                UUID.class,
                challengeId,
                creatorId);

        assertEquals(creatorId, persistedWinner);

        assertBalances(80L, 80L, 20L, 20L);

        assertLedgerIntegrity();
    }

    @ParameterizedTest
    @ValueSource(booleans = { true, false })
    void shouldSettleWhenBothParticipantsAgree(
            boolean creatorWins) {
        UUID challengeId = createAcceptedChallenge(20L);

        UUID winnerId = creatorWins
                ? creatorId
                : inviteeId;

        challengeService.confirm(
                challengeId,
                creatorId,
                winnerId);

        Challenge result = challengeService.confirm(
                challengeId,
                inviteeId,
                winnerId);

        assertEquals(
                ChallengeStatus.SETTLED,
                result.getStatus());

        assertNotNull(result.getSettledAt());

        assertEquals(
                "SETTLED",
                persistedStatus(challengeId));

        assertEquals(2L, confirmationCount(challengeId));
        assertEquals(1L, settlementCount(challengeId));

        assertEquals(2L, number("""
                SELECT COUNT(*)
                FROM confirmations
                WHERE challenge_id = ?
                  AND winner_id = ?
                """, challengeId, winnerId));

        assertEquals(1L, number("""
                SELECT COUNT(*)
                FROM challenges
                WHERE id = ?
                  AND settled_at IS NOT NULL
                """, challengeId));

        assertBalances(
                creatorWins ? 120L : 80L,
                creatorWins ? 80L : 120L,
                0L,
                0L);

        assertEquals(3L, number("""
                SELECT COUNT(*)
                FROM ledger_entries le
                JOIN ledger_transactions lt
                    ON lt.id = le.transaction_id
                WHERE lt.challenge_id = ?
                  AND lt.kind = 'SETTLE'
                """, challengeId));

        assertEquals(40L, number("""
                SELECT le.delta
                FROM ledger_entries le
                JOIN ledger_transactions lt
                    ON lt.id = le.transaction_id
                JOIN ledger_accounts la
                    ON la.id = le.account_id
                WHERE lt.challenge_id = ?
                  AND lt.kind = 'SETTLE'
                  AND la.type = 'USER_AVAILABLE'
                  AND la.wallet_id = ?
                """, challengeId, findWalletId(winnerId)));

        assertLedgerIntegrity();
    }

    @Test
    void shouldPreserveEscrowWhenParticipantsDisagree() {
        UUID challengeId = createAcceptedChallenge(20L);

        challengeService.confirm(
                challengeId,
                creatorId,
                creatorId);

        Challenge result = challengeService.confirm(
                challengeId,
                inviteeId,
                inviteeId);

        assertEquals(
                ChallengeStatus.DISPUTED,
                result.getStatus());

        assertNull(result.getSettledAt());

        assertEquals(
                "DISPUTED",
                persistedStatus(challengeId));

        assertEquals(2L, confirmationCount(challengeId));
        assertEquals(0L, settlementCount(challengeId));

        assertEquals(2L, number("""
                SELECT COUNT(DISTINCT winner_id)
                FROM confirmations
                WHERE challenge_id = ?
                """, challengeId));

        assertEquals(1L, number("""
                SELECT COUNT(*)
                FROM challenges
                WHERE id = ?
                  AND settled_at IS NULL
                """, challengeId));

        assertBalances(80L, 80L, 20L, 20L);

        assertLedgerIntegrity();
    }

    @Test
    void shouldPreserveOriginalConfirmationOnDuplicate() {
        UUID challengeId = createAcceptedChallenge(20L);

        challengeService.confirm(
                challengeId,
                creatorId,
                creatorId);

        assertThrows(
                DuplicateConfirmationException.class,
                () -> challengeService.confirm(
                        challengeId,
                        creatorId,
                        inviteeId));

        assertEquals(1L, confirmationCount(challengeId));

        UUID originalWinner = jdbcTemplate.queryForObject(
                """
                        SELECT winner_id
                        FROM confirmations
                        WHERE challenge_id = ?
                          AND participant_id = ?
                        """,
                UUID.class,
                challengeId,
                creatorId);

        assertEquals(creatorId, originalWinner);

        assertEquals(
                "AWAITING_RESULT",
                persistedStatus(challengeId));

        assertEquals(0L, settlementCount(challengeId));

        assertBalances(80L, 80L, 20L, 20L);

        assertLedgerIntegrity();
    }

    @Test
    void shouldNotSettleChallengeTwice() {
        UUID challengeId = createAcceptedChallenge(20L);

        challengeService.confirm(
                challengeId,
                creatorId,
                creatorId);

        challengeService.confirm(
                challengeId,
                inviteeId,
                creatorId);

        long entriesBefore = number(
                "SELECT COUNT(*) FROM ledger_entries");

        assertThrows(
                DuplicateConfirmationException.class,
                () -> challengeService.confirm(
                        challengeId,
                        inviteeId,
                        creatorId));

        assertEquals(
                "SETTLED",
                persistedStatus(challengeId));

        assertEquals(2L, confirmationCount(challengeId));
        assertEquals(1L, settlementCount(challengeId));

        assertEquals(
                entriesBefore,
                number("SELECT COUNT(*) FROM ledger_entries"));

        assertBalances(120L, 80L, 0L, 0L);

        assertLedgerIntegrity();
    }

    @Test
    void shouldSettleOnlyTargetChallengeEscrow() {
        UUID firstChallenge = createAcceptedChallenge(20L);
        UUID secondChallenge = createAcceptedChallenge(10L);

        assertBalances(70L, 70L, 30L, 30L);

        challengeService.confirm(
                firstChallenge,
                creatorId,
                creatorId);

        challengeService.confirm(
                firstChallenge,
                inviteeId,
                creatorId);

        assertEquals(
                "SETTLED",
                persistedStatus(firstChallenge));

        assertEquals(
                "ACCEPTED",
                persistedStatus(secondChallenge));

        assertEquals(
                0L,
                escrowBalance(firstChallenge, creatorId));

        assertEquals(
                0L,
                escrowBalance(firstChallenge, inviteeId));

        assertEquals(
                10L,
                escrowBalance(secondChallenge, creatorId));

        assertEquals(
                10L,
                escrowBalance(secondChallenge, inviteeId));

        assertEquals(1L, settlementCount(firstChallenge));
        assertEquals(0L, settlementCount(secondChallenge));
        assertEquals(0L, confirmationCount(secondChallenge));

        assertBalances(110L, 70L, 10L, 10L);

        assertLedgerIntegrity();
    }

    @Test
    void shouldRollbackEntireSettlementWhenCommitFails() {
        UUID challengeId = createAcceptedChallenge(20L);

        challengeService.confirm(
                challengeId,
                creatorId,
                creatorId);

        jdbcTemplate.execute("""
                CREATE FUNCTION pacto_test_fail_settlement()
                RETURNS trigger
                LANGUAGE plpgsql
                AS $$
                BEGIN
                    IF NEW.kind = 'SETTLE' THEN

                        IF (
                            SELECT status
                            FROM challenges
                            WHERE id = NEW.challenge_id
                        ) IS DISTINCT FROM 'SETTLED' THEN
                            RAISE EXCEPTION
                                'Expected settled challenge';
                        END IF;

                        IF (
                            SELECT COUNT(*)
                            FROM challenges
                            WHERE id = NEW.challenge_id
                              AND settled_at IS NOT NULL
                        ) <> 1 THEN
                            RAISE EXCEPTION
                                'Expected settlement timestamp';
                        END IF;

                        IF (
                            SELECT COUNT(*)
                            FROM confirmations
                            WHERE challenge_id = NEW.challenge_id
                        ) <> 2 THEN
                            RAISE EXCEPTION
                                'Expected two confirmations';
                        END IF;

                        IF (
                            SELECT COUNT(*)
                            FROM ledger_entries
                            WHERE transaction_id = NEW.id
                        ) <> 3 THEN
                            RAISE EXCEPTION
                                'Expected three settlement entries';
                        END IF;

                        IF (
                            SELECT COUNT(*)
                            FROM ledger_accounts
                            WHERE challenge_id = NEW.challenge_id
                              AND type = 'CHALLENGE_ESCROW'
                              AND balance = 0
                        ) <> 2 THEN
                            RAISE EXCEPTION
                                'Expected zeroed escrow accounts';
                        END IF;

                        IF (
                            SELECT COUNT(*)
                            FROM ledger_accounts
                            WHERE type = 'USER_AVAILABLE'
                              AND balance = 120
                        ) <> 1 THEN
                            RAISE EXCEPTION
                                'Expected winner payout';
                        END IF;

                        RAISE EXCEPTION
                            'PACTO_TEST_FORCED_SETTLEMENT_ROLLBACK';

                    END IF;

                    RETURN NEW;
                END;
                $$;
                """);

        try {
            jdbcTemplate.execute("""
                    CREATE CONSTRAINT TRIGGER
                        pacto_test_settlement_failure
                    AFTER INSERT ON ledger_transactions
                    DEFERRABLE INITIALLY DEFERRED
                    FOR EACH ROW
                    EXECUTE FUNCTION
                        pacto_test_fail_settlement()
                    """);

            RuntimeException failure = assertThrows(
                    RuntimeException.class,
                    () -> challengeService.confirm(
                            challengeId,
                            inviteeId,
                            creatorId));

            assertTrue(
                    containsMessage(
                            failure,
                            "PACTO_TEST_FORCED_SETTLEMENT_ROLLBACK"),
                    () -> "Unexpected failure: " + failure);

            assertEquals(
                    "AWAITING_RESULT",
                    persistedStatus(challengeId));

            assertEquals(1L, confirmationCount(challengeId));
            assertEquals(0L, settlementCount(challengeId));

            assertEquals(1L, number("""
                    SELECT COUNT(*)
                    FROM confirmations
                    WHERE challenge_id = ?
                      AND participant_id = ?
                      AND winner_id = ?
                    """, challengeId, creatorId, creatorId));

            assertEquals(1L, number("""
                    SELECT COUNT(*)
                    FROM challenges
                    WHERE id = ?
                      AND settled_at IS NULL
                    """, challengeId));

            assertEquals(
                    3L,
                    number("SELECT COUNT(*) FROM ledger_transactions"));

            assertEquals(
                    8L,
                    number("SELECT COUNT(*) FROM ledger_entries"));

            assertBalances(80L, 80L, 20L, 20L);

            assertLedgerIntegrity();

        } finally {
            jdbcTemplate.execute("""
                    DROP TRIGGER IF EXISTS
                        pacto_test_settlement_failure
                    ON ledger_transactions
                    """);

            jdbcTemplate.execute("""
                    DROP FUNCTION IF EXISTS
                        pacto_test_fail_settlement()
                    """);
        }

        Challenge retried = challengeService.confirm(
                challengeId,
                inviteeId,
                creatorId);

        assertEquals(
                ChallengeStatus.SETTLED,
                retried.getStatus());

        assertEquals(2L, confirmationCount(challengeId));
        assertEquals(1L, settlementCount(challengeId));

        assertBalances(120L, 80L, 0L, 0L);

        assertLedgerIntegrity();
    }

    private boolean containsMessage(
            Throwable error,
            String expected) {
        Throwable current = error;

        while (current != null) {
            if (current.getMessage() != null
                    && current.getMessage().contains(expected)) {
                return true;
            }

            current = current.getCause();
        }

        return false;
    }

    @Test
    void shouldRejectSettlementWithoutExistingTransaction() {
        UUID challengeId = createAcceptedChallenge(20L);

        UUID creatorWalletId = findWalletId(creatorId);
        UUID inviteeWalletId = findWalletId(inviteeId);

        assertThrows(
                IllegalTransactionStateException.class,
                () -> ledgerService.settle(
                        UUID.randomUUID(),
                        challengeId,
                        creatorWalletId,
                        inviteeWalletId,
                        creatorWalletId,
                        20L));

        assertEquals(
                "ACCEPTED",
                persistedStatus(challengeId));

        assertEquals(0L, settlementCount(challengeId));
        assertEquals(0L, confirmationCount(challengeId));

        assertBalances(80L, 80L, 20L, 20L);

        assertLedgerIntegrity();
    }
}