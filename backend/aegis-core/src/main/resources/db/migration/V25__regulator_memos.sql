-- Regulator-notification memos (E8 story 6). A DORA-shaped notification a financial entity may submit to
-- its competent authority about one change: the initial notification of a major ICT-related incident for a
-- vendor outage, or the voluntary notification of a significant cyber threat for a vulnerability. Aegis
-- drafts it only when the change materially affects a service; a second analyst approves it, which
-- finalises it for download; Aegis never sends it. The facts (reference, timeline, affected services and
-- components, third-party provider, evidence) are Aegis's own; the summary, impact, measures and next steps
-- are the AI's draft (ai_drafted) or a plain fallback. body is the rendered memo as it was approved.
--
-- Org-scoped like the inventory: org_id, store-level scoping, and the same RLS backstop as V9 and V18.

create table regulator_memos (
    id               uuid          primary key,
    org_id           uuid          not null,
    change_signal_id uuid          not null,
    signal_type      varchar(16)   not null,
    change_title     varchar(300)  not null,
    kind             varchar(24)   not null,
    reference        varchar(32)   not null,
    detected_at      timestamptz,
    services         text          not null,
    summary          text          not null,
    impact           text          not null,
    measures         text          not null,
    next_steps       text          not null,
    ai_drafted       boolean       not null,
    citations        text[]        not null,
    body             text          not null,
    status           varchar(16)   not null,
    created_by       uuid          not null,
    created_at       timestamptz   not null default now(),
    decided_by       uuid,
    decided_at       timestamptz,
    dismiss_reason   varchar(1000),
    updated_at       timestamptz   not null default now(),
    version          bigint        not null default 0,
    constraint ck_regulator_memos_status check (status in ('PROPOSED', 'APPROVED', 'DISMISSED')),
    constraint ck_regulator_memos_signal_type check (signal_type in ('VULNERABILITY', 'VENDOR_OUTAGE')),
    constraint ck_regulator_memos_kind check (kind in ('MAJOR_INCIDENT', 'CYBER_THREAT'))
);

-- One live memo per change: drafting it again is a conflict. A dismissed one does not count.
create unique index ux_regulator_memos_live
    on regulator_memos (org_id, change_signal_id)
    where status in ('PROPOSED', 'APPROVED');

create index ix_regulator_memos_org_created on regulator_memos (org_id, created_at desc);

alter table regulator_memos enable row level security;
alter table regulator_memos force row level security;
create policy tenant_isolation on regulator_memos
    using (org_id = current_setting('aegis.current_org', true)::uuid)
    with check (org_id = current_setting('aegis.current_org', true)::uuid);
