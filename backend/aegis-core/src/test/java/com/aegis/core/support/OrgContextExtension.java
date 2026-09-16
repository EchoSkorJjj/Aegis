package com.aegis.core.support;

import java.lang.reflect.Method;
import java.util.UUID;

import com.aegis.core.inventory.demo.DemoEstateSeeder;
import com.aegis.core.tenant.CurrentOrg;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.InvocationInterceptor;
import org.junit.jupiter.api.extension.ReflectiveInvocationContext;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/**
 * Runs each org-scoped integration test — its {@code @BeforeEach} and {@code @Test} — inside a fixed
 * org context, so both direct store/service calls and same-thread MockMvc requests resolve a tenant,
 * mirroring how a real request carries an {@code org} claim. Uses the demo org so a test may also
 * exercise the seeded estate. Tenant-isolation tests, which need more than one org, drive the org
 * themselves and do not use this.
 */
public class OrgContextExtension implements InvocationInterceptor {

    private static final UUID TEST_ORG = DemoEstateSeeder.DEMO_ORG_ID;

    @Override
    public void interceptBeforeEachMethod(
            Invocation<Void> invocation,
            ReflectiveInvocationContext<Method> invocationContext,
            ExtensionContext extensionContext)
            throws Throwable {
        proceedInOrg(invocation, extensionContext);
    }

    @Override
    public void interceptTestMethod(
            Invocation<Void> invocation,
            ReflectiveInvocationContext<Method> invocationContext,
            ExtensionContext extensionContext)
            throws Throwable {
        proceedInOrg(invocation, extensionContext);
    }

    private void proceedInOrg(Invocation<Void> invocation, ExtensionContext extensionContext) throws Throwable {
        CurrentOrg currentOrg = SpringExtension.getApplicationContext(extensionContext).getBean(CurrentOrg.class);
        Throwable[] thrown = new Throwable[1];
        currentOrg.runAs(TEST_ORG, () -> {
            try {
                invocation.proceed();
            } catch (Throwable t) {
                thrown[0] = t;
            }
        });
        if (thrown[0] != null) {
            throw thrown[0];
        }
    }
}
