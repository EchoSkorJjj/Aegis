-- Multi-tenancy (ADR 0003): retire the ADMIN role in favour of PLATFORM_ADMIN (the org-less
-- operator). Existing platform admins are promoted; org-scoped administration now uses ORG_ADMIN.
-- Insert-then-delete (not a plain UPDATE) so a user who somehow holds both roles keeps a single
-- (user_id, role) row rather than colliding on the primary key.

insert into user_roles (user_id, role)
    select user_id, 'PLATFORM_ADMIN' from user_roles where role = 'ADMIN'
    on conflict (user_id, role) do nothing;

delete from user_roles where role = 'ADMIN';
