-- An import from the service's repository reads the repository's whole dependency list, so it is the authority on
-- what the repository declares: a re-import unlinks the direct dependencies the repository no longer declares.
-- Before this, an upgraded library stayed linked at its old version beside the new one (axios 1.5.0 next to
-- 1.18.0) and was reported as vulnerable for good. Only links an import from the repository made are unlinked;
-- a component linked by hand, or by an uploaded SBOM, stays until someone removes it.
alter table business_service_components
    add column from_repository boolean not null default false;

-- Only an import from the repository records manifests (V20, V26), so those links are certainly its own. A link an
-- earlier import made without a manifest is marked by the next import that still finds it.
update business_service_components
    set from_repository = true
    where cardinality(manifest_paths) > 0;
