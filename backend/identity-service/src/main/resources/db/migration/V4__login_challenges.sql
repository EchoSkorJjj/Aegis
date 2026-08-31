-- Pending two-factor login challenges (emailed one-time code, hashed).
create table login_challenges (
    id         uuid         primary key,
    user_id    uuid         not null references users(id) on delete cascade,
    code_hash  varchar(64)  not null,
    attempts   integer      not null default 0,
    consumed   boolean      not null default false,
    expires_at timestamptz  not null,
    created_at timestamptz  not null default now()
);

create index idx_login_challenges_user on login_challenges(user_id);
