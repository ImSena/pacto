package dev.pacto.challenge.repository;

import dev.pacto.challenge.Challenge;
import org.springframework.data.repository.Repository;

import java.util.Optional;
import java.util.UUID;

public interface ChallengeRepository
        extends Repository<Challenge, UUID> {

    <S extends Challenge> S save(S challenge);

    Optional<Challenge> findById(UUID id);
}