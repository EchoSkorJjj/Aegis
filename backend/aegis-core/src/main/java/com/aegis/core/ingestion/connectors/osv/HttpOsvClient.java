package com.aegis.core.ingestion.connectors.osv;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.aegis.core.ingestion.connectors.feed.OutboundHttp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Downloads an ecosystem's OSV bulk export ({@code {baseUrl}/{ecosystem}/all.zip}) and streams its
 * entries, one record at a time, so a large snapshot never lands in memory whole.
 *
 * <p>The URL is SSRF-checked with {@link OutboundHttp#requireFetchable} before the fetch. Redirects
 * are followed by the JDK client (the export is a fixed, trusted storage bucket); each entry is read
 * through a per-record byte cap so a hostile payload cannot exhaust memory.
 */
class HttpOsvClient implements OsvClient {

    private static final Logger log = LoggerFactory.getLogger(HttpOsvClient.class);

    private final HttpClient http;
    private final String baseUrl;
    private final Duration readTimeout;
    private final boolean allowPrivateHosts;

    HttpOsvClient(OsvProperties properties) {
        this.baseUrl = properties.baseUrl().replaceAll("/+$", "");
        this.readTimeout = properties.readTimeout();
        this.allowPrivateHosts = properties.allowPrivateHosts();
        this.http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(properties.connectTimeout())
                .build();
    }

    @Override
    public void forEachRecord(String ecosystem, int maxRecords, int maxRecordBytes, RecordConsumer consumer)
            throws Exception {
        String url = baseUrl + "/" + ecosystem + "/all.zip";
        OutboundHttp.requireFetchable(url, allowPrivateHosts);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(readTimeout).GET().build();
        HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() / 100 != 2) {
            response.body().close();
            throw new IllegalStateException("osv fetch failed: HTTP " + response.statusCode() + " from " + url);
        }
        int count = 0;
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(response.body()))) {
            ZipEntry entry;
            while (count < maxRecords && (entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory() || !entry.getName().endsWith(".json")) {
                    continue;
                }
                String json = readEntry(zip, entry.getName(), maxRecordBytes);
                if (json == null) {
                    continue;
                }
                consumer.accept(idOf(entry.getName()), json);
                count++;
            }
        }
    }

    /** Read the current zip entry, or null if it exceeds the cap (skipped). */
    private static String readEntry(ZipInputStream zip, String name, int maxBytes) throws Exception {
        // One byte past the cap distinguishes "at the cap" from "over it".
        byte[] bytes = zip.readNBytes(maxBytes + 1);
        if (bytes.length > maxBytes) {
            log.warn("osv: skipping {} — record exceeds the {} byte cap", name, maxBytes);
            return null;
        }
        return bytes.length == 0 ? null : new String(bytes, StandardCharsets.UTF_8);
    }

    private static String idOf(String entryName) {
        int slash = entryName.lastIndexOf('/');
        String file = slash < 0 ? entryName : entryName.substring(slash + 1);
        return file.substring(0, file.length() - ".json".length());
    }
}
