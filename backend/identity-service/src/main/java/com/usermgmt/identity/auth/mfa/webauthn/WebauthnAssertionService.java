package com.usermgmt.identity.auth.mfa.webauthn;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.usermgmt.identity.auth.mfa.LoginChallenge;
import com.usermgmt.identity.auth.mfa.LoginChallengeRepository;
import com.usermgmt.identity.auth.mfa.MfaMethod;
import com.usermgmt.identity.auth.mfa.webauthn.WebauthnDtos.AssertionFinishRequest;
import com.usermgmt.identity.auth.mfa.webauthn.WebauthnDtos.AssertionFinishRequest.AssertionResponse;
import com.usermgmt.identity.auth.mfa.webauthn.WebauthnDtos.CredentialDescriptor;
import com.usermgmt.identity.auth.mfa.webauthn.WebauthnDtos.RequestOptionsResponse;
import com.usermgmt.identity.common.ApiExceptions;
import com.webauthn4j.WebAuthnManager;
import com.webauthn4j.converter.AttestedCredentialDataConverter;
import com.webauthn4j.credential.CredentialRecord;
import com.webauthn4j.credential.CredentialRecordImpl;
import com.webauthn4j.data.AuthenticationData;
import com.webauthn4j.data.AuthenticationParameters;
import com.webauthn4j.data.AuthenticationRequest;
import com.webauthn4j.data.attestation.authenticator.AttestedCredentialData;
import com.webauthn4j.data.attestation.statement.NoneAttestationStatement;
import com.webauthn4j.data.client.challenge.Challenge;
import com.webauthn4j.data.client.challenge.DefaultChallenge;
import com.webauthn4j.data.extension.authenticator.AuthenticationExtensionsAuthenticatorOutputs;
import com.webauthn4j.util.Base64UrlUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The WebAuthn / passkey assertion (login) ceremony. {@link #options} returns request options for a
 * pending login challenge that was armed with the WEBAUTHN method; {@link #verify} checks the
 * returned assertion against the stored public key and the issued challenge, advances the signature
 * counter, consumes the challenge on success, and reports the authenticated user.
 */
@Service
public class WebauthnAssertionService {

    private static final String PUBLIC_KEY = "public-key";

    private final LoginChallengeRepository challenges;
    private final WebauthnCredentialRepository credentials;
    private final WebAuthnManager webAuthnManager;
    private final AttestedCredentialDataConverter attestedCredentialDataConverter;
    private final WebauthnRelyingParty rp;

    public WebauthnAssertionService(LoginChallengeRepository challenges,
                                    WebauthnCredentialRepository credentials,
                                    WebAuthnManager webAuthnManager,
                                    AttestedCredentialDataConverter attestedCredentialDataConverter,
                                    WebauthnRelyingParty rp) {
        this.challenges = challenges;
        this.credentials = credentials;
        this.webAuthnManager = webAuthnManager;
        this.attestedCredentialDataConverter = attestedCredentialDataConverter;
        this.rp = rp;
    }

    /** Request options (challenge + the user's allowed credentials) for the pending login challenge. */
    @Transactional(readOnly = true)
    public RequestOptionsResponse options(String challengeId) {
        LoginChallenge challenge = activeWebauthnChallenge(challengeId)
                .orElseThrow(() -> new ApiExceptions.Unauthorized("Invalid or expired challenge"));
        List<CredentialDescriptor> allow = credentials.findByUserId(challenge.getUserId()).stream()
                .map(c -> new CredentialDescriptor(PUBLIC_KEY, c.getCredentialId(), transportList(c.getTransports())))
                .toList();
        return new RequestOptionsResponse(
                challenge.getWebauthnChallenge(), rp.id(), allow, "preferred", rp.timeoutSeconds() * 1000L);
    }

    /**
     * Verifies an assertion against the challenge's stored nonce. On success the challenge is consumed
     * and the user id returned; on any failure it returns empty (and the challenge stays usable until
     * it expires, so the user can retry). Own transaction so the counter/consume survive a thrown 401.
     */
    @Transactional
    public Optional<UUID> verify(String challengeId, AssertionFinishRequest request) {
        LoginChallenge challenge = activeWebauthnChallenge(challengeId).orElse(null);
        if (challenge == null) {
            return Optional.empty();
        }
        WebauthnCredential stored = credentials.findByCredentialId(request.credential().id()).orElse(null);
        if (stored == null || !stored.getUserId().equals(challenge.getUserId())) {
            return Optional.empty(); // unknown credential, or not this user's
        }

        AssertionResponse response = request.credential().response();
        try {
            AttestedCredentialData attested = attestedCredentialDataConverter.convert(
                    Base64UrlUtil.decode(stored.getAttestedCredentialData()));
            // Rebuild the credential record from what we stored at registration. Assertion only needs
            // the public key (inside `attested`) and the signature counter; the attestation statement
            // and extensions are registration-time data the assertion path never reads, but the
            // constructor requires them non-null — so pass a "none" statement and empty extensions.
            // The uv/backup flags are left null: the verifier treats a null backup-eligibility as
            // "skip that check" (see webauthn4j BEFlagVerifier).
            CredentialRecord credentialRecord = new CredentialRecordImpl(
                    new NoneAttestationStatement(),   // attestationStatement (unused for assertion)
                    null,                             // uvInitialized
                    null,                             // backupEligible
                    null,                             // backupState
                    stored.getSignCount(),            // counter
                    attested,                         // attestedCredentialData — holds the COSE public key
                    new AuthenticationExtensionsAuthenticatorOutputs.BuilderForRegistration().build(), // authenticatorExtensions (empty)
                    null,                             // clientData
                    null,                             // clientExtensions
                    null);                            // transports
            Challenge nonce = new DefaultChallenge(Base64UrlUtil.decode(challenge.getWebauthnChallenge()));
            AuthenticationRequest authenticationRequest = new AuthenticationRequest(
                    Base64UrlUtil.decode(request.credential().id()),
                    Base64UrlUtil.decode(response.authenticatorData()),
                    Base64UrlUtil.decode(response.clientDataJSON()),
                    Base64UrlUtil.decode(response.signature()));
            AuthenticationParameters parameters = new AuthenticationParameters(
                    rp.serverProperty(nonce), credentialRecord, null, false, true);
            AuthenticationData data = webAuthnManager.parse(authenticationRequest);
            webAuthnManager.verify(data, parameters);

            long newSignCount = data.getAuthenticatorData().getSignCount();
            if (newSignCount > stored.getSignCount()) {
                stored.setSignCount(newSignCount);
            }
            stored.setLastUsedAt(Instant.now());
            challenge.setConsumed(true);
            return Optional.of(challenge.getUserId());
        } catch (RuntimeException ex) {
            return Optional.empty(); // assertion did not verify
        }
    }

    private Optional<LoginChallenge> activeWebauthnChallenge(String challengeId) {
        UUID id;
        try {
            id = UUID.fromString(challengeId);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        LoginChallenge challenge = challenges.findById(id).orElse(null);
        if (challenge == null || challenge.isConsumed()
                || challenge.getMethod() != MfaMethod.WEBAUTHN
                || challenge.getWebauthnChallenge() == null
                || challenge.getExpiresAt().isBefore(Instant.now())) {
            return Optional.empty();
        }
        return Optional.of(challenge);
    }

    private static List<String> transportList(String transports) {
        return transports == null || transports.isBlank() ? List.of() : Arrays.asList(transports.split(","));
    }
}
