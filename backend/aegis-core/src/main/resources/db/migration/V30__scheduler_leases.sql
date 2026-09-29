-- The scheduled jobs (feed polls, normalisers, the OSV sync) run on one aegis-core instance at a time: the one
-- holding the lease in this table (platform.scheduling.SchedulerLease). Two instances run side by side while a
-- rolling deploy replaces one with the other, or when the service is scaled out; without a lease each would
-- run every job. Global, like the feed tables: the jobs belong to no organization.
create table scheduler_leases (
    name       varchar(100) primary key,
    holder     varchar(255) not null,
    expires_at timestamptz  not null
);
