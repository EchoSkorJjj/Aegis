-- Epic 6 (OSV support): dedup the change-signals feed by canonical vulnerability id.
--
-- The same real-world vulnerability can now arrive from several feeds — NVD (CVE-…) and OSV
-- (GHSA-…, aliasing that CVE) — as separate advisory rows sharing a canonical_id. The feed shows one
-- entry per vulnerability: DISTINCT ON (canonical_id) keeps the most recently revised advisory as the
-- representative, and its title is the canonical id (the CVE when there is one), so the CVE is what a
-- reader sees regardless of which feed won the tie. Matching re-expands a signal to every advisory
-- sharing the canonical id, so a CVE is matched with both its CPE (NVD) and its purl (OSV) coordinates.
--
-- Recreated wholesale (a view cannot be altered column-by-column); the vendor-incident and news
-- branches are unchanged from V10.

drop view change_signals;

create view change_signals as
select
    a.id                                                          as id,
    'VULNERABILITY'::text                                         as signal_type,
    a.source_id                                                   as source_id,
    a.external_id                                                 as external_id,
    a.canonical_id                                                as title,
    a.description                                                 as summary,
    a.cvss_severity                                               as severity,
    null::text                                                    as url,
    coalesce(a.last_modified_at, a.published_at, a.created_at)    as occurred_at,
    a.created_at                                                  as created_at,
    a.updated_at                                                  as updated_at
from (
    select distinct on (canonical_id) *
    from advisories
    order by canonical_id, last_modified_at desc nulls last, id
) a
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
