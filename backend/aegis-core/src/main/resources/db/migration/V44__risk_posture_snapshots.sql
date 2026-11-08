-- V41-V43 are reserved by CS203-129. Latest observed aggregate per organization and UTC day.
-- No findings, code, or personal data is stored.
create table risk_posture_snapshots (
    org_id uuid not null,
    day date not null,
    captured_at timestamptz not null,
    open_items integer not null check (open_items >= 0),
    critical_items integer not null check (critical_items >= 0),
    high_items integer not null check (high_items >= 0),
    primary key (org_id, day),
    check (critical_items + high_items <= open_items)
);

alter table risk_posture_snapshots enable row level security;
alter table risk_posture_snapshots force row level security;
create policy tenant_isolation on risk_posture_snapshots
    using (org_id = current_org()) with check (org_id = current_org());
