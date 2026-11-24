package dev.pacto.wallet.repository;

import dev.pacto.wallet.entity.Wallet;
import org.springframework.data.repository.Repository;

import java.util.Optional;
import java.util.UUID;

public interface WalletRepository extends Repository<Wallet, UUID> {

    <W extends Wallet> W save(W wallet);

    Optional<Wallet> findById(UUID id);

    Optional<Wallet> findByUser_Id(UUID userId);
}