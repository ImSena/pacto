# PACTO — Testing Strategy

## Overview

PACTO uses automated tests to validate financial
invariants, accounting consistency and transaction
atomicity.

The testing strategy combines domain unit tests,
service unit tests and PostgreSQL integration tests.

## Technology

- JUnit Jupiter
- Mockito
- Spring Boot Test
- Testcontainers
- PostgreSQL 17
- Maven Surefire

## Test suites

| Test class            | Type        | Tests |
| --------------------- | ----------- | ----: |
| LedgerAccountTest     | Unit        |     9 |
| LedgerPostingLineTest | Unit        |     4 |
| LedgerServiceTest     | Unit        |    10 |
| LedgerIntegrationTest | Integration |     6 |
| LedgerRollbackTest    | Integration |     1 |
| Total                 |             |    30 |
| Test class            | Type        | Tests |
| --------------------- | ----------- | ----: |
| LedgerAccountTest     | Unit        |     9 |
| LedgerPostingLineTest | Unit        |     4 |
| LedgerServiceTest     | Unit        |    10 |
| LedgerIntegrationTest | Integration |     6 |
| LedgerRollbackTest    | Integration |     1 |
| Total                 |             |    30 |

## Domain unit tests

Validate:

- Credits and debits.
- Insufficient balance.
- Zero-value postings.
- Numeric overflow and underflow.
- Account balance preservation after failures.
- System issuance account behavior.

These tests do not require Spring or PostgreSQL.

## Service unit tests

Mockito isolates repository dependencies.

Tests validate:

- Balanced accounting transactions.
- Valid initial grants.
- Invalid grant amounts.
- Duplicate accounts.
- Missing accounts.
- Invalid account types and posting signs.
- Duplicate initial grants.
- Early rejection without repository interactions.

## Integration tests

Integration tests use real PostgreSQL instances
managed by Testcontainers.

They validate:

- Flyway migrations and database initialization.
- Demo user creation.
- Ledger entry persistence.
- Accounting reconciliation.
- Credit conservation.
- Sequential duplicate grant rejection.

Development databases are not used by these
financial integration tests.

## Transaction rollback

LedgerRollbackTest creates a dedicated PostgreSQL
container and installs a test-only deferred
constraint trigger.

Before raising an intentional commit failure,
the trigger verifies that the expected accounting
entries and balance changes exist.

After the failure, the test verifies that:

- Original balances are preserved.
- No additional ledger transactions remain.
- No additional ledger entries remain.
- The failed business operation is not persisted.

The trigger is not part of production migrations.

## Running tests

Run from the backend directory.

### Domain and service tests

```bash
./mvnw \
-Dtest=LedgerAccountTest,LedgerPostingLineTest,LedgerServiceTest \
test
```

### Financial integration tests

Docker must be available.

```bash
./mvnw \
-Dtest=LedgerIntegrationTest,LedgerRollbackTest \
test
```

### Complete financial suite

```bash
./mvnw \
-Dtest=LedgerAccountTest,LedgerPostingLineTest,LedgerServiceTest,LedgerIntegrationTest,LedgerRollbackTest \
test
```

### Full project test suite

```bash
./mvnw clean test
```

## Validation baseline

PCT-010 financial test execution:

- Tests: 30
- Failures: 0
- Errors: 0
- Skipped: 0
- Build: SUCCESS

## Current limitations

- Concurrent financial operations are not yet tested.
- Persistent HTTP idempotency is not yet implemented.
- Challenge reservation and settlement operations
  are not yet implemented.
- Database-level ledger immutability is not enforced.
- Debit behavior is tested at the domain level.
- The rollback test covers initial grant operations.

These limitations are addressed according to the
project roadmap or evaluated as future hardening.

## Challenge domain tests

ChallengeStatusTest validates:

- All 36 state transition combinations.
- The five allowed state transitions.
- Rejection of the 31 invalid transitions.
- Terminal state identification.

ChallengeTest validates:

- Challenge creation and initial state.
- Participant and stake validation.
- Acceptance and cancellation rules.
- First and second result confirmations.
- Winner validation.
- Duplicate confirmation rejection.
- Settlement and dispute state transitions.
- State preservation after invalid operations.

These tests run without Spring, PostgreSQL or Docker.

Financial reservation, confirmation persistence and
settlement atomicity are not covered by these tests.

## Challenge resolution integration tests

ChallengeResolutionIntegrationTest validates:

- First confirmation without financial effects.
- Settlement for either participant.
- Escrow preservation during disputes.
- Confirmation immutability.
- Sequential duplicate settlement prevention.
- Escrow isolation between challenges.
- Full rollback after an intentional commit failure.
- Successful retry after rollback.
- Mandatory transaction propagation.
- Ledger reconciliation and credit conservation.

The intentional commit failure uses a test-only
deferred PostgreSQL constraint trigger.

No production migrations are modified.
