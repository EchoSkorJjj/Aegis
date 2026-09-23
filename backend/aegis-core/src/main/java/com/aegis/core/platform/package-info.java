/**
 * Platform — the cross-cutting foundation every feature module builds on, gathered in one place so the
 * top level reads as capabilities (inventory, ingestion, advisories, …) rather than plumbing.
 *
 * <ul>
 *   <li>{@link com.aegis.core.platform.common} — the uniform error contract ({@code ApiError} +
 *       {@code ApiExceptions} surfaced by the {@code GlobalExceptionHandler}).</li>
 *   <li>{@link com.aegis.core.platform.config} — HTTP security (resource-server JWT validation) and
 *       the OpenAPI wiring.</li>
 *   <li>{@link com.aegis.core.platform.tenant} — {@code CurrentOrg}, the active-organization (tenant)
 *       resolved from the JWT {@code org} claim that every org-scoped read/write goes through.</li>
 *   <li>{@link com.aegis.core.platform.web} — service-level endpoints with no domain of their own
 *       (health/ping).</li>
 * </ul>
 *
 * <p>Dependencies point one way: features depend on platform, never the reverse — platform must not
 * import a feature module, or the foundation would depend on the things built on top of it (enforced
 * by {@code PackageStructureArchTest}).
 */
package com.aegis.core.platform;
