
# Persistent Idempotency

## Overview

PACTO implements persistent command idempotency to prevent
duplicate business effects caused by client retries, network
failures, and concurrent requests.

Idempotency records are stored in PostgreSQL and committed
within the same database transaction as the corresponding
business operation.

The implementation does not require Redis, distributed caches,
or application-level synchronization.

## Protected Endpoints

| Method | Endpoint | Success Status |
|---|---|---|
| POST | /api/challenges | 201 |
| POST | /api/challenges/{id}/accept | 200 |
| POST | /api/challenges/{id}/confirm | 200 |
| POST | /api/challenges/{id}/cancel | 200 |

All protected endpoints require:

- X-Actor-Id
- Idempotency-Key

Read-only endpoints are unaffected.

X-Actor-Id represents a simulated actor identity in the MVP.
It is not an authentication mechanism.

## Key Scope

An idempotency key is uniquely scoped by:

- Actor ID
- Operation
- Idempotency key

PostgreSQL enforces this uniqueness through a database
constraint.

The same key may be reused by different actors or for
different operations without causing a conflict.

Keys are case-sensitive, contain only visible ASCII
characters, and must contain between 1 and 255 characters.

## Payload Canonicalization

Commands are converted into a deterministic binary
representation before hashing.

The implementation uses SHA-256 and includes a format
version to identify the canonical representation.

Canonicalization ensures that semantically equivalent
valid commands produce the same hash.

Examples:

- Stakes represented as 20 and 20.0.
- Different JSON property ordering.
- Leading and trailing whitespace in valid challenge titles.

The hash includes the relevant command parameters, including
challenge and winner identifiers when applicable.

Null descriptions and empty descriptions remain distinct.

The format version must remain compatible with previously
persisted records when introducing future changes.

## Execution Flow

1. Validate the key, payload hash, and actor.
2. Configure the transaction-local lock timeout.
3. Attempt to insert the idempotency record using
   INSERT ON CONFLICT DO NOTHING.
4. Retrieve the record using SELECT FOR UPDATE.
5. Compare the stored payload hash with the incoming hash.
6. Replay the completed response if the key already exists.
7. Otherwise, execute the business command.
8. Serialize the successful response inside the transaction.
9. Mark the idempotency record as COMPLETED.
10. Commit the transaction.

Only the transaction that successfully inserts a new record
is allowed to execute the business command.

## Transactional Guarantees

The idempotency service defines the transactional boundary.

The following operations participate in the same transaction:

- Idempotency record acquisition.
- Challenge state transitions.
- Ledger operations.
- Response serialization.
- Idempotency record completion.

If the transaction fails, both the business effects and the
idempotency record are rolled back.

REQUIRES_NEW is intentionally avoided because independently
committing an idempotency record or a financial operation
could violate atomicity.

A committed IN_PROGRESS record is considered an unexpected
inconsistency and must not trigger automatic re-execution.

## Response Persistence Policy

The current implementation persists successful responses only.

Supported persisted HTTP statuses:

- 200 OK
- 201 Created

Validation failures, business rejections, and technical
failures are not persisted as completed responses.

When a command is rejected and its transaction rolls back,
the key remains available for a subsequent attempt.

Once a successful response is committed, the key is bound
to the original payload hash.

This policy is subject to final MVP approval.

## Response Replay

A completed idempotency record contains:

- Actor ID.
- Operation.
- Idempotency key.
- Payload hash.
- HTTP status.
- JSON response body.
- Creation timestamp.
- Completion timestamp.

A matching retry returns the original successful HTTP
status and the stored JSON response.

For challenge creation, the Location header is reconstructed
from the persisted challenge identifier.

JSON responses are stored as PostgreSQL JSONB. Replay
preserves the semantic JSON content, not necessarily the
original whitespace or property ordering.

A replay returns the historical command response, not the
current resource state.

For example, replaying a successful acceptance after the
challenge has been settled returns the original ACCEPTED
response without modifying the settled challenge.

## HTTP Contract

| Scenario | HTTP Status |
|---|---|
| Successful creation | 201 |
| Successful command | 200 |
| Matching retry | Original success status |
| Missing or invalid key | 400 |
| Unknown actor | 403 |
| Existing key with different payload | 409 |
| Handled transient lock or query timeout | 503 |
| Unexpected technical failure | 500 |

Handled transient failures include a Retry-After header
with a value of one second.

Clients should retry transient failures using the original
key and payload.

A lost HTTP response does not necessarily indicate a
failed database transaction. Retrying with the original
key allows the client to recover a committed result.

## Concurrency Control

The implementation uses PostgreSQL as the transactional
coordinator.

The idempotency transaction uses READ COMMITTED isolation.

Concurrent requests using the same key are coordinated by:

- A unique database constraint.
- INSERT ON CONFLICT DO NOTHING.
- SELECT FOR UPDATE.
- Transactional response persistence.

A transaction-local PostgreSQL lock timeout of five seconds
limits individual lock waits.

The Spring transaction timeout is configured to fifteen
seconds.

These settings do not constitute a strict end-to-end HTTP
request deadline.

Broader concurrency control across different idempotency
keys is handled separately by challenge and account locking.

## Persistence Technology

PACTO uses Spring Data JPA for domain persistence and
JdbcTemplate for idempotency records.

JdbcTemplate was selected because the idempotency workflow
requires explicit control over PostgreSQL-specific SQL,
statement execution, row locking, and JSONB persistence.

Both technologies use the same Spring-managed DataSource
and participate in the same transaction.

This allows SQL-level control without sacrificing
transactional atomicity.

## SQL Exception Translation

The default JDBC exception translation produced an
UncategorizedSQLException when PostgreSQL returned
SQLSTATE 55P03 during a lock timeout.

This initially resulted in HTTP 500 instead of HTTP 503.

The idempotency repository therefore configures a dedicated
JdbcTemplate using SQLErrorCodeSQLExceptionTranslator.

This enables database-specific exception translation,
including CannotAcquireLockException for PostgreSQL lock
acquisition failures.

The existing HTTP exception handler maps the corresponding
locking exception to HTTP 503 with Retry-After.

The dedicated JdbcTemplate continues to use the same
Spring-managed DataSource.

## Testing Strategy

The implementation is covered by:

- Idempotency key validation tests.
- Payload hashing tests.
- Repository integration tests.
- Transactional orchestration tests.
- MVC contract tests.
- PostgreSQL and HTTP integration tests.

Integration tests exercise:

- Persistent response replay.
- Semantically equivalent payloads.
- Payload conflicts.
- Actor and operation scoping.
- Duplicate financial operation prevention.
- Concurrent requests with the same key.
- Database lock timeouts.
- Retry after rollback.
- Commit-time failures.
- Ledger integrity and balance conservation.

PostgreSQL integration tests use isolated Testcontainers
databases.

Financial invariants are verified independently through SQL.

## Known Limitations

- Actor identity is simulated for the MVP.
- Idempotency records do not currently expire.
- Failed responses are not persisted.
- Response replay preserves semantic JSON, not exact bytes.
- Lock and transaction timeouts are currently fixed values.
- External side effects are outside the transactional
  guarantees of this implementation.
- Idempotency does not replace business-level concurrency
  control across different keys.
- Application restart replay and broader concurrency stress
  testing require additional validation.

## Future Work

- Explicit challenge and account locking (PCT-016).
- High-contention concurrency testing (PCT-017).
- Expanded retry and ledger integrity testing (PCT-018).
- Frontend retry handling.
- Configurable timeout policies.
- Idempotency record retention strategy.
