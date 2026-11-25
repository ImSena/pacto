package dev.pacto.ledger;

import java.util.UUID;

public class InitialGrantAlreadyPerformedException
        extends RuntimeException {

    public InitialGrantAlreadyPerformedException(
            UUID accountId) {
        super(
                "Initial grant already performed for account: "
                        + accountId);
    }
}