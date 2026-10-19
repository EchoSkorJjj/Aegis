-- E5 story 3: the vendor status/outage connector's normalised output.
--
-- A vendor incident is the other half of "real-world change": an advisory says a component is
-- vulnerable, an incident says a third party the estate depends on is degraded or down. Matching
-- resolves vendor_key/vendor_name against the inventory's third_party_vendors to reach the affected
-- services, the same way advisories resolve through components.
--
-- Deliberately a separate table from advisories: an incident has no CPE, no CVSS and no affected
-- versions, and an advisory has no status or resolution time. Forcing both into one table would
-- leave half the columns null on every row.

create table vendor_incidents (
    id              uuid         primary key,
    source_id       varchar(64)  not null,
    -- "<vendor-key>:<feed's own incident id>" — scoped by vendor so two vendors cannot collide.
    external_id     text         not null,
    vendor_key      varchar(64)  not null,
    vendor_name     varchar(255) not null,
    title           text         not null,
    -- Feed vocabulary, kept verbatim: Statuspage uses investigating/identified/monitoring/resolved
    -- and none/minor/major/critical. RSS feeds carry neither, so both may be null.
    status          varchar(32),
    impact          varchar(32),
    url             text,
    summary         text,
    started_at      timestamptz,
    last_update_at  timestamptz,
    resolved_at     timestamptz,
    created_at      timestamptz  not null default now(),
    updated_at      timestamptz  not null default now(),
    -- One row per incident per feed; a re-poll of an updated incident updates this row in place.
    constraint uq_vendor_incidents_source_external unique (source_id, external_id),
    constraint ck_vendor_incidents_vendor_key_nonblank check (length(btrim(vendor_key)) > 0)
);

-- Matching's entry point: "which incidents affect this vendor?"
create index ix_vendor_incidents_vendor on vendor_incidents (vendor_key);

create index ix_vendor_incidents_started on vendor_incidents (started_at desc);
