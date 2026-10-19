package com.aegis.core.actions.tracker.jira;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import com.aegis.core.actions.tracker.jira.JiraApi.Project;
import com.aegis.core.actions.tracker.jira.JiraConnectionStore.Connection;
import com.aegis.core.platform.common.ApiExceptions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Connect Jira for the caller's organization. Anyone with a role in it may see where its risk tickets go;
 * only its organization admin connects, chooses the project or disconnects, since the connection files
 * issues in the organization's Jira. When Jira is not enabled on this deployment the status says so and
 * every change is a conflict.
 */
@RestController
@RequestMapping("/api/integrations/jira")
@SecurityRequirement(name = "bearer-jwt")
public class JiraIntegrationController {

    static final String READ = "hasAnyRole('ORG_ADMIN','SERVICE_OWNER','ANALYST','COMPLIANCE_ADMIN')";
    static final String MANAGE = "hasRole('ORG_ADMIN')";

    private final ObjectProvider<JiraConnections> jira;

    JiraIntegrationController(ObjectProvider<JiraConnections> jira) {
        this.jira = jira;
    }

    /** Whether Jira is enabled, and — when connected — the site, where tickets go, and who connected it when. */
    public record Status(boolean enabled, boolean connected, String siteName, String siteUrl, String projectKey,
                         String issueType, UUID connectedBy, Instant connectedAt) {

        static Status of(Connection connection) {
            return new Status(true, true, connection.siteName(), connection.siteUrl(), connection.projectKey(),
                    connection.issueType(), connection.connectedBy(), connection.connectedAt());
        }
    }

    public record Authorization(String authorizeUrl) {
    }

    public record ConnectRequest(@NotBlank @Size(max = 2048) String code, @NotBlank @Size(max = 512) String state) {
    }

    public record Site(String cloudId, String name, String url) {
    }

    public record ProjectOption(String key, String name) {
    }

    public record Options(List<Site> sites, String cloudId, List<ProjectOption> projects, List<String> issueTypes) {
    }

    public record SettingsRequest(
            @NotBlank @Size(max = 64) String cloudId,
            @NotBlank @Size(max = 255) String projectKey,
            @NotBlank @Size(max = 255) String issueType) {
    }

    @GetMapping
    @PreAuthorize(READ)
    @Operation(summary = "Whether Jira is enabled and connected, and where this organization's risk tickets go")
    public Status status() {
        JiraConnections connections = jira.getIfAvailable();
        if (connections == null) {
            return new Status(false, false, null, null, null, null, null, null);
        }
        return connections.connection().map(Status::of)
                .orElse(new Status(true, false, null, null, null, null, null, null));
    }

    @PostMapping("/authorizations")
    @PreAuthorize(MANAGE)
    @Operation(summary = "Start connecting: the Atlassian consent screen to send the organization admin to")
    public Authorization start(Authentication authentication) {
        return new Authorization(enabled().start(callerId(authentication)).toString());
    }

    @PostMapping("/connections")
    @PreAuthorize(MANAGE)
    @Operation(summary = "Finish connecting with what Atlassian sent back; the project is chosen next")
    public Status connect(@Valid @RequestBody ConnectRequest request, Authentication authentication) {
        return Status.of(enabled().complete(callerId(authentication), request.code(), request.state()));
    }

    @GetMapping("/options")
    @PreAuthorize(MANAGE)
    @Operation(summary = "The sites, projects and issue types risk tickets can be filed as")
    public Options options(@RequestParam(required = false) String cloudId,
            @RequestParam(required = false) String projectKey) {
        JiraConnections.Options options = enabled().options(cloudId, projectKey);
        return new Options(
                options.sites().stream().map(site -> new Site(site.cloudId(), site.name(), site.url())).toList(),
                options.cloudId(),
                options.projects().stream().map(JiraIntegrationController::option).toList(),
                options.issueTypes());
    }

    @PutMapping("/settings")
    @PreAuthorize(MANAGE)
    @Operation(summary = "Choose the site, project and issue type risk tickets are filed as")
    public Status configure(@Valid @RequestBody SettingsRequest request) {
        return Status.of(enabled().configure(request.cloudId(), request.projectKey(), request.issueType()));
    }

    @DeleteMapping("/connection")
    @PreAuthorize(MANAGE)
    @Operation(summary = "Disconnect Jira; revoke Aegis in your Atlassian account settings to withdraw the grant too")
    public ResponseEntity<Void> disconnect() {
        enabled().disconnect();
        return ResponseEntity.noContent().build();
    }

    private static ProjectOption option(Project project) {
        return new ProjectOption(project.key(), project.name());
    }

    private JiraConnections enabled() {
        JiraConnections connections = jira.getIfAvailable();
        if (connections == null) {
            throw new ApiExceptions.Conflict("Jira isn't enabled on this Aegis deployment");
        }
        return connections;
    }

    /** The caller's identity-service user id: the access token's subject. */
    private static UUID callerId(Authentication authentication) {
        try {
            return UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException | NullPointerException notAUser) {
            throw new ApiExceptions.Forbidden("Only a signed-in user can connect Jira");
        }
    }
}
