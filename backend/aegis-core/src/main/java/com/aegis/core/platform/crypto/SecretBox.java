package com.aegis.core.platform.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Seals credentials Aegis must keep — an integration's refresh token — so the database never holds them
 * in the clear: AES-256-GCM under one application key ({@code AEGIS_SECRETS_KEY}), with a fresh nonce per
 * seal and a {@code context} (say, the owning organization) bound in as associated data, so a sealed value
 * copied to another row or organization no longer opens. Rotating the key, and holding it in a secret
 * manager, is Epic 3's work.
 */
public final class SecretBox {

    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;

    private SecretBox(byte[] key) {
        this.key = new SecretKeySpec(key, "AES");
    }

    /** A box for a base64-encoded 32-byte key; {@link IllegalStateException} with what is wrong otherwise. */
    public static SecretBox fromBase64(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("AEGIS_SECRETS_KEY is required to store integration credentials");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(key.strip());
        } catch (IllegalArgumentException notBase64) {
            throw new IllegalStateException("AEGIS_SECRETS_KEY must be base64");
        }
        if (bytes.length != 32) {
            throw new IllegalStateException("AEGIS_SECRETS_KEY must be 32 bytes (openssl rand -base64 32)");
        }
        return new SecretBox(bytes);
    }

    public String seal(String plaintext, String context) {
        byte[] nonce = new byte[NONCE_BYTES];
        RANDOM.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(nonce.length + sealed.length)
                    .put(nonce).put(sealed).array());
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("Could not seal a credential", impossible);
        }
    }

    /** The plaintext, or {@link IllegalStateException} when the value was altered, or sealed for another context or key. */
    public String open(String sealed, String context) {
        try {
            byte[] bytes = Base64.getDecoder().decode(sealed);
            if (bytes.length <= NONCE_BYTES) {
                throw new IllegalStateException("A stored credential is damaged");
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, bytes, 0, NONCE_BYTES));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(bytes, NONCE_BYTES, bytes.length - NONCE_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException unreadable) {
            // Never pass the cause on: it can describe the key or the ciphertext.
            throw new IllegalStateException("A stored credential could not be opened; connect the integration again");
        }
    }

    @Override
    public String toString() {
        return "SecretBox[AES-256-GCM]";
    }
}
