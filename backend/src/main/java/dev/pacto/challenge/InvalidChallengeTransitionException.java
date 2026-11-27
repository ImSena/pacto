package dev.pacto.challenge;

public class InvalidChallengeTransitionException
        extends RuntimeException {

    public InvalidChallengeTransitionException(
            ChallengeStatus current,
            ChallengeStatus target) {
        super("Cannot transtion challenge from " + current + " to " + target);
    }
}
