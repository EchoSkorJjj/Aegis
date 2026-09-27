package com.aegis.core.inventory;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.aegis.core.inventory.DependencyGraph.Use;
import com.aegis.core.inventory.InventoryDtos.BusinessServiceResponse;
import com.aegis.core.inventory.InventoryDtos.OwnerResponse;
import com.aegis.core.inventory.InventoryDtos.PackageUsageResponse;
import com.aegis.core.inventory.InventoryDtos.PackageUseResponse;
import com.aegis.core.inventory.InventoryDtos.SoftwareComponentResponse;
import com.aegis.core.inventory.InventoryDtos.ThirdPartyVendorResponse;
import com.aegis.core.inventory.InventoryDtos.UsingServiceResponse;
import com.aegis.core.inventory.InventoryDtos.VendorUsageResponse;
import com.aegis.core.inventory.domain.BusinessService;
import com.aegis.core.inventory.domain.Owner;
import com.aegis.core.inventory.domain.SoftwareComponent;
import com.aegis.core.inventory.domain.ThirdPartyVendor;
import com.aegis.core.platform.common.ApiExceptions;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * "Used by": who uses a package, any version of it, and how — directly, or through which of its dependencies — and
 * who uses a vendor. A package's uses come from {@link DependencyGraph}, the walk a change's impact is worked out
 * with, so this lookup and a vulnerability's impact never disagree.
 */
@Service
public class UsedByService {

    private static final Pattern VERSION_PART = Pattern.compile("\\d+|\\D+");

    private final InventoryStore store;
    private final DependencyGraph graph;

    public UsedByService(InventoryStore store, DependencyGraph graph) {
        this.store = store;
        this.graph = graph;
    }

    /**
     * Every service using any version of the package, one row per service and version it uses, by service then
     * version. A version a service reaches several ways is shown the shortest way; a direct use wins.
     */
    @Transactional(readOnly = true)
    public PackageUsageResponse packageUsage(String ecosystem, String name) {
        if (ecosystem == null || ecosystem.isBlank() || name == null || name.isBlank()) {
            throw new ApiExceptions.BadRequest("Name the package: its ecosystem and name");
        }
        List<SoftwareComponent> versions = store.findSoftwareComponentsByPackage(ecosystem.strip(), name.strip());
        if (versions.isEmpty()) {
            return new PackageUsageResponse(ecosystem.strip(), name.strip(), List.of());
        }
        Map<UUID, SoftwareComponent> byId = new LinkedHashMap<>();
        versions.forEach(version -> byId.put(version.getId(), version));

        // One use per service and version: the shortest.
        Map<List<UUID>, Use> chosen = new LinkedHashMap<>();
        graph.usesOf(byId.keySet()).forEach((versionId, uses) -> uses.forEach(use -> chosen.merge(
                List.of(use.serviceId(), versionId), use,
                (kept, other) -> other.path().size() < kept.path().size() ? other : kept)));
        SoftwareComponent first = versions.getFirst();
        if (chosen.isEmpty()) {
            return new PackageUsageResponse(first.getEcosystem(), first.getName(), List.of());
        }

        Set<UUID> serviceIds = new LinkedHashSet<>();
        Set<UUID> through = new LinkedHashSet<>();
        chosen.values().forEach(use -> {
            serviceIds.add(use.serviceId());
            through.addAll(use.path().subList(1, use.path().size()));
        });
        Map<UUID, BusinessService> services = store.findBusinessServicesByIds(serviceIds);
        Map<UUID, List<Owner>> owners = store.findOwnersForServices(serviceIds);
        Map<UUID, SoftwareComponent> components = through.isEmpty() ? Map.of() : store.findSoftwareComponentsByIds(through);

        List<PackageUseResponse> rows = new ArrayList<>();
        chosen.forEach((key, use) -> {
            BusinessService service = services.get(use.serviceId());
            if (service == null) {
                return;
            }
            // The path runs from this version up to the linked component; shown from the linked one down.
            List<SoftwareComponentResponse> chain = use.path().subList(1, use.path().size()).reversed().stream()
                    .map(components::get)
                    .filter(Objects::nonNull)
                    .map(SoftwareComponentResponse::from)
                    .toList();
            rows.add(new PackageUseResponse(BusinessServiceResponse.from(service), owners(owners.get(service.getId())),
                    SoftwareComponentResponse.from(byId.get(key.get(1))), use.direct(), chain));
        });
        rows.sort(Comparator
                .comparing((PackageUseResponse row) -> row.service().name(), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(row -> row.service().id())
                .thenComparing(row -> row.component().version(), UsedByService::compareVersions)
                .thenComparing(row -> row.component().id()));
        return new PackageUsageResponse(first.getEcosystem(), first.getName(), rows);
    }

    /** Every service linking the vendor — vendors are linked directly — by name, with their owners. */
    @Transactional(readOnly = true)
    public VendorUsageResponse vendorUsage(UUID vendorId) {
        ThirdPartyVendor vendor = store.findThirdPartyVendor(vendorId)
                .orElseThrow(() -> new ApiExceptions.NotFound("Third-party vendor not found: " + vendorId));
        List<UUID> serviceIds = store.findServiceIdsForVendor(vendorId);
        if (serviceIds.isEmpty()) {
            return new VendorUsageResponse(ThirdPartyVendorResponse.from(vendor), List.of());
        }
        Map<UUID, BusinessService> services = store.findBusinessServicesByIds(serviceIds);
        Map<UUID, List<Owner>> owners = store.findOwnersForServices(serviceIds);
        List<UsingServiceResponse> using = serviceIds.stream()
                .map(services::get)
                .filter(Objects::nonNull)
                .map(service -> new UsingServiceResponse(BusinessServiceResponse.from(service),
                        owners(owners.get(service.getId()))))
                .sorted(Comparator.comparing(response -> response.service().name(), String.CASE_INSENSITIVE_ORDER))
                .toList();
        return new VendorUsageResponse(ThirdPartyVendorResponse.from(vendor), using);
    }

    private static List<OwnerResponse> owners(List<Owner> owners) {
        return owners == null ? List.of() : owners.stream().map(OwnerResponse::from).toList();
    }

    /** Versions in their natural order — 1.5.0 before 1.18.0 — number by number, the rest ignoring case. */
    static int compareVersions(String left, String right) {
        List<String> a = versionParts(left);
        List<String> b = versionParts(right);
        for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
            String x = a.get(i);
            String y = b.get(i);
            int order = Character.isDigit(x.charAt(0)) && Character.isDigit(y.charAt(0))
                    ? new BigInteger(x).compareTo(new BigInteger(y))
                    : x.compareToIgnoreCase(y);
            if (order != 0) {
                return order;
            }
        }
        return Integer.compare(a.size(), b.size());
    }

    private static List<String> versionParts(String version) {
        List<String> parts = new ArrayList<>();
        Matcher part = VERSION_PART.matcher(version == null ? "" : version);
        while (part.find()) {
            parts.add(part.group());
        }
        return parts;
    }
}
