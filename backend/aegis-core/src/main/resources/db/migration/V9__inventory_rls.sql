-- Defense-in-depth backstop for tenant isolation (ADR §6): Postgres row-level security on the
-- org-scoped inventory tables, keyed on the transaction-local GUC `aegis.current_org` that the
-- application binds from the verified JWT 'org' claim (see JpaInventoryStore#scope). The USING clause
-- scopes reads/updates/deletes; WITH CHECK stops a write landing a row in another org.
--
-- IMPORTANT — this engages ONLY when aegis-core connects as a NON-SUPERUSER role. A superuser bypasses
-- RLS entirely; FORCE below only subjects the table *owner*. The default local / Testcontainers /
-- docker-compose user is a superuser, so in those setups the application-level scoping in the store is
-- the actual enforcement and this migration is latent hardening. To activate it in a real deployment:
-- create a NOSUPERUSER role, grant it DML on the core schema, and point aegis-core at that role.
--
-- Feed tables (raw_events, advisories, vendor_incidents, news_items) are intentionally NOT covered —
-- they are global reference data (see V8).

alter table business_services enable row level security;
alter table business_services force row level security;
create policy tenant_isolation on business_services
    using (org_id = current_setting('aegis.current_org', true)::uuid)
    with check (org_id = current_setting('aegis.current_org', true)::uuid);

alter table software_components enable row level security;
alter table software_components force row level security;
create policy tenant_isolation on software_components
    using (org_id = current_setting('aegis.current_org', true)::uuid)
    with check (org_id = current_setting('aegis.current_org', true)::uuid);

alter table third_party_vendors enable row level security;
alter table third_party_vendors force row level security;
create policy tenant_isolation on third_party_vendors
    using (org_id = current_setting('aegis.current_org', true)::uuid)
    with check (org_id = current_setting('aegis.current_org', true)::uuid);

alter table owners enable row level security;
alter table owners force row level security;
create policy tenant_isolation on owners
    using (org_id = current_setting('aegis.current_org', true)::uuid)
    with check (org_id = current_setting('aegis.current_org', true)::uuid);

alter table business_service_components enable row level security;
alter table business_service_components force row level security;
create policy tenant_isolation on business_service_components
    using (org_id = current_setting('aegis.current_org', true)::uuid)
    with check (org_id = current_setting('aegis.current_org', true)::uuid);

alter table business_service_vendors enable row level security;
alter table business_service_vendors force row level security;
create policy tenant_isolation on business_service_vendors
    using (org_id = current_setting('aegis.current_org', true)::uuid)
    with check (org_id = current_setting('aegis.current_org', true)::uuid);

alter table business_service_owners enable row level security;
alter table business_service_owners force row level security;
create policy tenant_isolation on business_service_owners
    using (org_id = current_setting('aegis.current_org', true)::uuid)
    with check (org_id = current_setting('aegis.current_org', true)::uuid);

alter table software_component_dependencies enable row level security;
alter table software_component_dependencies force row level security;
create policy tenant_isolation on software_component_dependencies
    using (org_id = current_setting('aegis.current_org', true)::uuid)
    with check (org_id = current_setting('aegis.current_org', true)::uuid);
