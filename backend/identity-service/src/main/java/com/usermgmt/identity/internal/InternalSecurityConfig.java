package com.usermgmt.identity.internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The internal API ({@code /internal/**}) that aegis-core calls without a user — its scheduled owner
 * notifications. It has its own filter chain, ahead of the user-facing one: no JWT, only the shared service
 * token in {@code X-Service-Token} ({@code identity.internal.service-token}), compared in constant time. With no
 * token configured, every request is refused, so the API is off by default. The gateway never routes
 * {@code /internal/**}; only services on the internal network reach it.
 */
@Configuration
public class InternalSecurityConfig {

    static final String HEADER = "X-Service-Token";
    static final String ROLE = "INTERNAL_SERVICE";

    @Bean
    @Order(1)
    public SecurityFilterChain internalFilterChain(
            HttpSecurity http, @Value("${identity.internal.service-token:}") String serviceToken) throws Exception {
        http
                .securityMatcher("/internal/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(new ServiceTokenFilter(serviceToken), UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth.anyRequest().hasRole(ROLE))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)));
        return http.build();
    }

    /** Authenticates the caller as the internal service when it presents the configured token. */
    static final class ServiceTokenFilter extends OncePerRequestFilter {

        private final byte[] expected;

        ServiceTokenFilter(String serviceToken) {
            this.expected = serviceToken == null || serviceToken.isBlank()
                    ? null
                    : serviceToken.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            String presented = request.getHeader(HEADER);
            if (expected != null && presented != null
                    && MessageDigest.isEqual(expected, presented.getBytes(StandardCharsets.UTF_8))) {
                SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                        "aegis-core", null, List.of(new SimpleGrantedAuthority("ROLE_" + ROLE))));
            }
            chain.doFilter(request, response);
        }
    }
}
