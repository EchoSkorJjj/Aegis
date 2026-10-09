package com.usermgmt.identity.internal;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.usermgmt.identity.auth.email.EmailSender;
import com.usermgmt.identity.auth.email.OwnerNotification;
import com.usermgmt.identity.common.ApiExceptions;
import com.usermgmt.identity.org.MembershipStatus;
import com.usermgmt.identity.org.Organization;
import com.usermgmt.identity.org.OrganizationMemberRepository;
import com.usermgmt.identity.org.OrganizationRepository;
import com.usermgmt.identity.user.User;
import com.usermgmt.identity.user.UserRepository;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What aegis-core needs from identity-service when no user is asking (its scheduled owner notifications,
 * E9 story 5): the organizations to look at, and an email to a service owner. Behind the service token
 * ({@link InternalSecurityConfig}); never routed by the gateway.
 *
 * <p>The email goes only to an enabled user with a verified address who is an active member of the named
 * organization — whoever aegis-core names. Its wording is identity-service's ({@link EmailSender}); aegis-core
 * sends only the items, each pointing at a path in the app, never a link of its own.
 */
@RestController
@RequestMapping("/internal")
public class InternalController {

    /** A path in the frontend, such as {@code /findings/1b2c…}: no scheme, host, query or fragment. */
    static final String APP_PATH = "^/[A-Za-z0-9/_-]{0,200}$";

    private final OrganizationRepository organizations;
    private final OrganizationMemberRepository members;
    private final UserRepository users;
    private final EmailSender emailSender;

    public InternalController(OrganizationRepository organizations, OrganizationMemberRepository members,
            UserRepository users, EmailSender emailSender) {
        this.organizations = organizations;
        this.members = members;
        this.users = users;
        this.emailSender = emailSender;
    }

    public record OwnerEmailRequest(
            @NotNull UUID orgId,
            @NotNull UUID userId,
            @NotEmpty @Size(max = 50) List<@Valid @NotNull Item> items) {
    }

    public record Item(
            @NotBlank @Size(max = 500) String title,
            @NotBlank @Size(max = 255) String serviceName,
            @NotBlank @Pattern(regexp = APP_PATH) String path) {
    }

    @GetMapping("/organizations")
    @Operation(summary = "Every organization's id, for aegis-core's scheduled work")
    public List<UUID> organizations() {
        return organizations.findAll().stream().map(Organization::getId).toList();
    }

    @PostMapping("/notifications/owner-email")
    @Transactional(readOnly = true)
    @Operation(summary = "Email a service owner the items that need their decision: 204 when sent, 404 when they "
            + "are not an active member of the organization, 409 when their account can't be emailed")
    public ResponseEntity<Void> emailOwner(@Valid @RequestBody OwnerEmailRequest request) {
        members.findByOrgIdAndUserId(request.orgId(), request.userId())
                .filter(member -> member.getStatus() == MembershipStatus.ACTIVE)
                .orElseThrow(() -> new ApiExceptions.NotFound("Not an active member of this organization"));
        User user = users.findById(request.userId())
                .orElseThrow(() -> new ApiExceptions.NotFound("Not an active member of this organization"));
        if (!user.isEnabled() || !user.isEmailVerified()) {
            throw new ApiExceptions.Conflict("This account can't be emailed: it is disabled or its address is unverified");
        }
        emailSender.sendOwnerNotification(user.getEmail(), new OwnerNotification(request.items().stream()
                .map(item -> new OwnerNotification.Item(item.title(), item.serviceName(), item.path()))
                .toList()));
        return ResponseEntity.noContent().build();
    }
}
