package com.usermgmt.identity.org;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import com.usermgmt.identity.org.OrgDtos.CreateOrgRequest;
import com.usermgmt.identity.org.OrgDtos.OrgMembershipResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The caller's organizations and self-serve org creation (ADR 0001). */
@RestController
@RequestMapping("/orgs")
@SecurityRequirement(name = "bearer-jwt")
public class OrgController {

    private final OrgService orgs;

    public OrgController(OrgService orgs) {
        this.orgs = orgs;
    }

    /** The organizations the caller belongs to (drives the workspace switcher). */
    @GetMapping
    public List<OrgMembershipResponse> myOrgs(@AuthenticationPrincipal Jwt jwt) {
        return orgs.myOrganizations(UUID.fromString(jwt.getSubject()));
    }

    /** Creates an organization owned by the caller; switch to it (POST /auth/switch-org) for a scoped token. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrgMembershipResponse create(@Valid @RequestBody CreateOrgRequest req,
                                        @AuthenticationPrincipal Jwt jwt) {
        return orgs.createOrganization(UUID.fromString(jwt.getSubject()), req.name());
    }
}
