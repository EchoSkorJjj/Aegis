-- The manifest that declares a service's direct dependency, repository-relative (client/package.json,
-- backend/aegis-core/pom.xml), recorded when the SBOM is generated from the linked repository. An upgrade
-- pull request edits this file; null (an uploaded SBOM, a hand-made link) falls back to the ecosystem's
-- manifest at the repository root.
alter table business_service_components
    add column manifest_path varchar(400);
