package com.aegis.core.support;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.aegis.core.platform.common.ApiExceptions;
import com.aegis.core.platform.identity.IdentityInternalApi;

/**
 * Test stand-in for identity-service's internal API: records every owner email instead of sending it, and can be
 * told to fail the next emails or to treat a member as unreachable. Shared by the whole suite, so a test looks
 * only at the emails to its own (random) users.
 */
public class FakeIdentityInternalApi implements IdentityInternalApi {

    /** One email: to whom, in which organization, about which items. */
    public record Sent(UUID orgId, UUID userId, List<OwnerEmailItem> items) {
    }

    private final List<Sent> sent = Collections.synchronizedList(new ArrayList<>());
    private final Set<UUID> unreachable = ConcurrentHashMap.newKeySet();
    private final Set<UUID> failing = ConcurrentHashMap.newKeySet();
    private final AtomicInteger failures = new AtomicInteger();
    private volatile List<UUID> organizations = List.of();

    @Override
    public List<UUID> organizationIds() {
        return organizations;
    }

    @Override
    public EmailOutcome emailOwner(UUID orgId, UUID userId, List<OwnerEmailItem> items) {
        if (failing.contains(userId) && failures.getAndDecrement() > 0) {
            throw new ApiExceptions.UpstreamUnavailable("identity-service did not send the email");
        }
        if (unreachable.contains(userId)) {
            return EmailOutcome.UNREACHABLE;
        }
        sent.add(new Sent(orgId, userId, List.copyOf(items)));
        return EmailOutcome.SENT;
    }

    /** The emails sent to this user, oldest first. */
    public List<Sent> sentTo(UUID userId) {
        synchronized (sent) {
            return sent.stream().filter(email -> email.userId().equals(userId)).toList();
        }
    }

    public void organizations(List<UUID> ids) {
        organizations = List.copyOf(ids);
    }

    /** identity-service won't email this member (they left the organization, say). */
    public void unreachable(UUID userId) {
        unreachable.add(userId);
    }

    /** The next {@code times} emails to this user fail. */
    public void failNext(UUID userId, int times) {
        failing.add(userId);
        failures.set(times);
    }
}
