package dev.pacto.ledger;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

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