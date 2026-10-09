-- aegis-core's owner notification emails (E9 story 5), one row per email sent. aegis-core names each email with a
-- delivery id and asks again under the same id whenever it can't tell whether an earlier request went through (a
-- timeout, or a crash before it recorded the answer). An id already here is answered as sent without sending
-- again, so nobody gets the same email twice. The row is written in the transaction that sends the email: a send
-- that fails leaves no row, so asking again sends it.

create table owner_email_deliveries (
    id      uuid        primary key,
    sent_at timestamptz not null default now()
);
