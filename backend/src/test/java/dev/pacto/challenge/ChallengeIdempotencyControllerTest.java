package dev.pacto.challenge;

import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.TransactionTimedOutException;

import dev.pacto.challenge.controller.ChallengeController;

import dev.pacto.idempotency.IdempotencyConflictException;
import dev.pacto.idempotency.IdempotencyOperation;
import dev.pacto.idempotency.IdempotencyResponse;
import dev.pacto.idempotency.IdempotencyService;
import dev.pacto.idempotency.InvalidIdempotencyKeyException;
import dev.pacto.idempotency.PayloadHasher;
import dev.pacto.idempotency.UnknownIdempotencyActorException;

import tools.jackson.databind.json.JsonMapper;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class ChallengeIdempotencyControllerTest {

    private static final String KEY = "test-key-001";

    private static final String INVALID_KEY = "invalid key";

    @Mock
    private ChallengeService challengeService;

    @Mock
    private IdempotencyService idempotencyService;

    private MockMvc mockMvc;

    private UUID creatorId;
    private UUID inviteeId;
    private UUID challengeId;

    @BeforeEach
    void setUp() {

        creatorId = UUID.randomUUID();
        inviteeId = UUID.randomUUID();
        challengeId = UUID.randomUUID();

        mockMvc = MockMvcBuilders
                .standaloneSetup(
                        new ChallengeController(
                                challengeService,
                                idempotencyService,
                                JsonMapper.builder().build()))
                .build();
    }

    @ParameterizedTest
    @EnumSource(IdempotencyOperation.class)
    void shouldRejectMissingIdempotencyKey(
            IdempotencyOperation operation)
            throws Exception {

        mockMvc.perform(request(operation, null))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(
                challengeService,
                idempotencyService);
    }

    @ParameterizedTest
    @EnumSource(IdempotencyOperation.class)
    void shouldRejectInvalidIdempotencyKey(
            IdempotencyOperation operation)
            throws Exception {

        when(idempotencyService.execute(
                eq(actorFor(operation)),
                eq(operation),
                eq(INVALID_KEY),
                eq(expectedHash(operation)),
                any()))
                .thenThrow(
                        new InvalidIdempotencyKeyException(
                                "Invalid Idempotency-Key"));

        mockMvc.perform(request(operation, INVALID_KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail")
                        .value("Invalid Idempotency-Key"));

        verifyNoInteractions(challengeService);
    }

    @ParameterizedTest
    @EnumSource(IdempotencyOperation.class)
    void shouldReplayStoredResponseWithoutExecutingCommand(
            IdempotencyOperation operation)
            throws Exception {

        int httpStatus =
                operation == IdempotencyOperation.CREATE_CHALLENGE
                        ? 201
                        : 200;

        String challengeStatus = switch (operation) {
            case CREATE_CHALLENGE -> "OPEN";
            case ACCEPT_CHALLENGE -> "ACCEPTED";
            case CONFIRM_RESULT -> "AWAITING_RESULT";
            case CANCEL_CHALLENGE -> "CANCELLED";
        };

        String responseBody = responseBody(challengeStatus);

        when(idempotencyService.execute(
                eq(actorFor(operation)),
                eq(operation),
                eq(KEY),
                eq(expectedHash(operation)),
                any()))
                .thenReturn(
                        new IdempotencyResponse(
                                httpStatus,
                                responseBody));

        var result = mockMvc.perform(
                        request(operation, KEY))
                .andExpect(status().is(httpStatus))
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.APPLICATION_JSON))
                .andExpect(content().string(responseBody))
                .andExpect(jsonPath("$.id")
                        .value(challengeId.toString()))
                .andExpect(jsonPath("$.status")
                        .value(challengeStatus));

        if (operation == IdempotencyOperation.CREATE_CHALLENGE) {

            result.andExpect(
                    header().string(
                            "Location",
                            "/api/challenges/" + challengeId));

        } else {

            result.andExpect(
                    header().doesNotExist("Location"));
        }

        verify(idempotencyService).execute(
                eq(actorFor(operation)),
                eq(operation),
                eq(KEY),
                eq(expectedHash(operation)),
                any());

        verifyNoInteractions(challengeService);
    }

    @ParameterizedTest
    @EnumSource(IdempotencyOperation.class)
    void shouldReturnConflictForDifferentPayload(
            IdempotencyOperation operation)
            throws Exception {

        when(idempotencyService.execute(
                eq(actorFor(operation)),
                eq(operation),
                eq(KEY),
                eq(expectedHash(operation)),
                any()))
                .thenThrow(
                        new IdempotencyConflictException());

        mockMvc.perform(request(operation, KEY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.detail").value(
                        "Idempotency-Key has already been used "
                                + "with a different payload"));

        verifyNoInteractions(challengeService);
    }

    @Test
    void shouldReturnForbiddenForUnknownActor()
            throws Exception {

        IdempotencyOperation operation =
                IdempotencyOperation.ACCEPT_CHALLENGE;

        when(idempotencyService.execute(
                eq(inviteeId),
                eq(operation),
                eq(KEY),
                eq(expectedHash(operation)),
                any()))
                .thenThrow(
                        new UnknownIdempotencyActorException());

        mockMvc.perform(request(operation, KEY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.detail").value(
                        "Idempotency actor does not exist"));

        verifyNoInteractions(challengeService);
    }

    @ParameterizedTest
    @MethodSource("temporaryFailures")
    void shouldReturnServiceUnavailableForTemporaryFailure(
            RuntimeException exception)
            throws Exception {

        IdempotencyOperation operation =
                IdempotencyOperation.ACCEPT_CHALLENGE;

        when(idempotencyService.execute(
                eq(inviteeId),
                eq(operation),
                eq(KEY),
                eq(expectedHash(operation)),
                any()))
                .thenThrow(exception);

        mockMvc.perform(request(operation, KEY))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(
                        "Retry-After", "1"))
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.detail").value(
                        "Operation temporarily unavailable. "
                                + "Retry using the same Idempotency-Key"));

        verifyNoInteractions(challengeService);
    }

    @Test
    void shouldGenerateSameHashForEquivalentCreateRequests()
            throws Exception {

        String expectedHash = PayloadHasher.hashCreate(
                inviteeId,
                "Demo challenge",
                "Description",
                20L);

        String responseBody = responseBody("OPEN");

        when(idempotencyService.execute(
                eq(creatorId),
                eq(IdempotencyOperation.CREATE_CHALLENGE),
                eq(KEY),
                eq(expectedHash),
                any()))
                .thenReturn(
                        new IdempotencyResponse(
                                201,
                                responseBody));

        String firstRequest = """
                {
                    "inviteeId": "%s",
                    "title": "Demo challenge",
                    "description": "Description",
                    "stakePerParticipant": 20
                }
                """.formatted(inviteeId);

        String secondRequest = """
                {
                    "stakePerParticipant": 20.0,
                    "description": "Description",
                    "title": "  Demo challenge  ",
                    "inviteeId": "%s"
                }
                """.formatted(inviteeId);

        mockMvc.perform(
                post("/api/challenges")
                        .header(
                                "X-Actor-Id",
                                creatorId.toString())
                        .header("Idempotency-Key", KEY)
                        .contentType(
                                MediaType.APPLICATION_JSON)
                        .content(firstRequest))
                .andExpect(status().isCreated())
                .andExpect(content().string(responseBody));

        mockMvc.perform(
                post("/api/challenges")
                        .header(
                                "X-Actor-Id",
                                creatorId.toString())
                        .header("Idempotency-Key", KEY)
                        .contentType(
                                MediaType.APPLICATION_JSON)
                        .content(secondRequest))
                .andExpect(status().isCreated())
                .andExpect(content().string(responseBody));

        verify(idempotencyService, times(2)).execute(
                eq(creatorId),
                eq(IdempotencyOperation.CREATE_CHALLENGE),
                eq(KEY),
                eq(expectedHash),
                any());

        verifyNoInteractions(challengeService);
    }

    private static Stream<RuntimeException> temporaryFailures() {

        return Stream.of(
                new CannotAcquireLockException(
                        "Lock acquisition failed"),
                new QueryTimeoutException(
                        "Query timed out"),
                new TransactionTimedOutException(
                        "Transaction timed out"));
    }

    private MockHttpServletRequestBuilder request(
            IdempotencyOperation operation,
            String key) {

        MockHttpServletRequestBuilder builder =
                switch (operation) {

                    case CREATE_CHALLENGE ->
                            post("/api/challenges")
                                    .contentType(
                                            MediaType.APPLICATION_JSON)
                                    .content("""
                                            {
                                                "inviteeId": "%s",
                                                "title": "Demo challenge",
                                                "description": "Description",
                                                "stakePerParticipant": 20
                                            }
                                            """.formatted(inviteeId));

                    case ACCEPT_CHALLENGE ->
                            post("/api/challenges/{id}/accept",
                                    challengeId);

                    case CONFIRM_RESULT ->
                            post("/api/challenges/{id}/confirm",
                                    challengeId)
                                    .contentType(
                                            MediaType.APPLICATION_JSON)
                                    .content("""
                                            {
                                                "winnerId": "%s"
                                            }
                                            """.formatted(creatorId));

                    case CANCEL_CHALLENGE ->
                            post("/api/challenges/{id}/cancel",
                                    challengeId);
                };

        builder.header(
                "X-Actor-Id",
                actorFor(operation).toString());

        if (key != null) {

            builder.header("Idempotency-Key", key);
        }

        return builder;
    }

    private UUID actorFor(
            IdempotencyOperation operation) {

        return switch (operation) {
            case ACCEPT_CHALLENGE -> inviteeId;
            case CREATE_CHALLENGE,
                 CONFIRM_RESULT,
                 CANCEL_CHALLENGE -> creatorId;
        };
    }

    private String expectedHash(
            IdempotencyOperation operation) {

        return switch (operation) {

            case CREATE_CHALLENGE ->
                    PayloadHasher.hashCreate(
                            inviteeId,
                            "Demo challenge",
                            "Description",
                            20L);

            case ACCEPT_CHALLENGE ->
                    PayloadHasher.hashAccept(challengeId);

            case CONFIRM_RESULT ->
                    PayloadHasher.hashConfirm(
                            challengeId,
                            creatorId);

            case CANCEL_CHALLENGE ->
                    PayloadHasher.hashCancel(challengeId);
        };
    }

    private String responseBody(String challengeStatus) {

        return """
                {
                    "id": "%s",
                    "creatorId": "%s",
                    "inviteeId": "%s",
                    "title": "Demo challenge",
                    "description": "Description",
                    "stakePerParticipant": 20,
                    "status": "%s"
                }
                """.formatted(
                        challengeId,
                        creatorId,
                        inviteeId,
                        challengeStatus);
    }
}