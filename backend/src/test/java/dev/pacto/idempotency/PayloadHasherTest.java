package dev.pacto.idempotency;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PayloadHasherTest {

    private static final UUID CHALLENGE_A = UUID.fromString(
            "00000000-0000-0000-0000-000000000001");

    private static final UUID CHALLENGE_B = UUID.fromString(
            "00000000-0000-0000-0000-000000000002");

    private static final UUID USER_A = UUID.fromString(
            "00000000-0000-0000-0000-000000000003");

    private static final UUID USER_B = UUID.fromString(
            "00000000-0000-0000-0000-000000000004");

    @Test
    void shouldGenerateValidSha256HexadecimalHash() {

        String hash = PayloadHasher.hashCreate(
                USER_A,
                "Football",
                "Sunday match",
                20L);

        assertEquals(64, hash.length());

        assertTrue(hash.matches("^[0-9a-f]{64}$"));
    }

    @Test
    void shouldPreserveCanonicalFormat() {

        String hash = PayloadHasher.hashAccept(CHALLENGE_A);

        assertEquals(
                "e163894c5e0798a306a33f2d67ad4058"
                        + "d9e22ca5606253655c0df77d3b53a82c",
                hash);
    }

    @Test
    void shouldGenerateSameHashForIdenticalPayload() {

        String first = PayloadHasher.hashCreate(
                USER_A, "Football", "Sunday match", 20L);

        String second = PayloadHasher.hashCreate(
                USER_A, "Football", "Sunday match", 20L);

        assertEquals(first, second);
    }

    @Test
    void shouldNormalizeTitleWhitespace() {

        String first = PayloadHasher.hashCreate(
                USER_A, "Football", null, 20L);

        String second = PayloadHasher.hashCreate(
                USER_A, "  Football  ", null, 20L);

        assertEquals(first, second);
    }

    @Test
    void shouldPreserveTitleCase() {

        String first = PayloadHasher.hashCreate(
                USER_A, "Football", null, 20L);

        String second = PayloadHasher.hashCreate(
                USER_A, "football", null, 20L);

        assertNotEquals(first, second);
    }

    @Test
    void shouldPreserveDescriptionWhitespace() {

        String first = PayloadHasher.hashCreate(
                USER_A, "Football", "Sunday match", 20L);

        String second = PayloadHasher.hashCreate(
                USER_A, "Football", " Sunday match ", 20L);

        assertNotEquals(first, second);
    }

    @Test
    void shouldDistinguishNullAndEmptyDescription() {

        String first = PayloadHasher.hashCreate(
                USER_A, "Football", null, 20L);

        String second = PayloadHasher.hashCreate(
                USER_A, "Football", "", 20L);

        assertNotEquals(first, second);
    }

    @Test
    void shouldDistinguishNullAndLiteralNullDescription() {

        String first = PayloadHasher.hashCreate(
                USER_A, "Football", null, 20L);

        String second = PayloadHasher.hashCreate(
                USER_A, "Football", "null", 20L);

        assertNotEquals(first, second);
    }

    @Test
    void shouldPreventAmbiguousFieldConcatenation() {

        String first = PayloadHasher.hashCreate(
                USER_A, "ab", "c", 20L);

        String second = PayloadHasher.hashCreate(
                USER_A, "a", "bc", 20L);

        assertNotEquals(first, second);
    }

    @Test
    void shouldGenerateDifferentHashWhenStakeChanges() {

        String first = PayloadHasher.hashCreate(
                USER_A, "Football", null, 20L);

        String second = PayloadHasher.hashCreate(
                USER_A, "Football", null, 50L);

        assertNotEquals(first, second);
    }

    @Test
    void shouldGenerateDifferentHashWhenInviteeChanges() {

        String first = PayloadHasher.hashCreate(
                USER_A, "Football", null, 20L);

        String second = PayloadHasher.hashCreate(
                USER_B, "Football", null, 20L);

        assertNotEquals(first, second);
    }

    @Test
    void shouldGenerateDifferentAcceptHashWhenChallengeChanges() {

        String first = PayloadHasher.hashAccept(CHALLENGE_A);

        String second = PayloadHasher.hashAccept(CHALLENGE_B);

        assertNotEquals(first, second);
    }

    @Test
    void shouldGenerateDifferentCancelHashWhenChallengeChanges() {

        String first = PayloadHasher.hashCancel(CHALLENGE_A);

        String second = PayloadHasher.hashCancel(CHALLENGE_B);

        assertNotEquals(first, second);
    }

    @Test
    void shouldGenerateDifferentConfirmHashWhenWinnerChanges() {

        String first = PayloadHasher.hashConfirm(
                CHALLENGE_A, USER_A);

        String second = PayloadHasher.hashConfirm(
                CHALLENGE_A, USER_B);

        assertNotEquals(first, second);
    }

    @Test
    void shouldGenerateDifferentConfirmHashWhenChallengeChanges() {

        String first = PayloadHasher.hashConfirm(
                CHALLENGE_A, USER_A);

        String second = PayloadHasher.hashConfirm(
                CHALLENGE_B, USER_A);

        assertNotEquals(first, second);
    }

    @Test
    void shouldDistinguishDifferentOperations() {

        String accept = PayloadHasher.hashAccept(CHALLENGE_A);

        String cancel = PayloadHasher.hashCancel(CHALLENGE_A);

        assertNotEquals(accept, cancel);
    }

    @Test
    void shouldHandleUnicodeAndSpecialCharacters() {

        String first = PayloadHasher.hashCreate(
                USER_A,
                "Desafio de futebol",
                "Descrição: sábado, \"partida\"\nSegunda linha",
                20L);

        String second = PayloadHasher.hashCreate(
                USER_A,
                "Desafio de futebol",
                "Descrição: sábado, \"partida\"\nSegunda linha",
                20L);

        String third = PayloadHasher.hashCreate(
                USER_A,
                "Desafio de futebol",
                "Descrição: sábado, \"partida\"\nTerceira linha",
                20L);

        assertEquals(first, second);
        assertNotEquals(first, third);
    }
}