package dev.pacto.challenge;

import dev.pacto.challenge.repository.ChallengeRepository;
import dev.pacto.ledger.LedgerService;
import dev.pacto.user.repository.UserRepository;
import dev.pacto.wallet.entity.Wallet;
import dev.pacto.wallet.repository.WalletRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class ChallengeService {

    private final ChallengeRepository challengeRepository;
    private final UserRepository userRepository;
    private final WalletRepository walletRepository;
    private final LedgerService ledgerService;

    public ChallengeService(
            ChallengeRepository challengeRepository,
            UserRepository userRepository,
            WalletRepository walletRepository,
            LedgerService ledgerService) {
        this.challengeRepository = challengeRepository;
        this.userRepository = userRepository;
        this.walletRepository = walletRepository;
        this.ledgerService = ledgerService;
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