package com.aegis.core.actions.pr.github;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import com.aegis.core.actions.pr.github.GitHubConnectionStore.Connection;
import com.aegis.core.platform.common.ApiExceptions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Connect GitHub for the caller's organization. Anyone with a role in it may see what is connected; only its
 * organization admin connects or disconnects, since a connection lets Aegis read that account's repositories
 * and open draft pull requests on them. When the GitHub App is not enabled on this deployment the status says
 * so and every change is a conflict.
 */
@RestController
@RequestMapping("/api/integrations/github")
@SecurityRequirement(name = "bearer-jwt")
public class GitHubIntegrationController {

    static final String READ = "hasAnyRole('ORG_ADMIN','SERVICE_OWNER','ANALYST','COMPLIANCE_ADMIN')";
    static final String MANAGE = "hasRole('ORG_ADMIN')";

    private final ObjectProvider<GitHubConnections> github;

    GitHubIntegrationController(ObjectProvider<GitHubConnections> github) {
        this.github = github;
    }

    /** Whether GitHub is enabled here, where to install the App, and the installations this organization connected. */
    public record Status(boolean enabled, String installUrl, List<Connection> connections) {
    }

    public record Authorization(String authorizeUrl) {
    }

    public record ConnectRequest(@NotBlank @Size(max = 512) String code, @NotBlank @Size(max = 512) String state) {
    }

    public record Skipped(String accountLogin, String reason) {
    }

    public record Connected(List<Connection> connected, List<Skipped> skipped) {
    }

    @GetMapping
    @PreAuthorize(READ)
    @Operation(summary = "Whether GitHub is enabled, and the GitHub App installations this organization has connected")
    public Status status() {
        GitHubConnections connections = github.getIfAvailable();
        return connections == null ? new Status(false, null, List.of())
                : new Status(true, connections.installUrl(), connections.connections());
    }

    @PostMapping("/authorizations")
    @PreAuthorize(MANAGE)
    @Operation(summary = "Start connecting: the GitHub sign-in to send the organization admin to")
    public Authorization start(Authentication authentication) {
        return new Authorization(enabled().start(callerId(authentication)).toString());
    }

    @PostMapping("/connections")
    @PreAuthorize(MANAGE)
    @Operation(summary = "Finish connecting with the sign-in GitHub sent back: connects every installation the admin controls")
    public Connected connect(@Valid @RequestBody ConnectRequest request, Authentication authentication) {
        GitHubConnections.Completed completed = enabled().complete(callerId(authentication), request.code(), request.state());
        return new Connected(completed.connected(), completed.skipped().stream()
                .map(skipped -> new Skipped(skipped.accountLogin(), skipped.reason())).toList());
    }

    @DeleteMapping("/connections/{installationId}")
    @PreAuthorize(MANAGE)
    @Operation(summary = "Disconnect an installation; the App stays installed on GitHub until uninstalled there")
    public ResponseEntity<Void> disconnect(@PathVariable long installationId) {
        enabled().disconnect(installationId);
        return ResponseEntity.noContent().build();
    }

    private GitHubConnections enabled() {
        GitHubConnections connections = github.getIfAvailable();
        if (connections == null) {
            throw new ApiExceptions.Conflict("GitHub isn't enabled on this Aegis deployment");
        }
        return connections;
    }

    /** The caller's identity-service user id: the access token's subject. */
    private static UUID callerId(Authentication authentication) {
        try {
            return UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException | NullPointerException notAUser) {
            throw new ApiExceptions.Forbidden("Only a signed-in user can connect GitHub");
        }
    }
}
