package dev.pacto.ledger;

import dev.pacto.ledger.repository.LedgerAccountRepository;
import dev.pacto.user.entity.User;
import dev.pacto.user.repository.UserRepository;
import dev.pacto.wallet.entity.Wallet;
import dev.pacto.wallet.repository.WalletRepository;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class LedgerRollbackTest {

    private static final String FORCED_FAILURE = "PACTO_TEST_FORCED_ROLLBACK";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17")
            .withDatabaseName("pacto_rollback_test")
            .withUsername("pacto_test")
            .withPassword("pacto_test");

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private LedgerAccountRepository accountRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void shouldRollbackEntireOperationWhenCommitFails() {

        assertTrue(postgres.isRunning());

        assertEquals(
                "pacto_rollback_test",
                jdbcTemplate.queryForObject(
                        "SELECT current_database()",
                        String.class));

        UUID businessOperationId = UUID.randomUUID();

        UUID availableAccountId = createTemporaryAccount();

        UUID issuanceAccountId = accountRepository
                .findByTypeAndWalletIdIsNull(
                        LedgerAccountType.SYSTEM_ISSUANCE)
                .orElseThrow()
                .getId();

        assertEquals(0L, getBalance(availableAccountId));
        assertEquals(-200L, getBalance(issuanceAccountId));

        long transactionsBefore = scalar("""
                SELECT COUNT(*)
                FROM ledger_transactions
                """);

        long entriesBefore = scalar("""
                SELECT COUNT(*)
                FROM ledger_entries
                """);

        var balancesBefore = jdbcTemplate.queryForList("""
                SELECT id, balance
                FROM ledger_accounts
                ORDER BY id
                """);

        installFailureTrigger(businessOperationId);

        RuntimeException exception = assertThrows(
                RuntimeException.class,
                () -> ledgerService.postInitialGrant(
                        businessOperationId,
                        List.of(
                                new LedgerPostingLine(
                                        issuanceAccountId,
                                        -100L),
                                new LedgerPostingLine(
                                        availableAccountId,
                                        100L))));

        assertTrue(
                containsFailureMessage(exception),
                "Expected intentional commit failure");

        assertEquals(
                transactionsBefore,
                scalar("""
                        SELECT COUNT(*)
                        FROM ledger_transactions
                        """));

        assertEquals(
                entriesBefore,
                scalar("""
                        SELECT COUNT(*)
                        FROM ledger_entries
                        """));

        assertEquals(
                balancesBefore,
                jdbcTemplate.queryForList("""
                        SELECT id, balance
                        FROM ledger_accounts
                        ORDER BY id
                        """));

        assertEquals(
                -200L,
                getBalance(issuanceAccountId));

        assertEquals(
                0L,
                getBalance(availableAccountId));

        assertEquals(
                0L,
                scalar("""
                        SELECT COUNT(*)
                        FROM ledger_transactions
                        WHERE business_operation_id = ?
                        """,
                        businessOperationId));
    }

    private UUID createTemporaryAccount() {

        TransactionTemplate template = new TransactionTemplate(transactionManager);

        return template.execute(status -> {

            User user = userRepository.save(
                    new User("Rollback Test User"));

            Wallet wallet = walletRepository.save(
                    new Wallet(user));

            LedgerAccount account = accountRepository.save(
                    LedgerAccount.available(
                            wallet.getId()));

            return account.getId();
        });
    }

    private void installFailureTrigger(
            UUID businessOperationId) {

        jdbcTemplate.execute("""
                CREATE FUNCTION pacto_test_fail_commit()
                RETURNS TRIGGER
                LANGUAGE plpgsql
                AS $$
                BEGIN

                    IF (
                        SELECT COUNT(*)
                        FROM ledger_entries
                        WHERE transaction_id = NEW.id
                    ) <> 2

                    OR NOT EXISTS (
                        SELECT 1
                        FROM ledger_entries e
                        JOIN ledger_accounts a
                            ON a.id = e.account_id
                        WHERE e.transaction_id = NEW.id
                            AND a.type = 'SYSTEM_ISSUANCE'
                            AND e.delta = -100
                            AND a.balance = -300
                    )

                    OR NOT EXISTS (
                        SELECT 1
                        FROM ledger_entries e
                        JOIN ledger_accounts a
                            ON a.id = e.account_id
                        WHERE e.transaction_id = NEW.id
                            AND a.type = 'USER_AVAILABLE'
                            AND e.delta = 100
                            AND a.balance = 100
                    )

                    THEN
                        RAISE EXCEPTION
                            'PACTO_TEST_UNEXPECTED_STATE';
                    END IF;

                    RAISE EXCEPTION
                        'PACTO_TEST_FORCED_ROLLBACK';

                    RETURN NEW;
                END;
                $$;
                """);

        jdbcTemplate.execute("""
                CREATE CONSTRAINT TRIGGER pacto_test_rollback
                AFTER INSERT ON ledger_transactions
                DEFERRABLE INITIALLY DEFERRED
                FOR EACH ROW
                WHEN (
                    NEW.business_operation_id = '%s'::uuid
                )
                EXECUTE FUNCTION pacto_test_fail_commit();
                """.formatted(businessOperationId));
    }

    private boolean containsFailureMessage(
            Throwable exception) {

        Throwable current = exception;

        while (current != null) {

            if (current.getMessage() != null
                    && current.getMessage()
                            .contains(FORCED_FAILURE)) {

                return true;
            }

            current = current.getCause();
        }

        return false;
    }

    private long getBalance(UUID accountId) {

        return scalar("""
                SELECT balance
                FROM ledger_accounts
                WHERE id = ?
                """,
                accountId);
    }

    private long scalar(String sql, Object... args) {

        return jdbcTemplate.queryForObject(
                sql,
                Long.class,
                args);
    }
}