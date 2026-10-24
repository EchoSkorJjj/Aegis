package com.aegis.gateway;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Stream;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.PathContainer;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.function.HandlerFunction;
import org.springframework.web.servlet.function.RequestPredicate;
import org.springframework.web.servlet.function.RequestPredicates;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.util.pattern.PathPatternParser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * Access control (CS203-147): the gateway answers each caller as {@code security/access-control.md} says, and
 * routes exactly the paths that table lists. Each call goes through the real filter chain and routes to a stub
 * that says which service it stands for, so "allowed" means the request arrived where the table says.
 */
@SpringBootTest
class GatewayAccessTest {

    private static final HttpServer IDENTITY = stub("identity-service");
    private static final HttpServer CORE = stub("aegis-core");

    @DynamicPropertySource
    static void routeToTheStubs(DynamicPropertyRegistry registry) {
        registry.add("gateway.identity-uri", () -> "http://127.0.0.1:" + IDENTITY.getAddress().getPort());
        registry.add("gateway.core-uri", () -> "http://127.0.0.1:" + CORE.getAddress().getPort());
    }

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @AfterAll
    static void stopStubs() {
        IDENTITY.stop(0);
        CORE.stop(0);
    }

    @Test
    void everyRouteIsInTheAccessTableAndEveryRoutedRowIsARoute() {
        Set<String> routes = new TreeSet<>();
        for (RouterFunction<?> router : context.getBeansOfType(RouterFunction.class).values()) {
            router.accept(new RoutePaths(routes));
        }
        Set<String> rows = new TreeSet<>();
        Set<String> unrouted = new TreeSet<>();
        for (AccessTable.Row row : AccessTable.section("Gateway")) {
            String target = row.access().get("Routed to");
            if (target.startsWith("identity-service") || target.startsWith("aegis-core")) {
                rows.add(row.path());
                // A row may be a rule within a route (/api/admin/** within /api/**): some route must serve it.
                var sample = PathContainer.parsePath(row.path().replace("**", "access-check"));
                if (routes.stream().noneMatch(route -> PathPatternParser.defaultInstance.parse(route).matches(sample))) {
                    unrouted.add(row.path());
                }
            }
        }
        Set<String> missing = new TreeSet<>(routes);
        missing.removeAll(rows);
        assertThat(missing).as("routes missing from security/access-control.md").isEmpty();
        assertThat(unrouted).as("rows of security/access-control.md routed to a service that no route serves").isEmpty();
    }

    @TestFactory
    Stream<DynamicTest> everyPathAnswersEachCallerAsTheAccessTableSays() {
        return AccessTable.section("Gateway").stream().flatMap(row -> methods(row).stream()
                .flatMap(method -> AccessTable.CALLERS.stream().map(caller -> DynamicTest.dynamicTest(
                        method + " " + row.path() + " as " + caller + " → " + row.access().get(caller),
                        () -> check(row, method, caller)))));
    }

    private void check(AccessTable.Row row, HttpMethod method, String caller) throws Exception {
        MockHttpServletRequestBuilder call = request(method, row.path().replace("**", "access-check"));
        if (!caller.equals("Anonymous")) {
            call.with(jwt().authorities(new SimpleGrantedAuthority("ROLE_" + caller)));
        }
        MockHttpServletResponse response = mvc.perform(call).andReturn().getResponse();
        String what = method + " " + row.path() + " as " + caller;
        String target = row.access().get("Routed to");
        switch (row.access().get(caller)) {
            case "no" -> assertThat(response.getStatus()).as(what + " is refused")
                    .isEqualTo(caller.equals("Anonymous") ? 401 : 403);
            case "yes" -> {
                if (target.startsWith("identity-service") || target.startsWith("aegis-core")) {
                    String service = target.startsWith("identity-service") ? "identity-service" : "aegis-core";
                    assertThat(response.getStatus()).as(what + " reaches " + service).isEqualTo(200);
                    assertThat(response.getContentAsString()).as(what + " reaches " + service).isEqualTo(service);
                } else if (target.startsWith("nothing")) {
                    assertThat(response.getStatus()).as(what + " has no route").isEqualTo(404);
                } else {
                    assertThat(response.getStatus()).as(what + " is answered by the gateway").isEqualTo(200);
                }
            }
            default -> throw new IllegalStateException(what + ": unknown access value");
        }
    }

    /** "any" is checked with a read and a write. */
    private static List<HttpMethod> methods(AccessTable.Row row) {
        return row.method().equals("any") ? List.of(HttpMethod.GET, HttpMethod.POST)
                : List.of(HttpMethod.valueOf(row.method()));
    }

    /** Collects the path pattern of every route a router function serves. */
    private record RoutePaths(Set<String> paths) implements RouterFunctions.Visitor {

        @Override
        public void route(RequestPredicate predicate, HandlerFunction<?> handler) {
            predicate.accept(new RequestPredicates.Visitor() {
                @Override public void path(String pattern) {
                    paths.add(pattern);
                }
                @Override public void method(Set<HttpMethod> methods) { }
                @Override public void pathExtension(String extension) { }
                @Override public void header(String name, String value) { }
                @Override public void param(String name, String value) { }
                @Override public void version(String version) { }
                @Override public void startAnd() { }
                @Override public void and() { }
                @Override public void endAnd() { }
                @Override public void startOr() { }
                @Override public void or() { }
                @Override public void endOr() { }
                @Override public void startNegate() { }
                @Override public void endNegate() { }
                @Override public void unknown(RequestPredicate unknown) {
                    // The route builder starts from a predicate that matches nothing, so it routes nothing.
                    if (!unknown.toString().equals("Never")) {
                        throw new IllegalStateException("A route this test can't read: " + unknown);
                    }
                }
            });
        }

        @Override public void startNested(RequestPredicate predicate) { }
        @Override public void endNested(RequestPredicate predicate) { }
        @Override public void resources(Function<ServerRequest, java.util.Optional<Resource>> lookup) { }
        @Override public void attributes(Map<String, Object> attributes) { }
        @Override public void unknown(RouterFunction<?> router) {
            throw new IllegalStateException("A router this test can't read: " + router);
        }
    }

    /** A downstream service that answers every request with its own name. */
    private static HttpServer stub(String name) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> answer(exchange, name));
            server.start();
            return server;
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static void answer(HttpExchange exchange, String name) throws IOException {
        exchange.getRequestBody().readAllBytes();
        byte[] body = name.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/plain");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
