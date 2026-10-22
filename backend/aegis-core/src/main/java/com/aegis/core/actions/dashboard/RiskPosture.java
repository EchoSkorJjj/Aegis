package com.aegis.core.actions.dashboard;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import com.aegis.core.actions.ticket.ResponseStage;
import com.aegis.core.actions.triage.TriageQueue;
import com.aegis.core.actions.triage.TriageQueue.Item;
import com.aegis.core.platform.common.ApiExceptions;
import com.aegis.core.scoring.ScoringDtos.ImpactTier;
import org.springframework.stereotype.Service;

/** Current posture is the triage queue's own material items, never a second matching or scoring pipeline. */
@Service
public class RiskPosture {

    private final TriageQueue queue;
    private final PostureSnapshotStore snapshots;

    public RiskPosture(TriageQueue queue, PostureSnapshotStore snapshots) {
        this.queue = queue;
        this.snapshots = snapshots;
    }

    public record Totals(int openItems, int criticalItems, int highItems, int newItems, int inReviewItems,
                         int approvedItems, int unassignedItems, int affectedServices, int totalServices) {
    }

    /** An item belongs to each assigned owner; an unassigned group has a null id. */
    public record Breakdown(UUID id, String name, int openItems, int criticalItems, int highItems,
                            Double topScore) {
    }

    public record Snapshot(LocalDate day, Instant capturedAt, int openItems, int criticalItems, int highItems) {
    }

    public record Dashboard(Instant asOf, Totals totals, List<Breakdown> services, List<Breakdown> owners,
                            LocalDate from, LocalDate to, List<Snapshot> trend) {
    }

    public Dashboard read(int days) {
        if (days != 7 && days != 30 && days != 90) {
            throw new ApiExceptions.BadRequest("Choose a trend of 7, 30 or 90 days");
        }
        Instant asOf = Instant.now();
        TriageQueue.Queue estate = queue.overview();
        Dashboard current = aggregate(estate, asOf);
        save(current);
        LocalDate today = asOf.atOffset(ZoneOffset.UTC).toLocalDate();
        LocalDate from = today.minusDays(days - 1L);
        return new Dashboard(asOf, current.totals(), current.services(), current.owners(), from, today,
                snapshots.between(from, today));
    }

    /** Background capture uses the same computation as a page view, without fetching history. */
    public void capture() {
        Instant asOf = Instant.now();
        save(aggregate(queue.overview(), asOf));
    }

    private void save(Dashboard current) {
        Totals totals = current.totals();
        snapshots.save(new Snapshot(current.asOf().atOffset(ZoneOffset.UTC).toLocalDate(), current.asOf(),
                totals.openItems(), totals.criticalItems(), totals.highItems()));
    }

    static Dashboard aggregate(TriageQueue.Queue estate, Instant asOf) {
        List<Item> open = estate.items().stream().filter(item -> item.stage().isOpen()).toList();
        List<Breakdown> services = estate.services().stream()
                .map(service -> breakdown(service.id(), service.name(), open.stream()
                        .filter(item -> item.service().id().equals(service.id())).toList()))
                .sorted(RANKING).toList();
        List<Breakdown> owners = new ArrayList<>(estate.owners().stream()
                .map(owner -> breakdown(owner.id(), owner.name(), open.stream()
                        .filter(item -> item.owners().stream().anyMatch(assigned -> assigned.id().equals(owner.id())))
                        .toList()))
                .toList());
        List<Item> unassigned = open.stream().filter(item -> item.owners().isEmpty()).toList();
        owners.add(breakdown(null, "Unassigned", unassigned));
        owners.sort(RANKING);
        Totals totals = new Totals(open.size(), tierCount(open, ImpactTier.CRITICAL), tierCount(open, ImpactTier.HIGH),
                stageCount(open, ResponseStage.NEW), stageCount(open, ResponseStage.IN_REVIEW),
                stageCount(open, ResponseStage.APPROVED) + stageCount(open, ResponseStage.OVERRIDDEN),
                unassigned.size(), (int) services.stream().filter(service -> service.openItems() > 0).count(),
                services.size());
        return new Dashboard(asOf, totals, services, List.copyOf(owners), null, null, List.of());
    }

    private static Breakdown breakdown(UUID id, String name, List<Item> items) {
        return new Breakdown(id, name, items.size(), tierCount(items, ImpactTier.CRITICAL),
                tierCount(items, ImpactTier.HIGH), items.stream().mapToDouble(Item::score).max().stream()
                        .boxed().findFirst().orElse(null));
    }

    private static int tierCount(List<Item> items, ImpactTier tier) {
        return (int) items.stream().filter(item -> item.tier() == tier).count();
    }

    private static int stageCount(List<Item> items, ResponseStage stage) {
        return (int) items.stream().filter(item -> item.stage() == stage).count();
    }

    private static final Comparator<Breakdown> RANKING = Comparator.comparingInt(Breakdown::openItems).reversed()
            .thenComparing(Comparator.comparingInt(Breakdown::criticalItems).reversed())
            .thenComparing(Breakdown::name, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(row -> row.id() == null ? "" : row.id().toString());
}
