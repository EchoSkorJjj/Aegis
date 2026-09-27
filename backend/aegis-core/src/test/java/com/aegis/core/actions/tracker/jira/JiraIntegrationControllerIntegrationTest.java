package com.aegis.core.actions.tracker.jira;

import java.util.UUID;

import com.aegis.core.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The Connect Jira API on a deployment where Jira is off (the test default): everyone with a role in the
 * organization can see that, only its organization admin may try to change anything, and every change is
 * refused with the reason. Connecting itself is {@link JiraAdapterTest}.
 */
class JiraIntegrationControllerIntegrationTest extends AbstractIntegrationTest {

    private static final String PATH = "/api/integrations/jira";

    @Test
    void anyoneInTheOrganizationSeesWhetherJiraIsEnabled() throws Exception {
        for (String role : new String[]{"ORG_ADMIN", "SERVICE_OWNER", "ANALYST", "COMPLIANCE_ADMIN"}) {
            mvc.perform(get(PATH).with(as(role)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.enabled").value(false))
                    .andExpect(jsonPath("$.connected").value(false));
        }
        mvc.perform(get(PATH).with(as("USER"))).andExpect(status().isForbidden());
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
    }

    @Test
    void onlyTheOrganizationAdminMayConnectConfigureOrDisconnect() throws Exception {
        String connect = "{\"code\":\"the-code\",\"state\":\"the-state\"}";
        String settings = "{\"cloudId\":\"cloud\",\"projectKey\":\"SEC\",\"issueType\":\"Task\"}";
        for (String role : new String[]{"ANALYST", "COMPLIANCE_ADMIN", "SERVICE_OWNER"}) {
            mvc.perform(post(PATH + "/authorizations").with(as(role))).andExpect(status().isForbidden());
            mvc.perform(post(PATH + "/connections").with(as(role)).contentType(MediaType.APPLICATION_JSON).content(connect))
                    .andExpect(status().isForbidden());
            mvc.perform(get(PATH + "/options").with(as(role))).andExpect(status().isForbidden());
            mvc.perform(put(PATH + "/settings").with(as(role)).contentType(MediaType.APPLICATION_JSON).content(settings))
                    .andExpect(status().isForbidden());
            mvc.perform(delete(PATH + "/connection").with(as(role))).andExpect(status().isForbidden());
        }

        mvc.perform(post(PATH + "/authorizations").with(as("ORG_ADMIN")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Jira isn't enabled on this Aegis deployment"));
        mvc.perform(put(PATH + "/settings").with(as("ORG_ADMIN")).contentType(MediaType.APPLICATION_JSON).content(settings))
                .andExpect(status().isConflict());
        mvc.perform(put(PATH + "/settings").with(as("ORG_ADMIN")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cloudId\":\"cloud\",\"projectKey\":\" \",\"issueType\":\"Task\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(delete(PATH + "/connection").with(as("ORG_ADMIN"))).andExpect(status().isConflict());
    }

    private static RequestPostProcessor as(String role) {
        return jwt().jwt(token -> token.subject(UUID.randomUUID().toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }
}
