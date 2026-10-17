package com.usermgmt.identity.support;

import java.util.List;

import com.jayway.jsonpath.JsonPath;
import com.webauthn4j.data.AuthenticatorSelectionCriteria;
import com.webauthn4j.data.PublicKeyCredentialCreationOptions;
import com.webauthn4j.data.PublicKeyCredentialDescriptor;
import com.webauthn4j.data.PublicKeyCredentialParameters;
import com.webauthn4j.data.PublicKeyCredentialRequestOptions;
import com.webauthn4j.data.PublicKeyCredentialRpEntity;
import com.webauthn4j.data.PublicKeyCredentialType;
import com.webauthn4j.data.PublicKeyCredentialUserEntity;
import com.webauthn4j.data.ResidentKeyRequirement;
import com.webauthn4j.data.UserVerificationRequirement;
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier;
import com.webauthn4j.data.client.Origin;
import com.webauthn4j.data.client.challenge.DefaultChallenge;
import com.webauthn4j.test.EmulatorUtil;
import com.webauthn4j.test.client.ClientPlatform;
import com.webauthn4j.util.Base64UrlUtil;

/**
 * A software WebAuthn authenticator for integration tests. It wraps webauthn4j's emulator + client
 * platform and turns the server's ceremony options (JSON) into the browser-shaped finish request
 * (JSON) our endpoints accept — so tests exercise real attestation and assertion with no hardware
 * and no browser. One instance is one authenticator: {@link #registrationResponse} enrolls a
 * credential that a later {@link #assertionResponse} on the same instance can sign with.
 */
public final class WebauthnTestAuthenticator {

    private final ClientPlatform clientPlatform;

    public WebauthnTestAuthenticator(String origin) {
        this.clientPlatform = EmulatorUtil.createClientPlatform(EmulatorUtil.PACKED_AUTHENTICATOR);
        this.clientPlatform.setOrigin(new Origin(origin));
    }

    /** Turns a {@code /register/begin} options body into the {@code /register/finish} request body. */
    public String registrationResponse(String beginOptions, String label) {
        String rpId = JsonPath.read(beginOptions, "$.rp.id");
        String rpName = JsonPath.read(beginOptions, "$.rp.name");
        String userId = JsonPath.read(beginOptions, "$.user.id");
        String userName = JsonPath.read(beginOptions, "$.user.name");
        String userDisplayName = JsonPath.read(beginOptions, "$.user.displayName");
        String challenge = JsonPath.read(beginOptions, "$.challenge");

        AuthenticatorSelectionCriteria selection = new AuthenticatorSelectionCriteria(
                null, ResidentKeyRequirement.DISCOURAGED, UserVerificationRequirement.PREFERRED);
        PublicKeyCredentialCreationOptions options = new PublicKeyCredentialCreationOptions(
                new PublicKeyCredentialRpEntity(rpId, rpName),
                new PublicKeyCredentialUserEntity(Base64UrlUtil.decode(userId), userName, userDisplayName),
                new DefaultChallenge(Base64UrlUtil.decode(challenge)),
                List.of(new PublicKeyCredentialParameters(PublicKeyCredentialType.PUBLIC_KEY, COSEAlgorithmIdentifier.ES256)),
                null, null, selection, null, null);

        var credential = clientPlatform.create(options);
        var response = credential.getResponse();
        return """
                {"label":"%s","credential":{"id":"%s","type":"public-key",
                 "response":{"clientDataJSON":"%s","attestationObject":"%s"}}}
                """.formatted(label, credential.getId(),
                Base64UrlUtil.encodeToString(response.getClientDataJSON()),
                Base64UrlUtil.encodeToString(response.getAttestationObject()));
    }

    /** Turns a {@code /login/webauthn/options} body + challenge id into the {@code verify} request body. */
    public String assertionResponse(String requestOptions, String challengeId) {
        String rpId = JsonPath.read(requestOptions, "$.rpId");
        String challenge = JsonPath.read(requestOptions, "$.challenge");
        String allowId = JsonPath.read(requestOptions, "$.allowCredentials[0].id");

        PublicKeyCredentialRequestOptions options = new PublicKeyCredentialRequestOptions(
                new DefaultChallenge(Base64UrlUtil.decode(challenge)), 60_000L, rpId,
                List.of(new PublicKeyCredentialDescriptor(PublicKeyCredentialType.PUBLIC_KEY, Base64UrlUtil.decode(allowId), null)),
                UserVerificationRequirement.PREFERRED, null);

        var assertion = clientPlatform.get(options);
        var response = assertion.getResponse();
        return """
                {"challengeId":"%s","credential":{"id":"%s","type":"public-key",
                 "response":{"clientDataJSON":"%s","authenticatorData":"%s","signature":"%s"}}}
                """.formatted(challengeId, assertion.getId(),
                Base64UrlUtil.encodeToString(response.getClientDataJSON()),
                Base64UrlUtil.encodeToString(response.getAuthenticatorData()),
                Base64UrlUtil.encodeToString(response.getSignature()));
    }
}
