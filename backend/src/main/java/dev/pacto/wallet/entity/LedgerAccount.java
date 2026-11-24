package dev.pacto.wallet.entity;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "ledger_accounts")
public class LedgerAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 32, updatable = false)
    private LedgerAccountType type;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "wallet_id", updatable = false)
    private Wallet wallet;

    @Column(name = "challenge_id", updatable = false)
    private UUID challengeId;

    @Column(name = "balance", nullable = false)
    private long balance;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected LedgerAccount() {
    }

    private LedgerAccount(
            LedgerAccountType type,
            Wallet wallet,
            UUID challengeId) {
        this.type = Objects.requireNonNull(type);
        this.wallet = wallet;
        this.challengeId = challengeId;
        this.balance = 0L;
        this.createdAt = Instant.now();
    }

    public static LedgerAccount available(Wallet wallet) {
        Objects.requireNonNull(
                wallet, "Available account requires a wallet");

        return new LedgerAccount(
                LedgerAccountType.USER_AVAILABLE,
                wallet,
                null);
    }

    public static LedgerAccount systemIssuance() {
        return new LedgerAccount(
                LedgerAccountType.SYSTEM_ISSUANCE,
                null,
                null);
    }

    public UUID getId() {
        return id;
    }

    public LedgerAccountType getType() {
        return type;
    }

    public Wallet getWallet() {
        return wallet;
    }

    public UUID getChallengeId() {
        return challengeId;
    }

    public long getBalance() {
        return balance;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void applyDelta(long delta) {

        if (delta == 0L) {
            throw new IllegalArgumentException(
                    "Delta must not be zero");
        }

        long newBalance = Math.addExact(this.balance, delta);

        if (this.type != LedgerAccountType.SYSTEM_ISSUANCE
                && newBalance < 0L) {

            throw new IllegalStateException(
                    "Insufficient balance");
        }

        this.balance = newBalance;
    }
}