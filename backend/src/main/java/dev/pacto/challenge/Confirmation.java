package dev.pacto.challenge;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Immutable
@Table(name = "confirmations")
public class Confirmation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "challenge_id", nullable = false, updatable = false)
    private UUID challengeId;

    @Column(name = "participant_id", nullable = false, updatable = false)
    private UUID participantId;

    @Column(name = "winner_id", nullable = false, updatable = false)
    private UUID winnerId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Confirmation() {
    }

    Confirmation(
            UUID challengeId,
            UUID participantId,
            UUID winnerId) {
        this.challengeId = Objects.requireNonNull(
                challengeId,
                "Challenge ID is required");

        this.participantId = Objects.requireNonNull(
                participantId,
                "Participant ID is required");

        this.winnerId = Objects.requireNonNull(
                winnerId,
                "Winner ID is required");

        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getChallengeId() {
        return challengeId;
    }

    public UUID getParticipantId() {
        return participantId;
    }

    public UUID getWinnerId() {
        return winnerId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}