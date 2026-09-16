package com.aegis.core.ingestion.vendorstatus;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Fetches a vendor's status document. One client serves every configured vendor — each request
 * carries its own absolute URL.
 *
 * <p>Built here rather than injected: the application's other {@code RestClient} bean targets the
 * internal ai-service and carries its {@code X-Service-Token} as a default header, which must never
 * reach a third-party status page. Connect and read timeouts are explicit so one unresponsive vendor
 * cannot pin the shared ingestion thread.
 */
class VendorStatusClient {

    private final RestClient http;

    VendorStatusClient(VendorStatusProperties properties, ObservationRegistry observationRegistry) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.connectTimeout());
        requestFactory.setReadTimeout(properties.readTimeout());
        this.http = RestClient.builder()
                .requestFactory(requestFactory)
                .observationRegistry(observationRegistry)
                .build();
    }

    /** The document as returned, or null for an empty body. Non-2xx responses throw. */
    String fetch(String url) {
        return http.get().uri(url).retrieve().body(String.class);
    }
}
