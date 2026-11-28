package dev.pacto.idempotency;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.InjectMocks;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import dev.pacto.idempotency.repository.IdempotencyRecordRepository;
import dev.pacto.user.entity.User;
import dev.pacto.user.repository.UserRepository;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IdempotencyServiceTest {

        private static final UUID ACTOR_ID = UUID.randomUUID();

        private static final UUID RECORD_ID = UUID.randomUUID();

        private static final IdempotencyOperation OPERATION = IdempotencyOperation.CREATE_CHALLENGE;

        private static final String KEY = "create-001";

        private static final String HASH = "a".repeat(64);

        private static final String DIFFERENT_HASH = "b".repeat(64);

        private static final Instant CREATED_AT = Instant.parse("2026-01-01T10:00:00Z");

        private static final IdempotencyResponse RESPONSE = new IdempotencyResponse(
                        201,
                        """
                                        {"status":"OPEN"}
                                        """);

        @Mock
        private IdempotencyRecordRepository repository;

        @Mock
        private UserRepository userRepository;

        @Mock
        private User user;

        @Mock
        private Supplier<IdempotencyResponse> command;

        @InjectMocks
        private IdempotencyService service;

        @Test
        void shouldExecuteCommandForNewKey() {

                prepareAcquisition(
                                true,
                                inProgressRecord(HASH));

                when(command.get()).thenReturn(RESPONSE);

                IdempotencyResponse result = execute();

                assertEquals(RESPONSE, result);

                InOrder order = inOrder(repository, command);

                order.verify(repository)
                                .configureLockTimeout();

                order.verify(repository)
                                .tryInsert(
                                                ACTOR_ID, OPERATION, KEY, HASH);

                order.verify(repository)
                                .findForUpdate(
                                                ACTOR_ID, OPERATION, KEY);

                order.verify(command).get();

                order.verify(repository)
                                .complete(
                                                RECORD_ID,
                                                RESPONSE.httpStatus(),
                                                RESPONSE.responseBody());

                order.verifyNoMoreInteractions();
        }

        @Test
        void shouldReplayCompletedResponseWithoutExecutingCommand() {

                prepareAcquisition(
                                false,
                                completedRecord(HASH));

                IdempotencyResponse result = execute();

                assertEquals(RESPONSE, result);

                verifyNoInteractions(command);

                verify(repository, never())
                                .complete(any(), anyInt(), anyString());
        }

        @Test
        void shouldRejectDifferentPayloadForExistingKey() {

                prepareAcquisition(
                                false,
                                completedRecord(DIFFERENT_HASH));

                assertThrows(
                                IdempotencyConflictException.class,
                                this::execute);

                verifyNoInteractions(command);

                verify(repository, never())
                                .complete(any(), anyInt(), anyString());
        }

        @Test
        void shouldRejectExistingIncompleteRecord() {

                prepareAcquisition(
                                false,
                                inProgressRecord(HASH));

                assertThrows(
                                IllegalStateException.class,
                                this::execute);

                verifyNoInteractions(command);

                verify(repository, never())
                                .complete(any(), anyInt(), anyString());
        }

        @Test
        void shouldRejectMissingRecordAfterAcquisition() {

                when(userRepository.findById(ACTOR_ID))
                                .thenReturn(Optional.of(user));

                when(repository.tryInsert(
                                ACTOR_ID, OPERATION, KEY, HASH))
                                .thenReturn(true);

                when(repository.findForUpdate(
                                ACTOR_ID, OPERATION, KEY))
                                .thenReturn(Optional.empty());

                assertThrows(
                                IllegalStateException.class,
                                this::execute);

                verifyNoInteractions(command);

                verify(repository, never())
                                .complete(any(), anyInt(), anyString());
        }

        @Test
        void shouldRejectUnknownActorBeforeAcquiringKey() {

                when(userRepository.findById(ACTOR_ID))
                                .thenReturn(Optional.empty());

                assertThrows(
                                UnknownIdempotencyActorException.class,
                                this::execute);

                verifyNoInteractions(repository, command);
        }

        @Test
        void shouldRejectInvalidKeyBeforeDatabaseAccess() {

                assertThrows(
                                InvalidIdempotencyKeyException.class,
                                () -> service.execute(
                                                ACTOR_ID,
                                                OPERATION,
                                                "invalid key",
                                                HASH,
                                                command));

                verifyNoInteractions(
                                userRepository,
                                repository,
                                command);
        }

        @Test
        void shouldRejectInvalidHashBeforeDatabaseAccess() {

                assertThrows(
                                IllegalArgumentException.class,
                                () -> service.execute(
                                                ACTOR_ID,
                                                OPERATION,
                                                KEY,
                                                "invalid-hash",
                                                command));

                verifyNoInteractions(
                                userRepository,
                                repository,
                                command);
        }

        @Test
        void shouldPropagateCommandFailureWithoutCompletingRecord() {

                prepareAcquisition(
                                true,
                                inProgressRecord(HASH));

                IllegalStateException failure = new IllegalStateException(
                                "Intentional command failure");

                when(command.get()).thenThrow(failure);

                IllegalStateException exception = assertThrows(
                                IllegalStateException.class,
                                this::execute);

                assertSame(failure, exception);

                verify(command, times(1)).get();

                verify(repository, never())
                                .complete(any(), anyInt(), anyString());
        }

        @Test
        void shouldRejectNullCommandResponse() {

                prepareAcquisition(
                                true,
                                inProgressRecord(HASH));

                when(command.get()).thenReturn(null);

                assertThrows(
                                NullPointerException.class,
                                this::execute);

                verify(repository, never())
                                .complete(any(), anyInt(), anyString());
        }

        private IdempotencyResponse execute() {

                return service.execute(
                                ACTOR_ID,
                                OPERATION,
                                KEY,
                                HASH,
                                command);
        }

        private void prepareAcquisition(
                        boolean inserted,
                        IdempotencyRecord record) {

                when(userRepository.findById(ACTOR_ID))
                                .thenReturn(Optional.of(user));

                when(repository.tryInsert(
                                ACTOR_ID, OPERATION, KEY, HASH))
                                .thenReturn(inserted);

                when(repository.findForUpdate(
                                ACTOR_ID, OPERATION, KEY))
                                .thenReturn(Optional.of(record));
        }

        private IdempotencyRecord inProgressRecord(
                        String hash) {

                return new IdempotencyRecord(
                                RECORD_ID,
                                ACTOR_ID,
                                OPERATION,
                                KEY,
                                hash,
                                IdempotencyState.IN_PROGRESS,
                                null,
                                null,
                                CREATED_AT,
                                null);
        }

        private IdempotencyRecord completedRecord(
                        String hash) {

                return new IdempotencyRecord(
                                RECORD_ID,
                                ACTOR_ID,
                                OPERATION,
                                KEY,
                                hash,
                                IdempotencyState.COMPLETED,
                                RESPONSE.httpStatus(),
                                RESPONSE.responseBody(),
                                CREATED_AT,
                                CREATED_AT.plusSeconds(1));
        }
}