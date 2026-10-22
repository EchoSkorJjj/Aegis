-- An advisory's full text and fix commits (E11-5), for an assessment of whether a service's code uses what an advisory
-- says is vulnerable. description stays what the feed shows (OSV's one-line summary, NVD's description); details is the
-- rest of OSV's text, which the OSV parser used to drop; fix_commits are the commits among an advisory's references
-- (GHSA marks them WEB, not FIX), as https://github.com/{owner}/{repo}/commit/{sha} or
-- https://gitlab.com/{path}/-/commit/{sha}, at most five — the parsers fill both from now on (CommitUrl).
--
-- Advisories ingested before this migration are filled in from the newest raw payload their feed sent: these are
-- feed tables, global and outside row-level security, so this works under any role. Written to run more than once
-- (it only fills what is still empty); a payload that isn't valid JSON is skipped.
alter table advisories add column if not exists details text;
alter table advisories add column if not exists fix_commits text[] not null default '{}';

with latest as (
    -- The newest valid payload per advisory, chosen before any is parsed into jsonb.
    select distinct on (source_id, split_part(external_id, '@', 1)) id
    from raw_events
    where source_id in ('osv', 'nvd') and external_id is not null and pg_input_is_valid(payload, 'jsonb')
    order by source_id, split_part(external_id, '@', 1), ingested_at desc
),
newest as (
    select r.source_id, split_part(r.external_id, '@', 1) as advisory, r.payload::jsonb as document
    from raw_events r
    join latest l on l.id = r.id
),
details as (
    -- OSV only: with no summary, the details already are the description.
    select advisory, nullif(btrim(document ->> 'details'), '') as details
    from newest
    where source_id = 'osv' and nullif(btrim(document ->> 'summary'), '') is not null
),
references_ as (
    select n.source_id, n.advisory, r.ordinality, r.reference ->> 'url' as url
    from newest n
    cross join lateral jsonb_array_elements(
        case when jsonb_typeof(n.document -> 'references') = 'array' then n.document -> 'references'
             else '[]'::jsonb end) with ordinality as r(reference, ordinality)
),
commits as (
    select source_id, advisory, ordinality,
           case
               when github is not null then 'https://github.com/' || github[1] || '/' || github[2] || '/commit/'
                    || lower(github[3])
               when gitlab is not null then 'https://gitlab.com/' || gitlab[1] || '/-/commit/' || lower(gitlab[2])
           end as commit
    from (
        select source_id, advisory, ordinality,
               regexp_match(btrim(url), '^https?://(?:www\.)?github\.com/((?!\.{1,2}/)[A-Za-z0-9_.-]{1,100})/'
                   || '((?!\.{1,2}/)[A-Za-z0-9_.-]{1,100})/(?:pull/[0-9]{1,10}/)?commits?/([0-9a-fA-F]{7,40})'
                   || '(?:[/?#].*)?$') as github,
               regexp_match(btrim(url), '^https?://gitlab\.com/((?:(?!\.{1,2}/)[A-Za-z0-9_.-]{1,100}/){1,5}'
                   || '(?!\.{1,2}/)[A-Za-z0-9_.-]{1,100})/-/commit/([0-9a-fA-F]{7,40})(?:[/?#].*)?$') as gitlab
        from references_
    ) matched
),
firsts as (
    select source_id, advisory, commit, min(ordinality) as ordinality
    from commits
    where commit is not null
    group by source_id, advisory, commit
),
fix_commits as (
    select source_id, advisory, (array_agg(commit order by ordinality))[1:5] as commits
    from firsts
    group by source_id, advisory
)
update advisories a
set details = coalesce(a.details, d.details),
    fix_commits = case when cardinality(a.fix_commits) = 0 then coalesce(f.commits, '{}') else a.fix_commits end
from newest n
left join details d on n.source_id = 'osv' and d.advisory = n.advisory
left join fix_commits f on f.source_id = n.source_id and f.advisory = n.advisory
where a.source_id = n.source_id and a.external_id = n.advisory
  and ((a.details is null and d.details is not null)
       or (cardinality(a.fix_commits) = 0 and f.commits is not null));
