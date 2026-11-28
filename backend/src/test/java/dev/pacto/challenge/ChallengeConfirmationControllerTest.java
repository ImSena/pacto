package dev.pacto.challenge;

import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
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
class ChallengeConfirmationControllerTest {

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
                "Confirmation test",
                "Controller test",
                20L);

        ReflectionTestUtils.setField(
                challenge,
                "id",
                challengeId);

        challenge.accept(inviteeId);

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
                + "/confirm";
    }

    private String request(UUID winnerId) {
        return """
                {
                    "winnerId": "%s"
                }
                """.formatted(winnerId);
    }

    @Test
    void shouldRegisterFirstConfirmation() throws Exception {
        challenge.recordFirstConfirmation(
                creatorId,
                creatorId);

        when(challengeService.confirm(
                challengeId,
                creatorId,
                creatorId)).thenReturn(challenge);

        mockMvc.perform(
                post(endpoint())
                        .header("Idempotency-Key", "test-key-001")
                        .header("X-Actor-Id", creatorId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(creatorId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id")
                        .value(challengeId.toString()))
                .andExpect(jsonPath("$.status")
                        .value("AWAITING_RESULT"));

        verify(challengeService).confirm(
                challengeId,
                creatorId,
                creatorId);
    }

    @Test
    void shouldReturnSettledChallenge() throws Exception {
        challenge.recordFirstConfirmation(
                creatorId,
                creatorId);

        challenge.recordSecondConfirmation(
                inviteeId,
                creatorId,
                creatorId,
                creatorId);

        when(challengeService.confirm(
                challengeId,
                inviteeId,
                creatorId)).thenReturn(challenge);

        mockMvc.perform(
                post(endpoint())
                        .header("Idempotency-Key", "test-key-001")
                        .header("X-Actor-Id", inviteeId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(creatorId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status")
                        .value("SETTLED"))
                .andExpect(jsonPath("$.settledAt")
                        .isNotEmpty());

        verify(challengeService).confirm(
                challengeId,
                inviteeId,
                creatorId);
    }

    @Test
    void shouldReturnDisputedChallenge() throws Exception {
        challenge.recordFirstConfirmation(
                creatorId,
                creatorId);

        challenge.recordSecondConfirmation(
                inviteeId,
                inviteeId,
                creatorId,
                creatorId);

        when(challengeService.confirm(
                challengeId,
                inviteeId,
                inviteeId)).thenReturn(challenge);

        mockMvc.perform(
                post(endpoint())
                        .header("Idempotency-Key", "test-key-001")
                        .header("X-Actor-Id", inviteeId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(inviteeId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status")
                        .value("DISPUTED"));

        verify(challengeService).confirm(
                challengeId,
                inviteeId,
                inviteeId);
    }

    @Test
    void shouldRejectMissingWinner() throws Exception {
        mockMvc.perform(
                post(endpoint())
                        .header("Idempotency-Key", "test-key-001")
                        .header("X-Actor-Id", creatorId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(challengeService, idempotencyService);
    }

    @Test
    void shouldRejectInvalidWinnerUuid() throws Exception {
        mockMvc.perform(
                post(endpoint())
                        .header("Idempotency-Key", "test-key-001")
                        .header("X-Actor-Id", creatorId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "winnerId": "invalid-uuid"
                                }
                                """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(challengeService, idempotencyService);
    }

    @Test
    void shouldRejectMissingActorHeader() throws Exception {
        mockMvc.perform(
                post(endpoint())
                        .header("Idempotency-Key", "test-key-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(creatorId)))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(challengeService, idempotencyService);
    }

    @Test
    void shouldRejectOutsider() throws Exception {
        UUID outsiderId = UUID.randomUUID();

        when(challengeService.confirm(
                challengeId,
                outsiderId,
                creatorId)).thenThrow(
                        new InvalidChallengeActorException(
                                "Only challenge participants can confirm results"));

        mockMvc.perform(
                post(endpoint())
                        .header("Idempotency-Key", "test-key-001")
                        .header("X-Actor-Id", outsiderId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(creatorId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status")
                        .value(403));
    }

    @Test
    void shouldRejectWinnerOutsideChallenge() throws Exception {
        UUID invalidWinnerId = UUID.randomUUID();

        when(challengeService.confirm(
                challengeId,
                creatorId,
                invalidWinnerId)).thenThrow(
                        new InvalidChallengeWinnerException());

        mockMvc.perform(
                post(endpoint())
                        .header("Idempotency-Key", "test-key-001")
                        .header("X-Actor-Id", creatorId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(invalidWinnerId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status")
                        .value(400));
    }

    @Test
    void shouldRejectDuplicateConfirmation() throws Exception {
        when(challengeService.confirm(
                challengeId,
                creatorId,
                creatorId)).thenThrow(
                        new DuplicateConfirmationException(
                                challengeId,
                                creatorId));

        mockMvc.perform(
                post(endpoint())
                        .header("Idempotency-Key", "new-command-key")
                        .header("X-Actor-Id", creatorId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(creatorId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status")
                        .value(409));
    }

    @Test
    void shouldRejectUnknownChallenge() throws Exception {
        when(challengeService.confirm(
                challengeId,
                creatorId,
                creatorId)).thenThrow(
                        new ChallengeNotFoundException(challengeId));

        mockMvc.perform(
                post(endpoint())
                        .header("Idempotency-Key", "test-key-001")
                        .header("X-Actor-Id", creatorId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(creatorId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status")
                        .value(404));
    }

    @Test
    void shouldRejectInvalidStateTransition() throws Exception {
        when(challengeService.confirm(
                challengeId,
                creatorId,
                creatorId)).thenThrow(
                        new InvalidChallengeTransitionException(
                                ChallengeStatus.OPEN,
                                ChallengeStatus.AWAITING_RESULT));

        mockMvc.perform(
                post(endpoint())
                        .header("Idempotency-Key", "test-key-001")
                        .header("X-Actor-Id", creatorId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(creatorId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status")
                        .value(409));
    }
}