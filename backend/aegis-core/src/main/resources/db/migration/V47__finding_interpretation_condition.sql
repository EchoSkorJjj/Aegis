-- What must be true in the service for the change to harm it, and whether the sources show that. Readings written
-- before this have none, and are read again: the fingerprint now carries the reading's version.
alter table finding_interpretations
    add column trigger_condition varchar(1000),
    add column condition_met varchar(8) not null default 'UNKNOWN',
    add constraint ck_finding_interpretations_condition_met check (condition_met in ('UNKNOWN', 'YES', 'NO'));
