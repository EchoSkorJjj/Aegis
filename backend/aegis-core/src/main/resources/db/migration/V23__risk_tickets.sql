-- Risk tickets (E8 story 5). A drafted ticket for one change's impact (a vulnerability or a
-- vendor outage) on one service, which a second person approves before Aegis files it in the
-- organization's issue tracker. Its title, priority, due date, labels and evidence are Aegis's own,
-- from the impact score; its summary, risk, impact and actions are the AI's draft (ai_drafted) or a plain
-- fallback of Aegis's facts. Nothing is filed without that approval.
--
-- Org-scoped like the inventory it points into: org_id, store-level scoping, and the same RLS backstop as
-- V9 and V18. A ticket is removed with its service. Timestamps come from the database.

create table risk_tickets (
    id               uuid             primary key,
    org_id           uuid             not null,
    service_id       uuid             not null references business_services (id) on delete cascade,
    change_signal_id uuid             not null,
    signal_type      varchar(16)      not null,
    change_title     varchar(300)     not null,
    title            varchar(255)     not null,
    priority         varchar(16)      not null,
    due_date         date,
    score            double precision not null,
    tier             varchar(16)      not null,
    labels           text[]           not null,
    summary          text             not null,
    risk             text             not null,
    impact           text             not null,
    actions          text[]           not null,
    evidence         text[]           not null,
    ai_drafted       boolean          not null,
    citations        text[]           not null,
    status           varchar(16)      not null,
    created_by       uuid             not null,
    created_at       timestamptz      not null default now(),
    decided_by       uuid,
    decided_at       timestamptz,
    dismiss_reason   varchar(1000),
    issue_key        varchar(64),
    issue_url        varchar(400),
    failure_message  varchar(1000),
    updated_at       timestamptz      not null default now(),
    version          bigint           not null default 0,
    constraint ck_risk_tickets_status
        check (status in ('PROPOSED', 'FILING', 'FILED', 'FAILED', 'DISMISSED')),
    constraint ck_risk_tickets_signal_type check (signal_type in ('VULNERABILITY', 'VENDOR_OUTAGE')),
    constraint ck_risk_tickets_priority check (priority in ('HIGHEST', 'HIGH', 'MEDIUM', 'LOW', 'LOWEST')),
    constraint ck_risk_tickets_tier check (tier in ('CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'NEGLIGIBLE'))
);

-- One live ticket per (service, change): drafting it again is a conflict, not a duplicate. A failed or
-- dismissed one does not count, so it can be drafted afresh.
create unique index ux_risk_tickets_live
    on risk_tickets (org_id, service_id, change_signal_id)
    where status in ('PROPOSED', 'FILING', 'FILED');

create index ix_risk_tickets_org_service on risk_tickets (org_id, service_id, created_at desc);

alter table risk_tickets enable row level security;
alter table risk_tickets force row level security;
create policy tenant_isolation on risk_tickets
    using (org_id = current_setting('aegis.current_org', true)::uuid)
    with check (org_id = current_setting('aegis.current_org', true)::uuid);
