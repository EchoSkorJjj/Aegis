package com.usermgmt.identity.auth.mfa.webauthn;

import com.webauthn4j.data.client.Origin;
import com.webauthn4j.data.client.challenge.Challenge;
import com.webauthn4j.server.ServerProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * This deployment's WebAuthn Relying Party identity — the {@code rpId}, display name, and the exact
 * browser {@code origin} allowed to complete ceremonies. Configured via {@code identity.mfa.webauthn.*}.
 * Both the registration and (later) assertion ceremonies share {@link #serverProperty(Challenge)}.
 */
@Component
public class WebauthnRelyingParty {

    private final String id;
    private final String name;
    private final Origin origin;
    private final int timeoutSeconds;

    public WebauthnRelyingParty(
            @Value("${identity.mfa.webauthn.rp-id:localhost}") String id,
            @Value("${identity.mfa.webauthn.rp-name:Aegis}") String name,
            @Value("${identity.mfa.webauthn.origin:http://localhost:3000}") String origin,
            @Value("${identity.mfa.webauthn.timeout-seconds:300}") int timeoutSeconds) {
        this.id = id;
        this.name = name;
        this.origin = new Origin(origin);
        this.timeoutSeconds = timeoutSeconds;
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public int timeoutSeconds() {
        return timeoutSeconds;
    }

    /** The server-side ceremony context: our origin + rpId bound to the challenge we issued. */
    public ServerProperty serverProperty(Challenge challenge) {
        return new ServerProperty(origin, id, challenge, null);
    }
}
