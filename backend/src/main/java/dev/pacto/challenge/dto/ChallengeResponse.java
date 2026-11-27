package dev.pacto.challenge.dto;

import dev.pacto.challenge.Challenge;

import java.time.Instant;
import java.util.UUID;

public record ChallengeResponse(
        UUID id,
        UUID creatorId,
        UUID inviteeId,
        String title,
        String description,
        long stakePerParticipant,
        String status,
        Instant createdAt,
        Instant acceptedAt,
        Instant settledAt) {

    public static ChallengeResponse from(Challenge challenge) {
        return new ChallengeResponse(
                challenge.getId(),
                challenge.getCreatorId(),
                challenge.getInviteeId(),
                challenge.getTitle(),
                challenge.getDescription(),
                challenge.getStakePerParticipant(),
                challenge.getStatus().name(),
                challenge.getCreatedAt(),
                challenge.getAcceptedAt(),
                challenge.getSettledAt());
    }
}