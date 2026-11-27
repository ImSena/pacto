package dev.pacto.challenge;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static dev.pacto.challenge.ChallengeStatus.*;
import static org.junit.jupiter.api.Assertions.*;

class ChallengeStatusTest {

    @Test
    void shouldAllowOnlyValidTransitions() {
        Map<ChallengeStatus, Set<ChallengeStatus>> expected = Map.of(
                OPEN, Set.of(ACCEPTED, CANCELLED),
                ACCEPTED, Set.of(AWAITING_RESULT),
                AWAITING_RESULT, Set.of(SETTLED, DISPUTED),
                SETTLED, Set.of(),
                DISPUTED, Set.of(),
                CANCELLED, Set.of());

        for (ChallengeStatus current : ChallengeStatus.values()) {
            for (ChallengeStatus target : ChallengeStatus.values()) {
                assertEquals(
                        expected.get(current).contains(target),
                        current.canTransitionTo(target),
                        current + " -> " + target);
            }
        }
    }

    @Test
    void shouldIdentifyTerminalStatuses() {
        assertTrue(SETTLED.isTerminal());
        assertTrue(DISPUTED.isTerminal());
        assertTrue(CANCELLED.isTerminal());

        assertFalse(OPEN.isTerminal());
        assertFalse(ACCEPTED.isTerminal());
        assertFalse(AWAITING_RESULT.isTerminal());
    }
}