package com.usermgmt.identity.observability;

import com.usermgmt.identity.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The Prometheus scrape endpoint is public and carries both JVM and our custom security metrics. */
class ObservabilityIntegrationTest extends AbstractIntegrationTest {

    @Test
    void prometheus_endpoint_is_public_and_exposes_metrics() throws Exception {
        // Generate an auditable event so the custom counter is registered and non-zero.
        registerUser("metrics@example.com", "password123", "Metrics");

        String body = mvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("jvm_"); // standard JVM metrics
        assertThat(body).contains("identity_audit_events_total"); // our security metric
    }

    @Test
    void other_actuator_metrics_require_authentication() throws Exception {
        // /actuator/prometheus is deliberately public (Prometheus can't log in); the rest are not.
        mvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized());
    }
}
