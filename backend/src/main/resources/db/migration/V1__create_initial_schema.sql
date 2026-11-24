-- PACTO | PCT-004 | V1: esquema inicial
-- Modelo aprovado em docs/domain-model.md (PCT-002)
-- PostgreSQL 17. Créditos inteiros; UUIDs gerados pelo PostgreSQL.

CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    display_name VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE wallets (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL UNIQUE REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE challenges (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    creator_id UUID NOT NULL REFERENCES users(id),
    invitee_id UUID NOT NULL REFERENCES users(id),
    title VARCHAR(160) NOT NULL,
    description TEXT,
    stake_per_participant BIGINT NOT NULL CHECK (stake_per_participant > 0),
    status VARCHAR(32) NOT NULL DEFAULT 'OPEN'
        CHECK (status IN (
            'OPEN', 'ACCEPTED', 'AWAITING_RESULT',
            'SETTLED', 'DISPUTED', 'CANCELLED'
        )),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    accepted_at TIMESTAMPTZ,
    settled_at TIMESTAMPTZ,
    CONSTRAINT ck_challenge_distinct_participants CHECK (creator_id <> invitee_id),
    CONSTRAINT ck_challenge_title_not_blank CHECK (length(btrim(title)) > 0)
);

CREATE TABLE ledger_accounts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    type VARCHAR(32) NOT NULL
        CHECK (type IN ('USER_AVAILABLE', 'CHALLENGE_ESCROW', 'SYSTEM_ISSUANCE')),
    wallet_id UUID REFERENCES wallets(id),
    challenge_id UUID REFERENCES challenges(id),
    balance BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_account_owner_by_type CHECK (
        (type = 'USER_AVAILABLE' AND wallet_id IS NOT NULL AND challenge_id IS NULL)
        OR (type = 'CHALLENGE_ESCROW' AND wallet_id IS NOT NULL AND challenge_id IS NOT NULL)
        OR (type = 'SYSTEM_ISSUANCE' AND wallet_id IS NULL AND challenge_id IS NULL)
    ),
    CONSTRAINT ck_account_nonnegative_except_issuance CHECK (
        type = 'SYSTEM_ISSUANCE' OR balance >= 0
    )
);

-- Uma conta disponível por carteira, uma custódia por desafio e participante,
-- e uma conta global de emissão. Índices parciais ignoram outros tipos.
CREATE UNIQUE INDEX uq_ledger_account_available_wallet
    ON ledger_accounts(wallet_id) WHERE type = 'USER_AVAILABLE';

CREATE UNIQUE INDEX uq_ledger_account_escrow_challenge_wallet
    ON ledger_accounts(challenge_id, wallet_id) WHERE type = 'CHALLENGE_ESCROW';

CREATE UNIQUE INDEX uq_ledger_account_system_issuance
    ON ledger_accounts(type) WHERE type = 'SYSTEM_ISSUANCE';

CREATE TABLE ledger_transactions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    business_operation_id UUID NOT NULL UNIQUE,
    kind VARCHAR(32) NOT NULL
        CHECK (kind IN ('INITIAL_GRANT', 'RESERVE', 'SETTLE')),
    challenge_id UUID REFERENCES challenges(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_transaction_challenge_by_kind CHECK (
        (kind = 'INITIAL_GRANT' AND challenge_id IS NULL)
        OR (kind IN ('RESERVE', 'SETTLE') AND challenge_id IS NOT NULL)
    )
);

-- A reserva e a liquidação de um mesmo desafio só podem ser lançadas uma vez.
CREATE UNIQUE INDEX uq_ledger_transaction_challenge_kind
    ON ledger_transactions(challenge_id, kind)
    WHERE kind IN ('RESERVE', 'SETTLE');

CREATE TABLE ledger_entries (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id UUID NOT NULL REFERENCES ledger_transactions(id),
    account_id UUID NOT NULL REFERENCES ledger_accounts(id),
    delta BIGINT NOT NULL CHECK (delta <> 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX ix_ledger_entries_account_created
    ON ledger_entries(account_id, created_at DESC, id);

CREATE TABLE confirmations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    challenge_id UUID NOT NULL REFERENCES challenges(id),
    participant_id UUID NOT NULL REFERENCES users(id),
    winner_id UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_confirmation_challenge_participant UNIQUE (challenge_id, participant_id)
);

CREATE TABLE idempotency_records (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_id UUID NOT NULL REFERENCES users(id),
    operation VARCHAR(32) NOT NULL CHECK (operation IN (
        'CREATE_CHALLENGE', 'CANCEL_CHALLENGE',
        'ACCEPT_CHALLENGE', 'CONFIRM_RESULT'
    )),
    idempotency_key VARCHAR(255) NOT NULL
        CHECK (length(btrim(idempotency_key)) > 0),
    payload_hash VARCHAR(64) NOT NULL
        CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    state VARCHAR(16) NOT NULL DEFAULT 'IN_PROGRESS'
        CHECK (state IN ('IN_PROGRESS', 'COMPLETED')),
    http_status INTEGER,
    response_body JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    CONSTRAINT uq_idempotency_actor_operation_key
        UNIQUE (actor_id, operation, idempotency_key),
    CONSTRAINT ck_idempotency_completion CHECK (
        (state = 'IN_PROGRESS' AND completed_at IS NULL
            AND http_status IS NULL AND response_body IS NULL)
        OR (state = 'COMPLETED' AND completed_at IS NOT NULL
            AND http_status IS NOT NULL AND http_status BETWEEN 100 AND 599
            AND response_body IS NOT NULL)
    )
);

CREATE INDEX ix_challenges_invitee_status
    ON challenges(invitee_id, status, created_at DESC);

CREATE INDEX ix_challenges_creator_created
    ON challenges(creator_id, created_at DESC);
