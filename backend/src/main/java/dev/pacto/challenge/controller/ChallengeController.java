package dev.pacto.challenge.controller;

import java.math.BigDecimal;
import java.net.URI;
import java.util.UUID;

import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import dev.pacto.challenge.Challenge;
import dev.pacto.challenge.ChallengeNotFoundException;
import dev.pacto.challenge.ChallengeService;
import dev.pacto.challenge.DuplicateConfirmationException;
import dev.pacto.challenge.InvalidChallengeActorException;
import dev.pacto.challenge.InvalidChallengeTransitionException;
import dev.pacto.challenge.InvalidChallengeWinnerException;

import dev.pacto.challenge.dto.ChallengeResponse;
import dev.pacto.challenge.dto.ConfirmChallengeRequest;
import dev.pacto.challenge.dto.CreateChallengeRequest;

import dev.pacto.idempotency.IdempotencyConflictException;
import dev.pacto.idempotency.IdempotencyOperation;
import dev.pacto.idempotency.IdempotencyResponse;
import dev.pacto.idempotency.IdempotencyService;
import dev.pacto.idempotency.InvalidIdempotencyKeyException;
import dev.pacto.idempotency.PayloadHasher;
import dev.pacto.idempotency.UnknownIdempotencyActorException;

import dev.pacto.ledger.InsufficientBalanceException;

import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api/challenges")
public class ChallengeController {

        private final ChallengeService challengeService;

        private final IdempotencyService idempotencyService;

        private final JsonMapper jsonMapper;

        public ChallengeController(
                        ChallengeService challengeService,
                        IdempotencyService idempotencyService,
                        JsonMapper jsonMapper) {

                this.challengeService = challengeService;
                this.idempotencyService = idempotencyService;
                this.jsonMapper = jsonMapper;
        }

        @PostMapping
        public ResponseEntity<String> create(
                        @RequestHeader("X-Actor-Id") UUID actorId,
                        @RequestHeader("Idempotency-Key") String idempotencyKey,
                        @RequestBody CreateChallengeRequest request) {

                validateCreateRequest(actorId, request);

                long stake = parseStake(
                                request.stakePerParticipant());

                String payloadHash = PayloadHasher.hashCreate(
                                request.inviteeId(),
                                request.title(),
                                request.description(),
                                stake);

                IdempotencyResponse response = idempotencyService.execute(
                                actorId,
                                IdempotencyOperation.CREATE_CHALLENGE,
                                idempotencyKey,
                                payloadHash,
                                () -> {

                                        Challenge challenge = challengeService.create(
                                                        actorId,
                                                        request.inviteeId(),
                                                        request.title(),
                                                        request.description(),
                                                        stake);

                                        return serializeResponse(
                                                        HttpStatus.CREATED,
                                                        challenge);
                                });

                return toHttpResponse(response);
        }

        @PostMapping("/{challengeId}/accept")
        public ResponseEntity<String> accept(
                        @PathVariable UUID challengeId,
                        @RequestHeader("X-Actor-Id") UUID actorId,
                        @RequestHeader("Idempotency-Key") String idempotencyKey) {

                String payloadHash = PayloadHasher.hashAccept(challengeId);

                IdempotencyResponse response = idempotencyService.execute(
                                actorId,
                                IdempotencyOperation.ACCEPT_CHALLENGE,
                                idempotencyKey,
                                payloadHash,
                                () -> {

                                        Challenge challenge = challengeService.accept(
                                                        challengeId,
                                                        actorId);

                                        return serializeResponse(
                                                        HttpStatus.OK,
                                                        challenge);
                                });

                return toHttpResponse(response);
        }

        @PostMapping("/{challengeId}/confirm")
        public ResponseEntity<String> confirm(
                        @PathVariable UUID challengeId,
                        @RequestHeader("X-Actor-Id") UUID actorId,
                        @RequestHeader("Idempotency-Key") String idempotencyKey,
                        @RequestBody ConfirmChallengeRequest request) {

                if (request.winnerId() == null) {

                        throw new ResponseStatusException(
                                        HttpStatus.BAD_REQUEST,
                                        "Winner ID is required");
                }

                String payloadHash = PayloadHasher.hashConfirm(
                                challengeId,
                                request.winnerId());

                IdempotencyResponse response = idempotencyService.execute(
                                actorId,
                                IdempotencyOperation.CONFIRM_RESULT,
                                idempotencyKey,
                                payloadHash,
                                () -> {

                                        Challenge challenge = challengeService.confirm(
                                                        challengeId,
                                                        actorId,
                                                        request.winnerId());

                                        return serializeResponse(
                                                        HttpStatus.OK,
                                                        challenge);
                                });

                return toHttpResponse(response);
        }

        @PostMapping("/{challengeId}/cancel")
        public ResponseEntity<String> cancel(
                        @PathVariable UUID challengeId,
                        @RequestHeader("X-Actor-Id") UUID actorId,
                        @RequestHeader("Idempotency-Key") String idempotencyKey) {

                String payloadHash = PayloadHasher.hashCancel(challengeId);

                IdempotencyResponse response = idempotencyService.execute(
                                actorId,
                                IdempotencyOperation.CANCEL_CHALLENGE,
                                idempotencyKey,
                                payloadHash,
                                () -> {

                                        Challenge challenge = challengeService.cancel(
                                                        challengeId,
                                                        actorId);

                                        return serializeResponse(
                                                        HttpStatus.OK,
                                                        challenge);
                                });

                return toHttpResponse(response);
        }

