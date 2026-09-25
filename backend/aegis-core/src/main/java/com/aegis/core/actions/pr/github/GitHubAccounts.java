package com.aegis.core.actions.pr.github;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import com.aegis.core.platform.common.ApiExceptions;

/**
 * The GitHub accounts the App may act on, and for which Aegis organizations ({@code allowed-accounts}).
 * The App is installed per GitHub account, not per Aegis organization, so an entry is either
 * {@code login=<organization id>} — only that organization (list it once per organization) — or a bare
 * {@code login}, usable by every organization: fine while one organization uses the deployment, and
 * reported at startup, since otherwise any organization could read and propose changes to every
 * repository the installation can reach.
 */
final class GitHubAccounts {

    private final Map<String, Set<UUID>> organizations;

    private GitHubAccounts(Map<String, Set<UUID>> organizations) {
        this.organizations = organizations;
    }

    static GitHubAccounts parse(Collection<String> entries) {
        Map<String, Set<UUID>> parsed = new HashMap<>();
        for (String entry : entries == null ? Set.<String>of() : entries) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int split = trimmed.indexOf('=');
            String login = (split < 0 ? trimmed : trimmed.substring(0, split)).trim().toLowerCase(Locale.ROOT);
            if (login.isEmpty()) {
                throw new IllegalStateException("allowed-accounts has an entry without a GitHub account: " + trimmed);
            }
            Set<UUID> bound = parsed.computeIfAbsent(login, key -> new HashSet<>());
            if (split >= 0) {
                try {
                    bound.add(UUID.fromString(trimmed.substring(split + 1).trim()));
                } catch (IllegalArgumentException notAnId) {
                    throw new IllegalStateException(
                            "allowed-accounts entry for " + login + " must bind it to an organization id (a UUID)");
                }
            }
        }
        return new GitHubAccounts(parsed.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> Set.copyOf(entry.getValue()))));
    }

    boolean isEmpty() {
        return organizations.isEmpty();
    }

    /** Accounts every organization may use. */
    Set<String> unbound() {
        return organizations.entrySet().stream().filter(entry -> entry.getValue().isEmpty())
                .map(Map.Entry::getKey).collect(Collectors.toUnmodifiableSet());
    }

    /** Refuse unless the App may act on {@code login} for the caller's organization. */
    void requireAllowed(String login, Supplier<UUID> organization) {
        Set<UUID> bound = organizations.get(login.toLowerCase(Locale.ROOT));
        if (bound == null) {
            throw new ApiExceptions.Forbidden("GitHub installation account is not in allowed-accounts");
        }
        if (!bound.isEmpty() && !bound.contains(organization.get())) {
            throw new ApiExceptions.Forbidden(
                    "the Aegis GitHub App on " + login + " is not enabled for this organization");
        }
    }
}
