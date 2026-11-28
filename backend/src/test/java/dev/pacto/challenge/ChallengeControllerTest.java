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
import dev.pacto.ledger.InsufficientBalanceException;

import tools.jackson.databind.json.JsonMapper;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class ChallengeControllerTest {

        @Mock
        private ChallengeService challengeService;

        @Mock
        private IdempotencyService idempotencyService;

        private MockMvc mockMvc;

        private UUID creatorId;
        private UUID inviteeId;
        private UUID challengeId;

        private Challenge challenge;

        @BeforeEach
        void setUp() {
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
                                        Supplier<IdempotencyResponse> command = invocation.getArgument(4);
                                        return command.get();
                                });

                creatorId = UUID.randomUUID();
                inviteeId = UUID.randomUUID();
                challengeId = UUID.randomUUID();

                challenge = new Challenge(
                                creatorId,
                                inviteeId,
                                "Demo challenge",
                                "Description",
                                20L);

                ReflectionTestUtils.setField(
                                challenge,
                                "id",
                                challengeId);
        }

        private String createRequest(long stake) {
                return createRequestWithStake(Long.toString(stake));
        }

        private String createRequestWithStake(String stake) {
                return """
                                {
                                  "inviteeId": "%s",
                                  "title": "Demo challenge",
                                  "description": "Description",
                                  "stakePerParticipant": %s
                                }
                                """.formatted(inviteeId, stake);
        }

        @Test
        void shouldCreateChallenge() throws Exception {
                when(challengeService.create(
                                creatorId,
                                inviteeId,
                                "Demo challenge",
                                "Description",
                                20L)).thenReturn(challenge);

                mockMvc.perform(
                                post("/api/challenges")
                                                .header("Idempotency-Key", "test-key-001")
                                                .header("X-Actor-Id", creatorId.toString())
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(createRequest(20L)))
                                .andExpect(status().isCreated())
                                .andExpect(header().string(
                                                "Location",
                                                "/api/challenges/" + challengeId))
                                .andExpect(jsonPath("$.id")
                                                .value(challengeId.toString()))
                                .andExpect(jsonPath("$.status")
                                                .value("OPEN"))
                                .andExpect(jsonPath("$.stakePerParticipant")
                                                .value(20))
                                .andExpect(jsonPath("$.creatorId")
                                                .value(creatorId.toString()));

                verify(challengeService).create(
                                creatorId,
                                inviteeId,
                                "Demo challenge",
                                "Description",
                                20L);
        }

        @Test
        void shouldAcceptChallenge() throws Exception {
                challenge.accept(inviteeId);

                when(challengeService.accept(
                                challengeId,
                                inviteeId)).thenReturn(challenge);

                mockMvc.perform(
                                post("/api/challenges/{id}/accept", challengeId)
                                                .header("Idempotency-Key", "test-key-001")
                                                .header("X-Actor-Id", inviteeId.toString()))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.status")
                                                .value("ACCEPTED"))
                                .andExpect(jsonPath("$.acceptedAt")
                                                .isNotEmpty());
        }

        @Test
        void shouldRejectMissingActorHeader() throws Exception {
                mockMvc.perform(
                                post("/api/challenges")
                                                .header("Idempotency-Key", "test-key-001")
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(createRequest(20L)))
                                .andExpect(status().isBadRequest());

                verifyNoInteractions(challengeService, idempotencyService);
        }

        @Test
        void shouldRejectInvalidStake() throws Exception {
                mockMvc.perform(
                                post("/api/challenges")
                                                .header("Idempotency-Key", "test-key-001")
                                                .header("X-Actor-Id", creatorId.toString())
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(createRequest(0L)))
                                .andExpect(status().isBadRequest());

                verifyNoInteractions(challengeService, idempotencyService);
        }

        @Test
        void shouldReturnForbiddenForInvalidActor() throws Exception {
                when(challengeService.accept(
                                challengeId,
                                creatorId)).thenThrow(
                                                new InvalidChallengeActorException(
                                                                "Only the invitee can accept the challenge"));

                mockMvc.perform(
                                post("/api/challenges/{id}/accept", challengeId)
                                                .header("Idempotency-Key", "test-key-001")
                                                .header("X-Actor-Id", creatorId.toString()))
                                .andExpect(status().isForbidden())
                                .andExpect(jsonPath("$.status").value(403));
        }

        @Test
        void shouldReturnConflictForInvalidTransition()
                        throws Exception {

                when(challengeService.accept(
                                challengeId,
                                inviteeId)).thenThrow(
                                                new InvalidChallengeTransitionException(
                                                                ChallengeStatus.ACCEPTED,
                                                                ChallengeStatus.ACCEPTED));

                mockMvc.perform(
                                post("/api/challenges/{id}/accept", challengeId)
                                                .header("Idempotency-Key", "test-key-001")
                                                .header("X-Actor-Id", inviteeId.toString()))
                                .andExpect(status().isConflict())
                                .andExpect(jsonPath("$.status").value(409));
        }

        @Test
        void shouldReturnConflictForInsufficientBalance()
                        throws Exception {

                when(challengeService.accept(
                                challengeId,
                                inviteeId)).thenThrow(
                                                new InsufficientBalanceException(
                                                                UUID.randomUUID(),
                                                                10L,
                                                                -20L));

                mockMvc.perform(
                                post("/api/challenges/{id}/accept", challengeId)
                                                .header("Idempotency-Key", "test-key-001")
                                                .header("X-Actor-Id", inviteeId.toString()))
                                .andExpect(status().isConflict())
                                .andExpect(jsonPath("$.status").value(409))
                                .andExpect(jsonPath("$.detail").value(
                                                "Insufficient available balance to accept challenge"));
        }

        @Test
        void shouldReturnNotFoundForUnknownChallenge()
                        throws Exception {

                when(challengeService.accept(
                                challengeId,
                                inviteeId)).thenThrow(
                                                new ChallengeNotFoundException(challengeId));

                mockMvc.perform(
                                post("/api/challenges/{id}/accept", challengeId)
                                                .header("Idempotency-Key", "test-key-001")
                                                .header("X-Actor-Id", inviteeId.toString()))
                                .andExpect(status().isNotFound())
                                .andExpect(jsonPath("$.status").value(404));
        }

        @Test
        void shouldRejectFractionalStake() throws Exception {
                mockMvc.perform(
                                post("/api/challenges")
                                                .header("Idempotency-Key", "test-key-001")
                                                .header("X-Actor-Id", creatorId.toString())
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(createRequestWithStake("20.75")))
                                .andExpect(status().isBadRequest());

                verifyNoInteractions(challengeService, idempotencyService);
        }

        @Test
        void shouldRejectStakeOverflow() throws Exception {
                mockMvc.perform(
                                post("/api/challenges")
                                                .header("Idempotency-Key", "test-key-001")
                                                .header("X-Actor-Id", creatorId.toString())
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(createRequestWithStake(
                                                                "9223372036854775808")))
                                .andExpect(status().isBadRequest());

                verifyNoInteractions(challengeService, idempotencyService);
        }

        @Test
        void shouldAcceptExactWholeNumber() throws Exception {
                when(challengeService.create(
                                creatorId,
                                inviteeId,
                                "Demo challenge",
                                "Description",
                                20L)).thenReturn(challenge);

                mockMvc.perform(
                                post("/api/challenges")
                                                .header("Idempotency-Key", "test-key-001")
                                                .header("X-Actor-Id", creatorId.toString())
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(createRequestWithStake("20.0")))
                                .andExpect(status().isCreated())
                                .andExpect(jsonPath("$.stakePerParticipant")
                                                .value(20));

                verify(challengeService).create(
                                creatorId,
                                inviteeId,
                                "Demo challenge",
                                "Description",
                                20L);
        }
}