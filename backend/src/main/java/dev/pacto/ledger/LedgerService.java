package dev.pacto.ledger;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.pacto.ledger.repository.LedgerAccountRepository;
import dev.pacto.ledger.repository.LedgerEntryRepository;
import dev.pacto.ledger.repository.LedgerTransactionRepository;

import java.math.BigInteger;
import java.util.*;

@Service
public class LedgerService {
        public static final long INITIAL_GRANT_CREDITS = 100L;

        private final LedgerAccountRepository accountRepository;
        private final LedgerTransactionRepository transactionRepository;
        private final LedgerEntryRepository entryRepository;

        public LedgerService(
                        LedgerAccountRepository accountRepository,
                        LedgerTransactionRepository transactionRepository,
                        LedgerEntryRepository entryRepository) {
                this.accountRepository = accountRepository;
                this.transactionRepository = transactionRepository;
                this.entryRepository = entryRepository;
        }

        @Transactional
        public UUID postInitialGrant(
                        UUID businessOperationId,
                        List<LedgerPostingLine> postings) {
                Objects.requireNonNull(
                                businessOperationId,
                                "Business operation ID is required");

                postings = List.copyOf(postings);

                validatePostings(postings);
                validateInitialGrantAmount(postings);

                Map<UUID, LedgerAccount> accounts = new HashMap<>();

                for (LedgerPostingLine posting : postings) {
                        LedgerAccount account = accountRepository
                                        .findById(posting.accountId())
                                        .orElseThrow(() -> new IllegalArgumentException(
                                                        "Ledger account not found: "
                                                                        + posting.accountId()));

                        accounts.put(account.getId(), account);
                }

                validateInitialGrant(postings, accounts);
                validateInitialGrantNotPerformed(postings, accounts);

                LedgerTransaction transaction = transactionRepository.save(
                                LedgerTransaction.initialGrant(
                                                businessOperationId));

                List<LedgerEntry> entries = new ArrayList<>();

                for (LedgerPostingLine posting : postings) {

                        LedgerAccount account = accounts.get(posting.accountId());

                        account.applyDelta(posting.delta());

                        entries.add(
                                        new LedgerEntry(
                                                        transaction,
                                                        account,
                                                        posting.delta()));
                }

                entryRepository.saveAll(entries);

                return transaction.getId();
        }

        private void validatePostings(
                        List<LedgerPostingLine> postings) {
                if (postings.size() < 2) {
                        throw new IllegalArgumentException(
                                        "At least two postings are required");
                }

                Set<UUID> accountIds = new HashSet<>();

                BigInteger total = BigInteger.ZERO;

                for (LedgerPostingLine posting : postings) {

                        if (!accountIds.add(posting.accountId())) {
                                throw new IllegalArgumentException(
                                                "Duplicate account in ledger transaction");
                        }

                        total = total.add(
                                        BigInteger.valueOf(posting.delta()));
                }

                if (total.signum() != 0) {
                        throw new IllegalArgumentException(
                                        "Ledger transaction must be balanced");
                }
        }

        private void validateInitialGrant(
                        List<LedgerPostingLine> postings,
                        Map<UUID, LedgerAccount> accounts) {
                if (postings.size() != 2) {
                        throw new IllegalArgumentException(
                                        "Initial grant requires exactly two postings");
                }

                for (LedgerPostingLine posting : postings) {

                        LedgerAccount account = accounts.get(posting.accountId());

                        boolean validDebit = account.getType() == LedgerAccountType.SYSTEM_ISSUANCE
                                        && posting.delta() < 0;

                        boolean validCredit = account.getType() == LedgerAccountType.USER_AVAILABLE
                                        && posting.delta() > 0;

                        if (!validDebit && !validCredit) {
                                throw new IllegalArgumentException(
                                                "Invalid initial grant posting");
                        }
                }
        }

        private void validateInitialGrantAmount(
                        List<LedgerPostingLine> postings) {

                for (LedgerPostingLine posting : postings) {

                        long delta = posting.delta();

                        if (delta != INITIAL_GRANT_CREDITS
                                        && delta != -INITIAL_GRANT_CREDITS) {

                                throw new IllegalArgumentException(
                                                "Initial grant must be exactly "
                                                                + INITIAL_GRANT_CREDITS
                                                                + " credits");
                        }
                }
        }

        private void validateInitialGrantNotPerformed(
                        List<LedgerPostingLine> postings,
                        Map<UUID, LedgerAccount> accounts) {

                for (LedgerPostingLine posting : postings) {

                        LedgerAccount account = accounts.get(posting.accountId());

                        if (account.getType() != LedgerAccountType.USER_AVAILABLE) {
                                continue;
                        }

                        boolean alreadyGranted = entryRepository
                                        .existsByAccount_IdAndTransaction_Kind(
                                                        account.getId(),
                                                        LedgerTransactionKind.INITIAL_GRANT);

                        if (alreadyGranted) {
                                throw new InitialGrantAlreadyPerformedException(
                                                account.getId());
                        }
                }
        }
}