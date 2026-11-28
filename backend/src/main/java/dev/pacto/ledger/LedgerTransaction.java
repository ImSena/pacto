package dev.pacto.ledger;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "ledger_transactions")
public class LedgerTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "business_operation_id", nullable = false, unique = true, updatable = false)
    private UUID businessOperationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 32, updatable = false)
    private LedgerTransactionKind kind;

    @Column(name = "challenge_id", updatable = false)
    private UUID challengeId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected LedgerTransaction() {
    }

    private LedgerTransaction(
            UUID businessOperationId,
            LedgerTransactionKind kind,
            UUID challengeId) {
        this.businessOperationId = Objects.requireNonNull(
                businessOperationId,
                "Business operation ID is required");

        this.kind = Objects.requireNonNull(
                kind,
                "Transaction kind is required");

        this.challengeId = challengeId;
        this.createdAt = Instant.now();
    }

    static LedgerTransaction initialGrant(
            UUID businessOperationId) {
        return new LedgerTransaction(
                businessOperationId,
                LedgerTransactionKind.INITIAL_GRANT,
                null);
    }

    static LedgerTransaction reserve(
            UUID businessOperationId,
            UUID challengeId) {
        Objects.requireNonNull(
                businessOperationId,
                "Business operation ID is required");

        Objects.requireNonNull(
                challengeId,
                "Challenge ID is required");

        return new LedgerTransaction(
                businessOperationId,
                LedgerTransactionKind.RESERVE,
                challengeId);
    }

    static LedgerTransaction settle(
            UUID businessOperationId,
            UUID challengeId) {
        Objects.requireNonNull(
                businessOperationId,
                "Business operation ID is required");

        Objects.requireNonNull(
                challengeId,
                "Challenge ID is required");

        return new LedgerTransaction(
                businessOperationId,
                LedgerTransactionKind.SETTLE,
                challengeId);
    }

    public UUID getId() {
        return id;
    }

    public UUID getBusinessOperationId() {
        return businessOperationId;
    }

    public LedgerTransactionKind getKind() {
        return kind;
    }

    public UUID getChallengeId() {
        return challengeId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}