package com.aegis.core.inventory.demo;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.UUID;

import com.aegis.core.inventory.InventoryService;
import com.aegis.core.inventory.InventoryStore;
import com.aegis.core.inventory.domain.BusinessCriticality;
import com.aegis.core.inventory.domain.BusinessService;
import com.aegis.core.inventory.domain.ServiceExposure;
import com.aegis.core.inventory.domain.ThirdPartyVendor;
import com.aegis.core.inventory.sbom.CycloneDxBom;
import com.aegis.core.inventory.sbom.SbomImportService;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Loads the fictional demo estate from {@code classpath:demo/} — services, vendors, and each service's
 * CycloneDX SBOM (through the real import path). Find-or-create throughout, so it is safe to run
 * repeatedly and never duplicates or removes existing inventory.
 *
 * <p>It seeds <b>no owners</b>: an owner is a real organization member holding {@code SERVICE_OWNER}
 * in identity-service, verified at assignment, and the seed runs with no user or token to verify one.
 * Invite demo users with that role and assign them from each service's page.
 */
@Service
public class DemoEstateSeeder {

    public static final String ESTATE_RESOURCE = "demo/estate.json";

    /**
     * The org the demo estate is seeded into (ADR §6). A fixed id so the seed is idempotent and tests
     * can act as this tenant; the same literal backfills pre-tenant rows in migration V8. To browse
     * the demo estate through the app, a matching organization must exist in identity-service and the
     * viewing user be a member of it.
     */
    public static final UUID DEMO_ORG_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private final InventoryService inventory;
    private final InventoryStore store;
    private final SbomImportService sboms;
    private final ObjectMapper mapper;

    public DemoEstateSeeder(
            InventoryService inventory, InventoryStore store, SbomImportService sboms, ObjectMapper mapper) {
        this.inventory = inventory;
        this.store = store;
        this.sboms = sboms;
        this.mapper = mapper;
    }

    @Transactional
    public int seed() {
        Estate estate = read(ESTATE_RESOURCE, Estate.class);
        for (ServiceSpec spec : estate.services()) {
            BusinessService service = store.findBusinessServiceByName(spec.name())
                    .orElseGet(() -> inventory.createBusinessService(spec.name(), spec.criticality(), spec.exposure()));
            for (String vendorName : spec.vendors()) {
                ThirdPartyVendor vendor = store.findThirdPartyVendorByName(vendorName)
                        .orElseGet(() -> inventory.createThirdPartyVendor(vendorName));
                if (!store.serviceVendorExists(service.getId(), vendor.getId())) {
                    store.addServiceVendor(service.getId(), vendor.getId());
                }
            }
            if (spec.sbom() != null) {
                sboms.importSbom(service.getId(), read(spec.sbom(), CycloneDxBom.class));
            }
        }
        return estate.services().size();
    }

    private <T> T read(String resource, Class<T> type) {
        try (InputStream in = new ClassPathResource(resource).getInputStream()) {
            return mapper.readValue(in, type);
        } catch (IOException ex) {
            throw new UncheckedIOException("Cannot read demo estate resource " + resource, ex);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Estate(List<ServiceSpec> services) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ServiceSpec(
            String name, BusinessCriticality criticality, ServiceExposure exposure,
            List<String> vendors, String sbom) {
        public ServiceSpec {
            vendors = vendors == null ? List.of() : vendors;
        }
    }
}
