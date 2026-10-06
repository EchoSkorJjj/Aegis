-- Matching used to append owner display names to the evidence persisted in tickets, PR bodies and
-- memo bodies. Redact that historical clause without recomputing the captured facts or consulting
-- today's owner assignments: names may have changed and owners may have been removed since drafting.
-- Keep the possible-match warning, line boundaries, array order and all decision metadata.
-- Already redacted clauses and "unassigned" stay unchanged. No external issue or PR is modified.
--
-- Only responses that can still be decided or sent are rewritten: PROPOSED ones, and FAILED tickets and
-- pull requests, which a retry files or opens. A decided response (filed, opened, approved, dismissed) or
-- one being filed or opened right now keeps the record its decision was made on.
--
-- Pull request bodies drafted before V32 joined every explanation on one line, separated by a space, so
-- a clause also ends where the next explanation ("<id> affects …") begins.
create function pg_temp.redact_draft_owner_clause(value text) returns text
    language sql immutable strict
    as $$
        select regexp_replace(value,
            '(; owners: )(?!unassigned(?: \(possible match| \S+ affects |\r?\n|$))[^\r\n]*?'
                || '( \(possible match[^\r\n]*\)|(?= \S+ affects )|\r?\n|$)',
            '\1[REDACTED]\2', 'g');
    $$;

-- Flyway may run as the table owner rather than a superuser. FORCE RLS would otherwise silently
-- hide every tenant's rows. These transactional ALTERs hold the table locks until FORCE is restored;
-- other sessions cannot observe an interval without forced isolation. Policies remain in place.
alter table risk_tickets no force row level security;
alter table upgrade_proposals no force row level security;
alter table regulator_memos no force row level security;

with cleaned as (
    select id, array(
        select pg_temp.redact_draft_owner_clause(line)
        from unnest(evidence) with ordinality as lines(line, position)
        order by position
    ) as evidence
    from risk_tickets
    where status in ('PROPOSED', 'FAILED')
)
update risk_tickets as ticket
set evidence = cleaned.evidence, version = ticket.version + 1, updated_at = now()
from cleaned
where ticket.id = cleaned.id and ticket.evidence is distinct from cleaned.evidence;

update upgrade_proposals
set body = pg_temp.redact_draft_owner_clause(body), version = version + 1, updated_at = now()
where status in ('PROPOSED', 'FAILED') and body is distinct from pg_temp.redact_draft_owner_clause(body);

update regulator_memos
set body = pg_temp.redact_draft_owner_clause(body), version = version + 1, updated_at = now()
where status = 'PROPOSED' and body is distinct from pg_temp.redact_draft_owner_clause(body);

alter table risk_tickets force row level security;
alter table upgrade_proposals force row level security;
alter table regulator_memos force row level security;

drop function pg_temp.redact_draft_owner_clause(text);
