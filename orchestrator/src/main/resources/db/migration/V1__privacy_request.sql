create table privacy_request (
    id              uuid        primary key,
    status          varchar(20) not null,
    email_enc       bytea       not null, -- AES-GCM: 12-byte IV + ciphertext
    code_hash       bytea,                -- HMAC of the one-time code; cleared once used
    code_expires_at timestamptz not null,
    code_attempts   int         not null default 0,
    received_at     timestamptz not null,
    due_at          timestamptz not null
);
