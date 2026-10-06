package com.aegis.core.platform.common;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;

class SensitiveTextTest {

    @ParameterizedTest
    @MethodSource("sensitive")
    void secretsAndPersonalDataAreRemovedIdempotently(String source) {
        String protectedText = SensitiveText.redact(source);
        assertThat(protectedText).contains(SensitiveText.REDACTED).doesNotContain(source);
        assertThat(SensitiveText.redact(protectedText)).isEqualTo(protectedText);
    }

    private static Stream<String> sensitive() {
        return Stream.of(
                "-----BEGIN RSA PRIVATE KEY-----\nprivate-material\n-----END RSA PRIVATE KEY-----",
                "-----BEGIN PRIVATE KEY-----\nunfinished-material",
                "https://user:credential@host.example/path", "postgresql://user:credential@db.example/database",
                "Bearer opaque-credential", "Basic dXNlcjpwYXNzd29yZA==",
                "ghp_" + "a".repeat(36), "github_pat_" + "b".repeat(30), "gsk_" + "c7".repeat(15),
                "sk-" + "d4".repeat(15), "sk-proj-" + "Ab3".repeat(10), "xoxb-" + "e".repeat(30),
                "AKIA" + "F".repeat(16), "user@163.com",
                "eyJhbGci.eyJzdWI.signature", "\"api_key\": \"opaque-credential\"", "password='opaque credential'",
                "refresh_token=opaque-credential", "AWS_SECRET_ACCESS_KEY=opaque-credential",
                "AEGIS_SECRETS_KEY=opaque-credential", "DATABASE_PASSWORD=opaque-credential",
                "Cookie: session=opaque-credential; extra=another-credential",
                "alice.smith@example.test", "alice@\u200bexample.test",
                "123-45-6789", "phone: 555-123-4567", "full_name: Alice Smith",
                "home address: 123 Example Road", "+65 8123 4567");
    }

    @ParameterizedTest
    @MethodSource("advisoryProse")
    void packageSpecsAndSecurityVocabularySurvive(String prose) {
        assertThat(SensitiveText.redact(prose)).isEqualTo(prose);
    }

    private static Stream<String> advisoryProse() {
        return Stream.of(
                "Upgrade to lodash@4.17.21 or later.",
                "Affected: pkg:npm/lodash@4.17.20 and pkg:maven/org.apache.logging.log4j/log4j-core@2.14.1",
                "Fixed in pkg:maven/org.springframework/spring-core@5.3.20.RELEASE",
                "axios before 1.6.0 does not validate Basic authentication headers.",
                "Bearer tokens in the Authorization header are written to logs.",
                "The skip_certificate_verification option disables TLS checks.",
                "Service sk-payments-gateway-service-prod depends on the library.",
                "A CSRF token: the server never checks it.",
                "The Set-Cookie: header lacks the Secure flag.");
    }

    @Test
    void onlyTheValueIsRedactedNotTheRestOfTheLine() {
        assertThat(SensitiveText.redact("Contact phone: 555-123-4567. Check the continuity plan."))
                .isEqualTo("Contact [REDACTED]. Check the continuity plan.");
        assertThat(SensitiveText.redact("full_name: Alice Smith, then confirm the impact."))
                .isEqualTo("[REDACTED], then confirm the impact.");
        assertThat(SensitiveText.redact("Authorization: Bearer opaque-credential and nothing else"))
                .isEqualTo("Authorization: [REDACTED] and nothing else");
    }

    @Test
    void publicReferencesVersionsAndScoreArithmeticSurvive() {
        String facts = "CVE-2021-44228 GHSA-abcd-efgh-ijkl org.acme:library 1.2.3 2026-10-06 "
                + "severity 0.91 x exposure 1 x criticality 0.8 = 72.8 "
                + "https://nvd.nist.gov/vuln/detail/CVE-2021-44228";
        assertThat(SensitiveText.redact(facts)).isEqualTo(facts);
        assertThat(SensitiveText.redact((String) null)).isNull();
    }
}
