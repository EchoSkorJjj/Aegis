-- WebAuthn / passkey credentials. The stored key material is a PUBLIC key (not a secret), so it
-- is not encrypted, unlike the TOTP secret. A user may register several passkeys (one row each).
create table webauthn_credentials (
    id                       uuid         primary key,
    user_id                  uuid         not null references users(id) on delete cascade,
    credential_id            text         not null unique,
    attested_credential_data text         not null,   -- webauthn4j-serialised, base64url (holds the public key)
    sign_count               bigint       not null default 0,
    aaguid                   text,
    transports               text,                     -- comma-separated hints (e.g. "internal,hybrid")
    label                    text,
    created_at               timestamptz  not null default now(),
    last_used_at             timestamptz
);

create index idx_webauthn_credentials_user on webauthn_credentials(user_id);

-- The short-lived registration challenge issued by /register/begin — one pending per user,
-- overwritten on each begin and deleted on finish. Single-use, like a login challenge.
create table webauthn_registration (
    user_id    uuid         primary key references users(id) on delete cascade,
    challenge  text         not null,
    created_at timestamptz  not null default now(),
    expires_at timestamptz  not null
);
