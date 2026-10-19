-- Store a service's repository target so SBOM imports and future approved PRs use the same location.
-- A null branch selects the repository default; existing services remain unlinked.
alter table business_services
    add column repository_url varchar(400),
    add column repository_branch varchar(200);

alter table business_services
    add constraint ck_business_services_repository_branch
        check (repository_branch is null or repository_url is not null);
