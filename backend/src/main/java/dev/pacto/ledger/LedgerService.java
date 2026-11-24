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
}