package dev.pacto.idempotency.repository;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import dev.pacto.idempotency.IdempotencyOperation;
import dev.pacto.idempotency.IdempotencyRecord;
import dev.pacto.idempotency.IdempotencyState;

import javax.sql.DataSource;

import org.springframework.jdbc.support.SQLErrorCodeSQLExceptionTranslator;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class IdempotencyRecordRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<IdempotencyRecord> ROW_MAPPER = (rs, rowNum) -> {

        OffsetDateTime completedAt = rs.getObject(
                "completed_at",
                OffsetDateTime.class);

        return new IdempotencyRecord(
                rs.getObject("id", UUID.class),
                rs.getObject("actor_id", UUID.class),
                IdempotencyOperation.valueOf(
                        rs.getString("operation")),
                rs.getString("idempotency_key"),
                rs.getString("payload_hash"),
                IdempotencyState.valueOf(
                        rs.getString("state")),
                rs.getObject("http_status", Integer.class),
                rs.getString("response_body"),
                rs.getObject(
                        "created_at",
                        OffsetDateTime.class).toInstant(),
                completedAt == null
                        ? null
                        : completedAt.toInstant());
    };

    public IdempotencyRecordRepository(DataSource dataSource) {

        this.jdbcTemplate = new JdbcTemplate(dataSource);

        this.jdbcTemplate.setExceptionTranslator(
                new SQLErrorCodeSQLExceptionTranslator(dataSource));
    }

    public boolean tryInsert(
            UUID actorId,
            IdempotencyOperation operation,
            String idempotencyKey,
            String payloadHash) {

        int affectedRows = jdbcTemplate.update(
                """
                        INSERT INTO idempotency_records (
                            actor_id,
                            operation,
                            idempotency_key,
                            payload_hash
                        )
                        VALUES (?, ?, ?, ?)
                        ON CONFLICT (
                            actor_id,
                            operation,
                            idempotency_key
                        )
                        DO NOTHING
                        """,
                actorId,
                operation.name(),
                idempotencyKey,
                payloadHash);

        return affectedRows == 1;
    }

    public Optional<IdempotencyRecord> findForUpdate(
            UUID actorId,
            IdempotencyOperation operation,
            String idempotencyKey) {

        return jdbcTemplate.query(
                """
                        SELECT
                            id,
                            actor_id,
                            operation,
                            idempotency_key,
                            payload_hash,
                            state,
                            http_status,
                            response_body::text AS response_body,
                            created_at,
                            completed_at
                        FROM idempotency_records
                        WHERE actor_id = ?
                          AND operation = ?
                          AND idempotency_key = ?
                        FOR UPDATE
                        """,
                ROW_MAPPER,
                actorId,
                operation.name(),
                idempotencyKey)
                .stream()
                .findFirst();
    }

    public void complete(
            UUID recordId,
            int httpStatus,
            String responseBody) {

        int affectedRows = jdbcTemplate.update(
                """
                        UPDATE idempotency_records
                        SET state = 'COMPLETED',
                            http_status = ?,
                            response_body = CAST(? AS JSONB),
                            completed_at = statement_timestamp()
                        WHERE id = ?
                          AND state = 'IN_PROGRESS'
                        """,
                httpStatus,
                responseBody,
                recordId);

        if (affectedRows != 1) {

            throw new IllegalStateException(
                    "Expected exactly one in-progress "
                            + "idempotency record to be completed");
        }
    }

    public void configureLockTimeout() {

        jdbcTemplate.execute(
                "SET LOCAL lock_timeout = '5s'");
    }
}