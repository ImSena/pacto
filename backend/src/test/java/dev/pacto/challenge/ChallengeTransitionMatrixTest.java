package dev.pacto.challenge;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class ChallengeTransitionMatrixTest {

    private record Transition(
            ChallengeStatus origin,
            ChallengeStatus destination) {
    }

    private static final Set<Transition> ALLOWED_TRANSITIONS = Set.of(
            new Transition(
                    ChallengeStatus.OPEN,
                    ChallengeStatus.ACCEPTED),
            new Transition(
                    ChallengeStatus.OPEN,
                    ChallengeStatus.CANCELLED),
            new Transition(
                    ChallengeStatus.ACCEPTED,
                    ChallengeStatus.AWAITING_RESULT),
            new Transition(
                    ChallengeStatus.AWAITING_RESULT,
                    ChallengeStatus.SETTLED),
            new Transition(
                    ChallengeStatus.AWAITING_RESULT,
                    ChallengeStatus.DISPUTED));

    private static Stream<Arguments> allTransitions() {
        return Arrays.stream(ChallengeStatus.values())
                .flatMap(origin -> Arrays.stream(ChallengeStatus.values())
                        .map(destination -> Arguments.of(
                                origin,
                                destination)));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("allTransitions")
    void shouldRespectTransitionMatrix(
            ChallengeStatus origin,
            ChallengeStatus destination) {
        boolean expected = ALLOWED_TRANSITIONS.contains(
                new Transition(origin, destination));

        assertEquals(
                expected,
                origin.canTransitionTo(destination),
                () -> "Unexpected transition: "
                        + origin
                        + " -> "
                        + destination);
    }

    @ParameterizedTest
    @EnumSource(ChallengeStatus.class)
    void shouldCorrectlyIdentifyTerminalStates(
            ChallengeStatus status) {
        boolean expected = Set.of(
                ChallengeStatus.SETTLED,
                ChallengeStatus.DISPUTED,
                ChallengeStatus.CANCELLED).contains(status);

        assertEquals(
                expected,
                status.isTerminal());
    }
}