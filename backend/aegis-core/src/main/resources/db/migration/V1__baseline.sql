-- Aegis Core — baseline migration. Runs inside the `core` schema (Flyway owns and creates it).
--
-- Domain tables (services, components, vendors, advisories, findings, actions) are added by later
-- migrations as features land; see BUILD_PLAN.md step 1. This baseline is intentionally empty: it
-- exists so Flyway establishes the schema and its migration history from a known starting point.
-- (RAG embeddings are not stored here — that belongs to the ai-service.)
select 1;
