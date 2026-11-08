package com.aegis.core.platform.config;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import brave.Tracing;
import brave.context.slf4j.MDCScopeDecorator;
import brave.handler.MutableSpan;
import brave.handler.SpanHandler;
import brave.propagation.ThreadLocalCurrentTraceContext;
import brave.propagation.TraceContext;
import brave.propagation.tracecontext.TraceContextPropagation;
import brave.sampler.Sampler;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.aegis.core.ai.AiClientConfig;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.brave.bridge.BraveCurrentTraceContext;
import io.micrometer.tracing.brave.bridge.BravePropagator;
import io.micrometer.tracing.brave.bridge.BraveTracer;
import io.micrometer.tracing.handler.PropagatingSenderTracingObservationHandler;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class PipelineTracingTest {
    @Test
    void aiClientPropagatesThePipelineTraceAsW3c() {
        try (var tracing = Tracing.newBuilder().traceId128Bit(true)
                .propagationFactory(TraceContextPropagation.newFactoryBuilder().build())
                .sampler(Sampler.ALWAYS_SAMPLE).build()) {
            Tracer tracer = new BraveTracer(tracing.tracer(),
                    new BraveCurrentTraceContext(tracing.currentTraceContext()));
            var observations = ObservationRegistry.create();
            observations.observationConfig().observationHandler(
                    new PropagatingSenderTracingObservationHandler<>(tracer, new BravePropagator(tracing)));
            var client = new AiClientConfig().aiRestClient(observations, "http://ai-service:8083", "test-token");
            AtomicReference<String> traceparent = new AtomicReference<>();
            var builder = client.mutate();
            var server = MockRestServiceServer.bindTo(builder).build();
            server.expect(requestTo("http://ai-service:8083/v1/ping"))
                    .andExpect(request -> traceparent.set(request.getHeaders().getFirst("traceparent")))
                    .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
            var parent = tracer.nextSpan().name("aegis.draft.ticket").start();
            try (var ignored = tracer.withSpan(parent)) {
                builder.build().get().uri("/v1/ping").retrieve().body(String.class);
            } finally {
                parent.end();
            }
            server.verify();
            assertThat(traceparent.get()).matches("00-" + parent.context().traceId() + "-[0-9a-f]{16}-01");
            assertThat(traceparent.get().split("-")[2]).isNotEqualTo(parent.context().spanId());
        }
    }

    @Test
    void nestedStagesShareATraceRestoreTheirParentAndDoNotLeakExceptionText() {
        List<MutableSpan> spans = new ArrayList<>();
        var current = ThreadLocalCurrentTraceContext.newBuilder()
                .addScopeDecorator(MDCScopeDecorator.get()).build();
        try (var tracing = Tracing.newBuilder().currentTraceContext(current).traceId128Bit(true)
                .sampler(Sampler.ALWAYS_SAMPLE).addSpanHandler(new SpanHandler() {
                    @Override
                    public boolean end(TraceContext context, MutableSpan span, Cause cause) {
                        spans.add(span);
                        return true;
                    }
                }).build()) {
            Tracer tracer = new BraveTracer(tracing.tracer(), new BraveCurrentTraceContext(current));
            var aspect = new PipelineTracingAspect(tracer);
            Stages nested = proxy(new Stages(tracer, null), aspect);
            Stages stages = proxy(new Stages(tracer, nested), aspect);
            UUID signal = UUID.randomUUID();
            var logger = (Logger) LoggerFactory.getLogger(PipelineTracingAspect.class);
            var logs = new ListAppender<ILoggingEvent>() {
                @Override
                protected void append(ILoggingEvent event) {
                    event.prepareForDeferredProcessing();
                    super.append(event);
                }
            };
            logs.start();
            logger.addAppender(logs);
            var parent = tracer.nextSpan().name("http.request").start();
            try (var ignored = tracer.withSpan(parent)) {
                String parentId = MDC.get("spanId");
                assertThat(stages.draft(signal)).isEqualTo(parent.context().traceId());
                assertThat(MDC.get("spanId")).isEqualTo(parentId);
                assertThatThrownBy(() -> stages.fail(signal)).isInstanceOf(IllegalStateException.class)
                        .hasMessage("private prompt text");
                assertThat(MDC.get("spanId")).isEqualTo(parentId);
            } finally {
                parent.end();
                logger.detachAppender(logs);
            }
            assertThat(tracer.currentSpan()).isNull();
            assertThat(MDC.get("traceId")).isNull();
            assertThat(spans).extracting(MutableSpan::traceId).containsOnly(parent.context().traceId());
            MutableSpan draft = spans.stream().filter(s -> s.name().equals("aegis.draft.ticket")).findFirst().orElseThrow();
            MutableSpan score = spans.stream().filter(s -> s.name().equals("aegis.score.change")).findFirst().orElseThrow();
            assertThat(score.parentId()).isEqualTo(draft.id());
            assertThat(score.tags()).containsEntry("changeSignalId", signal.toString());
            assertThat(spans.stream().filter(s -> s.name().equals("aegis.draft.fail")).findFirst().orElseThrow().tags())
                    .containsEntry("error", "IllegalStateException");
            assertThat(logs.list).allSatisfy(event -> {
                assertThat(event.getMDCPropertyMap()).containsEntry("traceId", parent.context().traceId());
                assertThat(event.getMDCPropertyMap().get("spanId")).isNotBlank();
                assertThat(event.getFormattedMessage()).doesNotContain("private prompt text");
            });
            assertThat(logs.list).anySatisfy(event -> assertThat(event.getFormattedMessage()).contains("outcome=failure"));
        }
    }

    private static Stages proxy(Stages target, PipelineTracingAspect aspect) {
        var factory = new AspectJProxyFactory(target);
        factory.addAspect(aspect);
        return factory.getProxy();
    }

    static class Stages {
        private final Tracer tracer;
        private final Stages nested;

        Stages(Tracer tracer, Stages nested) {
            this.tracer = tracer;
            this.nested = nested;
        }

        @PipelineSpan(value = "aegis.draft.ticket", signalArgument = 0)
        public String draft(UUID signal) {
            return nested.score(signal);
        }

        @PipelineSpan(value = "aegis.score.change", signalArgument = 0)
        public String score(UUID signal) {
            return tracer.currentSpan().context().traceId();
        }

        @PipelineSpan(value = "aegis.draft.fail", signalArgument = 0)
        public void fail(UUID signal) {
            throw new IllegalStateException("private prompt text");
        }
    }
}
