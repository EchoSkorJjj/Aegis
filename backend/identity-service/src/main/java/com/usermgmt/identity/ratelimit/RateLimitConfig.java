package com.usermgmt.identity.ratelimit;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
public class RateLimitConfig {

    /**
     * Registers the rate limiter only for {@code /auth/*} and runs it ahead of the Spring Security
     * chain, so a flood is rejected as cheaply as possible. The filter itself is a no-op when
     * {@code identity.rate-limit.enabled} is false.
     */
    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilter(
            @Value("${identity.rate-limit.enabled:true}") boolean enabled,
            @Value("${identity.rate-limit.capacity:20}") int capacity,
            @Value("${identity.rate-limit.refill-period-seconds:60}") int refillPeriodSeconds,
            MeterRegistry meterRegistry) {
        FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>(
                new RateLimitFilter(enabled, capacity, refillPeriodSeconds, meterRegistry));
        registration.addUrlPatterns("/auth/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
