-- Single-use backup codes for MFA: a fallback when the authenticator is unavailable.
-- Stored hashed (SHA-256 of the normalised code); the plaintext is shown to the user once.
create table mfa_recovery_codes (
    id         uuid         primary key,
    user_id    uuid         not null references users(id) on delete cascade,
    code_hash  varchar(64)  not null,
    used       boolean      not null default false,
    created_at timestamptz  not null default now()
);

create index idx_mfa_recovery_codes_lookup on mfa_recovery_codes(user_id, code_hash);
