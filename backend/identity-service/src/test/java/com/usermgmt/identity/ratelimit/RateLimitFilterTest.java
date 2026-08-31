package com.usermgmt.identity.ratelimit;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit test of the token-bucket limiter: enforcement, per-IP isolation, and the disabled path. */
class RateLimitFilterTest {

    private final MeterRegistry registry = new SimpleMeterRegistry();

    @Test
    void blocks_once_the_per_ip_burst_is_exhausted() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(true, 3, 60, registry);

        for (int i = 0; i < 3; i++) {
            assertThat(call(filter, "203.0.113.7").getStatus()).isEqualTo(200);
        }

        MockHttpServletResponse blocked = call(filter, "203.0.113.7");
        assertThat(blocked.getStatus()).isEqualTo(429);
        assertThat(blocked.getHeader("Retry-After")).isEqualTo("60");
        assertThat(blocked.getContentAsString()).contains("\"status\":429");
    }

    @Test
    void buckets_are_isolated_per_ip() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(true, 1, 60, registry);

        assertThat(call(filter, "203.0.113.7").getStatus()).isEqualTo(200);
        assertThat(call(filter, "203.0.113.7").getStatus()).isEqualTo(429); // .7 exhausted
        assertThat(call(filter, "203.0.113.8").getStatus()).isEqualTo(200); // .8 unaffected
    }

    @Test
    void only_throttles_post_requests() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(true, 1, 60, registry);

        // A GET never consumes the bucket, so repeated GETs all pass...
        assertThat(call(filter, "GET", "203.0.113.7").getStatus()).isEqualTo(200);
        assertThat(call(filter, "GET", "203.0.113.7").getStatus()).isEqualTo(200);
        // ...and the POST budget is still intact.
        assertThat(call(filter, "203.0.113.7").getStatus()).isEqualTo(200);
    }

    @Test
    void disabled_filter_never_blocks() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(false, 1, 60, registry);

        assertThat(call(filter, "203.0.113.7").getStatus()).isEqualTo(200);
        assertThat(call(filter, "203.0.113.7").getStatus()).isEqualTo(200);
    }

    private MockHttpServletResponse call(RateLimitFilter filter, String ip) throws Exception {
        return call(filter, "POST", ip);
    }

    private MockHttpServletResponse call(RateLimitFilter filter, String method, String ip) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/auth/login");
        request.addHeader("X-Forwarded-For", ip);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }
}
