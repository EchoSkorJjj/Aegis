-- Every manifest that declares a service's direct dependency, not only the first one found (V20). The same
-- library version declared in client/package.json and server/package.json is one component, and an upgrade
-- pull request has to edit both: with one recorded path it edited one and left the other vulnerable.
-- Repository-relative paths, each at most 400 characters (checked when the SBOM is read).
alter table business_service_components
    add column manifest_paths text[] not null default '{}';

update business_service_components
    set manifest_paths = array[manifest_path]
    where manifest_path is not null;

alter table business_service_components
    drop column manifest_path;
