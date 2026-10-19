package com.aegis.core.notifications;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import com.aegis.core.actions.ticket.ResponseStage;
import com.aegis.core.actions.ticket.RiskTicket;
import com.aegis.core.actions.ticket.RiskTicketStore;
import com.aegis.core.actions.triage.TriageQueue;
import com.aegis.core.actions.triage.TriageQueue.Item;
import com.aegis.core.matching.MatchingDtos.ComponentRef;
import com.aegis.core.matching.MatchingDtos.OwnerRef;
import com.aegis.core.matching.MatchingDtos.VulnerabilityRef;
import com.aegis.core.notifications.OwnerNotification.EmailStatus;
import com.aegis.core.notifications.OwnerNotification.ItemKind;
import com.aegis.core.notifications.OwnerNotificationStore.Claim;
import com.aegis.core.notifications.OwnerNotificationStore.ItemOwner;
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
 * in-app notification, and an email through identity-service.
 *
 * <p>An item is the triage queue's ({@link TriageQueue#assigned}): a material vulnerability or vendor outage in one
 * service. It needs a decision while it is {@link ResponseStage#NEW} (no response yet) or
 * {@link ResponseStage#IN_REVIEW} (drafted, undecided); its owners are the service's assigned owners at the time
 * (CS203-120), so a new owner is told about what is already waiting. Whoever drafted a response in review is not
 * told that it needs their decision: it needs someone else's, or, with no pull request, they file it themselves.
 * One row per owner and item ({@link #itemKey}, {@link OwnerNotificationStore#addIfAbsent}) is the record that they
 * were told.
 *
 * <p>Whatever is due for one owner goes in one email ({@link #MAX_ITEMS_PER_EMAIL} items at most), claimed under a
 * delivery id before it is sent. Until identity-service confirms it, the same email is asked for again under the
 * same id — after a failure, with growing waits ({@link #retryDelay}); after a timeout or a crash, once
 * {@link #IN_FLIGHT} has passed — and identity-service sends each delivery id once, so nobody is emailed twice about
 * the same item. An email still failing after {@link #MAX_EMAIL_ATTEMPTS} tries, about thirteen hours, is given up;
 * an owner identity-service won't email (they left the organization, say) gets the in-app notification only.
 */
@Service
public class OwnerNotifier {

    static final int MAX_EMAIL_ATTEMPTS = 6;
    /** identity-service's limit on items per email; more are sent in further emails, each item still once. */
    static final int MAX_ITEMS_PER_EMAIL = 50;
    /** How long a claimed email may stay unconfirmed before it is asked for again: longer than any call takes. */
    static final Duration IN_FLIGHT = Duration.ofMinutes(15);
    private static final Duration FIRST_RETRY = Duration.ofMinutes(5);
    private static final Duration LONGEST_RETRY = Duration.ofHours(6);
    private static final int MAX_TITLE = 500;
    private static final int MAX_SERVICE_NAME = 255;
    private static final Set<ResponseStage> NEEDS_DECISION = EnumSet.of(ResponseStage.NEW, ResponseStage.IN_REVIEW);

    private static final Logger log = LoggerFactory.getLogger(OwnerNotifier.class);

    private final TriageQueue triage;
    private final RiskTicketStore tickets;
    private final OwnerNotificationStore store;
    private final IdentityInternalApi identity;
    private final CurrentOrg currentOrg;

    public OwnerNotifier(TriageQueue triage, RiskTicketStore tickets, OwnerNotificationStore store,
            IdentityInternalApi identity, CurrentOrg currentOrg) {
        this.triage = triage;
        this.tickets = tickets;
        this.store = store;
        this.identity = identity;
        this.currentOrg = currentOrg;
    }

    /** How a run went in one organization: notifications created, and emails sent. */
    public record Run(int notified, int emailsSent) {
    }

    /** Notify the current organization's owners about what is new, then send the emails due. */
    public Run notifyOwners() {
        UUID org = currentOrg.require();
        int notified = 0;
        try {
            notified = notifyNew();
        } catch (RuntimeException failed) {
            // The items couldn't be worked out this time (the queue, say); the emails already due still go.
            log.warn("owner notifications: items not worked out this run ({}); tried again next run",
                    failed.toString());
        }
        return new Run(notified, sendEmails(org));
    }

    private int notifyNew() {
        List<Item> items = triage.assigned().stream()
                .filter(item -> item.changeSignalId() != null && NEEDS_DECISION.contains(item.stage()))
                .toList();
        if (items.isEmpty()) {
            return 0;
        }
        Map<ItemOwner, UUID> known = store.linksOf(
                items.stream().map(item -> item.service().id()).collect(Collectors.toSet()),
                items.stream().map(OwnerNotifier::itemKey).collect(Collectors.toSet()));
        Map<UUID, UUID> drafters = drafters(items);
        int notified = 0;
        for (Item item : items) {
            String key = itemKey(item);
            UUID drafter = item.stage() == ResponseStage.IN_REVIEW && item.response() != null
                    ? drafters.get(item.response().ticketId()) : null;
            for (OwnerRef owner : item.owners()) {
                if (owner.userId() == null || owner.userId().equals(drafter)) {
                    continue;
                }
                ItemOwner notification = new ItemOwner(owner.userId(), item.service().id(), key);
                UUID linked = known.get(notification);
                try {
                    if (linked == null) {
                        if (store.addIfAbsent(new OwnerNotification(UUID.randomUUID(), owner.userId(),
                                item.service().id(), key, item.changeSignalId(), kind(item), title(item),
                                cut(SensitiveText.redact(item.service().name()), MAX_SERVICE_NAME), item.score(),
                                item.tier(), null, null, EmailStatus.PENDING, null))) {
                            notified++;
                        }
                    } else if (!linked.equals(item.changeSignalId())) {
                        store.relink(notification, item.changeSignalId());
                    }
                } catch (RuntimeException failed) {
                    // One item's odd data must never keep the others, or the emails, from going out.
                    log.warn("owner notifications: an item in service {} not recorded ({}); tried again next run",
                            item.service().id(), failed.getClass().getSimpleName());
                }
            }
        }
        return notified;
    }

    /** Who drafted each response in review, by its ticket. */
    private Map<UUID, UUID> drafters(List<Item> items) {
        Set<UUID> services = items.stream()
                .filter(item -> item.stage() == ResponseStage.IN_REVIEW && item.response() != null)
                .map(item -> item.service().id())
                .collect(Collectors.toSet());
        if (services.isEmpty()) {
            return Map.of();
        }
        return tickets.findCurrent(services).stream()
                .filter(ticket -> ticket.createdBy() != null)
                .collect(Collectors.toMap(RiskTicket::id, RiskTicket::createdBy, (first, second) -> first));
    }

    /** The emails claimed earlier and still unconfirmed, then one new email for each owner with items waiting. */
    private int sendEmails(UUID org) {
        int sent = 0;
        List<UUID> due;
        try {
            due = store.deliveriesDue(Instant.now());
        } catch (RuntimeException failed) {
            log.warn("owner notifications: emails due not read ({}); tried again next run", failed.toString());
            return 0;
        }
        for (UUID delivery : due) {
            if (send(org, () -> store.reclaim(delivery, Instant.now(), Instant.now().plus(IN_FLIGHT),
                    MAX_EMAIL_ATTEMPTS))) {
                sent++;
            }
        }

        Map<UUID, List<UUID>> byOwner = new LinkedHashMap<>();
        try {
            store.pendingEmail().forEach(waiting -> byOwner.computeIfAbsent(waiting.userId(),
                    user -> new ArrayList<>()).add(waiting.id()));
        } catch (RuntimeException failed) {
            log.warn("owner notifications: emails waiting not read ({}); tried again next run", failed.toString());
        }
        for (List<UUID> waiting : byOwner.values()) {
            for (int start = 0; start < waiting.size(); start += MAX_ITEMS_PER_EMAIL) {
                List<UUID> batch = List.copyOf(
                        waiting.subList(start, Math.min(start + MAX_ITEMS_PER_EMAIL, waiting.size())));
                if (send(org, () -> store.claimForEmail(UUID.randomUUID(), batch, Instant.now().plus(IN_FLIGHT)))) {
                    sent++;
                }
            }
        }
        return sent;
    }

    /** One try of one email: true when identity-service says it is sent (now, or on an earlier try). */
    private boolean send(UUID org, Supplier<Claim> claiming) {
        try {
            Claim claim = claiming.get();
            if (claim.notifications().isEmpty()) {
                return false;
            }
            EmailOutcome outcome;
            try {
                outcome = identity.emailOwner(org, claim.notifications().getFirst().userId(), claim.deliveryId(),
                        claim.notifications().stream()
                                .map(notification -> new OwnerEmailItem(notification.title(),
                                        notification.serviceName(), notification.path()))
                                .toList());
            } catch (RuntimeException failed) {
                // It may have gone out all the same (a timeout): the next try uses the same delivery id.
                if (claim.attempt() >= MAX_EMAIL_ATTEMPTS) {
                    log.warn("owner notifications: an email to one owner failed {} times ({}); given up",
                            claim.attempt(), failed.getClass().getSimpleName());
                    store.recordEmail(claim.deliveryId(), EmailStatus.FAILED, Instant.now());
                } else {
                    Duration wait = retryDelay(claim.attempt());
                    log.warn("owner notifications: an email to one owner failed ({}); tried again in {}",
                            failed.getClass().getSimpleName(), wait);
                    store.retryAt(claim.deliveryId(), Instant.now().plus(wait));
                }
                return false;
            }
            if (outcome == EmailOutcome.REJECTED) {
                log.warn("owner notifications: identity-service refused an email to one owner; given up");
            }
            store.recordEmail(claim.deliveryId(), switch (outcome) {
                case SENT -> EmailStatus.SENT;
                case UNREACHABLE -> EmailStatus.UNREACHABLE;
                case REJECTED -> EmailStatus.FAILED;
            }, Instant.now());
            return outcome == EmailOutcome.SENT;
        } catch (RuntimeException failed) {
            // Claiming or recording failed. A claimed email stays claimed and is asked for again under the same
            // delivery id once IN_FLIGHT has passed, which identity-service never sends twice.
            log.warn("owner notifications: an email's state not recorded ({}); checked again later",
                    failed.getClass().getSimpleName());
            return false;
        }
    }

    /** The wait after an email's {@code attempt}th try failed: 5 minutes, then four times longer, up to 6 hours. */
    static Duration retryDelay(int attempt) {
        Duration delay = FIRST_RETRY;
        for (int tried = 1; tried < attempt && delay.compareTo(LONGEST_RETRY) < 0; tried++) {
            delay = delay.multipliedBy(4);
        }
        return delay.compareTo(LONGEST_RETRY) < 0 ? delay : LONGEST_RETRY;
    }

    private static ItemKind kind(Item item) {
        return item.kind() == TriageQueue.Kind.VENDOR_OUTAGE ? ItemKind.VENDOR_OUTAGE : ItemKind.VULNERABILITY;
    }

    /**
     * What makes an item the same from run to run: a vulnerable dependency by its package and the vulnerability's
     * canonical id (a CVE) — whichever feed's advisory represents it today (V21), and whichever version of the
     * package the service is on — and an outage by its incident. A more severe vulnerability becoming the one to
     * answer is a new item.
     */
    static String itemKey(Item item) {
        if (item.upgrade() != null) {
            ComponentRef component = item.upgrade().component();
            return "VULNERABILITY:" + component.ecosystem() + ":" + component.name() + ":"
                    + answered(item).orElse(String.valueOf(item.changeSignalId()));
        }
        return "VENDOR_OUTAGE:" + (item.outage() != null ? item.outage().id() : item.changeSignalId());
    }

    /**
     * What changed, in a line: the vulnerability and the package it is in, or the outage as its vendor's status page
     * words it — quoted and attributed, since it is the vendor's text, not Aegis's. Redacted like every draft.
     * identity-service removes any link from it before it goes in an email.
     */
    static String title(Item item) {
        String title;
        if (item.upgrade() != null) {
            String vulnerability = answered(item).orElse(item.upgrade().vulnerabilities().isEmpty()
                    ? "A vulnerability" : item.upgrade().vulnerabilities().getFirst().id());
            title = vulnerability + " in " + item.upgrade().component().name() + " "
                    + item.upgrade().component().version();
        } else if (item.outage() != null) {
            String vendor = item.outage().vendorName() == null || item.outage().vendorName().isBlank()
                    ? "Vendor" : item.outage().vendorName().strip();
            title = item.outage().title() == null || item.outage().title().isBlank()
                    ? vendor + " outage"
                    : vendor + " status page: \"" + item.outage().title().strip() + "\"";
        } else {
            title = "An item";
        }
        return cut(SensitiveText.redact(title), MAX_TITLE);
    }

    /** The canonical id of the vulnerability the item answers. */
    private static Optional<String> answered(Item item) {
        return item.upgrade().vulnerabilities().stream()
                .filter(candidate -> item.changeSignalId().equals(candidate.changeSignalId()))
                .map(VulnerabilityRef::id)
                .findFirst();
    }

    private static String cut(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }
}
