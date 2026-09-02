-- The per-login WebAuthn assertion challenge (base64url), set when a login challenge is armed with
-- the WEBAUTHN method — the passkey signs over it. Null for the code-based factors.
alter table login_challenges add column webauthn_challenge text;
