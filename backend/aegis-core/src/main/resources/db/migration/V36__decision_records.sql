-- The decision audit trail (E10 story 1): every step a response takes — drafted, edited, overridden, approved, filed
-- or opened, failed, dismissed — recorded with who took it, when, why, and the response as it stood at that moment.
-- A response is a risk ticket, its upgrade pull request, or a regulator memo; together their records are the chain
-- of one item (a change in a service) from the draft to the outcome.
--
-- state is that snapshot, as JSON: the change, the score and tier, the evidence (matching paths, score arithmetic,
-- source excerpt), the draft and any edit or override, the decision and its result. It is a copy, so a later SBOM
-- import, a re-scored change or an edited response never alters what a past record says. service_name and
-- change_title are copies too, for the same reason.
--
-- service_ids are the services a step covers: a ticket's or pull request's one service (service_id, also named), or
-- every service a regulator memo covers, so each of those services' trails holds the memo though it names no single
-- service.
--
-- Append-only: while enabled, a trigger refuses every update, delete and truncate, from any role, the superuser
-- included. A trigger is not a lock against the table's owner or a superuser, though, who can disable or drop it, and
-- the owner today is the role aegis-core connects with (it also runs these migrations). Closing that needs the
-- owner/app role split in todo/aws-deployment.md, with the application's role holding only insert and select here.
-- No foreign keys: a record outlives the service, ticket or memo it describes (a service's tickets are removed with
-- it, V23).
-- Org-scoped with the same row-level security backstop as the tables it records; seq orders records written in the
-- same instant.

create table decision_records (
    id               uuid             primary key,
    seq              bigint           generated always as identity unique,
    org_id           uuid             not null,
    occurred_at      timestamptz      not null default now(),
    subject_type     varchar(24)      not null,
    subject_id       uuid             not null,
    response_id      uuid             not null,
    service_id       uuid,
    service_name     varchar(255),
    service_ids      uuid[]           not null,
    change_signal_id uuid             not null,
    change_title     varchar(300)     not null,
    action           varchar(24)      not null,
    actor_id         uuid             not null,
    reason           text,
    score            double precision,
    tier             varchar(16),
    state            text             not null,
    constraint ck_decision_records_subject
        check (subject_type in ('RISK_TICKET', 'UPGRADE_PULL_REQUEST', 'REGULATOR_MEMO')),
    constraint ck_decision_records_action
        check (action in ('DRAFTED', 'EDITED', 'OVERRIDDEN', 'OVERRIDE_WITHDRAWN', 'APPROVED', 'FILED',
                          'FILING_FAILED', 'OPENED', 'OPENING_FAILED', 'DISMISSED')),
    constraint ck_decision_records_tier check (tier in ('CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'NEGLIGIBLE')),
    constraint ck_decision_records_services check (service_id is null or service_id = any (service_ids))
);

create index ix_decision_records_item on decision_records (org_id, change_signal_id, seq);
create index ix_decision_records_services on decision_records using gin (service_ids);
create index ix_decision_records_response on decision_records (org_id, response_id, seq);
create index ix_decision_records_occurred on decision_records (org_id, occurred_at desc);

create function decision_records_immutable() returns trigger as $$
begin
    raise exception 'decision_records is append-only; % is not permitted', tg_op;
end;
$$ language plpgsql;

create trigger decision_records_no_mutate
    before update or delete on decision_records
    for each row execute function decision_records_immutable();

create trigger decision_records_no_truncate
    before truncate on decision_records
    for each statement execute function decision_records_immutable();

alter table decision_records enable row level security;
alter table decision_records force row level security;
create policy tenant_isolation on decision_records
    using (org_id = current_org()) with check (org_id = current_org());

-- An upgrade pull request keeps its service's score and tier as drafted, as its risk ticket does, so each of its
-- records carries them. Pull requests drafted before V36 have none (not backfilled).
alter table upgrade_proposals
    add column score double precision,
    add column tier  varchar(16),
    add constraint ck_upgrade_proposals_tier check (tier in ('CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'NEGLIGIBLE'));
