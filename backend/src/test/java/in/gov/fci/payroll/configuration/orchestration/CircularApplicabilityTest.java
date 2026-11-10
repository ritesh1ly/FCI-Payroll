package in.gov.fci.payroll.configuration.orchestration;

import static in.gov.fci.payroll.configuration.business.model.CircularOrderScope.location;
import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.configuration.orchestration.CircularService.CircularView;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * SRS-G-38 home page: the circulars shown are those in force today that apply to the user's office
 * (all offices, the office itself, or an office above it whose scope includes its descendants).
 */
class CircularApplicabilityTest {

    private static final LocalDate FROM = LocalDate.of(2026, 7, 1);
    private static final LocalDate TO = LocalDate.of(2026, 12, 31);

    // The user's office (a district, 30) and the offices above it: region 20, zone 10, HQ 1.
    private static final long DISTRICT = 30L;
    private static final Set<Long> PATH = new LinkedHashSet<>(List.of(DISTRICT, 20L, 10L, 1L));

    private static CircularView circular(LocalDate from, LocalDate to) {
        return new CircularView(
                1L,
                "HQ/PAY/2026/7",
                LocalDate.of(2026, 6, 20),
                "HQ",
                1L,
                "Dearness Allowance revision",
                "PAY",
                false,
                List.of("STAFF"),
                List.of(),
                from,
                to,
                null,
                null,
                5L,
                "ACTIVE",
                null,
                9L,
                "maker",
                Instant.EPOCH,
                null,
                0);
    }

    @Test
    void SRS_G_38_aCircularIsInForceFromItsFirstToItsLastDayBothIncluded() {
        CircularView dated = circular(FROM, TO);
        assertThat(CircularService.inForce(dated, FROM.minusDays(1))).isFalse();
        assertThat(CircularService.inForce(dated, FROM)).isTrue();
        assertThat(CircularService.inForce(dated, TO)).isTrue();
        assertThat(CircularService.inForce(dated, TO.plusDays(1))).isFalse();
    }

    @Test
    void SRS_G_38_aCircularWithoutAStartOrAnEndIsOpenOnThatSide() {
        assertThat(CircularService.inForce(circular(null, null), FROM)).isTrue();
        assertThat(CircularService.inForce(circular(FROM, null), LocalDate.of(2099, 1, 1)))
                .isTrue();
        assertThat(CircularService.inForce(circular(null, TO), LocalDate.of(2000, 1, 1))).isTrue();
        assertThat(CircularService.inForce(circular(null, TO), TO.plusDays(1))).isFalse();
    }

    @Test
    void SRS_G_38_anOfficeScopeAppliesAtThatOffice() {
        assertThat(CircularService.applies(List.of(location(1L, DISTRICT, false)), DISTRICT, PATH))
                .isTrue();
    }

    @Test
    void SRS_G_38_anOfficeAboveAppliesOnlyWhenItsScopeIncludesItsDescendants() {
        assertThat(CircularService.applies(List.of(location(1L, 20L, true)), DISTRICT, PATH))
                .isTrue();
        assertThat(CircularService.applies(List.of(location(1L, 20L, false)), DISTRICT, PATH))
                .isFalse();
        // Headquarters with its descendants reaches every office.
        assertThat(CircularService.applies(List.of(location(1L, 1L, true)), DISTRICT, PATH))
                .isTrue();
    }

    @Test
    void SRS_G_38_anotherOfficesSubtreeOrNoScopeDoesNotApply() {
        assertThat(CircularService.applies(List.of(location(1L, 40L, true)), DISTRICT, PATH))
                .isFalse();
        assertThat(CircularService.applies(List.of(), DISTRICT, PATH)).isFalse();
        // One applying scope among several is enough.
        assertThat(
                        CircularService.applies(
                                List.of(location(1L, 40L, false), location(1L, 10L, true)),
                                DISTRICT,
                                PATH))
                .isTrue();
    }
}
