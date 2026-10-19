-- When and how the service's SBOM was last imported, so the UI can show it and ask for an access token
-- up front when the last import needed one. How, never the credential itself: UPLOAD (a CycloneDX file
-- posted to the API), PUBLIC (cloned without credentials), INTEGRATION (a host integration such as the
-- GitHub App) or TOKEN (a pasted access token, used once and not stored). Existing services have none.
alter table business_services
    add column sbom_imported_at timestamptz,
    add column sbom_import_source varchar(16);

alter table business_services
    add constraint ck_business_services_sbom_import_source
        check (sbom_import_source is null or sbom_import_source in ('UPLOAD', 'PUBLIC', 'INTEGRATION', 'TOKEN'));

alter table business_services
    add constraint ck_business_services_sbom_import
        check ((sbom_imported_at is null) = (sbom_import_source is null));
