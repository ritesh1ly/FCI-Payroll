package in.gov.fci.payroll.payroll.business;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.organisation.business.model.LocationType;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;

/** The keys of a cycle instance (SRS-G-10): YYYYMM, the April-to-March year, the unit level. */
class CycleKeysTest {

    // BR-CYC-001: the period is keyed by year-month, per organisational instance.
    @Test
    void SRS_G_10_theFinancialYearStartsInAprilAndThePeriodKeyIsYyyymm() {
        assertThat(CycleKeys.finYear(YearMonth.of(2026, 4))).isEqualTo("2026-27");
        assertThat(CycleKeys.finYear(YearMonth.of(2027, 3))).isEqualTo("2026-27");
        assertThat(CycleKeys.finYear(YearMonth.of(2026, 1))).isEqualTo("2025-26");
        assertThat(CycleKeys.finYear(YearMonth.of(2026, 3))).isEqualTo("2025-26");
        assertThat(CycleKeys.finYear(YearMonth.of(2099, 12))).isEqualTo("2099-00");
        assertThat(CycleKeys.yymm(YearMonth.of(2026, 4))).isEqualTo("202604");
        assertThat(CycleKeys.yymm(YearMonth.of(2026, 12))).isEqualTo("202612");
    }

    @Test
    void SRS_G_10_headquartersZonesAndRegionsRunAtTheirLevelEveryOtherUnitAsADivision() {
        assertThat(CycleKeys.level(LocationType.HQ)).isEqualTo("HQ");
        assertThat(CycleKeys.level(LocationType.ZO)).isEqualTo("ZO");
        assertThat(CycleKeys.level(LocationType.RO)).isEqualTo("RO");
        for (LocationType type :
                new LocationType[] {
                    LocationType.DO, LocationType.DEPOT, LocationType.SHED, LocationType.FSD
                }) {
            assertThat(CycleKeys.level(type)).as(type.name()).isEqualTo("DO");
        }
    }

    @Test
    void SRS_G_2_aLoginIsCutToTheFiftyOfTheCycleColumns() {
        assertThat(CycleKeys.truncate(null)).isNull();
        assertThat(CycleKeys.truncate("maker")).isEqualTo("maker");
        assertThat(CycleKeys.truncate("x".repeat(50))).hasSize(50);
        assertThat(CycleKeys.truncate("x".repeat(51))).hasSize(50);
    }
}
