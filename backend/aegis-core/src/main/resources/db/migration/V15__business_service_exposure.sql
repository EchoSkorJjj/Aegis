-- CS203-65: exposure on a business service — the owner's declaration of whether the service is
-- reachable from outside its own application. Scoring multiplies it with the advisory's severity and
-- the service's business criticality, so it has to live next to criticality: both are business
-- decisions the owner makes when registering the service, not facts the platform can derive.
--
-- Existing rows predate the attribute, so they are backfilled to INTERNAL — the same middle default
-- BusinessService.DEFAULT_EXPOSURE applies to a request that omits it. ISOLATED would quietly
-- understate every already-registered service's risk; INTERNET_FACING would overstate it.
--
-- The column default keeps that agreement for any writer outside the entity (a fixture, a manual
-- insert); Hibernate always supplies the value explicitly.

alter table business_services
    add column exposure varchar(32) not null default 'INTERNAL';

alter table business_services
    add constraint ck_business_services_exposure
        check (exposure in ('ISOLATED', 'INTERNAL', 'INTERNET_FACING'));
