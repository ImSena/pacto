package dev.pacto.challenge;

import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import dev.pacto.challenge.controller.ChallengeController;
import dev.pacto.idempotency.IdempotencyOperation;
import dev.pacto.idempotency.IdempotencyResponse;
import dev.pacto.idempotency.IdempotencyService;

import tools.jackson.databind.json.JsonMapper;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class ChallengeCancellationControllerTest {

    @Mock
    private ChallengeService challengeService;

    @Mock
    private IdempotencyService idempotencyService;

    private MockMvc mockMvc;

    private UUID challengeId;
    private UUID creatorId;
    private UUID inviteeId;

    private Challenge challenge;

    @BeforeEach
    void setUp() {
        challengeId = UUID.randomUUID();
        creatorId = UUID.randomUUID();
        inviteeId = UUID.randomUUID();

        challenge = new Challenge(
                creatorId,
                inviteeId,
                "Cancellation test",
                "Controller cancellation test",
                20L);

        ReflectionTestUtils.setField(
                challenge,
                "id",
                challengeId);

        mockMvc = MockMvcBuilders
                .standaloneSetup(
                        new ChallengeController(
                                challengeService,
                                idempotencyService,
                                JsonMapper.builder().build()))
                .build();

        lenient().when(idempotencyService.execute(
                any(UUID.class),
                any(IdempotencyOperation.class),
                anyString(),
                anyString(),
                any()))
                .thenAnswer(invocation -> {
                    Supplier<IdempotencyResponse> command =
                            invocation.getArgument(4);
                    return command.get();
                });
    }

    private String endpoint() {
        return "/api/challenges/"
                + challengeId
                + "/cancel";
    }

    @Test
    void shouldCancelChallenge() throws Exception {
        challenge.cancel(creatorId);

        when(challengeService.cancel(
                challengeId,
                creatorId)).thenReturn(challenge);

        mockMvc.perform(
                post(endpoint())
                        .header("Idempotency-Key", "test-key-001")
                        .header(
                                "X-Actor-Id",
                                creatorId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id")
                        .value(challengeId.toString()))
                .andExpect(jsonPath("$.status")
                        .value("CANCELLED"));

        verify(challengeService).cancel(
                challengeId,
                creatorId);
    }

    @Test
    void shouldRejectMissingActorHeader() throws Exception {
        mockMvc.perform(
                post(endpoint())
                        .header("Idempotency-Key", "test-key-001"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(challengeService, idempotencyService);
    }

    @Test
    void shouldRejectInvalidActorUuid() throws Exception {
        mockMvc.perform(
                post(endpoint())
                        .header("Idempotency-Key", "test-key-001")
                        .header(
                                "X-Actor-Id",
                                "invalid-uuid"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(challengeService, idempotencyService);
    }

    @Test
    void shouldRejectInvalidChallengeUuid() throws Exception {
        mockMvc.perform(
                post("/api/challenges/invalid-uuid/cancel")
                        .header("Idempotency-Key", "test-key-001")
                        .header(
                                "X-Actor-Id",
                                creatorId.toString()))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(challengeService, idempotencyService);
    }

    @Test
    void shouldRejectCancellationByInvitee() throws Exception {
        when(challengeService.cancel(
                challengeId,
                inviteeId)).thenThrow(
                        new InvalidChallengeActorException(
                                "Only the creator can cancel the challenge"));

        mockMvc.perform(
                post(endpoint())
                        .header("Idempotency-Key", "test-key-001")
                        .header(
                                "X-Actor-Id",
                                inviteeId.toString()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status")
                        .value(403));
    }

    @Test
    void shouldRejectUnknownChallenge() throws Exception {
        when(challengeService.cancel(
                challengeId,
                creatorId)).thenThrow(
                        new ChallengeNotFoundException(challengeId));

        mockMvc.perform(
                post(endpoint())
                        .header("Idempotency-Key", "test-key-001")
                        .header(
                                "X-Actor-Id",
                                creatorId.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status")
                        .value(404));
    }

    @Test
    void shouldRejectCancellationAfterAcceptance()
            throws Exception {

        when(challengeService.cancel(
                challengeId,
                creatorId)).thenThrow(
                        new InvalidChallengeTransitionException(
                                ChallengeStatus.ACCEPTED,
                                ChallengeStatus.CANCELLED));

        mockMvc.perform(
                post(endpoint())
                        .header("Idempotency-Key", "test-key-001")
                        .header(
                                "X-Actor-Id",
                                creatorId.toString()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status")
                        .value(409));
    }

    @Test
    void shouldRejectDuplicateCancellation()
            throws Exception {

        when(challengeService.cancel(
                challengeId,
                creatorId)).thenThrow(
                        new InvalidChallengeTransitionException(
                                ChallengeStatus.CANCELLED,
                                ChallengeStatus.CANCELLED));

        mockMvc.perform(
                post(endpoint())
                        .header("Idempotency-Key", "new-command-key")
                        .header(
                                "X-Actor-Id",
                                creatorId.toString()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status")
                        .value(409));
    }
}
