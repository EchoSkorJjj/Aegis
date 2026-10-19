-- CS203-76: safe-upgrade draft pull requests (E8 story 4), with the decision step they need (an early
-- slice of E9). An upgrade proposal is the reviewed plan — which package moves from which version to which
-- in which manifest, and the exact pull-request title and body — that a person approves before Aegis opens
-- a draft pull request. Nothing is opened without that approval, and Aegis never merges.
--
-- Org-scoped like the inventory it points into: org_id, store-level scoping, and the same RLS backstop V9
-- gives the inventory tables (latent under the default superuser; engages under a non-superuser role).
-- A proposal is removed with its service. Timestamps come from the database, as for the inventory.

create table upgrade_proposals (
    id                  uuid          primary key,
    org_id              uuid          not null,
    service_id          uuid          not null references business_services (id) on delete cascade,
    change_signal_id    uuid          not null,
    vulnerability_id    varchar(64)   not null,
    component_id        uuid          not null,
    component_ecosystem varchar(64)   not null,
    component_name      varchar(255)  not null,
    from_version        varchar(128)  not null,
    to_version          varchar(128)  not null,
    manifest_path       varchar(400)  not null,
    repository_url      varchar(400)  not null,
    base_branch         varchar(200),
    head_branch         varchar(200)  not null,
    match_confidence    varchar(16)   not null,
    title               varchar(256)  not null,
    body                text          not null,
    status              varchar(16)   not null,
    created_by          uuid          not null,
    created_at          timestamptz   not null default now(),
    decided_by          uuid,
    decided_at          timestamptz,
    dismiss_reason      varchar(1000),
    pull_request_url    varchar(400),
    pull_request_number varchar(32),
    failure_message     varchar(1000),
    updated_at          timestamptz   not null default now(),
    version             bigint        not null default 0,
    constraint ck_upgrade_proposals_status
        check (status in ('PROPOSED', 'OPENING', 'OPENED', 'FAILED', 'DISMISSED')),
    constraint ck_upgrade_proposals_match_confidence
        check (match_confidence in ('CONFIRMED', 'POSSIBLE'))
);

-- One live proposal per (service, change, component): drafting it again is a conflict, not a duplicate.
-- A failed or dismissed one does not count, so the fix can be drafted afresh.
create unique index ux_upgrade_proposals_live
    on upgrade_proposals (org_id, service_id, change_signal_id, component_id)
    where status in ('PROPOSED', 'OPENING', 'OPENED');

create index ix_upgrade_proposals_org_service on upgrade_proposals (org_id, service_id, created_at desc);

alter table upgrade_proposals enable row level security;
alter table upgrade_proposals force row level security;
create policy tenant_isolation on upgrade_proposals
    using (org_id = current_setting('aegis.current_org', true)::uuid)
    with check (org_id = current_setting('aegis.current_org', true)::uuid);

-- The safe version is checked against every advisory that could name the package (UpgradeTargets). These
-- expression indexes back that lookup; each expression must stay identical to the one in
-- AffectedProductJpaRepository#findAdvisoryIdsNaming or the planner cannot use it.
create index ix_advisory_affected_products_package_key
    on advisory_affected_products ((regexp_replace(lower(package_name), '[-_.]+', '-', 'g')));
create index ix_advisory_affected_products_product_key
    on advisory_affected_products ((btrim(regexp_replace(lower(product), '[^a-z0-9]+', '-', 'g'), '-')));
