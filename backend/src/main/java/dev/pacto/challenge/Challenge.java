package dev.pacto.challenge;

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
import jakarta.persistence.Version;

@Entity
@Table(name = "challenges")
public class Challenge {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "creator_id", nullable = false, updatable = false)
    private UUID creatorId;

    @Column(name = "invitee_id", nullable = false, updatable = false)
    private UUID inviteeId;

    @Column(nullable = false, length = 160, updatable = false)
    private String title;

    @Column(columnDefinition = "TEXT", updatable = false)
    private String description;

    @Column(name = "stake_per_participant", nullable = false, updatable = false)
    private long stakePerParticipant;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ChallengeStatus status;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Column(name = "settled_at")
    private Instant settledAt;

    protected Challenge() {

    }

    public Challenge(
            UUID creatorId,
            UUID inviteeId,
            String title,
            String description,
            long stakePerParticipant) {
        Objects.requireNonNull(creatorId, "Creator Id is required");
        Objects.requireNonNull(inviteeId, "Invitee Id is required");

        if (creatorId.equals(inviteeId)) {
            throw new IllegalArgumentException(
                    "Creator and invitee must be different");
        }

        if (title == null || title.isBlank() || title.length() > 160) {
            throw new IllegalArgumentException(
                    "Title must contain between 1 and 160 characters");
        }

        if (stakePerParticipant <= 0) {
            throw new IllegalArgumentException(
                    "Stake per participant must be positive");
        }

        this.creatorId = creatorId;
        this.inviteeId = inviteeId;
        this.title = title.strip();
        this.description = description;
        this.stakePerParticipant = stakePerParticipant;
        this.status = ChallengeStatus.OPEN;
        this.createdAt = Instant.now();
    }

    void accept(UUID actorId) {
        if (!inviteeId.equals(actorId)) {
            throw new InvalidChallengeActorException(
                    "Only the invitee can accept the challenge");
        }

        transitionTo(ChallengeStatus.ACCEPTED);

        this.acceptedAt = Instant.now();
    }

    void cancel(UUID actorId) {
        if (!creatorId.equals(actorId)) {
            throw new InvalidChallengeActorException(
                    "Only the creator can cancel the challenge");
        }

        transitionTo(ChallengeStatus.CANCELLED);
    }

    public boolean isParticipant(UUID userId) {
        return creatorId.equals(userId) || inviteeId.equals(userId);
    }

    private void validateParticipant(UUID actorId) {
        if (!isParticipant(actorId)) {
            throw new InvalidChallengeActorException(
                    "Only challenge participants can confirm results");
        }
    }

    private void transitionTo(ChallengeStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new InvalidChallengeTransitionException(status, target);
        }

        this.status = target;
    }

    private void validateWinner(UUID winnerId) {
        if (!isParticipant(winnerId)) {
            throw new InvalidChallengeWinnerException();
        }
    }

    void recordFirstConfirmation(
            UUID actorId,
            UUID winnerId) {
        validateParticipant(actorId);
        validateWinner(winnerId);

        transitionTo(ChallengeStatus.AWAITING_RESULT);
    }

    void recordSecondConfirmation(
            UUID actorId,
            UUID winnerId,
            UUID firstParticipantId,
            UUID firstWinnerId) {
        validateParticipant(actorId);
        validateParticipant(firstParticipantId);

        validateWinner(winnerId);
        validateWinner(firstWinnerId);

        if (actorId.equals(firstParticipantId)) {
            throw new InvalidChallengeActorException(
                    "Participant has already confirmed the result");
        }

        if (winnerId.equals(firstWinnerId)) {
            transitionTo(ChallengeStatus.SETTLED);

            this.settledAt = Instant.now();
        } else {
            transitionTo(ChallengeStatus.DISPUTED);
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getCreatorId() {
        return creatorId;
    }

    public UUID getInviteeId() {
        return inviteeId;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public long getStakePerParticipant() {
        return stakePerParticipant;
    }

    public ChallengeStatus getStatus() {
        return status;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getAcceptedAt() {
        return acceptedAt;
    }

    public Instant getSettledAt() {
        return settledAt;
    }
}
