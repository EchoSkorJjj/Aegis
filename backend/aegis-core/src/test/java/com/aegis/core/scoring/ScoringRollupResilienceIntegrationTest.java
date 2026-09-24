package com.aegis.core.scoring;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.aegis.core.advisories.Advisory;
import com.aegis.core.advisories.AdvisoryStore;
import com.aegis.core.advisories.AffectedProduct;
import com.aegis.core.inventory.InventoryService;
import com.aegis.core.inventory.domain.BusinessCriticality;
import com.aegis.core.inventory.domain.BusinessService;
import com.aegis.core.inventory.domain.ServiceExposure;
import com.aegis.core.inventory.domain.SoftwareComponent;
import com.aegis.core.platform.common.ApiExceptions;
import com.aegis.core.support.AbstractInventoryIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The roll-up's "one bad signal must not sink the whole ranking" promise, exercised through the real
 * Spring beans (CS203-74). {@code ScoringRollupTest} covers the same path with plain mocks, which is
 * exactly why it could not see this: matching runs in its own {@code @Transactional} method, and an
 * exception thrown there marks a shared transaction rollback-only even when the caller catches it —
 * so the roll-up stepped over the bad signal and then failed with a 500 when it tried to commit.
 *
 * <p>The failure is injected through the shared {@code matchingSpy} (declared on
 * {@code AbstractIntegrationTest}, so this class runs in the suite's one application context): no real
 * data makes matching throw for a signal the roll-up has just listed, short of a race.
 */
class ScoringRollupResilienceIntegrationTest extends AbstractInventoryIntegrationTest {

    @Autowired
    private InventoryService inventory;

    @Autowired
    private AdvisoryStore advisories;

    @Test
    void aSignalThatFailsToMatchIsSkippedWithoutFailingTheRollup() throws Exception {
        String tag = Long.toHexString(Math.abs(UUID.randomUUID().getMostSignificantBits()));
        BusinessService service = inventory.createBusinessService(
                "Resilient " + tag, BusinessCriticality.CRITICAL, ServiceExposure.INTERNET_FACING);
        String packageName = "resilient-lib-" + tag;
        SoftwareComponent component = inventory.createSoftwareComponent("maven", "com.acme:" + packageName, "1.0.0");
        inventory.addServiceComponent(service.getId(), component.getId());

        advisories.upsert(advisory(tag, "1", packageName));
        Advisory broken = advisories.upsert(advisory(tag, "2", packageName));
        // The second signal's match blows up inside matching's own transaction — the way a bad row or
        // a mid-window deletion would.
        doThrow(new ApiExceptions.NotFound("advisory vanished mid-window")).when(matchingSpy).matchChange(broken.id());

        mvc.perform(get("/api/scoring/rollups").param("search", tag)
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ANALYST"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.signalsConsidered").value(2))
                .andExpect(jsonPath("$.signalsSkipped").value(1))
                .andExpect(jsonPath("$.signalsImpacting").value(1))
                .andExpect(jsonPath("$.services.length()").value(1))
                .andExpect(jsonPath("$.services[0].service.id").value(service.getId().toString()))
                .andExpect(jsonPath("$.services[0].signalCount").value(1))
                .andExpect(jsonPath("$.services[0].topSignal.externalId").value("CVE-2099-" + tag + "1"));
    }

    private static Advisory advisory(String tag, String ordinal, String packageName) {
        return new Advisory(
                null, "nvd", "CVE-2099-" + tag + ordinal, "resilience advisory", Instant.now(), Instant.now(),
                new Advisory.Cvss("3.1", null, new BigDecimal("9.8"), "CRITICAL"),
                List.of(new AffectedProduct(
                        "cpe:2.3:a:acme:" + packageName + ":*:*:*:*:*:*:*:*",
                        "a", "acme", packageName, null, null, null, null, null)));
    }
}
