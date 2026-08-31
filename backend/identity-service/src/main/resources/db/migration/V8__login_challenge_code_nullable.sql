-- A challenge answered by an authenticator (TOTP) or passkey has no server-stored code,
-- so the emailed-code hash is only present for the EMAIL_OTP method.
alter table login_challenges alter column code_hash drop not null;
