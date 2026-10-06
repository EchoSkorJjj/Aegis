package com.aegis.core.actions.tracker.jira;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.aegis.core.actions.tracker.IssueTracker;
import com.aegis.core.actions.tracker.jira.JiraApi.Created;
import com.aegis.core.actions.tracker.jira.JiraApi.FieldsRejected;
import com.aegis.core.actions.tracker.jira.JiraConnectionStore.Connection;
import com.aegis.core.platform.common.ApiExceptions;

/**
 * Files approved risk tickets in the organization's connected Jira project. The description is built as
 * Atlassian Document Format text nodes, so nothing a feed or the AI wrote is ever read as markup. A site
 * whose create screen has no priority or due date still gets the ticket, without those two fields.
 */
final class JiraIssueTracker implements IssueTracker {

    /** Fields a project's create screen may leave out; the ticket is filed without them rather than refused. */
    private static final Set<String> OPTIONAL_FIELDS = Set.of("priority", "duedate");

    private final JiraApi api;
    private final JiraConnections connections;

    JiraIssueTracker(JiraApi api, JiraConnections connections) {
        this.api = api;
        this.connections = connections;
    }

    @Override
    public FiledIssue file(IssueDraft issue) {
        Connection connection = connections.connection().orElseThrow(JiraConnections::notConnected);
        if (!connection.configured()) {
            throw new ApiExceptions.Conflict("Choose the Jira project and issue type for risk tickets under "
                    + "Organization → Integrations");
        }
        Created created;
        try {
            created = create(connection, fields(connection, issue, true));
        } catch (FieldsRejected rejected) {
            if (rejected.fields().isEmpty() || !OPTIONAL_FIELDS.containsAll(rejected.fields())) {
                throw new ApiExceptions.Conflict("Jira rejected the ticket's " + String.join(", ", rejected.fields().isEmpty()
                        ? Set.of("fields") : rejected.fields()) + "; check the project's create screen in Jira");
            }
            created = create(connection, fields(connection, issue, false));
        }
        return new FiledIssue(created.key(), connection.siteUrl().replaceAll("/+$", "") + "/browse/" + created.key());
    }

    private Created create(Connection connection, Map<String, Object> fields) {
        try {
            return api.createIssue(connection.cloudId(), connections.accessToken(), fields);
        } catch (JiraApi.Unauthorized expiredEarly) {
            try {
                return api.createIssue(connection.cloudId(), connections.renewedAccessToken(), fields);
            } catch (JiraApi.Unauthorized stillRefused) {
                throw new ApiExceptions.UpstreamUnavailable(
                        "Jira refused Aegis's access; an organization admin must connect Jira again");
            }
        }
    }

    static Map<String, Object> fields(Connection connection, IssueDraft issue, boolean optional) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("project", Map.of("key", connection.projectKey()));
        fields.put("issuetype", Map.of("name", connection.issueType()));
        fields.put("summary", issue.title());
        fields.put("description", document(issue.description()));
        fields.put("labels", issue.labels());
        if (optional) {
            fields.put("priority", Map.of("name", priority(issue.priority())));
            if (issue.dueDate() != null) {
                fields.put("duedate", issue.dueDate().toString());
            }
        }
        return fields;
    }

    /** Jira Cloud's default priority names. */
    static String priority(Priority priority) {
        return switch (priority) {
            case HIGHEST -> "Highest";
            case HIGH -> "High";
            case MEDIUM -> "Medium";
            case LOW -> "Low";
            case LOWEST -> "Lowest";
        };
    }

    /** The description as an Atlassian Document Format document of plain text nodes. */
    static Map<String, Object> document(List<Block> blocks) {
        List<Object> content = new ArrayList<>();
        for (Block block : blocks) {
            switch (block.kind()) {
                case HEADING -> {
                    List<Object> text = inline(block.text());
                    if (!text.isEmpty()) {
                        content.add(Map.of("type", "heading", "attrs", Map.of("level", 3), "content", text));
                    }
                }
                case PARAGRAPH -> {
                    List<Object> text = inline(block.text());
                    if (!text.isEmpty()) {
                        content.add(Map.of("type", "paragraph", "content", text));
                    }
                }
                case BULLETS -> {
                    List<Object> items = new ArrayList<>();
                    for (String item : block.items()) {
                        List<Object> text = inline(item);
                        if (!text.isEmpty()) {
                            items.add(Map.of("type", "listItem",
                                    "content", List.of(Map.of("type", "paragraph", "content", text))));
                        }
                    }
                    if (!items.isEmpty()) {
                        content.add(Map.of("type", "bulletList", "content", items));
                    }
                }
            }
        }
        return Map.of("type", "doc", "version", 1, "content", content);
    }

    /** Text nodes with line breaks between the lines; ADF refuses an empty text node. */
    private static List<Object> inline(String text) {
        List<Object> nodes = new ArrayList<>();
        if (text == null) {
            return nodes;
        }
        for (String line : text.strip().split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            if (!nodes.isEmpty()) {
                nodes.add(Map.of("type", "hardBreak"));
            }
            nodes.add(Map.of("type", "text", "text", line));
        }
        return nodes;
    }
}
