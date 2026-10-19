package com.aegis.core.actions.assess;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * A fix commit's diff from its host (E11-5). The URI is always rebuilt by {@link com.aegis.core.advisories.CommitUrl}
 * from a validated commit on github.com or gitlab.com; still, no redirect is followed, only a 200 counts, and at most
 * {@value #MAX_BYTES} bytes are read, within a few seconds. Public code, fetched without credentials.
 */
@Component
class HttpDiffSource implements DiffSource {

    static final int MAX_BYTES = 1024 * 1024;
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final Logger log = LoggerFactory.getLogger(HttpDiffSource.class);

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Override
    public Optional<String> diff(URI uri) {
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(TIMEOUT)
                .header("Accept", "text/plain, text/x-diff").GET().build();
        try {
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                if (response.statusCode() != 200) {
                    log.info("fix commit diff not fetched: HTTP {}", response.statusCode());
                    return Optional.empty();
                }
                return Optional.of(new String(body.readNBytes(MAX_BYTES), StandardCharsets.UTF_8));
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (IOException | RuntimeException unavailable) {
            log.info("fix commit diff not fetched: {}", unavailable.getClass().getSimpleName());
            return Optional.empty();
        }
    }
}
