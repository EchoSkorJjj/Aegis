package com.aegis.core.ingestion.connectors.feed;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The egress guards every connector shares: what a compromised feed URL must not be able to reach or stream. */
class OutboundHttpTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "http://127.0.0.1/feed",
        "http://localhost/feed",
        "http://[::1]/feed",
        "http://0.0.0.0/feed",
        "http://169.254.169.254/latest/meta-data", // the cloud metadata address
        "http://10.0.0.5/feed",
        "http://172.16.0.1/feed",
        "http://192.168.1.1/feed",
        "http://224.0.0.1/feed",
        "http://[fe80::1]/feed",
        "http://[fc00::1]/feed",
        "http://[fd12:3456::1]/feed"
    })
    void refusesEveryNonPublicAddress(String url) {
        assertThatThrownBy(() -> OutboundHttp.requireFetchable(url, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-public");
    }

    @Test
    void allowsAPublicAddress() {
        assertThat(OutboundHttp.requireFetchable("https://93.184.216.34/feed", false).getHost())
                .isEqualTo("93.184.216.34");
    }

    @Test
    void anInternalMirrorMayWaiveTheAddressCheckOnly() {
        assertThat(OutboundHttp.requireFetchable("http://127.0.0.1:9000/feed", true).getPort()).isEqualTo(9000);
        assertThatThrownBy(() -> OutboundHttp.requireFetchable("file:///etc/passwd", true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("only http(s)");
    }

    @ParameterizedTest
    @ValueSource(strings = {"file:///etc/passwd", "ftp://example.org/feed", "gopher://example.org", "example.org/feed"})
    void refusesEveryOtherScheme(String url) {
        assertThatThrownBy(() -> OutboundHttp.requireFetchable(url, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("only http(s)");
    }

    @Test
    void refusesAMalformedUrlOrOneWithNoHost() {
        assertThatThrownBy(() -> OutboundHttp.requireFetchable("http://exa mple.org/", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a valid URL");
        assertThatThrownBy(() -> OutboundHttp.requireFetchable("https:///feed", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no host");
    }

    @Test
    void refusesAHostThatCannotBeResolved() {
        assertThatThrownBy(() -> OutboundHttp.requireFetchable("https://aegis-no-such-host.invalid/feed", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot resolve");
    }

    @Test
    void readsABodyUpToItsCapAndRefusesOneByteMore() throws IOException {
        byte[] body = "12345".getBytes(StandardCharsets.UTF_8);

        assertThat(OutboundHttp.readCapped(new ByteArrayInputStream(body), 5)).isEqualTo("12345");
        assertThatThrownBy(() -> OutboundHttp.readCapped(new ByteArrayInputStream(body), 4))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("4 byte cap");
    }

    @Test
    void anEmptyBodyIsNull() throws IOException {
        assertThat(OutboundHttp.readCapped(null, 10)).isNull();
    }
}
