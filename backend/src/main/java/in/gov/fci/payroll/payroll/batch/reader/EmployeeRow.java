package in.gov.fci.payroll.payroll.batch.reader;

import in.gov.fci.payroll.jobs.batch.support.ItemKey;
import java.math.BigDecimal;

/**
 * One ACTIVE employee as the DA arrear job reads it: id, unit, current basic, stop-payment flag,
 * pay pattern.
 */
public record EmployeeRow(
        Long employeeId,
        Long locationId,
        BigDecimal currentBasic,
        String stopPayment,
        String payPattern)
        implements ItemKey {

    @Override
    public String itemKey() {
        return "employee:" + employeeId;
    }

    public boolean isStopPayment() {
        return "Y".equals(stopPayment);
    }
}
