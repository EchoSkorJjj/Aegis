-- Multi-tenancy, phase 1 (ADR 0003): organizations are the tenant boundary. Every org member's
-- users.org_id points here; the platform admin is org-less (org_id null). Org-scoping of data and
-- the signup / invite flows land in later phases — this migration only introduces the model.

create table organizations (
    id         uuid         primary key,
    name       varchar(255) not null,
    created_at timestamptz  not null default now(),
    updated_at timestamptz  not null default now()
);

alter table users
    add column org_id uuid references organizations (id);

create index ix_users_org_id on users (org_id);
