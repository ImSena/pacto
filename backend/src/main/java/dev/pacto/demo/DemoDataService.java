package dev.pacto.demo;

import dev.pacto.user.entity.User;
import dev.pacto.user.repository.UserRepository;
import dev.pacto.wallet.entity.*;
import dev.pacto.wallet.repository.*;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class DemoDataService {

    private static final long INITIAL_CREDITS = 100L;

    private static final UUID BRUNO_GRANT_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000001");

    private static final UUID LUCAS_GRANT_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000002");

    private final UserRepository userRepository;
    private final WalletRepository walletRepository;
    private final LedgerAccountRepository accountRepository;
    private final LedgerTransactionRepository transactionRepository;
    private final LedgerEntryRepository entryRepository;
    private final JdbcTemplate jdbcTemplate;

    public DemoDataService(
            UserRepository userRepository,
            WalletRepository walletRepository,
            LedgerAccountRepository accountRepository,
            LedgerTransactionRepository transactionRepository,
            LedgerEntryRepository entryRepository,
            JdbcTemplate jdbcTemplate) {
        this.userRepository = userRepository;
        this.walletRepository = walletRepository;
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.entryRepository = entryRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public void initialize() {

        jdbcTemplate.execute(
                "SELECT pg_advisory_xact_lock(730006)");

        boolean brunoGranted = transactionRepository.existsByBusinessOperationId(
                BRUNO_GRANT_ID);

        boolean lucasGranted = transactionRepository.existsByBusinessOperationId(
                LUCAS_GRANT_ID);

        if (brunoGranted && lucasGranted) {
            return;
        }

        if (brunoGranted || lucasGranted) {
            throw new IllegalStateException(
                    "Incomplete demo initialization detected");
        }

        if (userRepository.findByDisplayName("Bruno").isPresent()
                || userRepository.findByDisplayName("Lucas").isPresent()
                || accountRepository.findByTypeAndWalletIsNull(
                        LedgerAccountType.SYSTEM_ISSUANCE).isPresent()) {

            throw new IllegalStateException(
                    "Unexpected existing demo data");
        }

        LedgerAccount issuanceAccount = accountRepository.save(
                LedgerAccount.systemIssuance());

        createDemoUser(
                "Bruno",
                BRUNO_GRANT_ID,
                issuanceAccount);

        createDemoUser(
                "Lucas",
                LUCAS_GRANT_ID,
                issuanceAccount);
    }

    private void createDemoUser(
            String displayName,
            UUID businessOperationId,
            LedgerAccount issuanceAccount) {

        User user = userRepository.save(
                new User(displayName));

        Wallet wallet = walletRepository.save(
                new Wallet(user));

        LedgerAccount availableAccount = accountRepository.save(
                LedgerAccount.available(wallet));

        LedgerTransaction transaction = transactionRepository.save(
                LedgerTransaction.initialGrant(
                        businessOperationId));

        issuanceAccount.applyDelta(-INITIAL_CREDITS);
        availableAccount.applyDelta(INITIAL_CREDITS);

        LedgerEntry debit = new LedgerEntry(
                transaction,
                issuanceAccount,
                -INITIAL_CREDITS);

        LedgerEntry credit = new LedgerEntry(
                transaction,
                availableAccount,
                INITIAL_CREDITS);

        entryRepository.saveAll(
                List.of(debit, credit));
    }
}