package com.usermgmt.identity.config;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.UUID;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * The "auth seam" as Spring beans.
 *
 * <p>mode=local     -> this service signs and validates its own RSA JWTs.
 * <p>mode=delegated -> this service validates an external OIDC provider's JWTs.
 *
 * <p>Everything downstream (profile CRUD, roles, authorization) is identical in both modes.
 */
@Configuration
public class JwtConfig {

    /**
     * Dev/default: ephemeral RSA key generated at startup (tokens are invalidated on restart).
     * For production, load a stable key from a secret manager / keystore instead.
     */
    @Bean
    public RSAKey rsaKey() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair keyPair = generator.generateKeyPair();
            return new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                    .privateKey((RSAPrivateKey) keyPair.getPrivate())
                    .keyID(UUID.randomUUID().toString())
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to generate RSA signing key", e);
        }
    }

    @Bean
    public JWKSource<SecurityContext> jwkSource(RSAKey rsaKey) {
        return new ImmutableJWKSet<>(new JWKSet(rsaKey));
    }

    /** Used only when this service issues tokens (local mode). */
    @Bean
    public JwtEncoder jwtEncoder(JWKSource<SecurityContext> jwkSource) {
        return new NimbusJwtEncoder(jwkSource);
    }

    @Bean
    public JwtDecoder jwtDecoder(RSAKey rsaKey,
                                 @Value("${identity.auth.mode:local}") String mode,
                                 @Value("${identity.auth.issuer-uri:}") String issuerUri) {
        if ("delegated".equalsIgnoreCase(mode)) {
            if (issuerUri == null || issuerUri.isBlank()) {
                throw new IllegalStateException(
                        "identity.auth.mode=delegated requires identity.auth.issuer-uri to be set");
            }
            return JwtDecoders.fromIssuerLocation(issuerUri);
        }
        try {
            return NimbusJwtDecoder.withPublicKey(rsaKey.toRSAPublicKey()).build();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to build local JwtDecoder", e);
        }
    }
}
