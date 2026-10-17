/**
 * The unified {@link com.aegis.core.signals.ChangeSignal} view: a single, feed-agnostic
 * shape over the three things ingestion normalises — {@link com.aegis.core.advisories
 * advisories}, {@link com.aegis.core.incidents vendor incidents}, and
 * {@link com.aegis.core.news security news} — so matching, scoring, and the frontend can
 * treat "a real-world change" uniformly.
 *
 * <p>Read-only on purpose: this package owns no table of its own and writes nothing. It is backed by
 * the {@code change_signals} database view ({@code V10__change_signals_view.sql}); each source's own
 * connector remains the sole writer of its own idempotent, upserted output.
 */
package com.aegis.core.signals;
