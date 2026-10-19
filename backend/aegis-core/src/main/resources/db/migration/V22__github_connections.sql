-- Connect GitHub: which GitHub App installations each organization may act through. GitHub knows only that
-- the App is installed on an account, never which Aegis organization that installation is for, so an
-- organization admin connects it: they sign in to GitHub, Aegis checks they control the installation (the
-- account itself, or an admin of the GitHub organization), and records it here. Minting any installation
-- token — a draft pull request's or an SBOM import's clone — requires this row for the caller's
-- organization. It replaces the deployment-wide AEGIS_GITHUB_ALLOWED_ACCOUNTS setting.
--
-- Org-scoped like the inventory: org_id, store-level scoping, and the same RLS backstop as V9 and V18.

create table github_connections (
    id              uuid         primary key,
    org_id          uuid         not null,
    installation_id bigint       not null,
    account_login   varchar(100) not null,
    account_id      bigint       not null,
    account_type    varchar(20)  not null,
    connected_by    uuid         not null,
    connected_at    timestamptz  not null default now(),
    constraint ux_github_connections_installation unique (org_id, installation_id),
    constraint ck_github_connections_account_type check (account_type in ('User', 'Organization'))
);

alter table github_connections enable row level security;
alter table github_connections force row level security;
create policy tenant_isolation on github_connections
    using (org_id = current_setting('aegis.current_org', true)::uuid)
    with check (org_id = current_setting('aegis.current_org', true)::uuid);

-- The one-time state of a connection in progress: which organization and which person started it. Only a
-- SHA-256 of the state handed to GitHub is kept, it lives ten minutes, and completing the connection
-- consumes it, so a leaked or replayed callback link cannot connect anything.
create table github_connect_states (
    state_hash varchar(64)  primary key,
    org_id     uuid         not null,
    user_id    uuid         not null,
    expires_at timestamptz  not null
);

create index ix_github_connect_states_expiry on github_connect_states (expires_at);

alter table github_connect_states enable row level security;
alter table github_connect_states force row level security;
create policy tenant_isolation on github_connect_states
    using (org_id = current_setting('aegis.current_org', true)::uuid)
    with check (org_id = current_setting('aegis.current_org', true)::uuid);
