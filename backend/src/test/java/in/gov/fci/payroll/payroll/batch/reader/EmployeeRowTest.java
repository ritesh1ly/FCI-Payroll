package in.gov.fci.payroll.payroll.batch.reader;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** SRS-G-10, BR-CYC-012: a member as the DA arrear job reads it: its item key and stop flag. */
class EmployeeRowTest {

    @Test
    void BR_CYC_012_theItemKeyNamesTheEmployeeAndOnlyYMeansStopPayment() {
        EmployeeRow row = row("Y");
        assertThat(row.itemKey()).isEqualTo("employee:" + row.employeeId());
        assertThat(row.isStopPayment()).isTrue();
        assertThat(row("N").isStopPayment()).isFalse();
        assertThat(row(null).isStopPayment()).isFalse();
    }

    private static EmployeeRow row(String stop) {
        return new EmployeeRow(42L, 7L, new BigDecimal("30000"), stop, "IDA");
    }
}
