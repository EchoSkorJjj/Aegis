-- Universal identity/user store. User profile lives here regardless of auth mode.

create table users (
    id             uuid         primary key,
    email          varchar(255) not null unique,
    password_hash  varchar(255) not null,
    display_name   varchar(255),
    enabled        boolean      not null default true,
    email_verified boolean      not null default false,
    created_at     timestamptz  not null default now(),
    updated_at     timestamptz  not null default now()
);

create table user_roles (
    user_id uuid        not null references users(id) on delete cascade,
    role    varchar(32) not null,
    primary key (user_id, role)
);

create table refresh_tokens (
    id         uuid         primary key,
    user_id    uuid         not null references users(id) on delete cascade,
    token_hash varchar(64)  not null unique,   -- sha-256 hex of the opaque token
    expires_at timestamptz  not null,
    revoked    boolean      not null default false,
    created_at timestamptz  not null default now()
);

create index idx_refresh_tokens_user on refresh_tokens(user_id);
