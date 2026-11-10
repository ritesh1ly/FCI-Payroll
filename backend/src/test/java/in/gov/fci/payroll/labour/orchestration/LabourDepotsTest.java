package in.gov.fci.payroll.labour.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.LocationType;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.platform.security.AccessContext;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The depots of the labour screens (M-02, M-06, M-09; SRS-2-27): Headquarters sees every active
 * depot; an office its own depots and the depots under it; never an office that is not a depot or a
 * depot not active on the day; by code.
 */
class LabourDepotsTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 29);

    private final OrgDirectory org = mock(OrgDirectory.class);
    private final LabourDepots depots = new LabourDepots(org);

    private static OrgLocation location(
            long id, String code, LocationType type, long parent, boolean active) {
        OrgLocation l = mock(OrgLocation.class);
        when(l.getId()).thenReturn(id);
        when(l.getCode()).thenReturn(code);
        when(l.getType()).thenReturn(type);
        when(l.getParentId()).thenReturn(parent);
        when(l.isActiveOn(DAY)).thenReturn(active);
        return l;
    }

    private static AccessContext scope(Set<Long> offices) {
        AccessContext context = mock(AccessContext.class);
        when(context.dataScope()).thenReturn(offices);
        return context;
    }

    LabourDepotsTest() {
        Map<Long, OrgLocation> all = new LinkedHashMap<>();
        all.put(1L, location(1L, "DO-DL", LocationType.DO, 0L, true));
        all.put(2L, location(2L, "DP-B", LocationType.DEPOT, 1L, true));
        all.put(3L, location(3L, "DP-A", LocationType.DEPOT, 9L, true));
        all.put(4L, location(4L, "DP-C", LocationType.DEPOT, 1L, false));
        all.put(5L, location(5L, "DP-D", LocationType.DEPOT, 8L, true));
        when(org.snapshot())
                .thenReturn(new OrgDirectory.Snapshot(all, Map.of(), Map.of(), Map.of(), Map.of()));
    }

    @Test
    void SRS_2_27_headquartersSeesEveryActiveDepotByCode() {
        assertThat(depots.of(scope(Set.of()), true, DAY))
                .extracting(OrgLocation::getCode)
                .containsExactly("DP-A", "DP-B", "DP-D");
    }

    @Test
    void SRS_2_27_anOfficeSeesItsOwnDepotsAndTheDepotsUnderIt() {
        assertThat(depots.of(scope(Set.of(1L, 3L)), false, DAY))
                .extracting(OrgLocation::getCode)
                .containsExactly("DP-A", "DP-B");
        assertThat(depots.of(scope(Set.of()), false, DAY)).isEmpty();
    }
}
