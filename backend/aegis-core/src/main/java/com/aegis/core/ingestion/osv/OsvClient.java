package com.aegis.core.ingestion.osv;

/** Streams one ecosystem's OSV records from the bulk export. */
interface OsvClient {

    /**
     * Stream each record ({@code {id}.json}) in {@code ecosystem}'s export to {@code consumer}, up to
     * {@code maxRecords}, dropping any single record whose JSON exceeds {@code maxRecordBytes}.
     */
    void forEachRecord(String ecosystem, int maxRecords, int maxRecordBytes, RecordConsumer consumer)
            throws Exception;

    @FunctionalInterface
    interface RecordConsumer {
        void accept(String id, String json) throws Exception;
    }
}
