-- Owner notifications (E9 story 5). When an item — a material vulnerability or vendor outage in a service — needs
-- a decision, each of the service's assigned owners is told once: an in-app notification, and an email through
-- identity-service. One row per owner per item (service, change): the unique key is what makes "once" hold,
-- however often the scheduled job looks and whichever instance runs it.
--
-- email_status: PENDING (to send), SENDING (claimed; a crash here never resends, so nobody is emailed twice),
-- SENT, UNREACHABLE (identity-service won't email that member: they left the organization, or their account is
-- disabled or unverified), FAILED (gave up after repeated errors). Org-scoped like the inventory it points into,
-- with the same RLS backstop; removed with its service.

create table owner_notifications (
    id               uuid             primary key,
    org_id           uuid             not null,
    user_id          uuid             not null,
    service_id       uuid             not null references business_services (id) on delete cascade,
    change_signal_id uuid             not null,
    item_kind        varchar(16)      not null,
    title            varchar(500)     not null,
    service_name     varchar(255)     not null,
    score            double precision not null,
    tier             varchar(16)      not null,
    created_at       timestamptz      not null default now(),
    read_at          timestamptz,
    email_status     varchar(16)      not null default 'PENDING',
    email_attempts   integer          not null default 0,
    emailed_at       timestamptz,
    constraint ux_owner_notifications_item unique (org_id, user_id, service_id, change_signal_id),
    constraint ck_owner_notifications_kind check (item_kind in ('VULNERABILITY', 'VENDOR_OUTAGE')),
    constraint ck_owner_notifications_tier check (tier in ('CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'NEGLIGIBLE')),
    constraint ck_owner_notifications_email
        check (email_status in ('PENDING', 'SENDING', 'SENT', 'UNREACHABLE', 'FAILED'))
);

create index ix_owner_notifications_user on owner_notifications (org_id, user_id, created_at desc);
create index ix_owner_notifications_email on owner_notifications (org_id, email_status)
    where email_status = 'PENDING';

alter table owner_notifications enable row level security;
alter table owner_notifications force row level security;
create policy tenant_isolation on owner_notifications
    using (org_id = current_org()) with check (org_id = current_org());
