package dev.pacto.challenge;

import dev.pacto.challenge.repository.ChallengeRepository;
import dev.pacto.ledger.LedgerService;
import dev.pacto.user.repository.UserRepository;
import dev.pacto.wallet.entity.Wallet;
import dev.pacto.wallet.repository.WalletRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import dev.pacto.challenge.repository.ConfirmationRepository;
import java.util.List;

@Service
public class ChallengeService {

        private final ChallengeRepository challengeRepository;
        private final UserRepository userRepository;
        private final WalletRepository walletRepository;
        private final LedgerService ledgerService;
        private final ConfirmationRepository confirmationRepository;

        public ChallengeService(
                        ChallengeRepository challengeRepository,
                        UserRepository userRepository,
                        WalletRepository walletRepository,
                        LedgerService ledgerService,
                        ConfirmationRepository confirmationRepository) {
                this.challengeRepository = challengeRepository;
                this.userRepository = userRepository;
                this.walletRepository = walletRepository;
                this.ledgerService = ledgerService;
                this.confirmationRepository = confirmationRepository;
        }

        @Transactional
        public Challenge create(
                        UUID creatorId,
                        UUID inviteeId,
                        String title,
                        String description,
                        long stakePerParticipant) {
                Challenge challenge = new Challenge(
                                creatorId,
                                inviteeId,
                                title,
                                description,
                                stakePerParticipant);

                validateDemoParticipants(creatorId, inviteeId);

                return challengeRepository.save(challenge);
        }

        @Transactional
        public Challenge accept(
                        UUID challengeId,
                        UUID actorId) {
                Challenge challenge = findChallenge(challengeId);

                challenge.accept(actorId);

                validateDemoParticipants(
                                challenge.getCreatorId(),
                                challenge.getInviteeId());

                Wallet creatorWallet = findWallet(
                                challenge.getCreatorId());

                Wallet inviteeWallet = findWallet(
                                challenge.getInviteeId());

                ledgerService.reserve(
                                UUID.randomUUID(),
                                challenge.getId(),
                                creatorWallet.getId(),
                                inviteeWallet.getId(),
                                challenge.getStakePerParticipant());

                return challenge;
        }

        @Transactional
        public Challenge confirm(
                        UUID challengeId,
                        UUID actorId,
                        UUID winnerId) {
                Challenge challenge = findChallenge(challengeId);

                if (!challenge.isParticipant(actorId)) {
                        throw new InvalidChallengeActorException(
                                        "Only challenge participants can confirm results");
                }

                if (!challenge.isParticipant(winnerId)) {
                        throw new InvalidChallengeWinnerException();
                }

                List<Confirmation> confirmations = confirmationRepository.findByChallengeId(challengeId);

                boolean alreadyConfirmed = confirmations.stream()
                                .anyMatch(confirmation -> confirmation.getParticipantId().equals(actorId));

                if (alreadyConfirmed) {
                        throw new DuplicateConfirmationException(
                                        challengeId,
                                        actorId);
                }

                switch (challenge.getStatus()) {
                        case ACCEPTED -> {
                                if (!confirmations.isEmpty()) {
                                        throw new IllegalStateException(
                                                        "Accepted challenge must not have confirmations");
                                }

                                challenge.recordFirstConfirmation(
                                                actorId,
                                                winnerId);

                                confirmationRepository.save(
                                                new Confirmation(
                                                                challengeId,
                                                                actorId,
                                                                winnerId));
                        }

                        case AWAITING_RESULT -> {
                                if (confirmations.size() != 1) {
                                        throw new IllegalStateException(
                                                        "Awaiting result requires exactly one confirmation");
                                }

                                Confirmation first = confirmations.getFirst();

                                challenge.recordSecondConfirmation(
                                                actorId,
                                                winnerId,
                                                first.getParticipantId(),
                                                first.getWinnerId());

                                confirmationRepository.save(
                                                new Confirmation(
                                                                challengeId,
                                                                actorId,
                                                                winnerId));

                                if (challenge.getStatus() == ChallengeStatus.SETTLED) {
                                        settleChallenge(challenge, winnerId);
                                }
                        }

                        default -> throw new InvalidChallengeTransitionException(
                                        challenge.getStatus(),
                                        ChallengeStatus.AWAITING_RESULT);
                }

                return challenge;
        }

        private void settleChallenge(
                        Challenge challenge,
                        UUID winnerId) {
                Wallet creatorWallet = findWallet(
                                challenge.getCreatorId());

                Wallet inviteeWallet = findWallet(
                                challenge.getInviteeId());

                UUID winnerWalletId = winnerId.equals(
                                challenge.getCreatorId())
                                                ? creatorWallet.getId()
                                                : inviteeWallet.getId();

                ledgerService.settle(
                                UUID.randomUUID(),
                                challenge.getId(),
                                creatorWallet.getId(),
                                inviteeWallet.getId(),
                                winnerWalletId,
                                challenge.getStakePerParticipant());
        }

        private Challenge findChallenge(UUID challengeId) {
                return challengeRepository.findById(challengeId)
                                .orElseThrow(() -> new ChallengeNotFoundException(challengeId));
        }

        private Wallet findWallet(UUID userId) {
                return walletRepository.findByUser_Id(userId)
                                .orElseThrow(() -> new IllegalStateException(
                                                "Wallet not found for user: " + userId));
        }

        private void validateDemoParticipants(
                        UUID creatorId,
                        UUID inviteeId) {
                UUID brunoId = findDemoUserId("Bruno");
                UUID lucasId = findDemoUserId("Lucas");

                boolean validPair = (brunoId.equals(creatorId)
                                && lucasId.equals(inviteeId))
                                ||
                                (lucasId.equals(creatorId)
                                                && brunoId.equals(inviteeId));

                if (!validPair) {
                        throw new InvalidChallengeActorException(
                                        "Challenge must involve both demo users");
                }
        }

        private UUID findDemoUserId(String displayName) {
                return userRepository.findByDisplayName(displayName)
                                .orElseThrow(() -> new IllegalStateException(
                                                "Demo user not found: " + displayName))
                                .getId();
        }
}