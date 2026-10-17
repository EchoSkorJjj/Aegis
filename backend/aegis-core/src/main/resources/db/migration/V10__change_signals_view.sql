-- CS203-45: a unified, read-only projection over the three normalised ingestion outputs (advisories,
-- vendor incidents, security news) so matching, scoring, and the frontend can treat "a real-world
-- change" uniformly, without a fourth writable table duplicating what the connectors already persist.
--
-- This is a view, not a table: nothing is ever written through it. Each connector's own normaliser
-- (NvdNormalizer / VendorStatusNormalizer / NewsNormalizer) remains the sole writer of its source
-- table, and each source's own idempotent upsert on (source_id, external_id) is unaffected — this
-- view only unifies how the already-idempotent output is read.
--
-- occurred_at is the one timestamp every branch produces, coalesced from that source's best signal of
-- "when did this last change" (a revision date, an incident update, a publish date) down to created_at
-- as a last resort — it is what the unified list sorts by.

create view change_signals as
select
    a.id                                                          as id,
    'VULNERABILITY'::text                                         as signal_type,
    a.source_id                                                   as source_id,
    a.external_id                                                 as external_id,
    a.external_id                                                 as title,
    a.description                                                 as summary,
    a.cvss_severity                                               as severity,
    null::text                                                    as url,
    coalesce(a.last_modified_at, a.published_at, a.created_at)    as occurred_at,
    a.created_at                                                  as created_at,
    a.updated_at                                                  as updated_at
from advisories a
union all
select
    vi.id,
    'VENDOR_OUTAGE'::text,
    vi.source_id,
    vi.external_id,
    vi.title,
    vi.summary,
    vi.impact,
    vi.url,
    coalesce(vi.last_update_at, vi.started_at, vi.created_at),
    vi.created_at,
    vi.updated_at
from vendor_incidents vi
union all
select
    ni.id,
    'SECURITY_NEWS'::text,
    ni.source_id,
    ni.external_id,
    ni.title,
    ni.summary,
    null::text,
    ni.url,
    coalesce(ni.published_at, ni.created_at),
    ni.created_at,
    ni.updated_at
from news_items ni;
