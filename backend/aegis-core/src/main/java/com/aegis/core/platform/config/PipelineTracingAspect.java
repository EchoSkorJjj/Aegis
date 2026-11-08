package com.aegis.core.platform.config;

import java.util.UUID;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Adds child spans without adding high-cardinality metric labels or recording business content. */
@Aspect
@Component
public class PipelineTracingAspect {
    private static final Logger log = LoggerFactory.getLogger(PipelineTracingAspect.class);
    private final Tracer tracer;

    public PipelineTracingAspect(Tracer tracer) {
        this.tracer = tracer;
    }

    @Around("@annotation(pipeline)")
    public Object trace(ProceedingJoinPoint invocation, PipelineSpan pipeline) throws Throwable {
        Span span = tracer.nextSpan().name(pipeline.value()).start();
        String signal = uuidArgument(invocation.getArgs(), pipeline.signalArgument());
        String service = uuidArgument(invocation.getArgs(), pipeline.serviceArgument());
        if (signal != null) {
            span.tag("changeSignalId", signal);
        }
        if (service != null) {
            span.tag("serviceId", service);
        }
        long started = System.nanoTime();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            log.info("pipeline stage={} outcome=started changeSignalId={} serviceId={}",
                    pipeline.value(), signal, service);
            try {
                Object result = invocation.proceed();
                log.info("pipeline stage={} outcome=success changeSignalId={} serviceId={} durationMs={}",
                        pipeline.value(), signal, service, (System.nanoTime() - started) / 1_000_000);
                return result;
            } catch (Throwable failure) {
                // Exception messages can contain feed text, repository URLs or credentials. Record only the type.
                span.tag("error", failure.getClass().getSimpleName());
                log.warn("pipeline stage={} outcome=failure changeSignalId={} serviceId={} errorType={} durationMs={}",
                        pipeline.value(), signal, service, failure.getClass().getSimpleName(),
                        (System.nanoTime() - started) / 1_000_000);
                throw failure;
            }
        } finally {
            span.end();
        }
    }

    private static String uuidArgument(Object[] arguments, int index) {
        return index >= 0 && index < arguments.length && arguments[index] instanceof UUID id
                ? id.toString() : null;
    }
}
