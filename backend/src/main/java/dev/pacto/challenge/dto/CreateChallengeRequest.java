package dev.pacto.challenge.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record CreateChallengeRequest(
        UUID inviteeId,
        String title,
        String description,
        BigDecimal stakePerParticipant) {

}
