-- The change-signals feed shows one row per vulnerability: of the advisories sharing a canonical_id (the
-- same CVE from NVD, OSV, ...), the most recently revised one (V12). The view worked that out on every
-- query with DISTINCT ON over the whole advisories table, so even a lookup by id sorted every advisory —
-- about 36 ms at 46k advisories, and growing with the feeds. Recording the winner once, when an advisory
-- is written, makes a lookup by id a primary-key hit and lets every list read only the winners.
--
-- advisories.representative marks that row. A trigger keeps it right on every write path — the
-- connectors' upserts, backfills, tests — so it can never drift from the rule the view used to apply:
-- order by last_modified_at desc nulls last, then id. At most one representative per vulnerability is
-- enforced by a partial unique index, which also serves lookups of the feed by CVE.

alter table advisories add column representative boolean not null default false;

update advisories a
set representative = true
from (
    select distinct on (canonical_id) id
    from advisories
    order by canonical_id, last_modified_at desc nulls last, id
) winner
where a.id = winner.id;

create unique index ux_advisories_representative on advisories (canonical_id) where representative;

-- Re-elect one vulnerability's representative. Serialised per vulnerability, so two feeds writing the same
-- CVE at once cannot both win; the loser is cleared before the winner is set, as the unique index is
-- checked row by row.
create function advisories_elect_representative(vulnerability varchar) returns void
language plpgsql
set search_path from current -- the migration's schema; the application's connections don't search it
as $$
declare
    winner uuid;
begin
    perform pg_advisory_xact_lock(hashtextextended('advisories.representative:' || vulnerability, 0));
    select id into winner
    from advisories
    where canonical_id = vulnerability
    order by last_modified_at desc nulls last, id
    limit 1;
    update advisories set representative = false
    where canonical_id = vulnerability and representative and id is distinct from winner;
    if winner is not null then
        update advisories set representative = true where id = winner and not representative;
    end if;
end
$$;

-- Fires only for the columns the rule reads, so the re-election's own updates don't fire it again. When an
-- advisory moves to another vulnerability both are re-elected, in a fixed order, so two such moves never
-- wait on each other's locks.
create function advisories_representative_changed() returns trigger
language plpgsql
set search_path from current
as $$
begin
    if tg_op = 'INSERT' then
        perform advisories_elect_representative(new.canonical_id);
    elsif tg_op = 'DELETE' then
        perform advisories_elect_representative(old.canonical_id);
    elsif new.canonical_id is distinct from old.canonical_id then
        perform advisories_elect_representative(least(old.canonical_id, new.canonical_id));
        perform advisories_elect_representative(greatest(old.canonical_id, new.canonical_id));
    else
        perform advisories_elect_representative(new.canonical_id);
    end if;
    return null;
end
$$;

create trigger trg_advisories_representative
    after insert or delete or update of canonical_id, last_modified_at on advisories
    for each row execute function advisories_representative_changed();

-- A report moving to another vulnerability (an OSV record that gains a CVE alias) arrives there unflagged:
-- still flagged, it would collide with that vulnerability's representative in the unique index before the
-- re-election above could decide between them.
create function advisories_representative_moving() returns trigger
language plpgsql as $$
begin
    new.representative := false;
    return new;
end
$$;

create trigger trg_advisories_representative_moving
    before update of canonical_id on advisories
    for each row
    when (new.canonical_id is distinct from old.canonical_id)
    execute function advisories_representative_moving();

-- The same columns as V14; only the advisories branch changes, from DISTINCT ON to the recorded winner.
create or replace view change_signals as
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
from advisories a
where a.representative
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
