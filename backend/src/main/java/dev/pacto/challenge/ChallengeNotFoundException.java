package dev.pacto.challenge;

import java.util.UUID;

public class ChallengeNotFoundException
        extends RuntimeException {

    public ChallengeNotFoundException(UUID challengeId) {
        super("Challenge not found: " + challengeId);
    }
}