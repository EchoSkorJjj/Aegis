-- Epic 6 (OSV support): make advisories ecosystem-native and cross-source deduplicable.
--
-- 1. advisory_affected_products becomes coordinate-agnostic. A row is either CPE-shaped (NVD: cpe /
--    vendor / product) or purl-shaped (OSV: ecosystem / package_name), so the three CPE columns
--    drop NOT NULL and two purl columns are added. The version-range columns are unchanged — OSV's
--    introduced/fixed/last_affected map onto version_start_including / version_end_excluding /
--    version_end_including.
-- 2. advisories gain a canonical_id: the CVE id when the vulnerability has one (NVD's external_id, or
--    an OSV record's CVE alias), otherwise the record's own id. The same real-world vulnerability
--    reported by several feeds shares one canonical_id, which the change-signals feed dedups on and
--    matching expands a signal back to.

alter table advisory_affected_products
    alter column cpe drop not null,
    alter column vendor drop not null,
    alter column product drop not null,
    add column ecosystem    varchar(64),
    add column package_name  varchar(255);

-- Matching's purl entry point: "which advisories affect this ecosystem's package?"
create index ix_advisory_affected_ecosystem_package
    on advisory_affected_products (ecosystem, package_name);

alter table advisories add column canonical_id varchar(64);
-- Existing rows are all NVD, whose external_id already is the CVE — so it is its own canonical id.
update advisories set canonical_id = external_id where canonical_id is null;
alter table advisories alter column canonical_id set not null;

create index ix_advisories_canonical on advisories (canonical_id);
