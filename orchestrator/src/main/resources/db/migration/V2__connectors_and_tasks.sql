alter table privacy_request add column run_after timestamptz; -- end of the cooling-off period, set when verified

create table connector (
    id           uuid         primary key,
    name         varchar(100) not null unique,
    endpoint_url varchar(500) not null,
    secret_enc   bytea        not null, -- AES-GCM like emails: we need it back to sign requests
    stage        int          not null check (stage > 0),
    created_at   timestamptz  not null
);

create table task (
    id              uuid        primary key,
    request_id      uuid        not null references privacy_request (id),
    connector_id    uuid        not null references connector (id),
    stage           int         not null,
    status          varchar(20) not null, -- PENDING, SENT, DONE, FAILED
    result          varchar(20),          -- DELETED, ANONYMIZED, RETAINED
    note            varchar(500),
    attempts        int         not null default 0,
    next_attempt_at timestamptz not null,
    updated_at      timestamptz not null,
    unique (request_id, connector_id)
);

-- The dispatcher's "what's due?" query only ever looks at open tasks.
create index task_due on task (next_attempt_at) where status in ('PENDING', 'SENT');
