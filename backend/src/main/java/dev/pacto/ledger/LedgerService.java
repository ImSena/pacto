package dev.pacto.ledger;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import dev.pacto.ledger.repository.LedgerAccountRepository;
import dev.pacto.ledger.repository.LedgerEntryRepository;
import dev.pacto.ledger.repository.LedgerTransactionRepository;

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

                return persistPostings(
                                LedgerTransaction.initialGrant(businessOperationId),
                                postings,
                                accounts);
        }

        @Transactional(propagation = Propagation.MANDATORY)
        public UUID reserve(
                        UUID businessOperationId,
                        UUID challengeId,
                        UUID creatorWalletId,
                        UUID inviteeWalletId,
                        long stake) {
                Objects.requireNonNull(businessOperationId);
                Objects.requireNonNull(challengeId);
                Objects.requireNonNull(creatorWalletId);
                Objects.requireNonNull(inviteeWalletId);

                if (stake <= 0) {
                        throw new IllegalArgumentException(
                                        "Stake must be positive");
                }

                if (creatorWalletId.equals(inviteeWalletId)) {
                        throw new IllegalArgumentException(
                                        "Reservation requires two different wallets");
                }

                LedgerAccount creatorAvailable = findAvailableAccount(creatorWalletId);

                LedgerAccount inviteeAvailable = findAvailableAccount(inviteeWalletId);

                validateAvailableBalance(creatorAvailable, stake);
                validateAvailableBalance(inviteeAvailable, stake);

                LedgerAccount creatorEscrow = accountRepository.save(
                                LedgerAccount.escrow(
                                                creatorWalletId,
                                                challengeId));

                LedgerAccount inviteeEscrow = accountRepository.save(
                                LedgerAccount.escrow(
                                                inviteeWalletId,
                                                challengeId));

                List<LedgerPostingLine> postings = List.of(
                                new LedgerPostingLine(
                                                creatorAvailable.getId(), -stake),
                                new LedgerPostingLine(
                                                creatorEscrow.getId(), stake),
                                new LedgerPostingLine(
                                                inviteeAvailable.getId(), -stake),
                                new LedgerPostingLine(
                                                inviteeEscrow.getId(), stake));

                validatePostings(postings);

                Map<UUID, LedgerAccount> accounts = Map.of(
                                creatorAvailable.getId(), creatorAvailable,
                                creatorEscrow.getId(), creatorEscrow,
                                inviteeAvailable.getId(), inviteeAvailable,
                                inviteeEscrow.getId(), inviteeEscrow);

                return persistPostings(
                                LedgerTransaction.reserve(
                                                businessOperationId,
                                                challengeId),
                                postings,
                                accounts);
        }

        @Transactional(propagation = Propagation.MANDATORY)
        public UUID settle(
                        UUID businessOperationId,
                        UUID challengeId,
                        UUID creatorWalletId,
                        UUID inviteeWalletId,
                        UUID winnerWalletId,
                        long stake) {
                Objects.requireNonNull(businessOperationId);
                Objects.requireNonNull(challengeId);
                Objects.requireNonNull(creatorWalletId);
                Objects.requireNonNull(inviteeWalletId);
                Objects.requireNonNull(winnerWalletId);

                if (stake <= 0) {
                        throw new IllegalArgumentException(
                                        "Stake must be positive");
                }

                if (creatorWalletId.equals(inviteeWalletId)) {
                        throw new IllegalArgumentException(
                                        "Settlement requires two different wallets");
                }

                if (!winnerWalletId.equals(creatorWalletId)
                                && !winnerWalletId.equals(inviteeWalletId)) {
                        throw new IllegalArgumentException(
                                        "Winner must be a challenge participant");
                }

                long payout = Math.multiplyExact(stake, 2L);

                LedgerAccount creatorEscrow = findEscrowAccount(
                                challengeId,
                                creatorWalletId);

                LedgerAccount inviteeEscrow = findEscrowAccount(
                                challengeId,
                                inviteeWalletId);

                validateEscrowBalance(creatorEscrow, stake);
                validateEscrowBalance(inviteeEscrow, stake);

                LedgerAccount winnerAvailable = findAvailableAccount(winnerWalletId);

                Math.addExact(
                                winnerAvailable.getBalance(),
                                payout);

                List<LedgerPostingLine> postings = List.of(
                                new LedgerPostingLine(
                                                creatorEscrow.getId(),
                                                -stake),
                                new LedgerPostingLine(
                                                inviteeEscrow.getId(),
                                                -stake),
                                new LedgerPostingLine(
                                                winnerAvailable.getId(),
                                                payout));

                validatePostings(postings);

                Map<UUID, LedgerAccount> accounts = Map.of(
                                creatorEscrow.getId(), creatorEscrow,
                                inviteeEscrow.getId(), inviteeEscrow,
                                winnerAvailable.getId(), winnerAvailable);

                return persistPostings(
                                LedgerTransaction.settle(
                                                businessOperationId,
                                                challengeId),
                                postings,
                                accounts);
        }

        private UUID persistPostings(
                        LedgerTransaction transaction,
                        List<LedgerPostingLine> postings,
                        Map<UUID, LedgerAccount> accounts) {
                LedgerTransaction savedTransaction = transactionRepository.save(transaction);

                List<LedgerEntry> entries = new ArrayList<>();

                for (LedgerPostingLine posting : postings) {
                        LedgerAccount account = accounts.get(
                                        posting.accountId());

                        account.applyDelta(posting.delta());

                        LedgerEntry entry = new LedgerEntry(
                                        savedTransaction,
                                        account,
                                        posting.delta());

                        entries.add(entry);
                }

                entryRepository.saveAll(entries);

                return savedTransaction.getId();
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

        private LedgerAccount findAvailableAccount(UUID walletId) {
                return accountRepository
                                .findByWalletIdAndType(
                                                walletId,
                                                LedgerAccountType.USER_AVAILABLE)
                                .orElseThrow(() -> new IllegalStateException(
                                                "Available ledger account not found for wallet: "
                                                                + walletId));
        }

        private void validateAvailableBalance(
                        LedgerAccount account,
                        long amount) {
                if (account.getBalance() < amount) {
                        throw new InsufficientBalanceException(
                                        account.getId(),
                                        account.getBalance(),
                                        -amount);
                }
        }

        private LedgerAccount findEscrowAccount(
                        UUID challengeId,
                        UUID walletId) {
                return accountRepository
                                .findByChallengeIdAndWalletIdAndType(
                                                challengeId,
                                                walletId,
                                                LedgerAccountType.CHALLENGE_ESCROW)
                                .orElseThrow(() -> new IllegalStateException(
                                                "Escrow account not found for challenge: "
                                                                + challengeId
                                                                + " and wallet: "
                                                                + walletId));
        }

        private void validateEscrowBalance(
                        LedgerAccount escrow,
                        long expectedBalance) {
                if (escrow.getBalance() != expectedBalance) {
                        throw new IllegalStateException(
                                        "Invalid escrow balance for account: "
                                                        + escrow.getId()
                                                        + ". Expected: "
                                                        + expectedBalance
                                                        + ", actual: "
                                                        + escrow.getBalance());
                }
        }
}