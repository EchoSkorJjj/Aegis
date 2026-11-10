-- The AI's reading of a material finding: what changed, why it matters to this service and what to do. A background
-- job writes it with nobody asking, from facts Aegis already holds, never code. A suggestion for a person: nothing
-- reads it to score, rank or decide. Findings aren't stored, so a row is keyed by the service and what makes an item
-- the same from run to run (TriageQueue.Item.key: the package and canonical vulnerability id, or the incident).
-- `fingerprint` is a hash of what the AI was told, so it is asked again only when that changes.
create table finding_interpretations (
    id                  uuid          not null,
    org_id              uuid          not null,
    business_service_id uuid          not null,
    item_key            varchar(512)  not null,
    change_signal_id    uuid          not null,
    subject             varchar(255)  not null,
    signal              varchar(16)   not null,
    confidence          varchar(8)    not null,
    what_changed        varchar(2000) not null,
    why_it_matters      varchar(2000) not null,
    what_next           varchar(2000) not null,
    suggested_action    varchar(16)   not null,
    action_detail       varchar(1000) not null,
    assumptions         text[]        not null default '{}',
    citations           text[]        not null default '{}',
    fingerprint         varchar(64)   not null,
    interpreted_at      timestamptz   not null,
    constraint pk_finding_interpretations primary key (id),
    constraint fk_finding_interpretations_service foreign key (business_service_id)
        references business_services (id) on delete cascade,
    constraint uq_finding_interpretations unique (business_service_id, item_key),
    constraint ck_finding_interpretations_signal check (signal in ('UNCERTAIN', 'LIKELY_REAL', 'LIKELY_NOISE')),
    constraint ck_finding_interpretations_confidence check (confidence in ('LOW', 'MEDIUM', 'HIGH')),
    constraint ck_finding_interpretations_action check (suggested_action in ('VERIFY', 'ACT_NOW', 'SCHEDULE', 'MONITOR'))
);

create index ix_finding_interpretations_change on finding_interpretations (org_id, change_signal_id);

-- The tenant backstop every org-scoped table has (V9, read through current_org() since V31).
alter table finding_interpretations enable row level security;
alter table finding_interpretations force row level security;
create policy tenant_isolation on finding_interpretations
    using (org_id = current_org())
    with check (org_id = current_org());
