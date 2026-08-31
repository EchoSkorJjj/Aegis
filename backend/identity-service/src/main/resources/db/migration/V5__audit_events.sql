-- Append-only security audit trail: who did what, when, from where, and whether
-- it succeeded. Deliberately has NO foreign keys to users(id): audit rows are a
-- standalone ledger that must outlive the users they reference (e.g. a
-- USER_DELETED entry survives the deletion it records).
create table audit_events (
    id             uuid          primary key,
    occurred_at    timestamptz   not null default now(),
    event_type     varchar(64)   not null,
    outcome        varchar(16)   not null,
    actor_user_id  uuid,
    actor_email    varchar(320),
    target_user_id uuid,
    ip_address     varchar(45),
    user_agent     varchar(512),
    detail         text
);

create index idx_audit_events_occurred_at on audit_events(occurred_at desc);
create index idx_audit_events_actor       on audit_events(actor_user_id);
create index idx_audit_events_target      on audit_events(target_user_id);
create index idx_audit_events_type        on audit_events(event_type);

-- Enforce append-only at the database, not just in application code: reject any
-- attempt to modify or remove an existing row. (TRUNCATE is a separate privilege
-- and is intentionally not covered here.)
create or replace function audit_events_immutable() returns trigger as $$
begin
    raise exception 'audit_events is append-only; % is not permitted', tg_op;
end;
$$ language plpgsql;

create trigger audit_events_no_mutate
    before update or delete on audit_events
    for each row execute function audit_events_immutable();
