package com.aegis.core.platform.config;

import io.micrometer.core.aop.TimedAspect;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

/** Applies histogram timers to public pipeline operations, including calls made by other services. */
@Configuration(proxyBeanMethods = false)
@EnableAspectJAutoProxy(proxyTargetClass = true)
public class PipelineMetricsConfig {

    @Bean
    TimedAspect pipelineTimedAspect(MeterRegistry registry) {
        return new TimedAspect(registry);
    }
}
