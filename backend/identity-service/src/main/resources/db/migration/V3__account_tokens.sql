-- Single-use tokens for email verification and password reset.
create table auth_tokens (
    id         uuid         primary key,
    user_id    uuid         not null references users(id) on delete cascade,
    token_hash varchar(64)  not null unique,
    type       varchar(32)  not null,
    expires_at timestamptz  not null,
    used       boolean      not null default false,
    created_at timestamptz  not null default now()
);

create index idx_auth_tokens_user on auth_tokens(user_id);
