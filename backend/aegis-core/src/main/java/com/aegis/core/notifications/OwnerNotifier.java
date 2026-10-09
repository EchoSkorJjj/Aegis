package com.aegis.core.notifications;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.aegis.core.actions.ticket.ResponseStage;
import com.aegis.core.actions.triage.TriageQueue;
import com.aegis.core.actions.triage.TriageQueue.Item;
import com.aegis.core.matching.MatchingDtos.OwnerRef;
import com.aegis.core.matching.MatchingDtos.VulnerabilityRef;
import com.aegis.core.notifications.OwnerNotification.EmailStatus;
import com.aegis.core.notifications.OwnerNotification.ItemKind;
import com.aegis.core.platform.common.SensitiveText;
import com.aegis.core.platform.identity.IdentityInternalApi;
import com.aegis.core.platform.identity.IdentityInternalApi.EmailOutcome;
import com.aegis.core.platform.identity.IdentityInternalApi.OwnerEmailItem;
import com.aegis.core.platform.tenant.CurrentOrg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Tells each service owner, once, about every item in their services that needs a decision (E9 story 5): an
 * in-app notification, and one email per batch through identity-service.
 *
 * <p>An item is the triage queue's ({@link TriageQueue#all}): a material vulnerability or vendor outage in one
 * service. It needs a decision while it is {@link ResponseStage#NEW} (no response yet) or
 * {@link ResponseStage#IN_REVIEW} (drafted, undecided); its owners are the service's assigned owners at the time
 * (CS203-120), so a new owner is told about what is already waiting. One row per owner and item
 * ({@link OwnerNotificationStore#addIfAbsent}) is the record that they were told; its email is claimed before it
 * is sent, so nobody is emailed twice about the same item. A failed email is tried again on the next run, up to
 * {@link #MAX_EMAIL_ATTEMPTS} times; an owner identity-service won't email (they left the organization, say) gets
 * the in-app notification only.
 */
@Service
public class OwnerNotifier {

    static final int MAX_EMAIL_ATTEMPTS = 3;
    /** identity-service's limit on items per email; more are sent in further emails, each item still once. */
    static final int MAX_ITEMS_PER_EMAIL = 50;
    private static final int MAX_TITLE = 500;
    private static final Set<ResponseStage> NEEDS_DECISION = EnumSet.of(ResponseStage.NEW, ResponseStage.IN_REVIEW);

    private static final Logger log = LoggerFactory.getLogger(OwnerNotifier.class);

    private final TriageQueue triage;
    private final OwnerNotificationStore store;
    private final IdentityInternalApi identity;
    private final CurrentOrg currentOrg;

    public OwnerNotifier(TriageQueue triage, OwnerNotificationStore store, IdentityInternalApi identity,
            CurrentOrg currentOrg) {
        this.triage = triage;
        this.store = store;
        this.identity = identity;
        this.currentOrg = currentOrg;
    }

    /** How a run went in one organization: notifications created, and emails sent. */
    public record Run(int notified, int emailsSent) {
    }

    /** Notify the current organization's owners about what is new, then send the emails still due. */
    public Run notifyOwners() {
        UUID org = currentOrg.require();
        int notified = 0;
        for (Item item : triage.all()) {
            if (item.changeSignalId() == null || !NEEDS_DECISION.contains(item.stage())) {
                continue;
            }
            String title = title(item);
            for (OwnerRef owner : item.owners()) {
                if (owner.userId() != null && store.addIfAbsent(new OwnerNotification(UUID.randomUUID(),
                        owner.userId(), item.service().id(), item.changeSignalId(), kind(item), title,
                        cut(SensitiveText.redact(item.service().name())), item.score(), item.tier(), null, null,
                        EmailStatus.PENDING, null))) {
                    notified++;
                }
            }
        }
        return new Run(notified, sendEmails(org));
    }

    /** Each owner with emails due gets them in one email (or more, past the per-email limit). */
    private int sendEmails(UUID org) {
        Map<UUID, List<OwnerNotification>> byOwner = new LinkedHashMap<>();
        for (OwnerNotification due : store.pendingEmail()) {
            byOwner.computeIfAbsent(due.userId(), user -> new ArrayList<>()).add(due);
        }
        int sent = 0;
        for (Map.Entry<UUID, List<OwnerNotification>> owner : byOwner.entrySet()) {
            List<OwnerNotification> due = owner.getValue();
            for (int start = 0; start < due.size(); start += MAX_ITEMS_PER_EMAIL) {
                List<UUID> batch = due.subList(start, Math.min(start + MAX_ITEMS_PER_EMAIL, due.size())).stream()
                        .map(OwnerNotification::id).toList();
                if (send(org, owner.getKey(), batch)) {
                    sent++;
                }
            }
        }
        return sent;
    }

    private boolean send(UUID org, UUID userId, List<UUID> batch) {
        List<OwnerNotification> claimed = store.claimForEmail(batch);
        if (claimed.isEmpty()) {
            return false;
        }
        List<UUID> ids = claimed.stream().map(OwnerNotification::id).toList();
        try {
            EmailOutcome outcome = identity.emailOwner(org, userId, claimed.stream()
                    .map(notification -> new OwnerEmailItem(notification.title(), notification.serviceName(),
                            notification.path()))
                    .toList());
            store.recordEmail(ids, outcome == EmailOutcome.SENT ? EmailStatus.SENT : EmailStatus.UNREACHABLE);
            return outcome == EmailOutcome.SENT;
        } catch (RuntimeException failed) {
            log.warn("owner notifications: an email to one owner failed ({}); tried again next run",
                    failed.getClass().getSimpleName());
            store.releaseForRetry(ids, MAX_EMAIL_ATTEMPTS);
            return false;
        }
    }

    private static ItemKind kind(Item item) {
        return item.kind() == TriageQueue.Kind.VENDOR_OUTAGE ? ItemKind.VENDOR_OUTAGE : ItemKind.VULNERABILITY;
    }

    /**
     * What changed, in a line: the vulnerability and the package it is in, or the outage and its vendor. Feed text,
     * so redacted like every draft.
     */
    static String title(Item item) {
        String title;
        if (item.upgrade() != null) {
            String vulnerability = item.upgrade().vulnerabilities().stream()
                    .filter(candidate -> item.changeSignalId().equals(candidate.changeSignalId()))
                    .map(VulnerabilityRef::id)
                    .findFirst()
                    .orElse(item.upgrade().vulnerabilities().isEmpty() ? "A vulnerability"
                            : item.upgrade().vulnerabilities().get(0).id());
            title = vulnerability + " in " + item.upgrade().component().name() + " "
                    + item.upgrade().component().version();
        } else if (item.outage() != null) {
            String outage = item.outage().title() == null || item.outage().title().isBlank()
                    ? "Vendor outage" : item.outage().title();
            title = item.outage().vendorName() == null ? outage : outage + " at " + item.outage().vendorName();
        } else {
            title = "An item";
        }
        return cut(SensitiveText.redact(title));
    }

    private static String cut(String text) {
        return text.length() <= MAX_TITLE ? text : text.substring(0, MAX_TITLE - 1) + "…";
    }
}
