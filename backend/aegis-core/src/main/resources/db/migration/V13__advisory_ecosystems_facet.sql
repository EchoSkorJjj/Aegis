-- Epic 6 (OSV support): an ecosystem facet for the change-signals / advisories feed.
--
-- OSV brings package-ecosystem advisories (npm, PyPI, crates.io, RubyGems, Go, Maven), so the feed
-- needs an "ecosystem" filter alongside type/severity/source. Ecosystem lives on the affected-product
-- rows, and — because a single vulnerability can arrive from several feeds sharing a canonical id
-- (NVD by CVE, OSV by an aliasing GHSA) — its ecosystems may be spread across those sibling advisories.
-- This view folds them together by canonical id, so filtering a CVE by "npm" works even when it is the
-- CPE-only NVD row that represents the vulnerability in the deduped feed while the purl came from OSV.
--
-- Keyed by (canonical_id, ecosystem): the change-signals feed correlates on title (= canonical_id),
-- and the distinct ecosystem list powers the filter select. Ecosystem is lower-cased for a stable,
-- case-insensitive match/facet regardless of how a feed cased its bucket name (PyPI vs pypi).

create view advisory_ecosystems as
select distinct
    a.canonical_id            as canonical_id,
    lower(p.ecosystem)        as ecosystem
from advisories a
join advisory_affected_products p on p.advisory_id = a.id
where p.ecosystem is not null and p.ecosystem <> '';
