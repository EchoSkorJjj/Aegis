-- An upgrade pull request is part of a response (E8 story 5): the risk ticket for one vulnerability in one
-- service, with at most one current pull request for it. That pull request edits every manifest in the
-- service's repository that declares an affected package, so a proposal now holds a list of edits (JSON:
-- componentId, ecosystem, packageName, fromVersion, toVersion, manifestPath) instead of one component. A
-- proposal drafted before responses keeps its one edit and no ticket, and is decided on its own as before.
alter table upgrade_proposals
    add column risk_ticket_id uuid references risk_tickets (id) on delete set null,
    add column edits text;

update upgrade_proposals
    set edits = json_build_array(json_build_object(
            'componentId', component_id,
            'ecosystem', component_ecosystem,
            'packageName', component_name,
            'fromVersion', from_version,
            'toVersion', to_version,
            'manifestPath', manifest_path))::text;

alter table upgrade_proposals alter column edits set not null;

-- Dropping component_id drops the per-component live index with it; which dependency a current pull request
-- already upgrades is now checked when a response is drafted.
alter table upgrade_proposals
    drop column component_id,
    drop column component_ecosystem,
    drop column component_name,
    drop column from_version,
    drop column to_version,
    drop column manifest_path;

create index ix_upgrade_proposals_risk_ticket on upgrade_proposals (risk_ticket_id);

-- One current pull request per response: a dismissed one frees the slot.
create unique index ux_upgrade_proposals_current_response
    on upgrade_proposals (risk_ticket_id)
    where risk_ticket_id is not null and status in ('PROPOSED', 'OPENING', 'OPENED', 'FAILED');
