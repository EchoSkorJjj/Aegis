package com.aegis.core.support;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.aegis.core.inventory.demo.DemoEstateSeeder;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

/** Requests started together on threads of their own: what two people deciding at the same moment each get. */
public final class Race {

    private Race() {
    }

    /** The calls start together; their responses come back in the order given. */
    @SafeVarargs
    public static List<MockHttpServletResponse> race(Callable<MockHttpServletResponse>... calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.length);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<MockHttpServletResponse>> futures = new ArrayList<>();
            for (Callable<MockHttpServletResponse> call : calls) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return call.call();
                }));
            }
            start.countDown();
            List<MockHttpServletResponse> responses = new ArrayList<>();
            for (Future<MockHttpServletResponse> future : futures) {
                responses.add(future.get(30, TimeUnit.SECONDS));
            }
            return responses;
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * A caller with one role whose token names the demo organization: a request on a race's own thread has no test
     * org context to fall back on ({@link OrgContextExtension} binds the test's thread only).
     */
    public static RequestPostProcessor inDemoOrg(String role, UUID userId) {
        return jwt().jwt(token -> token.subject(userId.toString())
                        .claim("org", DemoEstateSeeder.DEMO_ORG_ID.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }
}
