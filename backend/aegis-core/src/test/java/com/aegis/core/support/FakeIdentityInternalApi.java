package com.aegis.core.support;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.aegis.core.platform.common.ApiExceptions;
import com.aegis.core.platform.identity.IdentityInternalApi;

/**
 * Test stand-in for identity-service's internal API: records every owner email instead of sending it, and, as
 * identity-service does, sends each delivery id once, answering a repeat as sent. It can be told to fail the next
 * emails — before sending them, or after (a timeout once identity-service has sent it) — to treat a member as
 * unreachable, or to refuse their emails. Shared by the whole suite, so a test looks only at the emails to its own
 * (random) users.
 */
public class FakeIdentityInternalApi implements IdentityInternalApi {

    /** One email: to whom, in which organization, under which delivery id, about which items. */
    public record Sent(UUID orgId, UUID userId, UUID deliveryId, List<OwnerEmailItem> items) {
    }

    private final List<Sent> sent = Collections.synchronizedList(new ArrayList<>());
    private final Set<UUID> delivered = ConcurrentHashMap.newKeySet();
    private final Set<UUID> unreachable = ConcurrentHashMap.newKeySet();
    private final Set<UUID> rejected = ConcurrentHashMap.newKeySet();
    private final Map<UUID, AtomicInteger> failBefore = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicInteger> failAfter = new ConcurrentHashMap<>();
    private volatile List<UUID> organizations = List.of();

    @Override
    public List<UUID> organizationIds() {
        return organizations;
    }

    @Override
    public EmailOutcome emailOwner(UUID orgId, UUID userId, UUID deliveryId, List<OwnerEmailItem> items) {
        if (delivered.contains(deliveryId)) {
            return EmailOutcome.SENT;
        }
        if (failBefore.getOrDefault(userId, new AtomicInteger()).getAndDecrement() > 0) {
            throw new ApiExceptions.UpstreamUnavailable("identity-service did not send the email");
        }
        if (unreachable.contains(userId)) {
            return EmailOutcome.UNREACHABLE;
        }
        if (rejected.contains(userId)) {
            return EmailOutcome.REJECTED;
        }
        delivered.add(deliveryId);
        sent.add(new Sent(orgId, userId, deliveryId, List.copyOf(items)));
        if (failAfter.getOrDefault(userId, new AtomicInteger()).getAndDecrement() > 0) {
            throw new ApiExceptions.UpstreamUnavailable("identity-service did not answer in time");
        }
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

    /** identity-service refuses this member's emails as malformed. */
    public void reject(UUID userId) {
        rejected.add(userId);
    }

    /** The next {@code times} emails to this user fail without being sent. */
    public void failNext(UUID userId, int times) {
        failBefore.put(userId, new AtomicInteger(times));
    }

    /** The next {@code times} emails to this user are sent, but the call fails as a timeout would. */
    public void sendThenFail(UUID userId, int times) {
        failAfter.put(userId, new AtomicInteger(times));
    }
}
