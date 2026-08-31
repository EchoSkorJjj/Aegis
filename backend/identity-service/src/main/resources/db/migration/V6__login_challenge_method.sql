-- Make login challenges MFA-method-aware. Existing rows were all emailed one-time codes.
alter table login_challenges
    add column method varchar(16) not null default 'EMAIL_OTP';
