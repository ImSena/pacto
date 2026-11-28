package dev.pacto.challenge.dto;

import java.util.UUID;

public record ConfirmChallengeRequest(
        UUID winnerId) {
}