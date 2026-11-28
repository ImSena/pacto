package dev.pacto.challenge;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import dev.pacto.challenge.controller.ChallengeController;
import dev.pacto.demo.DemoDataService;
import dev.pacto.idempotency.PayloadHasher;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChallengeIdempotencyIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:17")
                    .withDatabaseName("pacto_idempotency_http_test");

    private static final String CREATE = "/api/challenges";
    private static final String COMMIT_FAILURE = "PCT015_FORCED_COMMIT_FAILURE";

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DemoDataService demoDataService;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private ChallengeController challengeController;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private UUID creatorId;
    private UUID inviteeId;

    @BeforeEach
    void setUp() {
        assertEquals("pacto_idempotency_http_test",
                jdbcTemplate.queryForObject("SELECT current_database()", String.class));

        jdbcTemplate.execute("""
                TRUNCATE TABLE idempotency_records, confirmations,
                    ledger_entries, ledger_transactions, ledger_accounts,
                    challenges, wallets, users
                """);

        demoDataService.initialize();
        creatorId = userId("Bruno");
        inviteeId = userId("Lucas");
        assertBalances(100, 100, 0);
        assertLedgerIntegrity();
    }

    @AfterEach
    void closeClient() {
        client.close();
    }

    @Test
    void shouldReplayEquivalentCreateAndRejectChangedPayload() throws Exception {
        var first = post(CREATE, creatorId, "create-1", createBody("20"));
        UUID id = assertCreated(first);

        var replay = post(CREATE, creatorId, "create-1", """
                {"stakePerParticipant":20.0,"description":"Description",
                 "title":"  Football  ","inviteeId":"%s"}
                """.formatted(inviteeId));

        assertReplay(first, replay);
        assertEquals(first.headers().firstValue("Location"),
                replay.headers().firstValue("Location"));

        var conflict = post(CREATE, creatorId, "create-1", createBody("30"));
        assertEquals(409, conflict.statusCode());
        assertEquals("OPEN", persistedStatus(id));
        assertEquals(1L, count("SELECT COUNT(*) FROM challenges"));
        assertEquals(1L, count("SELECT COUNT(*) FROM idempotency_records"));
        assertStored(creatorId, "CREATE_CHALLENGE", "create-1", first);
        assertBalances(100, 100, 0);
        assertFinancialCounts(0, 0);
    }

    @Test
    void shouldReplayAcceptanceAndRejectSameKeyForDifferentChallenge() throws Exception {
        UUID id = create("create-1");
        UUID other = create("create-2");
        var first = accept(id, "accept-1");
        assertStatus(first, 200, "ACCEPTED");
        assertReplay(first, accept(id, "accept-1"));

        assertEquals(409, accept(other, "accept-1").statusCode());
        assertEquals("OPEN", persistedStatus(other));
        assertEquals("ACCEPTED", persistedStatus(id));
        assertStored(inviteeId, "ACCEPT_CHALLENGE", "accept-1", first);
        assertEquals(3L, count("SELECT COUNT(*) FROM idempotency_records"));
        assertBalances(80, 80, 40);
        assertFinancialCounts(1, 0);
    }

    @Test
    void shouldReplayHistoricalAcceptanceAndBothConfirmationsAfterSettlement() throws Exception {
        UUID id = create("create-1");
        var accepted = accept(id, "accept-1");
        assertStatus(accepted, 200, "ACCEPTED");

        var first = confirm(id, creatorId, creatorId, "confirm-1");
        assertStatus(first, 200, "AWAITING_RESULT");
        assertReplay(first, confirm(id, creatorId, creatorId, "confirm-1"));
        assertEquals(409, confirm(id, creatorId, inviteeId, "confirm-1").statusCode());
        assertEquals(1L, count("SELECT COUNT(*) FROM confirmations"));
        assertBalances(80, 80, 40);
        assertFinancialCounts(1, 0);

        // The same key is valid for the other actor.
        var second = confirm(id, inviteeId, creatorId, "confirm-1");
        assertStatus(second, 200, "SETTLED");
        assertReplay(accepted, accept(id, "accept-1"));
        assertReplay(first, confirm(id, creatorId, creatorId, "confirm-1"));
        assertReplay(second, confirm(id, inviteeId, creatorId, "confirm-1"));
        assertStored(creatorId, "CONFIRM_RESULT", "confirm-1", first);
        assertStored(inviteeId, "CONFIRM_RESULT", "confirm-1", second);

        assertEquals("SETTLED", persistedStatus(id));
        assertEquals(2L, count("SELECT COUNT(*) FROM confirmations"));
        assertEquals(4L, count("SELECT COUNT(*) FROM idempotency_records"));
        assertBalances(120, 80, 0);
        assertFinancialCounts(1, 1);
    }

    @Test
    void shouldReplayCancellationAndScopeKeyByOperation() throws Exception {
        UUID id = create("shared-key");
        var first = post(path(id, "cancel"), creatorId, "shared-key", null);
        assertStatus(first, 200, "CANCELLED");
        assertReplay(first, post(path(id, "cancel"), creatorId, "shared-key", null));
        assertEquals(409, post(path(id, "cancel"), creatorId, "new-key", null).statusCode());
        assertStored(creatorId, "CANCEL_CHALLENGE", "shared-key", first);
        assertEquals("CANCELLED", persistedStatus(id));
        assertEquals(2L, count("SELECT COUNT(*) FROM idempotency_records"));
        assertBalances(100, 100, 0);
        assertFinancialCounts(0, 0);
    }

    @Test
    void shouldRollbackBusinessRejectionAndAllowSameKeyAfterStateChanges() throws Exception {
        UUID id = create("create-1");
        assertEquals(409, confirm(id, creatorId, creatorId, "confirm-1").statusCode());
        assertEquals(0L, count("SELECT COUNT(*) FROM idempotency_records WHERE operation = 'CONFIRM_RESULT'"));
        assertEquals(0L, count("SELECT COUNT(*) FROM confirmations"));
        assertEquals("OPEN", persistedStatus(id));
        assertBalances(100, 100, 0);
        assertFinancialCounts(0, 0);

        assertStatus(accept(id, "accept-1"), 200, "ACCEPTED");
        var retry = confirm(id, creatorId, creatorId, "confirm-1");
        assertStatus(retry, 200, "AWAITING_RESULT");
        assertStored(creatorId, "CONFIRM_RESULT", "confirm-1", retry);
        assertEquals(1L, count("SELECT COUNT(*) FROM confirmations"));
        assertEquals(3L, count("SELECT COUNT(*) FROM idempotency_records"));
        assertBalances(80, 80, 40);
        assertFinancialCounts(1, 0);
    }

    @Test
    void shouldRollbackIdempotencyAndReservationOnCommitFailure() throws Exception {
        UUID id = create("create-1");
        try {
            jdbcTemplate.execute("""
                    CREATE FUNCTION pct015_fail_accept_commit() RETURNS trigger
                    LANGUAGE plpgsql AS $$
                    DECLARE
                        target_id uuid := (NEW.response_body ->> 'id')::uuid;
                    BEGIN
                        IF NEW.http_status IS DISTINCT FROM 200
                           OR (NEW.response_body ->> 'status') IS DISTINCT FROM 'ACCEPTED'
                           OR (SELECT status FROM challenges WHERE id = target_id)
                                IS DISTINCT FROM 'ACCEPTED'
                           OR (SELECT COUNT(*) FROM ledger_transactions
                               WHERE challenge_id = target_id AND kind = 'RESERVE') <> 1
                           OR (SELECT COUNT(*) FROM ledger_entries e
                               JOIN ledger_transactions t ON t.id = e.transaction_id
                               WHERE t.challenge_id = target_id AND t.kind = 'RESERVE') <> 4
                           OR (SELECT COUNT(*) FROM ledger_accounts
                               WHERE challenge_id = target_id AND type = 'CHALLENGE_ESCROW'
                                 AND balance = 20) <> 2
                        THEN
                            RAISE EXCEPTION 'PCT015_UNEXPECTED_PRECOMMIT_STATE';
                        END IF;
                        RAISE EXCEPTION 'PCT015_FORCED_COMMIT_FAILURE';
                    END;
                    $$
                    """);
            jdbcTemplate.execute("""
                    CREATE CONSTRAINT TRIGGER pct015_fail_accept_commit
                    AFTER UPDATE ON idempotency_records
                    DEFERRABLE INITIALLY DEFERRED
                    FOR EACH ROW
                    WHEN (NEW.operation = 'ACCEPT_CHALLENGE' AND NEW.state = 'COMPLETED')
                    EXECUTE FUNCTION pct015_fail_accept_commit()
                    """);

            // Invoke the real controller bean to inspect the actual commit exception.
            // All other success/replay calls in this test use real HTTP.
            RuntimeException failure = assertThrows(RuntimeException.class,
                    () -> challengeController.accept(id, inviteeId, "accept-1"));
            assertTrue(hasCauseMessage(failure, COMMIT_FAILURE), failure::toString);

            assertEquals("OPEN", persistedStatus(id));
            assertEquals(0L, count("SELECT COUNT(*) FROM idempotency_records WHERE operation = 'ACCEPT_CHALLENGE'"));
            assertEquals(1L, count("SELECT COUNT(*) FROM idempotency_records"));
            assertBalances(100, 100, 0);
            assertFinancialCounts(0, 0);
        } finally {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS pct015_fail_accept_commit ON idempotency_records");
            jdbcTemplate.execute("DROP FUNCTION IF EXISTS pct015_fail_accept_commit()");
        }

        var retry = accept(id, "accept-1");
        assertStatus(retry, 200, "ACCEPTED");
        assertReplay(retry, accept(id, "accept-1"));
        assertStored(inviteeId, "ACCEPT_CHALLENGE", "accept-1", retry);
        assertBalances(80, 80, 40);
        assertFinancialCounts(1, 0);
    }

    @Test
    void shouldSerializeTwoConcurrentAcceptsWithSameKey() throws Exception {
        UUID id = create("create-1");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var firstFuture = executor.submit(() -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Start barrier timed out");
                }
                return accept(id, "accept-1");
            });
            var secondFuture = executor.submit(() -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Start barrier timed out");
                }
                return accept(id, "accept-1");
            });
            try {
                assertTrue(ready.await(5, TimeUnit.SECONDS));
            } finally {
                start.countDown();
            }

            var first = firstFuture.get(30, TimeUnit.SECONDS);
            var second = secondFuture.get(30, TimeUnit.SECONDS);
            // A bounded lock wait may produce a transient response under load.
            assertTrue(first.statusCode() == 200 || first.statusCode() == 503, first::body);
            assertTrue(second.statusCode() == 200 || second.statusCode() == 503, second::body);
            assertTrue(first.statusCode() == 200 || second.statusCode() == 200);
            var stored = accept(id, "accept-1");
            assertStatus(stored, 200, "ACCEPTED");
            if (first.statusCode() == 200) assertReplay(first, stored);
            if (second.statusCode() == 200) assertReplay(second, stored);
            assertStored(inviteeId, "ACCEPT_CHALLENGE", "accept-1", stored);
        }

        assertEquals("ACCEPTED", persistedStatus(id));
        assertEquals(2L, count("SELECT COUNT(*) FROM idempotency_records"));
        assertBalances(80, 80, 40);
        assertFinancialCounts(1, 0);
    }

    @Test
    void shouldReturn503WhenSameKeyIsLockedAndAllowRetryAfterRollback() throws Exception {
        UUID id = create("create-1");
        var transaction = new TransactionTemplate(transactionManager);

        transaction.executeWithoutResult(status -> {
            // Hold the uncommitted unique key in a separate database transaction.
            jdbcTemplate.update("""
                    INSERT INTO idempotency_records (actor_id, operation, idempotency_key, payload_hash)
                    VALUES (?, 'ACCEPT_CHALLENGE', ?, ?)
                    """, inviteeId, "accept-1", PayloadHasher.hashAccept(id));

            var blocked = assertDoesNotThrow(() -> accept(id, "accept-1"));
            assertEquals(503, blocked.statusCode(), blocked::body);
            assertEquals("1", blocked.headers().firstValue("Retry-After").orElseThrow());
            assertEquals(503, jsonMapper.readTree(blocked.body()).path("status").asInt());
            status.setRollbackOnly();
        });

        assertEquals(0L, count("SELECT COUNT(*) FROM idempotency_records WHERE operation = 'ACCEPT_CHALLENGE'"));
        assertEquals("OPEN", persistedStatus(id));
        assertBalances(100, 100, 0);
        assertFinancialCounts(0, 0);

        var retry = accept(id, "accept-1");
        assertStatus(retry, 200, "ACCEPTED");
        assertStored(inviteeId, "ACCEPT_CHALLENGE", "accept-1", retry);
        assertBalances(80, 80, 40);
        assertFinancialCounts(1, 0);
    }

    @Test
    void shouldRejectUnknownActorWithoutCreatingRecord() throws Exception {
        UUID id = create("create-1");
        var response = post(path(id, "accept"), UUID.randomUUID(), "accept-1", null);
        assertEquals(403, response.statusCode());
        assertEquals(1L, count("SELECT COUNT(*) FROM idempotency_records"));
        assertEquals("OPEN", persistedStatus(id));
        assertBalances(100, 100, 0);
        assertFinancialCounts(0, 0);
    }

    private HttpResponse<String> post(String path, UUID actor, String key, String body)
            throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(25))
                .header("X-Actor-Id", actor.toString())
                .header("Idempotency-Key", key);
        if (body == null) {
            builder.POST(HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body));
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String createBody(String stake) {
        return """
                {"inviteeId":"%s","title":"Football","description":"Description",
                 "stakePerParticipant":%s}
                """.formatted(inviteeId, stake);
    }

    private UUID create(String key) throws Exception {
        return assertCreated(post(CREATE, creatorId, key, createBody("20")));
    }

    private UUID assertCreated(HttpResponse<String> response) {
        assertStatus(response, 201, "OPEN");
        UUID id = UUID.fromString(jsonMapper.readTree(response.body()).path("id").asText());
        assertEquals(CREATE + "/" + id, response.headers().firstValue("Location").orElseThrow());
        return id;
    }

    private String path(UUID id, String action) {
        return CREATE + "/" + id + "/" + action;
    }

    private HttpResponse<String> accept(UUID id, String key) throws Exception {
        return post(path(id, "accept"), inviteeId, key, null);
    }

    private HttpResponse<String> confirm(UUID id, UUID actor, UUID winner, String key)
            throws Exception {
        return post(path(id, "confirm"), actor, key,
                """
                {"winnerId":"%s"}
                """.formatted(winner));
    }

    private void assertStatus(HttpResponse<String> response, int status, String state) {
        assertEquals(status, response.statusCode(), response::body);
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
        assertEquals(state, jsonMapper.readTree(response.body()).path("status").asText());
    }

    private void assertReplay(HttpResponse<String> original, HttpResponse<String> replay) {
        assertEquals(original.statusCode(), replay.statusCode(), replay::body);
        assertEquals(Boolean.TRUE, jdbcTemplate.queryForObject(
                "SELECT CAST(? AS JSONB) = CAST(? AS JSONB)", Boolean.class,
                original.body(), replay.body()));
    }

    private void assertStored(UUID actor, String operation, String key, HttpResponse<String> response) {
        assertEquals(1L, count("""
                SELECT COUNT(*) FROM idempotency_records
                WHERE actor_id = ? AND operation = ? AND idempotency_key = ?
                  AND state = 'COMPLETED' AND completed_at IS NOT NULL
                  AND http_status = ? AND response_body = CAST(? AS JSONB)
                """, actor, operation, key, response.statusCode(), response.body()));
    }

    private UUID userId(String name) {
        return jdbcTemplate.queryForObject("SELECT id FROM users WHERE display_name = ?", UUID.class, name);
    }

    private String persistedStatus(UUID id) {
        return jdbcTemplate.queryForObject("SELECT status FROM challenges WHERE id = ?", String.class, id);
    }

    private long count(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Long.class, args);
    }

    private long available(UUID userId) {
        return count("""
                SELECT a.balance FROM ledger_accounts a JOIN wallets w ON w.id = a.wallet_id
                WHERE w.user_id = ? AND a.type = 'USER_AVAILABLE'
                """, userId);
    }

    private long reserved(UUID userId) {
        return count("""
                SELECT COALESCE(SUM(a.balance), 0)::bigint
                FROM ledger_accounts a JOIN wallets w ON w.id = a.wallet_id
                WHERE w.user_id = ? AND a.type = 'CHALLENGE_ESCROW'
                """, userId);
    }

    private void assertBalances(long creator, long invitee, long escrow) {
        assertEquals(creator, available(creatorId));
        assertEquals(invitee, available(inviteeId));
        assertEquals(escrow, count("SELECT COALESCE(SUM(balance), 0)::bigint FROM ledger_accounts WHERE type = 'CHALLENGE_ESCROW'"));
        // All scenarios in this suite have at most one accepted challenge.
        assertEquals(escrow / 2, reserved(creatorId));
        assertEquals(escrow / 2, reserved(inviteeId));
        assertEquals(200L, creator + invitee + escrow);
        assertLedgerIntegrity();
    }

    private void assertFinancialCounts(long reserves, long settlements) {
        assertEquals(reserves, count("SELECT COUNT(*) FROM ledger_transactions WHERE kind = 'RESERVE'"));
        assertEquals(settlements, count("SELECT COUNT(*) FROM ledger_transactions WHERE kind = 'SETTLE'"));
        assertEquals(3L + 2L * reserves, count("SELECT COUNT(*) FROM ledger_accounts"));
        assertEquals(2L + reserves + settlements, count("SELECT COUNT(*) FROM ledger_transactions"));
        assertEquals(4L + 4L * reserves + 3L * settlements, count("SELECT COUNT(*) FROM ledger_entries"));
        assertEquals(0L, count("SELECT COUNT(*) FROM idempotency_records WHERE state <> 'COMPLETED'"));
    }

    private void assertLedgerIntegrity() {
        assertEquals(0L, count("""
                SELECT COUNT(*) FROM (
                    SELECT t.id FROM ledger_transactions t
                    LEFT JOIN ledger_entries e ON e.transaction_id = t.id
                    GROUP BY t.id
                    HAVING COUNT(e.id) < 2 OR COALESCE(SUM(e.delta), 0) <> 0
                ) invalid_transactions
                """));
        assertEquals(0L, count("""
                SELECT COUNT(*) FROM (
                    SELECT a.id FROM ledger_accounts a
                    LEFT JOIN ledger_entries e ON e.account_id = a.id
                    GROUP BY a.id, a.balance
                    HAVING a.balance <> COALESCE(SUM(e.delta), 0)
                ) invalid_accounts
                """));
        assertEquals(0L, count("SELECT COUNT(*) FROM ledger_accounts WHERE type <> 'SYSTEM_ISSUANCE' AND balance < 0"));
        assertEquals(200L, count("SELECT COALESCE(SUM(balance), 0)::bigint FROM ledger_accounts WHERE type <> 'SYSTEM_ISSUANCE'"));
        assertEquals(-200L, count("SELECT balance FROM ledger_accounts WHERE type = 'SYSTEM_ISSUANCE'"));
        assertEquals(0L, count("SELECT COALESCE(SUM(balance), 0)::bigint FROM ledger_accounts"));
    }

    private boolean hasCauseMessage(Throwable failure, String marker) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause.getMessage() != null && cause.getMessage().contains(marker)) {
                return true;
            }
        }
        return false;
    }
}
