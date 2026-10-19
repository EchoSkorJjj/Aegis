-- E5 story 1: the raw-event store. Append-only capture of exactly what each feed returned, before
-- any normalisation. De-duplication is enforced by the unique dedup_key. Later stories read from
-- this table to normalise raw events into Change/Signal records.

create table raw_events (
    id           uuid         primary key,
    source_id    varchar(64)  not null,
    external_id  text,
    dedup_key    varchar(600) not null,
    content_type varchar(128) not null,
    payload      text         not null,
    occurred_at  timestamptz,
    ingested_at  timestamptz  not null default now(),
    constraint uq_raw_events_dedup_key unique (dedup_key),
    constraint ck_raw_events_source_id_nonblank check (length(btrim(source_id)) > 0),
    constraint ck_raw_events_payload_nonblank check (length(btrim(payload)) > 0)
);

-- Reverse lookups for later normalisation and per-feed health: "what did this feed ingest, recently?"
create index ix_raw_events_source_ingested on raw_events (source_id, ingested_at);
