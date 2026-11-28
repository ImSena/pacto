package dev.pacto.challenge.repository;

import dev.pacto.challenge.Confirmation;

import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.UUID;

public interface ConfirmationRepository
        extends Repository<Confirmation, UUID> {

    <S extends Confirmation> S save(S confirmation);

    List<Confirmation> findByChallengeId(
            UUID challengeId);
}