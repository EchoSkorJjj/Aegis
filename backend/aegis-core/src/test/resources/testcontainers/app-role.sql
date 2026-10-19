-- The integration tests connect as this role, as a deployed aegis-core does. It is not a superuser (Amazon RDS has
-- none, and no role there can bypass row-level security), so every row-level security policy applies to it, and it
-- owns what Flyway creates.
create role aegis login password 'aegis' nosuperuser nobypassrls;
grant create on database test to aegis;
