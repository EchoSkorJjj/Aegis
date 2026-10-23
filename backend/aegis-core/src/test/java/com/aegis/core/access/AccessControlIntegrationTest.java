package com.aegis.core.access;

import java.lang.reflect.Parameter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.aegis.core.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ValueConstants;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * Access control (CS203-147): every aegis-core endpoint answers each caller as {@code security/access-control.md}
 * says — 401 without a token, 403 for a role that may not call it, its own answer for one that may — and every
 * endpoint is in that table. Calls go through the real filter chain and method security with a decoded token of one
 * role, random ids in the path (so a service owner owns none of them) and a valid body, so the request reaches the
 * authorization check instead of stopping at validation. Runs in a fresh organization.
 */
class AccessControlIntegrationTest extends AbstractIntegrationTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final Pattern VARIABLE = Pattern.compile("\\{([^}]+)}");

    /**
     * A valid body for each endpoint that requires one, by {@code METHOD path}. Ids are random, so an allowed call
     * ends at "not found" or a conflict rather than changing anything that exists.
     */
    private static final Map<String, String> BODIES = Map.ofEntries(
            Map.entry("POST /api/inventory/services", "{\"name\":\"Access check\",\"criticality\":\"LOW\"}"),
            Map.entry("PUT /api/inventory/services/{id}", "{\"name\":\"Access check\",\"criticality\":\"LOW\"}"),
            Map.entry("POST /api/inventory/components", "{\"ecosystem\":\"npm\",\"name\":\"access-check\",\"version\":\"1.0.0\"}"),
            Map.entry("PUT /api/inventory/components/{id}", "{\"ecosystem\":\"npm\",\"name\":\"access-check\",\"version\":\"1.0.0\"}"),
            Map.entry("POST /api/inventory/vendors", "{\"name\":\"Access check\"}"),
            Map.entry("PUT /api/inventory/vendors/{id}", "{\"name\":\"Access check\"}"),
            Map.entry("POST /api/inventory/services/{serviceId}/owners", "{\"userId\":\"" + UUID.randomUUID() + "\"}"),
            Map.entry("PUT /api/inventory/services/{serviceId}/repository", "{\"url\":\"https://github.com/acme/access-check\"}"),
            Map.entry("POST /api/inventory/services/{serviceId}/sbom", "{\"bomFormat\":\"CycloneDX\",\"specVersion\":\"1.5\",\"components\":[]}"),
            Map.entry("POST /api/actions/risk-tickets", ids("changeSignalId", "serviceId")),
            Map.entry("POST /api/actions/risk-tickets/{id}/dismiss", "{\"reason\":\"Access check\"}"),
            Map.entry("POST /api/actions/risk-tickets/{id}/edit",
                    "{\"version\":0,\"summary\":\"s\",\"risk\":\"r\",\"impact\":\"i\",\"actions\":[\"a\"]}"),
            Map.entry("POST /api/actions/risk-tickets/{id}/override", "{\"version\":0,\"priority\":\"LOW\",\"reason\":\"Access check\"}"),
            Map.entry("POST /api/actions/risk-tickets/{id}/override/withdraw", "{\"version\":0}"),
            Map.entry("POST /api/actions/upgrade-proposals/{id}/dismiss", "{\"reason\":\"Access check\"}"),
            Map.entry("POST /api/actions/regulator-memos", ids("changeSignalId")),
            Map.entry("POST /api/actions/regulator-memos/{id}/dismiss", "{\"reason\":\"Access check\"}"),
            Map.entry("POST /api/actions/reachability-assessments", ids("serviceId", "changeSignalId")),
            Map.entry("POST /api/integrations/github/connections", "{\"code\":\"access-check\",\"state\":\"access-check\"}"),
            Map.entry("POST /api/integrations/jira/connections", "{\"code\":\"access-check\",\"state\":\"access-check\"}"),
            Map.entry("PUT /api/integrations/jira/settings", "{\"cloudId\":\"c\",\"projectKey\":\"ACC\",\"issueType\":\"Task\"}"));

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mappings;

    @Test
    void everyEndpointIsInTheAccessTableAndEveryRowIsAnEndpoint() {
        Set<String> endpoints = new TreeSet<>(endpoints().keySet());
        Set<String> rows = new TreeSet<>();
        AccessTable.section("aegis-core").forEach(row -> rows.add(row.key()));

        assertThat(difference(endpoints, rows)).as("endpoints missing from security/access-control.md").isEmpty();
        assertThat(difference(rows, endpoints)).as("rows of security/access-control.md that match no endpoint").isEmpty();
    }

    @TestFactory
    Stream<DynamicTest> everyEndpointAnswersEachCallerAsTheAccessTableSays() {
        Map<String, HandlerMethod> endpoints = endpoints();
        return AccessTable.section("aegis-core").stream()
                .filter(row -> endpoints.containsKey(row.key()))
                .flatMap(row -> AccessTable.CALLERS.stream().map(caller -> DynamicTest.dynamicTest(
                        row.key() + " as " + caller + " → " + row.access().get(caller),
                        () -> check(row, caller, endpoints.get(row.key())))));
    }

    private void check(AccessTable.Row row, String caller, HandlerMethod handler) throws Exception {
        String expected = row.access().get(caller);
        MockHttpServletRequestBuilder call = call(row, handler);
        if (!caller.equals("Anonymous")) {
            call.with(token(caller));
        }
        int status = mvc.perform(call).andReturn().getResponse().getStatus();
        String what = row.key() + " as " + caller;
        switch (expected) {
            // Allowed: the endpoint's own answer. 502 is one too: an upstream these tests don't run (the ai-service).
            case "yes" -> assertThat(status).as(what + " is allowed, so it reaches the endpoint")
                    .isNotIn(401, 403).matches(answer -> answer < 500 || answer == 502, "not a server error");
            case "no" -> assertThat(status).as(what + " is refused").isEqualTo(caller.equals("Anonymous") ? 401 : 403);
            // Random ids: the caller owns none of them, so an owner-only endpoint refuses them.
            case "own", "self" -> assertThat(status).as(what + " is refused for what isn't theirs").isEqualTo(403);
            default -> throw new IllegalStateException(what + ": unknown access value \"" + expected + "\"");
        }
    }

    /** The request: random path ids, every required query parameter, and the endpoint's body if it needs one. */
    private static MockHttpServletRequestBuilder call(AccessTable.Row row, HandlerMethod handler) {
        Matcher variables = VARIABLE.matcher(row.path());
        StringBuilder path = new StringBuilder();
        while (variables.find()) {
            variables.appendReplacement(path, Matcher.quoteReplacement(sample(variables.group(1))));
        }
        variables.appendTail(path);
        MockHttpServletRequestBuilder call = request(HttpMethod.valueOf(row.method()), path.toString());
        for (Parameter parameter : handler.getMethod().getParameters()) {
            RequestParam param = parameter.getAnnotation(RequestParam.class);
            if (param != null && param.required() && param.defaultValue().equals(ValueConstants.DEFAULT_NONE)) {
                String name = !param.name().isEmpty() ? param.name() : !param.value().isEmpty() ? param.value()
                        : parameter.getName();
                call.param(name, sample(name, parameter.getType()));
            }
            RequestBody body = parameter.getAnnotation(RequestBody.class);
            if (body != null && body.required()) {
                String json = BODIES.get(row.key());
                if (json == null) {
                    throw new IllegalStateException(row.key() + " needs a body: add a valid one to BODIES");
                }
                call.contentType(MediaType.APPLICATION_JSON).content(json);
            }
        }
        return call;
    }

    private static String sample(String variable) {
        return switch (variable) {
            case "sourceId" -> "nvd";
            case "externalId" -> "CVE-2000-0001";
            case "installationId" -> "1";
            default -> UUID.randomUUID().toString();
        };
    }

    private static String sample(String name, Class<?> type) {
        if (type == UUID.class) {
            return UUID.randomUUID().toString();
        }
        if (type == Instant.class) {
            return Instant.now().toString();
        }
        return name.equals("ecosystem") ? "npm" : "access-check";
    }

    /** One role; the organization roles in this test's organization, a platform admin in none (as in production). */
    private static org.springframework.test.web.servlet.request.RequestPostProcessor token(String role) {
        return jwt().jwt(builder -> {
            builder.subject(UUID.randomUUID().toString());
            if (!role.equals("PLATFORM_ADMIN")) {
                builder.claim("org", ORG.toString());
            }
        }).authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    private static String ids(String... names) {
        List<String> fields = new ArrayList<>();
        for (String name : names) {
            fields.add("\"" + name + "\":\"" + UUID.randomUUID() + "\"");
        }
        return "{" + String.join(",", fields) + "}";
    }

    /** aegis-core's own endpoints, by {@code METHOD path}: the framework's error and API-docs handlers aren't ours. */
    private Map<String, HandlerMethod> endpoints() {
        Map<String, HandlerMethod> endpoints = new java.util.LinkedHashMap<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : mappings.getHandlerMethods().entrySet()) {
            if (!entry.getValue().getBeanType().getPackageName().startsWith("com.aegis.core")) {
                continue;
            }
            RequestMappingInfo info = entry.getKey();
            for (String pattern : info.getPatternValues()) {
                for (var method : info.getMethodsCondition().getMethods()) {
                    endpoints.put(method.name() + " " + pattern, entry.getValue());
                }
            }
        }
        return endpoints;
    }

    private static Set<String> difference(Set<String> from, Set<String> minus) {
        Set<String> left = new TreeSet<>(from);
        left.removeAll(minus);
        return left;
    }
}
