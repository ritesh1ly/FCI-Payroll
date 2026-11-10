package in.gov.fci.payroll.organisation.business.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * SRS-G-29: an office counts for data scope and login only while it is active and inside its
 * effective period, both ends included; an open end date never expires.
 */
class OrgLocationTest {

    private static final LocalDate FROM = LocalDate.of(2026, 4, 1);
    private static final LocalDate TO = LocalDate.of(2026, 9, 30);

    private static OrgLocation office(Boolean active, LocalDate to) {
        OrgLocation location = BeanUtils.instantiateClass(OrgLocation.class);
        ReflectionTestUtils.setField(location, "active", active);
        ReflectionTestUtils.setField(location, "effectiveFrom", FROM);
        ReflectionTestUtils.setField(location, "effectiveTo", to);
        return location;
    }

    @Test
    void SRS_G_29_anActiveOfficeCountsOnBothBoundaryDaysOfItsPeriod() {
        OrgLocation location = office(true, TO);
        assertThat(location.isActiveOn(FROM)).isTrue();
        assertThat(location.isActiveOn(TO)).isTrue();
        assertThat(location.isActiveOn(FROM.minusDays(1))).isFalse();
        assertThat(location.isActiveOn(TO.plusDays(1))).isFalse();
    }

    @Test
    void SRS_G_29_anOpenEndedOfficeNeverExpires() {
        assertThat(office(true, null).isActiveOn(LocalDate.of(2090, 1, 1))).isTrue();
    }

    @Test
    void SRS_G_29_anInactiveOrUnflaggedOfficeNeverCounts() {
        assertThat(office(false, null).isActiveOn(FROM)).isFalse();
        assertThat(office(null, null).isActiveOn(FROM)).isFalse();
    }

    @Test
    void SRS_G_29_anOfficeIsShownByItsTypeNameAndCodeAsOnTheLoginPage() {
        OrgLocation location = office(true, null);
        ReflectionTestUtils.setField(location, "type", LocationType.DO);
        ReflectionTestUtils.setField(location, "name", "Delhi HQ");
        ReflectionTestUtils.setField(location, "code", "DH001");
        assertThat(location.displayLabel()).isEqualTo("DO — Delhi HQ (DH001)");
    }
}
