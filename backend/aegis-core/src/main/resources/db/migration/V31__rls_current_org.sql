-- Row-level security: a query that runs without an organization now sees no rows, instead of failing.
--
-- Every tenant_isolation policy compared org_id with current_setting('aegis.current_org', true)::uuid. Once a
-- connection has had the setting bound (the stores bind it per transaction), current_setting returns '' rather
-- than null in later transactions on that connection, and ''::uuid is an error. So a query that ran without an
-- organization saw no rows on a fresh connection but failed on a reused one. current_org() reads '' as null:
-- the policy is then false for every row, the same on any connection.
--
-- The policies bind current_org() by reference, so a later migration's policy uses it too:
--   using (org_id = current_org()) with check (org_id = current_org())
create function current_org() returns uuid
    language sql stable parallel safe
    return nullif(current_setting('aegis.current_org', true), '')::uuid;

alter policy tenant_isolation on business_services
    using (org_id = current_org()) with check (org_id = current_org());
alter policy tenant_isolation on software_components
    using (org_id = current_org()) with check (org_id = current_org());
alter policy tenant_isolation on third_party_vendors
    using (org_id = current_org()) with check (org_id = current_org());
alter policy tenant_isolation on owners
    using (org_id = current_org()) with check (org_id = current_org());
alter policy tenant_isolation on business_service_components
    using (org_id = current_org()) with check (org_id = current_org());
alter policy tenant_isolation on business_service_vendors
    using (org_id = current_org()) with check (org_id = current_org());
alter policy tenant_isolation on business_service_owners
    using (org_id = current_org()) with check (org_id = current_org());
alter policy tenant_isolation on software_component_dependencies
    using (org_id = current_org()) with check (org_id = current_org());
alter policy tenant_isolation on upgrade_proposals
    using (org_id = current_org()) with check (org_id = current_org());
alter policy tenant_isolation on github_connections
    using (org_id = current_org()) with check (org_id = current_org());
alter policy tenant_isolation on github_connect_states
    using (org_id = current_org()) with check (org_id = current_org());
alter policy tenant_isolation on risk_tickets
    using (org_id = current_org()) with check (org_id = current_org());
alter policy tenant_isolation on jira_connections
    using (org_id = current_org()) with check (org_id = current_org());
alter policy tenant_isolation on jira_connect_states
    using (org_id = current_org()) with check (org_id = current_org());
alter policy tenant_isolation on regulator_memos
    using (org_id = current_org()) with check (org_id = current_org());
alter policy tenant_isolation on business_service_component_dependencies
    using (org_id = current_org()) with check (org_id = current_org());
