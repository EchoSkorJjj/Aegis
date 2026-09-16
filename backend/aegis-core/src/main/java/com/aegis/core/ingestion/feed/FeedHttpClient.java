package com.aegis.core.ingestion.feed;

import java.time.Duration;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Fetches a feed document over HTTP. One instance serves many feeds — each request carries its own
 * absolute URL.
 *
 * <p>Connectors build one of these rather than injecting the application's {@code RestClient} bean,
 * which targets the internal ai-service and carries its {@code X-Service-Token} as a default header:
 * reusing it would send that internal credential to a third party. Connect and read timeouts are
 * always set, so one unresponsive publisher cannot pin the shared ingestion thread.
 */
public class FeedHttpClient {

    private final RestClient http;

    public FeedHttpClient(
            Duration connectTimeout, Duration readTimeout, ObservationRegistry observationRegistry) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);
        this.http = RestClient.builder()
                .requestFactory(requestFactory)
                .observationRegistry(observationRegistry)
                .build();
    }

    /** The document as returned, or null for an empty body. Non-2xx responses throw. */
    public String fetch(String url) {
        return http.get().uri(url).retrieve().body(String.class);
    }
}
