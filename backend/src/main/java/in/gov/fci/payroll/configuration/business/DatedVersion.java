package in.gov.fci.payroll.configuration.business;

import java.time.LocalDate;

/**
 * A version of a master whose table has no state column (target DDL v4.0 {@code other_parameter},
 * {@code net_pay_floor_rule}, {@code allowance_ceiling_norm}, {@code operation_master}): it is in
 * force when its approval is APPROVED and its dates cover the day (SRS-16-22, SRS-G-37).
 */
public interface DatedVersion {

    Long getApprovalId();

    LocalDate getEffectiveFrom();

    LocalDate getEffectiveTo();

    /** Ends the version the day before its successor starts (versions never overlap). */
    void endBefore(LocalDate successorFrom);

    default boolean coversDay(LocalDate day) {
        return !day.isBefore(getEffectiveFrom())
                && (getEffectiveTo() == null || !day.isAfter(getEffectiveTo()));
    }
}
