-- Preserve V44's totals-only observations as such. Historical ownership cannot be reconstructed from today's estate.
alter table risk_posture_snapshots add column breakdowns_recorded boolean not null default false;

-- A service and every one of its assigned owners each get a row, including zero-item groups.
-- UNASSIGNED is the explicit owner bucket for services with no owner (subject_id is null).
-- Keep subject ids and names as observed, with no FK to live services/owners: deletion or reassignment must not
-- rewrite past posture. Only the parent day's observation owns these rows.
create table risk_posture_breakdowns (
    id bigint generated always as identity primary key,
    org_id uuid not null,
    day date not null,
    dimension varchar(16) not null,
    subject_id uuid,
    subject_name varchar(255) not null,
    open_items integer not null check (open_items >= 0),
    critical_items integer not null check (critical_items >= 0),
    high_items integer not null check (high_items >= 0),
    top_score double precision,
    constraint fk_posture_breakdowns_day foreign key (org_id, day)
        references risk_posture_snapshots (org_id, day) on delete cascade,
    constraint ux_posture_breakdowns_subject unique nulls not distinct (org_id, day, dimension, subject_id),
    constraint ck_posture_breakdowns_subject check (
        (dimension in ('SERVICE', 'OWNER') and subject_id is not null)
        or (dimension = 'UNASSIGNED' and subject_id is null)),
    constraint ck_posture_breakdowns_counts check (critical_items + high_items <= open_items),
    constraint ck_posture_breakdowns_score check (
        (open_items = 0 and top_score is null)
        or (open_items > 0 and top_score is not null and top_score between 0 and 100))
);

alter table risk_posture_breakdowns enable row level security;
alter table risk_posture_breakdowns force row level security;
create policy tenant_isolation on risk_posture_breakdowns
    using (org_id = current_org()) with check (org_id = current_org());
