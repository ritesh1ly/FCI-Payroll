package in.gov.fci.payroll.platform.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** A row count read through the nullable JDBC API: zero for no value, the value otherwise. */
class RowCountsTest {

    @Test
    void aMissingCountIsZeroAndAnyNumberIsItsLongValue() {
        assertThat(RowCounts.of(null)).isZero();
        assertThat(RowCounts.of(42L)).isEqualTo(42L);
        assertThat(RowCounts.of(7)).isEqualTo(7L);
    }
}