        private IdempotencyResponse serializeResponse(
                        HttpStatus status,
                        Challenge challenge) {

                ChallengeResponse response = ChallengeResponse.from(challenge);

                String responseBody = jsonMapper.writeValueAsString(response);

                return new IdempotencyResponse(
                                status.value(),
                                responseBody);
        }

        private ResponseEntity<String> toHttpResponse(
                        IdempotencyResponse response) {

                ResponseEntity.BodyBuilder builder = ResponseEntity
                                .status(response.httpStatus())
                                .contentType(MediaType.APPLICATION_JSON);

                if (response.httpStatus() == HttpStatus.CREATED.value()) {

                        UUID challengeId = UUID.fromString(
                                        jsonMapper
                                                        .readTree(response.responseBody())
                                                        .path("id")
                                                        .asText());

                        builder.location(
                                        URI.create(
                                                        "/api/challenges/" + challengeId));
                }

                return builder.body(response.responseBody());
        }

        private void validateCreateRequest(
                        UUID actorId,
                        CreateChallengeRequest request) {

                if (request.inviteeId() == null) {

                        throw new ResponseStatusException(
                                        HttpStatus.BAD_REQUEST,
                                        "Invitee ID is required");
                }

                if (actorId.equals(request.inviteeId())) {

                        throw new ResponseStatusException(
                                        HttpStatus.BAD_REQUEST,
                                        "Creator and invitee must be different");
                }

                if (request.title() == null
                                || request.title().isBlank()
                                || request.title().length() > 160) {

                        throw new ResponseStatusException(
                                        HttpStatus.BAD_REQUEST,
                                        "Title must contain between 1 and 160 characters");
                }
        }

        private long parseStake(BigDecimal value) {

                if (value == null) {

                        throw new ResponseStatusException(
                                        HttpStatus.BAD_REQUEST,
                                        "Stake per participant is required");
                }

                final long stake;

                try {

                        stake = value.longValueExact();

                } catch (ArithmeticException exception) {

                        throw new ResponseStatusException(
                                        HttpStatus.BAD_REQUEST,
                                        "Stake must be a valid whole number");
                }

                if (stake <= 0) {

                        throw new ResponseStatusException(
                                        HttpStatus.BAD_REQUEST,
                                        "Stake per participant must be positive");
                }

                return stake;
        }

        @ExceptionHandler(ChallengeNotFoundException.class)
        public ProblemDetail handleNotFound(
                        ChallengeNotFoundException exception) {

                return ProblemDetail.forStatusAndDetail(
                                HttpStatus.NOT_FOUND,
                                exception.getMessage());
        }

        @ExceptionHandler(InvalidChallengeActorException.class)
        public ProblemDetail handleInvalidActor(
                        InvalidChallengeActorException exception) {

                return ProblemDetail.forStatusAndDetail(
                                HttpStatus.FORBIDDEN,
                                exception.getMessage());
        }

        @ExceptionHandler(InvalidChallengeTransitionException.class)
        public ProblemDetail handleInvalidTransition(
                        InvalidChallengeTransitionException exception) {

                return ProblemDetail.forStatusAndDetail(
                                HttpStatus.CONFLICT,
                                exception.getMessage());
        }

        @ExceptionHandler(InsufficientBalanceException.class)
        public ProblemDetail handleInsufficientBalance(
                        InsufficientBalanceException exception) {

                return ProblemDetail.forStatusAndDetail(
                                HttpStatus.CONFLICT,
                                "Insufficient available balance to accept challenge");
        }

        @ExceptionHandler(DuplicateConfirmationException.class)
        public ProblemDetail handleDuplicateConfirmation(
                        DuplicateConfirmationException exception) {

                return ProblemDetail.forStatusAndDetail(
                                HttpStatus.CONFLICT,
                                exception.getMessage());
        }

        @ExceptionHandler(InvalidChallengeWinnerException.class)
        public ProblemDetail handleInvalidWinner(
                        InvalidChallengeWinnerException exception) {

                return ProblemDetail.forStatusAndDetail(
                                HttpStatus.BAD_REQUEST,
                                exception.getMessage());
        }

        @ExceptionHandler(InvalidIdempotencyKeyException.class)
        public ProblemDetail handleInvalidIdempotencyKey(
                        InvalidIdempotencyKeyException exception) {

                return ProblemDetail.forStatusAndDetail(
                                HttpStatus.BAD_REQUEST,
                                exception.getMessage());
        }

        @ExceptionHandler(IdempotencyConflictException.class)
        public ProblemDetail handleIdempotencyConflict(
                        IdempotencyConflictException exception) {

                return ProblemDetail.forStatusAndDetail(
                                HttpStatus.CONFLICT,
                                exception.getMessage());
        }

        @ExceptionHandler(UnknownIdempotencyActorException.class)
        public ProblemDetail handleUnknownIdempotencyActor(
                        UnknownIdempotencyActorException exception) {

                return ProblemDetail.forStatusAndDetail(
                                HttpStatus.FORBIDDEN,
                                exception.getMessage());
        }

        @ExceptionHandler({
                        PessimisticLockingFailureException.class,
                        QueryTimeoutException.class,
                        TransactionTimedOutException.class
        })
        public ResponseEntity<ProblemDetail> handleTemporaryFailure(
                        RuntimeException exception) {

                ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                                HttpStatus.SERVICE_UNAVAILABLE,
                                "Operation temporarily unavailable. "
                                                + "Retry using the same Idempotency-Key");

                return ResponseEntity
                                .status(HttpStatus.SERVICE_UNAVAILABLE)
                                .header("Retry-After", "1")
                                .body(problem);
        }
}