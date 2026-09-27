-- Dependency edges belong to the SBOM that declared them. One library version resolves its dependencies differently
-- in each service's lockfile (axios 1.18.0 → follow-redirects 1.15.6 in one service, 1.15.9 in another), and a
-- lockfile moves them without the library's own version changing. Held once for the whole organization, every service
-- was reported with every other service's resolutions, and an edge a lockfile dropped was never removed. So each
-- service keeps the edges its own SBOMs declare; an edge added by hand stays in software_component_dependencies and
-- holds in every service.
create table business_service_component_dependencies (
    business_service_id uuid        not null,
    component_id        uuid        not null,
    dependency_id       uuid        not null,
    org_id              uuid        not null,
    -- An import from the service's repository declared it, so the next one removes it once the repository no longer
    -- does (as business_service_components.from_repository, V28); an uploaded SBOM only adds.
    from_repository     boolean     not null default false,
    created_at          timestamptz not null default now(),
    constraint pk_business_service_component_dependencies
        primary key (business_service_id, component_id, dependency_id),
    constraint fk_service_dependencies_service foreign key (business_service_id)
        references business_services (id) on delete cascade,
    constraint fk_service_dependencies_component foreign key (component_id)
        references software_components (id) on delete cascade,
    constraint fk_service_dependencies_dependency foreign key (dependency_id)
        references software_components (id) on delete cascade,
    constraint ck_service_dependencies_not_self check (component_id <> dependency_id)
);

create index ix_service_dependencies_component on business_service_component_dependencies (component_id);
create index ix_service_dependencies_dependency on business_service_component_dependencies (dependency_id);
create index ix_service_dependencies_org on business_service_component_dependencies (org_id);

-- The tenant backstop every inventory table has (V9).
alter table business_service_component_dependencies enable row level security;
alter table business_service_component_dependencies force row level security;
create policy tenant_isolation on business_service_component_dependencies
    using (org_id = current_setting('aegis.current_org', true)::uuid)
    with check (org_id = current_setting('aegis.current_org', true)::uuid);

-- Nothing recorded which SBOM declared an existing edge, so each goes to every service that reaches it today — from a
-- component it links, through the edges — which leaves every service's graph exactly as it was; the service's next
-- import makes it exact. A service imported from its repository (V28 marked its links) gets its edges marked as that
-- import's, so its next one can remove them.
with recursive reach (business_service_id, component_id) as (
    select business_service_id, component_id from business_service_components
    union
    select reach.business_service_id, edge.dependency_id
    from reach
    join software_component_dependencies edge on edge.component_id = reach.component_id
)
insert into business_service_component_dependencies
    (business_service_id, component_id, dependency_id, org_id, from_repository)
select reach.business_service_id, edge.component_id, edge.dependency_id, edge.org_id,
       exists (select 1 from business_service_components link
               where link.business_service_id = reach.business_service_id and link.from_repository)
from reach
join software_component_dependencies edge on edge.component_id = reach.component_id;

-- The edges a service reaches are its own now. One no service reaches stays, holding in every service: it may have
-- been added by hand.
delete from software_component_dependencies edge
where exists (select 1 from business_service_component_dependencies own
              where own.component_id = edge.component_id and own.dependency_id = edge.dependency_id);
