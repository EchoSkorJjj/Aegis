package com.aegis.core.ingestion.nvd;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Talks to the NVD CVE API 2.0. Paged with {@code startIndex}/{@code resultsPerPage} and windowed on
 * {@code lastModStartDate}/{@code lastModEndDate}, so a poll asks only for what changed.
 *
 * <p>This client is built here rather than injected: the application's only other {@code RestClient}
 * bean is the one aimed at the internal ai-service, and it carries the shared {@code X-Service-Token}
 * as a default header. Reusing it would send that internal credential to nvd.nist.gov. Connect and
 * read timeouts are set explicitly so a hung feed cannot pin the shared ingestion thread.
 *
 * <p>Each CVE is kept as the exact JSON sub-document NVD returned — the raw-event store holds what
 * the feed said, not this connector's reading of it.
 */
class NvdApiClient {

    private final RestClient http;
    private final ObjectMapper objectMapper;
    private final int resultsPerPage;

    NvdApiClient(NvdProperties properties, ObjectMapper objectMapper, ObservationRegistry observationRegistry) {
        this.objectMapper = objectMapper;
        this.resultsPerPage = properties.resultsPerPage();

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.connectTimeout());
        requestFactory.setReadTimeout(properties.readTimeout());

        RestClient.Builder builder = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .observationRegistry(observationRegistry);
        if (!properties.apiKey().isBlank()) {
            // Raises NVD's rate limit from 5 to 50 requests per rolling 30 seconds.
            builder.defaultHeader("apiKey", properties.apiKey());
        }
        this.http = builder.build();
    }

    /**
     * Fetch one page of CVEs modified within {@code [start, end]}. Any non-2xx response throws, which
     * the ingestion framework's retry/backoff handles (including NVD's 403/429 rate-limit replies).
     */
    NvdPage fetch(Instant start, Instant end, int startIndex) throws Exception {
        String body = http.get()
                .uri(uri -> uri
                        .queryParam("lastModStartDate", NvdTimestamps.format(start))
                        .queryParam("lastModEndDate", NvdTimestamps.format(end))
                        .queryParam("resultsPerPage", resultsPerPage)
                        .queryParam("startIndex", startIndex)
                        .build())
                .retrieve()
                .body(String.class);

        JsonNode root = objectMapper.readTree(body == null ? "{}" : body);
        int totalResults = root.path("totalResults").asInt(0);

        List<NvdCve> cves = new ArrayList<>();
        for (JsonNode vulnerability : root.path("vulnerabilities")) {
            JsonNode cve = vulnerability.path("cve");
            String id = cve.path("id").asString(null);
            if (id == null || id.isBlank()) {
                continue;
            }
            String lastModified = cve.path("lastModified").asString(null);
            cves.add(new NvdCve(id, lastModified, NvdTimestamps.parse(lastModified), cve.toString()));
        }
        return new NvdPage(totalResults, cves);
    }

    record NvdPage(int totalResults, List<NvdCve> cves) {
    }

    /**
     * {@code lastModifiedRaw} is kept verbatim because it is part of the de-duplication key: a
     * revised CVE is a new event, not a duplicate of the one already stored.
     */
    record NvdCve(String id, String lastModifiedRaw, Instant lastModified, String json) {
    }
}
