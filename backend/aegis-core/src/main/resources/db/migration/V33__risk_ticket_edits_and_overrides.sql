-- A reviewer may edit or override a drafted response before deciding it (E9 story 3), and both stay on record.
--
-- Edit: the summary, risk, impact and actions are rewritten in place; the draft as it was before the first edit is
-- kept in drafted_* with who edited it last and when. Override: the priority and due date (Aegis's own, from the
-- impact tier) are replaced, with a required reason; Aegis's assessment is kept in assessed_*. What gets filed is
-- always the ticket's own columns. Both are only possible while the ticket awaits a decision.

alter table risk_tickets
    add column drafted_summary   text,
    add column drafted_risk      text,
    add column drafted_impact    text,
    add column drafted_actions   text[],
    add column edited_by         uuid,
    add column edited_at         timestamptz,
    add column assessed_priority varchar(16),
    add column assessed_due_date date,
    add column override_reason   varchar(1000),
    add column overridden_by     uuid,
    add column overridden_at     timestamptz;

alter table risk_tickets
    add constraint ck_risk_tickets_edited check (
        (edited_at is null and edited_by is null and drafted_summary is null and drafted_risk is null
            and drafted_impact is null and drafted_actions is null)
        or (edited_at is not null and edited_by is not null and drafted_summary is not null
            and drafted_risk is not null and drafted_impact is not null and drafted_actions is not null)),
    add constraint ck_risk_tickets_overridden check (
        (overridden_at is null and overridden_by is null and override_reason is null and assessed_priority is null
            and assessed_due_date is null)
        or (overridden_at is not null and overridden_by is not null and override_reason is not null
            and assessed_priority is not null)),
    add constraint ck_risk_tickets_assessed_priority
        check (assessed_priority in ('HIGHEST', 'HIGH', 'MEDIUM', 'LOW', 'LOWEST'));
