package com.aegis.core.ingestion.feed;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Fetches a feed document over HTTP. One instance serves many feeds — each request carries its own
 * absolute URL.
 *
 * <p>Connectors build one of these rather than injecting the application's {@code RestClient} bean,
 * which targets the internal ai-service and carries its {@code X-Service-Token} as a default header:
 * reusing it would send that internal credential to a third party. Connect and read timeouts are
 * always set, so one unresponsive publisher cannot pin the shared ingestion thread.
 *
 * <p><b>Hardened against SSRF and oversized bodies.</b> Every URL — and every redirect it points at —
 * is checked with {@link OutboundHttp#requireFetchable}, so a feed cannot steer a fetch at an internal
 * address. Redirects are followed manually (the JDK client itself does not) precisely so each hop can
 * be re-checked, and the body is read through {@link OutboundHttp#readCapped} so a publisher cannot
 * stream an unbounded response into memory.
 */
public class FeedHttpClient {

    private final RestClient http;
    private final long maxResponseBytes;
    private final int maxRedirects;
    private final boolean allowPrivateHosts;

    public FeedHttpClient(
            Duration connectTimeout,
            Duration readTimeout,
            long maxResponseBytes,
            int maxRedirects,
            boolean allowPrivateHosts,
            ObservationRegistry observationRegistry) {
        this.maxResponseBytes = maxResponseBytes;
        this.maxRedirects = maxRedirects;
        this.allowPrivateHosts = allowPrivateHosts;
        // Redirects are followed by hand (see fetch) so each hop's target is re-checked for SSRF; the
        // JDK client must therefore not follow them itself.
        HttpClient jdkClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(connectTimeout)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(jdkClient);
        requestFactory.setReadTimeout(readTimeout);
        this.http = RestClient.builder()
                .requestFactory(requestFactory)
                .observationRegistry(observationRegistry)
                .build();
    }

    /**
     * The document as returned, or null for an empty body.
     *
     * @throws IllegalArgumentException the URL (or a redirect target) is not fetchable — see
     *     {@link OutboundHttp#requireFetchable}
     * @throws IllegalStateException a non-2xx response, a redirect with no target, or too many redirects
     */
    public String fetch(String url) {
        String current = url;
        for (int hop = 0; hop <= maxRedirects; hop++) {
            URI uri = OutboundHttp.requireFetchable(current, allowPrivateHosts);
            Hop result = http.get().uri(uri).exchange((request, response) -> {
                HttpStatusCode status = response.getStatusCode();
                if (status.is3xxRedirection()) {
                    String location = response.getHeaders().getFirst("Location");
                    if (location == null || location.isBlank()) {
                        throw new IllegalStateException("redirect with no Location from " + uri);
                    }
                    return new Hop(uri.resolve(location).toString(), null);
                }
                if (!status.is2xxSuccessful()) {
                    throw new IllegalStateException("feed fetch failed: HTTP " + status.value() + " from " + uri);
                }
                return new Hop(null, OutboundHttp.readCapped(response.getBody(), maxResponseBytes));
            });
            if (result.nextUrl() == null) {
                return result.body();
            }
            current = result.nextUrl();
        }
        throw new IllegalStateException("too many redirects (>" + maxRedirects + ") starting at " + url);
    }

    /** One step of the fetch: either a redirect to follow ({@code nextUrl}) or the final body. */
    private record Hop(String nextUrl, String body) {
    }
}
