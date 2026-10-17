-- Advisories: make a signal's date its PUBLICATION date, not its last-modified date.
--
-- NVD and OSV both re-stamp `last_modified_at` constantly (OSV especially — it revises records daily),
-- so a feed keyed on last-modified showed every advisory — even one disclosed years ago — as "today",
-- and the date filter could not isolate a real disclosure window. Sourcing occurred_at from
-- published_at (falling back to last_modified/created only when a feed omits it) makes the "when"
-- column, the newest-first sort, and the from/to date filter all reflect when the advisory was actually
-- published. Dedup still keeps the most recently *revised* row as the representative (it orders on
-- last_modified_at directly), so this changes only the displayed/filtered date, not which row wins.
--
-- Recreated wholesale (a view cannot be altered column-by-column); only the advisories branch's
-- occurred_at expression changes — the vendor-incident and news branches are unchanged from V12.

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
    coalesce(a.published_at, a.last_modified_at, a.created_at)    as occurred_at,
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
