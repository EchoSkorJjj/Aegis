package com.aegis.core.audit;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.aegis.core.inventory.InventoryService;
import com.aegis.core.platform.common.ApiExceptions;
import com.aegis.core.platform.identity.DirectoryUser;
import com.aegis.core.platform.identity.IdentityDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

/**
 * The evidence for one service and period (E10 story 3): every record in the service's decision trail taken in
 * {@code [from, to)} — its tickets', its pull requests' and the regulator memos covering it — oldest first, each
 * with the response as it stood then, plus the names to read them by. {@link EvidenceCsv} and {@link EvidencePdf}
 * write it; both carry exactly these records, so the PDF an auditor reads and the CSV they check it against agree.
 */
@Service
public class EvidenceExport {

    /** More than this in one export is refused: a period that large is better split, and a PDF of it unreadable. */
    static final int MAX_RECORDS = 5_000;

    private static final int PAGE = 500;
    private static final Logger log = LoggerFactory.getLogger(EvidenceExport.class);

    private final DecisionRecordStore store;
    private final InventoryService inventory;
    private final IdentityDirectory directory;

    public EvidenceExport(DecisionRecordStore store, InventoryService inventory, IdentityDirectory directory) {
        this.store = store;
        this.inventory = inventory;
        this.directory = directory;
    }

    /** One service's evidence for a period: what an export holds. */
    public record Evidence(
            UUID serviceId,
            String serviceName,
            Instant from,
            Instant to,
            List<DecisionRecord> records,
            Map<UUID, String> people) {

        public Evidence {
            records = List.copyOf(records);
            people = Map.copyOf(people);
        }

        /** Who took a step, by name where the directory knows them, else by id (they may have left). */
        public String nameOf(UUID userId) {
            return people.getOrDefault(userId, "Unknown member " + userId);
        }
    }

    /**
     * Collect the service's evidence for {@code [from, to)}. Both ends are required. The service may since have been
     * removed: its records outlive it, named as they named it.
     */
    public Evidence collect(UUID serviceId, Instant from, Instant to) {
        if (serviceId == null || from == null || to == null) {
            throw new ApiExceptions.BadRequest("An evidence export needs a service (serviceId) and a period (from, to)");
        }
        DecisionTrailQuery query = new DecisionTrailQuery(serviceId, null, null, null, null, null, from, to);
        List<DecisionRecord> records = new ArrayList<>();
        for (int page = 0; ; page++) {
            Page<DecisionRecord> batch = store.findPage(query,
                    PageRequest.of(page, PAGE, Sort.by(Sort.Direction.ASC, "sequence")));
            if (batch.getTotalElements() > MAX_RECORDS) {
                throw new ApiExceptions.BadRequest("This period holds " + batch.getTotalElements()
                        + " decision records, more than one export takes (" + MAX_RECORDS + "): export a shorter one");
            }
            records.addAll(batch.getContent());
            if (!batch.hasNext()) {
                break;
            }
        }
        return new Evidence(serviceId, serviceName(serviceId, records), from, to, records, people(records));
    }

    /** The names of these people in the caller's organization (e.g. who generated an export). */
    public Map<UUID, String> namesOf(List<UUID> userIds) {
        Map<UUID, String> names = new LinkedHashMap<>();
        for (UUID userId : userIds) {
            if (names.containsKey(userId)) {
                continue;
            }
            try {
                directory.findMember(userId).map(EvidenceExport::displayName).ifPresent(name -> names.put(userId, name));
            } catch (ApiExceptions.UpstreamUnavailable unavailable) {
                // Names are for reading; the records hold the ids, which are the evidence. Export without them.
                log.warn("Evidence export without names: the member directory is unavailable ({})",
                        unavailable.getMessage());
                return names;
            }
        }
        return names;
    }

    /** The service as it's named now, or, once removed, as its latest record named it. */
    private String serviceName(UUID serviceId, List<DecisionRecord> records) {
        try {
            return inventory.getBusinessService(serviceId).getName();
        } catch (ApiExceptions.NotFound removed) {
            for (int i = records.size() - 1; i >= 0; i--) {
                if (serviceId.equals(records.get(i).serviceId()) && records.get(i).serviceName() != null) {
                    return records.get(i).serviceName();
                }
            }
            return null;
        }
    }

    /**
     * The name of each person who took a step, from the member directory as the caller sees it. A person who left
     * the organization isn't found there and is shown by id.
     */
    private Map<UUID, String> people(List<DecisionRecord> records) {
        return namesOf(records.stream().map(DecisionRecord::actorId).distinct().toList());
    }

    private static String displayName(DirectoryUser member) {
        return member.displayName() == null || member.displayName().isBlank() ? member.email() : member.displayName();
    }
}
