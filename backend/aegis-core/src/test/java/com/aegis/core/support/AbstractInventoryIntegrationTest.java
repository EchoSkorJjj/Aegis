package com.aegis.core.support;

import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Base for org-scoped inventory integration tests. Every test runs inside a fixed demo-org context
 * (see {@link OrgContextExtension}) so its direct store/service setup and its MockMvc requests share a
 * tenant — the inventory is multi-tenant (ADR §6), so a test without an org would be rejected. Suites
 * that assert cross-tenant isolation drive the org themselves and extend {@link AbstractIntegrationTest}.
 */
@ExtendWith(OrgContextExtension.class)
public abstract class AbstractInventoryIntegrationTest extends AbstractIntegrationTest {
}
