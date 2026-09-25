package com.aegis.core.actions.pr.github;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import com.aegis.core.actions.pr.PrProvider;
import com.aegis.core.actions.pr.PullRequestDraft;
import com.aegis.core.actions.pr.PullRequestRef;
import com.aegis.core.actions.pr.RepositoryRef;
import com.aegis.core.platform.common.ApiExceptions;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.HttpMethod;

/** Spike adapter. Only a future approval-gated executor may call this port. */
final class GitHubAppPrProvider implements PrProvider {
    private final GitHubApi api;
    private final GitHubInstallationTokens tokens;
    private final Set<String> hosts;

    GitHubAppPrProvider(GitHubApi api, GitHubInstallationTokens tokens, Set<String> hosts) {
        this.api = api;
        this.tokens = tokens;
        this.hosts = hosts;
    }

    @Override
    public String id() {
        return "github";
    }

    @Override
    public boolean supports(RepositoryRef repository) {
        return repository != null && hosts.contains(repository.host()) && repository.path().split("/").length == 2;
    }

    @Override
    public PullRequestRef openDraftPullRequest(PullRequestDraft draft) {
        if (draft == null || !supports(draft.repository())) {
            throw new ApiExceptions.Conflict("GitHub provider does not support this repository");
        }
        String root = "repos/" + draft.repository().path();
        String token = tokens.forRepository(draft.repository().path());
        String base = draft.baseBranch();
        if (base == null) {
            base = api.get(root, token, Repository.class).defaultBranch();
            // Reuse the proposal's branch validation on the untrusted default branch response.
            try {
                new PullRequestDraft(draft.repository(), base, draft.headBranch(), draft.title(),
                        draft.body(), draft.commitMessage(), draft.changes());
            } catch (ApiExceptions.BadRequest ex) {
                throw new ApiExceptions.Conflict("GitHub default branch must be safe and distinct from the proposed head");
            }
            if (base == null || base.isBlank()) {
                throw GitHubApi.invalidResponse();
            }
        }
        var ref = api.get(root + "/git/ref/heads/" + base, token, GitRef.class);
        if (ref.object() == null || !validSha(ref.object().sha())) {
            throw GitHubApi.invalidResponse();
        }
        api.post(root + "/git/refs", token,
                Map.of("ref", "refs/heads/" + draft.headBranch(), "sha", ref.object().sha()), Void.class);
        // The head branch now exists and is this call's own. If anything below fails, delete it so the
        // same draft can be retried rather than hitting "reference already exists" forever.
        try {
            for (var change : draft.changes()) {
                String path = root + "/contents/" + change.path();
                // Untyped on purpose: a directory answers with a JSON array, which is a wrong target
                // (Conflict below), not a malformed GitHub response.
                Object existing = api.call(HttpMethod.GET, api.uri(path, draft.headBranch()), token, null,
                        Object.class, true, "GitHub file not found");
                var body = new HashMap<String, Object>();
                body.put("message", draft.commitMessage());
                body.put("content", Base64.getEncoder().encodeToString(change.content().getBytes(StandardCharsets.UTF_8)));
                body.put("branch", draft.headBranch());
                if (existing != null) {
                    if (!(existing instanceof Map<?, ?> file) || !"file".equals(file.get("type"))
                            || !(file.get("sha") instanceof String sha) || !validSha(sha)) {
                        throw new ApiExceptions.Conflict("GitHub target must be a regular file with a valid blob SHA");
                    }
                    body.put("sha", sha);
                }
                api.call(HttpMethod.PUT, api.uri(path, null), token, body, Void.class, false, "GitHub repository not found");
            }
            var pr = api.post(root + "/pulls", token, Map.of("title", draft.title(), "head", draft.headBranch(),
                    "base", base, "body", draft.body(), "draft", true), PullRequest.class);
            if (!validPullRequestUrl(pr, draft.repository())) {
                throw GitHubApi.invalidResponse();
            }
            return new PullRequestRef(id(), pr.htmlUrl(), Long.toString(pr.number()), draft.headBranch());
        } catch (RuntimeException failure) {
            deleteHeadBranch(root, token, draft.headBranch(), failure);
            throw failure;
        }
    }

    /**
     * Best-effort removal of the head branch this call created. A pull request that was opened but
     * whose response was then lost or rejected is closed by GitHub along with its branch. A cleanup
     * failure never replaces the original error.
     */
    private void deleteHeadBranch(String root, String token, String headBranch, RuntimeException failure) {
        try {
            api.call(HttpMethod.DELETE, api.uri(root + "/git/refs/heads/" + headBranch, null), token, null,
                    Void.class, true, "GitHub branch not found");
        } catch (RuntimeException cleanup) {
            failure.addSuppressed(cleanup);
        }
    }

    private static boolean validSha(String sha) {
        return sha != null && sha.matches("[a-fA-F0-9]{40}");
    }

    private static boolean validPullRequestUrl(PullRequest pr, RepositoryRef repository) {
        if (pr.number() <= 0 || pr.htmlUrl() == null) {
            return false;
        }
        try {
            var uri = URI.create(pr.htmlUrl());
            // GitHub canonicalizes owner/repository casing in its response.
            return "https".equals(uri.getScheme()) && repository.host().equalsIgnoreCase(uri.getHost())
                    && uri.getUserInfo() == null && uri.getPort() == -1
                    && uri.getQuery() == null && uri.getFragment() == null
                    && ("/" + repository.path() + "/pull/" + pr.number()).equalsIgnoreCase(uri.getRawPath());
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Repository(@JsonProperty("default_branch") String defaultBranch) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GitRef(GitObject object) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GitObject(String sha) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record PullRequest(@JsonProperty("html_url") String htmlUrl, long number) { }
}
