package com.aegis.gateway;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Edge authentication and <b>coarse</b> authorisation. The gateway validates the JWT signature and
 * claims against identity-service's JWKS (Boot builds a lazy decoder from
 * {@code spring.security.oauth2.resourceserver.jwt.jwk-set-uri}) and enforces route-level rules.
 * Owner-scoped, fine-grained decisions are made downstream in each service — the gateway cannot know
 * whether a user owns a given finding or service.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // identity-service's own public endpoints (login, register, refresh, JWKS)
                        // must pass through without a token.
                        .requestMatchers("/auth/**", "/oauth2/**").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/info", "/actuator/prometheus").permitAll()
                        // Coarse route rule: the platform-admin area needs PLATFORM_ADMIN (the
                        // org-less operator; the retired ADMIN role migrated to it — ADR 0001).
                        // Everything else on /api/** just needs a valid token; the service applies
                        // the owner-scoped, org-scoped rules.
                        .requestMatchers("/api/admin/**").hasRole("PLATFORM_ADMIN")
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())));
        return http.build();
    }

    /** Maps the JWT "roles" claim to ROLE_* authorities (same convention as the services). */
    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles");
        authorities.setAuthorityPrefix("ROLE_");

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }
}
