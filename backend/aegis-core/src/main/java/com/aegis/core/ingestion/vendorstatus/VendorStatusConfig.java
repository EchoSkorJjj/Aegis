package com.aegis.core.ingestion.vendorstatus;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link VendorStatusProperties}. The HTTP client is built by the source itself rather than
 * declared here: every feed connector needs its own {@code FeedHttpClient} with its own timeouts, and
 * several same-typed beans would just need disambiguating.
 */
@Configuration
@EnableConfigurationProperties(VendorStatusProperties.class)
class VendorStatusConfig {}
