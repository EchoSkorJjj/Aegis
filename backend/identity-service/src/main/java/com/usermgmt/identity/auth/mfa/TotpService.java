package com.usermgmt.identity.auth.mfa;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;

/**
 * Time-based one-time passwords (RFC 6238, HMAC-SHA1). Secrets are Base32 (RFC 4648) so they paste
 * into any authenticator app. Verification allows ±{@code window} steps of clock drift.
 */
@Service
public class TotpService {

    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final int SECRET_BYTES = 20; // 160-bit shared secret

    private final int periodSeconds;
    private final int digits;
    private final int window;
    private final String issuer;
    private final SecureRandom random = new SecureRandom();

    public TotpService(
            @Value("${identity.mfa.totp.period-seconds:30}") int periodSeconds,
            @Value("${identity.mfa.totp.digits:6}") int digits,
            @Value("${identity.mfa.totp.window:1}") int window,
            @Value("${identity.mfa.totp.issuer:Identity}") String issuer) {
        this.periodSeconds = periodSeconds;
        this.digits = digits;
        this.window = window;
        this.issuer = issuer;
    }

    /** A fresh random Base32 secret to hand to the user's authenticator app. */
    public String newSecret() {
        byte[] bytes = new byte[SECRET_BYTES];
        random.nextBytes(bytes);
        return base32Encode(bytes);
    }

    /** The {@code otpauth://} URI the client renders as a QR code. */
    public String otpauthUri(String base32Secret, String accountName) {
        return "otpauth://totp/" + urlEncode(issuer) + ":" + urlEncode(accountName)
                + "?secret=" + base32Secret
                + "&issuer=" + urlEncode(issuer)
                + "&algorithm=SHA1&digits=" + digits + "&period=" + periodSeconds;
    }

    /** True if {@code code} matches the secret within the allowed drift window. */
    public boolean verify(String base32Secret, String code) {
        if (code == null || code.isBlank()) {
            return false;
        }
        String candidate = code.trim();
        long now = Instant.now().getEpochSecond();
        for (int step = -window; step <= window; step++) {
            if (generateCode(base32Secret, now + (long) step * periodSeconds).equals(candidate)) {
                return true;
            }
        }
        return false;
    }

    /** The code for a given secret and wall-clock second (package-private for RFC-vector tests). */
    String generateCode(String base32Secret, long timeSeconds) {
        long counter = timeSeconds / periodSeconds;
        byte[] hash = hmacSha1(base32Decode(base32Secret), ByteBuffer.allocate(8).putLong(counter).array());
        int offset = hash[hash.length - 1] & 0x0f;
        int binary = ((hash[offset] & 0x7f) << 24)
                | ((hash[offset + 1] & 0xff) << 16)
                | ((hash[offset + 2] & 0xff) << 8)
                | (hash[offset + 3] & 0xff);
        int otp = binary % (int) Math.pow(10, digits);
        return String.format("%0" + digits + "d", otp);
    }

    private static byte[] hmacSha1(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA1 unavailable", e);
        }
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    static String base32Encode(byte[] data) {
        StringBuilder out = new StringBuilder();
        int buffer = 0;
        int bitsLeft = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                out.append(BASE32.charAt((buffer >> (bitsLeft - 5)) & 0x1f));
                bitsLeft -= 5;
            }
        }
        if (bitsLeft > 0) {
            out.append(BASE32.charAt((buffer << (5 - bitsLeft)) & 0x1f));
        }
        return out.toString();
    }

    static byte[] base32Decode(String secret) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int buffer = 0;
        int bitsLeft = 0;
        for (char c : secret.trim().toUpperCase().toCharArray()) {
            int value = BASE32.indexOf(c);
            if (value < 0) {
                continue; // ignore padding / separators
            }
            buffer = (buffer << 5) | value;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                out.write((buffer >> (bitsLeft - 8)) & 0xff);
                bitsLeft -= 8;
            }
        }
        return out.toByteArray();
    }
}
