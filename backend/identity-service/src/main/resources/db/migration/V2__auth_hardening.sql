-- Login rate-limiting / account lockout state.
alter table users add column failed_login_attempts integer not null default 0;
alter table users add column locked_until timestamptz;
