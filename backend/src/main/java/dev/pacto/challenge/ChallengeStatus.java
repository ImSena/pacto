package dev.pacto.challenge;

public enum ChallengeStatus {
    OPEN,
    ACCEPTED,
    AWAITING_RESULT,
    SETTLED,
    DISPUTED,
    CANCELLED;

    public boolean isTerminal() {
        return switch (this) {
            case SETTLED, DISPUTED, CANCELLED -> true;
            default -> false;
        };
    }

    public boolean canTransitionTo(ChallengeStatus target) {
        return switch (this) {
            case OPEN ->
                target == ACCEPTED || target == CANCELLED;
            case ACCEPTED ->
                target == AWAITING_RESULT;
            case AWAITING_RESULT ->
                target == SETTLED || target == DISPUTED;
            case SETTLED, DISPUTED, CANCELLED -> false;
        };
    }
}
