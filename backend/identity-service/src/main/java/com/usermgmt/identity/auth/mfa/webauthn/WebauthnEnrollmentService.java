package com.usermgmt.identity.auth.mfa.webauthn;

import com.usermgmt.identity.audit.AuditEventType;
import com.usermgmt.identity.audit.AuditService;
import com.usermgmt.identity.auth.mfa.MfaSettings;
import com.usermgmt.identity.auth.mfa.recovery.RecoveryCodeService;
import com.usermgmt.identity.auth.mfa.webauthn.WebauthnDtos.RegistrationFinishRequest;
import com.usermgmt.identity.auth.mfa.webauthn.WebauthnDtos.RegistrationFinishRequest.AttestationResponse;
import com.usermgmt.identity.auth.mfa.webauthn.WebauthnDtos.RegistrationFinishResponse;
import com.usermgmt.identity.auth.mfa.webauthn.WebauthnDtos.CredentialDescriptor;
import com.usermgmt.identity.auth.mfa.webauthn.WebauthnDtos.PasskeySummary;
import com.usermgmt.identity.auth.mfa.webauthn.WebauthnDtos.RegistrationOptionsResponse;
import com.usermgmt.identity.auth.mfa.webauthn.WebauthnDtos.RegistrationOptionsResponse.AuthenticatorSelection;
import com.usermgmt.identity.auth.mfa.webauthn.WebauthnDtos.RegistrationOptionsResponse.PublicKeyCredentialParameter;
import com.usermgmt.identity.auth.mfa.webauthn.WebauthnDtos.RegistrationOptionsResponse.RelyingParty;
import com.usermgmt.identity.auth.mfa.webauthn.WebauthnDtos.RegistrationOptionsResponse.UserEntity;
import com.usermgmt.identity.common.ApiExceptions;
import com.usermgmt.identity.user.User;
import com.usermgmt.identity.user.UserRepository;
import com.webauthn4j.WebAuthnManager;
import com.webauthn4j.converter.AttestedCredentialDataConverter;
import com.webauthn4j.data.RegistrationData;
import com.webauthn4j.data.RegistrationParameters;
import com.webauthn4j.data.RegistrationRequest;
import com.webauthn4j.data.attestation.authenticator.AttestedCredentialData;
import com.webauthn4j.data.client.challenge.Challenge;
import com.webauthn4j.data.client.challenge.DefaultChallenge;
import com.webauthn4j.util.Base64UrlUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Self-service passkey (WebAuthn) registration and management. {@link #beginRegistration} issues
 * creation options bound to a stored single-use challenge; {@link #finishRegistration} verifies the
 * returned attestation with webauthn4j, persists the resulting public credential, and issues the
 * first batch of recovery codes when the account has none yet. {@link #listPasskeys} and
 * {@link #deletePasskey} back the settings page.
 */
@Service
public class WebauthnEnrollmentService {

    /** COSE algorithm identifiers we accept, most-preferred first: ES256 then RS256. */
    private static final long COSE_ES256 = -7;
    private static final long COSE_RS256 = -257;
    private static final String PUBLIC_KEY = "public-key";
    private static final int MAX_LABEL_LENGTH = 100;

    private final MfaSettings settings;
    private final WebauthnRelyingParty rp;
    private final WebAuthnManager webAuthnManager;
    private final AttestedCredentialDataConverter attestedCredentialDataConverter;
    private final WebauthnCredentialRepository credentials;
    private final WebauthnRegistrationRepository registrations;
    private final UserRepository users;
    private final RecoveryCodeService recoveryCodes;
    private final AuditService audit;

    public WebauthnEnrollmentService(MfaSettings settings,
                                     WebauthnRelyingParty rp,
                                     WebAuthnManager webAuthnManager,
                                     AttestedCredentialDataConverter attestedCredentialDataConverter,
                                     WebauthnCredentialRepository credentials,
                                     WebauthnRegistrationRepository registrations,
                                     UserRepository users,
                                     RecoveryCodeService recoveryCodes,
                                     AuditService audit) {
        this.settings = settings;
        this.rp = rp;
        this.webAuthnManager = webAuthnManager;
        this.attestedCredentialDataConverter = attestedCredentialDataConverter;
        this.credentials = credentials;
        this.registrations = registrations;
        this.users = users;
        this.recoveryCodes = recoveryCodes;
        this.audit = audit;
    }

    /** Issues creation options and stores the (single-use) challenge the authenticator must sign. */
    @Transactional
    public RegistrationOptionsResponse beginRegistration(UUID userId) {
        requireEnabled();
        User user = users.findById(userId)
                .orElseThrow(() -> new ApiExceptions.Unauthorized("Not authenticated"));

        Challenge challenge = new DefaultChallenge();
        WebauthnRegistration pending = registrations.findById(userId)
                .orElseGet(() -> new WebauthnRegistration(userId));
        pending.setChallenge(Base64UrlUtil.encodeToString(challenge.getValue()));
        pending.setExpiresAt(Instant.now().plusSeconds(rp.timeoutSeconds()));
        registrations.save(pending);

        List<CredentialDescriptor> exclude = credentials.findByUserId(userId).stream()
                .map(c -> new CredentialDescriptor(PUBLIC_KEY, c.getCredentialId(), transportList(c.getTransports())))
                .toList();

        return new RegistrationOptionsResponse(
                new RelyingParty(rp.id(), rp.name()),
                new UserEntity(Base64UrlUtil.encodeToString(userHandle(userId)), user.getEmail(), displayName(user)),
                pending.getChallenge(),
                List.of(new PublicKeyCredentialParameter(PUBLIC_KEY, COSE_ES256),
                        new PublicKeyCredentialParameter(PUBLIC_KEY, COSE_RS256)),
                rp.timeoutSeconds() * 1000L,
                exclude,
                new AuthenticatorSelection("preferred", "preferred"),
                "none");
    }

    /** Verifies the attestation against the stored challenge and persists the new public credential. */
    @Transactional
    public RegistrationFinishResponse finishRegistration(UUID userId, RegistrationFinishRequest request) {
        requireEnabled();
        WebauthnRegistration pending = registrations.findById(userId)
                .orElseThrow(() -> new ApiExceptions.BadRequest("Start passkey registration first"));
        if (pending.getExpiresAt().isBefore(Instant.now())) {
            registrations.delete(pending);
            throw new ApiExceptions.BadRequest("Passkey registration expired — start again");
        }

        AttestationResponse response = request.credential().response();
        RegistrationData data = verifyAttestation(pending, response);

        AttestedCredentialData attested =
                data.getAttestationObject().getAuthenticatorData().getAttestedCredentialData();
        String credentialId = Base64UrlUtil.encodeToString(attested.getCredentialId());
        if (credentials.existsByCredentialId(credentialId)) {
            throw new ApiExceptions.Conflict("That passkey is already registered");
        }

        WebauthnCredential credential = new WebauthnCredential(
                UUID.randomUUID(), userId, credentialId,
                Base64UrlUtil.encodeToString(attestedCredentialDataConverter.convert(attested)),
                data.getAttestationObject().getAuthenticatorData().getSignCount());
        credential.setAaguid(attested.getAaguid().toString());
        credential.setTransports(joinTransports(response.transports()));
        credential.setLabel(label(request.label()));
        credentials.save(credential);
        registrations.delete(pending);

        audit.event(AuditEventType.MFA_ENROLLED).actor(userId).detail("WEBAUTHN").log();

        // First strong factor for this account? Hand out backup codes, as TOTP enrollment does.
        List<String> newRecoveryCodes = List.of();
        if (recoveryCodes.remaining(userId) == 0) {
            newRecoveryCodes = recoveryCodes.regenerate(userId);
            audit.event(AuditEventType.MFA_RECOVERY_CODES_GENERATED)
                    .actor(userId).detail(newRecoveryCodes.size() + " codes").log();
        }
        return new RegistrationFinishResponse(credentialId, credential.getLabel(), newRecoveryCodes);
    }

    /** The caller's registered passkeys, newest first, for the settings page. */
    @Transactional(readOnly = true)
    public List<PasskeySummary> listPasskeys(UUID userId) {
        return credentials.findByUserId(userId).stream()
                .sorted(Comparator.comparing(WebauthnCredential::getCreatedAt).reversed())
                .map(c -> new PasskeySummary(
                        c.getId().toString(),
                        c.getLabel(),
                        c.getCreatedAt().toString(),
                        c.getLastUsedAt() == null ? null : c.getLastUsedAt().toString()))
                .toList();
    }

    /** Removes one of the caller's passkeys (404 if it is not theirs). */
    @Transactional
    public void deletePasskey(UUID userId, UUID credentialRowId) {
        WebauthnCredential credential = credentials.findByIdAndUserId(credentialRowId, userId)
                .orElseThrow(() -> new ApiExceptions.NotFound("Passkey not found"));
        credentials.delete(credential);
        audit.event(AuditEventType.MFA_DISABLED).actor(userId).detail("WEBAUTHN").log();
    }

    private RegistrationData verifyAttestation(WebauthnRegistration pending, AttestationResponse response) {
        try {
            RegistrationRequest registrationRequest = new RegistrationRequest(
                    Base64UrlUtil.decode(response.attestationObject()),
                    Base64UrlUtil.decode(response.clientDataJSON()));
            Challenge challenge = new DefaultChallenge(Base64UrlUtil.decode(pending.getChallenge()));
            RegistrationParameters parameters =
                    new RegistrationParameters(rp.serverProperty(challenge), null, false, true);
            RegistrationData data = webAuthnManager.parse(registrationRequest);
            webAuthnManager.validate(data, parameters);
            return data;
        } catch (RuntimeException ex) {
            // Any parse/validation failure means the attestation is malformed or doesn't verify.
            throw new ApiExceptions.BadRequest("Passkey could not be verified");
        }
    }

    private void requireEnabled() {
        if (!settings.isWebauthnEnabled()) {
            throw new ApiExceptions.BadRequest("Passkey sign-in is not enabled");
        }
    }

    /** A stable, non-PII user handle for WebAuthn: the account's UUID as 16 bytes. */
    private static byte[] userHandle(UUID userId) {
        return ByteBuffer.allocate(16)
                .putLong(userId.getMostSignificantBits())
                .putLong(userId.getLeastSignificantBits())
                .array();
    }

    private static String displayName(User user) {
        return user.getDisplayName() != null && !user.getDisplayName().isBlank()
                ? user.getDisplayName() : user.getEmail();
    }

    private static String label(String raw) {
        String trimmed = raw.trim();
        return trimmed.length() > MAX_LABEL_LENGTH ? trimmed.substring(0, MAX_LABEL_LENGTH) : trimmed;
    }

    private static String joinTransports(List<String> transports) {
        return transports == null || transports.isEmpty() ? null : String.join(",", transports);
    }

    private static List<String> transportList(String transports) {
        return transports == null || transports.isBlank() ? List.of() : Arrays.asList(transports.split(","));
    }
}
