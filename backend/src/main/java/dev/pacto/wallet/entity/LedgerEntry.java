package dev.pacto.wallet.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Immutable
@Table(name = "ledger_entries")
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "transaction_id", nullable = false, updatable = false)
    private LedgerTransaction transaction;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false, updatable = false)
    private LedgerAccount account;

    @Column(name = "delta", nullable = false, updatable = false)
    private long delta;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected LedgerEntry() {
    }

    public LedgerEntry(
            LedgerTransaction transaction,
            LedgerAccount account,
            long delta) {
        this.transaction = Objects.requireNonNull(
                transaction,
                "Ledger transaction is required");

        this.account = Objects.requireNonNull(
                account,
                "Ledger account is required");

        if (delta == 0L) {
            throw new IllegalArgumentException(
                    "Ledger entry delta must not be zero");
        }

        this.delta = delta;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public LedgerTransaction getTransaction() {
        return transaction;
    }

    public LedgerAccount getAccount() {
        return account;
    }

    public long getDelta() {
        return delta;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}