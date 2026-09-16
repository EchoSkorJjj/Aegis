-- E5 story 2: the NVD connector's normalised output.
--
-- 1. raw_events gains a processing watermark. The store stays append-only in content (payloads are
--    never rewritten); processed_at only records that normalisation has already consumed the row,
--    so a restart resumes instead of re-parsing everything.
-- 2. advisories + advisory_affected_products hold the normalised advisory: the CVSS metric that
--    scoring will use, and the affected (vendor, product, version-range) tuples that matching will
--    resolve against registered components. Deliberately feed-agnostic: source_id distinguishes NVD
--    from the OSV/vendor feeds that land later.

alter table raw_events add column processed_at timestamptz;

-- The normaliser's work queue: unprocessed rows for one feed, oldest first.
create index ix_raw_events_unprocessed on raw_events (source_id, ingested_at) where processed_at is null;

create table advisories (
    id                 uuid          primary key,
    source_id          varchar(64)   not null,
    external_id        varchar(64)   not null,
    description        text,
    published_at       timestamptz,
    last_modified_at   timestamptz,
    cvss_version       varchar(8),
    cvss_vector        varchar(255),
    cvss_base_score    numeric(4,1),
    cvss_severity      varchar(16),
    created_at         timestamptz   not null default now(),
    updated_at         timestamptz   not null default now(),
    -- One row per advisory per feed; a re-poll of a revised CVE updates this row in place.
    constraint uq_advisories_source_external unique (source_id, external_id),
    constraint ck_advisories_external_id_nonblank check (length(btrim(external_id)) > 0)
);

create index ix_advisories_last_modified on advisories (last_modified_at desc);

create table advisory_affected_products (
    id                        uuid         primary key,
    advisory_id               uuid         not null references advisories (id) on delete cascade,
    cpe                       text         not null,
    part                      varchar(8),
    vendor                    varchar(255) not null,
    product                   varchar(255) not null,
    -- Exactly one concrete version, or an open/closed range — never both. Null everywhere means
    -- "all versions affected", which is how NVD expresses a wildcard CPE with no range bounds.
    version                   varchar(128),
    version_start_including   varchar(128),
    version_start_excluding   varchar(128),
    version_end_including     varchar(128),
    version_end_excluding     varchar(128)
);

create index ix_advisory_affected_advisory on advisory_affected_products (advisory_id);

-- Matching's entry point: "which advisories affect this vendor/product?"
create index ix_advisory_affected_vendor_product on advisory_affected_products (vendor, product);
