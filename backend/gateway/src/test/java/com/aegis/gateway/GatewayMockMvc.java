package com.aegis.gateway;

import java.io.IOException;
import java.io.UncheckedIOException;

import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * MockMvc for tests whose calls the gateway relays to a stub, with each request as a servlet container presents it.
 * Tomcat reports the body of a request that has none as finished, so the gateway relays the request without one.
 * MockMvc reports it unfinished until it is read, so the gateway would stream an empty body instead, and the JDK
 * HttpClient can fail that with a NullPointerException in {@code Http1Exchange.requestMoreBody}: it subscribes to a
 * streamed body before it stores the subscriber, and an empty body can complete in between (JDK 21 and 25 alike;
 * seen in CI). The BFF always sends a body's length, so production never streams an empty one.
 */
final class GatewayMockMvc {

    private GatewayMockMvc() {
    }

    static MockMvc of(WebApplicationContext context) {
        return MockMvcBuilders.webAppContextSetup(context)
                .defaultRequest(get("/").with(bodiless()))
                .apply(springSecurity())
                .build();
    }

    /** A request without content gets its (empty) body read to the end, which marks it finished. */
    private static RequestPostProcessor bodiless() {
        return request -> {
            if (request.getContentLength() < 1) {
                try {
                    request.getInputStream().readAllBytes();
                } catch (IOException ex) {
                    throw new UncheckedIOException(ex);
                }
            }
            return request;
        };
    }
}
