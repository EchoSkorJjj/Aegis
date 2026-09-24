-- Owners become real people: organization members holding the SERVICE_OWNER role.
--
-- An owner used to be a free-text name ("Payments Team"). Nothing downstream can act on a name — it
-- can't be matched to a signed-in user (their "my services" view, owner-only decisions), routed to, or
-- emailed. An owner row now stands for one identity-service user, keyed by that user's id (the JWT
-- `sub`) within the org; `name` and `email` are a snapshot refreshed on every assignment, for display
-- and for later notifications. aegis-core never trusts a client-supplied user id: an assignment first
-- verifies the member — and that they hold SERVICE_OWNER — against identity-service with the caller's
-- own token (so a user outside the caller's org is simply not found).
--
-- Existing free-text owners cannot be mapped to users, so they and their service links are removed;
-- a service's owners are re-assigned from its page. TRUNCATE rather than DELETE: row-level security
-- (V9) filters DELETE by the session's tenant, which a migration does not have, whereas TRUNCATE is not
-- subject to RLS. business_service_owners is the only table referencing owners.

truncate table business_service_owners, owners;

alter table owners
    add column user_id uuid not null,
    -- Mirrors identity-service's users.email; null if identity ever reports none (e.g. a future SSO user).
    add column email   varchar(255);

-- Two people may share a display name; an owner is unique by who they are, per org.
drop index uq_owners_org_name_ci;
create unique index uq_owners_org_user on owners (org_id, user_id);
