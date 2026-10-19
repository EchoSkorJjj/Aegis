package com.aegis.core.ingestion;

/**
 * Thrown by a {@link RawEventDrainer.RawEventHandler} when a raw payload is definitively malformed and
 * will never normalise — as distinct from a transient failure (a DB blip, a lost upsert race). The
 * drainer drops an unparseable event immediately (marking it processed; the raw payload stays in the
 * store for inspection), whereas a transient failure is retried on later runs and only dropped after a
 * bounded number of attempts.
 */
public class UnparseableEventException extends RuntimeException {

    public UnparseableEventException(String sourceId, String externalId, Throwable cause) {
        super("unparseable raw event from '" + sourceId + "' (" + externalId + "): " + cause.getMessage(), cause);
    }
}
