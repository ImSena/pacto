package dev.pacto.challenge;

public class InvalidChallengeWinnerException
        extends RuntimeException {

    public InvalidChallengeWinnerException() {
        super("Winner must be a challenge participant");
    }
}