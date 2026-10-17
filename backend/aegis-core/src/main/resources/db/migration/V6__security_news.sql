-- E5 story 4: the security news / threat intel connector's normalised output.
--
-- News is the loosest of the three feeds: an advisory names an affected package, an incident names an
-- impaired vendor, but an article only tells a story. What makes it actionable is the CVE identifiers
-- it mentions, extracted deterministically, which is how matching will join an article to advisories
-- already ingested and so to the components and services they affect.
--
-- The references live in their own table rather than a text[] so matching can index and join them the
-- same way it joins any other relationship in this schema.

create table news_items (
    id           uuid         primary key,
    source_id    varchar(64)  not null,
    -- "<feed-key>:<feed's own entry id>" — scoped by feed so two publishers cannot collide.
    external_id  text         not null,
    feed_key     varchar(64)  not null,
    feed_name    varchar(255) not null,
    title        text         not null,
    url          text,
    summary      text,
    published_at timestamptz,
    created_at   timestamptz  not null default now(),
    updated_at   timestamptz  not null default now(),
    -- One row per article per feed; a re-poll of an edited article updates this row in place.
    constraint uq_news_items_source_external unique (source_id, external_id),
    constraint ck_news_items_feed_key_nonblank check (length(btrim(feed_key)) > 0)
);

create index ix_news_items_published on news_items (published_at desc);

create index ix_news_items_feed on news_items (feed_key);

create table news_item_cve_references (
    id           uuid        primary key,
    news_item_id uuid        not null references news_items (id) on delete cascade,
    cve_id       varchar(32) not null
);

create index ix_news_cve_refs_item on news_item_cve_references (news_item_id);

-- Matching's entry point: "which articles discuss this CVE?"
create index ix_news_cve_refs_cve on news_item_cve_references (cve_id);
