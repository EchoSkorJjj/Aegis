-- The JavaScript lockfiles that resolve each of a service's direct dependencies (package-lock.json, pnpm-lock.yaml,
-- yarn.lock), as an import from the service's repository read them. A package the service's manifests don't declare,
-- one another package pulls in, is upgraded with an override in the project that lockfile belongs to, in its package
-- manager's own syntax (npm overrides, pnpm overrides, yarn resolutions). A link with lockfiles and no manifest is a
-- package a lockfile resolves that no project declares. Repository-relative paths; empty for anything linked by hand,
-- by an uploaded SBOM, or by an import before this one (a re-import records them).
alter table business_service_components
    add column lockfile_paths text[] not null default '{}';
