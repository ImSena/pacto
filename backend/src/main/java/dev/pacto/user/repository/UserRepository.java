package dev.pacto.user.repository;

import dev.pacto.user.entity.User;
import org.springframework.data.repository.Repository;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends Repository<User, UUID> {

    <U extends User> U save(U user);

    Optional<User> findById(UUID id);

    Optional<User> findByDisplayName(String displayName);
}