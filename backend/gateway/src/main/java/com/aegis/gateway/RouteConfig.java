package com.aegis.gateway;

import org.springframework.beans.factory.annotation.Value;
import static org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions.uri;
import static org.springframework.cloud.gateway.server.mvc.handler.GatewayRouterFunctions.route;
import static org.springframework.cloud.gateway.server.mvc.handler.HandlerFunctions.http;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import static org.springframework.web.servlet.function.RequestPredicates.path;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

/**
 * Routing table for the WebMVC gateway, expressed as functional {@link RouterFunction}s. Each route
 * matches a path, then {@code http()} forwards it to the downstream URI set by the {@code before}
 * filter, unchanged. The security rules that gate these paths live in {@link SecurityConfig}.
 *
 * <ul>
 *   <li>{@code /auth/**}, {@code /oauth2/**}, {@code /users/**} &rarr; identity-service
 *       (login, tokens, JWKS, and the user profile/admin API)</li>
 *   <li>{@code /api/**} &rarr; aegis-core (domain + pipeline)</li>
 * </ul>
 */
@Configuration
public class RouteConfig {

    @Bean
    public RouterFunction<ServerResponse> identityRoutes(
            @Value("${gateway.identity-uri:http://localhost:8081}") String identityUri) {
        return route("identity")
                .route(path("/auth/**"), http())
                .route(path("/oauth2/**"), http())
                // The user profile/admin API (e.g. /users/me, /users/{id}) — protected by the
                // gateway (see SecurityConfig: anyRequest authenticated); identity-service applies
                // the per-endpoint role rules.
                .route(path("/users/**"), http())
                .before(uri(identityUri))
                .build();
    }

    @Bean
    public RouterFunction<ServerResponse> coreRoutes(
            @Value("${gateway.core-uri:http://localhost:8082}") String coreUri) {
        return route("aegis-core")
                .route(path("/api/**"), http())
                .before(uri(coreUri))
                .build();
    }
}
