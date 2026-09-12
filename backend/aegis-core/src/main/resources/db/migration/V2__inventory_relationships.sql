-- CS203-41: inventory records and the direct edges used by the service/dependency map.
-- Entity deletion removes only relationship rows; it never cascades to another inventory entity.

create table business_services (
    id          uuid         primary key,
    name        varchar(255) not null,
    criticality varchar(16)  not null,
    created_at  timestamptz  not null default now(),
    updated_at  timestamptz  not null default now(),
    constraint ck_business_services_name_nonblank check (length(btrim(name)) > 0),
    constraint ck_business_services_criticality
        check (criticality in ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL'))
);

create unique index uq_business_services_name_ci on business_services (lower(name));

create table software_components (
    id         uuid         primary key,
    ecosystem  varchar(64)  not null,
    name       varchar(255) not null,
    version    varchar(128) not null,
    created_at timestamptz  not null default now(),
    updated_at timestamptz  not null default now(),
    constraint ck_software_components_ecosystem_nonblank check (length(btrim(ecosystem)) > 0),
    constraint ck_software_components_name_nonblank check (length(btrim(name)) > 0),
    constraint ck_software_components_version_nonblank check (length(btrim(version)) > 0)
);

create unique index uq_software_components_coordinates_ci
    on software_components (lower(ecosystem), lower(name), lower(version));
create index ix_software_components_name on software_components (lower(name));

create table third_party_vendors (
    id         uuid         primary key,
    name       varchar(255) not null,
    created_at timestamptz  not null default now(),
    updated_at timestamptz  not null default now(),
    constraint ck_third_party_vendors_name_nonblank check (length(btrim(name)) > 0)
);

create unique index uq_third_party_vendors_name_ci on third_party_vendors (lower(name));

create table owners (
    id         uuid         primary key,
    name       varchar(255) not null,
    created_at timestamptz  not null default now(),
    updated_at timestamptz  not null default now(),
    constraint ck_owners_name_nonblank check (length(btrim(name)) > 0)
);

create unique index uq_owners_name_ci on owners (lower(name));

create table business_service_components (
    business_service_id uuid not null,
    component_id        uuid not null,
    created_at          timestamptz not null default now(),
    constraint pk_business_service_components primary key (business_service_id, component_id),
    constraint fk_service_components_service foreign key (business_service_id)
        references business_services (id) on delete cascade,
    constraint fk_service_components_component foreign key (component_id)
        references software_components (id) on delete cascade
);

create index ix_service_components_component on business_service_components (component_id);

create table business_service_vendors (
    business_service_id uuid not null,
    vendor_id           uuid not null,
    created_at          timestamptz not null default now(),
    constraint pk_business_service_vendors primary key (business_service_id, vendor_id),
    constraint fk_service_vendors_service foreign key (business_service_id)
        references business_services (id) on delete cascade,
    constraint fk_service_vendors_vendor foreign key (vendor_id)
        references third_party_vendors (id) on delete cascade
);

create index ix_service_vendors_vendor on business_service_vendors (vendor_id);

create table business_service_owners (
    business_service_id uuid not null,
    owner_id            uuid not null,
    created_at          timestamptz not null default now(),
    constraint pk_business_service_owners primary key (business_service_id, owner_id),
    constraint fk_service_owners_service foreign key (business_service_id)
        references business_services (id) on delete cascade,
    constraint fk_service_owners_owner foreign key (owner_id)
        references owners (id) on delete cascade
);

create index ix_service_owners_owner on business_service_owners (owner_id);

create table software_component_dependencies (
    component_id  uuid not null,
    dependency_id uuid not null,
    created_at    timestamptz not null default now(),
    constraint pk_software_component_dependencies primary key (component_id, dependency_id),
    constraint fk_component_dependencies_component foreign key (component_id)
        references software_components (id) on delete cascade,
    constraint fk_component_dependencies_dependency foreign key (dependency_id)
        references software_components (id) on delete cascade,
    constraint ck_component_dependencies_not_self check (component_id <> dependency_id)
);

create index ix_component_dependencies_dependency
    on software_component_dependencies (dependency_id);
