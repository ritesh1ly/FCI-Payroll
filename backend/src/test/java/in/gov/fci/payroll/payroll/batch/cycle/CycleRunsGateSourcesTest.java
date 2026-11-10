package in.gov.fci.payroll.payroll.batch.cycle;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.configuration.api.NetPayFloors.Floor;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * SRS-15-13 (CYC-19): the computation puts every negative or zero net, and every net below the M-14
 * floor in force, in the quarantine queue that gate G6 reads; without a floor only the sign is
 * judged (the floor is never assumed).
 */
class CycleRunsGateSourcesTest {

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    @Test
    void SRS_15_13_aNegativeOrZeroNetIsAlwaysQuarantined() {
        assertThat(CycleRuns.quarantineReason(d("-0.01"), d("100"), Optional.empty()))
                .isEqualTo("NEGATIVE_NET");
        assertThat(CycleRuns.quarantineReason(d("0.00"), d("100"), Optional.empty()))
                .isEqualTo("ZERO_NET");
        assertThat(CycleRuns.quarantineReason(d("0.01"), d("100"), Optional.empty())).isNull();
    }

    @Test
    void SRS_16_13_aNetBelowTheFloorInForceIsQuarantinedByAmountOrPercentOfGross() {
        Optional<Floor> amount = Optional.of(new Floor("ABSOLUTE_AMOUNT", d("1500"), true));
        assertThat(CycleRuns.quarantineReason(d("1499.99"), d("40000"), amount))
                .isEqualTo("FLOOR_BREACH");
        assertThat(CycleRuns.quarantineReason(d("1500.00"), d("40000"), amount)).isNull();
        Optional<Floor> percent = Optional.of(new Floor("PERCENT_OF_GROSS", d("25"), true));
        // 25 % of 33,333.33 is 8,333.3325, compared at the paisa: 8,333.33.
        assertThat(CycleRuns.quarantineReason(d("8333.32"), d("33333.33"), percent))
                .isEqualTo("FLOOR_BREACH");
        assertThat(CycleRuns.quarantineReason(d("8333.33"), d("33333.33"), percent)).isNull();
        Optional<Floor> none = Optional.of(new Floor("NONE", null, true));
        assertThat(CycleRuns.quarantineReason(d("1"), d("40000"), none)).isNull();
        // The sign comes first: a negative net is NEGATIVE_NET whatever the floor.
        assertThat(CycleRuns.quarantineReason(d("-5"), d("40000"), amount))
                .isEqualTo("NEGATIVE_NET");
    }
}
