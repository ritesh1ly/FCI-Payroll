package in.gov.fci.payroll.organisation.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.organisation.business.model.LocationType;
import in.gov.fci.payroll.organisation.business.model.OrgDepartment;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.organisation.business.model.OrgZone;
import in.gov.fci.payroll.organisation.persistence.OrgRepositories;
import in.gov.fci.payroll.platform.cache.CacheProperties;
import in.gov.fci.payroll.platform.cache.CacheRegions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The office hierarchy as scope resolution reads it (SRS-G-29: data scope is an office and its
 * subtree; FRS V0.5 §6.10.6: a depot or DO without its own Maker and Checker is paid in its
 * parent's cycle, SRS-15-20). Every walk is cycle-safe: a broken loop in the parent links, an
 * unknown parent or an unknown office ends it with nothing rather than looping or failing.
 */
class OrgDirectoryTest {

    private final OrgRepositories.Locations locations = mock(OrgRepositories.Locations.class);
    private final OrgRepositories.Zones zones = mock(OrgRepositories.Zones.class);
    private final OrgRepositories.Departments departments = mock(OrgRepositories.Departments.class);
    private final CacheRegions regions = mock(CacheRegions.class);
    private final List<OrgLocation> offices = new ArrayList<>();

    private OrgDirectory directory;

    private void office(
            long id, String code, LocationType type, Long parent, long zone, boolean unit) {
        OrgLocation l = mock(OrgLocation.class);
        when(l.getId()).thenReturn(id);
        when(l.getCode()).thenReturn(code);
        when(l.getType()).thenReturn(type);
        when(l.getParentId()).thenReturn(parent);
        when(l.getZoneId()).thenReturn(zone);
        when(l.isCycleUnit()).thenReturn(unit);
        offices.add(l);
    }

    @BeforeEach
    void hierarchy() {
        when(regions.properties())
                .thenReturn(new CacheProperties(Duration.ofSeconds(10), Duration.ofMinutes(15)));
        office(1, "HQ001", LocationType.HQ, null, 100, true);
        office(2, "ZO-N", LocationType.ZO, 1L, 10, false);
        office(3, "RO-DL", LocationType.RO, 2L, 10, true);
        office(4, "DL01", LocationType.DO, 3L, 10, false);
        office(5, "DP-DL01", LocationType.DEPOT, 4L, 10, false);
        // Typed HQ but under another office: not the root of the hierarchy.
        office(6, "HQ-ANNEX", LocationType.HQ, 1L, 100, false);
        // A broken loop and a dangling parent, as a bad import could leave them.
        office(7, "LOOP-A", LocationType.DO, 8L, 20, false);
        office(8, "LOOP-B", LocationType.DO, 7L, 20, false);
        office(9, "ORPHAN", LocationType.DO, 99L, 20, false);
        when(locations.findAllByOrderByCode()).thenReturn(offices);
        OrgZone north = mock(OrgZone.class);
        when(north.getId()).thenReturn(10L);
        when(zones.findAllByOrderByCode()).thenReturn(List.of(north));
        OrgDepartment payroll = mock(OrgDepartment.class);
        when(payroll.getId()).thenReturn(30L);
        when(departments.findAllByOrderByCode()).thenReturn(List.of(payroll));
        directory = new OrgDirectory(locations, zones, departments, regions);
    }

    private static List<Long> ids(java.util.Collection<OrgLocation> found) {
        return found.stream().map(OrgLocation::getId).toList();
    }

    @Test
    void SRS_G_29_anOfficesScopeIsItselfAndEveryOfficeBelowIt() {
        assertThat(directory.subtree(3L)).containsExactly(3L, 4L, 5L);
        assertThat(directory.subtree(1L)).containsExactlyInAnyOrder(1L, 2L, 3L, 4L, 5L, 6L);
        assertThat(directory.subtree(5L)).containsExactly(5L);
        assertThat(directory.subtree(null)).isEmpty();
        assertThat(directory.subtree(404L)).isEmpty();
        assertThat(directory.subtree(7L)).as("a loop ends").containsExactlyInAnyOrder(7L, 8L);
    }

    @Test
    void SRS_G_29_theNearestOfficeOfATypeIsTheOfficeItselfOrAnAncestor() {
        assertThat(directory.ancestorOrSelf(5L, LocationType.RO))
                .get()
                .extracting(OrgLocation::getId)
                .isEqualTo(3L);
        assertThat(directory.ancestorOrSelf(3L, LocationType.RO))
                .get()
                .extracting(OrgLocation::getId)
                .isEqualTo(3L);
        assertThat(directory.ancestorOrSelf(5L, LocationType.HQ))
                .get()
                .extracting(OrgLocation::getId)
                .isEqualTo(1L);
        assertThat(directory.ancestorOrSelf(1L, LocationType.RO)).as("the root has none").isEmpty();
        assertThat(directory.ancestorOrSelf(7L, LocationType.RO)).as("a loop ends").isEmpty();
        assertThat(directory.ancestorOrSelf(9L, LocationType.RO)).as("an unknown parent").isEmpty();
        assertThat(directory.ancestorOrSelf(null, LocationType.RO)).isEmpty();
        assertThat(directory.ancestorOrSelf(404L, LocationType.RO)).isEmpty();
    }

    @Test
    void SRS_15_20_aLocationIsPaidInItsOwnCycleOrItsNearestCycleUnitAbove() {
        assertThat(directory.cycleUnitOf(5L)).get().extracting(OrgLocation::getId).isEqualTo(3L);
        assertThat(directory.cycleUnitOf(3L)).get().extracting(OrgLocation::getId).isEqualTo(3L);
        assertThat(directory.cycleUnitOf(2L))
                .as("the zone runs within HQ")
                .get()
                .extracting(OrgLocation::getId)
                .isEqualTo(1L);
        assertThat(directory.cycleUnitOf(8L)).as("a loop has no unit").isEmpty();
        assertThat(directory.cycleUnitOf(null)).isEmpty();
    }

    @Test
    void SRS_G_29_headquartersZonesAndLookupsComeFromOneSnapshot() {
        assertThat(directory.headquarters())
                .get()
                .extracting(OrgLocation::getCode)
                .isEqualTo("HQ001");
        assertThat(directory.zonalOffice(10L))
                .get()
                .extracting(OrgLocation::getCode)
                .isEqualTo("ZO-N");
        assertThat(directory.zonalOffice(20L)).as("no ZO recorded").isEmpty();
        assertThat(directory.inZone(10L)).containsExactlyInAnyOrder(2L, 3L, 4L, 5L);
        assertThat(directory.all()).hasSize(9);
        assertThat(ids(directory.locations(List.of(4L, 404L, 1L)))).containsExactly(4L, 1L);
        assertThat(directory.byCode("DL01")).get().extracting(OrgLocation::getId).isEqualTo(4L);
        assertThat(directory.byCode("NOPE")).isEmpty();
        assertThat(directory.zone(10L)).isPresent();
        assertThat(directory.location(null)).isEmpty();
        assertThat(directory.location(5L)).isPresent();
        assertThat(directory.snapshot().departments()).containsKey(30L);
        // One load serves every question until the region changes.
        verify(locations, times(1)).findAllByOrderByCode();
    }

    @Test
    void SRS_G_29_anHqTypedOfficeUnderAnotherOfficeIsNotTheHeadquarters() {
        // Only the annex is left: typed HQ, but it has a parent, so the hierarchy has no root.
        offices.removeIf(l -> l.getId() != 6L);
        OrgDirectory annexOnly = new OrgDirectory(locations, zones, departments, regions);
        assertThat(annexOnly.headquarters()).isEmpty();
        assertThat(annexOnly.all()).containsExactly(6L);
    }

    @Test
    void SRS_G_29_aChangeOfOfficesMarksTheRegionSoEveryInstanceReloads() {
        directory.evict();
        verify(regions).changed(OrgDirectory.REGION, "organisation");
    }
}
