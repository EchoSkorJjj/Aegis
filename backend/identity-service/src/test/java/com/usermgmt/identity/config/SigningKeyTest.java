package com.usermgmt.identity.config;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.Date;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SigningKeyTest {

    private static final String INVALID =
            "IDENTITY_JWT_PRIVATE_KEY must be an unencrypted PKCS#8 RSA PEM of at least 2048 bits";

    @Test
    void loadsTheConfiguredKeyAndDerivesItsPublicHalf() throws Exception {
        KeyPair pair = keyPair(2048);

        RSAKey key = SigningKey.load(pem(pair));

        assertThat(key.isPrivate()).isTrue();
        assertThat(key.toRSAPublicKey().getModulus()).isEqualTo(((RSAPublicKey) pair.getPublic()).getModulus());
    }

    @Test
    void everyInstanceSignsWithTheSameKeyId() throws Exception {
        String pem = pem(keyPair(2048));

        RSAKey first = SigningKey.load(pem);
        RSAKey second = SigningKey.from(pem);

        assertThat(first.getKeyID()).isNotBlank().isEqualTo(second.getKeyID())
                .isEqualTo(first.computeThumbprint().toString());
    }

    @Test
    void aTokenOneInstanceSignsVerifiesWithAnothersPublicKey() throws Exception {
        String pem = pem(keyPair(2048));
        RSAKey issuer = SigningKey.load(pem);
        RSAKey validator = SigningKey.load(pem).toPublicJWK();

        SignedJWT token = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(issuer.getKeyID()).build(),
                new JWTClaimsSet.Builder().subject("someone").expirationTime(new Date(System.currentTimeMillis() + 60_000))
                        .build());
        token.sign(new RSASSASigner(issuer));

        assertThat(token.verify(new RSASSAVerifier(validator))).isTrue();
        assertThat(token.getHeader().getKeyID()).isEqualTo(validator.getKeyID());
    }

    @Test
    void acceptsLineBreaksWrittenAsEscapes() throws Exception {
        String oneLine = pem(keyPair(2048)).strip().replace("\n", "\\n");

        assertThat(SigningKey.load(oneLine).isPrivate()).isTrue();
    }

    @Test
    void generatesAKeyWhenNoneIsConfigured() {
        RSAKey first = SigningKey.from("");
        RSAKey second = SigningKey.from(null);

        assertThat(first.isPrivate()).isTrue();
        assertThat(first.size()).isEqualTo(2048);
        assertThat(first.getKeyID()).isNotEqualTo(second.getKeyID());
    }

    @Test
    void refusesAPkcs1Key() {
        assertThatThrownBy(() -> SigningKey.load("-----BEGIN RSA PRIVATE KEY-----\nc2VjcmV0\n-----END RSA PRIVATE KEY-----"))
                .isInstanceOf(IllegalStateException.class).hasMessage(INVALID).hasNoCause();
    }

    @Test
    void refusesAKeyTooShort() throws Exception {
        String weak = pem(keyPair(1024));

        assertThatThrownBy(() -> SigningKey.load(weak)).isInstanceOf(IllegalStateException.class).hasMessage(INVALID);
    }

    @Test
    void refusesGarbageWithoutRepeatingIt() {
        String garbage = "-----BEGIN PRIVATE KEY-----\nbm90LWEta2V5LXNlY3JldA==\n-----END PRIVATE KEY-----";

        assertThatThrownBy(() -> SigningKey.load(garbage)).isInstanceOf(IllegalStateException.class)
                .hasMessage(INVALID).hasNoCause();
    }

    private static KeyPair keyPair(int bits) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(bits);
        return generator.generateKeyPair();
    }

    /** PKCS#8 PEM, as {@code openssl genpkey} writes it. */
    private static String pem(KeyPair pair) {
        String body = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(pair.getPrivate().getEncoded());
        return "-----BEGIN PRIVATE KEY-----\n" + body + "\n-----END PRIVATE KEY-----\n";
    }
}
