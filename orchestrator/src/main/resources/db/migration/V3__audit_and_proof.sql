alter table privacy_request
    alter column email_enc drop not null,  -- erased the moment a request is finished
    add column subject_hash   bytea,       -- keyed fingerprint of the email; survives erasure
    add column closed_at      timestamptz, -- when it reached COMPLETED, CANCELLED or REJECTED
    add column deadline_alert varchar(20); -- null, WARNING or OVERDUE: what the admin was last told

-- Everything that happens to a request, in order. Each row's hash covers its own content plus the previous row's
-- hash, so editing, deleting or reordering any row breaks the chain from that point on.
create table audit_event (
    id         bigserial   primary key,
    request_id uuid        not null references privacy_request (id),
    event      varchar(30) not null,
    detail     varchar(500),
    created_at timestamptz not null,
    prev_hash  bytea       not null,
    hash       bytea       not null
);

-- First line of defence: the database itself refuses edits and deletes. (The hash chain catches anyone who
-- switches this off.)
create function audit_event_is_append_only() returns trigger language plpgsql as $$
begin
    raise exception 'audit_event is append-only';
end $$;

create trigger audit_event_append_only
    before update or delete on audit_event
    for each row execute function audit_event_is_append_only();
