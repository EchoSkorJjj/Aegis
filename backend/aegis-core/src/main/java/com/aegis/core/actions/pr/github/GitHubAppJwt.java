package com.aegis.core.actions.pr.github;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.util.Base64;
import java.util.Date;

import com.aegis.core.platform.common.ApiExceptions;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

final class GitHubAppJwt {
    private final RSAPrivateKey key;
    private final String clientId;
    private final Clock clock;

    GitHubAppJwt(Path path, String clientId, Clock clock) {
        this(null, path, clientId, clock);
    }

    /** The key's PEM text wins over its file when both are given; a deployment usually sets one. */
    GitHubAppJwt(String pem, Path path, String clientId, Clock clock) {
        this.key = readKey(pem, path);
        this.clientId = clientId;
        this.clock = clock;
    }

    String create() {
        var now = clock.instant();
        var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), new JWTClaimsSet.Builder()
                .issuer(clientId).issueTime(Date.from(now.minusSeconds(60)))
                .expirationTime(Date.from(now.plusSeconds(9 * 60))).build());
        try {
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (JOSEException ex) {
            throw new ApiExceptions.UpstreamUnavailable("Could not sign GitHub App authentication");
        }
    }

    private static RSAPrivateKey readKey(String text, Path path) {
        boolean inline = text != null && !text.isBlank();
        if (!inline && path == null) {
            throw new IllegalStateException("GitHub App private-key or private-key-path is required when enabled");
        }
        try {
            // An environment variable may carry the PEM's line breaks as literal "\n" (a .env file, say).
            String pem = (inline ? text.replace("\\n", "\n") : Files.readString(path)).trim();
            String begin = "-----BEGIN PRIVATE KEY-----";
            String end = "-----END PRIVATE KEY-----";
            if (!pem.startsWith(begin) || !pem.endsWith(end)) {
                throw new IllegalArgumentException();
            }
            byte[] der = Base64.getDecoder().decode(
                    pem.substring(begin.length(), pem.length() - end.length()).replaceAll("\\s", ""));
            var key = (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
            if (key.getModulus().bitLength() < 2048) {
                throw new IllegalArgumentException();
            }
            return key;
        } catch (Exception ex) {
            // Discard causes: parsers/filesystem failures may include key material or secret paths.
            throw new IllegalStateException("GitHub App key must be a readable, unencrypted PKCS#8 RSA PEM (at least 2048 bits)");
        }
    }
}
