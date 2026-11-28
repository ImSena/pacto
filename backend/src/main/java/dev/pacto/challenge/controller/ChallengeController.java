package dev.pacto.challenge.controller;

import java.math.BigDecimal;
import java.net.URI;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
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
import dev.pacto.challenge.InvalidChallengeActorException;
import dev.pacto.challenge.InvalidChallengeTransitionException;
import dev.pacto.challenge.dto.ChallengeResponse;
import dev.pacto.challenge.dto.CreateChallengeRequest;
import dev.pacto.ledger.InsufficientBalanceException;

import dev.pacto.challenge.dto.ConfirmChallengeRequest;
import dev.pacto.challenge.DuplicateConfirmationException;
import dev.pacto.challenge.InvalidChallengeWinnerException;

@RestController
@RequestMapping("/api/challenges")
public class ChallengeController {

        private final ChallengeService challengeService;

        public ChallengeController(
                        ChallengeService challengeService) {
                this.challengeService = challengeService;
        }

        @PostMapping
        public ResponseEntity<ChallengeResponse> create(
                        @RequestHeader("X-Actor-Id") UUID actorId,
                        @RequestBody CreateChallengeRequest request) {
                validateCreateRequest(actorId, request);

                long stake = parseStake(request.stakePerParticipant());

                Challenge challenge = challengeService.create(
                                actorId,
                                request.inviteeId(),
                                request.title(),
                                request.description(),
                                stake);

                return ResponseEntity
                                .created(URI.create(
                                                "/api/challenges/" + challenge.getId()))
                                .body(ChallengeResponse.from(challenge));
        }

        @PostMapping("/{challengeId}/accept")
        public ResponseEntity<ChallengeResponse> accept(
                        @PathVariable UUID challengeId,
                        @RequestHeader("X-Actor-Id") UUID actorId) {
                Challenge challenge = challengeService.accept(
                                challengeId,
                                actorId);

                return ResponseEntity.ok(
                                ChallengeResponse.from(challenge));
        }

        @PostMapping("/{challengeId}/confirm")
        public ResponseEntity<ChallengeResponse> confirm(
                        @PathVariable UUID challengeId,
                        @RequestHeader("X-Actor-Id") UUID actorId,
                        @RequestBody ConfirmChallengeRequest request) {
                if (request.winnerId() == null) {
                        throw new ResponseStatusException(
                                        HttpStatus.BAD_REQUEST,
                                        "Winner ID is required");
                }

                Challenge challenge = challengeService.confirm(
                                challengeId,
                                actorId,
                                request.winnerId());

                return ResponseEntity.ok(
                                ChallengeResponse.from(challenge));
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
}