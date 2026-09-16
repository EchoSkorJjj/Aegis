/**
 * The security news / threat intel connector and the normalised articles it produces.
 *
 * <p>{@link com.aegis.core.ingestion.news.NewsSource} captures each configured publisher's RSS or Atom
 * document verbatim; {@link com.aegis.core.ingestion.news.NewsNormalizer} turns those into
 * {@link com.aegis.core.ingestion.news.NewsItem} records, extracting the CVE identifiers each article
 * mentions so matching has something deterministic to join on.
 *
 * <p>News feeds are <b>untrusted external content</b>, and the most obvious prompt-injection vector in
 * the product: an article is attacker-influenced prose. Nothing here is fed to an LLM — documents are
 * size-capped, the XML is parsed by the hardened {@link com.aegis.core.ingestion.feed} parser, and the
 * only interpretation applied is a CVE regex. Deterministic; no AI.
 */
package com.aegis.core.ingestion.news;
