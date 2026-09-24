package com.aegis.core.support;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.aegis.core.platform.identity.DirectoryUser;
import com.aegis.core.platform.identity.IdentityDirectory;

/**
 * In-memory stand-in for identity-service's member directory, registered as the primary
 * {@link IdentityDirectory} for every integration test (see {@link IdentityTestConfig}). Tests register
 * the members they need; an unregistered id is "not a member of the caller's org", exactly as the real
 * directory answers 404 for someone outside the org. Shared across the suite's one Spring context, so
 * register fresh random ids rather than relying on an empty directory.
 */
public class FakeIdentityDirectory implements IdentityDirectory {

    private final Map<UUID, DirectoryUser> members = new ConcurrentHashMap<>();

    public DirectoryUser register(DirectoryUser member) {
        members.put(member.id(), member);
        return member;
    }

    /** A fresh, enabled member holding {@code roles}, with a unique email derived from the name. */
    public DirectoryUser member(String displayName, String... roles) {
        UUID id = UUID.randomUUID();
        String email = displayName.toLowerCase().replaceAll("[^a-z0-9]+", ".") + "." + id + "@example.com";
        return register(new DirectoryUser(id, email, displayName, Set.of(roles), true));
    }

    @Override
    public Optional<DirectoryUser> findMember(UUID userId) {
        return Optional.ofNullable(members.get(userId));
    }
}
