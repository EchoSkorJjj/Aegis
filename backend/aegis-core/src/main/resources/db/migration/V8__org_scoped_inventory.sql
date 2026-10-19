-- Multi-tenancy for the inventory map (ADR §6). Every inventory row is scoped to an organization:
-- org_id is the tenant, taken from the verified JWT 'org' claim on writes (never from the client).
-- Uniqueness becomes per-org, so two organizations may name a service, component, vendor or owner
-- alike. Feed tables (raw_events, advisories, vendor_incidents, news_items) stay GLOBAL on purpose —
-- a CVE or outage is the same fact for every tenant; the tenant boundary is the matching that will
-- join them to an org's components, not the feeds themselves.
--
-- Existing rows (including any demo estate) are backfilled to a well-known demo org so the NOT NULL
-- can be enforced without a destructive reset. RLS enforcement is added separately in V9.

-- 1. Add org_id, nullable at first so the backfill can populate it.
alter table business_services              add column org_id uuid;
alter table software_components            add column org_id uuid;
alter table third_party_vendors            add column org_id uuid;
alter table owners                         add column org_id uuid;
alter table business_service_components    add column org_id uuid;
alter table business_service_vendors       add column org_id uuid;
alter table business_service_owners        add column org_id uuid;
alter table software_component_dependencies add column org_id uuid;

-- 2. Backfill pre-tenant rows to the demo org (must match DemoEstateSeeder.DEMO_ORG_ID).
update business_services              set org_id = '11111111-1111-1111-1111-111111111111' where org_id is null;
update software_components            set org_id = '11111111-1111-1111-1111-111111111111' where org_id is null;
update third_party_vendors            set org_id = '11111111-1111-1111-1111-111111111111' where org_id is null;
update owners                         set org_id = '11111111-1111-1111-1111-111111111111' where org_id is null;
update business_service_components    set org_id = '11111111-1111-1111-1111-111111111111' where org_id is null;
update business_service_vendors       set org_id = '11111111-1111-1111-1111-111111111111' where org_id is null;
update business_service_owners        set org_id = '11111111-1111-1111-1111-111111111111' where org_id is null;
update software_component_dependencies set org_id = '11111111-1111-1111-1111-111111111111' where org_id is null;

-- 3. Enforce NOT NULL now that every row has an org.
alter table business_services              alter column org_id set not null;
alter table software_components            alter column org_id set not null;
alter table third_party_vendors            alter column org_id set not null;
alter table owners                         alter column org_id set not null;
alter table business_service_components    alter column org_id set not null;
alter table business_service_vendors       alter column org_id set not null;
alter table business_service_owners        alter column org_id set not null;
alter table software_component_dependencies alter column org_id set not null;

-- 4. Uniqueness becomes per-org (drop the global unique indexes, recreate them keyed on org_id).
drop index uq_business_services_name_ci;
create unique index uq_business_services_org_name_ci on business_services (org_id, lower(name));

drop index uq_software_components_coordinates_ci;
create unique index uq_software_components_org_coordinates_ci
    on software_components (org_id, lower(ecosystem), lower(name), lower(version));

drop index uq_third_party_vendors_name_ci;
create unique index uq_third_party_vendors_org_name_ci on third_party_vendors (org_id, lower(name));

drop index uq_owners_name_ci;
create unique index uq_owners_org_name_ci on owners (org_id, lower(name));

-- 5. Index org_id on every table for the tenant-scoped scans and the RLS predicate.
create index ix_business_services_org on business_services (org_id);
create index ix_software_components_org on software_components (org_id);
create index ix_third_party_vendors_org on third_party_vendors (org_id);
create index ix_owners_org on owners (org_id);
create index ix_service_components_org on business_service_components (org_id);
create index ix_service_vendors_org on business_service_vendors (org_id);
create index ix_service_owners_org on business_service_owners (org_id);
create index ix_component_dependencies_org on software_component_dependencies (org_id);
