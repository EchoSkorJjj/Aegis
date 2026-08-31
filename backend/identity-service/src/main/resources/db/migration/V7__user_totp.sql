-- Enrolled authenticator (TOTP) secrets — encrypted at rest, one per user. `enabled` flips true
-- only once the user confirms a code, so a half-finished enrollment never gates login.
create table user_totp (
    user_id      uuid         primary key references users(id) on delete cascade,
    secret_enc   text         not null,
    enabled      boolean      not null default false,
    created_at   timestamptz  not null default now(),
    confirmed_at timestamptz
);
