-- Connect Jira: where each organization's approved risk tickets are filed (E8 story 5). An organization
-- admin signs in to Atlassian (OAuth 2.0, 3LO) and grants Aegis one Jira site; Aegis keeps the tokens it
-- was given — sealed with AES-256-GCM under AEGIS_SECRETS_KEY, bound to the organization, never in the
-- clear — and the project and issue type the admin chose. One connection per organization.
--
-- Org-scoped like the inventory: org_id, store-level scoping, and the same RLS backstop as V9 and V18.

create table jira_connections (
    org_id            uuid         primary key,
    cloud_id          varchar(64)  not null,
    site_name         varchar(255) not null,
    site_url          varchar(400) not null,
    project_key       varchar(255),
    issue_type        varchar(255),
    access_token      text         not null,
    access_expires_at timestamptz  not null,
    refresh_token     text         not null,
    connected_by      uuid         not null,
    connected_at      timestamptz  not null default now(),
    updated_at        timestamptz  not null default now()
);

alter table jira_connections enable row level security;
alter table jira_connections force row level security;
create policy tenant_isolation on jira_connections
    using (org_id = current_setting('aegis.current_org', true)::uuid)
    with check (org_id = current_setting('aegis.current_org', true)::uuid);

-- The one-time state of a connection in progress, as for GitHub (V22): only its SHA-256 is kept, it lives
-- ten minutes, and completing the connection consumes it.
create table jira_connect_states (
    state_hash varchar(64)  primary key,
    org_id     uuid         not null,
    user_id    uuid         not null,
    expires_at timestamptz  not null
);

create index ix_jira_connect_states_expiry on jira_connect_states (expires_at);

alter table jira_connect_states enable row level security;
alter table jira_connect_states force row level security;
create policy tenant_isolation on jira_connect_states
    using (org_id = current_setting('aegis.current_org', true)::uuid)
    with check (org_id = current_setting('aegis.current_org', true)::uuid);
