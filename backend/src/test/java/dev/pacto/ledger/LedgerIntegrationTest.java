package dev.pacto.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import dev.pacto.ledger.repository.LedgerAccountRepository;
import dev.pacto.user.entity.User;
import dev.pacto.user.repository.UserRepository;
import dev.pacto.wallet.entity.Wallet;
import dev.pacto.wallet.repository.WalletRepository;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class LedgerIntegrationTest {

    @SuppressWarnings("resource")
    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17")
            .withDatabaseName("pacto_test")
            .withUsername("pacto_test")
            .withPassword("pacto_test");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private LedgerAccountRepository accountRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void shouldConnectToIsolatedPostgreSQL() {

        String database = jdbcTemplate.queryForObject(
                "SELECT current_database()",
                String.class);

        assertTrue(postgres.isRunning());

        assertEquals("pacto_test", database);
    }

    @Test
    void shouldInitializeDemoUsers() {

        Long totalUsers = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users",
                Long.class);

        assertEquals(2L, totalUsers.longValue());
    }

    @Test
    void shouldPersistBalancedInitialGrants() {

        long totalTransactions = count("""
                SELECT COUNT(*)
                FROM ledger_transactions
                WHERE kind = 'INITIAL_GRANT'
                """);

        long totalEntries = count("""
                SELECT COUNT(*)
                FROM ledger_entries
                """);

        long invalidTransactions = count("""
                SELECT COUNT(*)
                FROM (
                    SELECT lt.id
                    FROM ledger_transactions lt
                    LEFT JOIN ledger_entries le
                        ON le.transaction_id = lt.id
                    GROUP BY lt.id
                    HAVING COUNT(le.id) <> 2
                        OR COALESCE(SUM(le.delta), 0) <> 0
                ) invalid_transactions
                """);

        assertEquals(2L, totalTransactions);
        assertEquals(4L, totalEntries);
        assertEquals(0L, invalidTransactions);
    }

    private long count(String sql, Object... args) {

        return jdbcTemplate.queryForObject(
                sql,
                Long.class,
                args);
    }

    @Test
    void shouldReconcileBalancesAndConserveCredits() {

        long inconsistentAccounts = count("""
                SELECT COUNT(*)
                FROM (
                    SELECT la.id
                    FROM ledger_accounts la
                    LEFT JOIN ledger_entries le
                        ON le.account_id = la.id
                    GROUP BY la.id
                    HAVING la.balance <>
                        COALESCE(SUM(le.delta), 0)
                ) inconsistent_accounts
                """);

        long accountingTotal = count("""
                SELECT COALESCE(SUM(balance), 0)
                FROM ledger_accounts
                """);

        long availableTotal = count("""
                SELECT COALESCE(SUM(balance), 0)
                FROM ledger_accounts
                WHERE type = 'USER_AVAILABLE'
                """);

        long issuanceTotal = count("""
                SELECT COALESCE(SUM(balance), 0)
                FROM ledger_accounts
                WHERE type = 'SYSTEM_ISSUANCE'
                """);

        long negativeAccounts = count("""
                SELECT COUNT(*)
                FROM ledger_accounts
                WHERE type <> 'SYSTEM_ISSUANCE'
                    AND balance < 0
                """);

        assertEquals(0L, inconsistentAccounts);
        assertEquals(0L, accountingTotal);
        assertEquals(200L, availableTotal);
        assertEquals(-200L, issuanceTotal);
        assertEquals(0L, negativeAccounts);
    }

    @Test
    @Transactional
    void shouldPostInitialGrantForNewWallet() {

        UUID businessOperationId = UUID.randomUUID();

        User user = userRepository.save(
                new User("Integration Test User"));

        Wallet wallet = walletRepository.save(
                new Wallet(user));

        LedgerAccount availableAccount = accountRepository.save(
                LedgerAccount.available(wallet.getId()));

        LedgerAccount issuanceAccount = accountRepository
                .findByTypeAndWalletIdIsNull(
                        LedgerAccountType.SYSTEM_ISSUANCE)
                .orElseThrow();

        entityManager.flush();

        UUID transactionId = ledgerService.postInitialGrant(
                businessOperationId,
                List.of(
                        new LedgerPostingLine(
                                issuanceAccount.getId(),
                                -100L),
                        new LedgerPostingLine(
                                availableAccount.getId(),
                                100L)));

        entityManager.flush();

        long availableBalance = count("""
                SELECT balance
                FROM ledger_accounts
                WHERE id = ?
                """,
                availableAccount.getId());

        long issuanceBalance = count("""
                SELECT balance
                FROM ledger_accounts
                WHERE id = ?
                """,
                issuanceAccount.getId());

        long totalEntries = count("""
                SELECT COUNT(*)
                FROM ledger_entries
                WHERE transaction_id = ?
                """,
                transactionId);

        long totalDelta = count("""
                SELECT SUM(delta)
                FROM ledger_entries
                WHERE transaction_id = ?
                """,
                transactionId);

        long persistedTransaction = count("""
                SELECT COUNT(*)
                FROM ledger_transactions
                WHERE id = ?
                    AND business_operation_id = ?
                    AND kind = 'INITIAL_GRANT'
                """,
                transactionId,
                businessOperationId);

        assertNotNull(transactionId);
        assertEquals(100L, availableBalance);
        assertEquals(-300L, issuanceBalance);
        assertEquals(2L, totalEntries);
        assertEquals(0L, totalDelta);
        assertEquals(1L, persistedTransaction);
    }

    @Test
    void shouldRejectDuplicateInitialGrantWithoutChangingState() {

        LedgerAccount issuanceAccount = accountRepository
                .findByTypeAndWalletIdIsNull(
                        LedgerAccountType.SYSTEM_ISSUANCE)
                .orElseThrow();

        UUID availableAccountId = jdbcTemplate.queryForObject("""
                SELECT la.id
                FROM ledger_accounts la
                JOIN wallets w
                    ON w.id = la.wallet_id
                JOIN users u
                    ON u.id = w.user_id
                WHERE u.display_name = 'Bruno'
                    AND la.type = 'USER_AVAILABLE'
                """,
                UUID.class);

        UUID newBusinessOperationId = UUID.randomUUID();

        long transactionsBefore = count("""
                SELECT COUNT(*)
                FROM ledger_transactions
                """);

        long entriesBefore = count("""
                SELECT COUNT(*)
                FROM ledger_entries
                """);

        var balancesBefore = jdbcTemplate.queryForList("""
                SELECT id, balance
                FROM ledger_accounts
                ORDER BY id
                """);

        assertThrows(
                InitialGrantAlreadyPerformedException.class,
                () -> ledgerService.postInitialGrant(
                        newBusinessOperationId,
                        List.of(
                                new LedgerPostingLine(
                                        issuanceAccount.getId(),
                                        -100L),
                                new LedgerPostingLine(
                                        availableAccountId,
                                        100L))));

        long transactionsAfter = count("""
                SELECT COUNT(*)
                FROM ledger_transactions
                """);

        long entriesAfter = count("""
                SELECT COUNT(*)
                FROM ledger_entries
                """);

        var balancesAfter = jdbcTemplate.queryForList("""
                SELECT id, balance
                FROM ledger_accounts
                ORDER BY id
                """);

        assertEquals(
                transactionsBefore,
                transactionsAfter);

        assertEquals(
                entriesBefore,
                entriesAfter);

        assertEquals(
                balancesBefore,
                balancesAfter);

        assertEquals(
                0L,
                count("""
                        SELECT COUNT(*)
                        FROM ledger_transactions
                        WHERE business_operation_id = ?
                        """,
                        newBusinessOperationId));
    }
}