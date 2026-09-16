/**
 * Shared plumbing for connectors that read syndication feeds: an XXE-hardened RSS/Atom parser, the
 * date formats publishers actually use, content hashing, and an HTTP client that always has timeouts.
 *
 * <p>These live together so the security-critical parts exist once. Duplicating the XML hardening per
 * connector is how one copy quietly drifts insecure.
 */
package com.aegis.core.ingestion.feed;
