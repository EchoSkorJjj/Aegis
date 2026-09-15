-- Org membership, phase 1 of ADR 0004 (supersedes ADR 0003's one-user-one-org). A user may belong to
-- MANY organizations via the organization_members join, each membership carrying its own org-scoped
-- roles. This migration EXPANDS the model non-destructively: it backfills memberships from the current
-- users.org_id / user_roles but leaves user_roles intact. The contract step that drops the now-redundant
-- org-scoped roles from user_roles lands later (V15, ADR 0004 Phase 3), once tokens read memberships.

create table organization_members (
    id         uuid         primary key,
    org_id     uuid         not null references organizations (id) on delete cascade,
    user_id    uuid         not null references users (id)         on delete cascade,
    status     varchar(20)  not null default 'ACTIVE',   -- INVITED | ACTIVE | SUSPENDED
    created_at timestamptz  not null default now(),
    updated_at timestamptz  not null default now(),
    unique (org_id, user_id)                             -- a user joins a given org at most once
);

create index ix_org_members_user on organization_members (user_id);

create table organization_member_roles (
    member_id uuid        not null references organization_members (id) on delete cascade,
    role      varchar(40) not null,
    primary key (member_id, role)
);

-- Backfill: one ACTIVE membership per user that has an org today (users PK is `id`).
insert into organization_members (id, org_id, user_id, status)
    select gen_random_uuid(), org_id, id, 'ACTIVE'
    from users
    where org_id is not null;

-- Copy each such user's org-scoped roles onto the membership; platform roles stay on user_roles.
insert into organization_member_roles (member_id, role)
    select m.id, ur.role
    from organization_members m
    join user_roles ur on ur.user_id = m.user_id
    where ur.role in ('ORG_ADMIN', 'SERVICE_OWNER', 'ANALYST', 'COMPLIANCE_ADMIN');

-- Repurpose users.org_id as the remembered "active org" hint for login (nullable; not a membership).
alter table users rename column org_id to last_active_org_id;
alter index ix_users_org_id rename to ix_users_last_active_org_id;
