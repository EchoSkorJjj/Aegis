-- Owner notifications (E9 story 5). When an item — a material vulnerability or vendor outage in a service — needs
-- a decision, each of the service's assigned owners is told once: an in-app notification, and an email through
-- identity-service. One row per owner per item: the unique key is what makes "once" hold, however often the
-- scheduled job looks and whichever instance runs it.
--
-- item_key names the item independently of the feed: a vulnerability by its package and canonical id (a CVE), an
-- outage by its incident. Its change signal is not enough, because which feed's advisory represents a CVE changes
-- whenever another feed revises it (V21); change_signal_id is only the item's page, kept current.
--
-- email_status: PENDING (not yet in an email), SENDING (claimed into the email email_delivery_id; sent again,
-- under the same id, from email_due_at while unconfirmed), SENT, UNREACHABLE (identity-service won't email that
-- member: they left the organization, or their account is disabled or unverified), FAILED (gave up). identity-
-- service sends each delivery id once, so asking again after a timeout or a crash never sends a second email.
-- Org-scoped like the inventory it points into, with the same RLS backstop; removed with its service.

create table owner_notifications (
    id                uuid             primary key,
    org_id            uuid             not null,
    user_id           uuid             not null,
    service_id        uuid             not null references business_services (id) on delete cascade,
    item_key          varchar(512)     not null,
    change_signal_id  uuid             not null,
    item_kind         varchar(16)      not null,
    title             varchar(500)     not null,
    service_name      varchar(255)     not null,
    score             double precision not null,
    tier              varchar(16)      not null,
    created_at        timestamptz      not null default now(),
    read_at           timestamptz,
    email_status      varchar(16)      not null default 'PENDING',
    email_delivery_id uuid,
    email_attempts    integer          not null default 0,
    email_due_at      timestamptz,
    emailed_at        timestamptz,
    constraint ux_owner_notifications_item unique (org_id, user_id, service_id, item_key),
    constraint ck_owner_notifications_kind check (item_kind in ('VULNERABILITY', 'VENDOR_OUTAGE')),
    constraint ck_owner_notifications_tier check (tier in ('CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'NEGLIGIBLE')),
    constraint ck_owner_notifications_email
        check (email_status in ('PENDING', 'SENDING', 'SENT', 'UNREACHABLE', 'FAILED')),
    constraint ck_owner_notifications_delivery
        check (email_status = 'PENDING' or email_delivery_id is not null)
);

create index ix_owner_notifications_user on owner_notifications (org_id, user_id, created_at desc);
create index ix_owner_notifications_email on owner_notifications (org_id, email_status, email_due_at)
    where email_status in ('PENDING', 'SENDING');
create index ix_owner_notifications_delivery on owner_notifications (email_delivery_id)
    where email_delivery_id is not null;

alter table owner_notifications enable row level security;
alter table owner_notifications force row level security;
create policy tenant_isolation on owner_notifications
    using (org_id = current_org()) with check (org_id = current_org());
