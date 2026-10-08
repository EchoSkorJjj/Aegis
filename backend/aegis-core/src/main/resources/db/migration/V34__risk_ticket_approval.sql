-- Who approved a risk ticket, and when, kept apart from decided_by / decided_at (E9 story 3). Those hold whoever
-- decided last, so when a response whose filing failed is dismissed, the dismissal used to replace the approval and
-- the record no longer said who had approved it. Set on approving and never cleared.
--
-- Not backfilled: a migration sees no rows under row-level security on RDS, and a ticket that is filing, filed or
-- failed still names its approver in decided_by; a failed one dismissed later carries it over then.

alter table risk_tickets
    add column approved_by uuid,
    add column approved_at timestamptz;

alter table risk_tickets
    add constraint ck_risk_tickets_approved check ((approved_by is null) = (approved_at is null));
