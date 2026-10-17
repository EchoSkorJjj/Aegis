-- Where a service's code imports its dependencies (E11-3): what an import from the service's repository found when it
-- read the code beside the SBOM. One row per module the code imports in one language — an npm package ('hono',
-- '@hono/node-server'), a Python module ('yaml', 'google.protobuf'), a Java package ('com.fasterxml.jackson.databind')
-- — with how many times it is imported and where: up to the first twenty sites, each 'path:line', repository-relative.
-- Module names, paths and line numbers only; the code itself is never stored. Each import from the repository replaces
-- the service's rows; a service never imported from its repository has none, and its dependencies show as unknown.
create table service_imports (
    business_service_id uuid         not null,
    language            varchar(16)  not null,
    module              varchar(255) not null,
    org_id              uuid         not null,
    site_count          integer      not null,
    sites               text[]       not null default '{}',
    constraint pk_service_imports primary key (business_service_id, language, module),
    constraint fk_service_imports_service foreign key (business_service_id)
        references business_services (id) on delete cascade,
    constraint ck_service_imports_language check (language in ('npm', 'pypi', 'maven')),
    constraint ck_service_imports_count check (site_count > 0)
);

create index ix_service_imports_org on service_imports (org_id);

-- The tenant backstop every inventory table has (V9, read through current_org() since V31).
alter table service_imports enable row level security;
alter table service_imports force row level security;
create policy tenant_isolation on service_imports
    using (org_id = current_org())
    with check (org_id = current_org());

-- The scan behind those rows, on the service: when it ran, at which commit (for links to the lines), the languages it
-- read files of, how many files it read, and — when it stopped before reading everything — why. Cleared with the SBOM
-- import whenever the repository link changes (V19).
alter table business_services
    add column import_scanned_at     timestamptz,
    add column import_scan_revision  varchar(64),
    add column import_scan_languages text[]  not null default '{}',
    add column import_scan_files     integer,
    add column import_scan_limit     varchar(300);
