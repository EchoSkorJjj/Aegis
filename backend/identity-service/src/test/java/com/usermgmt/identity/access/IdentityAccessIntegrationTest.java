package com.usermgmt.identity.access;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.usermgmt.identity.org.Organization;
import com.usermgmt.identity.org.OrganizationRepository;
import com.usermgmt.identity.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * Access control (CS203-147): every identity-service endpoint answers each caller as
 * {@code security/access-control.md} says, and every endpoint is in that table. identity-service decides mostly in
 * its filter chains, not in annotations, so each call goes through them for real: anonymously, with a decoded
 * token of one role, and with the internal API's service token. Refused means 401 from the security layer (it
 * names the scheme in {@code WWW-Authenticate}) or 403; anything else is the endpoint's own answer — a 401 for a
 * wrong password, a 400 for an incomplete body, a 404 for a random id.
 */
@TestPropertySource(properties = "identity.internal.service-token=" + IdentityAccessIntegrationTest.SERVICE_TOKEN)
class IdentityAccessIntegrationTest extends AbstractIntegrationTest {

    static final String SERVICE_TOKEN = "access-test-service-token";
    private static final UUID ORG = UUID.randomUUID();
    private static final Pattern VARIABLE = Pattern.compile("\\{([^}]+)}");

    /**
     * A valid body where the decision is made after the body is read (by {@code @PreAuthorize}), by
     * {@code METHOD path}; anywhere else an empty object is enough, as the filter chain decides first.
     */
    private static final Map<String, String> BODIES = Map.of(
            "POST /users", "{\"email\":\"access-" + UUID.randomUUID() + "@example.test\",\"roles\":[\"ANALYST\"]}",
            "PUT /users/{id}", "{\"displayName\":\"Access check\"}",
            "PUT /users/{id}/roles", "{\"roles\":[\"ANALYST\"]}",
            "POST /internal/notifications/owner-email", "{\"orgId\":\"" + ORG + "\",\"userId\":\"" + UUID.randomUUID()
                    + "\",\"deliveryId\":\"" + UUID.randomUUID() + "\",\"items\":[{\"title\":\"Access check\","
                    + "\"serviceName\":\"Payments\",\"path\":\"/findings/access-check\"}]}");

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mappings;

    @Autowired
    private OrganizationRepository organizations;

    /** The organization the tokens name exists, as it would for a real member. */
    @BeforeEach
    void organization() {
        if (!organizations.existsById(ORG)) {
            organizations.save(new Organization(ORG, "Access check " + ORG));
        }
    }

    @Test
    void everyEndpointIsInTheAccessTableAndEveryRowIsAnEndpoint() {
        Set<String> endpoints = new TreeSet<>(endpoints().keySet());
        Set<String> rows = new TreeSet<>();
        AccessTable.section("identity-service").forEach(row -> rows.add(row.key()));

        assertThat(difference(endpoints, rows)).as("endpoints missing from security/access-control.md").isEmpty();
        assertThat(difference(rows, endpoints)).as("rows of security/access-control.md that match no endpoint").isEmpty();
    }

    @TestFactory
    Stream<DynamicTest> everyEndpointAnswersEachCallerAsTheAccessTableSays() {
        Map<String, HandlerMethod> endpoints = endpoints();
        return AccessTable.section("identity-service").stream()
                .filter(row -> endpoints.containsKey(row.key()))
                .flatMap(row -> Stream.concat(
                        AccessTable.CALLERS.stream().map(caller -> DynamicTest.dynamicTest(
                                row.key() + " as " + caller + " → " + row.access().get(caller),
                                () -> check(row, caller))),
                        Stream.of(DynamicTest.dynamicTest(
                                row.key() + " with the service token → " + row.access().get("Service token"),
                                () -> checkServiceToken(row)))));
    }

    private void check(AccessTable.Row row, String caller) throws Exception {
        String expected = row.access().get(caller);
        String what = row.key() + " as " + caller;
        UUID subject = UUID.randomUUID();
        switch (expected) {
            case "yes" -> assertAllowed(what, perform(row, caller, subject, UUID.randomUUID()));
            case "no" -> assertThat(perform(row, caller, subject, UUID.randomUUID()).getStatus()).as(what + " is refused")
                    .isEqualTo(caller.equals("Anonymous") ? 401 : 403);
            case "self" -> {
                assertThat(perform(row, caller, subject, UUID.randomUUID()).getStatus())
                        .as(what + " is refused for someone else's account").isEqualTo(403);
                assertAllowed(what + " on their own account", perform(row, caller, subject, subject));
            }
            default -> throw new IllegalStateException(what + ": unknown access value \"" + expected + "\"");
        }
    }

    /** The internal API's token opens the internal API, and nothing else: elsewhere it is no token at all. */
    private void checkServiceToken(AccessTable.Row row) throws Exception {
        MockHttpServletResponse response = mvc.perform(call(row, UUID.randomUUID())
                .header("X-Service-Token", SERVICE_TOKEN)).andReturn().getResponse();
        String what = row.key() + " with the service token";
        if (row.access().get("Service token").equals("yes")) {
            assertAllowed(what, response);
        } else if (row.access().get("Anonymous").equals("no")) {
            assertThat(response.getStatus()).as(what + " is refused").isEqualTo(401);
        }
    }

    private MockHttpServletResponse perform(AccessTable.Row row, String caller, UUID subject, UUID pathId)
            throws Exception {
        MockHttpServletRequestBuilder call = call(row, pathId);
        if (!caller.equals("Anonymous")) {
            call.with(token(caller, subject));
        }
        return mvc.perform(call).andReturn().getResponse();
    }

    private static void assertAllowed(String what, MockHttpServletResponse response) {
        boolean refusedBySecurity = response.getStatus() == 401 && response.getHeader(HttpHeaders.WWW_AUTHENTICATE) != null;
        assertThat(refusedBySecurity).as(what + " is allowed, but security answered 401").isFalse();
        assertThat(response.getStatus()).as(what + " is allowed, so it reaches the endpoint").isNotEqualTo(403)
                .isLessThan(500);
    }

    /** The request: {@code pathId} for every path variable, and a body for anything that reads one. */
    private static MockHttpServletRequestBuilder call(AccessTable.Row row, UUID pathId) {
        Matcher variables = VARIABLE.matcher(row.path());
        String path = variables.replaceAll(pathId.toString());
        MockHttpServletRequestBuilder call = request(HttpMethod.valueOf(row.method()), path);
        if (!row.method().equals("GET") && !row.method().equals("DELETE")) {
            call.contentType(MediaType.APPLICATION_JSON).content(BODIES.getOrDefault(row.key(), "{}"));
        }
        return call;
    }

    /** One role; the organization roles in this test's organization, a platform admin in none (as in production). */
    private static RequestPostProcessor token(String role, UUID subject) {
        return jwt().jwt(builder -> {
            builder.subject(subject.toString()).claim("roles", java.util.List.of(role));
            if (!role.equals("PLATFORM_ADMIN")) {
                builder.claim("org", ORG.toString());
            }
        }).authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    /** identity-service's own endpoints, by {@code METHOD path}. */
    private Map<String, HandlerMethod> endpoints() {
        Map<String, HandlerMethod> endpoints = new LinkedHashMap<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : mappings.getHandlerMethods().entrySet()) {
            if (!entry.getValue().getBeanType().getPackageName().startsWith("com.usermgmt.identity")) {
                continue;
            }
            for (String pattern : entry.getKey().getPatternValues()) {
                for (var method : entry.getKey().getMethodsCondition().getMethods()) {
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
