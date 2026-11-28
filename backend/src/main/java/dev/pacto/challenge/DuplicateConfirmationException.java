package dev.pacto.challenge;

import java.util.UUID;

public class DuplicateConfirmationException
        extends RuntimeException {

    public DuplicateConfirmationException(
            UUID challengeId,
            UUID participantId) {
        super(
                "Participant " + participantId
                        + " has already confirmed challenge "
                        + challengeId);
    }
}