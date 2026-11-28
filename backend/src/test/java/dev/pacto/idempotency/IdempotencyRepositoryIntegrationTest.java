package dev.pacto.idempotency;

import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import java.util.concurrent.atomic.AtomicInteger;

import dev.pacto.idempotency.repository.IdempotencyRecordRepository;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class IdempotencyRepositoryIntegrationTest {

        @Container
        @ServiceConnection
        static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17")
                        .withDatabaseName("pacto_idempotency_test");

        private static final IdempotencyOperation OPERATION = IdempotencyOperation.CREATE_CHALLENGE;

        private static final String KEY = "create-001";

        private static final String RESPONSE_BODY = """
                        {
                            "status": "OPEN",
                            "stakePerParticipant": 20
                        }
                        """;

        @Autowired
        private IdempotencyRecordRepository repository;

        @Autowired
        private IdempotencyService idempotencyService;

        @Autowired
        private JdbcTemplate jdbcTemplate;

        @Autowired
        private PlatformTransactionManager transactionManager;

        @PersistenceContext
        private EntityManager entityManager;

        private TransactionTemplate transaction;

        private UUID actorId;

        private String payloadHash;

        @BeforeEach
        void setUp() {

                assertEquals(
                                "pacto_idempotency_test",
                                jdbcTemplate.queryForObject(
                                                "SELECT current_database()",
                                                String.class));

                transaction = new TransactionTemplate(transactionManager);

                transaction.setIsolationLevel(
                                TransactionDefinition.ISOLATION_READ_COMMITTED);

                actorId = createTestActor();

                payloadHash = PayloadHasher.hashCreate(
                                UUID.randomUUID(),
                                "Football",
                                null,
                                20L);
        }

        @Test
        void shouldInsertInProgressRecord() {

                transaction.executeWithoutResult(status -> {

                        assertTrue(repository.tryInsert(
                                        actorId,
                                        OPERATION,
                                        KEY,
                                        payloadHash));

                        IdempotencyRecord record = findRecord();

                        assertNotNull(record.id());
                        assertEquals(actorId, record.actorId());
                        assertEquals(OPERATION, record.operation());
                        assertEquals(KEY, record.idempotencyKey());
                        assertEquals(payloadHash, record.payloadHash());

                        assertEquals(
                                        IdempotencyState.IN_PROGRESS,
                                        record.state());

                        assertTrue(record.matchesPayload(payloadHash));
                        assertFalse(record.isCompleted());

                        assertNotNull(record.createdAt());

                        assertNull(record.httpStatus());
                        assertNull(record.responseBody());
                        assertNull(record.completedAt());

                        // Do not commit an unfinished record.
                        status.setRollbackOnly();
                });

                assertEquals(0L, countRecords(actorId));
        }

        @Test
        void shouldCompleteAndRecoverPersistedResponse() {

                UUID recordId = transaction.execute(status -> {

                        assertTrue(repository.tryInsert(
                                        actorId,
                                        OPERATION,
                                        KEY,
                                        payloadHash));

                        IdempotencyRecord record = findRecord();

                        repository.complete(
                                        record.id(),
                                        201,
                                        RESPONSE_BODY);

                        return record.id();
                });

                transaction.executeWithoutResult(status -> {

                        IdempotencyRecord record = findRecord();

                        assertEquals(recordId, record.id());

                        assertTrue(record.isCompleted());

                        assertEquals(
                                        IdempotencyState.COMPLETED,
                                        record.state());

                        assertEquals(
                                        Integer.valueOf(201),
                                        record.httpStatus());

                        assertNotNull(record.responseBody());
                        assertNotNull(record.completedAt());

                        assertFalse(
                                        record.completedAt()
                                                        .isBefore(record.createdAt()));

                        Boolean sameJson = jdbcTemplate.queryForObject(
                                        """
                                                        SELECT response_body = CAST(? AS JSONB)
                                                        FROM idempotency_records
                                                        WHERE id = ?
                                                        """,
                                        Boolean.class,
                                        RESPONSE_BODY,
                                        recordId);

                        assertEquals(Boolean.TRUE, sameJson);
                });

                assertEquals(1L, countRecords(actorId));
        }

        @Test
        void shouldIgnoreDuplicateInsertWithoutChangingRecord() {

                createCompletedRecord();

                IdempotencyRecord before = transaction.execute(status -> findRecord());

                String differentHash = "f".repeat(64);

                assertNotEquals(payloadHash, differentHash);

                transaction.executeWithoutResult(status -> {

                        assertFalse(repository.tryInsert(
                                        actorId,
                                        OPERATION,
                                        KEY,
                                        differentHash));

                        IdempotencyRecord after = findRecord();

                        assertEquals(before, after);

                        assertTrue(after.matchesPayload(payloadHash));

                        assertFalse(after.matchesPayload(differentHash));

                        assertTrue(after.isCompleted());
                });

                assertEquals(1L, countRecords(actorId));
        }

        @Test
        void shouldScopeKeysByActorAndOperation() {

                UUID anotherActor = createTestActor();

                String acceptHash = PayloadHasher.hashAccept(UUID.randomUUID());

                transaction.executeWithoutResult(status -> {

                        assertTrue(repository.tryInsert(
                                        actorId,
                                        OPERATION,
                                        KEY,
                                        payloadHash));

                        assertTrue(repository.tryInsert(
                                        anotherActor,
                                        OPERATION,
                                        KEY,
                                        payloadHash));

                        assertTrue(repository.tryInsert(
                                        actorId,
                                        IdempotencyOperation.ACCEPT_CHALLENGE,
                                        KEY,
                                        acceptHash));

                        IdempotencyRecord first = findRecord();

                        IdempotencyRecord second = repository.findForUpdate(
                                        anotherActor,
                                        OPERATION,
                                        KEY)
                                        .orElseThrow();

                        IdempotencyRecord third = repository.findForUpdate(
                                        actorId,
                                        IdempotencyOperation.ACCEPT_CHALLENGE,
                                        KEY)
                                        .orElseThrow();

                        assertNotEquals(first.id(), second.id());
                        assertNotEquals(first.id(), third.id());
                        assertNotEquals(second.id(), third.id());

                        assertEquals(anotherActor, second.actorId());

                        assertEquals(
                                        IdempotencyOperation.ACCEPT_CHALLENGE,
                                        third.operation());

                        status.setRollbackOnly();
                });

                assertEquals(0L, countRecords(actorId));
                assertEquals(0L, countRecords(anotherActor));
        }

        @Test
        void shouldRollbackCompletedRecordAndAllowRetry() {

                assertThrows(
                                IllegalStateException.class,
                                () -> transaction.executeWithoutResult(status -> {

                                        assertTrue(repository.tryInsert(
                                                        actorId,
                                                        OPERATION,
                                                        KEY,
                                                        payloadHash));

                                        IdempotencyRecord record = findRecord();

                                        repository.complete(
                                                        record.id(),
                                                        201,
                                                        RESPONSE_BODY);

                                        assertTrue(findRecord().isCompleted());

                                        throw new IllegalStateException(
                                                        "Intentional transaction failure");
                                }));

                assertEquals(0L, countRecords(actorId));

                createCompletedRecord();

                assertEquals(1L, countRecords(actorId));

                transaction.executeWithoutResult(status -> {

                        assertTrue(findRecord().isCompleted());
                });
        }

        @Test
        void shouldRejectCompletionOfAlreadyCompletedRecord() {

                createCompletedRecord();

                IdempotencyRecord before = transaction.execute(status -> findRecord());

                InvalidDataAccessApiUsageException exception = assertThrows(
                                InvalidDataAccessApiUsageException.class,
                                () -> transaction.executeWithoutResult(status -> {

                                        repository.complete(
                                                        before.id(),
                                                        200,
                                                        """
                                                                        {"status": "CANCELLED"}
                                                                        """);
                                }));

                assertInstanceOf(
                                IllegalStateException.class,
                                exception.getCause());

                assertEquals(
                                "Expected exactly one in-progress "
                                                + "idempotency record to be completed",
                                exception.getCause().getMessage());

                IdempotencyRecord after = transaction.execute(status -> findRecord());

                assertEquals(before, after);

                assertEquals(1L, countRecords(actorId));
        }

        @Test
        void shouldRequireExistingTransaction() {

                assertThrows(
                                IllegalTransactionStateException.class,
                                () -> repository.tryInsert(
                                                actorId,
                                                OPERATION,
                                                KEY,
                                                payloadHash));

                assertThrows(
                                IllegalTransactionStateException.class,
                                () -> repository.findForUpdate(
                                                actorId,
                                                OPERATION,
                                                KEY));

                assertThrows(
                                IllegalTransactionStateException.class,
                                () -> repository.complete(
                                                UUID.randomUUID(),
                                                201,
                                                RESPONSE_BODY));

                assertEquals(0L, countRecords(actorId));
        }

        @Test
        void shouldShareTransactionBetweenJpaAndJdbc() {

                UUID transientActor = UUID.randomUUID();

                transaction.executeWithoutResult(status -> {

                        Number jpaBackendPid = (Number) entityManager
                                        .createNativeQuery(
                                                        "SELECT pg_backend_pid()")
                                        .getSingleResult();

                        Integer jdbcBackendPid = jdbcTemplate.queryForObject(
                                        "SELECT pg_backend_pid()",
                                        Integer.class);

                        assertNotNull(jdbcBackendPid);

                        assertEquals(
                                        jpaBackendPid.intValue(),
                                        jdbcBackendPid.intValue());

                        assertEquals(
                                        "read committed",
                                        jdbcTemplate.queryForObject(
                                                        "SHOW transaction_isolation",
                                                        String.class));

                        entityManager.createNativeQuery(
                                        """
                                                        INSERT INTO users (id, display_name)
                                                        VALUES (:id, :name)
                                                        """)
                                        .setParameter("id", transientActor)
                                        .setParameter("name", "Transient test actor")
                                        .executeUpdate();

                        assertTrue(repository.tryInsert(
                                        transientActor,
                                        OPERATION,
                                        KEY,
                                        payloadHash));

                        IdempotencyRecord record = repository.findForUpdate(
                                        transientActor,
                                        OPERATION,
                                        KEY)
                                        .orElseThrow();

                        repository.complete(
                                        record.id(),
                                        201,
                                        RESPONSE_BODY);

                        status.setRollbackOnly();
                });

                assertEquals(0L, countRecords(transientActor));

                Long actorCount = jdbcTemplate.queryForObject(
                                """
                                                SELECT COUNT(*)
                                                FROM users
                                                WHERE id = ?
                                                """,
                                Long.class,
                                transientActor);

                assertEquals(Long.valueOf(0L), actorCount);
        }

        @Test
        void shouldExecuteCommandOnceAndReplayPersistedResponse() {

                AtomicInteger executionCount = new AtomicInteger();

                IdempotencyResponse first = idempotencyService.execute(
                                actorId,
                                OPERATION,
                                KEY,
                                payloadHash,
                                () -> {

                                        executionCount.incrementAndGet();

                                        return new IdempotencyResponse(
                                                        201,
                                                        RESPONSE_BODY);
                                });

                IdempotencyResponse replay = idempotencyService.execute(
                                actorId,
                                OPERATION,
                                KEY,
                                payloadHash,
                                () -> {

                                        executionCount.incrementAndGet();

                                        return new IdempotencyResponse(
                                                        201,
                                                        """
                                                                        {"status": "UNEXPECTED"}
                                                                        """);
                                });

                assertEquals(1, executionCount.get());

                assertEquals(201, first.httpStatus());

                assertEquals(
                                first.httpStatus(),
                                replay.httpStatus());

                Boolean sameJson = jdbcTemplate.queryForObject(
                                """
                                                SELECT CAST(? AS JSONB) = CAST(? AS JSONB)
                                                """,
                                Boolean.class,
                                first.responseBody(),
                                replay.responseBody());

                assertEquals(Boolean.TRUE, sameJson);

                assertEquals(1L, countRecords(actorId));

                transaction.executeWithoutResult(status -> {

                        IdempotencyRecord record = findRecord();

                        assertTrue(record.isCompleted());

                        assertEquals(
                                        replay.responseBody(),
                                        record.responseBody());
                });
        }

        @Test
        void shouldRollbackCommandEffectsAndAllowRetry() {

                UUID sideEffectId = UUID.randomUUID();

                IllegalStateException exception = assertThrows(
                                IllegalStateException.class,
                                () -> idempotencyService.execute(
                                                actorId,
                                                OPERATION,
                                                KEY,
                                                payloadHash,
                                                () -> {

                                                        entityManager.createNativeQuery(
                                                                        """
                                                                                        INSERT INTO users (
                                                                                            id,
                                                                                            display_name
                                                                                        )
                                                                                        VALUES (:id, :name)
                                                                                        """)
                                                                        .setParameter(
                                                                                        "id", sideEffectId)
                                                                        .setParameter(
                                                                                        "name",
                                                                                        "Transactional side effect")
                                                                        .executeUpdate();

                                                        throw new IllegalStateException(
                                                                        "Intentional command failure");
                                                }));

                assertEquals(
                                "Intentional command failure",
                                exception.getMessage());

                assertEquals(0L, countRecords(actorId));

                Long sideEffectCount = jdbcTemplate.queryForObject(
                                """
                                                SELECT COUNT(*)
                                                FROM users
                                                WHERE id = ?
                                                """,
                                Long.class,
                                sideEffectId);

                assertEquals(
                                Long.valueOf(0L),
                                sideEffectCount);

                IdempotencyResponse retry = idempotencyService.execute(
                                actorId,
                                OPERATION,
                                KEY,
                                payloadHash,
                                () -> new IdempotencyResponse(
                                                201,
                                                RESPONSE_BODY));

                assertEquals(201, retry.httpStatus());

                assertEquals(1L, countRecords(actorId));

                transaction.executeWithoutResult(status -> {

                        assertTrue(findRecord().isCompleted());
                });
        }

        private UUID createTestActor() {

                UUID id = UUID.randomUUID();

                jdbcTemplate.update(
                                """
                                                INSERT INTO users (
                                                    id,
                                                    display_name
                                                )
                                                VALUES (?, ?)
                                                """,
                                id,
                                "Idempotency test actor " + id);

                return id;
        }

        private IdempotencyRecord findRecord() {

                return repository.findForUpdate(
                                actorId,
                                OPERATION,
                                KEY)
                                .orElseThrow();
        }

        private void createCompletedRecord() {

                transaction.executeWithoutResult(status -> {

                        assertTrue(repository.tryInsert(
                                        actorId,
                                        OPERATION,
                                        KEY,
                                        payloadHash));

                        IdempotencyRecord record = findRecord();

                        repository.complete(
                                        record.id(),
                                        201,
                                        RESPONSE_BODY);
                });
        }

        private long countRecords(UUID actor) {

                Long count = jdbcTemplate.queryForObject(
                                """
                                                SELECT COUNT(*)
                                                FROM idempotency_records
                                                WHERE actor_id = ?
                                                """,
                                Long.class,
                                actor);

                return count;
        }
}