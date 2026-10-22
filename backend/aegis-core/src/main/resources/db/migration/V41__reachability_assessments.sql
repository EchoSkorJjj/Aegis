-- The AI's suggestion on whether a service's code calls the vulnerable function of a package it imports (E11-5):
-- asked for an item, the AI read a few lines around each place the code imports the package, at the commit the last
-- import from the repository read, and answered CALLED, NOT_CALLED or CANT_TELL. Only the answer is kept — the
-- verdict, its rationale (redacted) and the line ranges it cites, each 'start:end:line:path' — never the code. One per
-- service, package, vulnerability (its canonical id) and commit: a new import is a new commit, so the item is
-- unassessed again; asking again replaces the row. A suggestion for a person: nothing reads it to score or decide.
create table reachability_assessments (
    id                  uuid          not null,
    org_id              uuid          not null,
    business_service_id uuid          not null,
    ecosystem           varchar(32)   not null,
    package_name        varchar(255)  not null,
    vulnerability_id    varchar(128)  not null,
    scan_revision       varchar(64)   not null,
    verdict             varchar(16)   not null,
    rationale           varchar(4000) not null,
    cited_lines         text[]        not null default '{}',
    requested_by        uuid,
    assessed_at         timestamptz   not null,
    constraint pk_reachability_assessments primary key (id),
    constraint fk_reachability_assessments_service foreign key (business_service_id)
        references business_services (id) on delete cascade,
    constraint uq_reachability_assessments unique (business_service_id, ecosystem, package_name, vulnerability_id,
        scan_revision),
    constraint ck_reachability_assessments_verdict check (verdict in ('CALLED', 'NOT_CALLED', 'CANT_TELL'))
);

create index ix_reachability_assessments_org on reachability_assessments (org_id);

-- The tenant backstop every org-scoped table has (V9, read through current_org() since V31).
alter table reachability_assessments enable row level security;
alter table reachability_assessments force row level security;
create policy tenant_isolation on reachability_assessments
    using (org_id = current_org())
    with check (org_id = current_org());
