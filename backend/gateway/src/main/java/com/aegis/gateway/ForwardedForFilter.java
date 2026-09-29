package com.aegis.gateway;

import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.server.mvc.filter.HttpHeadersFilter;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.function.ServerRequest;

/**
 * Carries the browser's address to the services behind the gateway. identity-service's /auth rate limit and
 * audit log read the first X-Forwarded-For hop, which the frontend's BFF sets to the browser's address. The
 * gateway passes that header on, with the caller's own address appended, only when the caller matches
 * {@code gateway.trusted-proxies} (the BFF's network); anyone else's X-Forwarded-For becomes just their own
 * address, so nobody can claim someone else's.
 *
 * <p>Spring Cloud Gateway's own X-Forwarded filter can't express this: it keeps an entry only when the entry
 * itself is a trusted proxy, which drops the browser's public address. So it stays off, its default then
 * strips every X-Forwarded-* header (order 0), and this runs after it.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
class ForwardedForFilter implements HttpHeadersFilter.RequestHttpHeadersFilter {

    private static final String X_FORWARDED_FOR = "X-Forwarded-For";

    private final Pattern trustedProxies;

    ForwardedForFilter(@Value("${gateway.trusted-proxies}") String trustedProxies) {
        this.trustedProxies = Pattern.compile(trustedProxies);
    }

    @Override
    public HttpHeaders apply(HttpHeaders input, ServerRequest request) {
        HttpServletRequest servlet = request.servletRequest();
        String caller = servlet.getRemoteAddr();
        HttpHeaders headers = new HttpHeaders();
        input.headerSet().forEach(header -> headers.addAll(header.getKey(), header.getValue()));
        headers.remove(X_FORWARDED_FOR);
        if (caller == null) {
            return headers;
        }
        List<String> claimed = Collections.list(servlet.getHeaders(X_FORWARDED_FOR));
        if (trustedProxies.matcher(caller).matches() && !claimed.isEmpty()) {
            headers.set(X_FORWARDED_FOR, String.join(", ", claimed) + ", " + caller);
        } else {
            headers.set(X_FORWARDED_FOR, caller);
        }
        return headers;
    }
}
