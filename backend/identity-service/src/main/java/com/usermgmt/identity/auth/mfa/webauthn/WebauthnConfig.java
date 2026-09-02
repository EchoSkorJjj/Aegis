package com.usermgmt.identity.auth.mfa.webauthn;

import com.webauthn4j.WebAuthnManager;
import com.webauthn4j.converter.AttestedCredentialDataConverter;
import com.webauthn4j.converter.util.ObjectConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * webauthn4j collaborators, wired once and shared by the WebAuthn services. The "non-strict"
 * manager skips attestation-trust-anchor checks — appropriate for {@code attestation: none}
 * enrollment, where we verify the ceremony but do not vet the authenticator's make/model.
 */
@Configuration
public class WebauthnConfig {

    @Bean
    public ObjectConverter webauthnObjectConverter() {
        return new ObjectConverter();
    }

    @Bean
    public WebAuthnManager webAuthnManager() {
        return WebAuthnManager.createNonStrictWebAuthnManager();
    }

    @Bean
    public AttestedCredentialDataConverter attestedCredentialDataConverter(ObjectConverter objectConverter) {
        return new AttestedCredentialDataConverter(objectConverter);
    }
}
