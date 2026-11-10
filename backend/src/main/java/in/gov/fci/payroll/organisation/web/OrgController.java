package in.gov.fci.payroll.organisation.web;

import static in.gov.fci.payroll.platform.web.Responses.revalidated;

import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.organisation.business.model.OrgZone;
import java.util.Comparator;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** The organisation lookups the administration forms (A-03, A-04) need. */
@RestController
@Profile("online")
public class OrgController {

    public record LocationRow(
            Long id,
            String code,
            String name,
            String type,
            Long parentId,
            Long zoneId,
            String label) {}

    public record ZoneRow(Long id, String code, String name) {}

    public record OrgView(List<ZoneRow> zones, List<LocationRow> locations) {}

    private final OrgDirectory org;

    public OrgController(OrgDirectory org) {
        this.org = org;
    }

    @GetMapping("/v1/admin/org")
    @PreAuthorize("@authz.gateEither('A-03','VIEW','A-04','VIEW')")
    public ResponseEntity<OrgView> org(
            @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {
        // The whole hierarchy, the same for every administrator: revalidated, not sent again.
        return revalidated("\"org-" + org.version() + "\"", ifNoneMatch, this::view);
    }

    private OrgView view() {
        OrgDirectory.Snapshot snapshot = org.snapshot();
        List<ZoneRow> zones =
                snapshot.zones().values().stream()
                        .sorted(Comparator.comparing(OrgZone::getCode))
                        .map(z -> new ZoneRow(z.getId(), z.getCode(), z.getName()))
                        .toList();
        List<LocationRow> locations =
                org.locations(org.all()).stream()
                        .sorted(
                                Comparator.comparing(OrgLocation::getType)
                                        .thenComparing(OrgLocation::getCode))
                        .map(
                                l ->
                                        new LocationRow(
                                                l.getId(),
                                                l.getCode(),
                                                l.getName(),
                                                l.getType().name(),
                                                l.getParentId(),
                                                l.getZoneId(),
                                                l.displayLabel()))
                        .toList();
        return new OrgView(zones, locations);
    }
}
