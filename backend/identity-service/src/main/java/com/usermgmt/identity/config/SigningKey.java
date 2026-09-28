package com.usermgmt.identity.config;

import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;

import com.nimbusds.jose.jwk.RSAKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The RSA key that signs access tokens in local mode.
 *
 * <p>A deployment sets {@code identity.jwt.private-key} ({@code IDENTITY_JWT_PRIVATE_KEY}) to an unencrypted
 * PKCS#8 PEM of at least 2048 bits. Then tokens survive a restart, and every instance signs with the same key,
 * so the gateway and aegis-core accept a token whichever instance issued it — including while a rolling
 * deploy runs the old and the new one side by side. Without it a key is generated at startup, which suits one
 * local instance only.
 *
 * <p>The key id is the key's RFC 7638 thumbprint: the same on every instance and after every restart, and a
 * new one when the key is rotated.
 */
final class SigningKey {

    private static final Logger log = LoggerFactory.getLogger(SigningKey.class);

    private static final String BEGIN = "-----BEGIN PRIVATE KEY-----";
    private static final String END = "-----END PRIVATE KEY-----";
    private static final String INVALID =
            "IDENTITY_JWT_PRIVATE_KEY must be an unencrypted PKCS#8 RSA PEM of at least 2048 bits";

    private SigningKey() {
    }

    /** The configured key, or a new one when none is configured. */
    static RSAKey from(String pem) {
        if (pem == null || pem.isBlank()) {
            log.warn("No IDENTITY_JWT_PRIVATE_KEY set: signing tokens with a key generated at startup. Tokens won't "
                    + "survive a restart, and two instances would reject each other's tokens; set one in any deployment.");
            return generate();
        }
        return load(pem);
    }

    static RSAKey load(String pem) {
        try {
            // An environment variable may carry the PEM's line breaks as literal "\n" (a .env file, say).
            String text = pem.replace("\\n", "\n").strip();
            if (!text.startsWith(BEGIN) || !text.endsWith(END)) {
                throw new IllegalArgumentException();
            }
            byte[] der = Base64.getDecoder()
                    .decode(text.substring(BEGIN.length(), text.length() - END.length()).replaceAll("\\s", ""));
            var privateKey = (RSAPrivateCrtKey) KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
            if (privateKey.getModulus().bitLength() < 2048) {
                throw new IllegalArgumentException();
            }
            var publicKey = (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new RSAPublicKeySpec(privateKey.getModulus(), privateKey.getPublicExponent()));
            return new RSAKey.Builder(publicKey).privateKey(privateKey).keyIDFromThumbprint().build();
        } catch (Exception ex) {
            // Discard the cause: a parser's message may quote the key material.
            throw new IllegalStateException(INVALID);
        }
    }

    static RSAKey generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            var keyPair = generator.generateKeyPair();
            return new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                    .privateKey(keyPair.getPrivate())
                    .keyIDFromThumbprint()
                    .build();
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to generate RSA signing key", ex);
        }
    }
}
