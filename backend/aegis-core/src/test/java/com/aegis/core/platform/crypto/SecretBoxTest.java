package com.aegis.core.platform.crypto;

import java.security.SecureRandom;
import java.util.Base64;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** What sealing a credential promises: it opens only unaltered, with the same key, for the same owner. */
class SecretBoxTest {

    private static final String KEY = key();
    private final SecretBox box = SecretBox.fromBase64(KEY);

    @Test
    void aSealedValueOpensForItsOwnContextAndIsNeverStoredInTheClear() {
        String sealed = box.seal("refresh-token-secret", "jira:org-a");

        assertThat(sealed).doesNotContain("refresh-token-secret");
        assertThat(box.open(sealed, "jira:org-a")).isEqualTo("refresh-token-secret");
        assertThat(box.seal("refresh-token-secret", "jira:org-a")).as("a fresh nonce every time").isNotEqualTo(sealed);
    }

    @Test
    void itWillNotOpenForAnotherOwnerAnotherKeyOrOnceAltered() {
        String sealed = box.seal("refresh-token-secret", "jira:org-a");
        byte[] altered = Base64.getDecoder().decode(sealed);
        altered[altered.length - 1] ^= 1;

        assertThatThrownBy(() -> box.open(sealed, "jira:org-b")).isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("refresh-token-secret");
        assertThatThrownBy(() -> SecretBox.fromBase64(key()).open(sealed, "jira:org-a"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> box.open(Base64.getEncoder().encodeToString(altered), "jira:org-a"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> box.open("not base64 at all", "jira:org-a")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theKeyMustBeThirtyTwoBase64Bytes() {
        assertThatThrownBy(() -> SecretBox.fromBase64(" ")).hasMessageContaining("AEGIS_SECRETS_KEY is required");
        assertThatThrownBy(() -> SecretBox.fromBase64("not-base64!")).hasMessageContaining("must be base64");
        assertThatThrownBy(() -> SecretBox.fromBase64(Base64.getEncoder().encodeToString(new byte[16])))
                .hasMessageContaining("32 bytes");
        assertThat(box.toString()).doesNotContain(KEY);
    }

    private static String key() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }
}
