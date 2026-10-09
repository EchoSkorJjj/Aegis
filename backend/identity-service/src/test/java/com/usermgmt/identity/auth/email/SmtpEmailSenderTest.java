package com.usermgmt.identity.auth.email;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** The owner notification email (E9 story 5): untrusted item text stays one line, and the only links are the app's. */
class SmtpEmailSenderTest {

    private final JavaMailSender mailSender = mock(JavaMailSender.class);
    private final SmtpEmailSender sender = new SmtpEmailSender(mailSender, "no-reply@aegis.test", "https://app.test");

    @Test
    void itemTextCarriesNoLinkButTheAppsOwn() {
        SimpleMailMessage email = send(List.of(
                new OwnerNotification.Item("Acme status page: \"Sign in again at https://evil.example/login or "
                        + "www.evil.example now\"", "Payments", "/findings/1b2c"),
                new OwnerNotification.Item("Acme status page: \"Details: evil.example/help\"", "Payments",
                        "/findings/3d4e")));

        assertThat(email.getText())
                .contains("Acme status page: \"Sign in again at [link removed] or [link removed] now\" (Payments)")
                .contains("Acme status page: \"Details: evil[.]example/help\" (Payments)")
                .contains("https://app.test/findings/1b2c", "https://app.test/findings/3d4e",
                        "https://app.test/findings/mine")
                .doesNotContain("evil.example");
    }

    @Test
    void packageNamesStayReadable() {
        SimpleMailMessage email = send(List.of(
                new OwnerNotification.Item("CVE-2099-1 in org.apache.logging.log4j:log4j-core 2.14.1", "Payments",
                        "/findings/1"),
                new OwnerNotification.Item("CVE-2099-2 in github.com/gin-gonic/gin v1.7.0", "Payments",
                        "/findings/2")));

        assertThat(email.getText())
                .contains("CVE-2099-1 in org.apache.logging.log4j:log4j-core 2.14.1 (Payments)")
                .contains("CVE-2099-2 in github[.]com/gin-gonic/gin v1.7.0 (Payments)");
    }

    @Test
    void itemTextCannotAddLinesOrHeaders() {
        SimpleMailMessage email = send(List.of(new OwnerNotification.Item(
                "Outage\r\nBcc: someone@example.test\u2028Fake paragraph", "Payments\nhttps://evil.example",
                "/findings/1")));

        assertThat(email.getSubject()).isEqualTo("Aegis: Payments [link removed] needs your decision");
        assertThat(email.getText())
                .contains("- Outage Bcc: someone@example.test Fake paragraph (Payments [link removed])\n");
    }

    private SimpleMailMessage send(List<OwnerNotification.Item> items) {
        sender.sendOwnerNotification("owner@example.test", new OwnerNotification(items));
        ArgumentCaptor<SimpleMailMessage> sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(sent.capture());
        return sent.getValue();
    }
}
