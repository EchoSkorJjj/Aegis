package com.aegis.core.ingestion.connectors.feed;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;

/**
 * Egress guards for the connectors' outbound HTTP. Feed URLs are operator-configured, but a compromised
 * or hijacked publisher — or a redirect it serves — could otherwise point at an internal address or
 * stream an unbounded body. Two guards, shared by every connector so the hardening lives in one place:
 *
 * <ul>
 *   <li>{@link #requireFetchable(String, boolean)} — an SSRF check: the URL must be http(s) and its
 *       host must resolve only to public addresses (no loopback, link-local incl. the 169.254.169.254
 *       metadata address, private, unique-local or multicast target). The check can be waived per
 *       client for internal/mirrored feeds (and the connector unit tests, which fetch from localhost).</li>
 *   <li>{@link #readCapped(InputStream, long)} — a bounded body read, so an endpoint cannot exhaust
 *       memory before the per-event size check runs.</li>
 * </ul>
 *
 * <p>The SSRF check resolves DNS and inspects the addresses; it does not pin the connection to the
 * resolved address, so a determined DNS-rebinding attacker could still shift the target between the
 * check and the connect. Closing that fully means connecting by resolved IP with SNI, which is beyond
 * what operator-configured feeds warrant here.
 */
public final class OutboundHttp {

    private OutboundHttp() {
    }

    /**
     * Validate that {@code url} is an http(s) URL whose host resolves only to public addresses (unless
     * {@code allowPrivateHosts}), and return it as a {@link URI}.
     *
     * @param allowPrivateHosts when true, waive the non-public-address check — for a deployment whose
     *     feeds are internal/mirrored, and for the connector unit tests that fetch from localhost. The
     *     scheme and host are still validated.
     * @throws IllegalArgumentException the URL is malformed, is not http(s), has no host, cannot be
     *     resolved, or (when not waived) resolves to a non-public address
     */
    public static URI requireFetchable(String url, boolean allowPrivateHosts) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException malformed) {
            throw new IllegalArgumentException("not a valid URL: " + url, malformed);
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("only http(s) URLs may be fetched: " + url);
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("URL has no host: " + url);
        }
        if (allowPrivateHosts) {
            return uri;
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException unresolvable) {
            throw new IllegalArgumentException("cannot resolve host: " + host, unresolvable);
        }
        for (InetAddress address : addresses) {
            if (isNonPublic(address)) {
                throw new IllegalArgumentException("refusing to fetch a non-public address ("
                        + address.getHostAddress() + ") for host " + host);
            }
        }
        return uri;
    }

    private static boolean isNonPublic(InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        // IPv6 unique-local (fc00::/7) is not covered by isSiteLocalAddress().
        byte[] bytes = address.getAddress();
        return bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
    }

    /**
     * Read at most {@code maxBytes} from {@code body} as UTF-8. Returns null for a null stream (an
     * empty body); throws when the stream holds more than the cap.
     *
     * @throws IOException the stream cannot be read, or exceeds {@code maxBytes}
     */
    public static String readCapped(InputStream body, long maxBytes) throws IOException {
        if (body == null) {
            return null;
        }
        // Read one byte past the cap so "exactly at the cap" is distinguishable from "over it".
        // readNBytes grows its buffer as it reads, so a large limit does not pre-allocate.
        int limit = (int) Math.min(maxBytes + 1, Integer.MAX_VALUE);
        byte[] bytes = body.readNBytes(limit);
        if (bytes.length > maxBytes) {
            throw new IOException("response body exceeds the " + maxBytes + " byte cap");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
